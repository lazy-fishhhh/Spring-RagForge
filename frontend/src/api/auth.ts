/** 后端 /auth 接口的统一响应体，字段与 Result<T> 对应。 */
interface ApiResult<T> {
  success: boolean
  message: string
  data: T
}

/**
 * 拼带 X-User-Id 头。后端拦截器要求 /chat /admin /files 必须带，缺失 401。
 * 未登录时该头不带，由后端拒绝。登录页阶段不会调这些接口。
 */
export function authHeaders(init: Record<string, string> = {}): Record<string, string> {
  const userId = localStorage.getItem('ragforge.userId') ?? ''
  return { ...init, ...(userId ? { 'X-User-Id': userId } : {}) }
}

/** 发送验证码。后端 success=false 时抛错，消息带后端 message 字段。 */
export async function sendCode(email: string): Promise<void> {
  const res = await fetch('/auth/send-code', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email }),
  })
  const body = (await res.json().catch(() => null)) as ApiResult<unknown> | null
  if (!body || !body.success) {
    throw new Error(body?.message ?? `HTTP ${res.status}`)
  }
}

/** 验证码登录。成功返回 userId，前端据此拼 memoryId = userId:sessionId。 */
export async function login(email: string, code: string): Promise<string> {
  const res = await fetch('/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, code }),
  })
  const body = (await res.json().catch(() => null)) as ApiResult<{ userId: string }> | null
  if (!body || !body.success) {
    throw new Error(body?.message ?? `HTTP ${res.status}`)
  }
  return body.data.userId
}
