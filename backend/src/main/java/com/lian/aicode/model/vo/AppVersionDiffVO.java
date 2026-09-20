package com.lian.aicode.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/** 两个代码版本的轻量文本差异。 */
@Data
@Builder
public class AppVersionDiffVO {

    private Long appId;
    private Integer fromVersion;
    private Integer toVersion;
    private Map<String, String> files;
}
