package com.springragforge.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.springragforge.constant.SystemPrompts;
import com.springragforge.rag.DegradationRegistry;
import com.springragforge.rag.SourceRegistry;
import com.springragforge.rag.ingest.EsIndexSchema;
import com.springragforge.rag.ingest.IngestProperties;
import com.springragforge.rag.injector.NumberedContentInjector;
import com.springragforge.rag.memory.CharApproxTokenCountEstimator;
import com.springragforge.rag.memory.MemoryProperties;
import com.springragforge.rag.memory.SummarizingChatMemory;
import com.springragforge.rag.ratelimit.RateLimitProperties;
import com.springragforge.rag.resilience.CachingContentAggregator;
import com.springragforge.rag.resilience.CachingQueryTransformer;
import com.springragforge.rag.resilience.RequestScopeCache;
import com.springragforge.rag.resilience.ResilienceProperties;
import com.springragforge.rag.retriever.CachingContentRetriever;
import com.springragforge.rag.retriever.CoreferenceQueryTransformer;
import com.springragforge.rag.retriever.HybridContentRetriever;
import com.springragforge.rag.retriever.QueryRewriter;
import com.springragforge.service.ResilientChatService;
import com.springragforge.rag.rerank.DashScopeScoringModel;
import com.springragforge.rag.rerank.RerankProperties;
import com.springragforge.rag.rerank.SourceCapturingContentAggregator;
import com.springragforge.rag.rerank.TopNContentAggregator;
import com.springragforge.rag.retriever.RetrievalProperties;
import com.springragforge.repository.RedisChatMemoryStore;
import com.springragforge.service.ChatService;
import com.springragforge.service.RateLimitedChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.aggregator.ReRankingContentAggregator;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.transformer.CompressingQueryTransformer;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.search.simple.SimpleToolSearchStrategy;
import dev.langchain4j.skills.FileSystemSkillLoader;
import dev.langchain4j.skills.Skills;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchConfigurationKnn;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
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
    public ChatService chatService(ContentRetriever contentRetriever,
                                   ContentAggregator contentAggregator,
                                   QueryTransformer queryTransformer,
                                   ChatMemoryProvider chatMemoryProvider,
                                   ResilienceProperties resilienceProperties,
                                   DegradationRegistry degradationRegistry,
                                   RequestScopeCache requestScopeCache,
                                   RateLimitProperties rateLimitProperties,
                                   RateLimiter chatRateLimiter,
                                   Bulkhead chatBulkhead) {
        // 检索管线四段：改写/消解(transformer) → 召回(retriever) → 聚合/重排(aggregator) → 注入(injector)。
        // 注入器要自己指定：默认那个不给片段编号、也不带出处，系统提示词里的引用规则就落不了地
        RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                .queryTransformer(queryTransformer)
                .contentRetriever(contentRetriever)
                .contentAggregator(contentAggregator)
                .contentInjector(new NumberedContentInjector())
                .build();
        ChatService assistant = AiServices.builder(ChatService.class)
                .systemMessage(SystemPrompts.SYSTEM_PROMPT)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .storeRetrievedContentInChatMemory(false)
                .retrievalAugmentor(retrievalAugmentor)
                .build();
        // 代理外面套一层超时/重试/降级；控制器和评测走接口，拿到的是这一层
        ChatService resilient = new ResilientChatService(assistant, resilienceProperties, sourceRegistry,
                degradationRegistry, requestScopeCache);
        // 限流在最外层：一次请求（含内部重试）只算一个令牌、占一个并发位
        return new RateLimitedChatService(resilient, rateLimitProperties, chatRateLimiter, chatBulkhead);
    }

    /**
     * 速率闸：令牌桶。每 {@code limitRefreshPeriod} 补满 {@code limitForPeriod} 个令牌，
     * 取不到就立刻拒绝（timeoutDuration=0，不排队阻塞反应式线程）。
     * RateLimiter = 令牌桶限流器。它按固定周期往桶里补充令牌，每个请求来取一个令牌，取到才放行。
     */
    @Bean
    public RateLimiter chatRateLimiter(RateLimitProperties properties) {
        return RateLimiter.of("chat", RateLimiterConfig.custom()
                // 每个刷新周期补多少令牌 = 每周期最多放行多少个请求
                .limitForPeriod(properties.getLimitForPeriod())
                // 刷新周期：多久补一次令牌（配合上一项，决定"每秒/每分钟多少个请求"）
                .limitRefreshPeriod(properties.getLimitRefreshPeriod())
                // 取不到令牌时的等待时长。ZERO = 不等、不排队，立刻抛 RequestNotPermitted。
                // 必须为 0：Reactor 线程是共享事件循环，一旦在此阻塞会拖垮整条链。
                .timeoutDuration(Duration.ZERO)
                .build());
    }

    /**
     * 并发闸：信号量型。限制同时在途的请求数，取不到立刻拒绝（maxWaitDuration=0）。
     * 默认就是 SEMAPHORE 类型，maxConcurrentCalls 即为许可总数。
     * Bulkhead = 并发数限制器（隔板）。它限制同时在途的请求数——同时有几个请求在跑，超过就拒绝。
     */
    @Bean
    public Bulkhead chatBulkhead(RateLimitProperties properties) {
        return Bulkhead.of("chat", BulkheadConfig.custom()
                // 最大同时在途请求数 = 许可总数。超过就取不到许可，进入拒绝分支。
                .maxConcurrentCalls(properties.getMaxConcurrentCalls())
                // 取不到许可时的等待时长。ZERO = 不等、不排队，立刻抛 BulkheadFullException。
                // 同速率闸：不能在响应式线程上阻塞等待。
                .maxWaitDuration(Duration.ZERO)
                .build());
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
                                               ObjectMapper objectMapper,
                                               RequestScopeCache requestScopeCache,
                                               ResilienceProperties resilienceProperties) {
        int maxResults = retrievalProperties.getMaxResults();
        // 自写打分模型，填框架缺口；参数全来自配置
        ContentAggregator inner;
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
            inner = new SourceCapturingContentAggregator(rerank, sourceRegistry);
        } else {
            inner = new SourceCapturingContentAggregator(new TopNContentAggregator(maxResults), sourceRegistry);
        }
        // 缓存包在最外层：命中就整个跳过重排，重试时不再打重排接口
        return new CachingContentAggregator(inner, requestScopeCache, resilienceProperties);
    }

    /**
     * 多轮指代消解层：挂在 {@link RetrievalAugmentor} 最前，把带指代的追问结合会话历史改成独立问题。
     * 复用官方 {@link CompressingQueryTransformer}，外面包失败回退（见 {@link CoreferenceQueryTransformer}）。
     * 用非流式 {@link ChatModel}：消解要在检索前拿到完整结果，流式没有意义。
     *
     * <p>最外层再包请求级缓存：重试时不再重打消解 LLM，也避免改写文本漂移把下游检索缓存全部打穿。
     */
    @Bean
    public QueryTransformer queryTransformer(ChatModel chatModel,
                                             RequestScopeCache requestScopeCache,
                                             ResilienceProperties resilienceProperties) {
        QueryTransformer coreference =
                new CoreferenceQueryTransformer(new CompressingQueryTransformer(chatModel));
        return new CachingQueryTransformer(coreference, requestScopeCache, resilienceProperties);
    }

    /**
     * 会话记忆：带滚动摘要的 token 窗口，替代原来的纯滑窗（maxMessages=10）。
     * 复用非流式 {@link ChatModel} 做摘要——和 query 改写同一个模型，温度 0。
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(ChatModel chatModel, MemoryProperties memoryProperties) {
        TokenCountEstimator tokenCountEstimator = new CharApproxTokenCountEstimator();
        return memoryId -> new SummarizingChatMemory(
                memoryId, redisChatMemoryStore, chatModel, tokenCountEstimator, memoryProperties);
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
                                             QueryRewriter queryRewriter,
                                             RequestScopeCache requestScopeCache,
                                             ResilienceProperties resilienceProperties) {
        // 换成混合检索：BM25 + KNN 双路，RRF 融合。实现见 HybridContentRetriever。
        // BM25 那一路吃 QueryRewriter 改写出的英文关键词串，见该类说明。
        HybridContentRetriever hybrid = new HybridContentRetriever(elasticsearchClient, embeddingModel,
                ingestProperties.getIndexName(), retrievalProperties, queryRewriter);
        // 缓存包在外面：重试时同一次提问不再重打 ES / embedding
        return new CachingContentRetriever(hybrid, requestScopeCache, resilienceProperties);
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
