package com.springragforge.rag.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * 语料文档来源。灌库只认这个接口，不关心文档在本地磁盘还是 MinIO。
 *
 * <p>扫描只取定位信息，内容按需通过 {@link StoredDocument#readContent()} 读，
 * 避免一次把整个语料库加载进内存。
 */
public interface DocumentSource {

    /**
     * 扫描全部可灌库文档，返回 docKey 到文档的映射。
     *
     * @throws IllegalStateException 语料根不存在等配置问题，调用方据此在动数据前中止
     */
    Map<String, DocumentSource.StoredDocument> scan();

    /**
     * 一篇语料文档的定位信息和内容读取方式。
     *
     * <p>{@code virtualDirectoryPath} 是喂给 SourceLinkResolver 的「绝对目录路径」：
     * 本地实现就是真实父目录；MinIO 实现拼成「语料根 + 对象 key 的目录段」，
     * 保证两种存储算出的 docKey、chunk_id 同源。
     *
     * @param virtualDirectoryPath 虚拟绝对目录路径
     * @param fileName             文件名
     * @param reader               内容读取器
     */
    record StoredDocument(String virtualDirectoryPath, String fileName, ContentReader reader) {

        public byte[] readContent() {
            try {
                return reader.read();
            } catch (IOException e) {
                throw new UncheckedIOException("读取语料内容失败: " + fileName, e);
            }
        }
    }

    /** 内容读取函数式接口，允许抛 IOException。 */
    @FunctionalInterface
    interface ContentReader {
        byte[] read() throws IOException;
    }
}
