package com.springragforge.rag.retriever;

import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.CompressingQueryTransformer;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;

/**
 * 多轮指代消解：把带指代的追问（如"它怎么配置"、"上面那个方法呢"）结合会话历史，
 * 改写成独立完整的问题，再交给下游检索。改写逻辑复用官方 {@link CompressingQueryTransformer}，
 * 本类只补失败回退——官方这层不捕获异常，LLM 不可用会把整个检索打断；这里退回原问题，
 * 消解退化成不消解，主流程照常。
 *
 * <p>首轮跳过不用在这做：官方在 chatMemory 为空时本来就是原样返回，不调模型。
 *
 * <p>改写后的查询同时喂给两路：KNN 吃消解后的中文独立问题（跨语言 embedding），
 * BM25 仍走 {@link QueryRewriter} 英译（语料是英文）。
 */
@Slf4j
public class CoreferenceQueryTransformer implements QueryTransformer {

    private final CompressingQueryTransformer delegate;

    public CoreferenceQueryTransformer(CompressingQueryTransformer delegate) {
        this.delegate = delegate;
    }

    @Override
    public Collection<Query> transform(Query query) {
        try {
            return delegate.transform(query);
        } catch (Exception e) {
            log.warn("指代消解失败，回退为原问题: {} ({})", query.text(), e.toString());
            return List.of(query);
        }
    }
}
