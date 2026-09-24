package com.springragforge.config;

import com.springragforge.rag.splitter.AsciiDocDocumentSplitter;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingInitializer implements ApplicationRunner {

    private static final Path CACHE_FILE = Path.of("data/embedding-store.json");

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> store;

    @Override
    public void run(ApplicationArguments args) {
        if (Files.exists(CACHE_FILE)) {
            log.info("检测到向量库缓存文件，跳过文档灌库: {}", CACHE_FILE);
            return;
        }
        try {
            List<Document> documents = FileSystemDocumentLoader.loadDocuments(Path.of("E:\\springDoc\\corpus\\spring-framework\\core\\aop\\ataspectj"), new TextDocumentParser());
            EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                    .documentSplitter(new AsciiDocDocumentSplitter())
                    .embeddingModel(new BatchEmbeddingModel(embeddingModel))
                    .embeddingStore(store)
                    .build();
            ingestor.ingest(documents);

            if (store instanceof InMemoryEmbeddingStore<?> inMemoryStore) {
                Files.createDirectories(CACHE_FILE.getParent());
                inMemoryStore.serializeToFile(CACHE_FILE);
            }
            log.info("文档灌库完成，向量库已缓存到 {}", CACHE_FILE);
        } catch (Exception e) {
            // 灌库失败不阻塞启动，应用照常运行；排查网络/embedding 服务后可删除缓存文件重启重试
            log.error("文档灌库失败，应用仍可正常启动。请排查网络/embedding 服务后，删除缓存文件并重启重试。", e);
        }
    }
}