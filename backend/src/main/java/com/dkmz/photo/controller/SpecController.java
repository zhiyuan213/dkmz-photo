package com.dkmz.photo.controller;

import com.dkmz.photo.dto.SpecDoc;
import com.dkmz.photo.service.SpecService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 规格库接口（考试证件照规格的云端下发与后台维护）。
 *
 *   GET  /api/photo/specs              整库下发（前端启动/下拉刷新时调用）
 *   GET  /api/photo/specs/version      只取版本号（先比版本，避免每次全量拉）
 *   GET  /api/photo/specs/{id}         单条规格（如 kaoyan / ntce-certify）
 *   GET  /api/photo/presets            通用预设（一寸/二寸…），首页「常用尺寸」用
 *
 * 管理端（需 token，参数 token=<PHOTO_JOB_TOKEN>）：
 *   POST /api/photo/admin/spec-item    单条 upsert —— 某考试改了要求时用这个，最小改动面
 *   POST /api/photo/admin/specs        全量覆盖导入（body 同 GET /api/photo/specs 结构）
 *   POST /api/photo/admin/reseed       重新导入内置种子（只补缺失条目，不覆盖已改条目）
 *
 * 为什么要「云端可配」：规格写错会让用户报名被驳回（比不做更糟），
 * 出问题必须能在**几分钟内**改掉，而不是等小程序发版审核。
 */
@RestController
@RequestMapping("/api/photo")
public class SpecController {

    private static final Logger log = LoggerFactory.getLogger(SpecController.class);

    private final SpecService specService;

    @Value("${photo.job-token:change-me}")
    private String jobToken;

    public SpecController(SpecService specService) {
        this.specService = specService;
    }

    // ==========================================================
    // 下发
    // ==========================================================

    @GetMapping("/specs")
    public Map<String, Object> specs() {
        Map<String, Object> out = new LinkedHashMap<>();
        SpecDoc doc = specService.doc();
        if (doc == null) {
            // 库里为空（种子未导入成功）：明确告知，前端用随包内置库兜底
            out.put("ok", false);
            out.put("code", "SPEC_EMPTY");
            out.put("msg", "云端规格库为空，请使用内置库");
            return out;
        }
        out.put("ok", true);
        out.put("version", doc.getVersion());
        out.put("updatedAt", doc.getUpdatedAt());
        out.put("product", doc.getProduct());
        out.put("disclaimer", doc.getDisclaimer());
        out.put("verifiedFrom", doc.getVerifiedFrom());
        out.put("backgrounds", doc.getBackgrounds());
        out.put("presets", doc.getPresets());
        out.put("exams", doc.getExams());
        return out;
    }

    @GetMapping("/specs/version")
    public Map<String, Object> version() {
        return specService.version();
    }

    @GetMapping("/specs/{id}")
    public Map<String, Object> one(@PathVariable String id) {
        Map<String, Object> out = new LinkedHashMap<>();
        JsonNode node = specService.item(id);
        if (node == null) {
            out.put("ok", false);
            out.put("code", "SPEC_NOT_FOUND");
            out.put("msg", "规格不存在: " + id);
            return out;
        }
        out.put("ok", true);
        out.put("id", id);
        out.put("spec", node);
        return out;
    }

    @GetMapping("/presets")
    public Map<String, Object> presets() {
        Map<String, Object> out = new LinkedHashMap<>();
        SpecDoc doc = specService.doc();
        out.put("ok", doc != null);
        out.put("presets", doc == null ? java.util.List.of() : doc.getPresets());
        return out;
    }

    // ==========================================================
    // 管理端
    // ==========================================================

    /** 单条规格 upsert（紧急修正用）：?token=xx&id=kaoyan&kind=exam&name=考研&updatedAt=2026-10-12 */
    @PostMapping("/admin/spec-item")
    public Map<String, Object> upsertItem(@RequestParam(required = false) String token,
                                          @RequestParam(required = false) String id,
                                          @RequestParam(required = false) String kind,
                                          @RequestParam(required = false) String name,
                                          @RequestParam(required = false) String updatedAt,
                                          @RequestBody(required = false) JsonNode payload) {
        if (!jobToken.equals(token)) {
            return denied();
        }
        Map<String, Object> res = specService.upsertItem(id, kind, name, payload, updatedAt);
        log.info("规格条目 upsert id={} ok={}", id, res.get("ok"));
        return res;
    }

    /** 全量覆盖导入：body 结构与 GET /api/photo/specs 一致 */
    @PostMapping("/admin/specs")
    public Map<String, Object> importAll(@RequestParam(required = false) String token,
                                         @RequestParam(required = false, defaultValue = "true") boolean overwrite,
                                         @RequestBody(required = false) JsonNode body) {
        if (!jobToken.equals(token)) {
            return denied();
        }
        if (body == null || body.isMissingNode()) {
            return Map.of("ok", false, "msg", "请求体为空");
        }
        return specService.importAll(body, overwrite);
    }

    /** 重新导入内置种子（只补缺失条目，不覆盖后台改过的条目） */
    @PostMapping("/admin/reseed")
    public Map<String, Object> reseed(@RequestParam(required = false) String token) {
        if (!jobToken.equals(token)) {
            return denied();
        }
        try {
            int n = specService.seedFromClasspath();
            return Map.of("ok", true, "imported", n, "note", "仅补齐缺失条目，已存在的不覆盖");
        } catch (Exception e) {
            log.error("重新导入内置种子失败: {}", e.toString());
            return Map.of("ok", false, "msg", e.toString());
        }
    }

    private static Map<String, Object> denied() {
        return Map.of("ok", false, "msg", "token 无效");
    }
}
