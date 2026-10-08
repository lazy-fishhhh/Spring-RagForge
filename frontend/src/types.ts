/** 一条参考来源，对应后端 sources 事件里的一行。 */
export interface SourceRef {
  /** 引用编号，与正文里 [ref:N] 的 N 一致 */
  index: number
  /** 出处，如「Declaring Advice > Before Advice」 */
  origin: string
  /** 原文链接，可能为空（元数据里没有链接时后端整段省略） */
  url: string
}

export type ChatRole = 'user' | 'ai'

export interface ChatMessage {
  id: number
  role: ChatRole
  /** 展示用的正文：用户消息是输入原文，AI 消息是打字机已经吐出来的那部分 markdown */
  text: string
  /** 本次回答引用到的来源，按编号索引 */
  sources: SourceRef[]
  /** 降级原因（来自 degraded 事件），空表示正常回答 */
  degraded?: string
  /** 请求失败时的错误文案，非空则整条消息只显示它 */
  error?: string
  /** 流是否已收尾：决定「正在思考…」和「（无回复）」占位怎么显示 */
  finished: boolean
}

/** 从 SSE 流里解析出的一个事件块。event 为 null 表示无名事件（正文 token）。 */
export interface SseEvent {
  event: string | null
  data: string
}
