package com.springragforge.rag.retriever;

import com.springragforge.constant.SystemPrompts;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把中文问题改写成英文检索词，供 BM25 那一路使用。
 *
 * <p>解决的是语料与提问语言不一致：语料是英文文档，BM25 做词法匹配，
 * 中文问句在英文索引里命中数为零。改写后词法路才有东西可匹配。
 *
 * <p>只给 BM25 用。KNN 那一路仍拿原始问题——跨语言 embedding 本来就吃中文，
 * 换成英文关键词反而会把问题的语义削掉一层。
 *
 * <p>改写是额外一次 LLM 调用，失败或输出为空时一律回退成原始问题：
 * 检索退化回改写前的行为，不影响主流程。
 */
@Slf4j
@Component
public class QueryRewriter {

    private final ChatModel chatModel;
    private final RetrievalProperties properties;

    public QueryRewriter(ChatModel chatModel, RetrievalProperties properties) {
        this.chatModel = chatModel;
        this.properties = properties;
    }

    /**
     * 返回用于词法检索的查询串：改写开启且成功时是英文关键词串，否则是原问题。
     */
    public String rewrite(String question) {
        if (!properties.isRewriteEnabled()) {
            return question;
        }
        try {
            String rewritten = chatModel.chat(List.of(
                    SystemMessage.from(SystemPrompts.QUERY_REWRITE_PROMPT),
                    UserMessage.from(question))).aiMessage().text();
            if (rewritten == null || rewritten.isBlank()) {
                log.warn("查询改写返回空，回退为原问题: {}", question);
                return question;
            }
            String cleaned = rewritten.replaceAll("\\s+", " ").trim();
            log.debug("查询改写: '{}' -> '{}'", question, cleaned);
            return cleaned;
        } catch (Exception e) {
            // 改写只是加一条召回路，失败了不该让整个检索失败
            log.warn("查询改写失败，回退为原问题: {} ({})", question, e.toString());
            return question;
        }
    }
}
