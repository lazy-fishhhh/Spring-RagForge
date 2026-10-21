package com.springragforge.repository;

import com.springragforge.rag.memory.MemoryProperties;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 会话记忆的 Redis 存储：消息列表 + 滚动摘要两个 key，同一 TTL。
 */
@Repository
public class RedisChatMemoryStore implements ChatMemoryStore {

    /** 摘要 key 后缀，和消息 key 分开存，避免摘要被 MessageWindow 式裁剪波及。 */
    private static final String SUMMARY_SUFFIX = ":summary";
    /** 连续摘要失败次数 key 后缀：内存实例每请求重建，计数必须跨请求持久化。 */
    private static final String FAILURE_SUFFIX = ":summary-failures";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private MemoryProperties memoryProperties;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = stringRedisTemplate.opsForValue().get(key(memoryId));
        if (json == null) {
            return List.of();
        }
        return ChatMessageDeserializer.messagesFromJson(json);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> list) {
        String json = ChatMessageSerializer.messagesToJson(list);
        stringRedisTemplate.opsForValue().set(key(memoryId), json, memoryProperties.getTtl());
    }

    @Override
    public void deleteMessages(Object memoryId) {
        stringRedisTemplate.delete(key(memoryId));
    }

    public String getSummary(Object memoryId) {
        return stringRedisTemplate.opsForValue().get(summaryKey(memoryId));
    }

    /** 摘要为空不写：避免残留上一个已清空的摘要。 */
    public void updateSummary(Object memoryId, String summary) {
        if (summary == null || summary.isBlank()) {
            return;
        }
        stringRedisTemplate.opsForValue().set(summaryKey(memoryId), summary, memoryProperties.getTtl());
    }

    public void deleteSummary(Object memoryId) {
        stringRedisTemplate.delete(summaryKey(memoryId));
    }

    public int getSummaryFailures(Object memoryId) {
        String value = stringRedisTemplate.opsForValue().get(failureKey(memoryId));
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 归零即删 key，避免残留旧计数影响下一轮。 */
    public void updateSummaryFailures(Object memoryId, int failures) {
        if (failures <= 0) {
            stringRedisTemplate.delete(failureKey(memoryId));
            return;
        }
        stringRedisTemplate.opsForValue().set(failureKey(memoryId), Integer.toString(failures),
                memoryProperties.getTtl());
    }

    public void deleteSummaryFailures(Object memoryId) {
        stringRedisTemplate.delete(failureKey(memoryId));
    }

    private static String key(Object memoryId) {
        return memoryId.toString();
    }

    private static String summaryKey(Object memoryId) {
        return memoryId + SUMMARY_SUFFIX;
    }

    private static String failureKey(Object memoryId) {
        return memoryId + FAILURE_SUFFIX;
    }
}
