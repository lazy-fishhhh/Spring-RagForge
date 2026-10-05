package com.springragforge.rag.resilience;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 生成层韧性参数：超时预算、重试、降级开关。
 *
 * <p>分两段：首 token 之前可以重试（还没内容发给用户，重来无损）；首 token 之后只能做
 * 逐间隔空闲超时（已发出的 token 收不回，重试会导致重复内容）。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rag.resilience")
public class ResilienceProperties {

    /** 关掉即完全透传，不超时、不重试、不降级。评测对照用。 */
    private boolean enabled = true;

    /** 单次尝试的首 token 超时（t）：从订阅到第一个 token 的最长等待。每次重试重新计时。 */
    private Duration firstTokenTimeout = Duration.ofSeconds(10);

    /** 首 token 之后的逐间隔空闲超时：两个 token 之间超过它就判定流卡死。 */
    private Duration idleTimeout = Duration.ofSeconds(15);

    /** 整轮重试循环的硬上限（D）：从订阅起算，期间没有任何 token 就放弃并降级。 */
    private Duration overallDeadline = Duration.ofSeconds(30);

    /** 重试次数（总尝试次数 = 次数 + 1）。 */
    private int maxRetries = 2;

    /** 重试退避下限。 */
    private Duration minBackoff = Duration.ofMillis(200);

    /** 重试退避上限。 */
    private Duration maxBackoff = Duration.ofSeconds(2);

    /** 退避抖动系数（0~1），避免多个请求同时重试。 */
    private double jitter = 0.5;

    /**
     * 外层 overallDeadline 的超时算子也是逐间隔计时，要保证首 token 之后由内层 idleTimeout
     * 先触发，否则长回答会在中途被整体截止打断。
     */
    @PostConstruct // Bean 的依赖注入完成后、Bean 正式可用之前被 Spring 调用一次，相当于自检
    void validate() {
        if (!enabled) {
            return;
        }
        // 如果前者大于等于后者
        if (idleTimeout.compareTo(overallDeadline) >= 0) {
            throw new IllegalStateException(
                    "rag.resilience.idle-timeout (" + idleTimeout + ") 必须小于 overall-deadline ("
                            + overallDeadline + ")，否则首 token 后的空闲超时不会先于整轮截止触发");
        }
    }
}
