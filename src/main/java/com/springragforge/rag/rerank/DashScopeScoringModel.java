package com.springragforge.rag.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 阿里云百炼（DashScope）重排模型的 {@link ScoringModel} 实现。
 *
 * <p>langchain4j 只给了 ScoringModel 接口，没有官方的百炼重排实现，所以这里自己接一次 HTTP。
 * 走原生 text-rerank 端点，请求体是嵌套的 {@code {model, input:{query, documents}, parameters}}。
 *
 * <p>重排模型是多语种的，中文问题直接对英文片段打分即可，不需要像 BM25 那样先改写。
 *
 * <p>{@link #scoreAll} 必须返回与入参<b>等长同序</b>的分数列表——聚合器是按下标回填的。
 * 调用失败或解析失败时不让整个对话挂掉：退化成严格递减的分数，聚合器排序后仍是 RRF 原顺序。
 */
@Slf4j
public class DashScopeScoringModel implements ScoringModel {

    /** 原生 text-rerank 端点路径，拼在 base-url 之后。 */
    private static final String RERANK_PATH = "/services/rerank/text-rerank/text-rerank";

    private final String url;
    private final String apiKey;
    private final String modelName;
    private final Duration timeout;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public DashScopeScoringModel(String baseUrl, String apiKey, String modelName,
                                 Duration timeout, ObjectMapper mapper) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.url = base + RERANK_PATH;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.timeout = timeout;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        int n = segments.size();
        if (n == 0) {
            return Response.from(List.of());
        }
        List<String> documents = new ArrayList<>(n);
        for (TextSegment segment : segments) {
            documents.add(segment.text());
        }
        try {
            // 组装请求：地址 + 超时 + 鉴权头 + JSON 声明 + POST body
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    //指定post请求和请求体，BodyPublishers.ofString(...) 把字符串包装成“可以发送的 body”。
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(documents, query))) // POST + 请求体
                    .build();
            // 真正发出请求，HttpResponse.BodyHandlers.ofString()响应体按字符串接收
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            // 百炼的日志开关只覆盖它自家的模型，这里自己发的 HTTP 得自己打
            log.debug("重排响应 HTTP {}: {}", response.statusCode(), truncate(body));
            if (response.statusCode() != 200) {
                log.warn("重排调用失败 HTTP {}，退回 RRF 顺序: {}", response.statusCode(), truncate(body));
                return Response.from(fallbackOrder(n));
            }
            List<Double> scores = parseScores(body, n);
//            log.debug("重排得分: {}", scores);
            return Response.from(scores);
        } catch (Exception e) {
            log.warn("重排调用异常，退回 RRF 顺序: {}", e.toString());
            return Response.from(fallbackOrder(n));
        }
    }

    /**把 documents 和 query 拼成 DashScope 要的嵌套 JSON 字符串
     * 最终结果：
     * {
     *   "model": "...",
     *   "input": { "query": "...", "documents": ["...", "..."] },
     *   "parameters": { "return_documents": false }
     * }
     */
    private String buildRequestBody(List<String> documents, String query) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", modelName);
        ObjectNode input = root.putObject("input");
        input.put("query", query);
        ArrayNode docs = input.putArray("documents");
        documents.forEach(docs::add);// 便利添加
        // 只要分数不要原文，省带宽
        root.putObject("parameters").put("return_documents", false);
        return mapper.writeValueAsString(root); // 序列化
    }

    /** 返回的 results 按分数降序，用其中的 index 归位；没出现的下标补 0.0，保证等长同序。 */
    private List<Double> parseScores(String body, int n) throws Exception {
        JsonNode results = mapper.readTree(body).path("output").path("results");
        if (!results.isArray() || results.isEmpty()) {
            log.warn("重排响应里没有 output.results，检查端点/模型名/报文结构: {}", truncate(body));
        }
        List<Double> scores = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            scores.add(0.0);
        }
        for (JsonNode result : results) {
            // 获取下标，缺失则返回-1
            int index = result.path("index").asInt(-1);
            if (index >= 0 && index < n) {
                scores.set(index, result.path("relevance_score").asDouble(0.0));
            }
        }
        return scores;
    }

    /** 失败降级：手动排序赋分
     * 因为RRF排序默认是分数降序的，所以现在手动从高到低赋分，原样还回去（1，0.999999，0.99998...）
     * 都大于任何正常阈值，不会被误过滤。
     * */
    private static List<Double> fallbackOrder(int n) {
        List<Double> scores = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            scores.add(1.0 - i * 1e-6);
        }
        return scores;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 1000 ? text.substring(0, 1000) : text;
    }
}
