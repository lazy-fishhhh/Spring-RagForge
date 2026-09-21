package com.springragforge.config;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * 将一次 embedAll 拆成多批调用，规避 embedding 接口单次请求条数上限（如 DashScope 限 20 条）。
 * 其余方法（单条 embed、dimension 等）走接口默认实现，本类仅重写灌库实际调用的 embedAll。
 */
public class BatchEmbeddingModel implements EmbeddingModel {

    public static final int DEFAULT_BATCH_SIZE = 20;

    private final EmbeddingModel delegate;
    private final int batchSize;

    public BatchEmbeddingModel(EmbeddingModel delegate) {
        this(delegate, DEFAULT_BATCH_SIZE);
    }

    public BatchEmbeddingModel(EmbeddingModel delegate, int batchSize) {
        this.delegate = delegate;
        this.batchSize = batchSize;
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        List<Embedding> embeddings = new ArrayList<>(textSegments.size());
        for (int i = 0; i < textSegments.size(); i += batchSize) {
            List<TextSegment> batch = textSegments.subList(i, Math.min(i + batchSize, textSegments.size()));
            embeddings.addAll(delegate.embedAll(batch).content());
        }
        return Response.from(embeddings);
    }
}
