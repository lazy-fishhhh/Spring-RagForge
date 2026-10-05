package com.springragforge.usage;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 截模型返回的 token 用量。注册成 Bean 即可：langchain4j 的 Spring starter 会把所有
 * {@link ChatModelListener} Bean 注入到 chat-model 和 streaming-chat-model，无需改 AiServices。
 *
 * <p>监听器抛异常会被框架吞掉（见 ChatModelListenerUtils），不会影响正常请求，但这里仍做防御：
 * 任何字段缺失都当 0 处理，绝不往外抛。
 *
 * <p>已知边界：只覆盖 chat 与 streaming-chat 两个模型。embedding 和自写的重排打分模型
 * 不走 ChatModelListener，用量不在本表内。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsageCapturingChatModelListener implements ChatModelListener {

    static final String UNKNOWN_MODEL = "unknown";

    private final TokenUsageRegistry registry;
    private final TokenUsageProperties properties;

    /** 已经就「无 usage」告警过的模型，避免每个请求刷屏。 */
    private final Set<String> warnedModels = ConcurrentHashMap.newKeySet();

    @Override
    public void onResponse(ChatModelResponseContext context) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            ChatResponse response = context.chatResponse();
            String model = resolveModel(context.chatRequest(), response);
            TokenUsage usage = response == null ? null : response.tokenUsage();

            if (usage == null || (usage.inputTokenCount() == null && usage.outputTokenCount() == null)) {
                // 流式默认不带 usage：需在请求里加 stream_options.include_usage=true（见 application.yml）
                if (warnedModels.add(model)) {
                    log.warn("模型 {} 未返回 token usage，本次不计入统计；流式需开启 stream_options.include_usage", model);
                }
                return;
            }

            long input = orZero(usage.inputTokenCount());
            long output = orZero(usage.outputTokenCount());
            registry.record(model, input, output);

            if (properties.isLogEachCall()) {
                logUsage(model, input, output);
            }
        } catch (RuntimeException ex) {
            // 统计绝不能拖垮主链；这里只记一笔，不抛。
            log.warn("记录 token 用量失败: {}", ex.toString());
        }
    }

    /** 打一行用量日志，带本次调用按价格表估算的费用。价格表查不到该模型时只报 token 数。 */
    private void logUsage(String model, long input, long output) {
        TokenUsageProperties.ModelPrice price = properties.priceFor(model);
        if (price == null) {
            log.info("token 用量: model={} input={} output={} total={} 费用=未知(价格表无此模型)",
                    model, input, output, input + output);
            return;
        }
        double inputCost = cost(input, price.getInputPerMillion());
        double outputCost = cost(output, price.getOutputPerMillion());
        log.info("token 用量: model={} input={} output={} total={} 费用={} (输入 {} + 输出 {})",
                model, input, output, input + output, round(inputCost + outputCost), round(inputCost), round(outputCost));
    }

    /** 按「每百万 token」单价算费用。 */
    private static double cost(long tokens, double perMillion) {
        return tokens / 1_000_000.0 * perMillion;
    }

    /** 金额保留 6 位小数。 */
    private static double round(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    /** 模型名优先级：请求指定 > 响应回填 > 元数据；都拿不到记 unknown。 */
    private static String resolveModel(ChatRequest request, ChatResponse response) {
        if (request != null && request.modelName() != null && !request.modelName().isBlank()) {
            return request.modelName();
        }
        if (response != null) {
            if (response.modelName() != null && !response.modelName().isBlank()) {
                return response.modelName();
            }
            if (response.metadata() != null && response.metadata().modelName() != null
                    && !response.metadata().modelName().isBlank()) {
                return response.metadata().modelName();
            }
        }
        return UNKNOWN_MODEL;
    }

    private static long orZero(Integer value) {
        return value == null ? 0L : value;
    }
}
