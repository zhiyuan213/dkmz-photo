package com.dkmz.photo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 虚拟支付订单（道具直购 short_series_goods）。
 *
 * 状态机：INIT（已下单未支付）-> DELIVERED（已发货，权益已到账）
 *         兜底：REFUNDED（已退款并回收权益）
 *
 * 主键 outTradeNo 即下单时下发给微信的业务订单号，发货推送靠它回捞订单；
 * wxOrderId 存微信平台单号（WeChatPayInfo.MchOrderNo），做第二道幂等去重。
 */
@Data
@TableName("photo_pay_order")
public class PhotoPayOrder {

    /** 业务订单号（主键），格式 P + yyyyMMddHHmmssSSS + 4 位随机 */
    @TableId(type = IdType.INPUT)
    private String outTradeNo;

    private String openid;

    /** 道具 ID（与 mp 后台「道具管理」完全一致） */
    private String productId;

    private String goodsName;

    /** 本单发放的高清导出张数（会员类商品为 0） */
    private Integer quotaDelta;

    /** 本单发放的会员天数（张数包为 0） */
    private Integer vipDays;

    private Integer quantity;

    /** 道具单价（分），必须与后台道具价格一致 */
    private Integer priceFen;

    /** 微信回调里的实付金额（分） */
    private Integer actualFen;

    /** 0 现网 / 1 沙箱 */
    private Integer env;

    /** INIT / DELIVERED / REFUNDED */
    private String status;

    /** 微信侧平台单号 */
    private String wxOrderId;

    /** 微信支付交易号 */
    private String transactionId;

    /** 透传数据：openid|goodsId|quota|vipDays */
    private String attach;

    /** 收到发货推送的次数（排查重试问题用） */
    private Integer notifyCount;

    private LocalDateTime createdAt;

    private LocalDateTime paidAt;

    private LocalDateTime deliveredAt;
}
