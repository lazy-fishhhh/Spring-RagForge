package com.springragforge.rag.ingest;

import java.util.List;

/**
 * 一次灌库的结果，直接作为 HTTP 响应体返回，方便手动触发后立刻看清发生了什么。
 *
 * @param fullRebuild    是否走了全量重建（首次灌库、清单丢失、切分器或模型变更都算）
 * @param added          新增的文档数
 * @param updated        内容有变、重新灌的文档数
 * @param deleted        源文件已消失、向量已删的文档数
 * @param unchanged      内容没变、直接跳过的文档数（跳过 = 一次 embedding 都不调）
 * @param failed         本次灌库失败的文档数，失败文档保持上一次的状态，下次重试
 * @param embeddedChunks 实际送去 embedding 的片段数（幂等检查就看它是不是 0）
 * @param elapsedMillis  耗时
 * @param failures       失败原因，最多记前若干条，避免一个坏目录刷屏
 */
public record IngestStats(
        boolean fullRebuild,
        int added,
        int updated,
        int deleted,
        int unchanged,
        int failed,
        int embeddedChunks,
        long elapsedMillis,
        List<String> failures
) {
}
