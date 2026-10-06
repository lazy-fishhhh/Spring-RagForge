package com.springragforge.rag.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 增量灌库的行为测试。全部用临时目录和假 embedding 模型，不碰真实语料、不打网络。
 */
class IncrementalIndexerTest {

    private static final String ADVICE =
            "spring-framework/core/aop/ataspectj/advice.adoc";
    private static final String SERVLET =
            "spring-boot/reference/pages/web/servlet.adoc";

    @TempDir
    Path tempDir;

    private Path corpusRoot;
    private IngestProperties properties;
    private CountingEmbeddingModel embeddingModel;
    private RecordingStore store;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private IncrementalIndexer indexer;

    @BeforeEach
    void setUp() {
        corpusRoot = tempDir.resolve("corpus");
        properties = new IngestProperties();
        properties.setCorpusRoot(corpusRoot.toString());
        properties.setStoreFile(tempDir.resolve("data/embedding-store.json"));
        properties.setManifestFile(tempDir.resolve("data/ingest-manifest.json"));
        embeddingModel = new CountingEmbeddingModel();
        store = new RecordingStore();
        indexer = newIndexer();
    }

    private IncrementalIndexer newIndexer() {
        return new IncrementalIndexer(store, embeddingModel, properties, objectMapper,
                new LocalDocumentSource(properties), "fake-embedding-model");
    }

    @Test
    void firstRunIndexesEveryDocument() throws IOException {
        writeDoc(ADVICE, adoc("Declaring Advice", "Before advice runs before the join point."));
        writeDoc(SERVLET, adoc("Servlet Web Applications", "Spring MVC is built on the Servlet API."));

        IngestStats stats = indexer.reindex();

        assertThat(stats.fullRebuild()).isTrue();
        assertThat(stats.added()).isEqualTo(2);
        assertThat(stats.updated()).isZero();
        assertThat(stats.failed()).isZero();
        assertThat(stats.embeddedChunks()).isEqualTo(2);
        // 灌库按 docKey 排序走文档，所以 spring-boot 排在 spring-framework 前面
        assertThat(store.addedIds()).containsExactlyInAnyOrder(
                List.of(ADVICE + "#0"), List.of(SERVLET + "#0"));
        assertThat(manifest().getDocuments()).containsOnlyKeys(ADVICE, SERVLET);
    }

    @Test
    void secondRunOverUnchangedCorpusCallsEmbeddingZeroTimes() throws IOException {
        writeDoc(ADVICE, adoc("Declaring Advice", "Before advice runs before the join point."));
        writeDoc(SERVLET, adoc("Servlet Web Applications", "Spring MVC is built on the Servlet API."));
        indexer.reindex();
        int callsAfterFirstRun = embeddingModel.calls();

        IngestStats second = indexer.reindex();

        assertThat(second.fullRebuild()).isFalse();
        assertThat(second.unchanged()).isEqualTo(2);
        assertThat(second.added() + second.updated() + second.deleted()).isZero();
        assertThat(second.embeddedChunks()).isZero();
        assertThat(embeddingModel.calls()).isEqualTo(callsAfterFirstRun);
        assertThat(store.addedIds()).hasSize(2);
    }

    @Test
    void changedDocumentReplacesOnlyItsOwnVectors() throws IOException {
        Path advice = writeDoc(ADVICE, adoc("Declaring Advice", "Before advice runs before the join point."));
        writeDoc(SERVLET, adoc("Servlet Web Applications", "Spring MVC is built on the Servlet API."));
        indexer.reindex();

        Files.writeString(advice, adoc("Declaring Advice", "After advice runs after the join point."));
        IngestStats second = indexer.reindex();

        assertThat(second.updated()).isEqualTo(1);
        assertThat(second.unchanged()).isEqualTo(1);
        assertThat(second.embeddedChunks()).isEqualTo(1);
        // 更新一篇 = 删它的旧 id、再写新 id，别的文档一个都不动
        assertThat(store.removedIds()).containsExactly(List.of(ADVICE + "#0"));
        assertThat(store.addedIds()).hasSize(3);
    }

    @Test
    void deletedDocumentVectorsAreRemoved() throws IOException {
        writeDoc(ADVICE, adoc("Declaring Advice", "Before advice runs before the join point."));
        Path servlet = writeDoc(SERVLET, adoc("Servlet Web Applications", "Spring MVC is built on the Servlet API."));
        indexer.reindex();

        Files.delete(servlet);
        IngestStats second = indexer.reindex();

        assertThat(second.deleted()).isEqualTo(1);
        assertThat(second.embeddedChunks()).isZero();
        assertThat(store.removedIds()).containsExactly(List.of(SERVLET + "#0"));
        assertThat(manifest().getDocuments()).containsOnlyKeys(ADVICE);
    }

    @Test
    void changedChunkerSignatureReindexesEverything() throws IOException {
        writeDoc(ADVICE, adoc("Declaring Advice", "Before advice runs before the join point."));
        writeDoc(SERVLET, adoc("Servlet Web Applications", "Spring MVC is built on the Servlet API."));
        indexer.reindex();
        int clearsAfterFirstRun = store.clearCount();

        // 模拟切分器改版：清单里的签名和当前签名对不上
        IngestManifest stale = manifest();
        stale.setChunkerSignature("asciidoc-v0/999/999");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(properties.getManifestFile().toFile(), stale);

        IngestStats second = indexer.reindex();

        assertThat(second.fullRebuild()).isTrue();
        assertThat(second.added()).isEqualTo(2);
        assertThat(second.updated()).isZero();
        assertThat(store.clearCount()).isEqualTo(clearsAfterFirstRun + 1);
    }

    @Test
    void missingCorpusRootAbortsWithoutTouchingTheStore() throws IOException {
        writeDoc(ADVICE, adoc("Declaring Advice", "Before advice runs before the join point."));
        indexer.reindex();
        int callsAfterFirstRun = embeddingModel.calls();
        int clearsAfterFirstRun = store.clearCount();

        properties.setCorpusRoot(tempDir.resolve("no-such-corpus").toString());
        IncrementalIndexer broken = newIndexer();

        assertThatThrownBy(broken::reindex)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("语料根目录不存在");
        // 中止发生在删任何向量之前：既没重新 embedding，也没清库、没删 id
        assertThat(embeddingModel.calls()).isEqualTo(callsAfterFirstRun);
        assertThat(store.clearCount()).isEqualTo(clearsAfterFirstRun);
        assertThat(store.removedIds()).isEmpty();
    }

    @Test
    void emptyScanWithNonEmptyManifestAbortsWithoutWipingVectors() throws IOException {
        writeDoc(ADVICE, adoc("Declaring Advice", "Before advice runs before the join point."));
        writeDoc(SERVLET, adoc("Servlet Web Applications", "Spring MVC is built on the Servlet API."));
        indexer.reindex();
        int callsAfterFirstRun = embeddingModel.calls();
        int clearsAfterFirstRun = store.clearCount();

        // 语料整体消失（等同于 bucket 空了），清单里却还记着两篇
        Files.deleteIfExists(corpusRoot.resolve(ADVICE));
        Files.deleteIfExists(corpusRoot.resolve(SERVLET));

        assertThatThrownBy(indexer::reindex)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("扫描到的语料为空");
        // 中止发生在删任何向量之前
        assertThat(embeddingModel.calls()).isEqualTo(callsAfterFirstRun);
        assertThat(store.clearCount()).isEqualTo(clearsAfterFirstRun);
        assertThat(store.removedIds()).isEmpty();
        assertThat(manifest().getDocuments()).containsOnlyKeys(ADVICE, SERVLET);
    }

    private Path writeDoc(String relativePath, String text) throws IOException {
        Path file = corpusRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    private static String adoc(String title, String body) {
        return "= " + title + "\n\n== Body\n\n" + body + "\n";
    }

    private IngestManifest manifest() throws IOException {
        return objectMapper.readValue(properties.getManifestFile().toFile(), IngestManifest.class);
    }

    /** 假 embedding 模型：只数调用次数，不联网。 */
    private static final class CountingEmbeddingModel implements EmbeddingModel {

        private int calls;

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            calls++;
            List<Embedding> embeddings = new ArrayList<>(textSegments.size());
            for (TextSegment segment : textSegments) {
                embeddings.add(Embedding.from(new float[]{segment.text().length(), 1f}));
            }
            return Response.from(embeddings);
        }

        int calls() {
            return calls;
        }
    }

    /** 记录增删调的向量库：包一层真的内存实现，只为断言能看清删了哪些、加了哪些。 */
    private static final class RecordingStore implements EmbeddingStore<TextSegment> {

        private final InMemoryEmbeddingStore<TextSegment> delegate = new InMemoryEmbeddingStore<>();
        private final List<List<String>> removedIds = new ArrayList<>();
        private final List<List<String>> addedIds = new ArrayList<>();
        private int clearCount;

        @Override
        public String add(Embedding embedding) {
            return delegate.add(embedding);
        }

        @Override
        public void add(String id, Embedding embedding) {
            delegate.add(id, embedding);
        }

        @Override
        public String add(Embedding embedding, TextSegment embedded) {
            return delegate.add(embedding, embedded);
        }

        @Override
        public List<String> addAll(List<Embedding> embeddings) {
            return delegate.addAll(embeddings);
        }

        @Override
        public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
            addedIds.add(List.copyOf(ids));
            delegate.addAll(ids, embeddings, embedded);
        }

        @Override
        public void removeAll(Collection<String> ids) {
            removedIds.add(List.copyOf(ids));
            delegate.removeAll(ids);
        }

        @Override
        public void removeAll(Filter filter) {
            delegate.removeAll(filter);
        }

        @Override
        public void removeAll() {
            clearCount++;
            delegate.removeAll();
        }

        @Override
        public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
            return delegate.search(request);
        }

        List<List<String>> removedIds() {
            return removedIds;
        }

        List<List<String>> addedIds() {
            return addedIds;
        }

        int clearCount() {
            return clearCount;
        }
    }
}
