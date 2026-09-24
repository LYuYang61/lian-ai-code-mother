package com.lian.aicode.workflow.tool;

import com.lian.aicode.config.AiWorkflowProperties;
import com.lian.aicode.manager.OssManager;
import com.lian.aicode.workflow.model.ImageCategoryEnum;
import com.lian.aicode.workflow.model.ImageResource;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 将受限 Mermaid 源码转换成 SVG，并上传到已配置的阿里云 OSS。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MermaidDiagramTool {

    private final AiWorkflowProperties properties;
    private final OssManager ossManager;

    @Tool("把 Mermaid 架构图源码转换为 SVG 图片并保存到阿里云 OSS")
    public List<ImageResource> generateMermaidDiagram(@P("Mermaid 图表源码") String mermaidCode,
                                                      @P("架构图描述") String description) {
        if (!properties.isEnabled() || !StringUtils.hasText(mermaidCode)) {
            return List.of();
        }
        String source = mermaidCode.trim();
        if (source.length() > properties.getMermaid().safeMaxSourceChars()) {
            log.warn("Mermaid 图生成跳过：reason=源码超出长度限制");
            return List.of();
        }
        if (!ossManager.isAvailable()) {
            log.warn("Mermaid 图生成跳过：reason=阿里云 OSS 未启用");
            return List.of();
        }
        Path tempDirectory = null;
        long startedAt = System.nanoTime();
        try {
            tempDirectory = Files.createTempDirectory("lian-ai-workflow-mermaid-");
            Path input = tempDirectory.resolve("diagram.mmd");
            Path output = tempDirectory.resolve("diagram.svg");
            Files.writeString(input, source, StandardCharsets.UTF_8);
            int exitCode = runCli(input, output);
            if (exitCode != 0 || !Files.isRegularFile(output) || Files.size(output) == 0) {
                log.warn("Mermaid CLI 执行失败：exitCode={}, durationMs={}", exitCode,
                        elapsedMillis(startedAt));
                return List.of();
            }
            String url = ossManager.upload(properties.safeStoragePrefix() + "/mermaid/"
                            + UUID.randomUUID() + ".svg",
                    output, "image/svg+xml");
            log.info("Mermaid 架构图生成完成：descriptionLength={}, durationMs={}, result=成功",
                    description == null ? 0 : Math.min(description.length(), 200), elapsedMillis(startedAt));
            return List.of(ImageResource.builder()
                    .category(ImageCategoryEnum.ARCHITECTURE)
                    .description(trimDescription(description))
                    .url(url)
                    .build());
        } catch (Exception exception) {
            log.warn("Mermaid 架构图生成异常：reason={}, durationMs={}",
                    exception.getClass().getSimpleName(), elapsedMillis(startedAt));
            return List.of();
        } finally {
            deleteRecursively(tempDirectory);
        }
    }

    private int runCli(Path input, Path output) throws IOException, InterruptedException {
        String command = properties.getMermaid().getCommand();
        if (!StringUtils.hasText(command)) {
            return -1;
        }
        if (isWindows() && "mmdc".equalsIgnoreCase(command.trim())) {
            command = "mmdc.cmd";
        }
        Process process = new ProcessBuilder(List.of(command, "-i", input.toString(), "-o", output.toString(),
                "-b", "transparent"))
                .redirectErrorStream(true)
                .start();
        Thread outputDrain = Thread.startVirtualThread(() -> {
            try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                while (reader.readLine() != null) {
                    // 只消费输出避免子进程阻塞，不记录 CLI 的完整输出。
                }
            } catch (IOException ignored) {
                // 主线程使用退出码判断结果。
            }
        });
        try {
            Duration timeout = properties.getMermaid().getTimeout();
            boolean finished = process.waitFor(timeout == null ? 30_000 : timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                outputDrain.interrupt();
                return -2;
            }
            outputDrain.join(2_000);
            return process.exitValue();
        } finally {
            // Future.cancel(true) 或用户停止工作流可能中断等待线程，不能让 Mermaid 子进程脱离任务生命周期。
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            outputDrain.interrupt();
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    private String trimDescription(String description) {
        String value = StringUtils.hasText(description) ? description.trim() : "网站架构图";
        return value.substring(0, Math.min(value.length(), 200));
    }

    private void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    log.warn("清理 Mermaid 临时文件失败：file={}, reason={}", path.getFileName(),
                            exception.getClass().getSimpleName());
                }
            });
        } catch (IOException exception) {
            log.warn("扫描 Mermaid 临时目录失败：reason={}", exception.getClass().getSimpleName());
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
