package com.springragforge.rag.memory;

import com.springragforge.repository.RedisChatMemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 高低水位行为：只有涨到高水位才摘要，且一次逐到低水位，而不是「刚回到预算线」。
 */
class SummarizingChatMemoryTest {

    /** 100 个汉字 + 每条消息 4 token 结构开销 = 104 token，方便手算水位。 */
    private static final String FILLER = "中".repeat(100);

    @Test
    void summarizesOnlyAtHighWatermark_andEvictsDownToLowWatermark() throws Exception {
        // 预算 1000：高水位 900，低水位 500
        MemoryProperties props = new MemoryProperties();
        props.setBudgetTokens(1000);
        props.setSummarizeAtRatio(0.9);
        props.setEvictToRatio(0.5);
        props.setKeepRecentMessages(0);
        props.setEvictBatch(2);
        props.setSummaryMaxTokens(100);
        props.setSummaryFailureThreshold(2);
        props.setHardCapTokens(0);

        RedisChatMemoryStore store = mock(RedisChatMemoryStore.class);
        when(store.getMessages(any())).thenReturn(List.of());
        when(store.getSummary(any())).thenReturn(null);
        when(store.getSummaryFailures(any())).thenReturn(0);

        ChatModel summarizer = mock(ChatModel.class);
        when(summarizer.chat(anyString())).thenReturn("摘要");

        SummarizingChatMemory memory = new SummarizingChatMemory(
                "m1", store, summarizer, new CharApproxTokenCountEstimator(), props);

        // 10 条 = 1040 token，刚越过 900。前 8 条只有 832，一次都不该触发。
        for (int i = 0; i < 5; i++) {
            memory.add(UserMessage.from(FILLER));
            memory.add(AiMessage.from(FILLER));
        }

        verify(summarizer, times(1)).chat(anyString());

        // 逐到低水位 500 以内：1040 -> 416，剩 4 条；
        // 若只逐回预算线 1000，只会剩 10 条，这个断言就会失败。
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(store, atLeastOnce()).updateMessages(eq("m1"), captor.capture());
        List<ChatMessage> persisted = captor.getValue();
        assertEquals(4, persisted.size());
        int remaining = new CharApproxTokenCountEstimator().estimateTokenCountInMessages(persisted);
        assertTrue(remaining <= props.getBudgetTokens() * props.getEvictToRatio(),
                "逐出后应落到低水位以内，实际 " + remaining);
    }
}
