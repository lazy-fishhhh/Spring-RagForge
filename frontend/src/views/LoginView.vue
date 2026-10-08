<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { sendCode, login } from '../api/auth'
import { useAuth } from '../composables/useAuth'

/** QQ 邮箱格式：本地部分 + @qq.com，不区分大小写。 */
const QQ_MAIL = /^[a-z0-9_.+\-]+@qq\.com$/

const email = ref('')
const code = ref('')
const sending = ref(false)
const loging = ref(false)
const countdown = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

const { setUserId } = useAuth()

function tick(): void {
  if (countdown.value > 0) {
    countdown.value--
  } else if (timer) {
    clearInterval(timer)
    timer = null
  }
}

/** 发送验证码：前端先正则校验，不通过直接提示不发请求；通过后按钮进 60s 倒计时防连点。 */
async function onSendCode(): Promise<void> {
  const e = email.value.trim().toLowerCase()
  if (!QQ_MAIL.test(e)) {
    ElMessage.error('请输入正确的 QQ 邮箱（如 12345@qq.com）')
    return
  }
  sending.value = true
  try {
    await sendCode(e)
    ElMessage.success('验证码已发送，5 分钟内有效')
    countdown.value = 60
    timer = setInterval(tick, 1000)
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    sending.value = false
  }
}

/** 登录：邮箱+验证码，成功拿到 userId 存 localStorage，App.vue 自动切到聊天页。 */
async function onLogin(): Promise<void> {
  const e = email.value.trim().toLowerCase()
  if (!QQ_MAIL.test(e)) {
    ElMessage.error('请输入正确的 QQ 邮箱')
    return
  }
  if (!code.value.trim()) {
    ElMessage.error('请输入验证码')
    return
  }
  loging.value = true
  try {
    const userId = await login(e, code.value.trim())
    setUserId(userId)
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    loging.value = false
  }
}

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div
    class="login-wrap welcome-in flex min-h-screen items-center justify-center px-4"
    style="background: var(--bg)"
  >
    <div class="w-full max-w-[380px]">
      <div class="mb-7 text-center">
        <span
          class="mx-auto mb-4 inline-flex h-11 w-11 items-center justify-center rounded-2xl text-white"
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
        <h1 class="text-[22px] font-semibold tracking-tight" style="color: var(--ink-strong)">
          RagForge 登录
        </h1>
        <p class="mt-1.5 text-[13px]" style="color: var(--ink-2)">
          QQ 邮箱验证码登录，首次登录自动注册
        </p>
      </div>

      <div
        class="login-card rounded-2xl p-5"
        style="background: var(--surface); border: 1px solid var(--line); box-shadow: 0 1px 2px rgba(29, 28, 25, 0.04)"
      >
        <el-input
          v-model="email"
          placeholder="QQ 邮箱"
          size="large"
          :disabled="loging"
        >
          <template #append>
            <el-button
              :disabled="countdown > 0 || sending || loging"
              :loading="sending"
              @click="onSendCode"
            >
              {{ countdown > 0 ? `${countdown}s 后重发` : '发送验证码' }}
            </el-button>
          </template>
        </el-input>

        <el-input
          v-model="code"
          placeholder="6 位验证码"
          size="large"
          maxlength="6"
          class="mt-3"
          :disabled="loging"
          @keyup.enter="onLogin"
        />

        <el-button
          type="primary"
          size="large"
          class="login-btn mt-4 w-full"
          :loading="loging"
          :disabled="!email || !code"
          @click="onLogin"
        >
          登录
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 把 Element Plus 主按钮的蓝色换成项目的青绿强调色，统一视觉 */
.login-btn.el-button--primary {
  background-color: var(--accent);
  border-color: var(--accent);
}

.login-btn.el-button--primary:hover:not(:disabled) {
  background-color: var(--accent-strong);
  border-color: var(--accent-strong);
}

.login-btn.el-button--primary.is-disabled {
  background-color: #d8d4cc;
  border-color: #d8d4cc;
}

/* append 按钮贴近输入框，去掉多余内边距 */
.login-card :deep(.el-input-group__append) {
  padding: 0;
}

.login-card :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--line) inset;
}

.login-card :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--accent) 50%, var(--line)) inset;
}
</style>
