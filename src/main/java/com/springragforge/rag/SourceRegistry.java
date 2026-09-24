package com.springragforge.rag;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按会话 ID 缓存本次召回的参考来源，供流式回答结束后回传给前端。
 *
 * <p>同一 memoryId 的请求通常串行到达（用户发完一条等回复再发下一条），
 * 所以用 memoryId 做 key 不会出现并发覆盖。
 */
@Component
public class SourceRegistry {

    private final ConcurrentHashMap<String, List<SourceInfo>> store = new ConcurrentHashMap<>();

    public void put(String memoryId, List<SourceInfo> sources) {
        store.put(memoryId, sources);
    }

    public List<SourceInfo> get(String memoryId) {
        return store.getOrDefault(memoryId, List.of());
    }

    public void remove(String memoryId) {
        store.remove(memoryId);
    }
}
