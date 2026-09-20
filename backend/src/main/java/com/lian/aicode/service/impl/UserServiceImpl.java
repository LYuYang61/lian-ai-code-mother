package com.lian.aicode.service.impl;

import com.lian.aicode.constant.UserConstant;
import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import com.lian.aicode.mapper.UserAccountMapper;
import com.lian.aicode.model.dto.user.UserRegisterRequest;
import com.lian.aicode.model.dto.user.UserAdminUpdateRequest;
import com.lian.aicode.model.dto.user.UserQueryRequest;
import com.lian.aicode.model.dto.user.UserUpdateRequest;
import com.lian.aicode.model.entity.UserAccount;
import com.lian.aicode.model.enums.UserRoleEnum;
import com.lian.aicode.model.vo.LoginUserVO;
import com.lian.aicode.model.vo.PageResult;
import com.lian.aicode.model.vo.UserVO;
import com.lian.aicode.service.UserService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 用户服务实现。
 *
 * <p>Session 只保存用户 ID，读取业务数据时重新查询数据库，避免角色被修改后仍使用旧的
 * Session 对象；密码校验使用 BCrypt 的 matches，不把哈希结果反向当作明文密码。</p>
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserAccountMapper, UserAccount> implements UserService {

    private final PasswordEncoder passwordEncoder;

    @Override
    public Long register(UserRegisterRequest request) {
        String account = request.getUserAccount().trim();
        if (getByAccount(account) != null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号已存在");
        }
        UserAccount user = new UserAccount();
        user.setUserAccount(account);
        user.setUserPassword(passwordEncoder.encode(request.getUserPassword()));
        user.setUserName(StringUtils.hasText(request.getUserName()) ? request.getUserName().trim() : account);
        user.setUserRole(UserRoleEnum.USER.getValue());
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(user.getCreateTime());
        user.setEditTime(user.getCreateTime());
        user.setIsDelete(0);
        if (!save(user)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "注册失败");
        }
        return user.getId();
    }

    @Override
    public LoginUserVO login(String userAccount, String userPassword, HttpServletRequest request) {
        UserAccount user = getByAccount(userAccount.trim());
        if (user == null || !passwordEncoder.matches(userPassword, user.getUserPassword())) {
            // 不区分账号不存在和密码错误，避免泄露账号枚举信息。
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号或密码错误");
        }
        request.getSession(true).setAttribute(UserConstant.USER_LOGIN_STATE, user.getId());
        return getLoginUserVO(user);
    }

    @Override
    public UserAccount getLoginUser(HttpServletRequest request) {
        Object userId = request.getSession(false) == null
                ? null : request.getSession(false).getAttribute(UserConstant.USER_LOGIN_STATE);
        if (userId == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "请先登录");
        }
        try {
            UserAccount user = getById(Long.parseLong(userId.toString()));
            if (user == null) {
                throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "登录状态已失效");
            }
            return user;
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "登录状态无效");
        }
    }

    @Override
    public LoginUserVO getLoginUserVO(UserAccount user) {
        if (user == null) {
            return null;
        }
        return LoginUserVO.builder()
                .id(user.getId())
                .userAccount(user.getUserAccount())
                .userName(user.getUserName())
                .userAvatar(user.getUserAvatar())
                .userProfile(user.getUserProfile())
                .userRole(user.getUserRole())
                .build();
    }

    @Override
    public UserVO getUserVO(UserAccount user) {
        if (user == null) {
            return null;
        }
        return UserVO.builder()
                .id(user.getId())
                .userAccount(user.getUserAccount())
                .userName(user.getUserName())
                .userAvatar(user.getUserAvatar())
                .userProfile(user.getUserProfile())
                .userRole(user.getUserRole())
                .createTime(user.getCreateTime() == null ? null : user.getCreateTime().toString())
                .build();
    }

    @Override
    public List<UserVO> getUserVOList(List<UserAccount> users) {
        return users == null ? List.of() : users.stream().map(this::getUserVO).toList();
    }

    @Override
    public List<UserAccount> findAllByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mapper.selectListByQuery(QueryWrapper.create().in("id", ids));
    }

    @Override
    public boolean logout(HttpServletRequest request) {
        if (request.getSession(false) == null) {
            return true;
        }
        request.getSession(false).invalidate();
        return true;
    }

    @Override
    public UserAccount getById(Long id) {
        return id == null ? null : mapper.selectOneById(id);
    }

    @Override
    public UserAccount getByAccount(String account) {
        if (!StringUtils.hasText(account)) {
            return null;
        }
        return mapper.selectOneByQuery(QueryWrapper.create().eq("user_account", account));
    }

    @Override
    public UserAccount updateProfile(UserUpdateRequest request, UserAccount loginUser) {
        if (!loginUser.getId().equals(request.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只能修改自己的资料");
        }
        UserAccount update = new UserAccount();
        update.setId(loginUser.getId());
        update.setUserName(trimToNull(request.getUserName()));
        update.setUserAvatar(trimToNull(request.getUserAvatar()));
        update.setUserProfile(trimToNull(request.getUserProfile()));
        update.setEditTime(LocalDateTime.now());
        update.setUpdateTime(update.getEditTime());
        if (!updateById(update)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "更新用户资料失败");
        }
        return getById(loginUser.getId());
    }

    @Override
    public boolean isAdmin(UserAccount user) {
        return user != null && UserRoleEnum.isAdmin(user.getUserRole());
    }

    @Override
    public PageResult<UserVO> listUsers(UserQueryRequest request) {
        QueryWrapper wrapper = QueryWrapper.create();
        if (StringUtils.hasText(request.getUserAccount())) {
            wrapper.like("user_account", request.getUserAccount().trim());
        }
        if (StringUtils.hasText(request.getUserRole())) {
            wrapper.eq("user_role", request.getUserRole().trim());
        }
        wrapper.orderBy("create_time", false);
        long pageNum = Math.max(1, request.getPageNum());
        long pageSize = Math.min(Math.max(1, request.getPageSize()), 50);
        Page<UserAccount> page = mapper.paginate(Page.of(pageNum, pageSize), wrapper);
        long total = page.getTotalRow();
        return new PageResult<>(getUserVOList(page.getRecords()), pageNum, pageSize, total,
                total == 0 ? 0 : (total + pageSize - 1) / pageSize);
    }

    @Override
    public boolean adminUpdate(UserAdminUpdateRequest request) {
        if (request.getUserRole() != null
                && !UserRoleEnum.USER.getValue().equals(request.getUserRole())
                && !UserRoleEnum.ADMIN.getValue().equals(request.getUserRole())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户角色无效");
        }
        UserAccount update = new UserAccount();
        update.setId(request.getId());
        if (request.getUserName() != null) {
            update.setUserName(trimToNull(request.getUserName()));
        }
        if (request.getUserAvatar() != null) {
            update.setUserAvatar(trimToNull(request.getUserAvatar()));
        }
        if (request.getUserProfile() != null) {
            update.setUserProfile(trimToNull(request.getUserProfile()));
        }
        if (request.getUserRole() != null) {
            update.setUserRole(request.getUserRole());
        }
        update.setEditTime(LocalDateTime.now());
        update.setUpdateTime(update.getEditTime());
        return updateById(update);
    }

    @Override
    public boolean deleteUser(Long id) {
        return id != null && removeById(id);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
