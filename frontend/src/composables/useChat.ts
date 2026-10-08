import { computed, nextTick, ref, type Ref } from 'vue'
import { streamChat } from '../api/chat'
import { parseSources } from '../api/sources'
import type { ChatMessage, SseEvent } from '../types'

/** 打字机节奏：每 TICK_MS 毫秒吐 CHARS_PER_TICK 个字，与旧页面一致。 */
const TICK_MS = 10
const CHARS_PER_TICK = 4

export interface UseChat {
  messages: Ref<ChatMessage[]>
  memoryId: Ref<string>
  streaming: Ref<boolean>
  /** 每次有新内容就 +1，消息列表据此滚到底（比 deep watch 整段文本便宜） */
  scrollSignal: Ref<number>
  send: (text: string) => Promise<void>
  /** 中止当前流：保留已流出的正文，正在吐字的那条 AI 消息就地收尾 */
  stop: () => void
  newChat: () => void
}

/** 生成会话 id。优先用 crypto.randomUUID，不可用时退回时间戳+随机串。 */
function newSessionId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

/**
 * 对话状态机：发请求、消费 SSE、按打字机节奏吐字。
 *
 * <p>memoryId = userId:sessionId：userId 来自登录态，sessionId 每次点「新对话」换一个，
 * 后端用它做 Redis 聊天记忆的 key。
 * <p>用 runId 而不是布尔标志来判定「这次流还算不算数」：点「新对话」时把 runId 加一，
 * 在途的流下次回调就认不出自己，事件和定时器全部哑掉，不会往已经清空的列表里写东西。
 */
export function useChat(userId: Ref<string>): UseChat {
  const messages = ref<ChatMessage[]>([])
  const sessionId = ref(newSessionId())
  const memoryId = computed(() => `${userId.value}:${sessionId.value}`)
  const streaming = ref(false)
  const scrollSignal = ref(0)

  let nextId = 1
  let runId = 0
  let abortCtrl: AbortController | null = null

  function newChat(): void {
    runId++
    abortCtrl?.abort()
    abortCtrl = null
    sessionId.value = newSessionId()
    messages.value = []
    streaming.value = false
  }

  /** 中止在途流。runId 先自增让所有回调哑掉，再 abort 请求，AbortError 会被 send 的 catch 静默吞掉。 */
  function stop(): void {
    if (!streaming.value) return
    runId++
    abortCtrl?.abort()
    abortCtrl = null
    // 正在吐字的那条 AI 消息就地收尾，保留已经流出的内容
    const last = messages.value[messages.value.length - 1]
    if (last && last.role === 'ai' && !last.finished) last.finished = true
    streaming.value = false
  }

  async function send(text: string): Promise<void> {
    const prompt = text.trim()
    if (!prompt || streaming.value) return

    const myRun = ++runId
    messages.value.push({
      id: nextId++,
      role: 'user',
      text: prompt,
      sources: [],
      finished: true,
    })
    messages.value.push({
      id: nextId++,
      role: 'ai',
      text: '',
      sources: [],
      finished: false,
    })
    const ai = messages.value[messages.value.length - 1]!
    scrollSignal.value++

    abortCtrl = new AbortController()
    streaming.value = true

    // 打字机缓冲：收到的正文先囤在这里，由定时器分批吐进 ai.text
    let pending = ''
    let streamEnded = false
    let timer: ReturnType<typeof setInterval> | null = null

    const alive = () => myRun === runId
    const stopTimer = () => {
      if (timer !== null) {
        clearInterval(timer)
        timer = null
      }
    }
    const finish = () => {
      stopTimer()
      streaming.value = false
      abortCtrl = null
      scrollSignal.value++
    }

    timer = setInterval(() => {
      if (!alive()) {
        stopTimer()
        return
      }
      if (pending !== '') {
        // 按码点切：用 slice 切会把中文和 emoji 切成半个字
        const chars = Array.from(pending)
        ai.text += chars.splice(0, CHARS_PER_TICK).join('')
        pending = chars.join('')
        scrollSignal.value++
      }
      // 缓冲打空且流已结束 → 收尾。不一次性补完，逐字节奏保持到最后
      if (pending === '' && streamEnded) {
        ai.finished = true
        finish()
      }
    }, TICK_MS)

    const onEvent = (event: SseEvent): void => {
      if (!alive()) return
      if (event.event === 'quota') {
        // 每日额度用完：后端没调模型，直接收尾并显示提示
        ai.text = ''
        ai.error = event.data
        ai.finished = true
        finish()
        return
      }
      if (event.event === 'sources') {
        ai.sources = parseSources(event.data)
      } else if (event.event === 'degraded') {
        ai.degraded = event.data
      } else {
        // 无名事件即正文 token；[DONE] 已在 streamChat 里拦掉，不会流到这里
        pending += event.data
      }
    }

    try {
      await streamChat(prompt, memoryId.value.trim(), abortCtrl.signal, onEvent)
    } catch (err) {
      if (!alive()) return // 中止来自「新对话」，这条消息早已不在列表里，无事可做
      ai.text = ''
      ai.sources = []
      ai.error = `出错了：${err instanceof Error ? err.message : String(err)}`
      ai.finished = true
      finish()
      return
    }

    if (!alive()) return
    // 流正常结束：剩下的缓冲交给定时器打完，打空后自动收尾
    streamEnded = true
    await nextTick()
  }

  return { messages, memoryId, streaming, scrollSignal, send, stop, newChat }
}
