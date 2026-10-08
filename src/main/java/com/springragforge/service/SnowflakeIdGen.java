package com.springragforge.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 雪花 ID 生成器。64 位：1 符号位 + 41 时间戳 + 5 datacenterId + 5 workerId + 12 序列。
 * 应用层分配，不依赖 DB 自增。同一毫秒内靠 12 位序列号区分，理论单机 409.6 万/秒。
 *
 * <p>时钟回拨直接抛异常拒绝生成，避免产出重复 id（够用且最安全）。
 */
@Component
public class SnowflakeIdGen {

    /** 起始纪元：2024-01-01 00:00:00 UTC（毫秒），给时间戳留 41 位正偏移。 */
    private static final long EPOCH = 1704067200000L;

    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);           // 31
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);   // 31
    private static final long SEQUENCE_BITS = 12L;

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);             // 4095

    private final long workerId;
    private final long datacenterId;

    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdGen(
            @Value("${rag.snowflake.worker-id:1}") long workerId,
            @Value("${rag.snowflake.datacenter-id:1}") long datacenterId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId 超范围 [0," + MAX_WORKER_ID + "]");
        }
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException("datacenterId 超范围 [0," + MAX_DATACENTER_ID + "]");
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    /**
     * 生成下一个 64 位 id。synchronized 保证单机内序列号不并发冲突。
     */
    public synchronized long nextId() {
        long now = System.currentTimeMillis();
        if (now < lastTimestamp) {
            throw new IllegalStateException("时钟回拨，拒绝生成 id：" + (lastTimestamp - now) + "ms");
        }
        if (now == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // 本毫秒 4096 个序列号用尽，等到下一毫秒
                now = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = now;
        return ((now - EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long tilNextMillis(long lastTimestamp) {
        long now = System.currentTimeMillis();
        while (now <= lastTimestamp) {
            now = System.currentTimeMillis();
        }
        return now;
    }
}
