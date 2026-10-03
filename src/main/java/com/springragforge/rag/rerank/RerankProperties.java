package com.springragforge.rag.rerank;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 重排（Rerank）参数。重排是召回之后的精排阶段：把 RRF 融合出的候选池交给
 * cross-encoder 逐条打分，再取前 N 条喂给模型。
 *
 * <p>最终返回条数不在这里，复用 {@code rag.retrieval.max-results}：
 * 无论重排开不开，交给模型的都是这个数。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rag.rerank")
public class RerankProperties {

    /** 是否启用重排。关掉即退化为「RRF 融合后直接取前 N」，用于对照评测。 */
    private boolean enabled = true;

    /**
     * DashScope 基础地址。原生 text-rerank 端点会拼在其后
     * （{@code /services/rerank/text-rerank/text-rerank}）。
     */
    private String baseUrl = "https://dashscope.aliyuncs.com/api/v1";

    /** 百炼 API Key，与其它通义模型共用同一个。 */
    private String apiKey;

    /** 重排模型名。百炼公开名是 qwen3-rerank，控制台里以实际为准。 */
    private String modelName = "qwen3-rerank";

    /**
     * 分数阈值：低于它的片段直接丢掉。留空（null）表示不过滤。
     * 注意这是 cross-encoder 的相关性分（0~1），和 RRF 的 1/(k+rank) 不是一个量级，别照搬。
     */
    private Double minScore;

    /** 单次重排请求超时秒数。 */
    private int timeoutSeconds = 20;
}
