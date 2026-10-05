package com.springragforge.rag.rerank;

import com.springragforge.rag.SourceInfo;
import com.springragforge.rag.SourceRegistry;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 包装 ContentAggregator：在最终（重排后）片段列表产出的同时，
 * 把编号、出处、链接存入 SourceRegistry，供 Controller 在回答结束后回传前端。
 *
 * <p>捕获点必须放在聚合器之后，不能放在 ContentRetriever 上：重排前的候选池有几十条，
 * 而真正喂给模型的只有重排后的前 N 条。在召回处捕获会把没被引用的链接也回传前端。
 */
public class SourceCapturingContentAggregator implements ContentAggregator {

    private final ContentAggregator delegate;
    private final SourceRegistry registry;

    public SourceCapturingContentAggregator(ContentAggregator delegate, SourceRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
        List<Content> contents = delegate.aggregate(queryToContents);
        Object memoryId = queryToContents.keySet().stream()
                .map(SourceCapturingContentAggregator::chatMemoryIdOf)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (memoryId != null) {
            registry.put(memoryId.toString(), toSourceInfos(contents));
        }
        return contents;
    }

    /** Query.from(text) 造出来的 Query 没有 metadata，这里要防 NPE。 */
    private static Object chatMemoryIdOf(Query query) {
        return query.metadata() == null ? null : query.metadata().chatMemoryId();
    }

    private static List<SourceInfo> toSourceInfos(List<Content> contents) {
        List<SourceInfo> sources = new ArrayList<>(contents.size());
        for (int i = 0; i < contents.size(); i++) {
            TextSegment segment = contents.get(i).textSegment();
            Metadata metadata = segment.metadata();
            String origin = extractOrigin(metadata);
            String url = metadata.getString("source");
            sources.add(new SourceInfo(i + 1, origin, url, segment.text()));
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
