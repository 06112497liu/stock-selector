package com.aistock.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot 入口:半自动选股工具的 Thymeleaf 前端。
 *
 * <p><b>产品定位</b>:本工具只给「该买 / 继续持有 / 该卖」纯名单建议,
 * 人工去券商下单——不自动下单、不记账、不按用户资产算股数金额。
 * 默认策略 alpha 未经样本外验证,页面所有结论仅作学习 / 框架演示,不构成投资建议。
 *
 * <p>{@code @EnableScheduling} 开启定时任务基础设施;具体的每日推送调度器
 * {@link com.aistock.schedule.DailySignalScheduler} 仅在 {@code scheduler.enabled=true}
 * 时才注册(默认 false),故本地 / 测试默认无任何定时副作用。
 */
@SpringBootApplication
@EnableScheduling
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
