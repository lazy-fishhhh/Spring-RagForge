package com.springragforge.rag.retriever;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 混合检索参数。管线是「BM25 召回一路 + KNN 召回一路，再 RRF 融合取前 N」，
 * 这里的每个值是这条管线上的一个旋钮。
 *
 * <p>索引名不在这里，复用 {@code rag.ingest.index-name}：建索引和查索引必须是同一个。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rag.retrieval")
public class RetrievalProperties {

    /** BM25 那一路各召回多少条。是融合前的候选池，不是最终返回数。 */
    private int bm25TopK = 20;

    /** KNN 那一路各召回多少条，同样是候选池。 */
    private int knnTopK = 20;

    /** KNN 的 numCandidates：参与近邻计算的候选数，越大越准越慢。必须 >= knnTopK。 */
    private int knnNumCandidates = 100;

    /** RRF 公式里的 k，默认 60（论文取值）。越大越削弱头部排名的影响。 */
    private int rrfK = 60;

    /**
     * RRF 融合后交给下游（重排器）的候选数。这是重排的输入池，不是最终返回数。
     * 必须 >= maxResults，否则重排没有可重排的余地；重排关闭时它决定实际返回数上限。
     */
    private int candidatePool = 20;

    /** 最终返回给模型的片段数（重排后保留数）。 */
    private int maxResults = 5;

    /**
     * 是否把中文问题改写成英文检索词再走 BM25 那一路。
     * 关掉即回到「BM25 直接吃原问题」的老行为，用于对照评测。
     */
    private boolean rewriteEnabled = true;
}
