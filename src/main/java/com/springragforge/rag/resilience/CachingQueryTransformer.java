package com.springragforge.rag.resilience;

import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 给消解/改写层加请求级缓存：生成层重试时不再重打消解 LLM。
 *
 * <p>为什么这层也要缓存：消解是检索前的一次 LLM 调用，处在重试范围内。重跑两次 LLM 输出
 * 未必逐字相同，而下游 {@link com.springragforge.rag.retriever.CachingContentRetriever}
 * 和 {@link CachingContentAggregator} 都按 {@code query.text()} 做缓存键——改写文本一变，
 * 它们的缓存全部 miss，ES、embedding、重排全白跑。钉住一次改写结果，重试才真的只重调模型。
 *
 * <p>键用原始 query 文本（{@code query.text()}），不是改写后的文本。
 *
 * <p>装饰器模式：命中直接返回缓存的改写结果，未命中才调 delegate 并写入。
 * 改写结果保留原 Query 的 metadata，下游据此仍能取到 chatMemoryId。
 */
@Slf4j
public class CachingQueryTransformer implements QueryTransformer {

    private final QueryTransformer delegate;
    private final RequestScopeCache cache;
    private final ResilienceProperties properties;

    public CachingQueryTransformer(QueryTransformer delegate, RequestScopeCache cache,
                                   ResilienceProperties properties) {
        this.delegate = delegate;
        this.cache = cache;
        this.properties = properties;
    }

    @Override
    public Collection<Query> transform(Query query) {
        String memoryId = memoryIdOf(query);
        String originalText = query.text();
        // 韧性关闭、或拿不到 memoryId / 原文时直通，不做缓存
        if (!properties.isEnabled() || memoryId == null || originalText == null) {
            return delegate.transform(query);
        }

        // 命中：用缓存的改写文本重建 Query，挂回当前 metadata（下游还要靠它取 chatMemoryId）
        String cached = cache.getTransformed(memoryId, originalText);
        if (cached != null) {
            log.debug("消解命中缓存: '{}' -> '{}'", originalText, cached);
            return List.of(Query.from(cached, query.metadata()));
        }

        Collection<Query> fresh = delegate.transform(query);
        // 只缓存"恰好一条改写结果"的情况：多查询（扩展类改写）用单个字符串缓存不下，
        // 空结果无从缓存，两种都直接透传，不写缓存。
        if (fresh != null && fresh.size() == 1) {
            Query transformed = fresh.iterator().next();
            if (transformed.text() != null) {
                log.info("指代消解: '{}' -> '{}'", originalText, transformed.text());
                cache.putTransformed(memoryId, originalText, transformed.text());
            }
        }
        return fresh;
    }

    private static String memoryIdOf(Query query) {
        Object id = query.metadata() == null ? null : query.metadata().chatMemoryId();
        return id == null ? null : Objects.toString(id, null);
    }
}
