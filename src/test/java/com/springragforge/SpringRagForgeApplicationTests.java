package com.springragforge;

import com.springragforge.rag.ingest.IngestProperties;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SpringRagForgeApplicationTests {
    @Value("${rag.rerank.api-key}")
    private String apiKey;

    @Test
    void contextLoads() {
    }

    @Test
    void ingestPropertiesAreBound() {
        System.out.println(apiKey);
    }

}
