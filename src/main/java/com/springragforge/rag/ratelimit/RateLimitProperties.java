package com.springragforge.rag.ratelimit;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 限流参数：速率闸（单位时间允许多少请求）+ 并发闸（同时在途请求数上限）。
 *
 * <p>限的是「用户请求」这一层，不是模型调用：整个流式回答只算一次。所以两把闸都要套在
 * {@link com.springragforge.service.ResilientChatService} 外面，重试的多次尝试不再额外计数。
 *
 * <p>超限一律快速失败（不排队等待），避免请求在闸前堆积。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rag.rate-limit")
public class RateLimitProperties {

    /** 关掉即完全透传，不限流。评测对照用。 */
    private boolean enabled = true;

    /** 速率闸：每个刷新周期允许通过的请求数。 */
    private int limitForPeriod = 20;

    /** 速率闸：刷新周期长度，令牌桶每隔这么久补满 limitForPeriod 个令牌。 */
    private Duration limitRefreshPeriod = Duration.ofSeconds(1);

    /** 并发闸：同时处于「在途」状态的请求数上限（含整个 SSE 流的持续时间）。 */
    private int maxConcurrentCalls = 10;

    @PostConstruct
    void validate() {
        if (!enabled) {
            return;
        }
        if (limitForPeriod <= 0) {
            throw new IllegalStateException("rag.rate-limit.limit-for-period 必须大于 0");
        }
        if (limitRefreshPeriod == null || limitRefreshPeriod.isZero() || limitRefreshPeriod.isNegative()) {
            throw new IllegalStateException("rag.rate-limit.limit-refresh-period 必须为正");
        }
        if (maxConcurrentCalls <= 0) {
            throw new IllegalStateException("rag.rate-limit.max-concurrent-calls 必须大于 0");
        }
    }
}
