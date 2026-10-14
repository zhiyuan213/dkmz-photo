package com.dkmz.photo.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 规格库下发体（GET /api/photo/specs 的返回结构）。
 * 字段与小程序端 utils/specs.js 的内置结构保持一致，前端可整体替换内存中的库。
 *
 * v0.5.0 结构升级：去掉「考试（exam）」维度，改为**底色 + 尺寸**两个平铺列表。
 * 原 exams/presets 两个字段合并为 sizes（每个尺寸带 usedBy 场景标注）。
 * schemaVersion 变化时后端会全量重导种子，见 SpecService.syncSchemaIfNeeded。
 */
@Data
public class SpecDoc {

    private String version;

    private String updatedAt;

    private String product;

    private String disclaimer;

    /** 规格核实来源（官方公告列表），用于「关于」页展示可信度 */
    private List<String> verifiedFrom = new ArrayList<>();

    /** 底色：白 / 蓝 / 浅蓝 / 红 */
    private List<JsonNode> backgrounds = new ArrayList<>();

    /** 尺寸：一寸、二寸、考研 3:4…… 每条带 usedBy 场景标注与最严体积上限 */
    private List<JsonNode> sizes = new ArrayList<>();
}
