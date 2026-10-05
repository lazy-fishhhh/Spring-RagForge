package com.springragforge.usage;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * token 用量累计器，落在 Redis，重启不丢。
 *
 * <p>两类计数键：每个模型一个 hash，外加一个全局合计 hash；模型名另存一个 Set，
 * 供枚举（比 KEYS 扫描安全）。hash 字段：inputTokens / outputTokens / calls。
 *
 * <p>用 Redis 的 HINCRBY 原子自增，多请求并发累加不会互相覆盖。
 */
@Component
@RequiredArgsConstructor
public class TokenUsageRegistry {

    private static final String PREFIX = "rag:token-usage:";
    /** 模型名集合，用于枚举所有出现过的模型。 */
    private static final String MODELS_KEY = PREFIX + "__models";
    /** 全局合计。 */
    private static final String TOTAL_KEY = PREFIX + "__total";

    private static final String FIELD_INPUT = "inputTokens";
    private static final String FIELD_OUTPUT = "outputTokens";
    private static final String FIELD_CALLS = "calls";

    private final StringRedisTemplate redis;

    /** 记一次模型调用的用量。input/output 允许为 0（某侧无计数时）。 */
    public void record(String modelName, long inputTokens, long outputTokens) {
        String key = PREFIX + modelName;
        redis.opsForSet().add(MODELS_KEY, modelName);
        increment(key, inputTokens, outputTokens);
        increment(TOTAL_KEY, inputTokens, outputTokens);
    }

    /** 每个出现过的模型的用量；按模型名排序，便于日志/报表稳定输出。 */
    public Map<String, ModelUsage> snapshot() {
        Set<String> models = redis.opsForSet().members(MODELS_KEY);
        Map<String, ModelUsage> result = new LinkedHashMap<>();
        if (models == null) {
            return result;
        }
        models.stream().sorted().forEach(model -> result.put(model, read(model, PREFIX + model)));
        return result;
    }

    /** 全局合计。 */
    public ModelUsage total() {
        return read("__total__", TOTAL_KEY);
    }

    /** 清空所有计数（模型集合、各模型、合计），供评测前归零。 */
    public void reset() {
        Set<String> models = redis.opsForSet().members(MODELS_KEY);
        if (models != null) {
            for (String model : models) {
                redis.delete(PREFIX + model);
            }
        }
        redis.delete(MODELS_KEY);
        redis.delete(TOTAL_KEY);
    }

    private void increment(String key, long inputTokens, long outputTokens) {
        if (inputTokens != 0) {
            redis.opsForHash().increment(key, FIELD_INPUT, inputTokens);
        }
        if (outputTokens != 0) {
            redis.opsForHash().increment(key, FIELD_OUTPUT, outputTokens);
        }
        redis.opsForHash().increment(key, FIELD_CALLS, 1);
    }

    private ModelUsage read(String modelName, String key) {
        Map<String, String> fields = hashEntries(key);
        return new ModelUsage(modelName,
                parse(fields.get(FIELD_CALLS)),
                parse(fields.get(FIELD_INPUT)),
                parse(fields.get(FIELD_OUTPUT)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> hashEntries(String key) {
        // StringRedisTemplate 的 hash 序列化器是 String，运行时就是 Map<String,String>
        Map<String, String> entries = (Map<String, String>) (Map<?, ?>) redis.opsForHash().entries(key);
        return entries;
    }

    private static long parse(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
