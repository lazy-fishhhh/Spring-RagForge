package com.springragforge.rag.retriever;

import com.springragforge.rag.resilience.RequestScopeCache;
import com.springragforge.rag.resilience.ResilienceProperties;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.List;

/**
 * 给召回层加请求级缓存：生成层重试时同一次提问不再重打 ES / embedding。
 * 相当于混合检索之后，把检索片段缓存
 *
 * <p>拿不到 chatMemoryId 或韧性整体关闭时直通，不做缓存。
 */
public class CachingContentRetriever implements ContentRetriever {

    private final ContentRetriever delegate;
    private final RequestScopeCache cache;
    private final ResilienceProperties properties;

    public CachingContentRetriever(ContentRetriever delegate, RequestScopeCache cache,
                                   ResilienceProperties properties) {
        this.delegate = delegate;
        this.cache = cache;
        this.properties = properties;
    }

    @Override
    public List<Content> retrieve(Query query) {
        String memoryId = memoryIdOf(query); // 获取memoryId
        String queryText = query.text();
        if (!properties.isEnabled() || memoryId == null || queryText == null) {
            return delegate.retrieve(query);
        }
        // 如果有缓存就直接返回
        List<Content> cached = cache.getRaw(memoryId, queryText);
        if (cached != null) {
            return cached;
        }
        List<Content> fresh = delegate.retrieve(query);
        cache.putRaw(memoryId, queryText, fresh);
        return fresh;
    }

    private static String memoryIdOf(Query query) {
        Object id = query.metadata() == null ? null : query.metadata().chatMemoryId();
        return id == null ? null : id.toString();
    }
}
