package com.lian.aicode.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lian.aicode.ai.AiCodeGeneratorService;
import com.lian.aicode.ai.model.AppNameResult;
import com.lian.aicode.core.AiCodeGeneratorFacade;
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
import com.lian.aicode.service.UserService;
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
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final ObjectProvider<AiCodeGeneratorService> aiCodeGeneratorServiceProvider;
    private final ObjectMapper objectMapper;
    private final AppStorageService storageService;
    private final GenerationTaskManager taskManager;

    @Value("${app.storage.max-prompt-length:10000}")
    private int maxPromptLength;

    @Value("${app.deploy.public-base-url:http://localhost:8123/api/site}")
    private String deployPublicBaseUrl;

    @Value("${server.servlet.context-path:/api}")
    private String serverContextPath;

    @Override
    public Long createApp(AppAddRequest request, UserAccount loginUser) {
        requireLogin(loginUser);
        String prompt = normalizePrompt(request.getInitPrompt());
        CodeGenTypeEnum codeGenType = parseCodeGenType(request.getCodeGenType());
        String visibility = normalizeVisibility(request.getVisibility());

        App app = new App();
        // 应用命名是外部模型调用，不能放在数据库事务里持有连接；创建阶段只有一条应用记录写入。
        app.setAppName(generateAppName(prompt));
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
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "创建应用失败");
        }
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
        return updateById(update);
    }

    @Override
    @Transactional
    public boolean adminUpdateApp(AppAdminUpdateRequest request) {
        App app = requireApp(request.getId());
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
        return updateById(update);
    }

    @Override
    @Transactional
    public boolean deleteApp(Long appId, UserAccount loginUser) {
        requireLogin(loginUser);
        App app = requireApp(appId);
        assertOwnerOrAdmin(app, loginUser);
        taskManager.cancel(appId);
        deleteRelatedData(appId);
        chatHistoryService.deleteByAppId(appId);
        collaboratorService.deleteByAppId(appId);
        boolean removed = removeById(appId);
        if (removed) {
            // 数据库事务回滚时不能提前删除 Redis 记忆和磁盘文件；提交成功后再做外部资源清理。
            registerDeleteCleanup(appId, app.getDeployKey());
        }
        return removed;
    }

    @Override
    public Flux<String> chatToGenCode(Long appId, String message, UserAccount loginUser) {
        requireLogin(loginUser);
        App app = requireApp(appId);
        if (!collaboratorService.canEdit(app, loginUser)) {
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

        GenerationTaskManager.GenerationTask task = taskManager.start(appId);
        AppVersion version;
        com.lian.aicode.model.entity.ChatHistory userHistory;
        try {
            version = reserveVersion(app, prompt, codeGenType, loginUser.getId());
            try {
                userHistory = chatHistoryService.addMessage(appId, loginUser.getId(), prompt,
                        ChatHistoryMessageTypeEnum.USER, null, version.getVersionNo(), null);
                if (mapper.incrementConversationRounds(appId) <= 0) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "更新对话轮次失败");
                }
            } catch (RuntimeException exception) {
                // 版本已预留但用户消息写入失败时不能留下“永远生成中”的孤儿任务。
                markGenerationFailed(appId, version, null, exception);
                throw exception;
            }
        } catch (RuntimeException exception) {
            taskManager.finish(appId, task);
            throw exception;
        }

        StringBuilder aiMessage = new StringBuilder();
        AtomicBoolean sourceCompleted = new AtomicBoolean(false);
        AtomicBoolean stateMarked = new AtomicBoolean(false);
        Flux<String> source = aiCodeGeneratorFacade.generateAndSaveCodeStream(
                        appId, prompt, codeGenType,
                        storageService.versionDirectory(appId, version.getVersionNo()), userHistory.getId())
                .doOnNext(chunk -> {
                    if (chunk != null && aiMessage.length() < MAX_CHAT_HISTORY_MESSAGE_LENGTH) {
                        int remaining = MAX_CHAT_HISTORY_MESSAGE_LENGTH - aiMessage.length();
                        aiMessage.append(chunk, 0, Math.min(chunk.length(), remaining));
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
                                markGenerationReady(appId, version, userHistory.getId(), aiMessage.toString());
                            } else {
                                markGenerationCancelled(appId, version, userHistory.getId());
                            }
                        } catch (RuntimeException completionError) {
                            // 终态收口失败时，不能因为 stateMarked 已置位而遗留 generating 孤儿版本。
                            try {
                                markGenerationFailed(appId, version, userHistory.getId(), completionError);
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
                        markGenerationFailed(appId, version, userHistory.getId(), error);
                    }
                })
                .doFinally(signal -> {
                    // 浏览器断开 SSE 时，上游收到 cancel，不会触发 doOnComplete；仍需收口数据库状态。
                    if (signal == SignalType.CANCEL && stateMarked.compareAndSet(false, true)) {
                        markGenerationCancelled(appId, version, userHistory.getId());
                    }
                    taskManager.finish(appId, task);
                });
    }

    @Override
    public boolean stopGeneration(Long appId, UserAccount loginUser) {
        requireLogin(loginUser);
        App app = requireApp(appId);
        if (!collaboratorService.canEdit(app, loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有应用创建者或编辑协作者可以停止生成");
        }
        return taskManager.cancel(appId);
    }

    @Override
    @Transactional
    public String deployApp(Long appId, UserAccount loginUser) {
        requireLogin(loginUser);
        App app = requireApp(appId);
        assertOwnerOrAdmin(app, loginUser);
        return deployReadyVersion(appId, app);
    }

    /** 执行部署文件切换并保存数据库状态；调用方必须已经完成权限校验。 */
    private String deployReadyVersion(Long appId, App app) {
        AppVersion version = requireReadyVersion(appId, app.getCurrentVersion());
        Path sourceDirectory = storageService.resolveVersionPath(version.getRelativePath());
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
        } catch (RuntimeException exception) {
            if (fileSwitched) {
                restoreDeploymentAfterDatabaseFailure(appId, oldDeployKey, oldVersionNo, deployKey);
            }
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
            return false;
        }
        App update = new App();
        update.setId(appId);
        update.setDeploymentStatus(AppDeploymentStatusEnum.PAUSED.getValue());
        update.setUpdateTime(LocalDateTime.now());
        return updateById(update);
    }

    @Override
    @Transactional
    public String enableDeployment(Long appId, UserAccount loginUser) {
        requireLogin(loginUser);
        App app = requireApp(appId);
        assertOwnerOrAdmin(app, loginUser);
        return deployReadyVersion(appId, app);
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

    @Override
    @Transactional
    public boolean rollback(Long appId, Integer versionNo, UserAccount loginUser) {
        requireLogin(loginUser);
        App app = requireApp(appId);
        assertOwnerOrAdmin(app, loginUser);
        AppVersion targetVersion = requireReadyVersion(appId, versionNo);
        Integer oldVersionNo = app.getDeployedVersion() == null
                ? app.getCurrentVersion() : app.getDeployedVersion();
        // 如果当前应用已在线，回滚必须同步替换部署目录，否则数据库显示的当前版本和线上内容会不一致。
        if (AppDeploymentStatusEnum.DEPLOYED.getValue().equals(app.getDeploymentStatus())) {
            if (!StringUtils.hasText(app.getDeployKey())) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "部署状态异常，缺少部署标识");
            }
            storageService.deploy(storageService.resolveVersionPath(targetVersion.getRelativePath()), app.getDeployKey());
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
            return true;
        } catch (RuntimeException exception) {
            if (AppDeploymentStatusEnum.DEPLOYED.getValue().equals(app.getDeploymentStatus())) {
                restoreDeploymentAfterDatabaseFailure(appId, app.getDeployKey(), oldVersionNo, app.getDeployKey());
            }
            throw exception;
        }
    }

    @Override
    public AppVersionDiffVO diff(Long appId, Integer fromVersion, Integer toVersion, UserAccount loginUser) {
        App app = requireApp(appId);
        assertReadable(app, loginUser);
        if (!canManage(app, loginUser) && !collaboratorService.canEdit(app, loginUser)) {
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
        return updateById(update);
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
        return storageService.resolveVersionPath(version.getRelativePath());
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
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "请先登录后访问私有应用");
        }
        throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
    }

    private void assertOwnerOrAdmin(App app, UserAccount user) {
        if (!isOwner(app, user) && !userService.isAdmin(user)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限操作该应用");
        }
    }

    private void assertOwner(App app, UserAccount user) {
        if (!isOwner(app, user)) {
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
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "请先登录");
        }
    }

    private AppVersion reserveVersion(App app, String prompt, CodeGenTypeEnum type, Long userId) {
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
        return version;
    }

    private void markGenerationReady(Long appId, AppVersion version, Long parentId, String aiMessage) {
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
        if (StringUtils.hasText(aiMessage)) {
            try {
                chatHistoryService.addMessage(appId, version.getCreatedBy(), aiMessage,
                        ChatHistoryMessageTypeEnum.AI, parentId, version.getVersionNo(),
                        toJson(storageService.listRelativeFiles(storageService.versionDirectory(
                                appId, version.getVersionNo()))));
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

    private void markGenerationCancelled(Long appId, AppVersion version, Long parentId) {
        AppVersion updateVersion = new AppVersion();
        updateVersion.setId(version.getId());
        updateVersion.setStatus(AppVersionStatusEnum.CANCELLED.getValue());
        updateVersion.setDescription("用户停止了本次生成");
        updateVersion.setUpdateTime(LocalDateTime.now());
        if (appVersionMapper.update(updateVersion) <= 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "保存代码版本取消状态失败");
        }
        storageService.deleteVersionDirectory(appId, version.getVersionNo());

        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setGenerationStatus(AppGenerationStatusEnum.CANCELLED.getValue());
        updateApp.setGenerationMessage("生成已取消");
        updateApp.setUpdateTime(LocalDateTime.now());
        if (!updateById(updateApp)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "保存应用生成取消状态失败");
        }
        recordGenerationEvent(appId, version, parentId, "本次生成已取消");
    }

    private void markGenerationFailed(Long appId, AppVersion version, Long parentId, Throwable error) {
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

        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setGenerationStatus(AppGenerationStatusEnum.FAILED.getValue());
        updateApp.setGenerationMessage("生成失败，请检查模型配置或稍后重试");
        updateApp.setUpdateTime(LocalDateTime.now());
        if (!updateById(updateApp)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "保存应用生成失败状态失败");
        }
        // 不把模型供应商、数据库或文件系统异常原文写入用户可见历史，避免泄露内部信息。
        recordGenerationEvent(appId, version, parentId, "本次生成失败，请检查模型配置或稍后重试");
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
        appVersionMapper.deleteByQuery(QueryWrapper.create().eq("app_id", appId));
    }

    /**
     * 注册删除后的外部资源清理。
     *
     * <p>数据库逻辑删除和磁盘/Redis 不属于同一个事务；只有提交成功后才清理外部资源，
     * 避免数据库回滚后应用记录还在、但代码文件和会话记忆已经消失。</p>
     */
    private void registerDeleteCleanup(Long appId, String deployKey) {
        Runnable cleanup = () -> {
            try {
                aiCodeGeneratorFacade.evictAppMemory(appId);
                storageService.deleteApplicationFiles(appId);
                storageService.deleteDeployment(deployKey);
                log.info("删除应用外部资源清理完成：appId={}, deployKeyPresent={}", appId,
                        StringUtils.hasText(deployKey));
            } catch (RuntimeException exception) {
                // 文件和缓存清理是补偿动作；失败不能逆转已提交的数据库删除，但必须可追踪。
                log.error("删除应用外部资源清理失败：appId={}", appId, exception);
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

    /** 数据库状态保存失败时尽量恢复部署目录，避免“数据库未切换、线上文件已切换”。 */
    private void restoreDeploymentAfterDatabaseFailure(Long appId, String oldDeployKey,
                                                       Integer oldVersionNo, String switchedDeployKey) {
        try {
            AppVersion oldVersion = oldVersionNo == null || oldVersionNo <= 0 ? null
                    : appVersionMapper.selectOneByQuery(QueryWrapper.create()
                    .eq("app_id", appId).eq("version_no", oldVersionNo)
                    .eq("status", AppVersionStatusEnum.READY.getValue()));
            if (StringUtils.hasText(oldDeployKey) && oldVersion != null) {
                storageService.deploy(storageService.resolveVersionPath(oldVersion.getRelativePath()), oldDeployKey);
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

    private String generateAppName(String prompt) {
        AiCodeGeneratorService aiService = aiCodeGeneratorServiceProvider.getIfAvailable();
        if (aiService != null) {
            try {
                AppNameResult name = aiService.generateAppName(prompt);
                if (name != null && StringUtils.hasText(name.getAppName())) {
                    String clean = name.getAppName().replaceAll("[\\r\\n`\\\"']", "").trim();
                    if (!clean.isBlank()) {
                        return clean.substring(0, Math.min(clean.length(), 64));
                    }
                }
            } catch (RuntimeException exception) {
                log.info("AI 应用命名失败，使用本地回退名称：errorType={}",
                        exception.getClass().getSimpleName());
            }
        }
        String fallback = prompt.replaceAll("\\s+", " ").trim();
        return fallback.substring(0, Math.min(fallback.length(), 20));
    }

    private CodeGenTypeEnum parseCodeGenType(String value) {
        if (!StringUtils.hasText(value)) {
            return CodeGenTypeEnum.HTML;
        }
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(value.trim());
        if (type == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码生成类型无效");
        }
        return type;
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

    private String stringValue(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private Set<String> listVersionFiles(AppVersion version) {
        Path root = storageService.resolveVersionPath(version.getRelativePath());
        if (!Files.isDirectory(root) || !isInsideCodeOutputRoot(root)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "代码版本目录不存在");
        }
        Set<String> fileNames = new TreeSet<>();
        try (var paths = Files.walk(root)) {
            paths.filter(path -> Files.isRegularFile(path) && !Files.isSymbolicLink(path))
                    .map(root::relativize)
                    .map(Path::normalize)
                    .filter(path -> !path.startsWith(".."))
                    .map(path -> path.toString().replace('\\', '/'))
                    .forEach(fileNames::add);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "读取版本文件列表失败", exception);
        }
        return fileNames;
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
