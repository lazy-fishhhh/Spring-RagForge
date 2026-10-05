package com.springragforge.rag.resilience;

import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求级检索缓存。生成层重试时会重订阅整条链，把召回池和聚合（含重排）结果缓存下来，
 * 后续尝试直接读取，只重调模型，不再打 ES、embedding 和重排接口。
 *
 * <p>键是「memoryId + query 文本」。必须含 query 文本：同一 memoryId 下会换问题
 * （评测循环就是一个会话跑多题），只用 memoryId 会串味。
 *
 * <p>生命周期与会话一致，由 {@link com.springragforge.service.ResilientChatService}
 * 在流结束时清掉（放在那里而不是控制器，是为了让绕过控制器的评测路径也能清理）。
 */
@Component
public class RequestScopeCache {

    /**
     * 两层 Map 结构：memoryId -> (query 文本 -> 该次提问的检索结果)。
     *
     * <p>外层按 memoryId（会话）分组，内层按 query 文本区分同一个会话里的不同提问。
     * 用 ConcurrentHashMap 是因为会被多个请求线程并发读写；作为单例 Bean 的字段，
     * 这份数据是全局共享的，靠 clear(memoryId) 在会话/流结束时回收，避免堆积。
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Entry>> store = new ConcurrentHashMap<>();

    /** 读取「原始召回池」缓存。未命中返回 null（注意：null 表示没缓存，不是空结果）。 */
    public List<Content> getRaw(String memoryId, String queryText) {
        Entry entry = lookup(memoryId, queryText);
        return entry == null ? null : entry.raw;
    }

    /** 写入「原始召回池」缓存，即还没经过聚合/重排的召回结果。 */
    public void putRaw(String memoryId, String queryText, List<Content> raw) {
        entry(memoryId, queryText).raw = raw;
    }

    /**
     * 读取「消解/改写后的问题」缓存。未命中返回 null。
     *
     * <p>键用<b>原始</b> query 文本，不是改写后的。重试时若重跑消解，LLM 两次输出可能不完全一致，
     * 下游缓存（raw/aggregated）按 {@code query.text()} 做键就会 miss，整条检索白跑。钉住原文做键，
     * 重试才能拿到同一份改写结果。
     */
    public String getTransformed(String memoryId, String queryText) {
        Entry entry = lookup(memoryId, queryText);
        return entry == null ? null : entry.transformed;
    }

    /** 写入「消解/改写后的问题」缓存，键为原始 query 文本。 */
    public void putTransformed(String memoryId, String queryText, String transformed) {
        entry(memoryId, queryText).transformed = transformed;
    }

    /** 读取「聚合（含重排）」缓存。未命中返回 null。 */
    public List<Content> getAggregated(String memoryId, String queryText) {
        Entry entry = lookup(memoryId, queryText);
        return entry == null ? null : entry.aggregated;
    }

    /** 写入「聚合（含重排）」缓存。 */
    public void putAggregated(String memoryId, String queryText, List<Content> aggregated) {
        entry(memoryId, queryText).aggregated = aggregated;
    }

    /**
     * 清理整个会话的缓存。由 ResilientChatService 在流结束时调用，生命周期与会话一致。
     * 只按 memoryId 整体删除（而不是删单个 query），因为一个会话结束后，它下面的所有提问缓存都没用了。
     */
    public void clear(String memoryId) {
        if (memoryId != null) {
            store.remove(memoryId);
        }
    }

    /** 查表：先按 memoryId 找到该会话的子表，再按 queryText 找条目；任一层没有都返回 null。 */
    private Entry lookup(String memoryId, String queryText) {
        ConcurrentHashMap<String, Entry> byQuery = store.get(memoryId);
        return byQuery == null ? null : byQuery.get(queryText);
    }

    /**
     * 取条目，没有就逐层创建（两层都用 computeIfAbsent，保证并发下只创建一次）。
     * 读路径（lookup）不创建，写路径（entry）才创建，避免「只读一个不存在的键」也留下空壳。
     * computeIfAbsent：没有就按指定 的创建放进去
     */
    private Entry entry(String memoryId, String queryText) {
        return store.computeIfAbsent(memoryId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(queryText, k -> new Entry());
    }

    /**
     * 单次提问的缓存条目，同时持有两级检索结果。
     *
     * <p>字段用 volatile：可能一个线程写、另一个线程读，保证写完后读线程能看到最新值。
     * 用可变字段而不是不可变对象，是为了能分两次写入（先 raw 后 aggregated）。
     */
    private static final class Entry {
        volatile String transformed;       // 消解/改写后的问题文本
        volatile List<Content> raw;        // 原始召回池
        volatile List<Content> aggregated; // 聚合（含重排）后的结果
    }
}