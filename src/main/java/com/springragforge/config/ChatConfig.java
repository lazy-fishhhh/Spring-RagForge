package com.springragforge.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.springragforge.constant.SystemPrompts;
import com.springragforge.rag.SourceRegistry;
import com.springragforge.rag.ingest.EsIndexSchema;
import com.springragforge.rag.ingest.IngestProperties;
import com.springragforge.rag.injector.NumberedContentInjector;
import com.springragforge.rag.retriever.HybridContentRetriever;
import com.springragforge.rag.retriever.QueryRewriter;
import com.springragforge.rag.rerank.DashScopeScoringModel;
import com.springragforge.rag.rerank.RerankProperties;
import com.springragforge.rag.rerank.SourceCapturingContentAggregator;
import com.springragforge.rag.rerank.TopNContentAggregator;
import com.springragforge.rag.retriever.RetrievalProperties;
import com.springragforge.repository.RedisChatMemoryStore;
import com.springragforge.service.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.aggregator.ReRankingContentAggregator;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.search.simple.SimpleToolSearchStrategy;
import dev.langchain4j.skills.FileSystemSkillLoader;
import dev.langchain4j.skills.Skills;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchConfigurationKnn;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.Resource;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

@Configuration
public class ChatConfig {

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    // ---- 新增依赖注入 ----
    @Resource
    private StreamingChatModel streamingChatModel;

    @Resource
    private SourceRegistry sourceRegistry;

    @Bean
    public ChatService chatService(ContentRetriever contentRetriever, ContentAggregator contentAggregator) {
        // 检索管线三段：召回(retriever) → 聚合/重排(aggregator) → 注入(injector)。
        // 注入器要自己指定：默认那个不给片段编号、也不带出处，系统提示词里的引用规则就落不了地
        RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                .contentRetriever(contentRetriever)
                .contentAggregator(contentAggregator)
                .contentInjector(new NumberedContentInjector())
                .build();
        return AiServices.builder(ChatService.class)
                .systemMessage(SystemPrompts.SYSTEM_PROMPT)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider())
                .retrievalAugmentor(retrievalAugmentor)
                .build();
    }

    /**
     * 聚合段：重排就发生在这里。曝光成 bean 是为了让检索层评测也能走到重排
     * （RetrievalEvalTest 直接注入它），否则那个测试只调 ContentRetriever，这一级被跳过。
     *
     * <p>重排开启：{@link ReRankingContentAggregator} 对候选池逐条打分、排序、取前 N；
     * 关闭：只按 RRF 融合顺序截前 N（对照评测用）。两条路都用来源捕获包一层——
     * 捕获点必须在聚合之后，否则回传前端的是重排前的候选池，会有没被引用的链接。
     */
    @Bean
    public ContentAggregator contentAggregator(RetrievalProperties retrievalProperties,
                                               RerankProperties rerankProperties,
                                               ObjectMapper objectMapper) {
        int maxResults = retrievalProperties.getMaxResults();
        // 自写打分模型，填框架缺口；参数全来自配置
        if (rerankProperties.isEnabled()) {
            ScoringModel scoringModel = new DashScopeScoringModel(
                    rerankProperties.getBaseUrl(),
                    rerankProperties.getApiKey(),
                    rerankProperties.getModelName(),
                    Duration.ofSeconds(rerankProperties.getTimeoutSeconds()),
                    objectMapper);
            // 框架现成的聚合器：打分 → 降序 → minScore 过滤 → 截断
            ContentAggregator rerank = ReRankingContentAggregator.builder()
                    .scoringModel(scoringModel)
                    .minScore(rerankProperties.getMinScore())
                    .maxResults(maxResults)
                    .build();
            return new SourceCapturingContentAggregator(rerank, sourceRegistry);
        }
        return new SourceCapturingContentAggregator(new TopNContentAggregator(maxResults), sourceRegistry);
    }

    @Bean
    public ChatMemoryProvider chatMemoryProvider() {
        return new ChatMemoryProvider() {
            @Override
            public ChatMemory get(Object memoryId) {
                return MessageWindowChatMemory.builder()
                        .maxMessages(10)
                        .chatMemoryStore(redisChatMemoryStore)
                        .id(memoryId.toString())
                        .build();
            }
        };
    }

    // 连接es
    @Bean
    public ElasticsearchClient elasticsearchClient() {
        // 1. 底层HTTP Rest客户端，连接你的ES服务地址：192.168.16.128:9200
        RestClient restClient = RestClient.builder(
                HttpHost.create("http://192.168.16.128:9200")
        ).build();
        // 2. 传输层：把Java对象和ES返回的JSON互相转换（Jackson做序列化）
        RestClientTransport transport = new RestClientTransport(restClient, new co.elastic.clients.json.jackson.JacksonJsonpMapper());
        // 3. 高层ES客户端，供langchain4j使用
        return new ElasticsearchClient(transport);
    }
    // 构建向量数据库操作对象
    @Bean
    public EmbeddingStore<TextSegment> store(ElasticsearchClient elasticsearchClient, IngestProperties properties) {
        // ES 不会自动建出正确 mapping，索引和 vector 字段的 dense_vector 类型得我们自己保证，
        // 否则 KNN 检索报 "not knn_vector"。这里在返回 store 之前建好。
        EsIndexSchema.ensureIndex(
                elasticsearchClient, properties.getIndexName(), properties.getDimension());
        return ElasticsearchEmbeddingStore.builder()
                .client(elasticsearchClient) // 注入上面建好的ES客户端
                .indexName(properties.getIndexName())  // ES里存储向量的索引名称
                .build();
    }
//    @Bean
//    public EmbeddingStore<TextSegment> store(IngestProperties properties) {
//        // 启动阶段不做任何网络调用：有落盘文件就直接加载，否则返回空存储，
//        // 等手动调 POST /admin/reindex 灌库（灌库逻辑见 IncrementalIndexer）
//        Path storeFile = properties.getStoreFile();
//        if (Files.exists(storeFile)) {
//            return InMemoryEmbeddingStore.fromFile(storeFile);
//        }
//        return new InMemoryEmbeddingStore<>();
//    }
    // 构建向量数据库检索对象
    @Bean
    public ContentRetriever contentRetriever(ElasticsearchClient elasticsearchClient,
                                             EmbeddingModel embeddingModel,
                                             IngestProperties ingestProperties,
                                             RetrievalProperties retrievalProperties,
                                             QueryRewriter queryRewriter) {
        // 换成混合检索：BM25 + KNN 双路，RRF 融合。实现见 HybridContentRetriever。
        // BM25 那一路吃 QueryRewriter 改写出的英文关键词串，见该类说明。
        return new HybridContentRetriever(elasticsearchClient, embeddingModel,
                ingestProperties.getIndexName(), retrievalProperties, queryRewriter);
    }
//    // 老逻辑（纯向量检索），保留备查：minScore 是内存/向量分数语义下的阈值，
//    // RRF 融合后的分数是 1/(k+rank) 量级（约 0.016），这套阈值不适用，所以换成混合后不再用。
//    @Bean
//    public ContentRetriever contentRetriever(EmbeddingStore<TextSegment> store, EmbeddingModel embeddingModel){
//        return EmbeddingStoreContentRetriever.builder()
//                .embeddingStore(store)
//                .embeddingModel(embeddingModel)
//                .minScore(0.7)
//                .maxResults(5)
//                .build();
//    }

}
