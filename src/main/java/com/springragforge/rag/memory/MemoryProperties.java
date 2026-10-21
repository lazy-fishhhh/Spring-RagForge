package com.springragforge.rag.memory;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 会话记忆参数：滚动摘要的预算、逐出与保留策略、Redis TTL。
 *
 * <p>预算只算「摘要 + 最近消息」两块，不含 system 提示和检索片段——后两者不进 memory，
 * 由各自的路径注入，所以这里估的是纯记忆占用。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rag.memory")
public class MemoryProperties {

    /** 记忆预算（摘要 + 最近消息），单位 token。超出就逐出最老的消息做摘要。 */
    private int budgetTokens = 8000;

    /** 无论怎么逐出，最近这么多条消息永远保留原文，保证最近几轮不打折。 */
    private int keepRecentMessages = 8;

    /** 一次逐出的条数：攒够这批再做一次 LLM 摘要，避免逐一条就调一次模型。 */
    private int evictBatch = 2;

    /** 摘要自身的 token 上限：输出超过就在代码里截断，避免模型无视提示词无限增长。 */
    private int summaryMaxTokens = 400;

    /** 连续摘要失败达到该次数后降级：不写摘要、直接逐出，防止 history 无限增长。 */
    private int summaryFailureThreshold = 2;

    /** 存储硬上限（摘要 + 历史），0 表示不启用。超限时无视摘要直接丢弃最老消息。 */
    private int hardCapTokens = 0;

    /** 消息与摘要共用的 Redis TTL，两者一起刷新，别让一个先过期导致对不上。 */
    private Duration ttl = Duration.ofDays(1);

    @PostConstruct
    void validate() {
        if (budgetTokens <= 0) {
            throw new IllegalStateException("rag.memory.budget-tokens 必须为正数，当前 " + budgetTokens);
        }
        if (keepRecentMessages < 0) {
            throw new IllegalStateException("rag.memory.keep-recent-messages 不能为负，当前 " + keepRecentMessages);
        }
        if (evictBatch <= 0) {
            throw new IllegalStateException("rag.memory.evict-batch 必须为正数，当前 " + evictBatch);
        }
        if (summaryMaxTokens <= 0 || summaryMaxTokens >= budgetTokens) {
            throw new IllegalStateException("rag.memory.summary-max-tokens 必须在 (0, budget-tokens) 内，当前 "
                    + summaryMaxTokens + "，预算 " + budgetTokens);
        }
        if (summaryFailureThreshold <= 0) {
            throw new IllegalStateException("rag.memory.summary-failure-threshold 必须为正数，当前 "
                    + summaryFailureThreshold);
        }
        if (hardCapTokens != 0 && hardCapTokens < budgetTokens) {
            throw new IllegalStateException("rag.memory.hard-cap-tokens 为 0 或必须不小于 budget-tokens，当前 "
                    + hardCapTokens + "，预算 " + budgetTokens);
        }
    }
}
