package com.dkmz.photo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 商品（虚拟支付道具）。
 *
 * ⚠️ goodsId 必须与 mp 后台「虚拟支付 → 道具管理」登记的**道具 ID 完全一致**，
 *    priceFen 必须与后台价格完全一致，否则拉起支付会被微信侧拒绝。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PayGoods {

    /** 道具 ID（= 后台道具 ID），前端用它下单 */
    private String goodsId;

    /** 展示名 */
    private String name;

    /** 单价（分） */
    private Integer priceFen;

    /** 本商品发放的高清导出张数（会员类为 0） */
    private Integer quota;

    /** 本商品发放的会员天数（张数包为 0） */
    private Integer vipDays;
}
