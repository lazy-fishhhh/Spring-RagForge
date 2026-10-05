package com.springragforge.usage;

/** 单个模型（或全局合计）的原始用量计数，不含金额。金额由控制器结合价格表算。 */
public record ModelUsage(String modelName, long calls, long inputTokens, long outputTokens) {

    public long totalTokens() {
        return inputTokens + outputTokens;
    }

    public static ModelUsage empty(String modelName) {
        return new ModelUsage(modelName, 0, 0, 0);
    }
}
