package com.springragforge.rag.retriever;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.elasticsearch.Document;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ES 混合检索：BM25 走一路、KNN 走一路，再在 Java 里用 RRF 融合。
 *
 * <p>为什么不用 {@code EmbeddingStoreContentRetriever}：它只做向量召回，关键词精确命中
 * （类名、方法名、配置项）容易被 embedding 抹平。BM25 正好补这一块，两路融合更稳。
 *
 * <p>但 BM25 是词法匹配，中文问句在英文语料里命中数为零，所以它吃的是
 * {@link QueryRewriter} 改写出来的英文关键词串，不是原始问题。
 *
 * <p>为什么 RRF 放在 Java 而不是交给 ES：ES 服务端的 {@code rrf} retriever 要 8.16+，
 * 本地是 8.15，而且用户要的就是自己实现融合逻辑。代价是发两次请求，
 * 但候选池不大（默认各 20 条），可以接受。
 *
 * <p>返回的片段仍带完整元数据（chunk_id / source / heading_path...），
 * 所以外层 {@link SourceCapturingContentRetriever} 和 NumberedContentInjector 照常能工作。
 */
@Slf4j
public class HybridContentRetriever implements ContentRetriever {

    /** 向量字段名，对应 langchain4j 的 ElasticsearchConfiguration.VECTOR_FIELD。 */
    static final String VECTOR_FIELD = "vector";
    /** 正文片段字段名。 */
    static final String TEXT_FIELD = "text";
    /**
     * BM25 检索的字段与权重。正文权重 1，标题类字段权重 2——
     * 标题命中往往比正文命中更能说明「就是这一节」。
     * metadata.* 是 langchain4j 存元数据的对象字段，动态 mapping 会建出对应子字段。
     */
    static final List<String> BM25_FIELDS = List.of(
            TEXT_FIELD + "^1", "metadata.doc_title^2", "metadata.heading_path^2");

    private final ElasticsearchClient client;
    private final EmbeddingModel embeddingModel;
    private final String indexName;
    private final RetrievalProperties properties;
    private final QueryRewriter queryRewriter;

    public HybridContentRetriever(ElasticsearchClient client,
                                  EmbeddingModel embeddingModel,
                                  String indexName,
                                  RetrievalProperties properties,
                                  QueryRewriter queryRewriter) {
        this.client = client;
        this.embeddingModel = embeddingModel;
        this.indexName = indexName;
        this.properties = properties;
        this.queryRewriter = queryRewriter;
    }

    @Override
    public List<Content> retrieve(Query query) {
        String text = query.text();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            // 两路各自取候选，命中内容按 _id 去重收集，供融合后按 id 取回
            Map<String, Document> hitsById = new LinkedHashMap<>();
            // BM25 走改写后的英文关键词串（语料是英文），KNN 仍用原始问题（跨语言 embedding）
            List<String> bm25Ids = bm25Search(queryRewriter.rewrite(text), hitsById);
            // 老逻辑：BM25 直接吃原始问题。中文 token 在英文索引里命中数为零，那一路等于空转，
            // 保留备查。
//            List<String> bm25Ids = bm25Search(text, hitsById);
            List<String> knnIds = knnSearch(text, hitsById);

            List<ReciprocalRankFusion.Fused> fused =
                    ReciprocalRankFusion.fuse(List.of(bm25Ids, knnIds), properties.getRrfK());

            List<Content> contents = new ArrayList<>();
            for (int i = 0; i < fused.size() && contents.size() < properties.getMaxResults(); i++) {
                Document doc = hitsById.get(fused.get(i).id());
                if (doc != null) {
                    contents.add(toContent(doc));
                }
            }
            log.debug("混合检索: index={} bm25={} knn={} 融合取={}", indexName,
                    bm25Ids.size(), knnIds.size(), contents.size());
            return contents;
        } catch (IOException e) {
            throw new UncheckedIOException("ES 混合检索失败, index=" + indexName, e);
        }
    }

    /** BM25 路：multi_match 打正文和标题字段。 */
    private List<String> bm25Search(String text, Map<String, Document> hitsById) throws IOException {
        SearchResponse<Document> response = client.search(request -> request
                .index(indexName)
                .size(properties.getBm25TopK())
                .query(q -> q.multiMatch(m -> m.fields(BM25_FIELDS).query(text)))
                .source(source -> source.filter(filter -> filter.excludes(VECTOR_FIELD))), Document.class);
        return collect(response, hitsById);
    }

    /** KNN 路：ES 的 dense_vector 近邻检索，numCandidates 越大越准。 */
    private List<String> knnSearch(String text, Map<String, Document> hitsById) throws IOException {
        Embedding queryEmbedding = embeddingModel.embed(text).content();
        SearchResponse<Document> response = client.search(request -> request
                .index(indexName)
                .size(properties.getKnnTopK())
                .knn(knn -> knn.field(VECTOR_FIELD)
                        .queryVector(queryEmbedding.vectorAsList())
                        .k(properties.getKnnTopK())
                        .numCandidates(properties.getKnnNumCandidates()))
                .source(source -> source.filter(filter -> filter.excludes(VECTOR_FIELD))), Document.class);
        return collect(response, hitsById);
    }

    /** 取命中顺序里的 id 列表，同时把内容按 id 存好备用。 */
    private static List<String> collect(SearchResponse<Document> response, Map<String, Document> hitsById) {
        List<String> ids = new ArrayList<>(response.hits().hits().size());
        for (Hit<Document> hit : response.hits().hits()) {
            if (hit.source() == null) {
                continue;
            }
            ids.add(hit.id());
            hitsById.putIfAbsent(hit.id(), hit.source());
        }
        return ids;
    }

    /** 把 ES 里存的 Document 还原成带元数据的片段。元数据是灌库时写进去的那份，原样带回。 */
    private static Content toContent(Document doc) {
        Map<String, Object> raw = doc.getMetadata();
        Metadata metadata = raw == null ? new Metadata() : Metadata.from(raw);
        return Content.from(TextSegment.from(doc.getText(), metadata));
    }
}
