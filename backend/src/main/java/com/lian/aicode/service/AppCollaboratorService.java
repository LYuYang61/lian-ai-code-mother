package com.lian.aicode.service;

import com.lian.aicode.model.dto.app.AppCollaboratorRequest;
import com.lian.aicode.model.entity.App;
import com.lian.aicode.model.entity.UserAccount;
import com.lian.aicode.model.vo.AppCollaboratorVO;

import java.util.List;

/** 应用协作者权限和成员管理。 */
public interface AppCollaboratorService {

    boolean canView(App app, UserAccount user);

    boolean canEdit(App app, UserAccount user);

    List<AppCollaboratorVO> list(Long appId, UserAccount operator);

    /** 当前用户作为协作者参与的全部应用 id（含 viewer 和 editor），按加入时间倒序。 */
    List<Long> listCollaboratedAppIds(Long userId);

    boolean add(AppCollaboratorRequest request, UserAccount operator);

    boolean remove(AppCollaboratorRequest request, UserAccount operator);

    boolean deleteByAppId(Long appId);
}
