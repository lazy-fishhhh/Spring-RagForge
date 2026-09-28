package com.springragforge;

import com.springragforge.rag.ingest.IngestProperties;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SpringRagForgeApplicationTests {

    @Resource
    private IngestProperties ingestProperties;

    @Test
    void contextLoads() {
    }

    /**
     * 灌库配置真的绑上了才算数：Path 类型不是每种配置绑定都能转，绑不上就是空库 + 清单对不上。
     */
    @Test
    void ingestPropertiesAreBound() {
        assertThat(ingestProperties.getCorpusRoot()).isEqualTo("E:/springDoc/corpus");
        assertThat(ingestProperties.getStoreFile()).isEqualTo(Path.of("data/embedding-store.json"));
        assertThat(ingestProperties.getManifestFile()).isEqualTo(Path.of("data/ingest-manifest.json"));
    }

}
