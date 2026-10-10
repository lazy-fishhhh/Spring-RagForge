package com.springragforge.constant;

/**
 * 会话记忆相关的提示词与文案。
 */
public final class MemoryPrompts {

    private MemoryPrompts() {
        // 工具类禁止实例化
    }

    /** 摘要注入 prompt 时的前缀，明确告诉模型这段是压缩过的历史背景。 */
    public static final String SUMMARY_PREFIX = "以下是此前对话的摘要，供参考：\n";

    /**
     * 滚动摘要提示词。占位符：{summary} = 已有摘要，{batch} = 本次逐出的对话。
     *
     * <p>保留问答要点与引用出处，丢弃寒暄和重复，让压缩后的历史仍能支撑多轮追问
     * （指代消解和后续回答都要靠它）。
     */
    public static final String SUMMARY_PROMPT = """
            你是对话摘要器。把「已有摘要」和「本次新增对话」合并成一段新的摘要。

            保留：
            - 用户已问过的主题、关键追问和意图
            - 已给出的结论要点（提炼，不要复述整段回答）
            - 引用过的文档出处：类名、方法名、配置项 key、注解名，逐字保留
            - 尚未解决或悬置的问题

            丢弃：
            - 寒暄、客套、重复表述
            - 已作废或已被推翻的分支

            输出要求：
            - 只输出合并后的摘要正文，不要标题、不要解释、不要 markdown 代码块
            - 用中文，第三人称陈述
            - 控制在 300 字以内；信息装不下时优先保留悬置问题和引用出处

            已有摘要：
            {summary}

            本次新增对话：
            {batch}
            """;
}
