package com.springragforge.service;

import com.springragforge.entity.User;
import com.springragforge.repository.UserRepository;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 每日免费额度。每个用户每天 daily_quota 次（user 表字段，默认 50）。
 *
 * <p>计数存 Redis：key = quota:{userId}:{yyyyMMdd}，INCR 原子自增，首次写入设过期到当日 23:59:59，
 * 次日 key 自然换新。额度上限从 user 表读；用户不存在（伪造 id）按 0 处理直接挡。
 * <p>Redis 不可用时 fail-open（放行 + 记日志），避免计数器故障把所有用户挡在门外。
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);
    private static final String COUNTER_PREFIX = "quota:";
    /** BASIC_ISO_DATE 格式 yyyyMMdd，紧凑且无分隔符，做 key 后缀正合适。 */
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private UserRepository userRepository;

    /**
     * 消耗一次额度。返回 true 放行，false 表示今日已超额。
     * 每条 /chat 请求开头调一次，无论后续模型成败都计入（防止靠中止白嫖额度）。
     */
    public boolean tryConsume(String userId) {
        int limit = resolveLimit(userId);
        if (limit <= 0) {
            return false;
        }
        String key = COUNTER_PREFIX + userId + ':' + LocalDate.now().format(BASIC_DATE);
        try {
            Long count = redis.opsForValue().increment(key);
            // 首次写入才设过期，避免每次请求都刷新 TTL 把当天计数拖到明天
            if (count != null && count == 1L) {
                redis.expire(key, Duration.ofSeconds(secondsUntilMidnight()));
            }
            return count != null && count <= limit;
        } catch (Exception e) {
            log.warn("额度计数 Redis 不可用，fail-open 放行：{}", userId, e);
            return true;
        }
    }

    /** 今日已用次数，给前端展示额度用。 */
    public long getUsedToday(String userId) {
        String key = COUNTER_PREFIX + userId + ':' + LocalDate.now().format(BASIC_DATE);
        try {
            String v = redis.opsForValue().get(key);
            return v == null ? 0 : Long.parseLong(v);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 取用户每日额度。用户不存在或 id 非法（伪造）返回 0，直接挡。 */
    private int resolveLimit(String userId) {
        try {
            return userRepository.findById(Long.parseLong(userId))
                    .map(User::getDailyQuota)
                    .orElse(0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 当前到次日 0 点的秒数，用作当天计数 key 的过期时间。 */
    private long secondsUntilMidnight() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay();
        return Duration.between(now, midnight).getSeconds();
    }
}
