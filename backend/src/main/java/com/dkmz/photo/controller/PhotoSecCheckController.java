package com.dkmz.photo.controller;

import com.dkmz.photo.service.WxSecCheckService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内容安全检测的配套端点。
 *
 * GET /api/photo/sec-check/media/{token}  —— **公开无鉴权**，供微信服务器回拉待检图
 *   （security.mediaCheckAsync 的 media_url 必须公网可访问）。
 *   token 为 128 位随机串、10 分钟过期，且不落盘、不入对象存储，请求时即取即弃。
 *
 * GET /api/photo/sec-check/selftest?token=<PHOTO_JOB_TOKEN>&openid=<真实openid> —— 运维自检（需口令）
 *   现造一张纯白小图跑一遍「提交异步检测 → 等微信推送结果」全链路，
 *   **不必再上真机拍照片**看结果页明细。返回 traceId / suggest / 各通道状态。
 *   ⚠️ openid 必填（微信要求，且该用户近两小时访问过小程序）；也可由云托管注入的
 *   X-WX-OPENID 头自动带入。真实 openid 可从云托管日志里 `下单结果 openid=oXXXX` 那行取。
 */
@RestController
@RequestMapping("/api/photo/sec-check")
public class PhotoSecCheckController {

    private static final Logger log = LoggerFactory.getLogger(PhotoSecCheckController.class);

    private final WxSecCheckService secCheck;

    @Value("${photo.job-token:change-me}")
    private String jobToken;

    public PhotoSecCheckController(WxSecCheckService secCheck) {
        this.secCheck = secCheck;
    }

    /** 微信来拉待检图：无鉴权，但 token 不可猜且短命 */
    @GetMapping("/media/{token}")
    public ResponseEntity<byte[]> media(@PathVariable String token) {
        WxSecCheckService.Media m = secCheck.media(token);
        if (m == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(m.contentType))
                .cacheControl(CacheControl.noStore())
                .body(m.bytes);
    }

    @GetMapping("/selftest")
    public Map<String, Object> selftest(@RequestParam(required = false) String token,
                                        @RequestParam(required = false) String openid,
                                        @RequestHeader(value = "X-WX-OPENID", required = false) String cloudOpenid) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (jobToken == null || jobToken.isEmpty() || !jobToken.equals(token)) {
            out.put("ok", false);
            out.put("msg", "token 无效");
            return out;
        }
        // 云托管 callContainer 会自动注入 X-WX-OPENID；命令行自检则显式传 ?openid=
        String who = openid != null && !openid.isBlank() ? openid : cloudOpenid;
        if (who == null || who.isBlank()) {
            out.put("ok", false);
            out.put("msg", "缺少 openid：请加 &openid=<真实openid>（微信侧必填，且该用户近两小时访问过小程序）；"
                    + "真实 openid 可从云托管日志的 `下单结果 openid=oXXXX` 一行取得");
            return out;
        }
        try {
            byte[] sample = whitePng(256, 256);
            out.put("sampleBytes", sample.length);
            Map<String, Object> result = secCheck.diagnose(sample, who);
            out.putAll(result);
            // 地址取实际请求到的那条（云调用内网 / https 直连），与检测汇总日志同源
            Object apiUrl = result.get("apiUrl");
            log.info("内容安全检测 | 来源=自检 | 接口=POST {} | trace_id={} | "
                            + "结果=suggest:{} errcode:{} | received={} | ok={} | msg={}",
                    apiUrl == null ? "/wxa/media_check_async（请求未发出）" : apiUrl,
                    result.get("traceId"), result.get("suggest"), result.get("errcode"),
                    result.get("received"), result.get("ok"), result.get("msg"));
        } catch (Exception e) {
            log.error("sec-check selftest 异常: {}", e.toString());
            out.put("ok", false);
            out.put("msg", "自检异常：" + e.getMessage());
        }
        return out;
    }

    /** 现造一张纯白 PNG，仅用于探活检测通道 */
    private static byte[] whitePng(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }
}
