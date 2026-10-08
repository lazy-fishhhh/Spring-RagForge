<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { ref } from 'vue'
import { reindex } from '../api/admin'
import { useAuth } from '../composables/useAuth'

defineProps<{ streaming: boolean }>()
const emit = defineEmits<{
  'new-chat': []
}>()

const { logout } = useAuth()

/** 灌库是同步接口，可能跑几分钟，期间按钮保持 loading 免得连点 */
const reindexing = ref(false)

async function onReindex(): Promise<void> {
  reindexing.value = true
  try {
    const stats = await reindex()
    ElMessage.success({
      message:
        `灌库完成：重建=${stats.fullRebuild} 新增=${stats.added} 更新=${stats.updated}` +
        ` 删除=${stats.deleted} 跳过=${stats.unchanged} 失败=${stats.failed}（${stats.elapsedMillis}ms）`,
      duration: 8000,
      showClose: true,
    })
  } catch (err) {
    ElMessage.error({
      message: `灌库失败：${err instanceof Error ? err.message : String(err)}`,
      duration: 8000,
      showClose: true,
    })
  } finally {
    reindexing.value = false
  }
}
</script>

<template>
  <header
    class="flex h-14 shrink-0 items-center gap-3 px-5"
    style="border-bottom: 1px solid var(--line); background: color-mix(in srgb, var(--bg) 60%, white)"
  >
    <span class="flex items-center gap-2.5 text-[15px] font-semibold whitespace-nowrap" style="color: var(--ink-strong)">
      <span
        class="inline-flex h-6 w-6 items-center justify-center rounded-lg text-white"
        style="background: var(--ink-strong)"
        aria-hidden="true"
      >
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none">
          <path
            d="M12 3v4M12 17v4M3 12h4M17 12h4M6.3 6.3l2.5 2.5M15.2 15.2l2.5 2.5M17.7 6.3l-2.5 2.5M8.8 15.2l-2.5 2.5"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
          />
        </svg>
      </span>
      AI 对话
    </span>

    <div class="flex-1"></div>

    <el-button plain :disabled="streaming" @click="emit('new-chat')">新对话</el-button>
    <el-button plain :loading="reindexing" @click="onReindex">灌库</el-button>
    <el-button plain @click="logout">退出登录</el-button>
  </header>
</template>
