package com.dkmz.photo.service;

import com.dkmz.photo.support.SegmentRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/** 同一用户、同一上传图和美肤档位复用分割；只在内存短时缓存，不落照片到数据库。 */
@Service
public class PortraitProcessingService {
    public record Result(String png, String input, boolean beautyApplied, String beautySkippedReason) {}
    private record Entry(Result result, long expiresAt, long bytes) {}
    private final BdaSegmentService segment;
    private final CustomSegmentService masks;
    private final SkinBeautyService beauty;
    private final boolean beautyEnabled;
    private final IdPhotoBeautyService.Level defaultLevel;
    private final long ttlMillis;
    private final long maxBytes;
    private long cachedBytes;
    private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final ConcurrentHashMap<String, CompletableFuture<Result>> inFlight = new ConcurrentHashMap<>();

    public PortraitProcessingService(BdaSegmentService segment, CustomSegmentService masks, SkinBeautyService beauty,
            @Value("${photo.bda.beauty-enabled:false}") boolean beautyEnabled,
            @Value("${photo.bda.beauty-level:normal}") String level,
            @Value("${photo.segment.cache.ttl-seconds:600}") long ttlSeconds,
            @Value("${photo.segment.cache.max-bytes:33554432}") long maxBytes) {
        this.segment = segment; this.masks = masks; this.beauty = beauty;
        this.beautyEnabled = beautyEnabled; this.defaultLevel = IdPhotoBeautyService.Level.of(level);
        this.ttlMillis = Math.max(0, ttlSeconds) * 1000; this.maxBytes = Math.max(0, maxBytes);
    }

    public Result process(String user, String image, boolean wantBeauty, IdPhotoBeautyService.Level requestedLevel) {
        String pure = image.trim();
        int prefix = pure.indexOf("base64,");
        if (prefix >= 0) pure = pure.substring(prefix + 7);
        if (pure.length() > 5_500_000) throw new IllegalArgumentException("图片过大");
        IdPhotoBeautyService.Level level = requestedLevel == null ? defaultLevel : requestedLevel;
        String key = SegmentRateLimiter.hash(user + "|" + wantBeauty + "|" + beautyEnabled + "|" + level.key + "|" + pure);
        synchronized (cache) {
            clearExpired();
            Entry entry = cache.get(key);
            if (entry != null) return entry.result;
        }
        CompletableFuture<Result> future = new CompletableFuture<>();
        CompletableFuture<Result> existing = inFlight.putIfAbsent(key, future);
        if (existing != null) {
            try { return existing.join(); }
            catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException cause) throw cause;
                throw e;
            }
        }
        try {
            // 与上一请求完成/移除 inFlight 的竞态下，再检查一次缓存。
            synchronized (cache) {
                clearExpired();
                Entry entry = cache.get(key);
                if (entry != null) { future.complete(entry.result); return entry.result; }
            }
            String input = pure;
            boolean applied = false;
            String reason = wantBeauty && !beautyEnabled ? "美肤服务未启用" : null;
            if (wantBeauty && beautyEnabled) {
                try {
                    input = beauty.beautify(pure, masks.getFaceSkinMask(pure), level);
                    applied = true;
                } catch (RuntimeException e) { reason = "面部皮肤处理失败，本次仅替换背景"; }
            }
            Result result = new Result(segment.segment(input), input, applied, reason);
            // 瞬时美肤失败不缓存，允许后续重新尝试。
            if (!(wantBeauty && beautyEnabled && !applied)) put(key, result);
            future.complete(result);
            return result;
        } catch (RuntimeException e) { future.completeExceptionally(e); throw e; }
        finally { inFlight.remove(key, future); }
    }

    private void put(String key, Result result) {
        long bytes = 2L * (result.png.length() + result.input.length());
        if (ttlMillis <= 0 || bytes > maxBytes) return;
        synchronized (cache) {
            clearExpired();
            while (cachedBytes + bytes > maxBytes && !cache.isEmpty()) {
                String oldest = cache.keySet().iterator().next();
                cachedBytes -= cache.remove(oldest).bytes;
            }
            cache.put(key, new Entry(result, System.currentTimeMillis() + ttlMillis, bytes));
            cachedBytes += bytes;
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void clearExpired() {
        synchronized (cache) {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<String, Entry>> it = cache.entrySet().iterator();
            while (it.hasNext()) {
                Entry entry = it.next().getValue();
                if (entry.expiresAt <= now) { cachedBytes -= entry.bytes; it.remove(); }
            }
        }
    }
}
