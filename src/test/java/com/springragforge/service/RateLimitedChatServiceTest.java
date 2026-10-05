package com.springragforge.service;

import com.springragforge.rag.ratelimit.RateLimitProperties;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 不启 Spring 上下文，直接构造 {@link RateLimitedChatService} + 真 RateLimiter/Bulkhead。
 * 限流按真实状态（令牌、许可）判定，不需要虚拟时钟。
 */
class RateLimitedChatServiceTest {

    @Test
    void underLimit_passesThrough() {
        RateLimitedChatService svc = service(new StubChat(() -> Flux.just("a", "b")),
                props(true), limiter(10, Duration.ofSeconds(1)), bulkhead(10));

        StepVerifier.create(svc.chat("q", "m1"))
                .expectNext("a", "b")
                .verifyComplete();
    }

    @Test
    void rateExceeded_failsFastWithNote() {
        StubChat stub = new StubChat(() -> Flux.just("ok"));
        // 周期内只放行 1 个；刷新周期设长，保证第二次一定取不到令牌
        RateLimitedChatService svc = service(stub, props(true),
                limiter(1, Duration.ofMinutes(1)), bulkhead(10));

        StepVerifier.create(svc.chat("q", "m1")).expectNext("ok").verifyComplete();

        StepVerifier.create(svc.chat("q", "m1"))
                .expectNext(RateLimitedChatService.REJECT_NOTE)
                .verifyComplete();

        assertEquals(1, stub.calls, "第二次被速率闸拦下，不该打到内层");
    }

    @Test
    void concurrencyExceeded_failsFastWithNote() {
        // 内层流不结束，占住唯一并发位
        StubChat stub = new StubChat(Flux::never);
        RateLimitedChatService svc = service(stub, props(true),
                limiter(100, Duration.ofSeconds(1)), bulkhead(1));

        svc.chat("q", "m1").subscribe(); // 第一个订阅长期持有并发位

        StepVerifier.create(svc.chat("q", "m1"))
                .expectNext(RateLimitedChatService.REJECT_NOTE)
                .verifyComplete();

        assertEquals(1, stub.calls);
    }

    @Test
    void disabled_passesThrough_evenOverLimit() {
        StubChat stub = new StubChat(() -> Flux.just("ok", "ok"));
        RateLimitedChatService svc = service(stub, props(false),
                limiter(1, Duration.ofMinutes(1)), bulkhead(1));

        // 关掉限流：连续两次都能过，即便令牌只够一次、并发位只有一个
        StepVerifier.create(svc.chat("q", "m1")).expectNext("ok", "ok").verifyComplete();
        StepVerifier.create(svc.chat("q", "m1")).expectNext("ok", "ok").verifyComplete();
    }

    private RateLimitedChatService service(ChatService delegate, RateLimitProperties props,
                                           RateLimiter limiter, Bulkhead bulkhead) {
        return new RateLimitedChatService(delegate, props, limiter, bulkhead);
    }

    private RateLimitProperties props(boolean enabled) {
        RateLimitProperties p = new RateLimitProperties();
        p.setEnabled(enabled);
        return p;
    }

    private RateLimiter limiter(int perPeriod, Duration refresh) {
        return RateLimiter.of("test", RateLimiterConfig.custom()
                .limitForPeriod(perPeriod)
                .limitRefreshPeriod(refresh)
                .timeoutDuration(Duration.ZERO)
                .build());
    }

    private Bulkhead bulkhead(int maxConcurrent) {
        return Bulkhead.of("test", BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrent)
                .maxWaitDuration(Duration.ZERO)
                .build());
    }

    /** 每次调用返回下一个脚本（用完后一直用最后一个），并计数。 */
    private static final class StubChat implements ChatService {
        private final java.util.List<java.util.function.Supplier<Flux<String>>> scripts;
        private int calls;

        @SafeVarargs
        StubChat(java.util.function.Supplier<Flux<String>>... scripts) {
            this.scripts = java.util.List.of(scripts);
        }

        @Override
        public Flux<String> chat(String message, String memoryId) {
            int index = calls++;
            return scripts.get(Math.min(index, scripts.size() - 1)).get();
        }
    }
}
