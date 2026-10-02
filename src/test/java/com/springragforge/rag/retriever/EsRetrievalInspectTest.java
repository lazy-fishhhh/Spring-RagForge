package com.springragforge.rag.retriever;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.springragforge.rag.ingest.IngestProperties;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.elasticsearch.Document;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.List;

/**
 * ES 数据检查：连真实 ES，看索引里有什么、BM25 和 KNN 各自召回什么、RRF 融合排成什么样。
 * 只打印不断言（除了「索引非空」这个前置条件），用来肉眼核对灌库结果和调检索参数。
 *
 * <p>默认不跑。跑法：
 * <pre>mvn test -Dtest=EsRetrievalInspectTest -Des.run=true -Des.q="how to declare before advice"</pre>
 *
 * <p>前置条件：ES 起着、向量库已灌（{@code POST /admin/reindex}），
 * 且能连上 Redis 和模型接口（Spring 全上下文启动需要）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "es.run", matches = "true")
class EsRetrievalInspectTest {

    /** 摘录片段正文时打印多少字符，够判断是不是对的那一节即可。 */
    private static final int TEXT_PREVIEW = 120;

    @Resource
    private ElasticsearchClient client;

    @Resource
    private EmbeddingModel embeddingModel;

    @Resource
    private IngestProperties ingestProperties;

    @Resource
    private RetrievalProperties retrievalProperties;

    @Resource
    private QueryRewriter queryRewriter;

    @Test
    void inspect() throws IOException {
        String index = ingestProperties.getIndexName();
        String question = System.getProperty("es.q", "how to declare before advice");

        printIndexOverview(index);
        printSampleDocument(index);
//        printBm25Ranking(index, question);
//        printKnnRanking(index, question);
//        printHybridResult(index, question);
    }

    /** 索引是否存在、有多少文档、mapping 长什么样。 */
    private void printIndexOverview(String index) throws IOException {
        boolean exists = client.indices().exists(r -> r.index(index)).value();
        System.out.println("== 索引 ==\nindex = " + index + ", exists = " + exists);
        Assumptions.assumeTrue(exists, "索引不存在，先 POST /admin/reindex");

        long count = client.count(c -> c.index(index)).count();
        System.out.println("doc count = " + count);
        Assumptions.assumeTrue(count > 0, "索引为空，先 POST /admin/reindex");

        String mapping = client.indices().getMapping(m -> m.index(index))
                .result().get(index).mappings().toString();
        System.out.println("mapping = " + mapping);
    }

    /** 随便捞一条，确认 _source 里的 text / metadata 是灌库时写进去的样子。 */
    private void printSampleDocument(String index) throws IOException {
        SearchResponse<Document> response = client.search(s -> s.index(index).size(1)
                .source(src -> src.filter(f -> f.excludes(HybridContentRetriever.VECTOR_FIELD))), Document.class);
        List<Hit<Document>> hits = response.hits().hits();
        if (hits.isEmpty()) {
            System.out.println("== 样本文档 ==\n没有命中");
            return;
        }
        Document doc = hits.get(0).source();
        Metadata metadata = doc == null || doc.getMetadata() == null
                ? new Metadata() : Metadata.from(doc.getMetadata());
        System.out.println("== 样本文档 ==\nid = " + hits.get(0).id()
                + "\nmetadata = " + metadata.toMap()
                + "\ntext = " + preview(doc == null ? null : doc.getText()));
    }

    /** BM25 路单独排名。 */
    private void printBm25Ranking(String index, String question) throws IOException {
        SearchResponse<Document> response = client.search(s -> s.index(index)
                .size(retrievalProperties.getBm25TopK())
                .query(q -> q.multiMatch(m -> m.fields(HybridContentRetriever.BM25_FIELDS).query(question)))
                .source(src -> src.filter(f -> f.excludes(HybridContentRetriever.VECTOR_FIELD))), Document.class);
        System.out.println("== BM25 排名 ==\nquery = " + question);
        int rank = 1;
        for (Hit<Document> hit : response.hits().hits()) {
            System.out.printf("  %2d. score=%.4f id=%s | heading=%s%n",
                    rank++, hit.score(), hit.id(), heading(hit.source()));
        }
    }

    /** KNN 路单独排名。 */
    private void printKnnRanking(String index, String question) throws IOException {
        Embedding queryEmbedding = embeddingModel.embed(question).content();
        SearchResponse<Document> response = client.search(s -> s.index(index)
                .size(retrievalProperties.getKnnTopK())
                .knn(knn -> knn.field(HybridContentRetriever.VECTOR_FIELD)
                        .queryVector(queryEmbedding.vectorAsList())
                        .k(retrievalProperties.getKnnTopK())
                        .numCandidates(retrievalProperties.getKnnNumCandidates()))
                .source(src -> src.filter(f -> f.excludes(HybridContentRetriever.VECTOR_FIELD))), Document.class);
        System.out.println("== KNN 排名 ==\nquery = " + question
                + ", dim = " + queryEmbedding.dimension());
        int rank = 1;
        for (Hit<Document> hit : response.hits().hits()) {
            System.out.printf("  %2d. score=%.4f id=%s | heading=%s%n",
                    rank++, hit.score(), hit.id(), heading(hit.source()));
        }
    }

    /** 走完整混合检索，看最终喂给模型的顺序。 */
    private void printHybridResult(String index, String question) {
        HybridContentRetriever retriever = new HybridContentRetriever(
                client, embeddingModel, index, retrievalProperties, queryRewriter);
        List<Content> contents = retriever.retrieve(Query.from(question));
        System.out.println("== 混合检索（RRF 融合后，maxResults=" + retrievalProperties.getMaxResults() + "）==");
        for (int i = 0; i < contents.size(); i++) {
            TextSegment segment = contents.get(i).textSegment();
            Metadata metadata = segment.metadata();
            System.out.printf("  %2d. source=%s | heading=%s%n     %s%n",
                    i + 1, metadata.getString("source"), metadata.getString("heading_path"),
                    preview(segment.text()));
        }
        if (contents.isEmpty()) {
            System.out.println("  （空：两路都没召回，检查灌库和查询词）");
        }
    }

    private static String heading(Document doc) {
        if (doc == null || doc.getMetadata() == null) {
            return "";
        }
        Object value = doc.getMetadata().get("heading_path");
        return value == null ? "" : value.toString();
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= TEXT_PREVIEW ? flat : flat.substring(0, TEXT_PREVIEW) + "...";
    }
}
