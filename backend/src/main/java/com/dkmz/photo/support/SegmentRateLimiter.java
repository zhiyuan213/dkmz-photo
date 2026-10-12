package com.dkmz.photo.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 云端精修（/api/photo/segment）限流器 —— 防止接口被脚本刷爆 BDA 计费额度。
 *
 * 为什么必须有：这个接口的每次调用都直接烧真金白银（BDA 免费额度 1000 次/月/接口，
 * 超出后付费约 0.01 元/次；美颜态一次请求 = 2 次调用）。而服务挂在云托管公网域名上，
 * 不限流等于把钱包暴露在公网。端上正常用户做一张图只发 1 次请求，限额远高于真实用量。
 *
 * 实现：内存滑动窗口（ConcurrentLinkedDeque 存时间戳）。
 *   - 单实例部署足够精确；多实例时各实例独立计数（等于限额×实例数，仍远低于危险水位），
 *     为一个内部工具引入 Redis 不值得。
 *   - 四道闸，按「先全局后个人」顺序检查（全局满了说明服务已饱和，先挡住再说）：
 *       全局/分钟 → 全局/天 → 单用户/分钟 → 单用户/天
 *   - 阈值 ≤0 视为该档不限制。
 *   - 防内存膨胀：key 数超阈值时清一遍过期窗口；仍超限则整体清空（宁可漏拦不可拖垮服务）。
 *
 * 已知局限（可接受）：公网直连的攻击者可伪造 X-WX-OPENID 绕开「单用户」档，
 * 但「全局」两道闸与请求体大小限制仍然兜底 —— 攻击成本存在、损失有上界。
 * 彻底解法是关掉云托管控制台的「公网访问」开关（小程序 callContainer 走微信内网通道，
 * 不受影响），那是控制台操作，代码侧无法替代。
 */
@Component
public class SegmentRateLimiter {

    private static final long MINUTE_MS = 60_000L;
    private static final long DAY_MS = 24L * 60 * 60_000;
    /** windows 里的 key 数超过该值就触发一次过期清扫 */
    private static final int SWEEP_THRESHOLD = 4096;

    private final boolean enabled;
    private final long globalMinute;
    private final long globalDay;
    private final long perUserMinute;
    private final long perUserDay;

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    public SegmentRateLimiter(
            @Value("${photo.segment.rate.enabled:true}") boolean enabled,
            @Value("${photo.segment.rate.global-minute:30}") long globalMinute,
            @Value("${photo.segment.rate.global-day:900}") long globalDay,
            @Value("${photo.segment.rate.per-user-minute:6}") long perUserMinute,
            @Value("${photo.segment.rate.per-user-day:60}") long perUserDay) {
        this.enabled = enabled;
        this.globalMinute = globalMinute;
        this.globalDay = globalDay;
        this.perUserMinute = perUserMinute;
        this.perUserDay = perUserDay;
    }

    /**
     * 申请一次调用资格。
     *
     * @return null = 放行；非 null = 拒绝原因（可直接放进 msg 给前端 toast）
     */
    public String tryAcquire(String openid) {
        if (!enabled) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (windows.size() > SWEEP_THRESHOLD) {
            sweep(now);
        }

        String deny = acquire("g:min", globalMinute, MINUTE_MS, now);
        if (deny != null) {
            return deny;
        }
        deny = acquire("g:day", globalDay, DAY_MS, now);
        if (deny != null) {
            return deny;
        }
        String u = openid == null ? "anon" : openid;
        deny = acquire("u:min:" + u, perUserMinute, MINUTE_MS, now);
        if (deny != null) {
            return deny;
        }
        deny = acquire("u:day:" + u, perUserDay, DAY_MS, now);
        return deny;
    }

    /** 滑动窗口计数：弹出窗口外时间戳 → 满了拒绝 → 否则记一笔并放行 */
    private String acquire(String key, long limit, long windowMs, long now) {
        if (limit <= 0) {
            return null;
        }
        Deque<Long> dq = windows.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        synchronized (dq) {
            while (!dq.isEmpty() && now - dq.peekFirst() > windowMs) {
                dq.pollFirst();
            }
            if (dq.size() >= limit) {
                return windowMs == MINUTE_MS
                        ? "操作太频繁，请稍后再试"
                        : "今日制作次数已达上限，请明天再来";
            }
            dq.addLast(now);
            return null;
        }
    }

    /** 清掉最后一个时间戳已出 24h 窗口的 key；仍超限则整体清空（防内存被刷爆） */
    private void sweep(long now) {
        Iterator<Map.Entry<String, Deque<Long>>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Deque<Long>> e = it.next();
            Deque<Long> dq = e.getValue();
            Long last = dq.peekLast();
            if (last == null || now - last > DAY_MS) {
                it.remove();
            }
        }
        if (windows.size() > SWEEP_THRESHOLD) {
            windows.clear();
        }
    }
}
