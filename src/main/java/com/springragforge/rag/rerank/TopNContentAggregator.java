package com.springragforge.rag.rerank;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 不重排时的兜底聚合器：把各路候选按原有顺序拍平，只截前 N 条。
 * 用于 {@code rag.rerank.enabled=false} 的对照评测——行为等价于「RRF 融合后直接取前 N」。
 */
public class TopNContentAggregator implements ContentAggregator {

    private final int maxResults;

    public TopNContentAggregator(int maxResults) {
        this.maxResults = maxResults;
    }

    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
        List<Content> flattened = new ArrayList<>();
        for (Collection<List<Content>> groups : queryToContents.values()) {
            for (List<Content> group : groups) {
                flattened.addAll(group);
            }
        }
        return flattened.size() > maxResults
                ? new ArrayList<>(flattened.subList(0, maxResults))
                : flattened;
    }
}
