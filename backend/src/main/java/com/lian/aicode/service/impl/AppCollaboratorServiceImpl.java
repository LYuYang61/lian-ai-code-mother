package com.lian.aicode.service.impl;

import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import com.lian.aicode.mapper.AppCollaboratorMapper;
import com.lian.aicode.mapper.AppMapper;
import com.lian.aicode.model.dto.app.AppCollaboratorRequest;
import com.lian.aicode.model.entity.App;
import com.lian.aicode.model.entity.AppCollaborator;
import com.lian.aicode.model.entity.UserAccount;
import com.lian.aicode.model.enums.AppCollaboratorRoleEnum;
import com.lian.aicode.model.vo.AppCollaboratorVO;
import com.lian.aicode.service.AppCollaboratorService;
import com.lian.aicode.service.UserService;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 协作者权限实现；成员权限与应用运营权限严格分开。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppCollaboratorServiceImpl implements AppCollaboratorService {

    private final AppCollaboratorMapper collaboratorMapper;
    private final AppMapper appMapper;
    private final UserService userService;

    @Value("${app.collaboration.max-members:20}")
    private int maxMembers;

    @Override
    public boolean canView(App app, UserAccount user) {
        if (app == null || user == null) {
            return false;
        }
        if (isOwnerOrAdmin(app, user)) {
            return true;
        }
        return findActive(app.getId(), user.getId()) != null;
    }

    @Override
    public boolean canEdit(App app, UserAccount user) {
        if (app == null || user == null) {
            return false;
        }
        if (isOwner(app, user)) {
            return true;
        }
        AppCollaborator collaborator = findActive(app.getId(), user.getId());
        return collaborator != null && AppCollaboratorRoleEnum.EDITOR.getValue().equals(collaborator.getRole());
    }

    @Override
    public List<AppCollaboratorVO> list(Long appId, UserAccount operator) {
        App app = requireApp(appId);
        if (!canView(app, operator)) {
            log.warn("协作者列表权限校验失败：actor={}, appId={}, result=拒绝",
                    operator == null ? "<anonymous>" : operator.getUserAccount(), appId);
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权查看应用协作者");
        }
        List<AppCollaborator> collaborators = collaboratorMapper.selectListByQuery(
                QueryWrapper.create().eq("app_id", appId).orderBy("create_time", true));
        HashSet<Long> userIds = new HashSet<>();
        collaborators.stream().map(AppCollaborator::getUserId).filter(id -> id != null).forEach(userIds::add);
        Map<Long, UserAccount> userMap = new HashMap<>();
        userService.findAllByIds(userIds).forEach(user -> userMap.put(user.getId(), user));
        // 创建者是团队的一员，但按设计从不写入协作者表；列表首位返回 owner 角色，
        // 让协作者和管理员能看到完整团队构成，前端也不会对 owner 行提供移除操作。
        List<AppCollaboratorVO> members = new ArrayList<>();
        UserAccount owner = userService.getById(app.getUserId());
        if (owner != null) {
            members.add(AppCollaboratorVO.builder()
                    .appId(app.getId()).userId(owner.getId())
                    .userAccount(owner.getUserAccount()).userName(owner.getUserName())
                    .userAvatar(owner.getUserAvatar()).role("owner")
                    .createTime(app.getCreateTime() == null ? null : app.getCreateTime().toString())
                    .build());
        }
        collaborators.stream().map(item -> {
            UserAccount member = userMap.get(item.getUserId());
            return AppCollaboratorVO.builder()
                    .id(item.getId()).appId(item.getAppId()).userId(item.getUserId())
                    .userAccount(member == null ? null : member.getUserAccount())
                    .userName(member == null ? null : member.getUserName())
                    .userAvatar(member == null ? null : member.getUserAvatar())
                    .role(item.getRole()).createTime(item.getCreateTime() == null ? null : item.getCreateTime().toString())
                    .build();
        }).forEach(members::add);
        return members;
    }

    @Override
    public List<Long> listCollaboratedAppIds(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return collaboratorMapper.selectListByQuery(QueryWrapper.create()
                        .eq("user_id", userId).orderBy("create_time", false))
                .stream().map(AppCollaborator::getAppId).distinct().toList();
    }

    @Override
    @Transactional
    public boolean add(AppCollaboratorRequest request, UserAccount operator) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "协作者请求不能为空");
        }
        App app = requireApp(request.getAppId());
        requireOwnerOrAdmin(app, operator);
        Long targetUserId = request.getUserId();
        if (targetUserId == null) {
            // 前端更常用登录账号邀请协作者；雪花 id 过长容易抄错，账号在数据库层已保证全局唯一。
            if (!StringUtils.hasText(request.getUserAccount())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "请提供协作者的用户 id 或账号");
            }
            UserAccount byAccount = userService.getByAccount(request.getUserAccount().trim());
            if (byAccount == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "协作者账号不存在");
            }
            targetUserId = byAccount.getId();
        }
        if (app.getUserId().equals(targetUserId)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用创建者不需要添加为协作者");
        }
        if (userService.getById(targetUserId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "协作者用户不存在");
        }
        AppCollaboratorRoleEnum role;
        if (!StringUtils.hasText(request.getRole())) {
            role = AppCollaboratorRoleEnum.EDITOR;
        } else {
            role = AppCollaboratorRoleEnum.fromValue(request.getRole());
            if (role == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "协作者角色无效，只支持 viewer 或 editor");
            }
        }
        AppCollaborator existing = findActive(app.getId(), targetUserId);
        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            existing.setRole(role.getValue());
            existing.setInvitedBy(operator.getId());
            existing.setUpdateTime(now);
            boolean result = collaboratorMapper.update(existing) > 0;
            log.info("更新应用协作者：actor={}, appId={}, targetUserId={}, role={}, result={}",
                    operator.getUserAccount(), app.getId(), targetUserId, role.getValue(),
                    result ? "成功" : "失败");
            return result;
        }
        long memberCount = collaboratorMapper.selectCountByQuery(
                QueryWrapper.create().eq("app_id", app.getId()));
        if (memberCount >= maxMembers) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "协作者数量已达到上限");
        }
        AppCollaborator collaborator = AppCollaborator.builder()
                .appId(app.getId()).userId(targetUserId).role(role.getValue())
                .invitedBy(operator.getId()).createTime(now).updateTime(now).isDelete(0).build();
        try {
            boolean result = collaboratorMapper.insert(collaborator) > 0;
            log.info("应用协作者权限变更：actor={}, appId={}, targetUserId={}, role={}, result={}",
                    operator.getUserAccount(), app.getId(), targetUserId, role.getValue(),
                    result ? "成功" : "失败");
            return result;
        } catch (DuplicateKeyException exception) {
            // 并发邀请同一用户时，数据库唯一键是最终防线；向调用方返回可理解的业务错误。
            log.warn("添加应用协作者发生重复：actor={}, appId={}, targetUserId={}",
                    operator.getUserAccount(), app.getId(), targetUserId);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "该用户已经是应用协作者，请刷新后重试", exception);
        }
    }

    @Override
    @Transactional
    public boolean remove(AppCollaboratorRequest request, UserAccount operator) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "协作者请求不能为空");
        }
        App app = requireApp(request.getAppId());
        requireOwnerOrAdmin(app, operator);
        AppCollaborator collaborator = findActive(app.getId(), request.getUserId());
        if (collaborator == null) {
            log.info("移除应用协作者：actor={}, appId={}, targetUserId={}, result=不存在",
                    operator.getUserAccount(), app.getId(), request.getUserId());
            return false;
        }
        boolean result = collaboratorMapper.deleteById(collaborator.getId()) > 0;
        log.info("应用协作者权限变更：actor={}, appId={}, targetUserId={}, role={}, result={}",
                operator.getUserAccount(), app.getId(), request.getUserId(), collaborator.getRole(),
                result ? "移除成功" : "移除失败");
        return result;
    }

    @Override
    @Transactional
    public boolean deleteByAppId(Long appId) {
        if (appId == null || appId <= 0) {
            return false;
        }
        int deleted = collaboratorMapper.deleteByQuery(QueryWrapper.create().eq("app_id", appId));
        log.info("清理应用协作者：appId={}, count={}", appId, deleted);
        return deleted >= 0;
    }

    private App requireApp(Long appId) {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 id 无效");
        }
        App app = appMapper.selectOneByQuery(QueryWrapper.create().eq("id", appId));
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        return app;
    }

    private boolean isOwnerOrAdmin(App app, UserAccount user) {
        return user != null && (app.getUserId().equals(user.getId()) || userService.isAdmin(user));
    }

    private boolean isOwner(App app, UserAccount user) {
        return user != null && app.getUserId() != null && app.getUserId().equals(user.getId());
    }

    private void requireOwnerOrAdmin(App app, UserAccount user) {
        if (!isOwnerOrAdmin(app, user)) {
            log.warn("协作者管理权限校验失败：actor={}, appId={}, result=拒绝",
                    user == null ? "<anonymous>" : user.getUserAccount(), app.getId());
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有应用创建者或管理员可以管理协作者");
        }
    }

    private AppCollaborator findActive(Long appId, Long userId) {
        return collaboratorMapper.selectOneByQuery(QueryWrapper.create()
                .eq("app_id", appId).eq("user_id", userId));
    }
}
