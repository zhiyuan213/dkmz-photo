package com.dkmz.photo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 微信内容安全检测（security.imgSecCheck，UGC 图片合规）。
 *
 * 审核整改项（2026-10-15 驳回「存在平台未允许的服务内容」）：用户上传的照片属于 UGC，
 * 在进入云端处理前必须先过微信图片安全检测，风险图拦截并提示用户「内容有违规嫌疑」。
 *
 * 双通道（与 PhotoUserVerifier / PhotoPayService 的 jscode2session 写法一致）：
 *   ① wx.cloud-call=true（默认）：云托管内网通道 http://api.weixin.qq.com，
 *      网关自动注入 access_token，免鉴权、不走 TLS（access_token 参数留空即可）
 *   ② 降级：https://api.weixin.qq.com + TokenManager 的 access_token
 *      （需 WX_APPID / WX_SECRET，且服务器出口 IP 在 mp 后台 IP 白名单内）
 *
 * errcode 约定：0 通过；87014 内容违规；40001/42001 token 失效（刷新后重试一次）。
 * 通道不可用时抛异常，由调用方 fail-closed（宁可拒绝服务，也不放过风险图）。
 */
@Service
public class WxSecCheckService {

    private static final Logger log = LoggerFactory.getLogger(WxSecCheckService.class);

    private static final String PATH = "/wxa/img_sec_check";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper om = new ObjectMapper();
    private final TokenManager tokens;

    @Value("${wx.cloud-call:true}")
    private boolean cloudCall;

    /** 总开关。生产必须 true；本地开发无 WX 凭据时可置 false 跳过检测 */
    @Value("${photo.sec-check.enabled:true}")
    private boolean enabled;

    public WxSecCheckService(TokenManager tokens) {
        this.tokens = tokens;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 内容违规（errcode 87014）：调用方须拦截并提示用户，且不得回退本地处理 */
    public static class RiskyContentException extends Exception {
        public RiskyContentException() { super("图片内容涉嫌违规"); }
    }

    /**
     * 检测图片（JPEG/PNG，&lt;1M）。
     * 通过则正常返回；违规抛 {@link RiskyContentException}；通道不可用抛 IllegalStateException。
     */
    public void checkOrThrow(byte[] image) throws RiskyContentException {
        if (!enabled) {
            log.warn("imgSecCheck 已被开关关闭（SEC_CHECK_ENABLED=false）—— 仅限本地开发");
            return;
        }
        // ① 云托管内网免鉴权通道（网关自动注入 access_token，参数留空占位）
        if (cloudCall) {
            try {
                judge(post(image, "http://api.weixin.qq.com" + PATH + "?access_token="));
                return;
            } catch (RiskyContentException e) {
                throw e;
            } catch (Exception e) {
                log.warn("imgSecCheck 云托管内网通道失败，降级 https：{}", e.toString());
            }
        }
        // ② https + access_token（40001/42001 时刷新重试一次）
        String token = tokens.get();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                judge(post(image, "https://api.weixin.qq.com" + PATH + "?access_token=" + token));
                return;
            } catch (RiskyContentException e) {
                throw e;
            } catch (StaleTokenException e) {
                if (attempt == 0) {
                    token = tokens.refresh();
                    continue;
                }
                throw new IllegalStateException("内容安全检测凭据失效", e);
            } catch (Exception e) {
                throw new IllegalStateException("内容安全检测暂不可用", e);
            }
        }
        throw new IllegalStateException("内容安全检测暂不可用");
    }

    /** 发送图片二进制（Content-Type: application/octet-stream），返回响应文本 */
    private String post(byte[] image, String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(image))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("imgSecCheck HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    /** 解析微信响应：0 通过；87014 违规；40001/42001 token 失效；其余报错 */
    private void judge(String body) throws RiskyContentException, StaleTokenException {
        try {
            JsonNode node = om.readTree(body == null ? "{}" : body);
            int errcode = node.path("errcode").asInt(0);
            if (errcode == 0) {
                return;
            }
            if (errcode == 87014) {
                throw new RiskyContentException();
            }
            if (errcode == 40001 || errcode == 42001) {
                throw new StaleTokenException("access_token 失效: " + body);
            }
            throw new IllegalStateException("imgSecCheck errcode=" + errcode + " body=" + body);
        } catch (RiskyContentException | StaleTokenException e) {
            throw e;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("imgSecCheck 响应解析失败: " + body, e);
        }
    }

    /** access_token 失效，需要刷新后重试 */
    private static class StaleTokenException extends Exception {
        StaleTokenException(String msg) { super(msg); }
    }
}
