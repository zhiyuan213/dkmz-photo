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
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信内容安全检测（security.mediaCheckAsync，UGC 图片合规）。
 *
 * 审核整改项（2026-10-15 驳回「存在平台未允许的服务内容」）：用户上传的照片属于 UGC，
 * 在进入云端处理前必须过微信图片安全检测，风险图拦截并提示用户「内容有违规嫌疑」。
 *
 * ⚠️ 接口选型（2026-10-15 核实）：
 *   旧的同步接口 security.imgSecCheck（/wxa/img_sec_check）**已于 2021-09-01 停止维护，
 *   当前不可用**（固定报 errcode 47001），不要再改回去。现在唯一可用的是**异步**接口
 *   security.mediaCheckAsync（/wxa/media_check_async），因此本服务是「提交 → 等推送」模式：
 *     ① 提交时把待检图暂存内存（随机 token，{@link #MEDIA_TTL_MS} 过期），
 *        暴露公网只读地址 /api/photo/sec-check/media/{token} 供微信服务器回拉
 *        （media_url 必须是公网可访问地址，故需 XPHOTO_PUBLIC_BASE_URL）；
 *     ② 微信异步检测完，把结果推送到 mp 后台配置的「消息推送」地址
 *        （事件 wxa_media_check）→ 由 @link PhotoPayController#notify} 转交
 *        {@link #acceptPush} 落到 pending 上；
 *     ③ 本服务在原地轮询等待结果，即可对上层保持「同步」语义。
 *
 * 通道（与 PhotoUserVerifier / PhotoPayService 的 jscode2session 写法一致）：
 *   ① 云托管「开放接口服务」云调用（默认，wx.cloud-call=true）：
 *      容器内向 http://api.weixin.qq.com 发起请求，旁加载自动注入 access_token。
 *      ⚠️ 前置（缺一不可，均在云托管控制台，见 application.yml 注释）：
 *        a.「服务管理-云调用-开放接口服务」开关打开
 *        b.「微信令牌权限配置」登记接口 /wxa/media_check_async
 *        c. 开启后重新构建部署（对已有版本不生效）
 *   ② 兜底：https://api.weixin.qq.com + TokenManager 的 access_token
 *      （需 WX_APPID/WX_SECRET + 服务器出口 IP 在 mp 后台 IP 白名单内）。
 *
 * 结果语义：suggest = pass 放行 / risky 拦截 / review 疑似违规（本项目按拦截处理）。
 * 通道异常或超时未回结果 → 抛 IllegalStateException，由调用方 fail-closed。
 */
@Service
public class WxSecCheckService {

    private static final Logger log = LoggerFactory.getLogger(WxSecCheckService.class);

    /** 异步提交接口（唯一可用版本） */
    private static final String PATH_ASYNC = "/wxa/media_check_async";
    private static final String HOST_CLOUD = "http://api.weixin.qq.com";
    private static final String HOST_HTTPS = "https://api.weixin.qq.com";

    /** 待检图在内存里的存活时间（微信通常几秒内来拉） */
    static final long MEDIA_TTL_MS = 10 * 60_000L;
    /** 检测结果的存活时间（微信承诺 30 分钟内推送） */
    static final long RESULT_TTL_MS = 30 * 60_000L;
    /** 轮询间隔 */
    private static final long POLL_MS = 150L;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper om = new ObjectMapper();
    private final TokenManager tokens;
    private final SecureRandom random = new SecureRandom();

    /** token → 待检图（供微信回拉，不落盘、不持久化） */
    private final Map<String, Media> medias = new ConcurrentHashMap<>();
    /** trace_id → 检测结果占位（由推送填值） */
    private final Map<String, Pending> pendings = new ConcurrentHashMap<>();

    @Value("${wx.cloud-call:true}")
    private boolean cloudCall;

    /** 总开关。生产必须 true；本地开发无 WX 凭据时可置 false 跳过检测 */
    @Value("${photo.sec-check.enabled:true}")
    private boolean enabled;

    /** 等结果的最长时间（毫秒）；超时按通道不可用处理（fail-closed） */
    @Value("${photo.sec-check.wait-ms:10000}")
    private long waitMs;

    /** 本服务的公网基址（微信服务器要来拉待检图），如 https://xxx.sh.run.tcloudbase.com */
    @Value("${photo.sec-check.public-base-url:}")
    private String publicBaseUrl;

    public WxSecCheckService(TokenManager tokens) {
        this.tokens = tokens;
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ------------------------------------------------------------ 对外类型

    /** 内容违规：调用方须拦截并提示用户，且不得回退本地处理 */
    public static class RiskyContentException extends Exception {
        private final String suggest;

        public RiskyContentException(String suggest) {
            super("图片内容涉嫌违规");
            this.suggest = suggest;
        }

        public String getSuggest() {
            return suggest;
        }
    }

    /** 一次提交的结果 */
    static class Submit {
        final String traceId;
        final String mediaUrl;

        Submit(String traceId, String mediaUrl) {
            this.traceId = traceId;
            this.mediaUrl = mediaUrl;
        }
    }

    /** 待检图（内存暂存，过期即不可取） */
    public static class Media {
        public final byte[] bytes;
        public final String contentType;
        final long expireAt;

        Media(byte[] bytes, String contentType, long expireAt) {
            this.bytes = bytes;
            this.contentType = contentType;
            this.expireAt = expireAt;
        }
    }

    /** 检测结果占位（推送到达前 suggest 为 null） */
    static class Pending {
        volatile String suggest;
        volatile String label = "";
        volatile int errcode = -1;
        volatile String errmsg = "";
        volatile String raw = "";
        volatile boolean failed;   // 微信侧检测失败（errcode != 0），不是"还没回来"
        final long expireAt;

        Pending(long expireAt) {
            this.expireAt = expireAt;
        }

        boolean resolved() {
            return failed || (suggest != null && !suggest.isEmpty());
        }
    }

    /** 一次检测的判定结果 */
    static class Verdict {
        final String suggest;   // pass / risky / review
        final boolean failed;   // 微信侧检测失败
        final int errcode;
        final String detail;

        Verdict(String suggest, boolean failed, int errcode, String detail) {
            this.suggest = suggest;
            this.failed = failed;
            this.errcode = errcode;
            this.detail = detail;
        }
    }

    // ------------------------------------------------------------ 主流程

    /**
     * 检测图片：提交异步检测并等微信推送结果。
     * pass → 正常返回；risky/review → {@link RiskyContentException}；超时/通道异常 → IllegalStateException。
     */
    public void checkOrThrow(byte[] image, String openid) throws RiskyContentException {
        if (!enabled) {
            log.warn("内容安全检测已被开关关闭（SEC_CHECK_ENABLED=false）—— 仅限本地开发");
            return;
        }
        Submit submit = submit(image, openid);
        Verdict v = awaitVerdict(submit.traceId, waitMs);
        if (v == null) {
            throw new IllegalStateException(
                    "内容安全检测未在规定时间内返回结果（" + (waitMs / 1000) + "s）：未收到微信结果推送，"
                            + "请检查 mp 后台「消息推送」配置");
        }
        if (v.failed) {
            // 微信侧检测失败（拉不到图 / 内部错误）：不是"没结果"，要如实报出来
            throw new IllegalStateException("微信侧检测失败 errcode=" + v.errcode + " " + v.detail);
        }
        if ("pass".equalsIgnoreCase(v.suggest)) {
            return;
        }
        // risky（明确违规）与 review（疑似违规）都按拦截处理
        throw new RiskyContentException(v.suggest);
    }

    /** 提交异步检测，返回 trace_id（失败抛 IllegalStateException） */
    Submit submit(byte[] image, String openid) {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "未配置 XPHOTO_PUBLIC_BASE_URL —— mediaCheckAsync 要求图片公网可访问，微信无法回拉");
        }
        purgeExpired();
        String token = randomToken();
        medias.put(token, new Media(image, guessContentType(image), System.currentTimeMillis() + MEDIA_TTL_MS));
        String mediaUrl = trimSlash(publicBaseUrl) + "/api/photo/sec-check/media/" + token;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("media_url", mediaUrl);
        body.put("media_type", 1);   // 1 = 图片
        body.put("version", 2);
        body.put("scene", 1);        // 1 = 资料（证件照属资料图片）
        if (openid != null && !openid.isBlank()) {
            body.put("openid", openid);
        }
        String json;
        try {
            json = om.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("检测请求构造失败", e);
        }

        String respBody = callJson(json, PATH_ASYNC);
        String traceId;
        int errcode;
        try {
            JsonNode node = om.readTree(respBody == null ? "{}" : respBody);
            errcode = node.path("errcode").asInt(-1);
            traceId = node.path("trace_id").asText("");
        } catch (Exception e) {
            throw new IllegalStateException("检测响应解析失败: " + respBody, e);
        }
        if (errcode != 0 || traceId.isEmpty()) {
            throw new IllegalStateException("提交检测失败 errcode=" + errcode + " body=" + respBody);
        }
        pendings.put(traceId, new Pending(System.currentTimeMillis() + RESULT_TTL_MS));
        log.info("内容安全检测已提交 trace_id={}（等微信推送结果，最长 {}ms）", traceId, waitMs);
        return new Submit(traceId, mediaUrl);
    }

    /**
     * 轮询等待结果。
     * @return 三态：正常判定 / 微信侧失败（{@code v.failed}）/ 超时未回（null）
     */
    Verdict awaitVerdict(String traceId, long timeoutMs) {
        Pending p = pendings.get(traceId);
        if (p == null) {
            return null;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            if (p.resolved()) {
                return verdictOf(p);
            }
            if (System.currentTimeMillis() >= deadline) {
                // 超时按"没收到结果"处理（fail-closed），与"微信明确报错"区分开
                return p.resolved() ? verdictOf(p) : null;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return p.resolved() ? verdictOf(p) : null;
            }
        }
    }

    private static Verdict verdictOf(Pending p) {
        if (p.failed) {
            String detail = p.errmsg.isEmpty() ? p.raw : p.errmsg;
            return new Verdict(null, true, p.errcode, detail);
        }
        return new Verdict(p.suggest, false, p.errcode, p.label);
    }

    /**
     * 处理微信推送的检测结果（事件 wxa_media_check），由推送入口调用。
     * @return 是否匹配到等待中的提交
     */
    public boolean acceptPush(JsonNode node) {
        String traceId = node.path("trace_id").asText("");
        if (traceId.isEmpty()) {
            log.warn("内容安全检测推送缺 trace_id，忽略：{}", node);
            return false;
        }
        int errcode = node.path("errcode").asInt(0);
        String errmsg = node.path("errmsg").asText("");

        // suggest/label：顶层拿不到就去 detail 里找。
        // ⚠️ 微信 XML 推送里 suggest 是在 <detail> 内的：
        //   <detail><strategy/><errcode/><suggest/><label/><prob/></detail>
        // PushSignUtil 会把 detail 转成**嵌套对象**（不是字符串），因此必须按对象解析，
        // 用 asText() 取会得到空串、导致每次检测都白等到超时。
        String suggest = node.path("suggest").asText("");
        String label = node.path("label").asText("");
        int detailErrcode = 0;
        JsonNode detail = node.path("detail");
        if (detail.isTextual() && !detail.asText().isEmpty()) {
            try {
                detail = om.readTree(detail.asText());
            } catch (Exception ignore) {
                detail = null;   // detail 不是 JSON 就放弃解析
            }
        }
        if (detail != null) {
            JsonNode first = detail.isArray() ? (detail.size() > 0 ? detail.get(0) : null) : detail;
            if (first != null && first.isObject()) {
                if (suggest.isEmpty()) suggest = first.path("suggest").asText("");
                if (label.isEmpty()) label = first.path("label").asText("");
                detailErrcode = first.path("errcode").asInt(0);
            }
        }

        // 判定"微信侧检测失败"：顶层 errcode 非 0，或顶层没给结论但 detail 报了策略错误
        int failCode = errcode;
        String failMsg = errmsg;
        if (failCode == 0 && suggest.isEmpty() && detailErrcode != 0) {
            failCode = detailErrcode;
            failMsg = "detail.errcode=" + detailErrcode + "（检测策略执行失败，微信未能取到待检图或内部错误）";
        }

        Pending p = pendings.get(traceId);
        if (p == null) {
            log.info("内容安全检测结果无对应提交（可能已超时清理）trace_id={} suggest={} errcode={}",
                    traceId, suggest, errcode);
            return false;
        }
        p.errcode = failCode;
        p.errmsg = failMsg;
        p.raw = node.toString();
        if (failCode != 0) {
            p.failed = true;   // 明确失败：让上层立刻报错，而不是白等满 waitMs
            log.warn("内容安全检测失败 trace_id={} errcode={} errmsg={} raw={}",
                    traceId, failCode, failMsg, node);
            return true;
        }
        if (suggest.isEmpty()) {
            // 匹配到了但还没有结论（异常报文），保持等待，等下一轮或超时
            log.warn("内容安全检测推送已到达但无 suggest，继续等待 trace_id={} raw={}", traceId, node);
            return true;
        }
        p.suggest = suggest;
        p.label = label;
        log.info("内容安全检测结果 trace_id={} suggest={} label={}", traceId, suggest, label);
        return true;
    }

    /** 端点取待检图用；过期或不存在返回 null */
    public Media media(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        Media m = medias.get(token);
        if (m == null) {
            return null;
        }
        if (m.expireAt < System.currentTimeMillis()) {
            medias.remove(token);
            return null;
        }
        return m;
    }

    /** 运维自检：提交一张图并等结果，返回通道与结果明细（不抛异常） */
    public Map<String, Object> diagnose(byte[] image) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("cloudCall", cloudCall);
        out.put("publicBaseUrl", publicBaseUrl == null || publicBaseUrl.isBlank() ? "(未配置)" : publicBaseUrl);
        out.put("waitMs", waitMs);
        if (!enabled) {
            out.put("ok", false);
            out.put("msg", "SEC_CHECK_ENABLED=false，检测已被关闭（生产不允许）");
            return out;
        }
        try {
            Submit s = submit(image, null);
            out.put("traceId", s.traceId);
            out.put("mediaUrl", s.mediaUrl);
            out.put("submitted", true);
            Verdict v = awaitVerdict(s.traceId, waitMs);
            if (v == null) {
                out.put("ok", false);
                out.put("received", false);
                out.put("msg", "已提交但未在 " + (waitMs / 1000) + "s 内收到结果 —— 多半是 mp 后台「消息推送」"
                        + "未配置或未指向本服务 /api/photo/pay/notify");
                return out;
            }
            // 收到推送就说明「提交 → 公网回拉 → 推送」这条链路是通的
            out.put("received", true);
            out.put("errcode", v.errcode);
            out.put("suggest", v.suggest);
            if (v.failed) {
                out.put("ok", false);
                out.put("msg", "推送链路已通，但微信侧检测失败 errcode=" + v.errcode + " " + v.detail
                        + " —— 多半是微信拉不到 " + s.mediaUrl
                        + "（检查 XPHOTO_PUBLIC_BASE_URL 是否为公网可访问地址、云托管公网访问是否开启）");
                return out;
            }
            out.put("ok", true);
            out.put("msg", "通道正常，检测结果 suggest=" + v.suggest);
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("msg", "检测通道不可用：" + e.getMessage());
            return out;
        }
    }

    // ------------------------------------------------------------ 通道

    /** 提交接口调用：①云调用 → ②https+token（40001/42001 刷新重试一次） */
    private String callJson(String json, String path) {
        Exception cloudErr = null;
        if (cloudCall) {
            try {
                return postJson(json, HOST_CLOUD + path, true);
            } catch (Exception e) {
                cloudErr = e;
                log.warn("mediaCheckAsync 云托管内网通道失败，降级 https：{}", e.toString());
            }
        }
        String token;
        try {
            token = tokens.get();
        } catch (Exception e) {
            throw new IllegalStateException("取 access_token 失败：" + e.getMessage()
                    + (cloudErr == null ? "" : "；云调用通道失败：" + cloudErr.getMessage()), e);
        }
        for (int i = 0; i < 2; i++) {
            try {
                return postJson(json, HOST_HTTPS + path + "?access_token=" + token, false);
            } catch (Exception e) {
                if (i == 0 && e.getMessage() != null && (e.getMessage().contains("40001") || e.getMessage().contains("42001"))) {
                    try {
                        token = tokens.refresh();
                        continue;
                    } catch (Exception re) {
                        throw new IllegalStateException("刷新 access_token 失败：" + re.getMessage(), re);
                    }
                }
                throw new IllegalStateException("检测接口调用失败：" + e.getMessage()
                        + (cloudErr == null ? "" : "；云调用通道失败：" + cloudErr.getMessage()), e);
            }
        }
        throw new IllegalStateException("检测接口调用失败");
    }

    /**
     * 发 JSON（Content-Type: application/json），返回响应体。
     * 包级可见，便于测试用桩替换（与 PhotoUserVerifier.get 同思路）。
     */
    String postJson(String json, String url, boolean cloudChannel) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " body=" + resp.body());
        }
        if (cloudChannel && resp.headers().firstValue("x-openapi-seqid").isEmpty()) {
            // 走通云调用链路时微信会回带这个头；缺失 = 请求没经过「开放接口服务」，
            // 多半是控制台开关没开 / 接口没登记进微信令牌权限配置
            log.warn("mediaCheckAsync 响应缺 x-openapi-seqid —— 未走云托管云调用链路，请检查控制台"
                    + "「服务管理-云调用-开放接口服务」开关与「微信令牌权限配置」中的 /wxa/media_check_async");
        }
        return resp.body();
    }

    // ------------------------------------------------------------ 工具

    private String randomToken() {
        byte[] buf = new byte[16];
        random.nextBytes(buf);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : buf) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 只按魔数判断，避免把二进制当文本 */
    private static String guessContentType(byte[] b) {
        if (b != null && b.length > 3 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        return "image/jpeg";
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        medias.entrySet().removeIf(e -> e.getValue().expireAt < now);
        pendings.entrySet().removeIf(e -> e.getValue().expireAt < now);
        // 兜底：内存极端积压时清掉最老的一半，避免无上限增长
        if (medias.size() > 5000) {
            Iterator<String> it = medias.keySet().iterator();
            int drop = medias.size() / 2;
            while (it.hasNext() && drop-- > 0) {
                it.next();
                it.remove();
            }
            log.warn("待检图缓存超过 5000 条，已清理一半");
        }
    }
}
