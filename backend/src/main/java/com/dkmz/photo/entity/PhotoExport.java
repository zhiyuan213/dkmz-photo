package com.dkmz.photo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导出流水（高清无水印导出一次落一条）。
 *
 * client_no 由前端生成（一次导出动作一个唯一号），唯一键保证「网络重试 / 用户连点」
 * 不会重复扣减张数 —— 幂等键必须由「动作」而不是「请求」决定，这一点和订单号同理。
 */
@Data
@TableName("photo_export")
public class PhotoExport {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 前端生成的幂等号（唯一） */
    private String clientNo;

    private String openid;

    /** 导出规格（exam id / preset id），仅用于统计 */
    private String specId;

    /** 本次消耗来源：free / paid / vip */
    private String source;

    /** 规格校验是否通过（未通过也允许导出，但据此统计「用户常被什么坑到」） */
    private Boolean specOk;

    private LocalDateTime createdAt;
}
