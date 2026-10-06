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

    /**
     * 语料根。local 存储时是真实磁盘目录；minio 存储时是虚拟根名，
     * 只参与 chunk_id 的前缀换算，对象 key 相对 bucket 根计算。
     */
    private String corpusRoot = SourceLinkResolver.DEFAULT_CORPUS_ROOT;

    /** 向量库落盘文件。启动时从这里加载，灌库后写回。 */
    private Path storeFile = Path.of("data/embedding-store.json");

    /** 灌库清单：每个文档的哈希和它产出的向量 id。增量比对靠它。 */
    private Path manifestFile = Path.of("data/ingest-manifest.json");

    /** ES 向量索引名。灌库建索引和检索查索引必须用同一个，所以放配置里共用。
     * 下面这俩都是es中使用的
     */
    private String indexName = "rag_knowledge";

    /**
     * ES 索引里 dense_vector 的维度。必须等于 embedding 模型的输出维度，
     * 不一致时 ES 会直接拒绝写入。
     *
     * <p>注意：这个值没法从模型名推出来，换 embedding 模型时要同步改这里，
     * 并把旧索引删掉重建（维度变了 mapping 不兼容）。
     */
    private int dimension = 1024;
}
