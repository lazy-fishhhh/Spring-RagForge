package com.springragforge.config;

import com.springragforge.constant.SystemPrompts;
import com.springragforge.rag.SourceRegistry;
import com.springragforge.rag.ingest.IngestProperties;
import com.springragforge.rag.injector.NumberedContentInjector;
import com.springragforge.rag.retriever.SourceCapturingContentRetriever;
import com.springragforge.repository.RedisChatMemoryStore;
import com.springragforge.service.ChatService;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.search.simple.SimpleToolSearchStrategy;
import dev.langchain4j.skills.FileSystemSkillLoader;
import dev.langchain4j.skills.Skills;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

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
    public ChatService chatService(ContentRetriever contentRetriever) {
        // 用包装器包一层：召回的同时把片段元数据存进 SourceRegistry，供 Controller 回传来源
        ContentRetriever capturingRetriever = new SourceCapturingContentRetriever(contentRetriever, sourceRegistry);
        // 注入器要自己指定：默认那个不给片段编号、也不带出处，系统提示词里的引用规则就落不了地
        RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                .contentRetriever(capturingRetriever)
                .contentInjector(new NumberedContentInjector())
                .build();
        return AiServices.builder(ChatService.class)
                .systemMessage(SystemPrompts.SYSTEM_PROMPT)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider())
                .retrievalAugmentor(retrievalAugmentor)
                .build();
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


    //构建向量数据库操作对象
    @Bean
    public EmbeddingStore<TextSegment> store(IngestProperties properties) {
        // 启动阶段不做任何网络调用：有落盘文件就直接加载，否则返回空存储，
        // 等手动调 POST /admin/reindex 灌库（灌库逻辑见 IncrementalIndexer）
        Path storeFile = properties.getStoreFile();
        if (Files.exists(storeFile)) {
            return InMemoryEmbeddingStore.fromFile(storeFile);
        }
        return new InMemoryEmbeddingStore<>();
    }
    //构建向量数据库检索对象
    @Bean
    public ContentRetriever contentRetriever(EmbeddingStore<TextSegment> store, EmbeddingModel embeddingModel){
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .minScore(0.7)
                .maxResults(5)
                .build();
    }

}
