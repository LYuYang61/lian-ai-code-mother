package com.lian.aicode.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 网页封面截图和补偿任务配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "app.screenshot")
public class ScreenshotProperties {

    /** 是否在部署后生成并上传应用封面。 */
    private boolean enabled;

    /** 是否开启缺失封面重试和临时文件定时清理。 */
    private boolean schedulingEnabled;

    private int maxConcurrent = 1;
    private int queueCapacity = 20;
    private int viewportWidth = 1600;
    private int viewportHeight = 900;
    private Duration pageLoadTimeout = Duration.ofSeconds(30);
    private Duration waitTimeout = Duration.ofSeconds(15);
    private Duration tempFileTtl = Duration.ofHours(2);
    private Duration failureRetryDelay = Duration.ofMinutes(5);
    private String tempRoot;
    private String allowedHosts = "localhost,127.0.0.1";
    private int retryBatchSize = 20;
}
