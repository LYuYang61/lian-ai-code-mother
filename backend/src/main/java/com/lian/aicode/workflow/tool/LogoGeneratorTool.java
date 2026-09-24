package com.lian.aicode.workflow.tool;

import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import com.lian.aicode.config.AiWorkflowProperties;
import com.lian.aicode.manager.OssManager;
import com.lian.aicode.workflow.model.ImageCategoryEnum;
import com.lian.aicode.workflow.model.ImageResource;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 阿里云 Model Studio 文生图 Logo 工具；生成的临时 URL 会下载后再存入阿里云 OSS。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogoGeneratorTool {

    private final AiWorkflowProperties properties;
    private final OssManager ossManager;

    @Value("${DASHSCOPE_API_KEY:}")
    private String apiKey;

    @Tool("根据品牌、行业和视觉风格描述生成不含文字的 Logo 图片")
    public List<ImageResource> generateLogos(@P("Logo 设计描述") String description) {
        if (!properties.isEnabled() || !properties.getDashscope().isEnabled()
                || !StringUtils.hasText(apiKey)) {
            log.info("跳过 Logo 生成：reason=DashScope 未配置或能力未启用");
            return List.of();
        }
        if (!ossManager.isAvailable()) {
            log.warn("跳过 Logo 生成：reason=阿里云 OSS 未启用，拒绝保存临时第三方 URL");
            return List.of();
        }
        String safeDescription = StringUtils.hasText(description) ? description.trim() : "简洁现代的品牌图形";
        safeDescription = safeDescription.substring(0, Math.min(safeDescription.length(), 500));
        long startedAt = System.nanoTime();
        try {
            String prompt = "生成一个不包含任何文字的 Logo 图形。品牌描述：" + safeDescription;
            ImageSynthesisParam param = ImageSynthesisParam.builder()
                    .apiKey(apiKey.trim())
                    .model(properties.getDashscope().getModel())
                    .prompt(prompt)
                    .size("512*512")
                    .n(1)
                    .build();
            ImageSynthesisResult result = new ImageSynthesis().call(param);
            if (result == null || result.getOutput() == null || result.getOutput().getResults() == null) {
                log.warn("Logo 生成失败：reason=模型无图片结果, durationMs={}", elapsedMillis(startedAt));
                return List.of();
            }
            for (Map<String, String> item : result.getOutput().getResults()) {
                String temporaryUrl = item == null ? null : item.get("url");
                if (!StringUtils.hasText(temporaryUrl)) {
                    continue;
                }
                Path downloaded = downloadImage(temporaryUrl);
                try {
                    String ossUrl = ossManager.upload(properties.safeStoragePrefix() + "/logo/"
                                    + UUID.randomUUID() + ".png",
                            downloaded, "image/png");
                    log.info("Logo 生成并保存完成：durationMs={}, result=成功", elapsedMillis(startedAt));
                    return List.of(ImageResource.builder()
                            .category(ImageCategoryEnum.LOGO)
                            .description(safeDescription)
                            .url(ossUrl)
                            .build());
                } finally {
                    Files.deleteIfExists(downloaded);
                }
            }
            return List.of();
        } catch (Exception exception) {
            // 类名不足以区分协议不兼容、模型不存在和权限问题，必须带消息（与质检节点同一教训）。
            String detail = exception.getMessage() == null ? "" : exception.getMessage();
            log.warn("Logo 生成异常：reason={}, message={}, durationMs={}",
                    exception.getClass().getSimpleName(),
                    detail.substring(0, Math.min(300, detail.length())),
                    elapsedMillis(startedAt));
            return List.of();
        }
    }

    private Path downloadImage(String imageUrl) throws IOException, InterruptedException {
        URI uri = URI.create(imageUrl);
        if (!List.of("http", "https").contains(uri.getScheme())) {
            throw new IllegalArgumentException("图片 URL 协议不受支持");
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.getDashscope().getTimeout())
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2 || response.body().length == 0 || response.body().length > 5 * 1024 * 1024) {
            throw new IOException("Logo 图片下载结果不符合限制");
        }
        Path file = Files.createTempFile("lian-ai-logo-", ".png");
        Files.write(file, response.body());
        return file;
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
