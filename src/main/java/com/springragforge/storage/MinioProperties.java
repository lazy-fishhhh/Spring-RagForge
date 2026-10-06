package com.springragforge.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MinIO 连接配置，前缀 {@code minio}。
 *
 * <p>注意 {@code endpoint} 要是 S3 API 地址，不是浏览器控制台地址：
 * 标准部署下控制台在 9001（{@code /browser/...}），S3 API 默认在 9000。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {

    /** S3 API 端点，例如 http://192.168.16.128:9000。 */
    private String endpoint = "http://192.168.16.128:9000";

    /** 访问密钥。 */
    private String accessKey = "admin";

    /** 秘密密钥。 */
    private String secretKey = "12345678";

    /** 语料 bucket，对应控制台里的 rag-docs。 */
    private String bucket = "rag-docs";
}
