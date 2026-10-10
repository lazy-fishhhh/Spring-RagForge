package com.springragforge.rag.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;

/**
 * 字符近似 token 估算：qwen 无公开分词器，用字符类别粗估。
 *
 * <p>中文（CJK）≈ 1 token/字，其余（英文、符号）≈ 1/4 字符。只用来稳定地切窗口，
 * 不追求精确计数——估算偏大宁可早摘要，别让真实请求超窗。
 *
 * <p>实现 {@link TokenCountEstimator}，可被 LangChain4j 的记忆/窗口组件直接使用。
 */
public class CharApproxTokenCountEstimator implements TokenCountEstimator {

    /**
     * 每条消息的固定结构开销（role 字段、分隔符等），量级一个短 token。
     *
     * <p>真实请求里每条消息除了正文还会带上 role、分隔符等模板内容，
     * 这里统一按 4 个 token 估算，避免只算正文导致整体偏小。
     */
    private static final int PER_MESSAGE_OVERHEAD = 4;

    /**
     * 估算一段纯文本的 token 数。
     *
     * <p>规则：
     * <ul>
     *     <li>CJK 字符（中文、日文、韩文、全角符号等）按 1 字符 = 1 token；</li>
     *     <li>其余字符（英文、数字、半角符号等）按 4 字符 = 1 token，向上取整。</li>
     * </ul>
     *
     * @param text 待估算文本，允许为 {@code null} 或空
     * @return 估算出的 token 数，空文本返回 0
     */
    @Override
    public int estimateTokenCountInText(String text) {
        // 空文本直接返回 0，避免后续无意义遍历
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;   // CJK 字符计数，1 字符 ≈ 1 token
        int other = 0; // 非 CJK 字符计数，4 字符 ≈ 1 token
        // 按 code point 遍历，正确处理代理对（如 emoji、扩展汉字）
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (isCjk(codePoint)) {
                cjk++;
            } else {
                other++;
            }
            // 跳过当前 code point 占用的 char 数（BMP 为 1，增补平面为 2）
            i += Character.charCount(codePoint);
        }
        // ceil(other / 4)：非 CJK 字符按 4 个算 1 token
        return cjk + (other + 3) / 4;
    }

    /**
     * 估算单条消息的 token 数：固定结构开销 + 正文文本估算。
     *
     * @param message 聊天消息
     * @return 估算出的 token 数
     */
    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
        return PER_MESSAGE_OVERHEAD + estimateTokenCountInText(textOf(message));
    }

    /**
     * 估算一组消息的总 token 数，逐条累加。
     *
     * @param messages 消息集合
     * @return 估算出的总 token 数
     */
    @Override
    public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
        int sum = 0;
        for (ChatMessage message : messages) {
            sum += estimateTokenCountInMessage(message);
        }
        return sum;
    }

    /**
     * 抽取消息的可读文本，摘要渲染和计数共用。
     *
     * <p>不同消息类型的正文位置不同：
     * <ul>
     *     <li>{@link SystemMessage}、{@link AiMessage}、{@link ToolExecutionResultMessage} 直接取 text；</li>
     *     <li>{@link UserMessage} 由多个 {@link Content} 组成，需要拼接文本内容，
     *         图片等非文本内容用占位符 {@code [image]} 表示。</li>
     * </ul>
     *
     * @param message 聊天消息
     * @return 可读文本，未知类型返回空串
     */
    static String textOf(ChatMessage message) {
        if (message instanceof SystemMessage system) {
            return system.text();
        }
        if (message instanceof AiMessage ai) {
            // AiMessage 可能是工具调用，text 为 null，这里归一化为空串
            return ai.text() == null ? "" : ai.text();
        }
        if (message instanceof ToolExecutionResultMessage tool) {
            // 工具执行结果文本同样可能为 null
            return tool.text() == null ? "" : tool.text();
        }
        if (message instanceof UserMessage user) {
            // 用户消息可能包含多段内容（文本 + 图片等），需要逐段拼接
            StringBuilder sb = new StringBuilder();
            for (Content content : user.contents()) {
                if (content instanceof TextContent text) {
                    sb.append(text.text());
                } else if (content instanceof ImageContent) {
                    // 图片无法直接计数，用占位符参与估算
                    sb.append("[image]");
                }
            }
            return sb.toString();
        }
        // 未知消息类型不计入正文
        return "";
    }

    /**
     * 判断某个 code point 是否属于 CJK（中日韩）及相关全角字符范围。
     *
     * <p>这些字符在 qwen 等模型的分词器中通常接近 1 字符 = 1 token，
     * 因此单独计数，与英文等按 4 字符 = 1 token 区分开。
     *
     * @param codePoint Unicode code point
     * @return 属于 CJK 相关范围返回 true
     */
    private static boolean isCjk(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)   // CJK 统一表意
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF) // 扩展 A
                || (codePoint >= 0x3000 && codePoint <= 0x303F) // CJK 标点
                || (codePoint >= 0xFF00 && codePoint <= 0xFFEF) // 全角
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF); // 谚文
    }
}