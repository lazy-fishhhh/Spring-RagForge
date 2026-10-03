package com.springragforge.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springragforge.rag.retriever.RetrievalProperties;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 检索层评测：先调 ContentRetriever 召回，再走 ContentAggregator 重排（受 rag.rerank.enabled 控制），
 * 不过 LLM。只调 retriever 的话重排那一级会被跳过，所以这里必须经聚合器。
 *
 *
 * <p>每题取 top-K 命中，按 gold_source 的 (file, section) 判定是否命中：
 * <ul>
 *   <li>file 对块 {@code chunk_id} 里 {@code '#'} 之前的文档路径（相对语料根）</li>
 *   <li>section 对块的 {@code heading_path}，前缀或包含即算命中（块可能落在更深的小节）</li>
 * </ul>
 *
 * <p>指标只统计有 gold_source 的题；refuse 类题没有 gold，明细照记但不进 Recall/MRR。
 *
 * <p>默认不跑（怕全量 mvn test 打 embedding 接口）。跑法：
 * <pre>mvn test -Dtest=RetrievalEvalTest -Deval.run=true -Deval.config=baseline</pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "eval.run", matches = "true")
class RetrievalEvalTest {

    /** 评测集路径，相对项目根。改配置名/问题集都用系统属性覆盖，不动本类。 */
    private static final String DEFAULT_QUESTIONS = "eval/questions-f-m.json";
    /** 配置名，只用来给 CSV 命名，方便一次改一个变量时区分文件。 */
    private static final String DEFAULT_CONFIG_NAME = "hunhe0";//baseline
    private static final String DEFAULT_OUT_DIR = "eval";

    @Resource
    private ContentRetriever contentRetriever;

    @Resource
    private ContentAggregator contentAggregator;

    @Resource
    private RetrievalProperties retrievalProperties;

    @Resource
    private ObjectMapper objectMapper;

    @Test
    void runRetrievalEval() throws IOException {
        Path questionsFile = Path.of(System.getProperty("eval.questions", DEFAULT_QUESTIONS));
        String configName = System.getProperty("eval.config", DEFAULT_CONFIG_NAME);
        Path outFile = Path.of(System.getProperty("eval.out", DEFAULT_OUT_DIR), "results-" + configName + ".csv");
        // 默认评到检索器实际返回的条数上限，跟 rag.retrieval.max-results 对齐，
        // 免得参数一改评测口径就漂；另有 -Deval.topK 可强行覆盖
        int topK = Integer.getInteger("eval.topK", retrievalProperties.getMaxResults());

        EvalSet evalSet = objectMapper.readValue(questionsFile.toFile(), EvalSet.class);

        List<Detail> details = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        int goldTotal = 0;
        int goldHit = 0;
        double mrrSum = 0;

        for (Question q : evalSet.questions()) {
            long startedAt = System.nanoTime();
            Query query = Query.from(q.question());
            // 召回拿候选池，再经聚合器重排；延迟把重排那一次 HTTP 也算进去
            List<Content> candidates = contentRetriever.retrieve(query);
            Map<Query, Collection<List<Content>>> queryToContents = new LinkedHashMap<>();
            queryToContents.put(query, List.of(candidates));
            List<Content> hits = contentAggregator.aggregate(queryToContents);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            latencies.add(elapsedMs);

            List<GoldSource> golds = q.goldSource() == null ? List.of() : q.goldSource();
            // rank = 第一个命中块的排名（1 起），没命中就是 -1
            int rank = -1;
            for (int i = 0; i < hits.size() && i < topK; i++) {
                if (matchesGold(hits.get(i).textSegment(), golds)) {
                    rank = i + 1;
                    break;
                }
            }

            boolean scored = !golds.isEmpty();
            if (scored) {
                goldTotal++;
                if (rank > 0) {
                    goldHit++;
                    mrrSum += 1.0 / rank;
                }
            }
            details.add(new Detail(q.id(), q.type(), scored, rank, elapsedMs,
                    describe(hits, topK), q.question()));
        }

        writeCsv(outFile, details);

        // 一行汇总：Recall / MRR（K 取上面 topK）/ P95 延迟
        System.out.printf(Locale.ROOT,
                "[retrieval-eval] config=%s Recall@%d=%.4f (%d/%d) MRR@%d=%.4f P95=%dms total=%d gold=%d csv=%s%n",
                configName, topK, goldTotal == 0 ? 0 : (double) goldHit / goldTotal, goldHit, goldTotal,
                topK, goldTotal == 0 ? 0 : mrrSum / goldTotal,
                percentile95(latencies), details.size(), goldTotal, outFile);
    }

    /** 块是否属于任一 gold 章节。 */
    private static boolean matchesGold(TextSegment segment, List<GoldSource> golds) {
        if (golds.isEmpty()) {
            return false;
        }
        Metadata metadata = segment.metadata();
        String docKey = documentKey(metadata);
        String headingPath = normalize(metadata.getString("heading_path"));
        for (GoldSource gold : golds) {
            if (gold.file() == null || gold.file().isBlank()) {
                continue;
            }
            if (!normalize(gold.file()).equals(docKey)) {
                continue;
            }
            String section = normalize(gold.section());
            if (section.isEmpty() || headingPath.contains(section)) {
                return true;
            }
        }
        return false;
    }

    /** 从 chunk_id 取文档路径（'#' 之前那段）。chunk_id 是文档路径 + 段序号。 */
    private static String documentKey(Metadata metadata) {
        String chunkId = metadata.getString("chunk_id");
        if (chunkId == null || chunkId.isBlank()) {
            return "";
        }
        int hash = chunkId.lastIndexOf('#');
        return normalize(hash < 0 ? chunkId : chunkId.substring(0, hash));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\\', '/').trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** 命中来源列表，形如 {@code 1:file.adoc#12[heading > path] | 2:...}，看错在哪就靠它。 */
    private static String describe(List<Content> hits, int topK) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size() && i < topK; i++) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            TextSegment segment = hits.get(i).textSegment();
            Metadata metadata = segment.metadata();
            String chunkId = metadata.getString("chunk_id");
            sb.append(i + 1).append(':')
                    .append(chunkId == null || chunkId.isBlank() ? "(无 chunk_id)" : chunkId);
            String headingPath = metadata.getString("heading_path");
            if (headingPath != null && !headingPath.isBlank()) {
                sb.append('[').append(headingPath).append(']');
            }
        }
        return sb.toString();
    }

    /** 最近秩 P95：升序后取第 ceil(0.95n) 个。 */
    private static long percentile95(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int index = (int) Math.ceil(0.95 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static void writeCsv(Path file, List<Detail> details) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // 带 BOM 写出，Excel 才认 UTF-8，中文问题列不会乱码
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write('\uFEFF');
            writer.write("id,type,scored,hit,rank,elapsed_ms,hit_sources,question");
            writer.newLine();
            for (Detail d : details) {
                writer.write(String.join(",",
                        csv(d.id()), csv(d.type()), d.scored() ? "1" : "0",
                        d.scored() ? (d.rank() > 0 ? "1" : "0") : "",
                        d.rank() > 0 ? String.valueOf(d.rank()) : "",
                        String.valueOf(d.elapsedMs()),
                        csv(d.hitSources()), csv(d.question())));
                writer.newLine();
            }
        }
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    // ---- 评测集结构，字段名跟 questions-r.json 对齐 ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EvalSet(List<Question> questions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Question(String id, String type, String question,
                            @JsonProperty("gold_source") List<GoldSource> goldSource) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GoldSource(String file, String section) {
    }

    /** 一题的明细。 */
    private record Detail(String id, String type, boolean scored, int rank, long elapsedMs,
                          String hitSources, String question) {
    }
}
