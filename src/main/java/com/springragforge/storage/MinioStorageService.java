package com.springragforge.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * MinIO 对象操作的统一入口：bucket 自检、列对象、下载、上传。
 *
 * <p>灌库侧（MinioDocumentSource）用列举和下载，上传接口用上传，
 * 两处共用同一套异常处理和连接配置。
 */
@Slf4j
@Service
public class MinioStorageService {

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public MinioStorageService(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    /** 启动即确认 bucket 存在，不存在就建，避免第一次灌库/上传才报错。 */
    @PostConstruct
    public void init() {
        ensureBucket();
    }

    public void ensureBucket() {
        String bucket = properties.getBucket();
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO bucket 不存在，已创建: {}", bucket);
            }
        } catch (Exception e) {
            throw new IllegalStateException("初始化 MinIO bucket 失败: " + bucket, e);
        }
    }

    /**
     * 递归列出 bucket 下全部对象名（key），不含目录占位符。
     * MinIO 把对象分块返回，这里一次性聚合完。
     */
    public List<String> listObjectNames() {
        String bucket = properties.getBucket();
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder().bucket(bucket).recursive(true).build());
            List<String> names = new ArrayList<>();
            for (Result<Item> result : results) {
                Item item = result.get();
                if (!item.isDir()) {
                    names.add(item.objectName());
                }
            }
            return names;
        } catch (Exception e) {
            throw new IllegalStateException("列出 MinIO 对象失败: bucket=" + bucket, e);
        }
    }

    /** 下载对象全部字节。调用方负责保证对象不要大到撑爆内存（语料是 adoc 文本，体积可控）。 */
    public byte[] download(String objectName) {
        String bucket = properties.getBucket();
        try (InputStream in = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectName).build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("下载 MinIO 对象失败: " + objectName, e);
        }
    }

    /**
     * 删除对象。
     *
     * <p>MinIO 的 removeObject 对不存在的对象也返回成功，调用方没法分辨「删掉了」和
     * 「本来就没有」，所以先 stat 一次。删除接口要如实回 404，就得先探一下。
     *
     * @param objectName 对象 key
     * @return 删除前对象存在为 true，不存在为 false
     */
    public boolean remove(String objectName) {
        String bucket = properties.getBucket();
        try {
            minioClient.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(objectName).build());
        } catch (ErrorResponseException e) {
            String code = e.errorResponse().code();
            if (code != null && code.contains("NoSuchKey")) {
                return false;
            }
            throw new IllegalStateException("删除 MinIO 对象前检查失败: " + objectName, e);
        } catch (Exception e) {
            throw new IllegalStateException("删除 MinIO 对象前检查失败: " + objectName, e);
        }

        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder().bucket(bucket).object(objectName).build());
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("删除 MinIO 对象失败: " + objectName, e);
        }
    }

    /**
     * 上传对象。
     *
     * @param objectName  对象 key，可含目录前缀
     * @param in          内容流，由调用方负责关闭
     * @param size        内容字节数；未知传 -1
     * @param contentType MIME 类型，null 时按二进制处理
     * @return 对象 key
     */
    public String upload(String objectName, InputStream in, long size, String contentType) {
        String bucket = properties.getBucket();
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(in, size, -1)
                    .contentType(contentType != null ? contentType : "application/octet-stream")
                    .build());
            return objectName;
        } catch (Exception e) {
            throw new IllegalStateException("上传 MinIO 对象失败: " + objectName, e);
        }
    }
}
