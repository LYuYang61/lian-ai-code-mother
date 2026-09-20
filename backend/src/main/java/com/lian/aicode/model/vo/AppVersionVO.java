package com.lian.aicode.model.vo;

import lombok.Builder;
import lombok.Data;

/** 对外版本信息，不泄露服务端绝对文件路径。 */
@Data
@Builder
public class AppVersionVO {

    private Long id;
    private Long appId;
    private Integer versionNo;
    private String codeGenType;
    private String status;
    private String description;
    private String prompt;
    private String previewUrl;
    private String createTime;
}
