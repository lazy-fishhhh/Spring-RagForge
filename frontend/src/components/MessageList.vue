<script setup lang="ts">
import { nextTick, watch } from 'vue'
import type { ChatMessage } from '../types'
import MessageBubble from './MessageBubble.vue'

const props = defineProps<{ messages: ChatMessage[]; scrollSignal: number }>()

/** 距底部多少像素以内算「贴着底部」（阈值，不是状态，tick 时现量，避免事件竞态） */
const STICK_THRESHOLD = 80

function scrollToBottom(): void {
  const el = document.querySelector('main')
  if (el) el.scrollTop = el.scrollHeight
}

/** 新一轮对话（发出消息、列表项增加）时强制滚到底，保证能看到新回答 */
watch(
  () => props.messages.length,
  async () => {
    await nextTick()
    scrollToBottom()
  },
)

/**
 * 打字机每吐一次字滚一帧。必须先等 DOM 更新完再滚，否则量到的是旧高度、慢一拍。
 *
 * 只在用户本来就贴着底部时才滚：直接现量滚动位置，不依赖 scroll 事件维护的状态——
 * scroll 事件异步派发，与 10ms 的 tick 存在竞态，曾出现「上滚后仍被拉回底部」。
 * 用户上滚阅读时位置保持，滚回底部附近后自动跟随恢复。
 */
watch(
  () => props.scrollSignal,
  async () => {
    await nextTick()
    const el = document.querySelector('main')
    if (!el) return
    if (el.scrollHeight - el.scrollTop - el.clientHeight < STICK_THRESHOLD) {
      el.scrollTop = el.scrollHeight
    }
  },
)
</script>

<template>
  <main class="flex-1 overflow-y-auto">
    <div class="mx-auto flex w-full max-w-[768px] flex-col gap-8 px-4 py-8">
      <MessageBubble v-for="message in messages" :key="message.id" :message="message" />
    </div>
  </main>
</template>
