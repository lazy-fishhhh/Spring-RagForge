package com.springragforge.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

public interface ChatService {
    Flux<String> chat(@UserMessage String message, @MemoryId String memoryId);
}
