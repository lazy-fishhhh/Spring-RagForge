package com.springragforge.rag.ingest;

import com.springragforge.rag.source.SourceLinkResolver;
import com.springragforge.storage.MinioProperties;
import com.springragforge.storage.MinioStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * MinIO 语料源：bucket 里的对象 key 就是相对语料根的文档路径，
 * 例如 {@code spring-boot/reference/pages/web/servlet.adoc}。
 *
 * <p>扫描时把对象 key 还原成「虚拟语料根 + 目录段」喂给 SourceLinkResolver，
 * 算出的 docKey / chunk_id 和本地实现完全一致，灌库其余逻辑不用区分存储。
 *
 * <p>配置 {@code rag.ingest.storage=minio}（缺省即此值）时启用。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.ingest.storage", havingValue = "minio", matchIfMissing = true)
public class MinioDocumentSource implements DocumentSource {

    private final IngestProperties properties;
    private final MinioProperties minioProperties;
    private final MinioStorageService storageService;

    public MinioDocumentSource(
            IngestProperties properties,
            MinioProperties minioProperties,
            MinioStorageService storageService) {
        this.properties = properties;
        this.minioProperties = minioProperties;
        this.storageService = storageService;
    }

    @Override
    public Map<String, StoredDocument> scan() {
        List<String> objectNames = storageService.listObjectNames();
        SourceLinkResolver resolver = new SourceLinkResolver(properties.getCorpusRoot());
        String root = normalizeRoot(properties.getCorpusRoot());

        Map<String, StoredDocument> files = new TreeMap<>();
        List<String> unmapped = new ArrayList<>();
        for (String objectName : objectNames) {
            if (!objectName.toLowerCase().endsWith(".adoc")) {
                continue;
            }
            int slash = objectName.lastIndexOf('/');
            String relativeDir = slash < 0 ? "" : objectName.substring(0, slash);
            String fileName = slash < 0 ? objectName : objectName.substring(slash + 1);
            String virtualDirectory = relativeDir.isEmpty() ? root : root + "/" + relativeDir;

            String docKey = resolver.documentKey(virtualDirectory, fileName);
            if (docKey.isEmpty()) {
                unmapped.add(objectName);
                continue;
            }
            StoredDocument document = new StoredDocument(
                    virtualDirectory,
                    fileName,
                    () -> storageService.download(objectName));
            StoredDocument previous = files.put(docKey, document);
            if (previous != null) {
                log.warn("docKey 冲突，后者覆盖前者: {} <= {}", objectName, previous);
            }
        }

        for (String objectName : unmapped) {
            log.warn("对象不在语料根下，跳过: {}", objectName);
        }
        log.info("语料扫描完成(MinIO): bucket={} 待比对文档={} 未映射={}",
                minioProperties.getBucket(), files.size(), unmapped.size());
        return files;
    }

    /** 反斜杠换正斜杠，去掉尾部斜杠。 */
    private static String normalizeRoot(String corpusRoot) {
        String normalized = corpusRoot.replace('\\', '/');
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
