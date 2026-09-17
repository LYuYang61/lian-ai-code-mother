package com.lian.aicode.controller;

import com.lian.aicode.common.BaseResponse;
import com.lian.aicode.common.ResultUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 初始化阶段用于确认服务、上下文路径和 OpenAPI 文档均可访问的健康接口。 */
@Tag(name = "系统接口")
@RestController
@RequestMapping("/health")
public class HealthController {

    @Operation(summary = "健康检查")
    @GetMapping("/")
    public BaseResponse<String> healthCheck() {
        return ResultUtils.success("ok");
    }
}
