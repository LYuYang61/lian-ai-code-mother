package com.lian.aicode.manager;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.models.DeleteObjectRequest;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.aliyun.sdk.service.oss2.transport.BinaryData;
import com.lian.aicode.config.OssClientProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** 隔离阿里云 OSS SDK 的封面文件适配层。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OssManager {

    private final ObjectProvider<OSSClient> ossClientProvider;
    private final OssClientProperties properties;

    public boolean isAvailable() {
        return properties.isEnabled() && ossClientProvider.getIfAvailable() != null;
    }

    /** 上传 JPEG 封面并返回公开访问地址；密钥和完整 SDK 响应不会进入日志。 */
    public String upload(String key, Path file) {
        return upload(key, file, "image/jpeg");
    }

    /**
     * 上传受控类型的对象并返回公开访问地址。
     *
     * <p>工作流生成的 Mermaid 图使用 SVG，Logo 使用 PNG；统一从这里进入 OSS，
     * 避免各工具自行拼接对象路径或泄露临时第三方地址。</p>
     */
    public String upload(String key, Path file, String contentType) {
        OSSClient client = requireClient();
        String safeKey = normalizeKey(key);
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("OSS 上传文件不存在");
        }
        String safeContentType = normalizeContentType(contentType);
        try (var inputStream = Files.newInputStream(file)) {
            client.putObject(PutObjectRequest.newBuilder()
                    .bucket(properties.getBucket())
                    .key(safeKey)
                    .contentType(safeContentType)
                    .body(BinaryData.fromStream(inputStream, Files.size(file)))
                    .build());
        } catch (IOException exception) {
            throw new IllegalStateException("读取待上传的 OSS 封面文件失败", exception);
        }
        log.info("阿里云 OSS 对象上传完成：objectKey={}, contentType={}, result=成功",
                safeKey, safeContentType);
        return normalizePublicBaseUrl(properties.getPublicBaseUrl()) + "/" + safeKey;
    }

    /** 仅删除本项目配置的 OSS 公共域名下对象，避免把外部 URL 当作 OSS 对象删除。 */
    public boolean deleteByUrl(String url, Long appId) {
        if (!StringUtils.hasText(url) || !isAvailable()) {
            return false;
        }
        if (appId == null) {
            log.info("跳过阿里云 OSS 封面清理：result=缺少应用 ID");
            return false;
        }
        String prefix = normalizePublicBaseUrl(properties.getPublicBaseUrl()) + "/";
        if (!url.startsWith(prefix) || url.indexOf('?') >= 0 || url.indexOf('#') >= 0) {
            log.info("跳过外部封面清理：result=非当前阿里云 OSS 公共域名");
            return false;
        }
        String key = normalizeKey(url.substring(prefix.length()));
        if (!key.startsWith("app-covers/" + appId + "/")) {
            log.info("跳过阿里云 OSS 封面清理：appId={}, result=对象不属于当前应用", appId);
            return false;
        }
        requireClient().deleteObject(DeleteObjectRequest.newBuilder()
                .bucket(properties.getBucket())
                .key(key)
                .build());
        log.info("阿里云 OSS 封面删除完成：objectKey={}, result=成功", key);
        return true;
    }

    private OSSClient requireClient() {
        OSSClient client = ossClientProvider.getIfAvailable();
        if (!properties.isEnabled() || client == null) {
            throw new IllegalStateException("阿里云 OSS 未启用或配置不完整");
        }
        return client;
    }

    private String normalizeKey(String key) {
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("OSS 对象键不能为空");
        }
        String normalized = key.trim().replace('\\', '/').replaceFirst("^/+", "");
        if (normalized.isBlank() || normalized.startsWith("/")
                || Arrays.stream(normalized.split("/"))
                .anyMatch(segment -> segment.isBlank() || ".".equals(segment) || "..".equals(segment))) {
            throw new IllegalArgumentException("OSS 对象键非法");
        }
        return normalized;
    }

    private String normalizePublicBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("OSS_PUBLIC_BASE_URL 未配置");
        }
        return baseUrl.trim().replaceAll("/+$", "");
    }

    private String normalizeContentType(String contentType) {
        String value = StringUtils.hasText(contentType) ? contentType.trim().toLowerCase() : "";
        if (!java.util.Set.of("image/jpeg", "image/png", "image/svg+xml", "image/webp")
                .contains(value)) {
            throw new IllegalArgumentException("OSS 内容类型不在图片白名单内");
        }
        return value;
    }
}
