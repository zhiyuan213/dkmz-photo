package com.dkmz.photo.service;

import com.dkmz.photo.support.Logs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 制作接口用一次性 wx.login code 校验身份，不能凭伪造 openid 绕过个人限额。 */
@Service
public class PhotoUserVerifier {

    private static final Logger log = LoggerFactory.getLogger(PhotoUserVerifier.class);

    /**
     * 日志里用的接口地址：**只到 path，不带 query**。
     * query 里有 secret 与 js_code，绝不能落日志（见 MEMORY 的凭据安全红线）。
     */
    private static final String API_LOGIN = "https://api.weixin.qq.com/sns/jscode2session";

    private final String appid, secret;
    private final boolean cloudCall;
    private static class HttpHolder {
        private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
    private final ObjectMapper mapper = new ObjectMapper();

    public PhotoUserVerifier(@Value("${wx.appid:}") String appid, @Value("${wx.secret:}") String secret,
            @Value("${wx.cloud-call:true}") boolean cloudCall) {
        this.appid = appid; this.secret = secret; this.cloudCall = cloudCall;
    }

    public String verify(String code, String claimedOpenid) {
        if (code == null || code.isBlank() || code.length() > 256) throw new IllegalArgumentException("请重新登录后再制作");
        String query = "appid=" + encode(appid) + "&secret=" + encode(secret) + "&js_code=" + encode(code)
                + "&grant_type=authorization_code";
        long t0 = System.currentTimeMillis();
        try {
            String body;
            String channel;
            if (cloudCall) {
                try {
                    body = get("http://api.weixin.qq.com/sns/jscode2session?" + query);
                    channel = "云托管内网 http";
                } catch (Exception e) {
                    log.warn("外部接口 | GET {} | 用途=登录校验 | 云调用内网通道失败，降级 https 直连：{}",
                            API_LOGIN, Logs.safe(e));
                    body = get(API_LOGIN + "?" + query);
                    channel = "https 直连";
                }
            } else {
                body = get(API_LOGIN + "?" + query);
                channel = "https 直连";
            }
            JsonNode response = mapper.readTree(body);
            String user = response.path("openid").asText("");
            int errcode = response.path("errcode").asInt(0);
            long cost = System.currentTimeMillis() - t0;
            if (user.isBlank() || response.path("session_key").asText("").isBlank() || errcode != 0) {
                log.warn("外部接口 | GET {} | 用途=登录校验 | 通道={} | 结果=失败 errcode={} | 耗时={}ms",
                        API_LOGIN, channel, errcode, cost);
                throw new IllegalArgumentException("登录凭证无效，请重试");
            }
            if (claimedOpenid != null && !claimedOpenid.equals(user)) {
                log.warn("外部接口 | GET {} | 用途=登录校验 | 通道={} | user={} | 结果=身份不一致（请求头 openid 与 code 不符） | 耗时={}ms",
                        API_LOGIN, channel, userTag(user), cost);
                throw new IllegalArgumentException("登录身份不一致，请重新进入小程序");
            }
            log.info("外部接口 | GET {} | 用途=登录校验 | 通道={} | user={} | 结果=ok | 耗时={}ms",
                    API_LOGIN, channel, userTag(user), cost);
            return user;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            // 异常文本过脱敏：连接类异常有可能把带 secret/js_code 的完整 URL 带出来
            log.warn("外部接口 | GET {} | 用途=登录校验 | 结果=调用异常 | 耗时={}ms | {}",
                    API_LOGIN, System.currentTimeMillis() - t0, Logs.safe(e));
            throw new IllegalStateException("登录验证暂时不可用，请稍后再试");
        }
    }

    String get(String url) throws Exception {
        HttpResponse<String> response = HttpHolder.CLIENT.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("登录验证请求失败");
        return response.body();
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    /** 日志用的用户标识：只取 openid 前 8 位，绝不整串落日志 */
    private static String userTag(String openid) {
        return openid == null ? "anonymous" : openid.substring(0, Math.min(8, openid.length())) + "…";
    }
}
