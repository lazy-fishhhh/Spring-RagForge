package com.springragforge.rag.injector;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.injector.ContentInjector;

import java.util.List;

/**
 * 把召回的文档片段拼成带编号和出处的形态再交给模型。
 *
 * <p>默认的 {@code DefaultContentInjector} 只按顺序塞纯文本，没有编号也没有出处，
 * 而系统提示词要求「用 [1][2] 标注来源」「链接逐字复制」。不自己拼的话，编号只能靠模型瞎编，
 * 出处更是无从谈起。
 *
 * <p>拼出来的形态：
 * <pre>
 * 用户原问题
 *
 * ===== 文档片段 [1] =====
 * 出处：Declaring Advice &gt; Before Advice
 * 链接：https://docs.spring.io/.../advice.html#aop-advice-before
 * 内容：
 * &lt;片段原文&gt;
 * </pre>
 */
public class NumberedContentInjector implements ContentInjector {

    /**
     * 把召回的片段注入到用户消息中。
     *
     * <p>整体流程：以「用户原问题」为底稿 → 依次追加每个片段（带编号/出处/链接/正文）
     * → 再包回 UserMessage 返回。注意这里返回的是 {@link ChatMessage}，
     * 但实际只处理 {@link UserMessage}，其它类型一律原样放行。
     */
    @Override
    public ChatMessage inject(List<Content> contents, ChatMessage chatMessage) {
        // 没有召回内容，或当前消息不是用户消息时，不做任何改动直接返回：
        // - contents 为空：没什么可注入的；
        // - chatMessage 不是 UserMessage：RAG 注入只针对用户提问，其它角色（如系统/工具消息）保持原样。
        if (contents == null || contents.isEmpty() || !(chatMessage instanceof UserMessage userMessage)) {
            return chatMessage;
        }

        // 以用户原始问题作为提示词的开头，后续片段都追加在后面。
        // 用 StringBuilder 是因为片段可能很多，避免频繁字符串拼接产生大量临时对象。
        StringBuilder prompt = new StringBuilder(userMessage.singleText());

        // 逐个片段追加，编号从 1 开始（对应系统提示词里 [1][2] 的写法）。
        for (int i = 0; i < contents.size(); i++) {
            appendFragment(prompt, i + 1, contents.get(i).textSegment());
        }

        // 拼好的纯文本再转回 UserMessage。
        // Prompt.from(...).toUserMessage() 会按模型约定的格式做一次包装，
        // 比直接 new UserMessage(text) 更稳妥。
        return Prompt.from(prompt.toString()).toUserMessage();
    }

    /**
     * 把单个片段按固定模板追加到提示词里：
     * <pre>
     * ===== 文档片段 [n] =====
     * 出处：...
     * 链接：...
     * 内容：
     * &lt;片段原文&gt;
     * </pre>
     *
     * <p>「出处」「链接」是可选的：元数据里没有就整行不写，
     * 避免出现「出处：」这种空字段误导模型。
     */
    private static void appendFragment(StringBuilder prompt, int index, TextSegment segment) {
        // 每个片段都携带自己的 Metadata（灌库时写入的 heading_path / doc_title / source 等）。
        Metadata metadata = segment.metadata();

        // 片段分隔标题：编号即模型在回答里应引用的 [n]，两边用 ===== 包起来便于模型识别边界。
        prompt.append("\n\n===== 文档片段 [").append(index).append("] =====\n");

        // 出处优先取标题路径（如「Declaring Advice > Before Advice」），
        // 取不到就退到文档标题、文件名；全都取不到时 origin 返回空串，这一行就不写。
        String origin = origin(metadata);
        if (!origin.isEmpty()) {
            prompt.append("出处：").append(origin).append('\n');
        }

        // 链接来自 metadata 的 source 字段（灌库时写入的原文 URL）。
        // 系统提示词要求「链接逐字复制」，所以这里必须原样输出、不做任何加工。
        String source = metadata.getString("source");
        if (source != null && !source.isBlank()) {
            prompt.append("链接：").append(source).append('\n');
        }

        // 最后是片段正文。前面加的「内容：」是给模型的分隔标签，
        // 让模型清楚知道哪段是文档原文，避免和出处/链接混在一起。
        prompt.append("内容：\n").append(segment.text());
    }

    /**
     * 计算片段的「出处」，按优先级依次尝试：
     * <ol>
     *   <li>{@code heading_path}：标题路径，最精确，能定位到具体小节；</li>
     *   <li>{@code doc_title}：整篇文档标题，次之；</li>
     *   <li>{@code file_name}：文件名，最兜底。</li>
     * </ol>
     * 三者都没有就返回空字符串，调用方据此决定不输出「出处」这一行。
     */
    private static String origin(Metadata metadata) {
        for (String key : List.of("heading_path", "doc_title", "file_name")) {
            String value = metadata.getString(key);
            // 同时过滤 null 和空白串：元数据里可能存了 "" 或 " "，
            // 这种值当作出处毫无意义，等同于没有。
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}