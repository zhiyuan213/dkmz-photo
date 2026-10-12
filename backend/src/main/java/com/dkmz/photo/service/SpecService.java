package com.dkmz.photo.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dkmz.photo.dto.SpecDoc;
import com.dkmz.photo.entity.PhotoSpec;
import com.dkmz.photo.mapper.PhotoSpecMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规格库服务。
 *
 * 设计要点（为什么这么做）：
 *   ① 规格库是核心资产且**必须能紧急修正**（某考试改了尺寸要求 → 用户按旧规格做照会被驳回），
 *      所以放数据库、由后台接口改，前端启动时拉取，而不是写死在包里；
 *   ② 前端仍然保留**内置兜底**：拉取失败（离线/接口异常/首启）时用随包版本，功能不中断；
 *   ③ 种子只在**空表**时导入一次，之后以数据库为准 —— 避免每次启动覆盖运营改过的数据；
 *   ④ 但「下线条目」与「版本号」是例外（每次启动都执行）：这两件事必须能随包生效，
 *      否则模板删不掉、前端也因为版本号没变而永远不会重拉。
 */
@Service
public class SpecService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SpecService.class);

    /** meta 条目的固定主键 */
    public static final String META_ID = "meta";

    /**
     * 已下线条目（下线 = 从库里删行，而不是只在代码里藏起来 —— 库才是唯一事实来源）。
     *
     * 为什么必须显式删：种子只在**空表**时导入，之后一律以库为准，
     * 所以「下线某个模板/底色」改种子是没用的，老数据会一直躺在下发体里。
     */
    private static final Set<String> RETIRED_IDS = Set.of(
            "generic-red-1inch", // 通用一寸 · 红底（2026-10-13 下线）
            "red"                // 红底（仅被上面那个模板使用，成对下线）
    );

    private final PhotoSpecMapper specMapper;
    private final ObjectMapper om = new ObjectMapper();

    public SpecService(PhotoSpecMapper specMapper) {
        this.specMapper = specMapper;
    }

    // ==========================================================
    // 启动种子
    // ==========================================================

    @Override
    public void run(ApplicationArguments args) {
        try {
            Long count = specMapper.selectCount(null);
            if (count == null || count == 0) {
                int n = seedFromClasspath();
                log.info("规格库首次初始化完成，导入 {} 条", n);
            } else {
                log.info("规格库已存在 {} 条，跳过种子导入", count);
            }
            // 无论是否首次导入，都要做两件「必须能随包生效」的事：
            //   ① 清掉已下线条目 —— 早期种子留下的行不会自己消失
            //   ② 用随包 meta 覆盖库里的 meta —— 前端是「先比版本号，一样就不重拉」，
            //      版本号若由库里的老数据决定，改了规格它也永远不会重拉
            purgeRetired();
            refreshMetaFromClasspath();
        } catch (Exception e) {
            // 种子失败不能影响服务启动（前端有内置兜底）
            log.error("规格库种子导入 / 同步失败: {}", e.toString());
        }
    }

    /** 删除已下线条目（幂等：库里没有就跳过） */
    private void purgeRetired() {
        for (String id : RETIRED_IDS) {
            if (specMapper.selectById(id) == null) {
                continue;
            }
            specMapper.deleteById(id);
            log.info("规格库已下线条目 id={}（已从库中删除）", id);
        }
    }

    /** 用 classpath 的 specs-seed.json 覆盖 meta 行（版本号 / 核实日期以随包数据为准） */
    private void refreshMetaFromClasspath() throws Exception {
        try (InputStream in = new ClassPathResource("specs-seed.json").getInputStream()) {
            JsonNode meta = om.readTree(in).path("meta");
            if (meta.isMissingNode()) {
                return;
            }
            JsonNode node = meta.deepCopy();
            upsert(META_ID, "meta", "规格库元信息", node.toString(),
                    meta.path("updatedAt").asText(null), 0, true);
        }
    }

    /** 从 classpath 的 specs-seed.json 导入种子 */
    public int seedFromClasspath() throws Exception {
        try (InputStream in = new ClassPathResource("specs-seed.json").getInputStream()) {
            JsonNode root = om.readTree(in);
            return importDoc(root, false);
        }
    }

    /**
     * 导入整库。
     *
     * @param overwrite true = 覆盖已有条目（后台「重新导入内置库」用）；false = 只插入缺失的
     */
    public int importDoc(JsonNode root, boolean overwrite) {
        int n = 0;
        JsonNode meta = root.path("meta");
        if (!meta.isMissingNode()) {
            JsonNode node = meta.deepCopy();
            upsert(META_ID, "meta", "规格库元信息", node.toString(),
                    meta.path("updatedAt").asText(null), 0, overwrite);
            n++;
        }
        n += importList(root.path("backgrounds"), "background", overwrite, false);
        n += importList(root.path("presets"), "preset", overwrite, false);
        n += importList(root.path("exams"), "exam", overwrite, true);
        return n;
    }

    private int importList(JsonNode arr, String kind, boolean overwrite, boolean useUpdatedAt) {
        if (arr == null || !arr.isArray()) {
            return 0;
        }
        int n = 0;
        int sort = 0;
        for (JsonNode item : arr) {
            String id = item.path("id").asText(null);
            if (id == null || id.isBlank()) {
                continue;
            }
            String name = item.path("name").asText(id);
            String updatedAt = useUpdatedAt ? item.path("updatedAt").asText(null) : null;
            upsert(id, kind, name, item.toString(), updatedAt, sort++, overwrite);
            n++;
        }
        return n;
    }

    private void upsert(String id, String kind, String name, String payload,
                        String updatedAt, int sortNo, boolean overwrite) {
        PhotoSpec exists = specMapper.selectById(id);
        if (exists != null && !overwrite) {
            return;
        }
        PhotoSpec s = new PhotoSpec();
        s.setId(id);
        s.setKind(kind);
        s.setName(name);
        s.setPayload(payload);
        s.setSortNo(sortNo);
        s.setUpdatedAt(updatedAt);
        s.setModifiedAt(LocalDateTime.now());
        if (exists == null) {
            s.setCreatedAt(LocalDateTime.now());
            specMapper.insert(s);
        } else {
            specMapper.updateById(s);
        }
    }

    // ==========================================================
    // 下发
    // ==========================================================

    /** 组装整库下发体；库里为空时返回 null（前端用内置兜底） */
    public SpecDoc doc() {
        List<PhotoSpec> all = specMapper.selectList(new QueryWrapper<PhotoSpec>().orderByAsc("sort_no"));
        if (all == null || all.isEmpty()) {
            return null;
        }
        SpecDoc doc = new SpecDoc();
        for (PhotoSpec s : all) {
            JsonNode node = parse(s.getPayload());
            switch (s.getKind()) {
                case "meta" -> {
                    doc.setVersion(node.path("version").asText(null));
                    doc.setUpdatedAt(node.path("updatedAt").asText(null));
                    doc.setProduct(node.path("product").asText(null));
                    doc.setDisclaimer(node.path("disclaimer").asText(null));
                    List<String> from = new ArrayList<>();
                    for (JsonNode f : node.path("verifiedFrom")) {
                        from.add(f.asText());
                    }
                    doc.setVerifiedFrom(from);
                }
                case "background" -> doc.getBackgrounds().add(node);
                case "preset" -> doc.getPresets().add(node);
                case "exam" -> doc.getExams().add(node);
                default -> log.warn("未知规格条目类型 kind={} id={}", s.getKind(), s.getId());
            }
        }
        return doc;
    }

    /** 单条规格（前端按需增量刷新用） */
    public JsonNode item(String id) {
        PhotoSpec s = specMapper.selectById(id);
        return s == null ? null : parse(s.getPayload());
    }

    /** 只取版本号（前端启动时先比版本，避免每次全量拉） */
    public Map<String, Object> version() {
        PhotoSpec meta = specMapper.selectById(META_ID);
        JsonNode node = meta == null ? null : parse(meta.getPayload());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("version", node == null ? null : node.path("version").asText(null));
        out.put("updatedAt", node == null ? null : node.path("updatedAt").asText(null));
        out.put("entries", specMapper.selectCount(null));
        return out;
    }

    /** 单条 upsert（后台紧急修正单个考试规格） */
    public Map<String, Object> upsertItem(String id, String kind, String name, JsonNode payload, String updatedAt) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (id == null || id.isBlank() || payload == null || payload.isMissingNode()) {
            out.put("ok", false);
            out.put("msg", "参数无效：需要 id 与 payload");
            return out;
        }
        PhotoSpec exists = specMapper.selectById(id);
        String kindFinal = (kind != null && !kind.isBlank())
                ? kind
                : (exists != null ? exists.getKind() : "exam");
        String nameFinal = (name != null && !name.isBlank())
                ? name
                : (exists != null ? exists.getName() : payload.path("name").asText(id));
        upsert(id, kindFinal, nameFinal, payload.toString(), updatedAt,
                exists != null && exists.getSortNo() != null ? exists.getSortNo() : 999, true);
        out.put("ok", true);
        out.put("id", id);
        out.put("kind", kindFinal);
        log.info("规格条目已更新 id={} kind={}", id, kindFinal);
        return out;
    }

    /** 全量覆盖导入（后台改完整库后提交） */
    public Map<String, Object> importAll(JsonNode root, boolean overwrite) {
        int n = importDoc(root, overwrite);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("imported", n);
        out.put("overwrite", overwrite);
        log.info("规格库导入完成 imported={} overwrite={}", n, overwrite);
        return out;
    }

    private JsonNode parse(String json) {
        try {
            return om.readTree(json);
        } catch (Exception e) {
            log.error("规格条目 JSON 解析失败: {}", e.toString());
            return om.createObjectNode();
        }
    }
}
