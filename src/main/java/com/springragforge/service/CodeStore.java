package com.springragforge.service;

import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 验证码存储。Redis 为主，挂了自动降级到本地 ConcurrentHashMap 内存表。
 * <p>
 * 防刷与存储合在一个原子操作里：用 Redis SETNX（setIfAbsent）一把完成
 * 「该邮箱是否刚发过」的判定和写入，避免 check-then-set 的并发缝隙。
 *
 * <p>降级表不带 TTL，靠存进去的 expireAt 惰性清理；服务重启会丢，但至少
 * Redis 挂掉时不直接 500。
 */
@Component
public class CodeStore {

    private static final Logger log = LoggerFactory.getLogger(CodeStore.class);
    private static final String KEY_PREFIX = "email:code:";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 降级内存表：email → 验证码 + 过期时间戳 */
    private final ConcurrentHashMap<String, Entry> fallback = new ConcurrentHashMap<>();

    private record Entry(String code, long expireAt) {
    }

    /**
     * 邮箱是否刚发过验证码（且未过期）。
     */
    public boolean hasCode(String email) {
        try {
            Boolean has = stringRedisTemplate.hasKey(KEY_PREFIX + email);
            return Boolean.TRUE.equals(has);
        } catch (Exception e) {
            log.warn("Redis 不可用，查本地降级表", e);
            Entry entry = fallback.get(email);
            return entry != null && entry.expireAt > System.currentTimeMillis();
        }
    }

    /**
     * 原子写入：若该邮箱无未过期验证码则写入并返回 true；已存在返回 false。
     * 借 Redis SETNX 的原子性做防刷，一次操作完成判定+存储。
     */
    public boolean saveCodeIfAbsent(String email, String code, Duration ttl) {
        try {
            Boolean set = stringRedisTemplate.opsForValue()
                    .setIfAbsent(KEY_PREFIX + email, code, ttl);
            return Boolean.TRUE.equals(set);
        } catch (Exception e) {
            log.warn("Redis 不可用，降级到本地内存存验证码", e);
            long expireAt = System.currentTimeMillis() + ttl.toMillis();
            Entry prev = fallback.putIfAbsent(email, new Entry(code, expireAt));
            if (prev == null) {
                return true;
            }
            if (prev.expireAt > System.currentTimeMillis()) {
                return false; // 未过期 → 拒绝
            }
            fallback.put(email, new Entry(code, expireAt));
            return true;
        }
    }

    /**
     * 取验证码。过期或不存在返回 null。
     */
    public String getCode(String email) {
        try {
            return stringRedisTemplate.opsForValue().get(KEY_PREFIX + email);
        } catch (Exception e) {
            log.warn("Redis 不可用，查本地降级表", e);
            Entry entry = fallback.get(email);
            if (entry == null || entry.expireAt <= System.currentTimeMillis()) {
                fallback.remove(email);
                return null;
            }
            return entry.code;
        }
    }

    /**
     * 删除验证码。比对成功后立即调用，防同一个验证码被重放使用。
     */
    public void deleteCode(String email) {
        try {
            stringRedisTemplate.delete(KEY_PREFIX + email);
        } catch (Exception e) {
            log.warn("Redis 不可用，清本地降级表", e);
            fallback.remove(email);
        }
    }
}
