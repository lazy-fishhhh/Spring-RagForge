package com.springragforge.rag.ingest;

import com.springragforge.rag.source.SourceLinkResolver;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 灌库相关配置。
 *
 * <p>三个路径都在同一个命名空间下，因为它们必须一致：{@code corpusRoot} 决定 chunk_id 怎么算，
 * {@code manifestFile} 记的历史 chunk_id 要和 {@code storeFile} 里的向量对得上，对不上就白删。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rag.ingest")
public class IngestProperties {

    /** 语料根目录。下面一层是语料源（spring-framework / spring-boot / spring-ai）。 */
    private String corpusRoot = SourceLinkResolver.DEFAULT_CORPUS_ROOT;

    /** 向量库落盘文件。启动时从这里加载，灌库后写回。 */
    private Path storeFile = Path.of("data/embedding-store.json");

    /** 灌库清单：每个文档的哈希和它产出的向量 id。增量比对靠它。 */
    private Path manifestFile = Path.of("data/ingest-manifest.json");
}
