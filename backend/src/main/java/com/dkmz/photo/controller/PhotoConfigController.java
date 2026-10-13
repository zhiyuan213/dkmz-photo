package com.dkmz.photo.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/** 仅下发公开产品开关，不返回支付或腾讯云凭据。 */
@RestController
public class PhotoConfigController {
    private final boolean reviewMode;

    public PhotoConfigController(@Value("${photo.review-mode:false}") boolean reviewMode) {
        this.reviewMode = reviewMode;
    }

    @GetMapping("/api/photo/config")
    public Map<String, Object> config() {
        return Map.of("ok", true, "reviewMode", reviewMode);
    }
}
