package com.lian.aicode.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 应用对外视图，聚合所有者和当前生成/部署状态。
 *
 * <p>NoArgs/AllArgs 构造器供 Jackson 反序列化使用（精选列表缓存值包含本视图）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppVO {

    private Long id;
    private String appName;
    private String cover;
    private String initPrompt;
    private String codeGenType;
    private String deployKey;
    private String deployedTime;
    private Integer priority;
    private Long userId;
    private String visibility;
    private String category;
    private String tags;
    private String generationStatus;
    private Integer currentVersion;
    private Integer deployedVersion;
    private Integer conversationRounds;
    private Integer downloadCount;
    private String generationMessage;
    private String featuredStatus;
    private String featuredReason;
    private String deploymentStatus;
    private String createTime;
    private String updateTime;
    private UserVO owner;
    private String previewUrl;
    private String deployUrl;
}
