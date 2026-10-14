package com.dkmz.photo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 免费次数每日发放记录（每次「用户 + 业务日」一行）。
 *
 * 语义：last_grant_date 表示「最近一次给该用户发过免费额度的业务日」。
 * 与今天不同 ⇒ 今天还没发过 ⇒ 重置 free_quota 并把本行推到今天。
 *
 * 为什么不放在 photo_entitlement 上：schema.sql 每次启动执行，禁止 ALTER ADD COLUMN
 * （第二次启动会因列已存在而失败，导致服务起不来），所以只能新建表。
 */
@Data
@TableName("photo_free_grant")
public class PhotoFreeGrant {

    @TableId(type = IdType.INPUT)
    private String openid;

    /** 最近一次发放免费次数的业务日（Asia/Shanghai） */
    private LocalDate lastGrantDate;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
