package com.dkmz.photo.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 规格库下发体（GET /api/photo/specs 的返回结构）。
 * 字段与小程序端 utils/specs.js 的内置结构保持一致，前端可整体替换内存中的库。
 */
@Data
public class SpecDoc {

    private String version;

    private String updatedAt;

    private String product;

    private String disclaimer;

    /** 规格核实来源（官方公告列表），用于「关于」页展示可信度 */
    private List<String> verifiedFrom = new ArrayList<>();

    private List<JsonNode> backgrounds = new ArrayList<>();

    private List<JsonNode> presets = new ArrayList<>();

    private List<JsonNode> exams = new ArrayList<>();
}
