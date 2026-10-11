package com.dkmz.photo.service;

import com.tencentcloudapi.bda.v20200324.BdaClient;
import com.tencentcloudapi.bda.v20200324.models.SegmentPortraitPicRequest;
import com.tencentcloudapi.bda.v20200324.models.SegmentPortraitPicResponse;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 腾讯云 BDA（人体分析）人像分割 —— 「云端精修」抠图。
 *
 * 背景：端上 MODNet 在发丝边缘 / 复杂背景 / 低端机上的抠图质量有限，
 * 提供可选的云端分割通道（SegmentPortraitPic，二分类人像分割），
 * 返回透明底 PNG（ResultImage），前端据此重建 alpha 继续走原有换底合成。
 *
 * 关键约束（腾讯云文档）：
 *   - 图片 base64 ≤ 5M、分辨率 < 2000×2000（证件照裁剪图本身只有几百 KB / 几百像素，天然满足）
 *   - 地域仅 ap-beijing / ap-guangzhou / ap-shanghai
 *   - 每接口每月 1000 次免费额度，超出后付费约 0.01 元/次
 *
 * 未配置密钥时 isEnabled()=false，接口明确返回「未开通」，前端回退端上抠图。
 */
@Service
public class BdaSegmentService {

    private static final Logger log = LoggerFactory.getLogger(BdaSegmentService.class);

    /** base64 入参上限：文档限制 5M，这里收紧一点防滥用 */
    private static final int MAX_BASE64_LEN = 5_500_000;

    @Value("${photo.bda.enabled:false}")
    private boolean enabled;

    @Value("${photo.bda.secret-id:}")
    private String secretId;

    @Value("${photo.bda.secret-key:}")
    private String secretKey;

    @Value("${photo.bda.region:ap-guangzhou}")
    private String region;

    /** 可选：云托管容器证书链问题时可切内网域名 bda.internal.tencentcloudapi.com */
    @Value("${photo.bda.endpoint:}")
    private String endpoint;

    private volatile BdaClient client;

    public boolean isEnabled() {
        return enabled && notBlank(secretId) && notBlank(secretKey);
    }

    /**
     * 调用 SegmentPortraitPic，返回 {ok, image?, code?, msg?}
     * @param imageBase64 原图 base64（允许带 data:image/xxx;base64, 前缀，自动剥离）
     */
    public Map<String, Object> segmentPortrait(String imageBase64) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!isEnabled()) {
            out.put("ok", false);
            out.put("code", "BDA_DISABLED");
            out.put("msg", "云端精修尚未开通");
            return out;
        }
        String b64 = stripDataUrlPrefix(imageBase64);
        if (b64 == null || b64.length() < 100) {
            out.put("ok", false);
            out.put("code", "BAD_IMAGE");
            out.put("msg", "图片数据为空或过小");
            return out;
        }
        if (b64.length() > MAX_BASE64_LEN) {
            out.put("ok", false);
            out.put("code", "IMAGE_TOO_LARGE");
            out.put("msg", "图片超过 5M 限制");
            return out;
        }

        long t0 = System.currentTimeMillis();
        try {
            SegmentPortraitPicRequest req = new SegmentPortraitPicRequest();
            req.setImage(b64);
            req.setScene("GEN");          // 通用场景；证件照不是绿幕
            req.setRspImgType("base64");  // 直接回 base64，省一次 30 分钟有效的临时 URL 下载

            SegmentPortraitPicResponse resp = client().SegmentPortraitPic(req);
            long cost = System.currentTimeMillis() - t0;
            String image = resp.getResultImage();
            if (image == null || image.isEmpty()) {
                log.warn("BDA 人像分割返回空图 cost={}ms", cost);
                out.put("ok", false);
                out.put("code", "EMPTY_RESULT");
                out.put("msg", "云端分割未返回图片");
                return out;
            }
            log.info("BDA 人像分割 ok cost={}ms inLen={} outLen={}", cost, b64.length(), image.length());
            out.put("ok", true);
            out.put("image", image);
            out.put("costMs", cost);
            return out;
        } catch (TencentCloudSDKException e) {
            log.error("BDA 人像分割失败 code={} msg={}", e.getErrorCode(), e.getMessage());
            out.put("ok", false);
            out.put("code", "BDA_FAIL");
            out.put("msg", (e.getErrorCode() != null ? e.getErrorCode() + ": " : "") + e.getMessage());
            return out;
        } catch (Exception e) {
            // 网络层异常（连接超时 / 证书链 PKIX 等）也归一到 BDA_FAIL
            log.error("BDA 调用异常: {}", e.toString());
            out.put("ok", false);
            out.put("code", "BDA_FAIL");
            out.put("msg", e.toString());
            return out;
        }
    }

    private BdaClient client() {
        if (this.client == null) {
            synchronized (this) {
                if (this.client == null) {
                    Credential cred = new Credential(secretId, secretKey);
                    HttpProfile http = new HttpProfile();
                    http.setReadTimeout(20);      // 秒
                    http.setConnTimeout(10);
                    if (notBlank(endpoint)) {
                        http.setEndpoint(endpoint.trim());
                    }
                    ClientProfile profile = new ClientProfile();
                    profile.setHttpProfile(http);
                    this.client = new BdaClient(cred, region, profile);
                    log.info("BdaClient 初始化 region={} endpoint={}", region, notBlank(endpoint) ? endpoint : "(默认)");
                }
            }
        }
        return this.client;
    }

    /** 剥离 data URL 前缀（data:image/jpeg;base64,xxxx → xxxx） */
    private static String stripDataUrlPrefix(String s) {
        if (s == null) return null;
        String t = s.trim();
        int i = t.indexOf("base64,");
        return i >= 0 ? t.substring(i + "base64,".length()).trim() : t;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
