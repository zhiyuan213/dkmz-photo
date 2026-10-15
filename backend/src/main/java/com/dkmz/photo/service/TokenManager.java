package com.dkmz.photo.service;

import com.dkmz.photo.support.Logs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * access_token 管理器（带内存缓存）。
 * 仅在「非云调用」通道下使用：本地开发、或云调用降级时。
 */
@Component
public class TokenManager {

    private static final Logger log = LoggerFactory.getLogger(TokenManager.class);

    /** 日志里用的接口地址：**不带 query**（query 里有 appid + secret，绝不落日志） */
    private static final String API_TOKEN = "https://api.weixin.qq.com/cgi-bin/token";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper om = new ObjectMapper();

    @Value("${wx.appid:}")
    private String appid;

    @Value("${wx.secret:}")
    private String secret;

    private volatile String token;
    private volatile long expireAt = 0L;

    public synchronized String get() {
        if (System.currentTimeMillis() < expireAt - 60_000L) {
            return token;
        }
        long t0 = System.currentTimeMillis();
        try {
            String url = String.format(
                    "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=%s&secret=%s",
                    appid, secret);
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode node = om.readTree(resp.body());
            if (node.has("access_token")) {
                token = node.get("access_token").asText();
                expireAt = System.currentTimeMillis() + node.get("expires_in").asLong() * 1000L;
                // 只记有效期，绝不记 token 本身
                log.info("外部接口 | GET {} | 用途=access_token | 结果=ok expiresIn={}s | 耗时={}ms",
                        API_TOKEN, node.get("expires_in").asLong(), System.currentTimeMillis() - t0);
                return token;
            }
            // 失败原因（errcode/errmsg）随异常一起打，避免同一次调用出现两条日志
            throw new RuntimeException("获取 access_token 失败: " + resp.body());
        } catch (Exception e) {
            // 脱敏：这个接口的 query 里有 secret，连接类异常可能把完整 URL 带出来
            log.warn("外部接口 | GET {} | 用途=access_token | 结果=异常 | 耗时={}ms | {}",
                    API_TOKEN, System.currentTimeMillis() - t0, Logs.safe(e));
            throw new RuntimeException("获取 access_token 异常", e);
        }
    }

    /** 强制刷新（用于 40001 / 42001 重试） */
    public synchronized String refresh() {
        expireAt = 0L;
        return get();
    }
}
