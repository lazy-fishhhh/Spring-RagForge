package com.springragforge.rag.ingest;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * ES 索引结构维护：建索引 + 写 mapping。
 *
 * <p>为什么必须显式建：langchain4j 的 {@code ElasticsearchEmbeddingStore} 不负责建索引。
 * 索引不存在时 {@code addAll} 只能让 ES 自动建，自动建出来的 {@code vector} 字段是普通
 * float 数组而不是 {@code dense_vector}，之后 KNN 检索会报
 * {@code Field [vector] is not knn_vector}。所以 mapping 得我们自己保证。
 *
 * <p>字段名对齐 langchain4j 的实现：向量写 {@code vector}，正文写 {@code text}。
 * 维度必须等于 embedding 模型的输出维度，否则 ES 拒绝写入。
 */
@Slf4j
public final class EsIndexSchema {

    /** 向量字段名，对应 langchain4j 的 ElasticsearchConfiguration.VECTOR_FIELD。 */
    public static final String VECTOR_FIELD = "vector";

    private EsIndexSchema() {
    }

    /** 索引不存在才建。幂等，启动时调用。 */
    public static void ensureIndex(ElasticsearchClient client, String indexName, int dimension) {
        try {
            boolean exists = client.indices().exists(request -> request.index(indexName)).value();
            if (exists) {
                log.debug("ES 索引已存在，跳过创建: {}", indexName);
                return;
            }
            createIndex(client, indexName, dimension);
        } catch (IOException e) {
            throw new UncheckedIOException("检查或创建 ES 索引失败: " + indexName, e);
        }
    }

    /**
     * 删掉再按同一 mapping 重建。全量灌库要清空历史向量时走这条。
     *
     * <p>不直接用 store 的 {@code removeAll()}：ES 实现里它是删整个索引，
     * 删完 mapping 也没了，后续写入又退回自动建索引的老问题。
     */
    public static void recreateIndex(ElasticsearchClient client, String indexName, int dimension) {
        try {
            client.indices().delete(request -> request.index(indexName).ignoreUnavailable(true));
            createIndex(client, indexName, dimension);
            log.info("ES 索引已重建: {} (dense_vector {} 维)", indexName, dimension);
        } catch (IOException e) {
            throw new UncheckedIOException("重建 ES 索引失败: " + indexName, e);
        }
    }

    private static void createIndex(ElasticsearchClient client, String indexName, int dimension) throws IOException {
        client.indices().create(request -> request
                .index(indexName)
                .mappings(mappings -> mappings
                        .properties(VECTOR_FIELD, property -> property
                                .denseVector(vector -> vector
                                        .dims(dimension)
                                        .index(true)
                                        .similarity("cosine")))));
        log.info("ES 索引已创建: {} (dense_vector {} 维)", indexName, dimension);
    }
}
