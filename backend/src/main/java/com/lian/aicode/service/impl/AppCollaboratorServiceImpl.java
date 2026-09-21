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
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权查看应用协作者");
        }
        List<AppCollaborator> collaborators = collaboratorMapper.selectListByQuery(
                QueryWrapper.create().eq("app_id", appId).orderBy("create_time", true));
        HashSet<Long> userIds = new HashSet<>();
        collaborators.stream().map(AppCollaborator::getUserId).filter(id -> id != null).forEach(userIds::add);
        Map<Long, UserAccount> userMap = new HashMap<>();
        userService.findAllByIds(userIds).forEach(user -> userMap.put(user.getId(), user));
        return collaborators.stream().map(item -> {
            UserAccount member = userMap.get(item.getUserId());
            return AppCollaboratorVO.builder()
                    .id(item.getId()).appId(item.getAppId()).userId(item.getUserId())
                    .userAccount(member == null ? null : member.getUserAccount())
                    .userName(member == null ? null : member.getUserName())
                    .userAvatar(member == null ? null : member.getUserAvatar())
                    .role(item.getRole()).createTime(item.getCreateTime() == null ? null : item.getCreateTime().toString())
                    .build();
        }).toList();
    }

    @Override
    @Transactional
    public boolean add(AppCollaboratorRequest request, UserAccount operator) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "协作者请求不能为空");
        }
        App app = requireApp(request.getAppId());
        requireOwnerOrAdmin(app, operator);
        if (app.getUserId().equals(request.getUserId())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用创建者不需要添加为协作者");
        }
        if (userService.getById(request.getUserId()) == null) {
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
        AppCollaborator existing = findActive(app.getId(), request.getUserId());
        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            existing.setRole(role.getValue());
            existing.setInvitedBy(operator.getId());
            existing.setUpdateTime(now);
            log.info("更新应用协作者：appId={}, userId={}, role={}", app.getId(), request.getUserId(), role.getValue());
            return collaboratorMapper.update(existing) > 0;
        }
        long memberCount = collaboratorMapper.selectCountByQuery(
                QueryWrapper.create().eq("app_id", app.getId()));
        if (memberCount >= maxMembers) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "协作者数量已达到上限");
        }
        AppCollaborator collaborator = AppCollaborator.builder()
                .appId(app.getId()).userId(request.getUserId()).role(role.getValue())
                .invitedBy(operator.getId()).createTime(now).updateTime(now).isDelete(0).build();
        try {
            boolean result = collaboratorMapper.insert(collaborator) > 0;
            log.info("添加应用协作者：appId={}, userId={}, role={}, success={}",
                    app.getId(), request.getUserId(), role.getValue(), result);
            return result;
        } catch (DuplicateKeyException exception) {
            // 并发邀请同一用户时，数据库唯一键是最终防线；向调用方返回可理解的业务错误。
            log.warn("添加应用协作者发生重复：appId={}, userId={}", app.getId(), request.getUserId());
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
            return false;
        }
        boolean result = collaboratorMapper.deleteById(collaborator.getId()) > 0;
        log.info("移除应用协作者：appId={}, userId={}, success={}", app.getId(), request.getUserId(), result);
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
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有应用创建者或管理员可以管理协作者");
        }
    }

    private AppCollaborator findActive(Long appId, Long userId) {
        return collaboratorMapper.selectOneByQuery(QueryWrapper.create()
                .eq("app_id", appId).eq("user_id", userId));
    }
}
