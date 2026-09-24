package com.lian.aicode.workflow.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 工作流收集到的图片资源。
 *
 * <p>URL 来自外部服务或已配置的阿里云 OSS，只作为模型提示词中的数据，不作为服务器文件路径执行。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageResource implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private ImageCategoryEnum category;
    private String description;
    private String url;
}
