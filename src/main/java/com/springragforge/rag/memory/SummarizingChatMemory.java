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
 * <p>结构：{@code messages()} 返回「摘要 SystemMessage + 最近若干条原文」。占用涨到预算高水位时
 * 从头部一次性逐出到低水位，攒够一批做一次增量摘要合进摘要段。摘要计入预算，否则摘要本身会无限增长。
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
    /** 连续摘要失败次数；成功或降级逐出后归零。 */
    private int summaryFailures;

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
        // 从 Redis 拉取该会话已有的历史、摘要与失败计数，保证多实例/重启后状态一致。
        // 失败计数必须持久化：provider 每个请求都 new 一个实例，只放内存永远到不了阈值。
        this.history = new ArrayList<>(store.getMessages(id));
        this.summary = store.getSummary(id);
        this.summaryFailures = store.getSummaryFailures(id);
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
     *
     * <p>提示视图按预算裁剪，但不修改 history。摘要失败期间历史会暂时超预算，
     * 这里只截断要发给模型的窗口；原文保留到连续失败达到阈值后降级丢弃，或硬上限兜底。
     */
    @Override
    public List<ChatMessage> messages() {
        List<ChatMessage> window = promptWindow();
        List<ChatMessage> out = new ArrayList<>(window.size() + 1);
        // 有摘要时，把它作为一条 SystemMessage 放在最前
        if (summary != null && !summary.isBlank()) {
            out.add(SystemMessage.from(MemoryPrompts.SUMMARY_PREFIX + summary));
        }
        // 再追加尚未被摘要的最近原文
        out.addAll(window);
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
        summaryFailures = 0;
        store.deleteMessages(id);
        store.deleteSummary(id);
        store.deleteSummaryFailures(id);
    }

    /**
     * 达到高水位就逐出最老的消息，攒够一批做一次增量摘要。
     *
     * <p>触发用高水位（{@code summarizeAtRatio}）、逐出到低水位（{@code evictToRatio}），
     * 而不是「一超预算就摘」：后者窗口刚回到预算内，下一轮新增两条消息又超，
     * 等于每轮多一次同步模型往返。高低水位让一次摘要能管好几轮。
     *
     * <p>顺序是「先摘要、成功才逐出」：单次失败时 history 原样保留，下一轮重试，
     * 避免一次模型抖动丢掉内容。但连续失败达到 {@code summaryFailureThreshold} 后
     * 降级为直接逐出：老消息本来就被 {@link #promptWindow()} 截掉不进 prompt，
     * 继续保留只会撑大 Redis。最后再用 {@code hardCapTokens} 兜底，
     * 防止 {@code keepRecentMessages} 本身超预算时永远无法逐出。
     *
     * <p>逐出计划一次性算完再调一次模型，避免贴着保留下限时退化成"一条一条摘"。
     * 计划只逐到低水位或只剩 {@code keepRecentMessages} 条为止。
     */
    private void evictAndSummarize() {
        if (shouldSummarize()) {
            List<ChatMessage> evicted = planEviction();
            if (evicted.isEmpty()) {
                log.warn("记忆超过摘要水位但无可逐出消息: memoryId={} history={} keep={} trigger={}",
                        id, history.size(), props.getKeepRecentMessages(), triggerTokens());
            } else {
                summarizeOrDegrade(evicted);
            }
        }
        enforceHardCap();
    }

    /**
     * 尝试摘要并逐出；失败累计到阈值时放弃摘要、直接丢弃，保证 history 有界。
     */
    private void summarizeOrDegrade(List<ChatMessage> evicted) {
        String merged = summarize(summary, evicted);
        if (merged != null) {
            summary = merged;
            history.subList(0, evicted.size()).clear();
            summaryFailures = 0;
            return;
        }
        summaryFailures++;
        if (summaryFailures < props.getSummaryFailureThreshold()) {
            // 未到阈值：保留原文，messages() 会按预算裁剪本轮的提示视图，下一轮再试
            log.warn("滚动摘要失败，保留 {} 条原文等待重试 ({}/{}): memoryId={}",
                    evicted.size(), summaryFailures, props.getSummaryFailureThreshold(), id);
            return;
        }
        // 持续失败：降级丢弃，否则 history 无限增长。被丢的消息本就不在 prompt 窗口内
        history.subList(0, evicted.size()).clear();
        summaryFailures = 0;
        log.warn("滚动摘要连续失败 {} 次，降级丢弃 {} 条原文: memoryId={}",
                props.getSummaryFailureThreshold(), evicted.size(), id);
    }

    /**
     * 硬上限兜底：摘要持续失败且 {@code keepRecentMessages} 本身超预算时，
     * {@link #planEviction()} 永远为空。这里无视保留下限丢弃最老消息，但至少保留最新一条。
     */
    private void enforceHardCap() {
        int cap = props.getHardCapTokens();
        if (cap <= 0) {
            return;
        }
        int removedTokens = 0;
        int dropped = 0;
        int total = estimateTokens();
        while (total - removedTokens > cap && history.size() - dropped > 1) {
            removedTokens += counter.estimateTokenCountInMessage(history.get(dropped));
            dropped++;
        }
        if (dropped > 0) {
            history.subList(0, dropped).clear();
            log.warn("记忆超过硬上限 {} token，丢弃 {} 条最老原文: memoryId={}",
                    cap, dropped, id);
        }
    }

    /**
     * 规划本轮要逐出的最老消息，不修改 history。按 {@code evict-batch} 分批推进，
     * 直到剩余估算降到低水位，或可逐出条数用尽（只剩保留下限）。
     */
    private List<ChatMessage> planEviction() {
        int total = estimateTokens();
        int target = evictTargetTokens();   // 低水位：一次逐到这里，给后面几轮留余量
        int removable = Math.max(0, history.size() - props.getKeepRecentMessages());
        List<ChatMessage> planned = new ArrayList<>();
        int removedTokens = 0;
        int next = 0;
        while (total - removedTokens > target && next < removable) {
            int batchSize = Math.min(props.getEvictBatch(), removable - next);
            for (int i = 0; i < batchSize; i++) {
                ChatMessage message = history.get(next++);
                planned.add(message);
                removedTokens += counter.estimateTokenCountInMessage(message);
            }
        }
        return planned;
    }

    /**
     * 拼 prompt 用的窗口：从最新消息往回装，装到预算为止。
     *
     * <p>只影响本次提示，不删除 history。正常情况下 history 已在预算内，返回全部；
     * 摘要失败时历史暂时超预算，这里做非破坏性截断，原文留待下一轮重试摘要。
     */
    private List<ChatMessage> promptWindow() {
        // 这里用预算硬上限而不是触发水位：高水位只是「该摘了」的信号，
        // 窗口本身不能超过预算，否则真发出去的 prompt 会超出记忆预算。
        int available = props.getBudgetTokens() - summaryTokens();
        if (available <= 0) {
            return List.of();
        }
        int start = history.size();
        int used = 0;
        while (start > 0) {
            int cost = counter.estimateTokenCountInMessage(history.get(start - 1));
            if (used + cost > available) {
                break;
            }
            used += cost;
            start--;
        }
        // 连最新一条都放不下时也保留它，否则模型看不到当前问题
        if (start == history.size() && !history.isEmpty()) {
            start = history.size() - 1;
        }
        return history.subList(start, history.size());
    }

    /**
     * 是否达到触发摘要的高水位。摘要本身也计入，否则摘要会无限增长撑爆窗口。
     *
     * <p>用高水位而不是预算线：贴着预算触发的话，逐出后下一轮新增两条消息立刻又超，
     * 摘要调用就退化成每轮一次。
     */
    private boolean shouldSummarize() {
        return estimateTokens() > triggerTokens();
    }

    /** 触发摘要的 token 水位：{@code budgetTokens × summarizeAtRatio}。 */
    private int triggerTokens() {
        return tokensAt(props.getSummarizeAtRatio());
    }

    /** 逐出目标 token 水位：{@code budgetTokens × evictToRatio}。 */
    private int evictTargetTokens() {
        return tokensAt(props.getEvictToRatio());
    }

    /** 把预算比例换算成绝对 token 数，向下取整，避免边界上多算一个。 */
    private int tokensAt(double ratio) {
        return (int) Math.floor(props.getBudgetTokens() * ratio);
    }

    /** 当前记忆的总估算占用：滚动摘要 + 尚未被摘要的原文。 */
    private int estimateTokens() {
        return summaryTokens() + counter.estimateTokenCountInMessages(history);
    }

    /** 摘要占用的估算 token，摘要为空时为 0。 */
    private int summaryTokens() {
        return summary == null || summary.isBlank()
                ? 0
                : counter.estimateTokenCountInText(summary);
    }

    /**
     * 增量摘要：{@code summary_new = LLM(summary_prev + 本批逐出)}。绝不重摘全量历史，否则每轮一次 O(n) 调用。
     *
     * <p>失败或模型返回空时返回 {@code null}，由调用方决定不逐出；不能在这里返回旧摘要，
     * 否则调用方无法区分"摘要成功但内容没变"和"调用失败"。
     *
     * @param previous 上一次的摘要，可能为 null（首次摘要）
     * @param batch    本次计划逐出的消息批次
     * @return 合并并裁剪过的新摘要；失败或返回空时为 {@code null}
     */
    private String summarize(String previous, List<ChatMessage> batch) {
        try {
            // 把"旧摘要"和"本批消息"填进模板，交给模型做增量合并
            String prompt = MemoryPrompts.SUMMARY_PROMPT
                    .replace("{summary}", previous == null || previous.isBlank() ? "(无，这是首次摘要)" : previous)
                    .replace("{batch}", renderBatch(batch));
            String result = summarizer.chat(prompt);
            if (result == null || result.isBlank()) {
                return null;
            }
            return capSummary(result.trim());
        } catch (Exception e) {
            log.warn("滚动摘要调用失败: memoryId={} cause={}", id, e.toString());
            return null;
        }
    }

    /**
     * 摘要输出的硬上限：提示词管不住模型时，这里按与预算一致的估算口径截断。
     * 优先在句末切断；句末离上限太远时退化为硬截。
     */
    private String capSummary(String text) {
        int max = props.getSummaryMaxTokens();
        if (counter.estimateTokenCountInText(text) <= max) {
            return text;
        }
        int cjk = 0;
        int other = 0;
        int limitEnd = -1;
        int boundaryEnd = -1;
        int boundaryTokens = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int end = i + Character.charCount(codePoint);
            if (CharApproxTokenCountEstimator.isCjk(codePoint)) {
                cjk++;
            } else {
                other++;
            }
            int tokens = cjk + (other + 3) / 4;
            if (tokens > max) {
                break;
            }
            limitEnd = end; // 最多能装多少
            if (isSentenceEnd(codePoint)) {
                boundaryEnd = end;  // 结尾是标点时最多装多少
                boundaryTokens = tokens;
            }
            i = end;
        }
        int cut = boundaryEnd > 0 && boundaryTokens >= max / 2 ? boundaryEnd : limitEnd;
        if (cut <= 0) {
            return text;
        }
        String capped = text.substring(0, cut).trim();
        log.warn("摘要超出 {} token，已截断: memoryId={} before={} after={}",
                max, id, counter.estimateTokenCountInText(text), counter.estimateTokenCountInText(capped));
        return capped;
    }

    /** 句末标点，用于尽量在语义边界截断摘要。 */
    private static boolean isSentenceEnd(int codePoint) {
        return codePoint == '。' || codePoint == '！' || codePoint == '？' || codePoint == '；'
                || codePoint == '!' || codePoint == '?' || codePoint == ';' || codePoint == '\n';
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

    /** 消息、摘要和失败计数一起落 Redis，同一 TTL，避免一个先过期导致对不上。 */
    private void persist() {
        store.updateMessages(id, history);
        store.updateSummary(id, summary);
        store.updateSummaryFailures(id, summaryFailures);
    }
}