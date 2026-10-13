package com.dkmz.photo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        try {
            String body;
            if (cloudCall) {
                try { body = get("http://api.weixin.qq.com/sns/jscode2session?" + query); }
                catch (Exception e) { body = get("https://api.weixin.qq.com/sns/jscode2session?" + query); }
            } else { body = get("https://api.weixin.qq.com/sns/jscode2session?" + query); }
            JsonNode response = mapper.readTree(body);
            String user = response.path("openid").asText("");
            if (user.isBlank() || response.path("session_key").asText("").isBlank()
                    || response.path("errcode").asInt(0) != 0) throw new IllegalArgumentException("登录凭证无效，请重试");
            if (claimedOpenid != null && !claimedOpenid.equals(user)) throw new IllegalArgumentException("登录身份不一致，请重新进入小程序");
            return user;
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("登录验证暂时不可用，请稍后再试"); }
    }

    String get(String url) throws Exception {
        HttpResponse<String> response = HttpHolder.CLIENT.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("登录验证请求失败");
        return response.body();
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
