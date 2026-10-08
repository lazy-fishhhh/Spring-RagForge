<script setup lang="ts">
import { nextTick, ref } from 'vue'
import ChatHeader from './components/ChatHeader.vue'
import MessageList from './components/MessageList.vue'
import LoginView from './views/LoginView.vue'
import { useChat } from './composables/useChat'
import { useAuth } from './composables/useAuth'

const { userId } = useAuth()
const { messages, streaming, scrollSignal, send, stop, newChat } = useChat(userId)

const draft = ref('')
const textarea = ref<HTMLTextAreaElement | null>(null)

function submit(): void {
  const text = draft.value
  draft.value = ''
  void send(text)
  void nextTick(autoResize)
}

/** 欢迎页建议提问：点一下直接作为首轮问题发送 */
function pick(prompt: string): void {
  draft.value = prompt
  submit()
}

/** Enter 发送、Shift+Enter 换行；输入法组词中的回车和流式进行中都不算发送 */
function onKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing || streaming.value) return
  event.preventDefault()
  submit()
}

/** 输入坞随内容自适应增高，封顶 180px 后内部滚动 */
function autoResize(): void {
  const el = textarea.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 180)}px`
}

/** 欢迎页建议提问卡片（纯展示文案，点击走 pick） */
const suggestions = [
  {
    title: 'Spring AOP 有哪些通知类型？',
    desc: '五种 Advice 的区别与使用场景',
    icon: 'M4 6h16M4 12h16M4 18h10',
  },
  {
    title: '如何声明一个事务？',
    desc: '@Transactional 用法与传播级别',
    icon: 'M5 7h14v10H5zM8 10h8M8 13h5',
  },
  {
    title: 'Bean 的作用域有哪几种？',
    desc: 'singleton 与 prototype 等对比',
    icon: 'M9 4h6v6H9zM4 14h6v6H4zM14 14h6v6h-6z',
  },
  {
    title: '给我一段日志切面示例代码',
    desc: '包含 @Aspect 与切入点表达式',
    icon: 'm7 8-4 4 4 4M17 8l4 4-4 4M13 5l-2 14',
  },
]
</script>

<template>
  <LoginView v-if="!userId" />
  <div v-else class="flex h-screen flex-col" style="background: var(--bg)">
    <ChatHeader :streaming="streaming" @new-chat="newChat" />

    <!-- 对话态：消息列表 -->
    <MessageList v-if="messages.length" :messages="messages" :scroll-signal="scrollSignal" />

    <!-- 空态：欢迎页，作为首屏视觉锚点 -->
    <main v-else class="welcome-in flex flex-1 items-center justify-center overflow-y-auto px-4">
      <div class="w-full max-w-[580px] py-10">
        <div class="mb-8 text-center">
          <span
            class="mx-auto mb-5 inline-flex h-11 w-11 items-center justify-center rounded-2xl text-white"
            style="background: var(--ink-strong)"
          >
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path
                d="M12 3v4M12 17v4M3 12h4M17 12h4M6.3 6.3l2.5 2.5M15.2 15.2l2.5 2.5M17.7 6.3l-2.5 2.5M8.8 15.2l-2.5 2.5"
                stroke="currentColor"
                stroke-width="1.6"
                stroke-linecap="round"
              />
            </svg>
          </span>
          <h1 class="text-[26px] font-semibold tracking-tight" style="color: var(--ink-strong)">
            你好，有什么可以帮你？
          </h1>
          <p class="mt-2 text-[14px]" style="color: var(--ink-2)">
            基于知识库内容回答问题，回答中的引用角标可直达来源原文
          </p>
        </div>

        <div class="grid gap-3 sm:grid-cols-2">
          <button
            v-for="item in suggestions"
            :key="item.title"
            type="button"
            class="suggest-tile group rounded-xl p-3.5 text-left"
            @click="pick(item.title)"
          >
            <span class="flex items-start gap-3">
              <svg
                width="16"
                height="16"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.6"
                stroke-linecap="round"
                stroke-linejoin="round"
                class="mt-0.5 shrink-0 transition-colors"
                aria-hidden="true"
              >
                <path :d="item.icon" />
              </svg>
              <span class="min-w-0">
                <span
                  class="block text-[13.5px] font-medium leading-5"
                  style="color: var(--ink)"
                >{{ item.title }}</span>
                <span class="mt-0.5 block truncate text-[12px]" style="color: var(--ink-3)">
                  {{ item.desc }}
                </span>
              </span>
            </span>
          </button>
        </div>
      </div>
    </main>

    <!-- 底部输入坞 -->
    <footer class="shrink-0 px-4 pb-4 pt-2" style="background: linear-gradient(to top, var(--bg), color-mix(in srgb, var(--bg) 70%, transparent), transparent)">
      <div class="mx-auto w-full max-w-[768px]">
        <div class="composer relative rounded-[22px] bg-white">
          <textarea
            ref="textarea"
            v-model="draft"
            rows="1"
            placeholder="给 AI 发送消息…"
            class="block max-h-[180px] w-full resize-none overflow-y-auto bg-transparent px-4 pb-9 pt-3.5 text-[15px] leading-6 outline-none"
            style="color: var(--ink)"
            @keydown="onKeydown"
            @input="autoResize"
          />
          <div class="absolute bottom-2.5 right-3">
            <button
              v-if="streaming"
              type="button"
              title="停止生成"
              class="stop-btn"
              @click="stop"
            >
              <svg width="12" height="12" viewBox="0 0 14 14" fill="currentColor" aria-hidden="true">
                <rect x="2" y="2" width="10" height="10" rx="2" />
              </svg>
            </button>
            <button
              v-else
              type="button"
              title="发送"
              class="send-btn"
              :disabled="!draft.trim()"
              @click="submit"
            >
              <svg
                width="16"
                height="16"
                viewBox="0 0 16 16"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <path d="M8 13V3" />
                <path d="M3.5 7.5 8 3l4.5 4.5" />
              </svg>
            </button>
          </div>
        </div>
        <p class="mt-2 text-center text-[11px]" style="color: var(--ink-3)">
          Enter 发送 · Shift+Enter 换行 · 内容由 AI 生成，请注意甄别
        </p>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.composer {
  border: 1px solid var(--line);
  box-shadow: 0 1px 2px rgba(29, 28, 25, 0.05);
  transition: border-color 0.15s, box-shadow 0.15s;
}

.composer:focus-within {
  border-color: color-mix(in srgb, var(--accent) 45%, var(--line));
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 12%, transparent);
}

.suggest-tile {
  border: 1px solid var(--line);
  background: var(--surface);
  transition: border-color 0.15s, background-color 0.15s, transform 0.15s;
}

.suggest-tile svg {
  color: var(--ink-3);
}

.suggest-tile:hover {
  border-color: color-mix(in srgb, var(--accent) 40%, var(--line));
  background: #fcfcfa;
  transform: translateY(-1px);
}

.suggest-tile:hover svg {
  color: var(--accent);
}

.send-btn,
.stop-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 9999px;
  cursor: pointer;
  transition: background-color 0.15s, color 0.15s;
}

.send-btn {
  background: var(--accent);
  color: #fff;
}

.send-btn:hover:not(:disabled) {
  background: var(--accent-strong);
}

.send-btn:disabled {
  background: #e9e6df;
  color: #aaa59a;
  cursor: default;
}

.stop-btn {
  background: var(--ink-strong);
  color: #fff;
}

.stop-btn:hover {
  background: #000;
}
</style>
