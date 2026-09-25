package com.lian.aicode.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lian.aicode.ai.AiCodeGenTypeRoutingService;
import com.lian.aicode.ai.AiCodeGenTypeRoutingServiceFactory;
import com.lian.aicode.ai.AiCodeGeneratorService;
import com.lian.aicode.ai.AiCodeGeneratorServiceFactory;
import com.lian.aicode.ai.CodeGenTypeRoutingHeuristic;
import com.lian.aicode.ai.model.AppNameResult;
import com.lian.aicode.ai.model.message.StreamMessageTypeEnum;
import com.lian.aicode.core.AiCodeGeneratorFacade;
import com.lian.aicode.core.builder.VueProjectBuilder;
import com.lian.aicode.core.stream.StreamMessageHistoryFormatter;
import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import com.lian.aicode.mapper.AppMapper;
import com.lian.aicode.mapper.AppVersionMapper;
import com.lian.aicode.model.dto.app.AppAddRequest;
import com.lian.aicode.model.dto.app.AppAdminUpdateRequest;
import com.lian.aicode.model.dto.app.AppCollaboratorRequest;
import com.lian.aicode.model.dto.app.AppFeaturedRequest;
import com.lian.aicode.model.dto.app.AppQueryRequest;
import com.lian.aicode.model.dto.app.AppUpdateRequest;
import com.lian.aicode.model.entity.App;
import com.lian.aicode.model.entity.AppVersion;
import com.lian.aicode.model.entity.UserAccount;
import com.lian.aicode.model.enums.AppDeploymentStatusEnum;
import com.lian.aicode.model.enums.AppFeaturedStatusEnum;
import com.lian.aicode.model.enums.AppGenerationStatusEnum;
import com.lian.aicode.model.enums.AppVersionStatusEnum;
import com.lian.aicode.model.enums.AppVisibilityEnum;
import com.lian.aicode.model.enums.ChatHistoryMessageTypeEnum;
import com.lian.aicode.model.enums.CodeGenTypeEnum;
import com.lian.aicode.model.vo.AppBuildStatusVO;
import com.lian.aicode.model.vo.AppVersionDiffVO;
import com.lian.aicode.model.vo.AppVersionVO;
import com.lian.aicode.model.vo.AppCollaboratorVO;
import com.lian.aicode.model.vo.AppVO;
import com.lian.aicode.model.vo.ChatHistoryVO;
import com.lian.aicode.model.vo.PageResult;
import com.lian.aicode.model.vo.UserVO;
import com.lian.aicode.service.AppService;
import com.lian.aicode.service.AppCollaboratorService;
import com.lian.aicode.service.AppStorageService;
import com.lian.aicode.service.ChatHistoryService;
import com.lian.aicode.service.GenerationCancelledException;
import com.lian.aicode.service.GenerationTaskManager;
import com.lian.aicode.service.ScreenshotService;
import com.lian.aicode.service.UserService;
import com.lian.aicode.workflow.CodeGenWorkflow;
import com.lian.aicode.workflow.model.WorkflowRequest;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 应用核心业务实现。
 *
 * <p>生成任务的数据库状态、代码版本目录和 SSE 流在同一个业务服务中编排：只有模型流完整结束、
 * 解析器成功且固定文件保存成功，版本才会变成 {@code ready}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    private static final long MAX_USER_PAGE_SIZE = 20;
    private static final long MAX_ADMIN_PAGE_SIZE = 200;
    private static final int MAX_DIFF_FILES = 100;
    private static final long MAX_DIFF_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DIFF_LINES = 2_000;
    private static final int MAX_CHAT_HISTORY_MESSAGE_LENGTH = 20_000;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final char[] DEPLOY_KEY_CHARS =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private final AppVersionMapper appVersionMapper;
    private final UserService userService;
    private final AppCollaboratorService collaboratorService;
    private final ChatHistoryService chatHistoryService;
    private final AiCodeGeneratorFacade aiCodeGeneratorFacade;
    private final ObjectProvider<AiCodeGeneratorServiceFactory> aiCodeGeneratorServiceFactoryProvider;
    private final ObjectProvider<AiCodeGeneratorService> aiCodeGeneratorServiceProvider;
    private final ObjectProvider<AiCodeGenTypeRoutingServiceFactory> aiCodeGenTypeRoutingServiceFactoryProvider;
    private final ObjectProvider<AiCodeGenTypeRoutingService> aiCodeGenTypeRoutingServiceProvider;
    private final ObjectMapper objectMapper;
    private final AppStorageService storageService;
    private final GenerationTaskManager taskManager;
    private final StreamMessageHistoryFormatter streamMessageHistoryFormatter;
    private final VueProjectBuilder vueProjectBuilder;
    private final ScreenshotService screenshotService;
    private final CodeGenWorkflow codeGenWorkflow;

    @Value("${app.storage.max-prompt-length:10000}")
    private int maxPromptLength;

    @Value("${app.deploy.public-base-url:http://localhost:8123/api/site}")
    private String deployPublicBaseUrl;

    @Value("${app.preview.public-base-url:http://localhost:8123/api/preview}")
    private String previewPublicBaseUrl;

    @Value("${server.servlet.context-path:/api}")
    private String serverContextPath;

    @Override
    public Long createApp(AppAddRequest request, UserAccount loginUser) {
        requireLogin(loginUser);
        String prompt = normalizePrompt(request.getInitPrompt());
        CodeGenTypeRoutingDecision routingDecision = resolveCodeGenType(
                request.getCodeGenType(), prompt, loginUser.getUserAccount());
        CodeGenTypeEnum codeGenType = routingDecision.type();
        String visibility = normalizeVisibility(request.getVisibility());

        App app = new App();
        // 应用命名是外部模型调用，不能放在数据库事务里持有连接；创建阶段只有一条应用记录写入。
        app.setAppName(generateAppName(prompt, loginUser.getUserAccount()));
        app.setInitPrompt(prompt);
        app.setCodeGenType(codeGenType.getValue());
        app.setUserId(loginUser.getId());
        app.setVisibility(visibility);
        app.setCategory(trimToNull(request.getCategory()));
        app.setTags(normalizeTags(request.getTags()));
        app.setPriority(0);
        app.setGenerationStatus(AppGenerationStatusEnum.DRAFT.getValue());
        app.setCurrentVersion(0);
        app.setConversationRounds(0);
        app.setFeaturedStatus(AppFeaturedStatusEnum.NONE.getValue());
        app.setDeploymentStatus(AppDeploymentStatusEnum.UNDEPLOYED.getValue());
        app.setCreateTime(LocalDateTime.now());
        app.setUpdateTime(app.getCreateTime());
        app.setEditTime(app.getCreateTime());
        app.setIsDelete(0);
        if (!save(app)) {
            log.error("创建应用失败：actor={}, type={}, result=失败", loginUser.getUserAccount(), codeGenType.getValue());
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "创建应用失败");
        }
        log.info("创建应用成功：actor={}, appId={}, type={}, typeSource={}, visibility={}, result=成功",
                loginUser.getUserAccount(), app.getId(), codeGenType.getValue(), routingDecision.source(), visibility);
        return app.getId();
    }

    @Override
    public AppVO getAppVO(Long appId, UserAccount loginUser) {
        App app = requireApp(appId);
        assertReadable(app, loginUser);
        return toAppVO(app);
    }

    @Override
    public PageResult<AppVO> listMyApps(AppQueryRequest request, UserAccount loginUser) {
        requireLogin(loginUser);
        QueryWrapper wrapper = buildQueryWrapper(request);
        wrapper.eq("user_id", loginUser.getId());
        return pageToVO(request, wrapper, MAX_USER_PAGE_SIZE);
    }

    @Override
    public PageResult<AppVO> listCollaboratedApps(AppQueryRequest request, UserAccount loginUser) {
        requireLogin(loginUser);
        List<Long> appIds = collaboratorService.listCollaboratedAppIds(loginUser.getId());
        if (appIds.isEmpty()) {
            return new PageResult<>(List.of(), 1, 12, 0, 0);
        }
        // 协作应用的所有者是别人，不能套用“我的应用”的 user_id 过滤；用 id 集合限定范围。
        QueryWrapper wrapper = buildQueryWrapper(request).in("id", appIds);
        return pageToVO(request, wrapper, MAX_USER_PAGE_SIZE);
    }

    @Override
    public PageResult<AppVO> listFeaturedApps(AppQueryRequest request) {
        // 精选列表始终先按 priority 降序，确保 999 等置顶值真的排在 99 精选之前；
        // 普通查询的 sortField 不能意外破坏运营置顶语义。
        QueryWrapper wrapper = buildQueryWrapper(request, true);
        wrapper.eq("visibility", AppVisibilityEnum.PUBLIC.getValue())
                // 新一轮生成失败或取消时，仍保留上一个可用版本的公开访问能力。
                .gt("current_version", 0)
                // approved 表示精选申请通过，priority >= 99 兼容管理员直接精选/置顶。
                .and("(featured_status = ? OR priority >= ?)",
                        AppFeaturedStatusEnum.APPROVED.getValue(), 99);
        return pageToVO(request, wrapper, MAX_USER_PAGE_SIZE);
    }

    @Override
    public PageResult<AppVO> listAdminApps(AppQueryRequest request) {
        return pageToVO(request, buildQueryWrapper(request), MAX_ADMIN_PAGE_SIZE);
    }

    @Override
    @Transactional
    public boolean updateApp(AppUpdateRequest request, UserAccount loginUser) {
        requireLogin(loginUser);
        App app = requireApp(request.getId());
        // 普通资料接口只允许创建者修改；管理员使用 /app/admin/update，避免权限边界被复用接口绕过。
        assertOwner(app, loginUser);
        String previousCover = app.getCover();
        App update = new App();
        update.setId(app.getId());
        if (request.getAppName() != null) {
            String name = request.getAppName().trim();
            if (name.isBlank() || name.length() > 64) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用名称不能为空且不能超过 64 个字符");
            }
            update.setAppName(name);
        }
        if (request.getCover() != null) {
            update.setCover(trimToNull(request.getCover()));
        }
        if (request.getCategory() != null) {
            update.setCategory(trimToNull(request.getCategory()));
        }
        if (request.getTags() != null) {
            update.setTags(normalizeTags(request.getTags()));
        }
        if (request.getVisibility() != null) {
            update.setVisibility(normalizeVisibility(request.getVisibility()));
        }
        update.setEditTime(LocalDateTime.now());
        update.setUpdateTime(update.getEditTime());
        boolean result = updateById(update);
        if (result && request.getCover() != null
                && !Objects.equals(previousCover, update.getCover())) {
            registerReplacedCoverCleanup(app.getId(), previousCover, loginUser.getUserAccount());
        }
        log.info("更新应用资料：actor={}, appId={}, result={}", loginUser.getUserAccount(), app.getId(),
                result ? "成功" : "失败");
        return result;
    }

    @Override
    @Transactional
    public boolean adminUpdateApp(AppAdminUpdateRequest request, UserAccount operator) {
        App app = requireApp(request.getId());
        String previousCover = app.getCover();
        App update = new App();
        update.setId(app.getId());
        if (request.getAppName() != null) {
            String name = request.getAppName().trim();
            if (name.isBlank() || name.length() > 64) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用名称不能为空且不能超过 64 个字符");
            }
            update.setAppName(name);
        }
        if (request.getCover() != null) {
            update.setCover(trimToNull(request.getCover()));
        }
        if (request.getCategory() != null) {
            update.setCategory(trimToNull(request.getCategory()));
        }
        if (request.getTags() != null) {
            update.setTags(normalizeTags(request.getTags()));
        }
        if (request.getVisibility() != null) {
            update.setVisibility(normalizeVisibility(request.getVisibility()));
        }
        if (request.getPriority() != null) {
            if (request.getPriority() < 0 || request.getPriority() > 9999) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "优先级范围应为 0-9999");
            }
            update.setPriority(request.getPriority());
        }
        if (request.getFeaturedStatus() != null) {
            String status = request.getFeaturedStatus().trim().toLowerCase(Locale.ROOT);
            if (Arrays.stream(AppFeaturedStatusEnum.values()).noneMatch(item -> item.getValue().equals(status))) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "精选状态无效");
            }
            String effectiveVisibility = request.getVisibility() == null
                    ? app.getVisibility() : request.getVisibility().trim().toLowerCase(Locale.ROOT);
            if (AppFeaturedStatusEnum.APPROVED.getValue().equals(status)
                    && (!AppVisibilityEnum.PUBLIC.getValue().equals(effectiveVisibility)
                    || app.getCurrentVersion() == null || app.getCurrentVersion() <= 0)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "只有公开且生成完成的应用可以设为精选");
            }
            if (AppFeaturedStatusEnum.APPROVED.getValue().equals(status)) {
                // 不能仅凭 current_version 数字审核精选，必须确认对应目录仍然存在且可用。
                requireReadyVersion(app.getId(), app.getCurrentVersion());
            }
            update.setFeaturedStatus(status);
            update.setFeaturedReason(trimToNull(request.getFeaturedReason()));
            if (AppFeaturedStatusEnum.APPROVED.getValue().equals(status)
                    && (request.getPriority() == null || request.getPriority() < 99)) {
                update.setPriority(99);
            }
            if (AppFeaturedStatusEnum.REJECTED.getValue().equals(status)) {
                update.setPriority(0);
            }
        }
        update.setEditTime(LocalDateTime.now());
        update.setUpdateTime(update.getEditTime());
        boolean result = updateById(update);
        if (result && request.getCover() != null
                && !Objects.equals(previousCover, update.getCover())) {
            registerReplacedCoverCleanup(app.getId(), previousCover,
                    operator == null ? "<unknown>" : operator.getUserAccount());
        }
        log.info("管理员更新应用运营字段：actor={}, appId={}, featuredStatus={}, priority={}, result={}",
                operator == null ? "<unknown>" : operator.getUserAccount(), app.getId(),
                request.getFeaturedStatus(), request.getPriority(), result ? "成功" : "失败");
        if (request.getFeaturedStatus() != null) {
            log.info("应用精选状态流转：actor={}, appId={}, from={}, to={}, result={}",
                    operator == null ? "<unknown>" : operator.getUserAccount(), app.getId(),
                    app.getFeaturedStatus(), request.getFeaturedStatus(), result ? "成功" : "失败");
        }
        return result;
    }

    @Override
    @Transactional
    public boolean deleteApp(Long appId, UserAccount loginUser) {
        requireLogin(loginUser);
        App app = requireApp(appId);
        assertOwnerOrAdmin(app, loginUser);
        if (!taskManager.beginDelete(appId)) {
            log.warn("删除应用被拒绝：actor={}, appId={}, result=生成任务进行中", loginUser.getUserAccount(), appId);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "应用正在生成，请先停止并等待生成任务结束后再删除");
        }
        boolean deleteGuardRegistered = false;
        try {
            log.info("删除应用开始：actor={}, appId={}, result=处理中", loginUser.getUserAccount(), appId);
            deleteRelatedData(appId);
            chatHistoryService.deleteByAppId(appId);
            collaboratorService.deleteByAppId(appId);
            boolean removed = removeById(appId);
            if (removed) {
                // 数据库事务回滚时不能提前删除 Redis 记忆和磁盘文件；提交成功后再做外部资源清理。
                registerDeleteGuardRelease(appId);
                deleteGuardRegistered = true;
                registerDeleteCleanup(appId, app.getDeployKey(), app.getCover(), loginUser.getUserAccount());
            } else {
                taskManager.finishDelete(appId);
            }
            log.info("删除应用完成：actor={}, appId={}, result={}", loginUser.getUserAccount(), appId,
                    removed ? "成功" : "失败");
            return removed;
        } catch (RuntimeException exception) {
            if (!deleteGuardRegistered) {
                taskManager.finishDelete(appId);
            }
            throw exception;
        }
    }

    @Override
    public Flux<String> chatToGenCode(Long appId, String message, UserAccount loginUser, boolean agent) {
        requireLogin(loginUser);
        App app = requireApp(appId);
        if (!collaboratorService.canEdit(app, loginUser)) {
            log.warn("生成权限校验失败：actor={}, appId={}, result=拒绝",
                    loginUser.getUserAccount(), appId);
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有应用创建者或编辑协作者可以生成代码");
        }
        if (message == null || message.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "提示词不能为空");
        }
        String prompt = normalizePrompt(message);
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        if (codeGenType == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "应用代码生成类型无效");
        }
        boolean modification = app.getCurrentVersion() != null && app.getCurrentVersion() > 0;

        long startedAt = System.nanoTime();
        log.info("应用生成任务开始：actor={}, appId={}, type={}, mode={}, pipeline={}, promptLength={}",
                loginUser.getUserAccount(), appId, codeGenType.getValue(),
                modification ? "修改" : "创建", agent ? "workflow" : "direct", prompt.length());

        GenerationTaskManager.GenerationTask task;
        try {
            task = taskManager.start(appId);
        } catch (RuntimeException exception) {
            log.warn("应用生成任务启动失败：actor={}, appId={}, result=拒绝, reason={}",
                    loginUser.getUserAccount(), appId, exception.getClass().getSimpleName());
            throw exception;
        }
        AppVersion reservedVersion = null;
        com.lian.aicode.model.entity.ChatHistory userHistory;
        try {
            reservedVersion = reserveVersion(app, prompt, codeGenType, loginUser.getId(), loginUser.getUserAccount());
            try {
                prepareVersionSource(app, reservedVersion, codeGenType, loginUser.getUserAccount());
                userHistory = chatHistoryService.addMessage(appId, loginUser.getId(), prompt,
                        ChatHistoryMessageTypeEnum.USER, null, reservedVersion.getVersionNo(), null);
                if (mapper.incrementConversationRounds(appId) <= 0) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "更新对话轮次失败");
                }
            } catch (RuntimeException exception) {
                // 版本已预留但用户消息写入失败时不能留下“永远生成中”的孤儿任务。
                markGenerationFailed(appId, reservedVersion, null, loginUser.getUserAccount(), exception);
                throw exception;
            }
        } catch (RuntimeException exception) {
            taskManager.finish(appId, task);
            log.warn("应用生成任务初始化失败：actor={}, appId={}, version={}, reason={}",
                    loginUser.getUserAccount(), appId,
                    reservedVersion == null ? null : reservedVersion.getVersionNo(), exception.getClass().getSimpleName());
            throw exception;
        }

        final AppVersion version = reservedVersion;
        Path versionDirectory = storageService.resolveVersionPath(version.getRelativePath());
        StringBuilder aiMessage = new StringBuilder();
        AtomicBoolean sourceCompleted = new AtomicBoolean(false);
        AtomicBoolean stateMarked = new AtomicBoolean(false);
        // 只统计真实成功的文件变更工具事件；模型可以把工具记录写成文本，但伪造不出事件信封。
        AtomicInteger realFileMutations = new AtomicInteger();
        Flux<String> source = (agent
                ? codeGenWorkflow.executeWorkflowWithFlux(WorkflowRequest.builder()
                .appId(appId)
                .versionNo(version.getVersionNo())
                .excludedMessageId(userHistory.getId())
                .actorAccount(loginUser.getUserAccount())
                .prompt(prompt)
                .outputDirectory(versionDirectory)
                // 修改轮把上一可用版本目录作为质检基线：质检只看本次变更的文件，
                // 避免为存量代码问题反复重试（2026-09-24 实测 41 字符需求重试三轮 227 秒全废）。
                .baselineDirectory(modification
                        ? storageService.versionDirectory(appId, app.getCurrentVersion()) : null)
                .generationType(codeGenType)
                .modification(modification)
                .build())
                : aiCodeGeneratorFacade.generateAndSaveCodeStream(
                appId, prompt, codeGenType,
                versionDirectory, userHistory.getId(),
                version.getVersionNo(), loginUser.getUserAccount(), modification)
                )
                .doOnNext(chunk -> {
                    String historyChunk = streamMessageHistoryFormatter.toHistoryText(chunk);
                    if (!historyChunk.isBlank() && aiMessage.length() < MAX_CHAT_HISTORY_MESSAGE_LENGTH) {
                        int remaining = MAX_CHAT_HISTORY_MESSAGE_LENGTH - aiMessage.length();
                        aiMessage.append(historyChunk, 0, Math.min(historyChunk.length(), remaining));
                    }
                    if (codeGenType == CodeGenTypeEnum.VUE_PROJECT
                            && isSuccessfulFileMutationEvent(objectMapper, chunk)) {
                        realFileMutations.incrementAndGet();
                    }
                })
                // 门面只有在解析和保存成功后才完成，因此这里是“可用版本”的唯一完成信号。
                .doOnComplete(() -> sourceCompleted.set(true));

        return source
                .takeUntilOther(task.cancelSignal())
                .doOnComplete(() -> {
                    if (stateMarked.compareAndSet(false, true)) {
                        try {
                            // 上游已经完整结束时，停止请求只能算“来晚了”，不能把可用版本改成取消态。
                            if (sourceCompleted.get()) {
                                if (codeGenType == CodeGenTypeEnum.VUE_PROJECT && realFileMutations.get() == 0) {
                                    // 2026-09-23 实测：模型可能"读而不改"后正常收尾，产出纯模板的假成功版本。
                                    // 拒收并按失败收口；异常会经下方 catch 记录原因并向前端透出。
                                    throw new BusinessException(ErrorCode.OPERATION_ERROR,
                                            "模型未写入任何工程文件，本版本已作废，请重试或简化需求描述");
                                }
                                markGenerationReady(appId, version, userHistory.getId(), loginUser.getUserAccount(),
                                        aiMessage.toString());
                            } else {
                                markGenerationCancelled(appId, version, userHistory.getId(), loginUser.getUserAccount());
                            }
                        } catch (RuntimeException completionError) {
                            // 终态收口失败时，不能因为 stateMarked 已置位而遗留 generating 孤儿版本。
                            try {
                                markGenerationFailed(appId, version, userHistory.getId(), loginUser.getUserAccount(),
                                        completionError);
                            } catch (RuntimeException stateError) {
                                log.error("生成终态收口失败：appId={}, version={}", appId, version.getVersionNo(), stateError);
                            }
                            throw completionError;
                        }
                    }
                })
                // takeUntilOther 会以“正常完成”结束下游；追加显式取消错误，避免控制器误发 done。
                .concatWith(Flux.defer(() -> task.isCancelled() && !sourceCompleted.get()
                        ? Flux.error(new GenerationCancelledException()) : Flux.empty()))
                .doOnError(error -> {
                    if (stateMarked.compareAndSet(false, true)) {
                        markGenerationFailed(appId, version, userHistory.getId(), loginUser.getUserAccount(), error);
                    }
                })
                .doFinally(signal -> {
                    // 浏览器断开 SSE 时，上游收到 cancel，不会触发 doOnComplete；仍需收口数据库状态。
                    if (signal == SignalType.CANCEL && stateMarked.compareAndSet(false, true)) {
                        markGenerationCancelled(appId, version, userHistory.getId(), loginUser.getUserAccount());
                    }
                    log.info("应用生成任务结束：actor={}, appId={}, version={}, signal={}, durationMs={}",
                            loginUser.getUserAccount(), appId, version.getVersionNo(), signal,
                            elapsedMillis(startedAt));
                    taskManager.finish(appId, task);
                });
    }

    /**
     * 判定一条流式事件是否为真实成功的文件变更。
     *
     * <p>type/name/result 三重判据只有 TokenStreamAdapter 能构造；模型在正文里伪造的
     * “工具记录”会以 ai_response 事件到达，type 对不上，无法计入。</p>
     */
    public static boolean isSuccessfulFileMutationEvent(ObjectMapper mapper, String chunk) {
        if (chunk == null || !chunk.contains("tool_executed")) {
            return false;
        }
        try {
            var node = mapper.readTree(chunk);
            String result = node.path("result").asText("");
            String name = node.path("name").asText();
            boolean mutationTool = "writeFile".equals(name) || "modifyFile".equals(name)
                    || "deleteFile".equals(name);
            boolean success = result.startsWith("文件写入成功") || result.startsWith("文件修改成功")
                    || result.startsWith("文件删除成功");
            return StreamMessageTypeEnum.TOOL_EXECUTED.getValue().equals(node.path("type").asText())
                    && mutationTool && success;
        } catch (IOException exception) {
            return false;
        }
    }

    /** 保留旧测试和调用方的语义别名，实际判据已覆盖删除文件这种合法变更。 */
    public static boolean isSuccessfulFileWriteEvent(ObjectMapper mapper, String chunk) {
        return isSuccessfulFileMutationEvent(mapper, chunk);
    }

    /**
     * Vue 版本迭代以当前可用版本为基线；第一版由模板服务初始化，后续版本只继承受控源文件。
     */
    private void prepareVersionSource(App app, AppVersion version, CodeGenTypeEnum type, String actorAccount) {
        if (type != CodeGenTypeEnum.VUE_PROJECT || app.getCurrentVersion() == null || app.getCurrentVersion() <= 0) {
            return;
        }
        AppVersion baseVersion = appVersionMapper.selectOneByQuery(QueryWrapper.create()
                .eq("app_id", app.getId())
                .eq("version_no", app.getCurrentVersion())
                .eq("status", AppVersionStatusEnum.READY.getValue()));
        if (baseVersion == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "当前 Vue 基线版本不存在");
        }
        Path source = storageService.resolveVersionPath(baseVersion.getRelativePath());
        Path target = storageService.resolveVersionPath(version.getRelativePath());
        log.info("准备 Vue 版本迭代：actor={}, appId={}, fromVersion={}, toVersion={}",
                actorAccount, app.getId(), baseVersion.getVersionNo(), version.getVersionNo());
        storageService.copyProjectSource(source, target);
    }

    @Override
    public boolean stopGeneration(Long appId, UserAccount loginUser) {
        requireLogin(loginUser);
        App app = requireApp(appId);
        if (!collaboratorService.canEdit(app, loginUser)) {
            log.warn("停止生成权限校验失败：actor={}, appId={}, result=拒绝", loginUser.getUserAccount(), appId);
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有应用创建者或编辑协作者可以停止生成");
        }
        boolean result = taskManager.cancel(appId);
        log.info("停止应用生成：actor={}, appId={}, result={}", loginUser.getUserAccount(), appId,
                result ? "成功" : "无进行中任务");
        return result;
    }

    @Override
    @Transactional
    public String deployApp(Long appId, UserAccount loginUser) {
        requireLogin(loginUser);
        App app = requireApp(appId);
        assertOwnerOrAdmin(app, loginUser);
        return deployReadyVersion(appId, app, loginUser.getUserAccount());
    }

    /** 执行部署文件切换并保存数据库状态；调用方必须已经完成权限校验。 */
    private String deployReadyVersion(Long appId, App app, String actorAccount) {
        AppVersion version = requireReadyVersion(appId, app.getCurrentVersion());
        Path sourceDirectory = resolveRuntimeDirectory(version);
        log.info("应用部署开始：actor={}, appId={}, version={}, previousStatus={}", actorAccount, appId,
                version.getVersionNo(), app.getDeploymentStatus());
        String deployKey = StringUtils.hasText(app.getDeployKey()) ? app.getDeployKey() : generateDeployKey();
        String oldDeployKey = app.getDeployKey();
        // currentVersion 是用户当前选中的版本，不一定是部署目录正在提供的版本。
        // 兼容历史数据：旧记录没有 deployedVersion 时退回 currentVersion。
        Integer oldVersionNo = app.getDeployedVersion() == null
                ? app.getCurrentVersion() : app.getDeployedVersion();
        boolean fileSwitched = false;
        try {
            storageService.deploy(sourceDirectory, deployKey);
            fileSwitched = true;
            App update = new App();
            update.setId(appId);
            update.setDeployKey(deployKey);
            update.setDeployedVersion(version.getVersionNo());
            update.setDeployedTime(LocalDateTime.now());
            update.setDeploymentStatus(AppDeploymentStatusEnum.DEPLOYED.getValue());
            update.setUpdateTime(update.getDeployedTime());
            if (!updateById(update)) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "保存部署状态失败");
            }
            log.info("部署状态流转：actor={}, appId={}, version={}, from={}, to=deployed, result=成功",
                    actorAccount, appId, version.getVersionNo(), app.getDeploymentStatus());
            registerAfterCommit(() -> screenshotService.submitIfMissing(appId, version.getVersionNo(),
                    buildDeployUrl(deployKey), "deploy", actorAccount));
        } catch (RuntimeException exception) {
            if (fileSwitched) {
                restoreDeploymentAfterDatabaseFailure(appId, oldDeployKey, oldVersionNo, deployKey);
            }
            log.warn("应用部署失败：actor={}, appId={}, version={}, reason={}", actorAccount, appId,
                    version.getVersionNo(), exception.getClass().getSimpleName());
            throw exception;
        }
        return buildDeployUrl(deployKey);
    }

    @Override
    public boolean disableDeployment(Long appId, UserAccount loginUser) {
        requireLogin(loginUser);
        App app = requireApp(appId);
        assertOwnerOrAdmin(app, loginUser);
        if (!AppDeploymentStatusEnum.DEPLOYED.getValue().equals(app.getDeploymentStatus())) {
            log.info("暂停部署跳过：actor={}, appId={}, previousStatus={}, result=无需操作",
                    loginUser.getUserAccount(), appId, app.getDeploymentStatus());
            return false;
        }
        App update = new App();
        update.setId(appId);
        update.setDeploymentStatus(AppDeploymentStatusEnum.PAUSED.getValue());
        update.setUpdateTime(LocalDateTime.now());
        boolean result = updateById(update);
        log.info("部署状态流转：actor={}, appId={}, from=deployed, to=paused, result={}",
                loginUser.getUserAccount(), appId, result ? "成功" : "失败");
        return result;
    }

    @Override
    @Transactional
    public String enableDeployment(Long appId, UserAccount loginUser) {
        requireLogin(loginUser);
        App app = requireApp(appId);
        assertOwnerOrAdmin(app, loginUser);
        return deployReadyVersion(appId, app, loginUser.getUserAccount());
    }

    @Override
    public List<AppVersionVO> listVersions(Long appId, UserAccount loginUser) {
        App app = requireApp(appId);
        assertReadable(app, loginUser);
        QueryWrapper query = QueryWrapper.create().eq("app_id", appId);
        // 公开访客只看到当前公开版本，不能通过版本列表枚举历史生成内容和提示词；
        // editor 协作者可以生成新版本，因此同样允许查看完整版本历史。
        if (!canManage(app, loginUser) && !collaboratorService.canEdit(app, loginUser)) {
            if (app.getCurrentVersion() == null || app.getCurrentVersion() <= 0) {
                return List.of();
            }
            query.eq("version_no", app.getCurrentVersion());
        }
        List<AppVersion> versions = appVersionMapper.selectListByQuery(query.orderBy("version_no", false));
        return versions.stream().map(this::toVersionVO).toList();
    }

    /**
     * 构建状态只读诊断（教程 11 期扩展思路）。
     *
     * <p>同步打包模式下 {@code building} 恒为 false：版本在 ready 之前不会对预览开放，
     * 该接口用于自查“预览 404/内容是旧版”时的构建产物状态，不用于轮询进度。</p>
     */
    @Override
    public AppBuildStatusVO getBuildStatus(Long appId, UserAccount loginUser) {
        requireLogin(loginUser);
        App app = requireApp(appId);
        if (!canManage(app, loginUser) && !collaboratorService.canEdit(app, loginUser)) {
            log.warn("构建状态查询权限校验失败：actor={}, appId={}, result=拒绝",
                    loginUser.getUserAccount(), appId);
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                    "只有应用创建者、编辑协作者或管理员可以查询构建状态");
        }
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        int versionNo = app.getCurrentVersion() == null ? 0 : app.getCurrentVersion();
        AppBuildStatusVO.AppBuildStatusVOBuilder builder = AppBuildStatusVO.builder()
                .appId(appId)
                .codeGenType(app.getCodeGenType())
                .versionNo(versionNo)
                .building(false);
        if (type != CodeGenTypeEnum.VUE_PROJECT) {
            log.info("查询应用构建状态：actor={}, appId={}, type={}, result=无需构建",
                    loginUser.getUserAccount(), appId, app.getCodeGenType());
            return builder.status("not_applicable")
                    .message("该代码生成类型无需构建，文件保存后即可预览")
                    .build();
        }
        if (versionNo <= 0) {
            return builder.status("not_found").message("应用尚未生成任何版本").build();
        }
        AppVersion version = appVersionMapper.selectOneByQuery(QueryWrapper.create()
                .eq("app_id", appId).eq("version_no", versionNo));
        if (version == null) {
            return builder.status("not_found").message("当前版本记录不存在").build();
        }
        Path projectRoot = storageService.resolveVersionPath(version.getRelativePath());
        boolean projectExists = Files.isDirectory(projectRoot);
        boolean distExists = isSafeVueDist(storageService.projectDistDirectory(appId, versionNo));
        String status = distExists ? "completed" : (projectExists ? "pending" : "not_found");
        String message = switch (status) {
            case "completed" -> "构建产物已就绪，可直接预览";
            case "pending" -> "工程文件已生成但缺少构建产物，请重新部署或回滚后重试";
            default -> "当前版本目录不存在";
        };
        String buildTime = readDistBuildTime(appId, versionNo);
        log.info("查询应用构建状态：actor={}, appId={}, version={}, status={}, projectExists={}, distExists={}",
                loginUser.getUserAccount(), appId, versionNo, status, projectExists, distExists);
        return builder.projectExists(projectExists).distExists(distExists)
                .status(status).message(message).buildTime(buildTime).build();
    }

    /** 读取 dist/index.html 的最后修改时间作为构建时间；读取失败不影响状态本身。 */
    private String readDistBuildTime(Long appId, Integer versionNo) {
        Path index = storageService.projectDistDirectory(appId, versionNo).resolve("index.html");
        try {
            if (!Files.isRegularFile(index) || Files.isSymbolicLink(index)) {
                return null;
            }
            return Instant.ofEpochMilli(Files.getLastModifiedTime(index).toMillis()).toString();
        } catch (IOException exception) {
            log.warn("读取构建产物时间失败：appId={}, version={}", appId, versionNo);
            return null;
        }
    }

    @Override
    @Transactional
    public boolean rollback(Long appId, Integer versionNo, UserAccount loginUser) {
        requireLogin(loginUser);
        App app = requireApp(appId);
        assertOwnerOrAdmin(app, loginUser);
        AppVersion targetVersion = requireReadyVersion(appId, versionNo);
        log.info("应用版本回滚开始：actor={}, appId={}, fromVersion={}, toVersion={}",
                loginUser.getUserAccount(), appId, app.getCurrentVersion(), versionNo);
        Integer oldVersionNo = app.getDeployedVersion() == null
                ? app.getCurrentVersion() : app.getDeployedVersion();
        // 如果当前应用已在线，回滚必须同步替换部署目录，否则数据库显示的当前版本和线上内容会不一致。
        if (AppDeploymentStatusEnum.DEPLOYED.getValue().equals(app.getDeploymentStatus())) {
            if (!StringUtils.hasText(app.getDeployKey())) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "部署状态异常，缺少部署标识");
            }
            storageService.deploy(resolveRuntimeDirectory(targetVersion), app.getDeployKey());
        }
        App update = new App();
        update.setId(appId);
        update.setCurrentVersion(versionNo);
        if (AppDeploymentStatusEnum.DEPLOYED.getValue().equals(app.getDeploymentStatus())) {
            update.setDeployedVersion(versionNo);
        }
        update.setGenerationStatus(AppGenerationStatusEnum.READY.getValue());
        update.setGenerationMessage("已回滚到版本 " + versionNo);
        update.setUpdateTime(LocalDateTime.now());
        try {
            if (!updateById(update)) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "保存回滚状态失败");
            }
            log.info("版本状态流转：actor={}, appId={}, currentVersion={}, deployedVersion={}, result=回滚成功",
                    loginUser.getUserAccount(), appId, versionNo, update.getDeployedVersion());
            registerAfterCommit(() -> screenshotService.submitIfMissing(appId, versionNo,
                    buildPreviewScreenshotUrl(appId, versionNo), "rollback", loginUser.getUserAccount()));
            return true;
        } catch (RuntimeException exception) {
            if (AppDeploymentStatusEnum.DEPLOYED.getValue().equals(app.getDeploymentStatus())) {
                restoreDeploymentAfterDatabaseFailure(appId, app.getDeployKey(), oldVersionNo, app.getDeployKey());
            }
            log.warn("应用版本回滚失败：actor={}, appId={}, toVersion={}, reason={}",
                    loginUser.getUserAccount(), appId, versionNo, exception.getClass().getSimpleName());
            throw exception;
        }
    }

    @Override
    public AppVersionDiffVO diff(Long appId, Integer fromVersion, Integer toVersion, UserAccount loginUser) {
        App app = requireApp(appId);
        assertReadable(app, loginUser);
        if (!canManage(app, loginUser) && !collaboratorService.canEdit(app, loginUser)) {
            log.warn("版本比较权限校验失败：actor={}, appId={}, result=拒绝",
                    loginUser == null ? "<anonymous>" : loginUser.getUserAccount(), appId);
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有应用创建者、编辑协作者或管理员可以比较历史版本");
        }
        AppVersion from = requireReadyVersion(appId, fromVersion);
        AppVersion to = requireReadyVersion(appId, toVersion);
        Set<String> fileNames = new TreeSet<>();
        fileNames.addAll(listVersionFiles(from));
        fileNames.addAll(listVersionFiles(to));
        if (fileNames.size() > MAX_DIFF_FILES) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "版本文件数量过多，暂不支持比较");
        }
        Map<String, String> files = new LinkedHashMap<>();
        for (String fileName : fileNames) {
            files.put(fileName, diffText(readFile(from, fileName), readFile(to, fileName)));
        }
        return AppVersionDiffVO.builder()
                .appId(appId)
                .fromVersion(fromVersion)
                .toVersion(toVersion)
                .files(files)
                .build();
    }

    @Override
    public List<ChatHistoryVO> listChatHistory(Long appId, UserAccount loginUser) {
        // 兼容第四期旧接口的错误语义：公开应用的访客可以看应用，但不能枚举私有对话审计记录。
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有登录用户才能查看应用对话历史");
        }
        return chatHistoryService.listRecent(appId, loginUser, 200);
    }

    @Override
    public List<AppCollaboratorVO> listCollaborators(Long appId, UserAccount loginUser) {
        requireLogin(loginUser);
        return collaboratorService.list(appId, loginUser);
    }

    @Override
    public boolean addCollaborator(AppCollaboratorRequest request, UserAccount loginUser) {
        requireLogin(loginUser);
        return collaboratorService.add(request, loginUser);
    }

    @Override
    public boolean removeCollaborator(AppCollaboratorRequest request, UserAccount loginUser) {
        requireLogin(loginUser);
        return collaboratorService.remove(request, loginUser);
    }

    @Override
    @Transactional
    public boolean applyFeatured(AppFeaturedRequest request, UserAccount loginUser) {
        requireLogin(loginUser);
        App app = requireApp(request.getAppId());
        if (!app.getUserId().equals(loginUser.getId())) {
            log.warn("申请精选权限校验失败：actor={}, appId={}, result=拒绝",
                    loginUser.getUserAccount(), request.getAppId());
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只能申请自己的应用");
        }
        if (!AppVisibilityEnum.PUBLIC.getValue().equals(app.getVisibility())
                || app.getCurrentVersion() == null || app.getCurrentVersion() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "只有公开且生成完成的应用可以申请精选");
        }
        requireReadyVersion(app.getId(), app.getCurrentVersion());
        App update = new App();
        update.setId(app.getId());
        update.setFeaturedStatus(AppFeaturedStatusEnum.PENDING.getValue());
        update.setFeaturedReason(trimToNull(request.getReason()));
        update.setUpdateTime(LocalDateTime.now());
        boolean result = updateById(update);
        log.info("应用精选状态流转：actor={}, appId={}, from={}, to=pending, result={}",
                loginUser.getUserAccount(), app.getId(), app.getFeaturedStatus(), result ? "成功" : "失败");
        return result;
    }

    @Override
    public App findByDeployKey(String deployKey) {
        if (!StringUtils.hasText(deployKey)) {
            return null;
        }
        return mapper.selectOneByQuery(QueryWrapper.create().eq("deploy_key", deployKey));
    }

    @Override
    public Path getPreviewPath(Long appId, Integer versionNo, UserAccount loginUser) {
        App app = requireApp(appId);
        assertReadable(app, loginUser);
        if (!canManage(app, loginUser)
                && (app.getCurrentVersion() == null || !app.getCurrentVersion().equals(versionNo))) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "公开访问只能预览当前版本");
        }
        AppVersion version = requireReadyVersion(appId, versionNo);
        // 预览是只读请求，不能因为访问页面就触发 npm install/build；构建只在生成完成或显式部署流程中执行。
        Path runtimeDirectory = resolveRuntimeDirectory(version, false);
        if (app.getCurrentVersion() != null && app.getCurrentVersion().equals(versionNo)) {
            screenshotService.submitIfMissing(appId, versionNo, buildPreviewScreenshotUrl(appId, versionNo),
                    "preview", loginUser == null ? "<anonymous>" : loginUser.getUserAccount());
        }
        return runtimeDirectory;
    }

    @Override
    public Path getDeployPath(String deployKey) {
        return storageService.deployDirectory(deployKey);
    }

    @Override
    public Path getDownloadPath(Long appId, UserAccount loginUser) {
        App app = requireApp(appId);
        requireLogin(loginUser);
        assertOwnerOrAdmin(app, loginUser);
        AppVersion version = requireReadyVersion(appId, app.getCurrentVersion());
        return storageService.resolveVersionPath(version.getRelativePath());
    }

    @Override
    public void recordDownload(Long appId, UserAccount loginUser) {
        requireLogin(loginUser);
        App app = requireApp(appId);
        assertOwnerOrAdmin(app, loginUser);
        int updated = mapper.incrementDownloadCount(appId);
        if (updated <= 0) {
            log.warn("记录应用下载失败：actor={}, appId={}, result=数据库未更新", loginUser.getUserAccount(), appId);
            return;
        }
        log.info("记录应用下载：actor={}, appId={}, result=成功", loginUser.getUserAccount(), appId);
    }

    private PageResult<AppVO> pageToVO(AppQueryRequest request, QueryWrapper wrapper, long maxPageSize) {
        long pageNum = request == null ? 1 : Math.max(request.getPageNum(), 1);
        long pageSize = request == null ? 12 : Math.min(Math.max(request.getPageSize(), 1), maxPageSize);
        Page<App> page = page(Page.of(pageNum, pageSize), wrapper);
        long total = page.getTotalRow();
        List<App> appRecords = page.getRecords();
        Set<Long> ownerIds = new HashSet<>();
        appRecords.stream().map(App::getUserId).filter(java.util.Objects::nonNull).forEach(ownerIds::add);
        Map<Long, UserVO> ownerMap = new HashMap<>();
        userService.findAllByIds(ownerIds).forEach(user -> ownerMap.put(user.getId(), userService.getUserVO(user)));
        List<AppVO> records = appRecords.stream()
                .map(app -> toAppVO(app, ownerMap.get(app.getUserId()))).toList();
        long pages = total == 0 ? 0 : (total + pageSize - 1) / pageSize;
        return new PageResult<>(records, pageNum, pageSize, total, pages);
    }

    private QueryWrapper buildQueryWrapper(AppQueryRequest request) {
        return buildQueryWrapper(request, false);
    }

    private QueryWrapper buildQueryWrapper(AppQueryRequest request, boolean featuredOrdering) {
        QueryWrapper wrapper = QueryWrapper.create();
        if (request == null) {
            return featuredOrdering
                    ? wrapper.orderBy("priority", false).orderBy("create_time", false)
                    : wrapper.orderBy("create_time", false);
        }
        if (StringUtils.hasText(request.getSearchText())) {
            // 小规模数据先做参数化的多字段检索；数据量增大后可替换为全文索引/ES。
            String keyword = "%" + request.getSearchText().trim() + "%";
            wrapper.and("(app_name LIKE ? OR init_prompt LIKE ? OR tags LIKE ?)",
                    keyword, keyword, keyword);
        }
        if (request.getId() != null) {
            wrapper.eq("id", request.getId());
        }
        if (StringUtils.hasText(request.getAppName())) {
            wrapper.like("app_name", request.getAppName().trim());
        }
        if (StringUtils.hasText(request.getCover())) {
            wrapper.like("cover", request.getCover().trim());
        }
        if (StringUtils.hasText(request.getInitPrompt())) {
            wrapper.like("init_prompt", request.getInitPrompt().trim());
        }
        if (StringUtils.hasText(request.getCodeGenType())) {
            wrapper.eq("code_gen_type", request.getCodeGenType().trim());
        }
        if (StringUtils.hasText(request.getDeployKey())) {
            wrapper.eq("deploy_key", request.getDeployKey().trim());
        }
        if (request.getPriority() != null) {
            wrapper.eq("priority", request.getPriority());
        }
        if (request.getUserId() != null) {
            wrapper.eq("user_id", request.getUserId());
        }
        if (StringUtils.hasText(request.getCategory())) {
            wrapper.eq("category", request.getCategory().trim());
        }
        if (StringUtils.hasText(request.getTags())) {
            wrapper.like("tags", request.getTags().trim());
        }
        if (StringUtils.hasText(request.getTag())) {
            // 标签按逗号存储，使用边界匹配避免搜索“端”误命中“客户端”。
            String tag = request.getTag().trim();
            wrapper.and("(tags = ? OR tags LIKE ? OR tags LIKE ? OR tags LIKE ?)",
                    tag, tag + ",%", "%," + tag + ",%", "%," + tag);
        }
        if (StringUtils.hasText(request.getVisibility())) {
            wrapper.eq("visibility", request.getVisibility().trim());
        }
        if (StringUtils.hasText(request.getGenerationStatus())) {
            wrapper.eq("generation_status", request.getGenerationStatus().trim());
        }
        if (request.getCurrentVersion() != null) {
            wrapper.eq("current_version", request.getCurrentVersion());
        }
        if (StringUtils.hasText(request.getGenerationMessage())) {
            wrapper.like("generation_message", request.getGenerationMessage().trim());
        }
        if (StringUtils.hasText(request.getDeploymentStatus())) {
            wrapper.eq("deployment_status", request.getDeploymentStatus().trim());
        }
        if (StringUtils.hasText(request.getFeaturedStatus())) {
            wrapper.eq("featured_status", request.getFeaturedStatus().trim());
        }
        if (StringUtils.hasText(request.getFeaturedReason())) {
            wrapper.like("featured_reason", request.getFeaturedReason().trim());
        }
        Map<String, String> sortColumns = Map.of(
                "createTime", "create_time",
                "updateTime", "update_time",
                "priority", "priority",
                "downloadCount", "download_count",
                "appName", "app_name");
        String sortColumn = sortColumns.getOrDefault(request.getSortField(), "create_time");
        boolean ascending = "asc".equalsIgnoreCase(request.getSortOrder())
                || "ascend".equalsIgnoreCase(request.getSortOrder());
        if (featuredOrdering && !"priority".equals(sortColumn)) {
            return wrapper.orderBy("priority", false).orderBy(sortColumn, ascending);
        }
        return wrapper.orderBy(sortColumn, ascending);
    }

    private App requireApp(Long appId) {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 id 无效");
        }
        App app = getById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        return app;
    }

    private void assertReadable(App app, UserAccount user) {
        if (user != null && (userService.isAdmin(user) || app.getUserId().equals(user.getId()))) {
            return;
        }
        if (collaboratorService.canView(app, user)) {
            return;
        }
        if (AppVisibilityEnum.PUBLIC.getValue().equals(app.getVisibility())
                && app.getCurrentVersion() != null && app.getCurrentVersion() > 0) {
            return;
        }
        if (user == null) {
            log.warn("应用访问权限校验失败：actor=<anonymous>, appId={}, result=未登录", app.getId());
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "请先登录后访问私有应用");
        }
        log.warn("应用访问权限校验失败：actor={}, appId={}, result=拒绝",
                user.getUserAccount(), app.getId());
        throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
    }

    private void assertOwnerOrAdmin(App app, UserAccount user) {
        if (!isOwner(app, user) && !userService.isAdmin(user)) {
            log.warn("应用运营权限校验失败：actor={}, appId={}, result=拒绝",
                    user == null ? "<anonymous>" : user.getUserAccount(), app.getId());
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限操作该应用");
        }
    }

    private void assertOwner(App app, UserAccount user) {
        if (!isOwner(app, user)) {
            log.warn("应用编辑权限校验失败：actor={}, appId={}, result=拒绝",
                    user == null ? "<anonymous>" : user.getUserAccount(), app.getId());
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有应用创建者可以修改该应用");
        }
    }

    private boolean isOwner(App app, UserAccount user) {
        return app != null && user != null && app.getUserId() != null
                && app.getUserId().equals(user.getId());
    }

    private boolean canManage(App app, UserAccount user) {
        return isOwner(app, user) || userService.isAdmin(user);
    }

    private void requireLogin(UserAccount user) {
        if (user == null) {
            log.warn("应用操作登录校验失败：actor=<anonymous>, result=拒绝");
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "请先登录");
        }
    }

    private AppVersion reserveVersion(App app, String prompt, CodeGenTypeEnum type, Long userId,
                                      String actorAccount) {
        // 失败或取消的版本也占用过版本号，不能只根据 current_version 递增，否则重试会撞唯一键。
        AppVersion latestVersion = appVersionMapper.selectOneByQuery(QueryWrapper.create()
                .eq("app_id", app.getId()).orderBy("version_no", false));
        int versionNo = latestVersion == null ? 1 : latestVersion.getVersionNo() + 1;
        AppVersion version = new AppVersion();
        version.setAppId(app.getId());
        version.setVersionNo(versionNo);
        version.setCodeGenType(type.getValue());
        version.setRelativePath(storageService.versionRelativePath(app.getId(), versionNo));
        version.setPrompt(prompt);
        version.setStatus(AppVersionStatusEnum.GENERATING.getValue());
        version.setCreatedBy(userId);
        version.setCreateTime(LocalDateTime.now());
        version.setUpdateTime(version.getCreateTime());
        version.setIsDelete(0);
        if (appVersionMapper.insert(version) <= 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "创建代码版本失败");
        }
        App update = new App();
        update.setId(app.getId());
        update.setGenerationStatus(AppGenerationStatusEnum.GENERATING.getValue());
        update.setGenerationMessage(null);
        update.setUpdateTime(LocalDateTime.now());
        if (!updateById(update)) {
            appVersionMapper.deleteById(version.getId());
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "更新生成状态失败");
        }
        log.info("版本状态流转：actor={}, appId={}, version={}, from=none, to=generating, result=成功",
                actorAccount, app.getId(), versionNo);
        return version;
    }

    private void markGenerationReady(Long appId, AppVersion version, Long parentId, String actorAccount,
                                     String aiMessage) {
        AppVersion updateVersion = new AppVersion();
        updateVersion.setId(version.getId());
        updateVersion.setStatus(AppVersionStatusEnum.READY.getValue());
        updateVersion.setDescription("AI 代码生成完成");
        updateVersion.setUpdateTime(LocalDateTime.now());
        if (appVersionMapper.update(updateVersion) <= 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "保存代码版本完成状态失败");
        }

        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setCurrentVersion(version.getVersionNo());
        updateApp.setGenerationStatus(AppGenerationStatusEnum.READY.getValue());
        updateApp.setGenerationMessage("版本 " + version.getVersionNo() + " 已生成");
        updateApp.setUpdateTime(LocalDateTime.now());
        if (!updateById(updateApp)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "保存应用生成完成状态失败");
        }
        log.info("版本状态流转：actor={}, appId={}, version={}, from=generating, to=ready, result=成功",
                actorAccount, appId, version.getVersionNo());
        screenshotService.submitIfMissing(appId, version.getVersionNo(),
                buildPreviewScreenshotUrl(appId, version.getVersionNo()), "generation-ready", actorAccount);
        if (StringUtils.hasText(aiMessage)) {
            try {
                chatHistoryService.addMessage(appId, version.getCreatedBy(), aiMessage,
                        ChatHistoryMessageTypeEnum.AI, parentId, version.getVersionNo(),
                        toJson(storageService.listRelativeFiles(storageService.resolveVersionPath(
                                version.getRelativePath()))));
                log.info("保存成功生成对话记录：appId={}, version={}", appId, version.getVersionNo());
            } catch (RuntimeException exception) {
                // 版本已经可靠落盘；历史写入失败不能把已可用版本回滚，但必须留下告警。
                log.error("保存成功生成对话记录失败：appId={}, version={}", appId, version.getVersionNo(), exception);
            }
        }
        try {
            chatHistoryService.triggerSummaryIfNeeded(appId);
        } catch (RuntimeException exception) {
            // 摘要属于异步优化项，不能把已经成功落盘的代码版本误报成生成失败。
            log.warn("触发应用对话摘要失败，不影响代码版本：appId={}, version={}", appId, version.getVersionNo(), exception);
        }
    }

    private void markGenerationCancelled(Long appId, AppVersion version, Long parentId, String actorAccount) {
        AppVersion updateVersion = new AppVersion();
        updateVersion.setId(version.getId());
        updateVersion.setStatus(AppVersionStatusEnum.CANCELLED.getValue());
        updateVersion.setDescription("用户停止了本次生成");
        updateVersion.setUpdateTime(LocalDateTime.now());
        if (appVersionMapper.update(updateVersion) <= 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "保存代码版本取消状态失败");
        }
        storageService.deleteVersionDirectory(appId, version.getVersionNo());
        log.info("清理取消版本文件：actor={}, appId={}, version={}, result=完成",
                actorAccount, appId, version.getVersionNo());

        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setGenerationStatus(AppGenerationStatusEnum.CANCELLED.getValue());
        updateApp.setGenerationMessage("生成已取消");
        updateApp.setUpdateTime(LocalDateTime.now());
        if (!updateById(updateApp)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "保存应用生成取消状态失败");
        }
        log.info("版本状态流转：actor={}, appId={}, version={}, from=generating, to=cancelled, result=成功",
                actorAccount, appId, version.getVersionNo());
        recordGenerationEvent(appId, version, parentId, "本次生成已取消");
    }

    private void markGenerationFailed(Long appId, AppVersion version, Long parentId, String actorAccount,
                                      Throwable error) {
        String errorType = error == null ? "未知异常" : error.getClass().getSimpleName();
        log.warn("应用代码生成失败：appId={}, version={}, errorType={}", appId, version.getVersionNo(), errorType);
        AppVersion updateVersion = new AppVersion();
        updateVersion.setId(version.getId());
        updateVersion.setStatus(AppVersionStatusEnum.FAILED.getValue());
        updateVersion.setDescription("生成失败");
        updateVersion.setUpdateTime(LocalDateTime.now());
        if (appVersionMapper.update(updateVersion) <= 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "保存代码版本失败状态失败");
        }
        storageService.deleteVersionDirectory(appId, version.getVersionNo());
        log.info("清理失败版本文件：actor={}, appId={}, version={}, result=完成",
                actorAccount, appId, version.getVersionNo());

        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setGenerationStatus(AppGenerationStatusEnum.FAILED.getValue());
        // 业务异常的文案本身面向用户（如护轨拦截原因、构建失败提示），直接呈现；
        // 其他异常不写原文，避免泄露模型供应商、数据库或文件系统的内部信息。
        String visibleFailure = error instanceof BusinessException businessException
                && StringUtils.hasText(businessException.getMessage())
                ? businessException.getMessage()
                : "生成失败，请检查模型配置或稍后重试";
        updateApp.setGenerationMessage(visibleFailure);
        updateApp.setUpdateTime(LocalDateTime.now());
        if (!updateById(updateApp)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "保存应用生成失败状态失败");
        }
        log.info("版本状态流转：actor={}, appId={}, version={}, from=generating, to=failed, result=失败",
                actorAccount, appId, version.getVersionNo());
        recordGenerationEvent(appId, version, parentId, "本次" + visibleFailure);
    }

    private void recordGenerationEvent(Long appId, AppVersion version, Long parentId, String message) {
        try {
            chatHistoryService.addMessage(appId, version.getCreatedBy(), message,
                    ChatHistoryMessageTypeEnum.ERROR, parentId, version.getVersionNo(), null);
            log.info("保存生成结果事件：appId={}, version={}, messageType=error", appId, version.getVersionNo());
        } catch (RuntimeException exception) {
            // 状态收口不能因为审计记录失败而再次抛出，避免 SSE 已结束后出现二次异常。
            log.error("保存生成结果事件失败：appId={}, version={}", appId, version.getVersionNo(), exception);
        }
    }

    private void deleteRelatedData(Long appId) {
        int deleted = appVersionMapper.deleteByQuery(QueryWrapper.create().eq("app_id", appId));
        log.info("清理应用版本记录：appId={}, count={}", appId, deleted);
    }

    /**
     * 注册删除后的外部资源清理。
     *
     * <p>数据库逻辑删除和磁盘/Redis 不属于同一个事务；只有提交成功后才清理外部资源，
     * 避免数据库回滚后应用记录还在、但代码文件和会话记忆已经消失。</p>
     */
    private void registerDeleteCleanup(Long appId, String deployKey, String cover, String actorAccount) {
        Runnable cleanup = () -> {
            boolean allSucceeded = true;
            try {
                aiCodeGeneratorFacade.evictAppMemory(appId);
            } catch (RuntimeException exception) {
                allSucceeded = false;
                log.error("清理应用 AI 记忆失败：actor={}, appId={}, reason={}", actorAccount, appId,
                        exception.getClass().getSimpleName(), exception);
            }
            try {
                storageService.deleteApplicationFiles(appId);
            } catch (RuntimeException exception) {
                allSucceeded = false;
                log.error("清理应用代码文件失败：actor={}, appId={}, reason={}", actorAccount, appId,
                        exception.getClass().getSimpleName(), exception);
            }
            try {
                storageService.deleteDeployment(deployKey);
            } catch (RuntimeException exception) {
                allSucceeded = false;
                log.error("清理应用部署文件失败：actor={}, appId={}, reason={}", actorAccount, appId,
                        exception.getClass().getSimpleName(), exception);
            }
            try {
                screenshotService.deleteCover(cover, appId, actorAccount);
            } catch (RuntimeException exception) {
                allSucceeded = false;
                log.error("清理应用封面失败：actor={}, appId={}, reason={}", actorAccount, appId,
                        exception.getClass().getSimpleName(), exception);
            }
            if (allSucceeded) {
                log.info("删除应用外部资源清理完成：actor={}, appId={}, deployKeyPresent={}, result=成功",
                        actorAccount, appId, StringUtils.hasText(deployKey));
            } else {
                // 文件和缓存清理是补偿动作；失败不能逆转已提交的数据库删除，但必须可追踪。
                log.error("删除应用外部资源清理完成但存在失败项：actor={}, appId={}, result=需补偿",
                        actorAccount, appId);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }
            });
        } else {
            cleanup.run();
        }
    }

    /** 数据库事务结束后释放删除临界区；回滚时也必须释放，避免应用永久不能生成。 */
    private void registerDeleteGuardRelease(Long appId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    taskManager.finishDelete(appId);
                }
            });
        } else {
            taskManager.finishDelete(appId);
        }
    }

    /** 数据库状态保存失败时尽量恢复部署目录，避免“数据库未切换、线上文件已切换”。 */
    private void restoreDeploymentAfterDatabaseFailure(Long appId, String oldDeployKey,
                                                       Integer oldVersionNo, String switchedDeployKey) {
        try {
            AppVersion oldVersion = oldVersionNo == null || oldVersionNo <= 0 ? null
                    : appVersionMapper.selectOneByQuery(QueryWrapper.create()
                    .eq("app_id", appId).eq("version_no", oldVersionNo)
                    .eq("status", AppVersionStatusEnum.READY.getValue()));
            if (StringUtils.hasText(oldDeployKey) && oldVersion != null) {
                storageService.deploy(resolveRuntimeDirectory(oldVersion), oldDeployKey);
                if (!oldDeployKey.equals(switchedDeployKey)) {
                    storageService.deleteDeployment(switchedDeployKey);
                }
                log.info("恢复旧部署目录完成：appId={}, version={}", appId, oldVersionNo);
            } else {
                storageService.deleteDeployment(switchedDeployKey);
                log.info("清理未提交部署目录完成：appId={}", appId);
            }
        } catch (RuntimeException restoreException) {
            log.error("恢复部署目录失败：appId={}, oldVersion={}", appId, oldVersionNo, restoreException);
        }
    }

    private AppVersion requireReadyVersion(Long appId, Integer versionNo) {
        if (versionNo == null || versionNo <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "版本号无效");
        }
        AppVersion version = appVersionMapper.selectOneByQuery(QueryWrapper.create()
                .eq("app_id", appId).eq("version_no", versionNo));
        if (version == null || !AppVersionStatusEnum.READY.getValue().equals(version.getStatus())
                || !Files.isDirectory(storageService.resolveVersionPath(version.getRelativePath()))) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "可用代码版本不存在");
        }
        return version;
    }

    /**
     * 返回可被浏览器或部署目录直接提供的运行目录。
     * Vue 工程的源代码根目录不是生产静态资源目录，必须提供已经构建好的 dist；部署前
     * 如果 dist 被人工删除，会再次执行受控构建并在失败时拒绝部署。
     */
    private Path resolveRuntimeDirectory(AppVersion version) {
        return resolveRuntimeDirectory(version, true);
    }

    private Path resolveRuntimeDirectory(AppVersion version, boolean buildIfMissing) {
        Path projectRoot = storageService.resolveVersionPath(version.getRelativePath());
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(version.getCodeGenType());
        if (type != CodeGenTypeEnum.VUE_PROJECT) {
            return projectRoot;
        }
        Path dist = storageService.projectDistDirectory(version.getAppId(), version.getVersionNo());
        if (!isSafeVueDist(dist) && buildIfMissing) {
            log.info("Vue 版本缺少构建产物，开始补充构建：appId={}, version={}",
                    version.getAppId(), version.getVersionNo());
            if (!vueProjectBuilder.buildProject(projectRoot)) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        "Vue 项目构建失败，请检查 Windows Node.js/npm 环境和生成文件");
            }
        }
        if (!isSafeVueDist(dist)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Vue 项目构建产物不存在");
        }
        return dist;
    }

    private boolean isSafeVueDist(Path dist) {
        Path index = dist.resolve("index.html").normalize();
        return !Files.isSymbolicLink(dist)
                && Files.isDirectory(dist)
                && !Files.isSymbolicLink(index)
                && Files.isRegularFile(index)
                && isInsideCodeOutputRoot(dist)
                && isInsideCodeOutputRoot(index);
    }

    private AppVO toAppVO(App app) {
        return toAppVO(app, userService.getUserVO(userService.getById(app.getUserId())));
    }

    private AppVO toAppVO(App app, UserVO owner) {
        String previewUrl = app.getCurrentVersion() != null && app.getCurrentVersion() > 0
                ? buildPreviewUrl(app.getId(), app.getCurrentVersion()) : null;
        String deployUrl = StringUtils.hasText(app.getDeployKey())
                ? buildDeployUrl(app.getDeployKey()) : null;
        return AppVO.builder()
                .id(app.getId()).appName(app.getAppName()).cover(app.getCover())
                .initPrompt(app.getInitPrompt()).codeGenType(app.getCodeGenType())
                .deployKey(app.getDeployKey()).deployedTime(stringValue(app.getDeployedTime()))
                .priority(app.getPriority()).userId(app.getUserId()).visibility(app.getVisibility())
                .category(app.getCategory()).tags(app.getTags()).generationStatus(app.getGenerationStatus())
                .currentVersion(app.getCurrentVersion())
                .deployedVersion(app.getDeployedVersion())
                .conversationRounds(app.getConversationRounds() == null ? 0 : app.getConversationRounds())
                .downloadCount(app.getDownloadCount() == null ? 0 : app.getDownloadCount())
                .generationMessage(app.getGenerationMessage())
                .featuredStatus(app.getFeaturedStatus()).featuredReason(app.getFeaturedReason())
                .deploymentStatus(app.getDeploymentStatus()).createTime(stringValue(app.getCreateTime()))
                .updateTime(stringValue(app.getUpdateTime())).owner(owner)
                .previewUrl(previewUrl).deployUrl(deployUrl).build();
    }

    private AppVersionVO toVersionVO(AppVersion version) {
        String previewUrl = AppVersionStatusEnum.READY.getValue().equals(version.getStatus())
                ? buildPreviewUrl(version.getAppId(), version.getVersionNo()) : null;
        return AppVersionVO.builder().id(version.getId()).appId(version.getAppId())
                .versionNo(version.getVersionNo()).codeGenType(version.getCodeGenType())
                .status(version.getStatus()).description(version.getDescription()).prompt(version.getPrompt())
                .previewUrl(previewUrl)
                .createTime(stringValue(version.getCreateTime())).build();
    }

    private String generateAppName(String prompt, String actorAccount) {
        // 命名是高频入口：优先用按次创建的无状态服务（prototype 模型），工厂缺席时回退默认服务。
        AiCodeGeneratorServiceFactory serviceFactory = aiCodeGeneratorServiceFactoryProvider.getIfAvailable();
        AiCodeGeneratorService aiService = serviceFactory != null
                ? serviceFactory.getForStatelessTask()
                : aiCodeGeneratorServiceProvider.getIfAvailable();
        if (aiService != null) {
            long startedAt = System.nanoTime();
            log.info("AI 应用命名开始：actor={}, promptLength={}", actorAccount, prompt.length());
            try {
                AppNameResult name = aiService.generateAppName(prompt);
                if (name != null && StringUtils.hasText(name.getAppName())) {
                    String clean = name.getAppName().replaceAll("[\\r\\n`\\\"']", "").trim();
                    if (!clean.isBlank()) {
                        log.info("AI 应用命名结束：actor={}, result=成功, durationMs={}", actorAccount,
                                elapsedMillis(startedAt));
                        return clean.substring(0, Math.min(clean.length(), 64));
                    }
                }
            } catch (RuntimeException exception) {
                log.warn("AI 应用命名失败，使用本地回退名称：actor={}, reason={}, durationMs={}", actorAccount,
                        exception.getClass().getSimpleName(), elapsedMillis(startedAt));
            }
            log.info("AI 应用命名结束：actor={}, result=回退, durationMs={}", actorAccount,
                    elapsedMillis(startedAt));
        }
        String fallback = prompt.replaceAll("\\s+", " ").trim();
        return fallback.substring(0, Math.min(fallback.length(), 20));
    }

    private CodeGenTypeRoutingDecision resolveCodeGenType(String requestedValue, String prompt, String actorAccount) {
        if (StringUtils.hasText(requestedValue) && !"auto".equalsIgnoreCase(requestedValue.trim())) {
            CodeGenTypeEnum explicitType = CodeGenTypeEnum.getEnumByValue(requestedValue.trim());
            if (explicitType == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码生成类型无效");
            }
            log.info("代码生成类型选择：actor={}, type={}, source=explicit", actorAccount, explicitType.getValue());
            return new CodeGenTypeRoutingDecision(explicitType, "explicit");
        }
        // 路由是创建应用入口上的模型调用：按次创建服务（prototype 模型 + 结果缓存），支持并发创建。
        AiCodeGenTypeRoutingServiceFactory routingFactory =
                aiCodeGenTypeRoutingServiceFactoryProvider.getIfAvailable();
        AiCodeGenTypeRoutingService routingService = routingFactory != null
                ? routingFactory.createAiCodeGenTypeRoutingService()
                : aiCodeGenTypeRoutingServiceProvider.getIfAvailable();
        if (routingService != null) {
            try {
                CodeGenTypeEnum routedType = routingService.routeCodeGenType(prompt);
                if (routedType != null) {
                    return new CodeGenTypeRoutingDecision(routedType, "ai");
                }
                log.warn("AI 代码类型路由返回空结果：actor={}, result=使用本地回退", actorAccount);
            } catch (RuntimeException exception) {
                log.warn("AI 代码类型路由调用异常：actor={}, reason={}, result=使用本地回退",
                        actorAccount, exception.getClass().getSimpleName());
            }
        }
        CodeGenTypeEnum fallback = CodeGenTypeRoutingHeuristic.choose(prompt);
        log.info("代码生成类型选择：actor={}, type={}, source=heuristic-fallback", actorAccount, fallback.getValue());
        return new CodeGenTypeRoutingDecision(fallback, "heuristic-fallback");
    }

    private void registerAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /** 清理被手工封面替换掉的自动封面；清理失败不影响已经提交的资料变更。 */
    private void registerReplacedCoverCleanup(Long appId, String previousCover, String actorAccount) {
        if (!StringUtils.hasText(previousCover)) {
            return;
        }
        registerAfterCommit(() -> {
            try {
                screenshotService.deleteCover(previousCover, appId, actorAccount);
            } catch (RuntimeException exception) {
                log.error("清理被替换应用封面失败：actor={}, appId={}, reason={}, result=需补偿",
                        actorAccount, appId, exception.getClass().getSimpleName(), exception);
            }
        });
    }

    private record CodeGenTypeRoutingDecision(CodeGenTypeEnum type, String source) {
    }

    private String normalizePrompt(String prompt) {
        if (!StringUtils.hasText(prompt)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "需求描述不能为空");
        }
        String value = prompt.trim();
        if (value.length() > maxPromptLength) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "需求描述过长");
        }
        return value;
    }

    private String normalizeVisibility(String visibility) {
        String value = StringUtils.hasText(visibility)
                ? visibility.trim().toLowerCase(Locale.ROOT) : AppVisibilityEnum.PRIVATE.getValue();
        if (!AppVisibilityEnum.isValid(value)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用可见范围无效");
        }
        return value;
    }

    private String normalizeTags(String tags) {
        if (!StringUtils.hasText(tags)) {
            return null;
        }
        return Arrays.stream(tags.split("[,，\\n]"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(item -> item.substring(0, Math.min(item.length(), 24)))
                .distinct().limit(20).reduce((left, right) -> left + "," + right).orElse(null);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            log.warn("序列化版本文件列表失败", exception);
            return "[]";
        }
    }

    private String generateDeployKey() {
        String key;
        do {
            StringBuilder builder = new StringBuilder(12);
            for (int i = 0; i < 12; i++) {
                builder.append(DEPLOY_KEY_CHARS[SECURE_RANDOM.nextInt(DEPLOY_KEY_CHARS.length)]);
            }
            key = builder.toString();
        } while (findByDeployKey(key) != null);
        return key;
    }

    private String buildDeployUrl(String deployKey) {
        return deployPublicBaseUrl.replaceAll("/+$", "") + "/" + deployKey + "/";
    }

    private String buildPreviewUrl(Long appId, Integer versionNo) {
        String contextPath = serverContextPath == null ? "" : serverContextPath.trim();
        if ("/".equals(contextPath)) {
            contextPath = "";
        }
        if (!contextPath.isBlank() && !contextPath.startsWith("/")) {
            contextPath = "/" + contextPath;
        }
        return contextPath.replaceAll("/+$", "") + "/preview/" + appId + "/" + versionNo + "/";
    }

    private String buildPreviewScreenshotUrl(Long appId, Integer versionNo) {
        return previewPublicBaseUrl.replaceAll("/+$", "") + "/" + appId + "/" + versionNo + "/";
    }

    private String stringValue(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private Set<String> listVersionFiles(AppVersion version) {
        Path root = storageService.resolveVersionPath(version.getRelativePath());
        if (!Files.isDirectory(root) || !isInsideCodeOutputRoot(root)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "代码版本目录不存在");
        }
        return new TreeSet<>(storageService.listRelativeFiles(root));
    }

    private String readFile(AppVersion version, String fileName) {
        Path root = storageService.resolveVersionPath(version.getRelativePath());
        Path path = root.resolve(fileName).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path) || Files.isSymbolicLink(path)
                || !isInsideRealRoot(root, path)) {
            return "";
        }
        try {
            if (Files.size(path) > MAX_DIFF_FILE_BYTES) {
                return "[文件超过差异比较大小限制，未展开]";
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "读取版本文件失败", exception);
        }
    }

    private boolean isInsideCodeOutputRoot(Path path) {
        return isInsideRealRoot(storageService.getCodeOutputRoot(), path);
    }

    private boolean isInsideRealRoot(Path root, Path path) {
        try {
            return path.toRealPath().startsWith(root.toRealPath());
        } catch (IOException exception) {
            return false;
        }
    }

    private String diffText(String oldText, String newText) {
        if (oldText.equals(newText)) {
            return "无变化";
        }
        List<String> oldLines = Arrays.asList(oldText.split("\\R", -1));
        List<String> newLines = Arrays.asList(newText.split("\\R", -1));
        if (oldLines.size() > MAX_DIFF_LINES || newLines.size() > MAX_DIFF_LINES) {
            return "内容发生变化（文件过大，未展开逐行差异）";
        }

        // 轻量 LCS 差异算法，不引入额外依赖；限制行数避免差异接口消耗过多内存。
        int[][] lcs = new int[oldLines.size() + 1][newLines.size() + 1];
        for (int i = oldLines.size() - 1; i >= 0; i--) {
            for (int j = newLines.size() - 1; j >= 0; j--) {
                lcs[i][j] = oldLines.get(i).equals(newLines.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        StringBuilder diff = new StringBuilder("--- from\n+++ to\n");
        int oldIndex = 0;
        int newIndex = 0;
        while (oldIndex < oldLines.size() || newIndex < newLines.size()) {
            if (oldIndex < oldLines.size() && newIndex < newLines.size()
                    && oldLines.get(oldIndex).equals(newLines.get(newIndex))) {
                diff.append("  ").append(oldLines.get(oldIndex++)).append('\n');
            } else if (newIndex < newLines.size()
                    && (oldIndex == oldLines.size() || lcs[oldIndex][newIndex + 1] >= lcs[oldIndex + 1][newIndex])) {
                diff.append("+ ").append(newLines.get(newIndex++)).append('\n');
            } else {
                diff.append("- ").append(oldLines.get(oldIndex++)).append('\n');
            }
        }
        return diff.toString();
    }
}
