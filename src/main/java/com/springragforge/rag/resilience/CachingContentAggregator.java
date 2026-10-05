package com.springragforge.rag.resilience;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 给聚合（含重排）层加请求级缓存：生成层重试时跳过重排，只重调模型。
 *
 * <p>聚合结果本就按 Query 一一产出，这里取第一个带 chatMemoryId 的 Query 作为键，
 * 与 {@link com.springragforge.rag.retriever.CachingContentRetriever} 保持一致。
 *
 * <p>实现方式是装饰器模式：持有真正的聚合器 {@code delegate}，只在它外面套一层缓存。
 * 缓存命中就直接返回，不再走 delegate（即不再重排）；未命中才调用 delegate 并写入缓存。
 */
public class CachingContentAggregator implements ContentAggregator {

    /** 被装饰的真实聚合器（含重排），缓存未命中时由它干活。 */
    private final ContentAggregator delegate;
    private final RequestScopeCache cache;
    private final ResilienceProperties properties;

    public CachingContentAggregator(ContentAggregator delegate, RequestScopeCache cache,
                                    ResilienceProperties properties) {
        this.delegate = delegate;
        this.cache = cache;
        this.properties = properties;
    }

    // 值类型是 Collection<List<Content>>：一个 query 可能对应多种检索方式，
    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
        // 从所有 query 里挑一个带 chatMemoryId 的作为缓存键（聚合结果按 Query 产出，取一个代表即可）。
        Query keyQuery = firstKeyQuery(queryToContents);
        String memoryId = keyQuery == null ? null : memoryIdOf(keyQuery);
        String queryText = keyQuery == null ? null : keyQuery.text();

        // 韧性关闭、或拿不到 memoryId / query 文本时直通，不做缓存。
        if (!properties.isEnabled() || memoryId == null || queryText == null) {
            return delegate.aggregate(queryToContents);
        }

        // 命中缓存：直接返回上次聚合（含重排）的结果，跳过重排。
        List<Content> cached = cache.getAggregated(memoryId, queryText);
        if (cached != null) {
            return cached;
        }

        // 未命中：真正执行聚合/重排，并把结果写入缓存供本次重试复用。
        List<Content> fresh = delegate.aggregate(queryToContents);
        cache.putAggregated(memoryId, queryText, fresh);
        return fresh;
    }

    /**
     * 取第一个带 chatMemoryId 的 Query 作为键。
     *
     * <p>不能直接拿 keySet() 的第一个：query 可能是改写/扩展出来的子查询，未必都带 memoryId，
     * 所以要过滤出真正带 memoryId 的那个。找不到就返回 null，调用方据此走直通逻辑。
     */
    private static Query firstKeyQuery(Map<Query, Collection<List<Content>>> queryToContents) {
        return queryToContents.keySet().stream()
                .filter(q -> memoryIdOf(q) != null)
                .findFirst()
                .orElse(null);
    }

    /**
     * 从 Query 的元数据里取 chatMemoryId 并转成字符串。
     *
     * <p>用 Objects.toString(id, null) 而不是 id.toString()：id 理论上可能为 null，
     * 这样写能安全地返回 null 而不抛 NPE。
     */
    private static String memoryIdOf(Query query) {
        Object id = query.metadata() == null ? null : query.metadata().chatMemoryId();
        return id == null ? null : Objects.toString(id, null);
    }
}