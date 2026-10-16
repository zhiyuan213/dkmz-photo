package com.dkmz.photo.service;

import com.dkmz.photo.support.SegmentRateLimiter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.Base64;

/**
 * 微信完整原图检测通过后的短期凭证。数据库只保存随机凭证的摘要和用户摘要，
 * 不保存照片；UPDATE 原子消费使多个云托管实例也只能使用一次。
 */
@Service
public class PhotoCheckTicketService {
    private static final long TTL_MS = 2 * 60_000L;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final SecureRandom random = new SecureRandom();

    public PhotoCheckTicketService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
    }

    /** 预检本身也会调用微信接口；同一用户每分钟最多十次。 */
    public boolean allowPrecheck(String user) {
        if (user == null || user.isBlank()) return false;
        return Boolean.TRUE.equals(transaction.execute(status -> {
            jdbc.queryForObject("SELECT id FROM photo_processing_guard WHERE id = 1 FOR UPDATE", Integer.class);
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM photo_check_request WHERE user_hash = ? AND created_at > ?",
                    Long.class, SegmentRateLimiter.hash(user), new Timestamp(System.currentTimeMillis() - 60_000));
            if (count != null && count >= 10) return false;
            jdbc.update("INSERT INTO photo_check_request(user_hash, created_at) VALUES (?, CURRENT_TIMESTAMP(3))",
                    SegmentRateLimiter.hash(user));
            return true;
        }));
    }

    public String issue(String user) {
        if (user == null || user.isBlank()) throw new IllegalStateException("无效用户");
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("INSERT INTO photo_check_ticket(token_hash, user_hash, expires_at) VALUES (?, ?, ?)",
                SegmentRateLimiter.hash(token), SegmentRateLimiter.hash(user),
                new Timestamp(System.currentTimeMillis() + TTL_MS));
        return token;
    }

    /** 用户、时效和未消费状态同时匹配才成功；单条 UPDATE 保证原子性。 */
    public boolean consume(String token, String user) {
        if (token == null || token.isBlank() || user == null || user.isBlank()) return false;
        return jdbc.update("UPDATE photo_check_ticket SET consumed_at = CURRENT_TIMESTAMP(3) "
                        + "WHERE token_hash = ? AND user_hash = ? AND consumed_at IS NULL AND expires_at > CURRENT_TIMESTAMP(3)",
                SegmentRateLimiter.hash(token), SegmentRateLimiter.hash(user)) == 1;
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanup() {
        jdbc.update("DELETE FROM photo_check_ticket WHERE expires_at <= CURRENT_TIMESTAMP(3)");
        jdbc.update("DELETE FROM photo_check_request WHERE created_at <= ?",
                new Timestamp(System.currentTimeMillis() - 60_000));
    }
}
