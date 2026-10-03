package com.springragforge.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springragforge.rag.SourceInfo;
import com.springragforge.rag.SourceRegistry;
import com.springragforge.service.ChatService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成层评测：走完整 RAG 链路（召回 + 注入 + 流式生成），把每题的回答和来源存成 JSON，
 * 供人工或后续脚本按 key_points / should_refuse 判分。
 *
 * <p>每题用独立的 memoryId 调 {@link ChatService}：
 * <ul>
 *   <li>memoryId 隔离会话，题目之间不会互相污染（RedisChatMemoryStore 按 id 存历史）</li>
 *   <li>memoryId 带本次 runId 前缀，避免连跑两轮时 Redis 里的旧会话被带上</li>
 *   <li>{@link SourceRegistry} 正好用 memoryId 做 key，所以流结束后能取回本题召回的来源</li>
 * </ul>
 *
 * <p>前置条件：向量库必须先灌好（{@code POST /admin/reindex}），否则 store 为空，
 * 每题都会走成拒答。另外要能连上 Redis 和模型接口。
 *
 * <p>默认不跑。跑法：
 * <pre>mvn test -Dtest=GenerationEvalTest -Deval.run=true -Deval.config=baseline</pre>
 * 可覆盖项：{@code -Deval.questions=... -Deval.out=eval}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "eval.run", matches = "true")
class GenerationEvalTest {

    private static final String DEFAULT_QUESTIONS = "eval/question-shengcheng.json";
    /** 配置名，只用来给输出文件命名，方便一次改一个变量时区分跑批。 */
    private static final String DEFAULT_CONFIG_NAME = "hunhe0";// baseline
    private static final String DEFAULT_OUT_DIR = "eval";
    /** 单题上限。流式接口偶尔卡住时能继续跑下一题，不至于整批挂死。 */
    private static final Duration PER_QUESTION_TIMEOUT = Duration.ofMinutes(3);
    /** 匹配回答正文里的引用标记，与 ChatController 保持一致。 */
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[ref:(\\d+)\\]");

    @Resource
    private ChatService chatService;

    @Resource
    private SourceRegistry sourceRegistry;

    @Resource
    private ObjectMapper objectMapper;

    @Test
    void runGenerationEval() throws IOException {
        Path questionsFile = Path.of(System.getProperty("eval.questions", DEFAULT_QUESTIONS));
        String configName = System.getProperty("eval.config", DEFAULT_CONFIG_NAME);
        Path outFile = Path.of(System.getProperty("eval.out", DEFAULT_OUT_DIR),
                "generation-" + configName + ".json");
        String runId = Long.toString(System.currentTimeMillis());
        String memoryIdPrefix = "eval-" + runId + "-";

        EvalSet evalSet = objectMapper.readValue(questionsFile.toFile(), EvalSet.class);

        List<Result> results = new ArrayList<>();
        long latencySum = 0;
        int errorCount = 0;

        for (Question q : evalSet.questions()) {
            String memoryId = memoryIdPrefix + q.id();
            StringBuilder answerBuffer = new StringBuilder();

            long startedAt = System.nanoTime();
            String error = null;
            try {
                chatService.chat(q.question(), memoryId)
                        .doOnNext(answerBuffer::append)
                        .blockLast(PER_QUESTION_TIMEOUT);
            } catch (RuntimeException e) {
                // 单题失败只记账，不中断整批；答案可能只攒到一半，一并留着供排查
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                errorCount++;
            }
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            latencySum += elapsedMs;

            List<SourceInfo> retrieved = sourceRegistry.get(memoryId);
            sourceRegistry.remove(memoryId);

            String answer = answerBuffer.toString();
            Set<Integer> cited = extractCitedIndexes(answer);
            List<SourceInfo> citedSources = retrieved.stream()
                    .filter(s -> cited.contains(s.index()))
                    .toList();

            results.add(new Result(q.id(), q.type(), q.question(),
                    q.keyPoints(), q.goldSource(), q.shouldRefuse(), q.refuseScope(), q.note(),
                    answer, citedSources, retrieved, elapsedMs, error));

            System.out.printf(Locale.ROOT,
                    "[generation-eval] %s done in %dms answerChars=%d retrieved=%d cited=%d%s%n",
                    q.id(), elapsedMs, answer.length(), retrieved.size(), citedSources.size(),
                    error == null ? "" : " error=" + error);
        }

        GenerationReport report = new GenerationReport(runId, configName, Instant.now().toString(),
                questionsFile.toString(), results.size(), errorCount,
                results.isEmpty() ? 0 : latencySum / results.size(), results);
        writeJson(outFile, report);

        System.out.printf(Locale.ROOT,
                "[generation-eval] config=%s total=%d errors=%d avgLatency=%dms json=%s%n",
                configName, results.size(), errorCount, report.avgLatencyMs(), outFile);
    }

    /** 从回答正文里提取所有 [ref:n] 引用编号；模型虚构的编号会在过滤时被自然忽略。 */
    private static Set<Integer> extractCitedIndexes(String answer) {
        Set<Integer> indexes = new HashSet<>();
        Matcher m = CITATION_PATTERN.matcher(answer);
        while (m.find()) {
            indexes.add(Integer.parseInt(m.group(1)));
        }
        return indexes;
    }

    private void writeJson(Path file, GenerationReport report) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8);
    }

    // ---- 评测集结构，字段名跟 question-shengcheng.json 对齐 ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EvalSet(List<Question> questions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Question(String id, String type, String question,
                            @JsonProperty("key_points") List<String> keyPoints,
                            @JsonProperty("gold_source") List<GoldSource> goldSource,
                            @JsonProperty("should_refuse") Boolean shouldRefuse,
                            @JsonProperty("refuse_scope") String refuseScope,
                            @JsonProperty("derived_from") String derivedFrom,
                            String note) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GoldSource(String file, String section) {
    }

    // ---- 输出结构 ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GenerationReport(String runId,
                                    @JsonProperty("config") String config,
                                    @JsonProperty("started_at") String startedAt,
                                    @JsonProperty("questions_file") String questionsFile,
                                    @JsonProperty("total") int total,
                                    @JsonProperty("error_count") int errorCount,
                                    @JsonProperty("avg_latency_ms") long avgLatencyMs,
                                    @JsonProperty("results") List<Result> results) {
    }

    /** 一题的结果。题目侧的字段原样带出来，判分脚本不用再回头 join 问题集。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Result(String id, String type, String question,
                          @JsonProperty("key_points") List<String> keyPoints,
                          @JsonProperty("gold_source") List<GoldSource> goldSource,
                          @JsonProperty("should_refuse") Boolean shouldRefuse,
                          @JsonProperty("refuse_scope") String refuseScope,
                          @JsonProperty("note") String note,
                          @JsonProperty("answer") String answer,
                          @JsonProperty("cited_sources") List<SourceInfo> citedSources,
                          @JsonProperty("retrieved_sources") List<SourceInfo> retrievedSources,
                          @JsonProperty("elapsed_ms") long elapsedMs,
                          @JsonProperty("error") String error) {
    }
}
