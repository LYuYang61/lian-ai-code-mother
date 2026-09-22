package com.lian.aicode.service;

import com.lian.aicode.model.dto.user.UserAdminUpdateRequest;
import com.lian.aicode.model.dto.user.UserQueryRequest;
import com.lian.aicode.model.dto.user.UserRegisterRequest;
import com.lian.aicode.model.dto.user.UserUpdateRequest;
import com.lian.aicode.model.entity.UserAccount;
import com.lian.aicode.model.vo.LoginUserVO;
import com.lian.aicode.model.vo.PageResult;
import com.lian.aicode.model.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Collection;

/** 用户注册、登录态和公开信息服务。 */
public interface UserService {

    Long register(UserRegisterRequest request);

    LoginUserVO login(String userAccount, String userPassword, HttpServletRequest request);

    UserAccount getLoginUser(HttpServletRequest request);

    LoginUserVO getLoginUserVO(UserAccount user);

    UserVO getUserVO(UserAccount user);

    List<UserVO> getUserVOList(List<UserAccount> users);

    /** 批量查询用户，供应用列表组装所有者信息，避免逐条查询造成 N+1。 */
    List<UserAccount> findAllByIds(Collection<Long> ids);

    boolean logout(HttpServletRequest request);

    UserAccount getById(Long id);

    UserAccount getByAccount(String account);

    UserAccount updateProfile(UserUpdateRequest request, UserAccount loginUser);

    boolean isAdmin(UserAccount user);

    PageResult<UserVO> listUsers(UserQueryRequest request);

    boolean adminUpdate(UserAdminUpdateRequest request, UserAccount operator);

    boolean deleteUser(Long id, UserAccount operator);
}
