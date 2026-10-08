import { authHeaders } from './auth'

/** 一次灌库的结果，字段与后端 IngestStats 记录一一对应。 */
export interface IngestStats {
  fullRebuild: boolean
  added: number
  updated: number
  deleted: number
  unchanged: number
  failed: number
  embeddedChunks: number
  elapsedMillis: number
  failures: string[]
}

/**
 * 触发一次增量灌库。同步接口，耗时取决于有多少文档真的变了，可能跑几分钟。
 *
 * @throws Error 409（已有任务在跑 / 语料根不存在）或其它非 2xx 时抛出，消息里带后端 error 字段
 */
export async function reindex(): Promise<IngestStats> {
  const res = await fetch('/admin/reindex', { method: 'POST', headers: authHeaders() })
  const body: unknown = await res.json().catch(() => null)
  if (!res.ok) {
    const detail =
      body && typeof body === 'object' && 'error' in body
        ? String((body as { error: unknown }).error)
        : `HTTP ${res.status}`
    throw new Error(detail)
  }
  return body as IngestStats
}
