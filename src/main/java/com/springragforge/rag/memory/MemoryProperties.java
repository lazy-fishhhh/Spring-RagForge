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

    /** 记忆预算（摘要 + 最近消息），单位 token。这是提示窗口不能越过的硬上限。 */
    private int budgetTokens = 8000;

    /**
     * 触发摘要的高水位：估算占用超过 {@code budgetTokens × 该比例} 才开始逐出 + 摘要。
     *
     * <p>不贴着预算线触发，是为了让一次摘要能管好几轮：一超预算就摘的话，
     * 窗口刚回到预算内，下一轮新增两条消息又超，等于每轮多一次同步模型往返。
     */
    private double summarizeAtRatio = 0.95;

    /**
     * 逐出目标低水位：一次摘要把占用逐到 {@code budgetTokens × 该比例}，给后续几轮留余量。
     * 必须小于 {@link #summarizeAtRatio}，否则刚摘完又站在触发线上。
     */
    private double evictToRatio = 0.70;

    /**
     * 无论怎么逐出，最近这么多条消息永远保留原文，保证最近几轮不打折。
     * 注意：若这些消息本身估算就超过高水位，水位机制会退化成每轮摘要一次。
     */
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
        if (summarizeAtRatio <= 0 || summarizeAtRatio > 1) {
            throw new IllegalStateException("rag.memory.summarize-at-ratio 必须在 (0, 1] 内，当前 "
                    + summarizeAtRatio);
        }
        if (evictToRatio <= 0 || evictToRatio >= summarizeAtRatio) {
            throw new IllegalStateException("rag.memory.evict-to-ratio 必须在 (0, summarize-at-ratio) 内，当前 "
                    + evictToRatio + "，触发水位 " + summarizeAtRatio);
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
