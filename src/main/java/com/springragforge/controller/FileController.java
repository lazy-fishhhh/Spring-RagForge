package com.springragforge.controller;

import com.springragforge.storage.MinioProperties;
import com.springragforge.storage.MinioStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * 文档上传接口。文件直接落到 MinIO 的 rag-docs bucket，
 * 对象 key 就是灌库时用的文档路径，例如 {@code spring-boot/xxx.adoc}。
 *
 * <p>上传不触发灌库；想让新文档进知识库，再调一次 {@code POST /admin/reindex}。
 */
@Slf4j
@RestController
@RequestMapping("/files")
public class FileController {

    private final MinioStorageService storageService;
    private final MinioProperties minioProperties;

    public FileController(MinioStorageService storageService, MinioProperties minioProperties) {
        this.storageService = storageService;
        this.minioProperties = minioProperties;
    }

    /**
     * 上传单个文件。
     *
     * @param file      上传的文件
     * @param directory bucket 内目标目录（对象 key 前缀），可空；例如 spring-boot/reference
     */
    @PostMapping("/upload")
    public ResponseEntity<Object> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "directory", required = false) String directory) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "上传文件不能为空"));
        }
        String objectName;
        try {
            objectName = buildObjectName(file.getOriginalFilename(), directory);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }

        try (InputStream in = file.getInputStream()) {
            storageService.upload(objectName, in, file.getSize(), file.getContentType());
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "读取上传文件失败: " + e.getMessage()));
        }

        log.info("文件已上传: bucket={} object={} size={}",
                minioProperties.getBucket(), objectName, file.getSize());
        return ResponseEntity.ok(Map.of(
                "bucket", minioProperties.getBucket(),
                "objectName", objectName,
                "size", file.getSize(),
                "contentType", String.valueOf(file.getContentType())));
    }

    /**
     * 删除单个文档对象。
     *
     * <p>只动 bucket 里的对象，不碰向量库：删掉的文档会在下一次 {@code POST /admin/reindex}
     * 的「语料已删除」环节按清单摘掉向量。这样接口没有副作用，也不会因为 ES 抖一下而留下半截状态。
     *
     * @param objectName bucket 内对象 key，例如 spring-boot/reference/pages/web/servlet.adoc
     */
    @DeleteMapping
    public ResponseEntity<Object> delete(@RequestParam("objectName") String objectName) {
        String key;
        try {
            key = normalizeObjectName(objectName);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }

        boolean removed = storageService.remove(key);
        if (!removed) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "对象不存在: " + key));
        }
        log.info("文件已删除: bucket={} object={}", minioProperties.getBucket(), key);
        return ResponseEntity.ok(Map.of(
                "bucket", minioProperties.getBucket(),
                "objectName", key,
                "deleted", true));
    }

    /**
     * 校验并规范化完整对象 key。key 直接相对 bucket 根，不做任何前缀拼接，
     * 但仍要挡住越权写出的路径（{@code ..}）和前导斜杠。
     */
    private static String normalizeObjectName(String objectName) {
        String key = objectName == null ? "" : objectName.trim().replace('\\', '/');
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        if (key.isBlank()) {
            throw new IllegalArgumentException("objectName 不能为空");
        }
        for (String segment : key.split("/")) {
            if (segment.equals("..")) {
                throw new IllegalArgumentException("objectName 不能包含 ..");
            }
        }
        return key;
    }

    /**
     * 拼对象 key：目录前缀 + 文件名。
     * 只取文件名部分，去掉前端可能带入的路径；目录里的 {@code ..} 一律拒绝，
     * 防止写出 bucket 预期之外的 key。
     */
    private static String buildObjectName(String originalFilename, String directory) {
        String fileName = originalFilename == null ? "" : originalFilename.replace('\\', '/');
        int slash = fileName.lastIndexOf('/');
        if (slash >= 0) {
            fileName = fileName.substring(slash + 1);
        }
        if (fileName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        String dir = directory == null ? "" : directory.trim().replace('\\', '/');
        while (dir.startsWith("/")) {
            dir = dir.substring(1);
        }
        while (dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        if (!dir.isEmpty()) {
            for (String segment : dir.split("/")) {
                if (segment.equals("..")) {
                    throw new IllegalArgumentException("目录不能包含 ..");
                }
            }
            return dir + "/" + fileName;
        }
        return fileName;
    }
}
