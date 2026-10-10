package com.springragforge.rag.memory;

import com.springragforge.constant.MemoryPrompts;
import com.springragforge.repository.RedisChatMemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 带滚动摘要的会话记忆，替代纯滑窗。
 *
 * <p>结构：{@code messages()} 返回「摘要 SystemMessage + 最近若干条原文」。超预算时从头部逐出最老的
 * 消息，攒够一批做一次增量摘要合进摘要段。摘要计入预算，否则摘要本身会无限增长。
 *
 * <p>为什么自己实现而不包 {@link dev.langchain4j.store.memory.chat.ChatMemoryStore}：AiServices 拼 prompt
 * 走的是 {@code ChatMemory.messages()}，不走 store；而 {@code MessageWindowChatMemory.add} 是先裁窗口再存，
 * store 永远只看到裁过的历史。摘要要进 prompt，只能在 {@code messages()} 这一层做。
 *
 * <p>摘要是同步的：AiServices 在流结束后才调 {@code add(AiMessage)}，摘要会卡在 [DONE] 之前多一次模型往返。
 * 换来的是顺序确定、无并发写序问题。
 */
@Slf4j
public class SummarizingChatMemory implements ChatMemory {

    /** 会话标识，用来区分不同用户/会话，也是 Redis 存储的 key 一部分。 */
    private final Object id;
    /** 持久化存储，负责把消息和摘要落到 Redis。 */
    private final RedisChatMemoryStore store;
    /** 用来生成摘要的模型；与对话模型可以是同一个，也可以单独指定。 */
    private final ChatModel summarizer;
    /** token 估算器，用来判断是否超出预算、需要触发摘要。 */
    private final TokenCountEstimator counter;
    /** 记忆相关配置：预算、逐出批量、保留最近条数等。 */
    private final MemoryProperties props;

    /** 最近、尚未被摘要的消息；持久化到 Redis，取消摘要逻辑后即是原来的滑窗。 */
    private final List<ChatMessage> history;
    /** 滚动摘要，null 表示还没有摘要。 */
    private String summary;

    /**
     * 构造时从 Redis 恢复历史消息和已有摘要，实现"重启后记忆不丢"。
     *
     * @param id         会话标识
     * @param store      Redis 存储
     * @param summarizer 生成摘要用的模型
     * @param counter    token 估算器
     * @param props      记忆配置
     */
    public SummarizingChatMemory(Object id,
                                 RedisChatMemoryStore store,
                                 ChatModel summarizer,
                                 TokenCountEstimator counter,
                                 MemoryProperties props) {
        this.id = id;
        this.store = store;
        this.summarizer = summarizer;
        this.counter = counter;
        this.props = props;
        // 从 Redis 拉取该会话已有的历史与摘要，保证多实例/重启后上下文一致
        this.history = new ArrayList<>(store.getMessages(id));
        this.summary = store.getSummary(id);
    }

    @Override
    public Object id() {
        return id;
    }

    /**
     * 返回真正要拼进 prompt 的消息列表。
     *
     * <p>顺序为：摘要（若存在，包成一条 SystemMessage）+ 最近的原文消息。
     * 摘要放在最前面作为系统提示，模型能先读到"之前发生过什么"。
     */
    @Override
    public List<ChatMessage> messages() {
        List<ChatMessage> out = new ArrayList<>(history.size() + 1);
        // 有摘要时，把它作为一条 SystemMessage 放在最前
        if (summary != null && !summary.isBlank()) {
            out.add(SystemMessage.from(MemoryPrompts.SUMMARY_PREFIX + summary));
        }
        // 再追加尚未被摘要的最近原文
        out.addAll(history);
        return out;
    }

    /**
     * 追加一条消息。这是 AiServices 每轮对话后调用的入口。
     *
     * <p>一轮里框架会调三次：systemMessage、userMessage、aiMessage。逐出/摘要只在
     * {@link AiMessage}（整轮收尾）那一次做：拼 prompt 发生在 add(userMessage) 之前，
     * 当轮逐出没有意义；挂三次会导致每轮多次摘要调用。
     */
    @Override
    public void add(ChatMessage message) {
        // 框架每轮都会 add 一条 SystemMessage（系统提示词），不去重会无限堆积。
        // 策略：新 SystemMessage 进来时先移除旧的，保证 history 里始终只有一条。
        if (message instanceof SystemMessage) {
            history.removeIf(m -> m instanceof SystemMessage);
        }
        history.add(message);
        if (message instanceof AiMessage) {
            evictAndSummarize();
        }
        persist();
    }

    /**
     * 整体替换历史消息（例如外部批量导入或重置某段上下文）。
     */
    @Override
    public void set(Iterable<ChatMessage> messages) {
        history.clear();
        messages.forEach(history::add);
        evictAndSummarize();
        persist();
    }

    /**
     * 清空本会话的所有记忆：内存中的 history、summary，以及 Redis 中的对应数据。
     */
    @Override
    public void clear() {
        history.clear();
        summary = null;
        store.deleteMessages(id);
        store.deleteSummary(id);
    }

    /**
     * 超预算就逐出最老的消息，攒够一批做一次增量摘要。
     *
     * <p>循环里只做移除，攒齐整轮要逐出的全部消息后再一次摘要。若逐出一批调一次模型，
     * 贴着保留下限时 {@code batchSize} 恒为 1，就退化成"一条一条摘"——每轮好几次模型调用。
     *
     * <p>循环条件保证：只有"超预算"且"剩余消息多于保留条数"时才逐出，
     * 避免把最近上下文也裁掉。逐到回到预算内或只剩保留条数为止。
     */
    private void evictAndSummarize() {
        if (!overBudget()) {
            return;
        }
        int keep = props.getKeepRecentMessages();
        List<ChatMessage> evicted = new ArrayList<>();
        while (overBudget() && history.size() > keep) {
            // history.size() - keep 恰好是“可逐出而不破坏保留条数”的上限。
            int batchSize = Math.min(props.getEvictBatch(), history.size() - keep);
            // 取出最老的一批消息，追加到 evicted 中，等待最后统一摘要。
            evicted.addAll(history.subList(0, batchSize));
            // 从 history 中移除这批最老的消息（等价于滑窗向右移动）。
            history.subList(0, batchSize).clear();
        }
        // 把所有被逐出的消息一次性合并进摘要，减少 summarize 调用次数，
        if (!evicted.isEmpty()) {
            summary = summarize(summary, evicted);
        }
    }

    /**
     * 判断当前记忆是否超出 token 预算。
     *
     * <p>关键点：摘要本身也计入预算——否则摘要会无限增长，最终反而撑爆窗口。
     */
    private boolean overBudget() {
        int tokens = counter.estimateTokenCountInMessages(history);
        if (summary != null && !summary.isBlank()) {
            tokens += counter.estimateTokenCountInText(summary);
        }
        return tokens > props.getBudgetTokens();
    }

    /**
     * 增量摘要：{@code summary_new = LLM(summary_prev + 本批逐出)}。绝不重摘全量历史，否则每轮一次 O(n) 调用。
     *
     * <p>失败时返回旧摘要、不抛出：最老的消息已经逐出，等于退化成纯截断，主流程不受影响。
     *
     * @param previous 上一次的摘要，可能为 null（首次摘要）
     * @param batch    本次被逐出的消息批次
     * @return 合并后的新摘要；失败或模型返回空则返回旧摘要
     */
    private String summarize(String previous, List<ChatMessage> batch) {
        try {
            // 把"旧摘要"和"本批消息"填进模板，交给模型做增量合并
            String prompt = MemoryPrompts.SUMMARY_PROMPT
                    .replace("{summary}", previous == null || previous.isBlank() ? "(无，这是首次摘要)" : previous)
                    .replace("{batch}", renderBatch(batch));
            String result = summarizer.chat(prompt);
            // 模型没返回有效内容，保持旧摘要（相当于本批只做了截断）
            if (result == null || result.isBlank()) {
                return previous;
            }
            return result.trim();
        } catch (Exception e) {
            // 摘要失败不能影响主流程：最老的消息已逐出，退化为纯截断即可
            log.warn("滚动摘要失败，退化为截断: memoryId={} cause={}", id, e.toString());
            return previous;
        }
    }

    /**
     * 把一批消息渲染成"角色：正文"的多行文本，供摘要 prompt 使用。
     */
    private static String renderBatch(List<ChatMessage> batch) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : batch) {
            sb.append(roleOf(message)).append("：")
                    // 复用估算器里的文本抽取逻辑，保证摘要与计数口径一致
                    .append(CharApproxTokenCountEstimator.textOf(message))
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * 把消息类型翻译成中文角色名，便于模型理解和生成摘要。
     */
    private static String roleOf(ChatMessage message) {
        if (message instanceof UserMessage) {
            return "用户";
        }
        if (message instanceof AiMessage) {
            return "助手";
        }
        if (message instanceof SystemMessage) {
            return "系统";
        }
        if (message instanceof ToolExecutionResultMessage) {
            return "工具";
        }
        return "其他";
    }

    /** 消息和摘要一起落 Redis，同一 TTL，避免一个先过期导致对不上。 */
    private void persist() {
        store.updateMessages(id, history);
        store.updateSummary(id, summary);
    }
}