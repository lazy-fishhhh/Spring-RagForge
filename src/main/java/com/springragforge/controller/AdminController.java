package com.springragforge.controller;

import com.springragforge.rag.ingest.IncrementalIndexer;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 运维接口。灌库是手动触发的：启动不扫盘、不打 embedding 接口，
 * 想让改动生效就自己打一次 {@code POST /admin/reindex}。
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    @Resource
    private IncrementalIndexer incrementalIndexer;

    /**
     * 触发一次增量灌库。同步执行，耗时取决于有多少文档真的变了。
     *
     * <p>返回 200 加统计 = 跑完了；返回 409 = 没跑（已有任务在跑，或语料根目录不存在），
     * 后者不会动任何已有向量。
     */
    @PostMapping("/reindex")
    public ResponseEntity<Object> reindex() {
        try {
            return ResponseEntity.ok(incrementalIndexer.reindex());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }
}
