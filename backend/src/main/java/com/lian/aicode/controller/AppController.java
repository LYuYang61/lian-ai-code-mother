package com.lian.aicode.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lian.aicode.common.BaseResponse;
import com.lian.aicode.common.ResultUtils;
import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import com.lian.aicode.model.dto.app.AppAddRequest;
import com.lian.aicode.model.dto.app.AppAdminUpdateRequest;
import com.lian.aicode.model.dto.app.AppDeployRequest;
import com.lian.aicode.model.dto.app.AppFeaturedRequest;
import com.lian.aicode.model.dto.app.AppQueryRequest;
import com.lian.aicode.model.dto.app.AppUpdateRequest;
import com.lian.aicode.model.dto.app.AppVersionRequest;
import com.lian.aicode.model.dto.common.IdRequest;
import com.lian.aicode.model.entity.UserAccount;
import com.lian.aicode.model.vo.AppVersionDiffVO;
import com.lian.aicode.model.vo.AppVersionVO;
import com.lian.aicode.model.vo.AppVO;
import com.lian.aicode.model.vo.ChatHistoryVO;
import com.lian.aicode.model.vo.PageResult;
import com.lian.aicode.service.AppService;
import com.lian.aicode.service.GenerationCancelledException;
import com.lian.aicode.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 应用 CRUD、AI 生成、版本管理和部署接口。 */
@Tag(name = "应用接口")
@RestController
@RequestMapping("/app")
@RequiredArgsConstructor
public class AppController {

    private final AppService appService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    @Operation(summary = "SSE 流式生成应用代码")
    @GetMapping(value = "/chat/gen/code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatToGenCode(@RequestParam Long appId,
                                                       @RequestParam String message,
                                                       HttpServletRequest request) {
        UserAccount loginUser = userService.getLoginUser(request);
        Flux<String> contentFlux = appService.chatToGenCode(appId, message, loginUser);
        return contentFlux
                .map(this::chunkEvent)
                // done 只在门面完成“解析 + 保存 + 状态更新”后发送。
                .concatWith(Mono.just(doneEvent()))
                .onErrorResume(GenerationCancelledException.class,
                        error -> Flux.just(cancelledEvent()))
                .onErrorResume(error -> Flux.just(errorEvent(error)));
    }

    @Operation(summary = "停止应用生成")
    @PostMapping("/chat/stop")
    public BaseResponse<Boolean> stopGeneration(@Valid @RequestBody IdRequest request,
                                                HttpServletRequest httpRequest) {
        return ResultUtils.success(appService.stopGeneration(request.getId(), userService.getLoginUser(httpRequest)));
    }

    @Operation(summary = "创建应用")
    @PostMapping("/add")
    public BaseResponse<Long> add(@Valid @RequestBody AppAddRequest request, HttpServletRequest httpRequest) {
        return ResultUtils.success(appService.createApp(request, userService.getLoginUser(httpRequest)));
    }

    @Operation(summary = "更新自己的应用资料")
    @PostMapping("/update")
    public BaseResponse<Boolean> update(@Valid @RequestBody AppUpdateRequest request,
                                        HttpServletRequest httpRequest) {
        return ResultUtils.success(appService.updateApp(request, userService.getLoginUser(httpRequest)));
    }

    @Operation(summary = "删除应用及其版本、对话和文件")
    @PostMapping("/delete")
    public BaseResponse<Boolean> delete(@Valid @RequestBody IdRequest request, HttpServletRequest httpRequest) {
        return ResultUtils.success(appService.deleteApp(request.getId(), userService.getLoginUser(httpRequest)));
    }

    @Operation(summary = "查看应用详情")
    @GetMapping("/get/vo")
    public BaseResponse<AppVO> getVO(@RequestParam Long id, HttpServletRequest request) {
        return ResultUtils.success(appService.getAppVO(id, optionalLoginUser(request)));
    }

    @Operation(summary = "分页查询我的应用")
    @PostMapping("/my/list/page/vo")
    public BaseResponse<PageResult<AppVO>> listMy(@Valid @RequestBody AppQueryRequest request,
                                                  HttpServletRequest httpRequest) {
        return ResultUtils.success(appService.listMyApps(request, userService.getLoginUser(httpRequest)));
    }

    @Operation(summary = "分页查询精选公开应用")
    @PostMapping("/good/list/page/vo")
    public BaseResponse<PageResult<AppVO>> listGood(@Valid @RequestBody AppQueryRequest request) {
        return ResultUtils.success(appService.listFeaturedApps(request));
    }

    @Operation(summary = "申请精选")
    @PostMapping("/featured/apply")
    public BaseResponse<Boolean> applyFeatured(@Valid @RequestBody AppFeaturedRequest request,
                                               HttpServletRequest httpRequest) {
        return ResultUtils.success(appService.applyFeatured(request, userService.getLoginUser(httpRequest)));
    }

    @Operation(summary = "部署当前应用版本")
    @PostMapping("/deploy")
    public BaseResponse<String> deploy(@Valid @RequestBody AppDeployRequest request,
                                       HttpServletRequest httpRequest) {
        return ResultUtils.success(appService.deployApp(request.getAppId(), userService.getLoginUser(httpRequest)));
    }

    @Operation(summary = "暂停部署访问")
    @PostMapping("/deploy/disable")
    public BaseResponse<Boolean> disableDeploy(@Valid @RequestBody AppDeployRequest request,
                                               HttpServletRequest httpRequest) {
        return ResultUtils.success(appService.disableDeployment(request.getAppId(), userService.getLoginUser(httpRequest)));
    }

    @Operation(summary = "恢复部署访问")
    @PostMapping("/deploy/enable")
    public BaseResponse<String> enableDeploy(@Valid @RequestBody AppDeployRequest request,
                                             HttpServletRequest httpRequest) {
        return ResultUtils.success(appService.enableDeployment(request.getAppId(), userService.getLoginUser(httpRequest)));
    }

    @Operation(summary = "列出应用版本")
    @GetMapping("/version/list")
    public BaseResponse<java.util.List<AppVersionVO>> listVersions(@RequestParam Long appId,
                                                                    HttpServletRequest request) {
        return ResultUtils.success(appService.listVersions(appId, optionalLoginUser(request)));
    }

    @Operation(summary = "回滚应用版本")
    @PostMapping("/version/rollback")
    public BaseResponse<Boolean> rollback(@Valid @RequestBody AppVersionRequest request,
                                          HttpServletRequest httpRequest) {
        return ResultUtils.success(appService.rollback(request.getAppId(), request.getVersionNo(),
                userService.getLoginUser(httpRequest)));
    }

    @Operation(summary = "比较两个应用版本")
    @GetMapping("/version/diff")
    public BaseResponse<AppVersionDiffVO> diff(@RequestParam Long appId,
                                               @RequestParam Integer fromVersion,
                                               @RequestParam Integer toVersion,
                                               HttpServletRequest request) {
        return ResultUtils.success(appService.diff(appId, fromVersion, toVersion, optionalLoginUser(request)));
    }

    @Operation(summary = "查看应用对话历史")
    @GetMapping("/chat/history")
    public BaseResponse<java.util.List<ChatHistoryVO>> history(@RequestParam Long appId,
                                                               HttpServletRequest request) {
        return ResultUtils.success(appService.listChatHistory(appId, optionalLoginUser(request)));
    }

    @Operation(summary = "下载当前代码版本")
    @GetMapping("/download")
    public void download(@RequestParam Long appId,
                         HttpServletRequest request,
                         HttpServletResponse response) {
        Path directory = appService.getDownloadPath(appId, userService.getLoginUser(request));
        try {
            response.setContentType("application/zip");
            response.setHeader("Content-Disposition", ContentDisposition.attachment()
                    .filename("app-" + appId + ".zip", StandardCharsets.UTF_8).build().toString());
            writeZip(directory, response);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "下载代码失败", exception);
        }
    }

    @Operation(summary = "管理员分页查询应用")
    @PostMapping("/admin/list/page/vo")
    public BaseResponse<PageResult<AppVO>> adminList(@Valid @RequestBody AppQueryRequest request,
                                                     HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        return ResultUtils.success(appService.listAdminApps(request));
    }

    @Operation(summary = "管理员更新应用运营字段")
    @PostMapping("/admin/update")
    public BaseResponse<Boolean> adminUpdate(@Valid @RequestBody AppAdminUpdateRequest request,
                                             HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        return ResultUtils.success(appService.adminUpdateApp(request));
    }

    @Operation(summary = "管理员查看应用详情")
    @GetMapping("/admin/get/vo")
    public BaseResponse<AppVO> adminGet(@RequestParam Long id, HttpServletRequest httpRequest) {
        UserAccount admin = requireAdmin(httpRequest);
        return ResultUtils.success(appService.getAppVO(id, admin));
    }

    @Operation(summary = "管理员删除应用")
    @PostMapping("/admin/delete")
    public BaseResponse<Boolean> adminDelete(@Valid @RequestBody IdRequest request,
                                             HttpServletRequest httpRequest) {
        UserAccount admin = requireAdmin(httpRequest);
        return ResultUtils.success(appService.deleteApp(request.getId(), admin));
    }

    private UserAccount requireAdmin(HttpServletRequest request) {
        UserAccount user = userService.getLoginUser(request);
        if (!userService.isAdmin(user)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "需要管理员权限");
        }
        return user;
    }

    private UserAccount optionalLoginUser(HttpServletRequest request) {
        try {
            return userService.getLoginUser(request);
        } catch (BusinessException exception) {
            if (exception.getCode() == ErrorCode.NOT_LOGIN_ERROR.getCode()) {
                return null;
            }
            throw exception;
        }
    }

    private ServerSentEvent<String> chunkEvent(String chunk) {
        try {
            return ServerSentEvent.<String>builder()
                    .data(objectMapper.writeValueAsString(Map.of("d", chunk == null ? "" : chunk)))
                    .build();
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "SSE 数据编码失败", exception);
        }
    }

    private ServerSentEvent<String> doneEvent() {
        return ServerSentEvent.<String>builder().event("done").data("").build();
    }

    private ServerSentEvent<String> cancelledEvent() {
        return ServerSentEvent.<String>builder().event("cancelled").data("").build();
    }

    private ServerSentEvent<String> errorEvent(Throwable error) {
        String message = error instanceof BusinessException businessException
                ? businessException.getMessage() : "生成失败，请稍后重试";
        return ServerSentEvent.<String>builder().event("error")
                .data(toJson(Map.of("message", message == null ? "生成失败" : message))).build();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "{\"message\":\"生成失败\"}";
        }
    }

    private void writeZip(Path directory, HttpServletResponse response) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "代码目录不存在");
        }
        try (ZipOutputStream zip = new ZipOutputStream(response.getOutputStream(), StandardCharsets.UTF_8);
             var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path)).toList()) {
                Path relative = directory.relativize(path).normalize();
                if (relative.startsWith("..")) {
                    throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "非法下载路径");
                }
                zip.putNextEntry(new ZipEntry(relative.toString().replace('\\', '/')));
                Files.copy(path, zip);
                zip.closeEntry();
            }
            zip.finish();
        }
    }
}
