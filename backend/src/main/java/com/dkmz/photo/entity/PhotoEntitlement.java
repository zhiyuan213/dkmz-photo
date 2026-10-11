package com.dkmz.photo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户权益（会员 + 高清导出张数）。
 *
 * 与 exam-countdown 的 user_quota 的差别：那边额度是「可同时管理的提醒数」按实时计算，
 * 这边是**消耗型**（导出一次扣一张），所以 paid_quota 用原子自减，
 * 且导出动作由 photo_export 表按 client_no 幂等，避免前端重试重复扣减。
 *
 * 会员（vip_expire_at 未到期）在有效期内不消耗张数。
 */
@Data
@TableName("photo_entitlement")
public class PhotoEntitlement {

    @TableId(type = IdType.INPUT)
    private String openid;

    /** 免费体验张数（默认 1，用于让用户在真机上看到「无水印效果」再决定付费） */
    private Integer freeQuota;

    /** 已购剩余张数 */
    private Integer paidQuota;

    /** 会员到期时间（null = 从未购买会员） */
    private LocalDateTime vipExpireAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
