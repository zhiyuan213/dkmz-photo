package com.dkmz.photo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                return token;
            }
            throw new RuntimeException("获取 access_token 失败: " + resp.body());
        } catch (Exception e) {
            throw new RuntimeException("获取 access_token 异常", e);
        }
    }

    /** 强制刷新（用于 40001 / 42001 重试） */
    public synchronized String refresh() {
        expireAt = 0L;
        return get();
    }
}
