package com.dkmz.photo.service;

import com.tencentcloudapi.bda.v20200324.models.SegmentPortraitPicRequest;
import com.tencentcloudapi.bda.v20200324.models.SegmentPortraitPicResponse;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * 腾讯云 BDA（人体分析）人像分割 —— 「云端精修」抠图。
 *
 * 接口：SegmentPortraitPic（Version 2020-03-24，仅 ap-beijing/guangzhou/shanghai）
 * 返回 ResultImage = 透明底 PNG base64；前端据此重建 alpha 继续端上换底合成。
 *
 * 硬限制（超了直接报错）：
 *   - 分辨率 < 2000×2000 → 服务端先压缩到长边 ≤1920 兜底
 *   - base64 编码后 ≤ 5M
 *   - 只用 Image(base64) 入参，不用 Url（Url 需要先落存储，违背不落盘原则）
 *
 * 可靠性：
 *   - 偶发 ServerError/RequestTimeout 自动重试 1 次
 *   - SDK 错误码翻译成中文提示（mapError），前端可直接展示
 *
 * 安全：全程内存直通，不写盘、不写库、任何日志不打印图片内容。
 */
@Service
public class BdaSegmentService {

    private static final Logger log = LoggerFactory.getLogger(BdaSegmentService.class);

    /** 腾讯云硬限制：分辨率 < 2000×2000，服务端压缩到长边 ≤1920 */
    private static final int MAX_SIDE = 1920;
    /** base64 编码后上限 5M，留余量 */
    private static final int MAX_BASE64_LEN = 5_500_000;
    /** 原始字节上限（base64 长度 × 3/4），超过先压缩 */
    private static final int MAX_RAW_BYTES = 4 * 1024 * 1024;

    private final BdaClientProvider provider;

    public BdaSegmentService(BdaClientProvider provider) {
        this.provider = provider;
    }

    public boolean isEnabled() {
        return provider.isEnabled();
    }

    /**
     * 人像分割：入参原图 base64（可带 dataURL 前缀），返回透明底 PNG base64。
     * 失败抛 IllegalStateException，消息为可直接展示的中文。
     */
    public String segment(String imageBase64) {
        String b64 = stripDataUrlPrefix(imageBase64);
        if (b64 == null || b64.length() < 100) {
            throw new IllegalArgumentException("图片数据为空或过小");
        }
        if (b64.length() > MAX_BASE64_LEN) {
            throw new IllegalArgumentException("图片超过 5M 限制");
        }

        byte[] raw = Base64.getDecoder().decode(b64);
        byte[] prepared = compressIfNeeded(raw);

        SegmentPortraitPicRequest req = new SegmentPortraitPicRequest();
        req.setImage(Base64.getEncoder().encodeToString(prepared));
        req.setScene("GEN");          // 通用场景；证件照不是绿幕
        req.setRspImgType("base64");  // 直接回 base64，省一次 30 分钟有效的临时 URL 下载

        long t0 = System.currentTimeMillis();
        SegmentPortraitPicResponse resp;
        try {
            resp = provider.client().SegmentPortraitPic(req);
        } catch (TencentCloudSDKException e) {
            // 偶发 ServerError/超时重试一次；其余错误（分辨率、无人脸、欠费）不重试
            if (isRetryable(e.getErrorCode())) {
                log.warn("BDA 首次调用失败，重试一次: {}", e.getErrorCode());
                try {
                    resp = provider.client().SegmentPortraitPic(req);
                } catch (TencentCloudSDKException retryEx) {
                    throw new IllegalStateException(mapError(retryEx), retryEx);
                }
            } else {
                throw new IllegalStateException(mapError(e), e);
            }
        }

        String image = resp.getResultImage();
        if (image == null || image.isEmpty()) {
            throw new IllegalStateException("人像分割未返回结果图");
        }
        log.info("BDA 人像分割 ok cost={}ms", System.currentTimeMillis() - t0);
        // 注意：绝不 log 入参/结果 base64，会把用户人脸写进日志
        return image;
    }

    private static boolean isRetryable(String code) {
        return "FailedOperation.ServerError".equals(code)
                || "FailedOperation.RequestTimeout".equals(code)
                || "FailedOperation.UnKnowError".equals(code);
    }

    /** SDK 错误码 → 前端可直接展示的中文 */
    private static String mapError(TencentCloudSDKException e) {
        String c = e.getErrorCode();
        if (c == null) return "抠图服务异常，请重试";
        switch (c) {
            case "FailedOperation.ImageResolutionExceed":
                return "图片分辨率过大，请压缩后重试";
            case "FailedOperation.ImageSizeExceed":
            case "LimitExceeded.TooLargeFileError":
            case "FailedOperation.RequestEntityTooLarge":
                return "图片文件过大，请压缩后重试";
            case "FailedOperation.ImageResolutionInsufficient":
                return "图片分辨率过小";
            case "InvalidParameterValue.NoFaceInPhoto":
                return "照片中没有检测到人脸，请换一张正面照";
            case "FailedOperation.ProfileNumExceed":
                return "照片中人数过多，请使用单人照片";
            case "FailedOperation.ImageDecodeFailed":
            case "FailedOperation.ImageNotSupported":
                return "图片格式不支持，请使用 JPG 或 PNG";
            case "FailedOperation.SegmentFailed":
                return "抠图失败，请换一张背景更简洁的照片";
            case "ResourceUnavailable.InArrears":
                return "服务欠费，请联系管理员";
            case "ResourceUnavailable.NotExist":
                return "未开通人像分割服务";
            default:
                return "抠图失败（" + c + "），请重试";
        }
    }

    /**
     * 压缩到接口限制内（纯内存，不落盘）。手机原图普遍 3000×4000，不压必报错。
     * 压缩失败就原样传，让腾讯云去判断。
     */
    private static byte[] compressIfNeeded(byte[] src) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(src));
            if (img == null) return src;

            int w = img.getWidth();
            int h = img.getHeight();
            boolean needResize = Math.max(w, h) > MAX_SIDE;
            if (!needResize && src.length <= MAX_RAW_BYTES) {
                return src;
            }

            BufferedImage out = img;
            if (needResize) {
                double s = (double) MAX_SIDE / Math.max(w, h);
                int nw = (int) Math.round(w * s);
                int nh = (int) Math.round(h * s);
                BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                // JPEG 读进来可能有 alpha，先铺白底，避免缩图后边缘发黑
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, nw, nh);
                g.drawImage(img, 0, 0, nw, nh, null);
                g.dispose();
                out = scaled;
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(out, "jpg", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return src;
        }
    }

    /** 剥离 data URL 前缀（data:image/jpeg;base64,xxxx → xxxx） */
    private static String stripDataUrlPrefix(String s) {
        if (s == null) return null;
        String t = s.trim();
        int i = t.indexOf("base64,");
        return i >= 0 ? t.substring(i + "base64,".length()).trim() : t;
    }
}
