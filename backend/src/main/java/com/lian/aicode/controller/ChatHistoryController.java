package com.lian.aicode.controller;

import com.lian.aicode.common.BaseResponse;
import com.lian.aicode.common.ResultUtils;
import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import com.lian.aicode.model.dto.chathistory.ChatHistoryQueryRequest;
import com.lian.aicode.model.entity.UserAccount;
import com.lian.aicode.model.vo.ChatHistoryStatsVO;
import com.lian.aicode.model.vo.ChatHistoryVO;
import com.lian.aicode.model.vo.ChatSummaryVO;
import com.lian.aicode.model.vo.CursorPageResult;
import com.lian.aicode.model.vo.PageResult;
import com.lian.aicode.service.ChatHistoryService;
import com.lian.aicode.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/** 对话历史查询、管理、导出和摘要接口。 */
@Slf4j
@Tag(name = "对话历史接口")
@RestController
@RequestMapping("/chatHistory")
@RequiredArgsConstructor
public class ChatHistoryController {

    private final ChatHistoryService chatHistoryService;
    private final UserService userService;

    @Value("${app.chat-history.cursor-page-size:10}")
    private int defaultPageSize;

    @Operation(summary = "按游标查询应用对话历史")
    @GetMapping("/app/{appId}")
    public BaseResponse<CursorPageResult<ChatHistoryVO>> listAppHistory(
            @PathVariable Long appId,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime lastCreateTime,
            @RequestParam(required = false) Long lastId,
            HttpServletRequest request) {
        UserAccount loginUser = userService.getLoginUser(request);
        int requestedPageSize = pageSize == null ? defaultPageSize : pageSize;
        return ResultUtils.success(chatHistoryService.listAppHistory(appId, requestedPageSize,
                lastCreateTime, lastId, loginUser));
    }

    @Operation(summary = "查询应用对话统计")
    @GetMapping("/app/{appId}/stats")
    public BaseResponse<ChatHistoryStatsVO> stats(@PathVariable Long appId, HttpServletRequest request) {
        return ResultUtils.success(chatHistoryService.stats(appId, userService.getLoginUser(request)));
    }

    @Operation(summary = "导出应用对话为 Markdown")
    @GetMapping("/app/{appId}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long appId, HttpServletRequest request) {
        byte[] content = chatHistoryService.exportMarkdown(appId, userService.getLoginUser(request));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "markdown", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("chat-history-" + appId + ".md", StandardCharsets.UTF_8).build());
        log.info("导出对话历史：appId={}, bytes={}", appId, content.length);
        return ResponseEntity.ok().headers(headers).body(content);
    }

    @Operation(summary = "生成应用对话摘要")
    @PostMapping("/app/{appId}/summarize")
    public BaseResponse<ChatSummaryVO> summarize(@PathVariable Long appId, HttpServletRequest request) {
        return ResultUtils.success(chatHistoryService.summarize(appId, userService.getLoginUser(request)));
    }

    @Operation(summary = "管理员分页查询全部对话历史")
    @PostMapping("/admin/list/page/vo")
    public BaseResponse<PageResult<ChatHistoryVO>> adminList(@Valid @RequestBody ChatHistoryQueryRequest query,
                                                              HttpServletRequest request) {
        UserAccount admin = requireAdmin(request);
        return ResultUtils.success(chatHistoryService.listAdmin(query, admin));
    }

    @Operation(summary = "管理员删除对话历史")
    @DeleteMapping("/admin/delete")
    public BaseResponse<Boolean> adminDelete(@RequestParam Long id, HttpServletRequest request) {
        return ResultUtils.success(chatHistoryService.deleteMessage(id, requireAdmin(request)));
    }

    private UserAccount requireAdmin(HttpServletRequest request) {
        UserAccount user = userService.getLoginUser(request);
        if (!userService.isAdmin(user)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "需要管理员权限");
        }
        return user;
    }
}
