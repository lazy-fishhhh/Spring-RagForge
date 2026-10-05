package com.springragforge.service;

import com.springragforge.rag.DegradationRegistry;
import com.springragforge.rag.SourceInfo;
import com.springragforge.rag.SourceRegistry;
import com.springragforge.rag.resilience.RequestScopeCache;
import com.springragforge.rag.resilience.ResilienceProperties;
import dev.langchain4j.exception.RetriableException;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 给生成层套超时、重试、降级。包装 {@link ChatService} 的 AiServices 代理，
 * 控制器和评测都通过接口拿到它，透明。
 *
 * <p>分两段处理，分界是「第一个 token 是否已交给下游」：
 * <ul>
 *   <li>首 token 前：可无损重试。单次尝试由 firstTokenTimeout 计时，整轮由 overallDeadline 兜底，
 *       只重试可重试错误。重试用尽 → 降级，把检索原文吐给用户。</li>
 *   <li>首 token 后：token 已发出，重试会造成重复内容。只做逐间隔空闲超时；超时或出错就追加一句
 *       中断提示，然后正常收尾（保住 [DONE]）。</li>
 * </ul>
 */
@Slf4j
public class ResilientChatService implements ChatService {

    /** 首 token 已发出后若中断，追加这句提示收尾（不重试，避免重复内容）。 */
    static final String INTERRUPTION_NOTE = "\n\n[回答中断：模型连接超时或异常]";
    /** 降级文案一：有检索原文可兜底时，前缀提示。 */
    private static final String DEGRADED_PREFIX = "[降级回答] 模型暂时不可用，以下为本次检索到的原文片段：\n\n";
    /** 降级文案二：模型和检索都不可用，连原文都没有，只能道歉。 */
    private static final String DEGRADED_APOLOGY = "[降级回答] 模型与检索服务暂时不可用，请稍后重试。";
    /** 降级原因标记：回退到检索原文。 */
    private static final String REASON_FALLBACK_TO_PASSAGES = "model_unavailable_fallback_to_passages";
    /** 降级原因标记：只道歉。 */
    private static final String REASON_APOLOGY = "model_and_retrieval_unavailable";

    /** 被包装的真实 ChatService（AiServices 代理），正常路径由它产出 token。 */
    private final ChatService delegate;
    /** 韧性参数：超时、重试、退避、开关。 */
    private final ResilienceProperties properties;
    /** 检索来源登记表，降级时用来取出本次检索到的原文片段。 */
    private final SourceRegistry sourceRegistry;
    /** 降级登记表，记录某会话走了哪种降级，供后续观测/评测。 */
    private final DegradationRegistry degradationRegistry;
    /** 请求级缓存，流结束时清理，避免跨请求堆积。 */
    private final RequestScopeCache cache;

    public ResilientChatService(ChatService delegate,
                                ResilienceProperties properties,
                                SourceRegistry sourceRegistry,
                                DegradationRegistry degradationRegistry,
                                RequestScopeCache cache) {
        this.delegate = delegate;
        this.properties = properties;
        this.sourceRegistry = sourceRegistry;
        this.degradationRegistry = degradationRegistry;
        this.cache = cache;
    }

    @Override
    public Flux<String> chat(String message, String memoryId) {
        // 韧性整体关闭：完全透传，不超时、不重试、不降级（评测对照用）。
        if (!properties.isEnabled()) {
            return delegate.chat(message, memoryId);
        }

        // 每次 chat() 一份状态：第一个 token 一旦交给下游就置位，之后不再重试
        AtomicBoolean firstTokenSeen = new AtomicBoolean(false);

        // Flux.defer 必须：langchain4j-reactor 的流是一次性 unicast sink，
        // 重订阅要重新调 delegate 建新 sink，否则报「allows only one Subscriber」
        Flux<String> attempt = Flux.defer(() -> delegate.chat(message, memoryId))
                // doOnNext：有token流过就执行，标记「已产出内容，不能再重试」
                .doOnNext(token -> firstTokenSeen.set(true))
                // timeout 的两个参数：第一个是「还没收到任何元素」的超时；第二个回调在收到首个元素后，
                // 对后续每个元素间隔用 idleTimeout 计时。
                .timeout(Mono.delay(properties.getFirstTokenTimeout()),
                        last -> Mono.delay(properties.getIdleTimeout()));

        // 重试，retryWhen：当上游出错时，不要直接把错误抛给下游，而是按我给的重试策略，再订阅上游一次
        Flux<String> withRetry = attempt.retryWhen(
                // backoff：重试策略：getMaxRetries次数，第一次重试前至少等getMinBackoff
                Retry.backoff(properties.getMaxRetries(), properties.getMinBackoff())
                        // 最多等待时间
                        .maxBackoff(properties.getMaxBackoff())
                        // 抖动：jitter = 0.5 → 在 ±50% 范围内随机。
                        // 本来该等固定时间再重试，现在改成等"固定时间 ± 一个随机量"，让多个请求的等待时间错开
                        .jitter(properties.getJitter())
                        // Retry 的过滤器只看异常，看不到是否已发过 token，所以靠外部标志门控：
                        // 已发过 token（firstTokenSeen=true）就返回 false，不再重试。
                        .filter(ex -> !firstTokenSeen.get() && isRetryable(ex))
                        .doBeforeRetry(signal -> log.warn(  // 日志
                                "重试: memoryId={} 第{}次 cause={}",
                                memoryId,
                                signal.totalRetries() + 1,
                                Exceptions.unwrap(signal.failure()).toString())));

        return withRetry
                // 整轮重试循环的硬上限；idleTimeout < overallDeadline 保证首 token 后由内层空闲超时先触发
                .timeout(properties.getOverallDeadline())
                // 出错兜底：分两种。
                //  - 已发过 token：只追加一句中断提示，正常收尾（保住 [DONE]），不重试不降级。
                //  - 还没发 token：走降级，吐检索原文或道歉。
                .onErrorResume(ex -> firstTokenSeen.get()
                        ? Flux.just(INTERRUPTION_NOTE)
                        : fallback(memoryId, ex))
                // 无论正常结束、出错还是取消，都清理本次会话的请求级缓存。
                .doFinally(signal -> cache.clear(memoryId));
    }

    /** 只重试可能自愈的错误：限流、5xx、超时、连接类 IO。鉴权/参数错误等判为不可重试。 */
    private static boolean isRetryable(Throwable ex) {
        // 解开 Reactor 包裹的异常，拿到真正的 cause 再判断类型。
        Throwable t = Exceptions.unwrap(ex);
        return t instanceof RetriableException
                || t instanceof TimeoutException
                || t instanceof IOException;
    }

    /**
     * 降级：模型这条链已经放弃，尽量把本次检索到的原文交还用户。
     *
     * <p>有原文 → 拼成带 [ref:n] 的文本一次性吐出；没有原文（检索也没成功）→ 只回道歉。
     * 同时把降级原因写进 degradationRegistry 供观测。
     */
    private Flux<String> fallback(String memoryId, Throwable ex) {
        log.warn("生成失败，走降级: memoryId={} cause={}", memoryId, ex.toString());
        List<SourceInfo> sources = memoryId == null ? List.of() : sourceRegistry.get(memoryId);
        if (sources.isEmpty()) {
            // 检索也没结果，只能道歉。
            record(memoryId, REASON_APOLOGY);
            return Flux.just(DEGRADED_APOLOGY);
        }
        record(memoryId, REASON_FALLBACK_TO_PASSAGES);
        StringBuilder sb = new StringBuilder(DEGRADED_PREFIX);
        for (SourceInfo source : sources) {
            // 带上 [ref:n] 标记，控制器照常按引用过滤并推送 sources 事件，前端无需特判
            sb.append("[ref:").append(source.index()).append("] ").append(source.origin()).append('\n')
                    .append(source.text() == null ? "" : source.text()).append("\n\n");
        }
        // 降级内容一次性作为一个元素发出（不是逐 token 流式）。
        return Flux.just(sb.toString());
    }

    /** 记录降级原因；memoryId 为 null（无会话上下文）时跳过。 */
    private void record(String memoryId, String reason) {
        if (memoryId != null) {
            degradationRegistry.put(memoryId, reason);
        }
    }
}