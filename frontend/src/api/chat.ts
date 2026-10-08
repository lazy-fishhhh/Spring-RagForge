import type { SseEvent } from '../types'
import { authHeaders } from './auth'

/**
 * 解析一个 SSE 事件块（已按空行切好）。取 event 名与所有 data 行，
 * 按规范用 \n 把多行 data 拼回去。
 */
function parseEvent(chunk: string): SseEvent | null {
  const dataLines: string[] = []
  let event: string | null = null
  for (const line of chunk.split('\n')) {
    // 只去行尾的 \r（兼容 \r\n），不能动内容里的空格
    const l = line.replace(/\r$/, '')
    if (l.startsWith('event:')) {
      event = l.slice(6).trim()
    } else if (l.startsWith('data:')) {
      let value = l.slice(5)
      // 规范：冒号后紧跟的一个空格要去掉，其余空格保留
      if (value.startsWith(' ')) value = value.slice(1)
      dataLines.push(value)
    }
  }
  if (dataLines.length === 0) return null
  return { event, data: dataLines.join('\n') }
}

/**
 * 发起一次对话并消费 SSE 流。每个事件交给 onEvent，遇到 [DONE] 就停止。
 *
 * 用 fetch + ReadableStream 而不是 EventSource：这个接口是 GET 但还是想用 AbortController
 * 精确中止，且 EventSource 无法在出错时拿到状态码。
 *
 * @throws Error 非 2xx 或响应没有 body 时抛出，中止时抛 AbortError
 */
export async function streamChat(
  message: string,
  memoryId: string,
  signal: AbortSignal,
  onEvent: (event: SseEvent) => void,
): Promise<void> {
  const url = `/chat?message=${encodeURIComponent(message)}&memoryId=${encodeURIComponent(memoryId)}`
  const res = await fetch(url, {
    headers: authHeaders({ Accept: 'text/event-stream' }),
    signal,
  })
  if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`)

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  for (;;) {
    const { done, value } = await reader.read()
    // 流断了就把 buffer 当最后一块处理，否则末尾内容会丢
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    // 按空行切完整事件，剩下的留在 buffer 等下次拼
    const parts = buffer.split('\n\n')
    buffer = parts.pop() ?? ''
    for (const part of parts) {
      const event = parseEvent(part)
      if (event === null) continue
      if (event.data === '[DONE]') return
      onEvent(event)
    }
  }

  // 流结束前收到的最后一段（后面没跟空行）
  if (buffer.trim()) {
    const event = parseEvent(buffer)
    if (event && event.data !== '[DONE]') onEvent(event)
  }
}
