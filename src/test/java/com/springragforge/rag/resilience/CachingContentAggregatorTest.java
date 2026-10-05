package com.springragforge.rag.resilience;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CachingContentAggregatorTest {

    private final RequestScopeCache cache = new RequestScopeCache();
    private final StubAggregator delegate = new StubAggregator();

    @Test
    void sameQueryAndMemory_hitsCache_delegateCalledOnce() {
        CachingContentAggregator aggregator = new CachingContentAggregator(delegate, cache, props(true));

        List<Content> first = aggregator.aggregate(input("怎么写", "m1"));
        List<Content> second = aggregator.aggregate(input("怎么写", "m1"));

        assertEquals(1, delegate.calls.get());
        assertEquals(first, second);
    }

    @Test
    void differentQuery_callsDelegatePerQuery() {
        CachingContentAggregator aggregator = new CachingContentAggregator(delegate, cache, props(true));

        aggregator.aggregate(input("问一", "m1"));
        aggregator.aggregate(input("问二", "m1"));

        assertEquals(2, delegate.calls.get());
    }

    @Test
    void disabled_bypassesCache() {
        CachingContentAggregator aggregator = new CachingContentAggregator(delegate, cache, props(false));

        aggregator.aggregate(input("q", "m1"));
        aggregator.aggregate(input("q", "m1"));

        assertEquals(2, delegate.calls.get());
    }

    private static Map<Query, Collection<List<Content>>> input(String text, String memoryId) {
        Metadata metadata = Metadata.from(UserMessage.from(text), memoryId, List.of());
        Query query = Query.from(text, metadata);
        return Map.of(query, List.of(List.of(Content.from(TextSegment.from("snippet")))));
    }

    private static ResilienceProperties props(boolean enabled) {
        ResilienceProperties p = new ResilienceProperties();
        p.setEnabled(enabled);
        return p;
    }

    private static final class StubAggregator implements ContentAggregator {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
            int n = calls.incrementAndGet();
            return List.of(Content.from(TextSegment.from("agg-" + n)));
        }
    }
}
