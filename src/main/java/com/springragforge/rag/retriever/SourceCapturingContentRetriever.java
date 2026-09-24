package com.springragforge.rag.retriever;

import com.springragforge.rag.SourceInfo;
import com.springragforge.rag.SourceRegistry;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.ArrayList;
import java.util.List;

/**
 * 包装原始 ContentRetriever，在召回的同时把片段的编号、出处、链接提取出来，
 * 存入 SourceRegistry，供 Controller 在流式回答结束后回传给前端。
 *
 * <p>来源信息由后端代码保证准确，不依赖模型自觉输出。
 */
public class SourceCapturingContentRetriever implements ContentRetriever {

    private final ContentRetriever delegate;
    private final SourceRegistry registry;

    public SourceCapturingContentRetriever(ContentRetriever delegate, SourceRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    // 召回片段时，提取编号、出处、链接，存入 SourceRegistry，供 Controller 回传给前端
    @Override
    public List<Content> retrieve(Query query) {
        List<Content> contents = delegate.retrieve(query);
        Object memoryId = query.metadata().chatMemoryId();
        if (memoryId != null) {
            registry.put(memoryId.toString(), toSourceInfos(contents));
        }
        return contents;
    }

    private static List<SourceInfo> toSourceInfos(List<Content> contents) {
        List<SourceInfo> sources = new ArrayList<>(contents.size());
        for (int i = 0; i < contents.size(); i++) {
            TextSegment segment = contents.get(i).textSegment();
            Metadata metadata = segment.metadata();
            String origin = extractOrigin(metadata);
            String url = metadata.getString("source");
            sources.add(new SourceInfo(i + 1, origin, url));
        }
        return sources;
    }

    /** 出处优先用标题路径，没有就退到文档标题，再没有就用文件名。与 NumberedContentInjector 保持一致。 */
    private static String extractOrigin(Metadata metadata) {
        for (String key : List.of("heading_path", "doc_title", "file_name")) {
            String value = metadata.getString(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
