package com.dkmz.photo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 登科喵证件照后端。
 *
 * 说明：本项目**不需要定时任务**（无推送类业务），因此云托管可以把最小实例数设为 0，
 * 由支付回调/用户请求唤醒，成本更低。虚拟支付发货的兜底查单由外部定时任务
 * （云托管「定时任务」调 POST /api/photo/pay/recheck?token=xxx）触发。
 */
@MapperScan("com.dkmz.photo.mapper")
@SpringBootApplication
public class DkmzPhotoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DkmzPhotoApplication.class, args);
    }
}
