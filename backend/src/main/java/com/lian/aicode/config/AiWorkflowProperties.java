package com.lian.aicode.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 第九期 AI 工作流的边界配置。
 *
 * <p>外部图片服务、Mermaid CLI 和文生图都属于可选能力；配置缺失时工作流会跳过对应素材，
 * 不会把密钥写入配置对象日志，也不会阻止基础后端启动。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai-workflow")
public class AiWorkflowProperties {

    private boolean enabled = true;
    private int maxQualityRetries = 2;
    private boolean qualityCheckEnabled = true;
    private boolean qualityFailOpen = true;
    /**
     * 质检重试耗尽后的处置：false（默认，软失败）时构建成功即放行，遗留问题作为质量警告透出；
     * true 时维持硬失败，整轮作废。2026-09-24 实测：小需求修改轮为存量代码问题重试三轮
     * 共 227 秒后全废，质检对迭代修改应是质量增强而不是硬门槛。
     */
    private boolean qualityHardFail = false;
    private int qualityMaxCodeChars = 120_000;
    private int qualityMaxFiles = 80;
    /** 工作流自有素材在阿里云 OSS 中的对象前缀，不是本地绝对路径。 */
    private String storagePrefix = "workflow";
    private ImageCollection imageCollection = new ImageCollection();
    private Mermaid mermaid = new Mermaid();
    private Dashscope dashscope = new Dashscope();

    public int safeMaxQualityRetries() {
        return Math.min(Math.max(maxQualityRetries, 0), 5);
    }

    public int safeQualityMaxCodeChars() {
        return Math.min(Math.max(qualityMaxCodeChars, 10_000), 1_000_000);
    }

    public int safeQualityMaxFiles() {
        return Math.min(Math.max(qualityMaxFiles, 1), 500);
    }

    public String safeStoragePrefix() {
        if (storagePrefix == null || storagePrefix.isBlank()) {
            return "workflow";
        }
        String normalized = storagePrefix.trim().replace('\\', '/').replaceAll("^/+|/+$", "");
        if (normalized.isBlank() || normalized.startsWith(".") || normalized.contains("..")) {
            return "workflow";
        }
        return normalized;
    }

    @Data
    public static class ImageCollection {
        private boolean enabled = true;
        private int maxPlanTasks = 6;
        private int maxImages = 24;
        private int parallelism = 4;
        private Duration timeout = Duration.ofSeconds(45);

        public int safeMaxPlanTasks() {
            return Math.min(Math.max(maxPlanTasks, 0), 20);
        }

        public int safeMaxImages() {
            return Math.min(Math.max(maxImages, 0), 100);
        }

        public int safeParallelism() {
            return Math.min(Math.max(parallelism, 1), 16);
        }
    }

    @Data
    public static class Mermaid {
        private String command = "mmdc";
        private Duration timeout = Duration.ofSeconds(30);
        private int maxSourceChars = 20_000;

        public int safeMaxSourceChars() {
            return Math.min(Math.max(maxSourceChars, 100), 100_000);
        }
    }

    @Data
    public static class Dashscope {
        private boolean enabled = true;
        private String model = "wan2.2-t2i-flash";
        private Duration timeout = Duration.ofSeconds(90);
    }
}
