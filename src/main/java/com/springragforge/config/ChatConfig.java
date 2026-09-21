package com.springragforge.config;

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

    @Bean
    public ChatService chatService(ContentRetriever contentRetriever) {
        return AiServices.builder(ChatService.class)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider())
                .contentRetriever(contentRetriever)
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
    public EmbeddingStore<TextSegment> store() {
        // 启动阶段不做任何网络调用：有缓存文件则直接加载，否则返回空存储，由 EmbeddingInitializer 在应用启动完成后灌库
        Path cacheFile = Path.of("data/embedding-store.json");
        if (Files.exists(cacheFile)) {
            return InMemoryEmbeddingStore.fromFile(cacheFile);
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
                .maxResults(3)
                .build();
    }

}
