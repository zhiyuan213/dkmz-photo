package com.dkmz.photo.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** 两个制作接口共享 MySQL 滑动窗口；重启不清零，多实例统一计数。 */
@Component
public class SegmentRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(SegmentRateLimiter.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    private final long globalMinute, globalDay, perUserMinute, perUserDay;

    public SegmentRateLimiter(JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${photo.segment.rate.enabled:true}") boolean enabled,
            @Value("${photo.segment.rate.global-minute:30}") long globalMinute,
            @Value("${photo.segment.rate.global-day:900}") long globalDay,
            @Value("${photo.segment.rate.per-user-minute:10}") long perUserMinute,
            @Value("${photo.segment.rate.per-user-day:30}") long perUserDay) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.enabled = enabled;
        this.globalMinute = globalMinute;
        this.globalDay = globalDay;
        this.perUserMinute = perUserMinute;
        this.perUserDay = perUserDay;
    }

    public Map<String, Object> rules() {
        return Map.of("enabled", enabled, "perMinute", perUserMinute, "per24Hours", perUserDay);
    }

    public String tryAcquire(String openid) {
        if (!enabled) return null;
        if (openid == null || openid.isBlank()) return "请重新登录后再制作";
        try {
            return transaction.execute(status -> {
                // 单行锁串行化所有额度检查；拒绝请求不占用其它窗口的名额。
                jdbc.queryForObject("SELECT id FROM photo_processing_guard WHERE id = 1 FOR UPDATE", Integer.class);
                Timestamp now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", Timestamp.class);
                Timestamp minute = new Timestamp(now.getTime() - 60_000);
                Timestamp day = new Timestamp(now.getTime() - 86_400_000);
                String userHash = hash(openid);
                jdbc.update("DELETE FROM photo_processing_request WHERE created_at <= ?", day);
                Map<String, Object> count = jdbc.queryForMap(
                    "SELECT COUNT(*) AS gd, SUM(CASE WHEN created_at > ? THEN 1 ELSE 0 END) AS gm, "
                    + "SUM(CASE WHEN user_hash = ? THEN 1 ELSE 0 END) AS ud, "
                    + "SUM(CASE WHEN user_hash = ? AND created_at > ? THEN 1 ELSE 0 END) AS um "
                    + "FROM photo_processing_request", minute, userHash, userHash, minute);
                if (full(count, "um", perUserMinute)) return "制作过于频繁，每分钟最多 " + perUserMinute + " 次";
                if (full(count, "ud", perUserDay)) return "最近 24 小时制作已达 " + perUserDay + " 次，请待较早记录满 24 小时后再试";
                if (full(count, "gm", globalMinute) || full(count, "gd", globalDay)) return "当前制作请求较多，请稍后再试";
                jdbc.update("INSERT INTO photo_processing_request(user_hash, created_at) VALUES (?, ?)", userHash, now);
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("制作限流存储不可用: {}", e.getClass().getSimpleName());
            return "制作服务暂时不可用，请稍后再试";
        }
    }

    private static boolean full(Map<String, Object> count, String key, long limit) {
        Object value = count.get(key);
        return limit > 0 && value instanceof Number && ((Number) value).longValue() >= limit;
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("请求摘要失败", e); }
    }
}
