package com.dkmz.photo.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class SegmentRateLimiterTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;

    @BeforeEach
    void setup() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        jdbc = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
        String schema = Files.readString(Path.of("src/main/resources/schema.sql"));
        var statements = Pattern.compile("CREATE TABLE IF NOT EXISTS photo_processing_(?:guard|request)[\\s\\S]*?;").matcher(schema);
        while (statements.find()) jdbc.execute(statements.group());
        jdbc.execute("INSERT INTO photo_processing_guard(id) VALUES (1)");
    }

    private SegmentRateLimiter limiter(long minute, long day) {
        return new SegmentRateLimiter(jdbc, manager, true, 1000, 1000, minute, day);
    }

    @Test
    void restartedInstancesShareMinuteLimit() {
        SegmentRateLimiter first = limiter(10, 30), restarted = limiter(10, 30);
        for (int i = 0; i < 10; i++) assertNull((i % 2 == 0 ? first : restarted).tryAcquire("user"));
        assertNotNull(restarted.tryAcquire("user"));
        assertEquals(10, jdbc.queryForObject("SELECT COUNT(*) FROM photo_processing_request", Integer.class));
        assertNull(first.tryAcquire("other-user"));
    }

    @Test
    void rollingDayLimitIncludesPriorMinutesButExpiresAfter24Hours() {
        Timestamp old = new Timestamp(System.currentTimeMillis() - 120_000);
        for (int i = 0; i < 30; i++) jdbc.update("INSERT INTO photo_processing_request(user_hash,created_at) VALUES (?,?)", SegmentRateLimiter.hash("user"), old);
        assertNotNull(limiter(10, 30).tryAcquire("user"));
        jdbc.update("UPDATE photo_processing_request SET created_at=?", new Timestamp(System.currentTimeMillis() - 86_400_001));
        assertNull(limiter(10, 30).tryAcquire("user"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM photo_processing_request", Integer.class));
    }

    @Test
    void concurrentInstancesCannotOverspendAndDeniedRequestsDoNotConsumeGlobalQuota() throws Exception {
        SegmentRateLimiter a = limiter(10, 30), b = limiter(10, 30);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                SegmentRateLimiter instance = i % 2 == 0 ? a : b;
                tasks.add(() -> instance.tryAcquire("user") == null);
            }
            int allowed = 0;
            for (Future<Boolean> result : pool.invokeAll(tasks)) if (result.get()) allowed++;
            assertEquals(10, allowed);
            assertEquals(10, jdbc.queryForObject("SELECT COUNT(*) FROM photo_processing_request", Integer.class));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void storageFailureBlocksPaidApiAccess() {
        jdbc.execute("DROP TABLE photo_processing_request");
        assertNotNull(limiter(10, 30).tryAcquire("user"));
    }
}
