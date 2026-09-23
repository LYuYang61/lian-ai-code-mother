package com.lian.aicode.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 阿里云 OSS 客户端配置；访问密钥不进入 Spring 配置对象。 */
@Data
@Component
@ConfigurationProperties(prefix = "oss.client")
public class OssClientProperties {

    private boolean enabled;
    private String region;
    private String bucket;
    private String endpoint;
    private String publicBaseUrl;
    private boolean useCName;
}
