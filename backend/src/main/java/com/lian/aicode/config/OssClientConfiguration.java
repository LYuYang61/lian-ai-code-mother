package com.lian.aicode.config;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.credentials.EnvironmentVariableCredentialsProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** OSS 默认关闭；启用时尽早检查必要配置，并复用单例客户端。 */
@Configuration
@ConditionalOnProperty(prefix = "oss.client", name = "enabled", havingValue = "true")
public class OssClientConfiguration {

    @Bean(destroyMethod = "close")
    public OSSClient ossClient(OssClientProperties properties) {
        validate(properties);
        if (!StringUtils.hasText(System.getenv("OSS_ACCESS_KEY_ID"))
                || !StringUtils.hasText(System.getenv("OSS_ACCESS_KEY_SECRET"))) {
            throw new IllegalStateException(
                    "OSS 已启用，但进程环境变量 OSS_ACCESS_KEY_ID 和 OSS_ACCESS_KEY_SECRET 未完整配置");
        }

        var builder = OSSClient.newBuilder()
                .credentialsProvider(new EnvironmentVariableCredentialsProvider())
                .region(properties.getRegion().trim());
        if (StringUtils.hasText(properties.getEndpoint())) {
            builder.endpoint(properties.getEndpoint().trim());
        }
        if (properties.isUseCName()) {
            builder.useCName(true);
        }
        return builder.build();
    }

    private void validate(OssClientProperties properties) {
        if (!StringUtils.hasText(properties.getRegion())
                || !StringUtils.hasText(properties.getBucket())
                || !StringUtils.hasText(properties.getPublicBaseUrl())) {
            throw new IllegalStateException(
                    "OSS 已启用，但 OSS_REGION、OSS_BUCKET、OSS_PUBLIC_BASE_URL 未完整配置");
        }
        validateHttpUrl(properties.getPublicBaseUrl(), "OSS_PUBLIC_BASE_URL");
        if (StringUtils.hasText(properties.getEndpoint())) {
            validateHttpUrl(properties.getEndpoint(), "OSS_ENDPOINT");
        }
        if (properties.isUseCName() && !StringUtils.hasText(properties.getEndpoint())) {
            throw new IllegalStateException("OSS_USE_CNAME=true 时必须同时配置 OSS_ENDPOINT");
        }
    }

    private void validateHttpUrl(String value, String propertyName) {
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            if (!StringUtils.hasText(scheme)
                    || !Set.of("http", "https").contains(scheme.toLowerCase(Locale.ROOT))
                    || !StringUtils.hasText(uri.getHost())
                    || StringUtils.hasText(uri.getUserInfo())
                    || StringUtils.hasText(uri.getQuery())
                    || StringUtils.hasText(uri.getFragment())) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new IllegalStateException(propertyName + " 必须是合法的 HTTP(S) URL，且不能带凭据、查询参数或片段");
        }
    }
}
