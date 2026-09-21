package com.springragforge.controller;

import com.springragforge.service.ChatService;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ChatController {

    @Resource
    private ChatService chatService;

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(String message, String memoryId) {
        return chatService.chat(message, memoryId)
                .map(token -> ServerSentEvent.<String>builder().data(token).build())
                .concatWith(Flux.just(ServerSentEvent.<String>builder().data("[DONE]").build()));
    }


}
