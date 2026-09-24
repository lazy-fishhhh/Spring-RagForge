package com.springragforge.controller;

import com.springragforge.rag.SourceInfo;
import com.springragforge.rag.SourceRegistry;
import com.springragforge.service.ChatService;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
public class ChatController {

    @Resource
    private ChatService chatService;

    @Resource
    private SourceRegistry sourceRegistry;

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(String message, String memoryId) {
        return chatService.chat(message, memoryId)
                // 2. 把每个 token 包装成一个默认的 SSE 事件（event 名默认为 message）
                .map(token -> ServerSentEvent.<String>builder()
                        .data(token).build())
                // 3. 正文流结束后，追加一条"参考来源"事件
                //    必须用 Flux.defer 惰性求值：只有前面的 token 流真正结束后才执行，
                //    否则此时 sourceRegistry 里可能还没写入检索结果，会拿到空列表。
                .concatWith(Flux.defer(() -> {
                    // 从注册表取出本次对话检索到的来源（key 为 memoryId）
                    List<SourceInfo> sources = sourceRegistry.get(memoryId);
                    if (sources.isEmpty()) {
                        return Flux.empty();
                    }
                    String payload = formatSources(sources);
                    // 用命名事件 "sources" 发送，前端可用 addEventListener("sources", ...) 单独接收
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("sources").data(payload).build());
                }))
                // 4. 最后追加结束标记 [DONE]，客户端据此关闭连接
                .concatWith(Flux.just(ServerSentEvent.<String>builder()
                        .data("[DONE]").build()))
                // 5. 无论流是正常完成、出错还是被客户端取消，都清理该 memoryId 对应的来源数据，防止内存泄漏
                .doFinally(signal -> sourceRegistry.remove(memoryId));
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
