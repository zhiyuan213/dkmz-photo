package com.dkmz.photo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 登科喵证件照后端。
 *
 * 定时清理内存中的短时照片缓存；支付兜底查单仍由云托管外部定时任务触发。
 */
@MapperScan("com.dkmz.photo.mapper")
@SpringBootApplication
@EnableScheduling
public class DkmzPhotoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DkmzPhotoApplication.class, args);
    }
}
