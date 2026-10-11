package com.dkmz.photo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 规格库条目。
 *
 * 为什么用「一行一条目」而不是整库一个 JSON 字段：规格库需要**紧急可修正**——
 * 某个考试改了尺寸要求时，运营只需改这一条（接口 POST /api/photo/admin/specs），
 * 不用重发整个库、也不会覆盖其他条目的改动。
 *
 * kind 取值：
 *   meta        —— 库级元信息（version / updatedAt / disclaimer / verifiedFrom），id 固定 'meta'
 *   background  —— 底色（白/蓝/浅蓝/红）
 *   preset      —— 预设尺寸（一寸/二寸…）
 *   exam        —— 考试规格（id 即考试 key，如 kaoyan）
 *
 * payload 是条目自身的完整 JSON（与前端 specs.js 结构一致），前端无需二次映射。
 */
@Data
@TableName("photo_spec")
public class PhotoSpec {

    /** 条目 ID：meta / background id / preset id / exam id */
    @TableId(type = IdType.INPUT)
    private String id;

    /** meta / background / preset / exam */
    private String kind;

    /** 展示名（便于后台列表查看） */
    private String name;

    /** 条目 JSON（原样下发） */
    private String payload;

    /** 排序（越小越前） */
    private Integer sortNo;

    /** 本条目的规格核实/更新日期（运营可单独标注） */
    private String updatedAt;

    private LocalDateTime createdAt;

    private LocalDateTime modifiedAt;
}
