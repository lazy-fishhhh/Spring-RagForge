package com.springragforge.service;

import com.springragforge.rag.DegradationRegistry;
import com.springragforge.rag.SourceInfo;
import com.springragforge.rag.SourceRegistry;
import com.springragforge.rag.resilience.RequestScopeCache;
import com.springragforge.rag.resilience.ResilienceProperties;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.RateLimitException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不启 Spring 上下文，直接构造 {@link ResilientChatService}。
 * 用 {@link StepVerifier} 的虚拟时钟驱动超时与退避，断言不依赖真实等待。
 */
class ResilientChatServiceTest {

    private final SourceRegistry sourceRegistry = new SourceRegistry();
    private final DegradationRegistry degradationRegistry = new DegradationRegistry();
    private final RequestScopeCache cache = new RequestScopeCache();

    @BeforeAll
    static void failFastInsteadOfHanging() {
        // 真实时间上限：虚拟时钟卡住时宁可失败也不要无限等
        StepVerifier.setDefaultTimeout(Duration.ofSeconds(10));
    }

    @BeforeEach
    void reset() {
        sourceRegistry.remove("m1");
        degradationRegistry.remove("m1");
        cache.clear("m1");
    }

    @Test
    void success_passesThrough_andNeverDegrades() {
        StubChat stub = new StubChat(() -> Flux.just("a", "b"));

        StepVerifier.withVirtualTime(() -> service(stub, props()).chat("q", "m1"))
                .expectNext("a", "b")
                .verifyComplete();

        assertEquals(1, stub.calls.get());
        assertNull(degradationRegistry.get("m1"));
    }

    @Test
    void errorBeforeFirstToken_thenSuccess_retries() {
        StubChat stub = new StubChat(
                () -> Flux.error(new InternalServerException("500")),
                () -> Flux.just("ok"));

        StepVerifier.withVirtualTime(() -> service(stub, props()).chat("q", "m1"))
                .thenAwait(Duration.ofMillis(500)) // 让退避走完，第二个脚本才产出
                .expectNext("ok")
                .verifyComplete();

        assertEquals(2, stub.calls.get());
        assertNull(degradationRegistry.get("m1"));
    }

    @Test
    void retriesExhausted_fallsBackToRetrievedPassages() {
        sourceRegistry.put("m1", List.of(
                new SourceInfo(1, "doc A", "http://a", "正文甲"),
                new SourceInfo(2, "doc B", null, "正文乙")));
        StubChat stub = new StubChat(() -> Flux.error(new RateLimitException("429")));

        StepVerifier.withVirtualTime(() -> service(stub, props()).chat("q", "m1"))
                .thenAwait(Duration.ofSeconds(5)) // 三次尝试 + 退避全部走完
                .expectNextMatches(text -> {
                    assertTrue(text.startsWith("[降级回答]"), text);
                    assertTrue(text.contains("[ref:1]") && text.contains("[ref:2]"), text);
                    return text.contains("正文甲") && text.contains("正文乙");
                })
                .verifyComplete();

        assertEquals(3, stub.calls.get());
        assertEquals("model_unavailable_fallback_to_passages", degradationRegistry.get("m1"));
    }

    @Test
    void errorMidStream_appendsInterruptionNote_andDoesNotRetry() {
        StubChat stub = new StubChat(
                () -> Flux.concat(Flux.just("partial"), Flux.error(new IOException("boom"))));

        StepVerifier.withVirtualTime(() -> service(stub, props()).chat("q", "m1"))
                .expectNext("partial")
                .expectNext(ResilientChatService.INTERRUPTION_NOTE)
                .verifyComplete();

        assertEquals(1, stub.calls.get());
        assertNull(degradationRegistry.get("m1"));
    }

    @Test
    void timeoutBeforeFirstToken_retriesThenFallsBack() {
        StubChat stub = new StubChat(Flux::never);

        StepVerifier.withVirtualTime(() -> service(stub, props()).chat("q", "m1"))
                .thenAwait(Duration.ofSeconds(6))
                .expectNextMatches(text -> text.startsWith("[降级回答]"))
                .verifyComplete();

        assertEquals(3, stub.calls.get()); // maxRetries + 1
    }

    @Test
    void idleTimeoutAfterFirstToken_appendsInterruptionNote() {
        StubChat stub = new StubChat(() -> Flux.concat(Flux.just("a"), Flux.never()));

        StepVerifier.withVirtualTime(() -> service(stub, props()).chat("q", "m1"))
                .expectNext("a")
                .thenAwait(Duration.ofSeconds(3))
                .expectNext(ResilientChatService.INTERRUPTION_NOTE)
                .verifyComplete();

        assertEquals(1, stub.calls.get());
    }

    @Test
    void retrievalAndModelBothDown_returnsApology() {
        StubChat stub = new StubChat(() -> Flux.error(new InternalServerException("500")));

        StepVerifier.withVirtualTime(() -> service(stub, props()).chat("q", "m1"))
                .thenAwait(Duration.ofSeconds(5)) // 三次尝试 + 退避全部走完
                .expectNextMatches(text -> {
                    assertTrue(text.startsWith("[降级回答]"), text);
                    return !text.contains("[ref:");
                })
                .verifyComplete();

        assertEquals("model_and_retrieval_unavailable", degradationRegistry.get("m1"));
    }

    @Test
    void nonRetriableError_fallsBackImmediately() {
        StubChat stub = new StubChat(() -> Flux.error(new InvalidRequestException("400")));

        StepVerifier.withVirtualTime(() -> service(stub, props()).chat("q", "m1"))
                .expectNextMatches(text -> text.startsWith("[降级回答]"))
                .verifyComplete();

        assertEquals(1, stub.calls.get());
    }

    private ResilientChatService service(ChatService stub, ResilienceProperties props) {
        return new ResilientChatService(stub, props, sourceRegistry, degradationRegistry, cache);
    }

    /** 虚拟时钟下的预算：单次 1s，空闲 2s，整轮 10s，退避 100/200ms。 */
    private ResilienceProperties props() {
        ResilienceProperties p = new ResilienceProperties();
        p.setEnabled(true);
        p.setFirstTokenTimeout(Duration.ofSeconds(1));
        p.setIdleTimeout(Duration.ofSeconds(2));
        p.setOverallDeadline(Duration.ofSeconds(10));
        p.setMaxRetries(2);
        p.setMinBackoff(Duration.ofMillis(100));
        p.setMaxBackoff(Duration.ofMillis(200));
        p.setJitter(0.0);
        return p;
    }

    /** 每次调用返回下一个脚本（用完后一直用最后一个），并计数。 */
    private static final class StubChat implements ChatService {
        private final List<Supplier<Flux<String>>> scripts;
        private final AtomicInteger calls = new AtomicInteger();

        @SafeVarargs
        StubChat(Supplier<Flux<String>>... scripts) {
            this.scripts = List.of(scripts);
        }

        @Override
        public Flux<String> chat(String message, String memoryId) {
            int index = calls.getAndIncrement();
            return scripts.get(Math.min(index, scripts.size() - 1)).get();
        }
    }
}
