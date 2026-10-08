<script setup lang="ts">
import { computed } from 'vue'
import { renderMarkdown } from '../markdown/render'
import type { ChatMessage } from '../types'

const props = defineProps<{ message: ChatMessage }>()

/** 只有 AI 回答渲染 markdown。用户输入永远是纯文本，走模板插值，绝不 v-html */
const html = computed(() =>
  props.message.role === 'ai' ? renderMarkdown(props.message.text, props.message.sources) : '',
)

/** 正文一个字都还没吐出来：流式中显示打字动画，收尾后说明确实没内容 */
const placeholder = computed(() => {
  if (props.message.role !== 'ai' || props.message.error || props.message.text !== '') return null
  return props.message.finished ? '（无回复）' : '正在思考…'
})

const degradedText = computed(() =>
  props.message.degraded === 'model_and_retrieval_unavailable'
    ? '本条回答已降级：模型与检索服务暂时不可用。'
    : '本条回答已降级：模型暂时不可用，内容来自检索原文。',
)

/**
 * 正文是 v-html 注入的 HTML，没法在上面挂 Vue 事件；且每个打字机 tick 都会重建内部 DOM，
 * 逐个绑定也留不住。容器本身不被替换，所以交互全部走容器上的事件委托。
 */
function onProseClick(event: MouseEvent): void {
  const target = event.target
  if (!(target instanceof Element)) return

  // 引用角标：新标签打开来源原文
  const badge = target.closest('sup[data-ref]')
  if (badge) {
    const index = Number(badge.getAttribute('data-ref'))
    const source = props.message.sources.find((item) => item.index === index)
    if (source?.url) window.open(source.url, '_blank', 'noopener')
    return
  }

  // 代码块复制按钮（render.ts 输出的结构）
  const copyBtn = target.closest('button.code-copy')
  if (copyBtn instanceof HTMLButtonElement) {
    const code = copyBtn.closest('.code-block')?.querySelector('pre code')?.textContent ?? ''
    void navigator.clipboard.writeText(code).then(() => {
      copyBtn.textContent = '已复制'
      copyBtn.classList.add('copied')
      setTimeout(() => {
        copyBtn.textContent = '复制'
        copyBtn.classList.remove('copied')
      }, 1600)
    })
  }
}
</script>

<template>
  <div class="msg-in flex" :class="message.role === 'user' ? 'justify-end' : 'justify-start'">
    <!-- 用户消息：右对齐暖色气泡 -->
    <div
      v-if="message.role === 'user'"
      class="max-w-[70%] rounded-[20px] rounded-br-md px-4 py-2.5 text-[15px] leading-relaxed break-words whitespace-pre-wrap"
      style="background: var(--user-bubble); color: var(--ink)"
    >
      {{ message.text }}
    </div>

    <!-- AI 消息：无气泡直接铺底，长回答更好读 -->
    <div v-else class="w-full min-w-0">
      <!-- 请求失败：整条只显示错误，不留半截正文 -->
      <span v-if="message.error" class="text-[15px] text-red-600">{{ message.error }}</span>

      <template v-else>
        <div v-if="placeholder && !message.finished" class="typing-dots" aria-label="正在思考">
          <i></i><i></i><i></i>
        </div>
        <p v-else-if="placeholder" class="text-[15px]" style="color: var(--ink-3)">（无回复）</p>
        <div
          v-else
          class="prose max-w-none prose-p:my-2.5 prose-headings:mb-3 prose-headings:mt-5 prose-li:my-1"
          v-html="html"
          @click="onProseClick"
        />
        <div
          v-if="message.finished && message.degraded && !placeholder"
          class="mt-3 rounded-lg border-l-[3px] border-amber-400/70 bg-amber-400/10 px-3 py-2 text-[13px] text-gray-500"
        >
          {{ degradedText }}
        </div>
      </template>
    </div>
  </div>
</template>
