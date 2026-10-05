package com.springragforge.controller;

import com.springragforge.rag.DegradationRegistry;
import com.springragforge.rag.SourceInfo;
import com.springragforge.rag.SourceRegistry;
import com.springragforge.service.ChatService;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
public class ChatController {

    /** 匹配回答正文中的引用标记，如 [ref:1]、[ref:3]。用 [ref:] 前缀避免和代码里的数组下标 [0][1] 冲突。 */
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[ref:(\\d+)\\]");

    @Resource
    private ChatService chatService;

    @Resource
    private SourceRegistry sourceRegistry;

    @Resource
    private DegradationRegistry degradationRegistry;

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(String message, String memoryId) {
        // 累积模型输出的完整正文，流结束后从中提取被引用的来源编号，只返回模型实际用到的来源。
        StringBuilder answerBuffer = new StringBuilder();

        return chatService.chat(message, memoryId)
                // 2. 每个 token 一边转发给前端，一边追加到 answerBuffer
                .doOnNext(answerBuffer::append)
                .map(token -> ServerSentEvent.<String>builder()
                        .data(token).build())
                // 3. 若本次回答走了降级（模型不可用，返回的是检索原文），先推一条命名事件，
                //    前端据此显示降级横幅。正常回答时该注册表为空，直接跳过。
                .concatWith(Flux.defer(() -> {
                    if (memoryId == null) {
                        return Flux.<ServerSentEvent<String>>empty();
                    }
                    String reason = degradationRegistry.get(memoryId);
                    if (reason == null) {
                        return Flux.<ServerSentEvent<String>>empty();
                    }
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("degraded").data(reason).build());
                }))
                // 4. 正文流结束后，追加一条"参考来源"事件
                //    必须用 Flux.defer 惰性求值：只有前面的 token 流真正结束后才执行，
                //    此时 answerBuffer 已完整，sourceRegistry 里也已写入检索结果。
                .concatWith(Flux.defer(() -> {
                    List<SourceInfo> sources = sourceRegistry.get(memoryId);
                    if (sources.isEmpty()) {
                        return Flux.empty();
                    }
                    // 只保留模型在正文中实际引用过的来源，避免把没用到的召回片段也展示给用户
                    Set<Integer> cited = extractCitedIndexes(answerBuffer.toString());
                    List<SourceInfo> used = sources.stream()
                            .filter(s -> cited.contains(s.index()))
                            .toList();
                    if (used.isEmpty()) {
                        return Flux.empty();
                    }
                    String payload = formatSources(used);
                    // 用命名事件 "sources" 发送，前端可用 addEventListener("sources", ...) 单独接收
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("sources").data(payload).build());
                }))
                // 5. 最后追加结束标记 [DONE]，客户端据此关闭连接
                .concatWith(Flux.just(ServerSentEvent.<String>builder()
                        .data("[DONE]").build()))
                // 6. 无论流是正常完成、出错还是被客户端取消，都清理该 memoryId 对应的临时数据，防止内存泄漏
                .doFinally(signal -> {
                    sourceRegistry.remove(memoryId);
                    degradationRegistry.remove(memoryId);
                });
    }

    /** 从模型回答正文里提取所有 [ref:n] 引用编号。模型虚构的编号（如召回只有 5 条却写了 [ref:6]）会在 filter 阶段被自然忽略。 */
    private static Set<Integer> extractCitedIndexes(String answer) {
        Set<Integer> indexes = new HashSet<>();
        Matcher m = CITATION_PATTERN.matcher(answer);
        while (m.find()) {
            indexes.add(Integer.parseInt(m.group(1)));
        }
        return indexes;
    }

    private static String formatSources(List<SourceInfo> sources) {
        StringBuilder sb = new StringBuilder("参考来源：\n");
        for (SourceInfo s : sources) {
            sb.append('[').append(s.index()).append("] 出处：").append(s.origin());
            if (s.url() != null && !s.url().isBlank()) {
                sb.append(" | 链接：").append(s.url());
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

}
