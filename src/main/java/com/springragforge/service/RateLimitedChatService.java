package com.springragforge.service;

import com.springragforge.rag.ratelimit.RateLimitProperties;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;

/**
 * 给整条生成链套限流，最外层。包装 {@link ResilientChatService}，比它更靠外，
 * 所以一次用户请求（含内部所有重试）只占用一个速率令牌、一个并发位。
 *
 * <p>两把闸，作用不同：
 * <ul>
 *   <li>速率闸（{@link RateLimiter}）：令牌桶，每个刷新周期放行固定数量的请求，拦的是「请求频率」。</li>
 *   <li>并发闸（{@link Bulkhead}，信号量型）：限制同时在途的请求数，拦的是「同时压着几个流」，
 *       流式回答持续数秒，不设闸会把上游模型连接数打满。</li>
 * </ul>
 *
 * <p>超限快速失败：不排队、不等待，直接下发一句提示。用 {@code transformDeferred}（而非
 * {@code transform}）保证每次订阅都重新走一遍算子，语义与其它订阅点一致。
 */
@Slf4j
public class RateLimitedChatService implements ChatService {

    /** 超限文案：作为普通 token 下发，前端照常渲染；控制器照常补 [DONE] 收尾。 */
    static final String REJECT_NOTE = "[限流] 请求过于频繁，请稍后再试。";

    /** 被包装的内层服务（超时/重试/降级那层）。 */
    private final ChatService delegate;
    /** 限流参数与开关。 */
    private final RateLimitProperties properties;
    /** 速率闸：单位时间放行量。 */
    private final RateLimiter rateLimiter;
    /** 并发闸：在途请求数上限。 */
    private final Bulkhead bulkhead;

    public RateLimitedChatService(ChatService delegate,
                                  RateLimitProperties properties,
                                  RateLimiter rateLimiter,
                                  Bulkhead bulkhead) {
        this.delegate = delegate;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.bulkhead = bulkhead;
    }

    @Override
    public Flux<String> chat(String message, String memoryId) {
        // 限流整体关闭：完全透传（评测对照用）。
        if (!properties.isEnabled()) {
            return delegate.chat(message, memoryId);
        }

        // Flux.defer 必须：内层流是一次性 unicast sink，重订阅要重新调用 delegate 建新 sink。
        // 两把闸在订阅时各取一个许可，流终止时释放；许可覆盖整个流的生命周期。
        Flux<String> limited = Flux.defer(() -> delegate.chat(message, memoryId))
                // 速率闸：订阅时尝试取一个速率令牌，取不到即抛 RequestNotPermitted。
                // 用 transformDeferred 而非 transform：每次订阅都重新套一遍算子，许可按订阅独立获取，
                // 且只在最外层订阅取一次，内层所有重试都共用这一个令牌。
                .transformDeferred(RateLimiterOperator.of(rateLimiter))
                // 并发闸：订阅时尝试占一个并发位，取不到即抛 BulkheadFullException。
                // 许可同样绑定到流终止才释放，覆盖整个流式回答的持续时间。
                .transformDeferred(BulkheadOperator.of(bulkhead));

        // 只拦截"限流拒绝"两类异常，换成限流提示下发；其它异常原样透传（交给内层或框架处理）。
        return limited.onErrorResume(ex -> isRejected(ex)
                ? reject(memoryId, ex)
                : Flux.error(ex));
    }

    /** 只认两种限流拒绝：速率闸的 RequestNotPermitted、并发闸的 BulkheadFullException。 */
    private static boolean isRejected(Throwable ex) {
        // 解开 Reactor 包裹的异常，拿到真正的 cause 再判类型（与 ResilientChatService 同样处理）。
        Throwable t = Exceptions.unwrap(ex);
        return t instanceof RequestNotPermitted || t instanceof BulkheadFullException;
    }

    /** 快速失败：记一条日志，然后把提示当作唯一 token 下发，不降级、不重试。 */
    private Flux<String> reject(String memoryId, Throwable ex) {
        // 限流是"现在别来"，重试无意义，所以只记日志 + 回提示，不进降级流程。
        log.warn("请求被限流: memoryId={} cause={}", memoryId, Exceptions.unwrap(ex).getClass().getSimpleName());
        // 提示走和正常 token 完全相同的通道：前端无需特判，控制器照常补 [DONE] 收尾。
        return Flux.just(REJECT_NOTE);
    }
}
