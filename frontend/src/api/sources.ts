import type { SourceRef } from '../types'

/**
 * 解析后端 sources 事件的纯文本载荷：
 *
 * <pre>
 * 参考来源：
 * [1] 出处：Declaring Advice &gt; Before Advice | 链接：https://docs.spring.io/...
 * [2] 出处：Web Security
 * </pre>
 *
 * 「 | 链接：」整段在来源没有链接时会被后端省略，所以这一段按可选处理，不能假设它一定在。
 */
export function parseSources(payload: string): SourceRef[] {
  const sources: SourceRef[] = []
  for (const line of payload.split('\n')) {
    const matched = /^\[(\d+)\]\s*(.*)$/.exec(line.trim())
    if (!matched) continue

    const index = Number(matched[1])
    const rest = matched[2]
    const sep = rest.indexOf(' | 链接：')
    const origin = (sep >= 0 ? rest.slice(0, sep) : rest).replace(/^出处：/, '').trim()
    const url = sep >= 0 ? rest.slice(sep + ' | 链接：'.length).trim() : ''
    sources.push({ index, origin, url })
  }
  return sources
}
