package com.springragforge.rag;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 按会话 ID 记录本次回答是否走了降级路径，供控制器在流结束前推一条命名事件 {@code degraded} 给前端。
 *
 * <p>与 {@link SourceRegistry} 同一套并发假设：同一 memoryId 的请求串行到达，用 memoryId 做 key 不会并发覆盖。
 */
@Component
public class DegradationRegistry {

    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

    public void put(String memoryId, String reason) {
        store.put(memoryId, reason);
    }

    /** 未降级时返回 null。 */
    public String get(String memoryId) {
        return store.get(memoryId);
    }

    public void remove(String memoryId) {
        store.remove(memoryId);
    }
}
