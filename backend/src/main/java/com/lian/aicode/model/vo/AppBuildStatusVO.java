package com.lian.aicode.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Vue 工程构建状态视图（教程 11 期“构建状态查询接口”扩展思路）。
 *
 * <p>本项目采用同步打包：模型流结束前构建必须完成，因此 {@code isBuilding} 恒为 false，
 * 该接口主要用于诊断“预览 404/看到旧版”类问题的状态自查，而不是轮询构建进度。</p>
 */
@Data
@Builder
public class AppBuildStatusVO {

    private Long appId;

    private String codeGenType;

    /** 当前版本号；应用尚未生成过版本时为 0。 */
    private Integer versionNo;

    /** 工程源码目录是否存在。 */
    private boolean projectExists;

    /** 构建产物 dist/index.html 是否存在。 */
    private boolean distExists;

    /** 同步打包模式下恒为 false，保留字段是为了与异步构建方案兼容。 */
    private boolean building;

    /**
     * 状态：completed（构建产物就绪）/ pending（工程已生成、缺少构建产物）/
     * not_found（版本目录不存在）/ not_applicable（该代码生成类型无需构建）。
     */
    private String status;

    private String message;

    /** 构建产物最后修改时间（ISO-8601 字符串）；产物不存在时为 null。 */
    private String buildTime;
}
