package com.springragforge.rag.ingest;

import com.springragforge.rag.source.SourceLinkResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 本地磁盘语料源：原 IncrementalIndexer 的扫盘逻辑搬到这里。
 *
 * <p>配置 {@code rag.ingest.storage=local} 时启用。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.ingest.storage", havingValue = "local")
public class LocalDocumentSource implements DocumentSource {

    private final IngestProperties properties;

    public LocalDocumentSource(IngestProperties properties) {
        this.properties = properties;
    }

    @Override
    public Map<String, StoredDocument> scan() {
        Path root = Path.of(properties.getCorpusRoot());
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("语料根目录不存在，已中止灌库（不会删除任何已有向量）: " + root);
        }

        SourceLinkResolver resolver = new SourceLinkResolver(properties.getCorpusRoot());
        Map<String, StoredDocument> files = new TreeMap<>();
        List<Path> unmapped = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(LocalDocumentSource::isAsciiDoc)
                    .forEach(path -> {
                        Path absolute = path.toAbsolutePath();
                        String docKey = resolver.documentKey(
                                absolute.getParent().toString(), absolute.getFileName().toString());
                        if (docKey.isEmpty()) {
                            // 不在语料根下就算不出 chunk_id，这种文件只能放弃
                            unmapped.add(absolute);
                            return;
                        }
                        StoredDocument document = new StoredDocument(
                                absolute.getParent().toString(),
                                absolute.getFileName().toString(),
                                () -> Files.readAllBytes(absolute));
                        StoredDocument previous = files.put(docKey, document);
                        if (previous != null) {
                            log.warn("docKey 冲突，后者覆盖前者: {} <= {}", absolute, previous);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("扫描语料目录失败: " + root, e);
        }

        for (Path path : unmapped) {
            log.warn("文件不在语料根下，跳过: {}", path);
        }
        log.info("语料扫描完成(本地): 根={} 待比对文档={} 未映射={}", root, files.size(), unmapped.size());
        return files;
    }

    private static boolean isAsciiDoc(Path path) {
        return path.getFileName().toString().toLowerCase().endsWith(".adoc");
    }
}
