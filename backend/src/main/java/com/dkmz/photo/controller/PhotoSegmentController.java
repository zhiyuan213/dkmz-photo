package com.dkmz.photo.controller;

import com.dkmz.photo.service.BdaSegmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 云端精修（腾讯云 BDA 人像分割）接口。
 *
 *   POST /api/photo/segment   body: { image: "<base64 或 dataURL>" }
 *   返回: { ok:true, image:"<透明底 PNG base64>", costMs }
 *         { ok:false, code:"BDA_DISABLED"|"BAD_IMAGE"|"IMAGE_TOO_LARGE"|"BDA_FAIL", msg }
 *
 * 调用方（小程序）在端上 MODNet 结果不满意 / 用户点「云端精修」时使用；
 * code=BDA_DISABLED 或 BDA_FAIL 时前端一律回退端上抠图，功能不中断。
 * 隐私：开启后照片会上传至腾讯云做分割处理，仅用于生成分割结果、不做留存训练
 * （腾讯云承诺不作数据留存，见其《数据处理协议》）——隐私政策需相应披露。
 */
@RestController
@RequestMapping("/api/photo")
public class PhotoSegmentController {

    private static final Logger log = LoggerFactory.getLogger(PhotoSegmentController.class);

    private final BdaSegmentService bdaService;

    public PhotoSegmentController(BdaSegmentService bdaService) {
        this.bdaService = bdaService;
    }

    @PostMapping("/segment")
    public Map<String, Object> segment(@RequestBody(required = false) Map<String, String> body,
                                       @RequestHeader(value = "X-WX-OPENID", required = false) String openid) {
        String image = body == null ? null : body.get("image");
        if (image == null || image.isEmpty()) {
            return Map.of("ok", false, "code", "BAD_REQUEST", "msg", "缺少 image 参数");
        }
        Map<String, Object> res = bdaService.segmentPortrait(image);
        // 只记 openid 是否存在 + 结果状态，不落任何图片内容
        log.info("segment 调用 user={} code={}", openid == null ? "anonymous" : openid.substring(0, Math.min(8, openid.length())) + "…", res.get("code") == null ? "OK" : res.get("code"));
        return res;
    }
}
