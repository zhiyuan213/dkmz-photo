package com.dkmz.photo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 退款流水（审计留痕）。
 *
 * 为什么单独建表而不是给 photo_pay_order 加列：schema.sql 每次启动都执行，
 * ALTER TABLE ADD COLUMN 不幂等会让服务启动失败；新建表用 IF NOT EXISTS 则安全。
 * 幂等：同一 (wx_refund_id, ret_code) 只落一条（重复推送/重试不产生多条）。
 */
@Data
@TableName("photo_pay_refund")
public class PhotoPayRefund {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String outTradeNo;

    private String openid;

    private String wxRefundId;

    private String mchRefundId;

    private String wxOrderId;

    private Integer refundFee;

    private Integer retCode;

    private String retMsg;

    /** 本次回收的张数（未发货订单为 0） */
    private Integer quotaReduced;

    /** 本次回收的会员天数 */
    private Integer vipDaysReduced;

    private LocalDateTime succAt;

    private LocalDateTime createdAt;
}
