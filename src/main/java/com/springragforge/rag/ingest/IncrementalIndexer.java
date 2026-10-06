package com.springragforge.rag.ingest;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springragforge.config.BatchEmbeddingModel;
import com.springragforge.rag.splitter.AsciiDocDocumentSplitter;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 增量灌库：只重灌内容变了的文档，没变的直接跳过。
 *
 * <p>为什么不能用 {@code EmbeddingStoreIngestor}：它自己生成 UUID 当向量 id，灌完就再也定位不到
 * 某个文档的那些向量，想按文档更新只能全库重建。所以这里改成显式 id —— 直接用切分器产出的
 * {@code chunk_id} 当向量 id，配合 {@link IngestManifest} 记下每个文档产出了哪些 id，
 * 就能整篇替换。
 *
 * <p>比对粒度是文档而不是片段：{@code chunk_id} 里带着段序号，文档中间插一段会让后面所有段的
 * 序号整体漂移，逐片段比对没有意义。所以按文件内容哈希判断，变了就整篇重切重灌。
 *
 * <p>每次跑完都会落盘两样东西：向量库和清单。清单里同时记着切分器签名和 embedding 模型名，
 * 这两样一变，历史向量就不可比了，直接清库全量重来。
 *
 * <p>向量库换成了 ES：删除和落盘的语义跟内存实现不同，见 {@link EsIndexSchema} 与
 * {@link #resetStore()}。其余逻辑对着 {@code EmbeddingStore} 接口写，两种实现共用。
 */
@Slf4j
@Component
public class IncrementalIndexer {

    /** 单次灌库最多记多少条失败原因。失败计数不受此限。 */
    private static final int MAX_RECORDED_FAILURES = 20;

    private final EmbeddingStore<TextSegment> store;
    private final IngestProperties properties;
    private final ObjectMapper objectMapper;
    /** 语料来源：本地磁盘或 MinIO，按 rag.ingest.storage 配置选择。 */
    private final DocumentSource documentSource;
    private final String embeddingModelName;
    /** 切分器按配置里的语料根构造，保证切出来的 chunk_id 和清单里的 key 同源。 */
    private final AsciiDocDocumentSplitter splitter;
    private final BatchEmbeddingModel batchEmbeddingModel;

    /**
     * ES 客户端。全量重建时要靠它「删索引 + 按 mapping 重建」，因为 store 层没有
     * 这种操作。测试里灌的是内存实现，用不到，允许为 null。
     */
    private final ElasticsearchClient elasticsearchClient;

    /** 同一时刻只允许一次灌库，避免手动连点两次导致清单和向量库互相覆盖。 */
    private final AtomicBoolean running = new AtomicBoolean();

    @Autowired
    public IncrementalIndexer(
            EmbeddingStore<TextSegment> store,
            EmbeddingModel embeddingModel,
            IngestProperties properties,
            ObjectMapper objectMapper,
            DocumentSource documentSource,
            @Value("${langchain4j.open-ai.embedding-model.model-name:unknown}") String embeddingModelName,
            @Nullable ElasticsearchClient elasticsearchClient) {
        this.store = store;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.documentSource = documentSource;
        this.embeddingModelName = embeddingModelName;
        this.splitter = new AsciiDocDocumentSplitter(properties.getCorpusRoot());
        // 单次请求条数有上限（DashScope 20 条），灌库必须分批
        this.batchEmbeddingModel = new BatchEmbeddingModel(embeddingModel);
        this.elasticsearchClient = elasticsearchClient;
    }

    /** 测试用：不带 ES 客户端，灌内存实现。 */
    IncrementalIndexer(
            EmbeddingStore<TextSegment> store,
            EmbeddingModel embeddingModel,
            IngestProperties properties,
            ObjectMapper objectMapper,
            DocumentSource documentSource,
            String embeddingModelName) {
        this(store, embeddingModel, properties, objectMapper, documentSource,
                embeddingModelName, null);
    }

    /**
     * 执行一次增量灌库。同步执行，调用方（HTTP 请求线程）等结果。
     *
     * @return 本次灌库的统计
     * @throws IllegalStateException 已有灌库在跑，或语料根目录不存在（后者会中止，不动任何数据）
     */
    public IngestStats reindex() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("已有灌库任务在执行，请等它结束后再试");
        }
        long startedAt = System.currentTimeMillis();
        try {
            return doReindex(startedAt);
        } finally {
            running.set(false);
        }
    }

    private IngestStats doReindex(long startedAt) {
        // 先扫描再动数据：语料根配错时要在删任何东西之前就抛出去
        Map<String, DocumentSource.StoredDocument> files = documentSource.scan();

        IngestManifest manifest = readManifest();
        // 扫描结果为空但清单里还记着文档：多半是存储侧出了问题（bucket 空了、挂载点了、
        // 语料被误清），此时按「文档已删除」继续会把向量库清空。宁可中止。
        if (manifest != null && !manifest.getDocuments().isEmpty() && files.isEmpty()) {
            throw new IllegalStateException("扫描到的语料为空，但清单里还有 "
                    + manifest.getDocuments().size() + " 篇文档，已中止灌库（不会删除任何向量）");
        }
        String signature = splitter.signature();
        // 清单丢了、清单和当前配置不匹配、或落盘的向量库没了，历史向量都定位不到，只能全量重建
        boolean reusable = manifest != null
                && storeIntact()
                && signature.equals(manifest.getChunkerSignature())
                && embeddingModelName.equals(manifest.getEmbeddingModel());
        if (!reusable) {
            // 全量重建：清干净旧向量，也顺便处理了「模型换了，旧向量不可比」。
            // 清库方式按 store 实现分派，见 resetStore()。
            resetStore();
            manifest = new IngestManifest();
            log.info("灌库走全量重建（首次灌库 / 清单或向量库缺失 / 切分器或模型变更）");
        }
        manifest.setChunkerSignature(signature);
        manifest.setEmbeddingModel(embeddingModelName);

        int added = 0;
        int updated = 0;
        int deleted = 0;
        int unchanged = 0;
        int failed = 0;
        int embeddedChunks = 0;
        List<String> failures = new ArrayList<>();

        // 1. 删：清单里有、磁盘上已经没有的文档
        for (String docKey : new TreeSet<>(manifest.getDocuments().keySet())) {
            if (files.containsKey(docKey)) {
                continue;
            }
            IngestManifest.DocEntry entry = manifest.getDocuments().remove(docKey);
            removeVectors(entry.getChunkIds());
            deleted++;
            log.info("语料已删除，移除向量: {} ({} 个片段)", docKey, entry.getChunkIds().size());
        }

        // 2. 增/改：按文件哈希比对
        for (Map.Entry<String, DocumentSource.StoredDocument> file : files.entrySet()) {
            String docKey = file.getKey();
            DocumentSource.StoredDocument stored = file.getValue();
            try {
                byte[] content = stored.readContent();
                String sha256 = sha256(content);
                IngestManifest.DocEntry previous = manifest.getDocuments().get(docKey);
                if (previous != null && sha256.equals(previous.getSha256())) {
                    // 内容没变：一次 embedding 都不调，这是增量灌库的意义所在
                    unchanged++;
                    continue;
                }

                List<TextSegment> segments = split(content, stored);
                if (segments.isEmpty()) {
                    // 文档被掏空了，旧向量不能留着当幽灵
                    if (previous != null) {
                        removeVectors(previous.getChunkIds());
                        manifest.getDocuments().remove(docKey);
                        deleted++;
                    }
                    continue;
                }

                List<String> ids = chunkIds(segments);
                List<Embedding> embeddings = batchEmbeddingModel.embedAll(segments).content();
                if (embeddings.size() != segments.size()) {
                    throw new IllegalStateException(
                            "embedding 返回条数与片段数不一致: " + embeddings.size() + " != " + segments.size());
                }
                embeddedChunks += segments.size();

                // 先 embed 成功，再删旧写新：中途失败最多是这个文档没更新，不会留下删除后的空档
                removeVectors(previous == null ? List.of() : previous.getChunkIds());
                store.addAll(ids, embeddings, new ArrayList<>(segments));
                manifest.getDocuments().put(docKey, new IngestManifest.DocEntry(sha256, ids));

                if (previous == null) {
                    added++;
                    log.info("新增文档: {} ({} 个片段)", docKey, ids.size());
                } else {
                    updated++;
                    log.info("更新文档: {} ({} 个片段)", docKey, ids.size());
                }
            } catch (Exception e) {
                // 单个文档失败不影响其它文档，也不写清单，下次灌库自然会重试
                failed++;
                log.error("文档灌库失败，本次跳过: {} ({})", docKey, e.toString());
                if (failures.size() < MAX_RECORDED_FAILURES) {
                    failures.add(docKey + ": " + e);
                }
            }
        }

        // 3. 收尾：先落向量库、再写清单。顺序不能反——
        // 中间挂掉的话，清单落后于向量库只是下次多灌一遍（幂等），
        // 反过来则是清单声称已灌、向量库里其实没有，那些文档会被永久跳过。
        serializeStore();
        writeManifest(manifest);

        long elapsed = System.currentTimeMillis() - startedAt;
        log.info("灌库结束: 重建={} 新增={} 更新={} 删除={} 跳过={} 失败={} 送 embed 片段={} 耗时={}ms",
                !reusable, added, updated, deleted, unchanged, failed, embeddedChunks, elapsed);
        return new IngestStats(!reusable, added, updated, deleted, unchanged, failed, embeddedChunks, elapsed, failures);
    }

    /**
     * 全量重建时清空历史向量。
     *
     * <p>两种实现语义不同，必须分开：
     * <ul>
     *   <li>内存实现：{@code removeAll()} 把内容清空，mapping 概念不存在，直接用。</li>
     *   <li>ES 实现：{@code removeAll()} 是「删掉整个索引」（langchain4j 内部调
     *       {@code indices.delete}），删完 mapping 一起没了，之后写入的向量只是普通数组，
     *       KNN 检索会失败。所以 ES 走 {@link EsIndexSchema#recreateIndex}：
     *       删索引 + 按当前维度重建。</li>
     * </ul>
     */
    private void resetStore() {
        if (elasticsearchClient != null && !(store instanceof InMemoryEmbeddingStore<?>)) {
            EsIndexSchema.recreateIndex(
                    elasticsearchClient, properties.getIndexName(), properties.getDimension());
            return;
        }
        // 内存实现（原逻辑）：清空内容即可，没有 mapping 要维护。
        store.removeAll();
    }

    /**
     * 向量库里上一次灌的数据还在不在。
     *
     * <p>只对「落盘的内存实现」有意义：进程重启后它从文件恢复，文件没了就是空库，
     * 这时清单再完整也不能信，否则会跳过所有文档、留下一个空库。
     * ES 是外部持久化实现，数据不在本地文件里，直接返回 true（见下方分支）。
     */
    private boolean storeIntact() {
        if (!(store instanceof InMemoryEmbeddingStore<?>)) {
            return true;
        }
        return Files.exists(properties.getStoreFile());
    }

    /**
     * 切分单篇文档。用文档来源给的虚拟目录和文件名重建 langchain4j Document，
     * 切分器靠这些元数据算 chunk_id 和 source——和以前从磁盘加载时完全一致。
     */
    private List<TextSegment> split(byte[] content, DocumentSource.StoredDocument stored) {
        Metadata metadata = new Metadata();
        metadata.put(Document.ABSOLUTE_DIRECTORY_PATH, stored.virtualDirectoryPath());
        metadata.put(Document.FILE_NAME, stored.fileName());
        Document document = Document.from(new String(content, StandardCharsets.UTF_8), metadata);
        return splitter.split(document);
    }

    /** 取每个片段的 chunk_id 当向量 id，缺一个都不能继续，否则删不掉也更新不了。 */
    private static List<String> chunkIds(List<TextSegment> segments) {
        List<String> ids = new ArrayList<>(segments.size());
        for (TextSegment segment : segments) {
            String chunkId = segment.metadata().getString("chunk_id");
            if (chunkId == null || chunkId.isBlank()) {
                throw new IllegalStateException("片段缺少 chunk_id，无法作为向量 id: "
                        + segment.text().substring(0, Math.min(40, segment.text().length())));
            }
            ids.add(chunkId);
        }
        return ids;
    }

    /** 按 id 删向量。空列表直接跳过：有些实现不接受空集合。 */
    private void removeVectors(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        store.removeAll(ids);
    }

    /** 读清单。文件不存在或内容坏了都返回 null，由调用方按全量重建处理。 */
    private IngestManifest readManifest() {
        Path file = properties.getManifestFile();
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(file.toFile(), IngestManifest.class);
        } catch (Exception e) {
            log.warn("灌库清单读取失败，本次按全量重建处理: {}", file, e);
            return null;
        }
    }

    /** 写清单。先写临时文件再原子替换，避免写一半崩了留下半截 JSON。 */
    private void writeManifest(IngestManifest manifest) {
        Path file = properties.getManifestFile();
        try {
            createParentDirectories(file);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), manifest);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("写入灌库清单失败: " + file, e);
        }
    }

    /**
     * 向量库落盘。只对内存实现有意义：它重启后要从文件恢复。
     *
     * <p>ES 是外部持久化存储，数据本身就在 ES 里，没有落盘这回事，
     * 命中下面的分支打印一条 warn 直接跳过（旧代码保留备查）。
     */
    private void serializeStore() {
        Path file = properties.getStoreFile();
        if (!(store instanceof InMemoryEmbeddingStore<?> inMemoryStore)) {
            log.warn("当前向量库不是内存实现，跳过落盘: {}", store.getClass().getName());
            return;
        }
        try {
            createParentDirectories(file);
            inMemoryStore.serializeToFile(file);
        } catch (IOException e) {
            throw new UncheckedIOException("向量库落盘失败: " + file, e);
        }
    }

    private static void createParentDirectories(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    /** 字节内容的 sha256，十六进制小写。比 mtime 可靠：换机器、git checkout 后 mtime 全变。 */
    private static String sha256(byte[] content) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
        return HexFormat.of().formatHex(digest.digest(content));
    }
}
