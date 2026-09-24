package com.lian.aicode.controller;

import com.lian.aicode.common.BaseResponse;
import com.lian.aicode.common.ResultUtils;
import com.lian.aicode.service.UserService;
import com.lian.aicode.workflow.CodeGenWorkflow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 工作流只读诊断接口；不执行模型、不读取用户提示词，仅返回当前状态图。 */
@Slf4j
@Tag(name = "AI 工作流接口")
@RestController
@RequestMapping("/workflow")
@RequiredArgsConstructor
public class WorkflowController {

    private final CodeGenWorkflow codeGenWorkflow;
    private final UserService userService;

    @Operation(summary = "查看 AI 工作流 Mermaid 图")
    @GetMapping("/graph")
    public BaseResponse<String> graph(HttpServletRequest request) {
        var user = userService.getLoginUser(request);
        String graph = codeGenWorkflow.graphMermaid();
        log.info("查看 AI 工作流图：actor={}, result=成功", user.getUserAccount());
        return ResultUtils.success(graph);
    }
}
