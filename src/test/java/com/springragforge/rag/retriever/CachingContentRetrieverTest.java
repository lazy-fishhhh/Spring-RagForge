package com.springragforge.rag.retriever;

import com.springragforge.rag.resilience.RequestScopeCache;
import com.springragforge.rag.resilience.ResilienceProperties;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CachingContentRetrieverTest {

    private final RequestScopeCache cache = new RequestScopeCache();
    private final StubRetriever delegate = new StubRetriever();

    @Test
    void sameQueryAndMemory_hitsCache_delegateCalledOnce() {
        CachingContentRetriever retriever = new CachingContentRetriever(delegate, cache, props(true));
        Query query = query("怎么写", "m1");

        List<Content> first = retriever.retrieve(query);
        List<Content> second = retriever.retrieve(query);

        assertEquals(1, delegate.calls.get());
        assertEquals(first, second);
    }

    @Test
    void differentQuery_callsDelegatePerQuery() {
        CachingContentRetriever retriever = new CachingContentRetriever(delegate, cache, props(true));

        retriever.retrieve(query("问一", "m1"));
        retriever.retrieve(query("问二", "m1"));

        assertEquals(2, delegate.calls.get());
    }

    @Test
    void nullMemoryId_bypassesCache() {
        CachingContentRetriever retriever = new CachingContentRetriever(delegate, cache, props(true));
        Query noMetadata = Query.from("q");

        retriever.retrieve(noMetadata);
        retriever.retrieve(noMetadata);

        assertEquals(2, delegate.calls.get());
    }

    @Test
    void disabled_bypassesCache() {
        CachingContentRetriever retriever = new CachingContentRetriever(delegate, cache, props(false));
        Query query = query("q", "m1");

        retriever.retrieve(query);
        retriever.retrieve(query);

        assertEquals(2, delegate.calls.get());
    }

    private static Query query(String text, String memoryId) {
        Metadata metadata = Metadata.from(UserMessage.from(text), memoryId, List.of());
        return Query.from(text, metadata);
    }

    private static ResilienceProperties props(boolean enabled) {
        ResilienceProperties p = new ResilienceProperties();
        p.setEnabled(enabled);
        return p;
    }

    private static final class StubRetriever implements ContentRetriever {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public List<Content> retrieve(Query query) {
            int n = calls.incrementAndGet();
            return List.of(Content.from(TextSegment.from("hit-" + n)));
        }
    }
}
