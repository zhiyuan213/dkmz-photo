package com.dkmz.photo.service;

import com.tencentcloudapi.bda.v20200324.models.SegmentationOptions;
import com.tencentcloudapi.bda.v20200324.models.SegmentCustomizedPortraitPicRequest;
import com.tencentcloudapi.bda.v20200324.models.SegmentCustomizedPortraitPicResponse;
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
 * 腾讯云自定义人像分割 —— 拿精准的「面部皮肤」mask（美肤作用区域）。
 *
 * 接口：SegmentCustomizedPortraitPic（与 SegmentPortraitPic 共享每月 1000 次免费额度）。
 *
 * ⚠️ 与参考实现的关键差异：只请求 Face=true。
 *   官方语义：MaskImage 中「未选中的类目 = 背景」。若同时请求 Face+五官+头发，
 *   mask 是这些类目的并集——头发也会被美白（白发变黑发是另一回事，美白头发是错的）。
 *   只选 Face（面部皮肤，官方定义不含五官与头发）→ mask 就是纯脸颊皮肤，
 *   眉毛/眼睛/嘴唇/发丝天然排除，YCbCr 提亮只作用在皮肤上。
 *
 * 返回字段：PortraitImage / MaskImage（与二分类接口的 ResultImage/ResultMask 不同名）。
 * 全程内存直通，不落盘、日志不打印图片内容。
 */
@Service
public class CustomSegmentService {

    private static final Logger log = LoggerFactory.getLogger(CustomSegmentService.class);

    private static final int MAX_SIDE = 1920;

    private final BdaClientProvider provider;

    public CustomSegmentService(BdaClientProvider provider) {
        this.provider = provider;
    }

    public boolean isEnabled() {
        return provider.isEnabled();
    }

    /**
     * 获取面部皮肤区域 mask。
     *
     * @param imageBase64 原图 base64（可带 dataURL 前缀）
     * @return mask 灰度图 base64（PNG），亮部 = 面部皮肤
     */
    public String getFaceSkinMask(String imageBase64) {
        String pure = stripDataUrlPrefix(imageBase64);
        byte[] raw = Base64.getDecoder().decode(pure);

        SegmentCustomizedPortraitPicRequest req = new SegmentCustomizedPortraitPicRequest();
        req.setImage(Base64.getEncoder().encodeToString(compressIfNeeded(raw)));

        // 只选 Face：mask = 纯面部皮肤（不含五官与头发）
        SegmentationOptions opt = new SegmentationOptions();
        opt.setFace(true);
        req.setSegmentationOptions(opt);

        // 日志写明外部接口：域名 + Action + region
        String api = provider.endpointLabel();
        String rg = provider.regionLabel();
        long t0 = System.currentTimeMillis();
        try {
            SegmentCustomizedPortraitPicResponse resp =
                    provider.client().SegmentCustomizedPortraitPic(req);
            String mask = resp.getMaskImage();
            if (mask == null || mask.isEmpty()) {
                log.warn("外部接口 | POST {} Action=SegmentCustomizedPortraitPic region={} | 结果=异常（未返回 mask）| 耗时={}ms",
                        api, rg, System.currentTimeMillis() - t0);
                throw new IllegalStateException("未获取到面部区域");
            }
            log.info("外部接口 | POST {} Action=SegmentCustomizedPortraitPic region={} | 用途=面部皮肤 mask | 结果=ok | 耗时={}ms",
                    api, rg, System.currentTimeMillis() - t0);
            return mask;
        } catch (TencentCloudSDKException e) {
            log.warn("外部接口 | POST {} Action=SegmentCustomizedPortraitPic region={} | 结果=失败 {} | 耗时={}ms",
                    api, rg, e.getErrorCode(), System.currentTimeMillis() - t0);
            throw new IllegalStateException("面部区域识别失败", e);
        }
    }

    private static String stripDataUrlPrefix(String s) {
        if (s == null) return null;
        String t = s.trim();
        int i = t.indexOf("base64,");
        return i >= 0 ? t.substring(i + "base64,".length()).trim() : t;
    }

    private static byte[] compressIfNeeded(byte[] src) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(src));
            if (img == null || Math.max(img.getWidth(), img.getHeight()) <= MAX_SIDE) {
                return src;
            }
            double s = (double) MAX_SIDE / Math.max(img.getWidth(), img.getHeight());
            int nw = (int) Math.round(img.getWidth() * s);
            int nh = (int) Math.round(img.getHeight() * s);
            BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, nw, nh);
            g.drawImage(img, 0, 0, nw, nh, null);
            g.dispose();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(out, "jpg", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return src;
        }
    }
}
