package com.lian.aicode.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 只有显式开启时才注册定时任务，避免基础开发环境无意义地启动调度线程。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.screenshot", name = "scheduling-enabled", havingValue = "true")
public class ScreenshotSchedulingConfiguration {
}
