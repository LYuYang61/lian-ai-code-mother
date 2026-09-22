package com.lian.aicode.controller;

import com.lian.aicode.common.BaseResponse;
import com.lian.aicode.common.ResultUtils;
import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import com.lian.aicode.model.dto.user.UserAdminUpdateRequest;
import com.lian.aicode.model.dto.user.UserLoginRequest;
import com.lian.aicode.model.dto.user.UserQueryRequest;
import com.lian.aicode.model.dto.user.UserRegisterRequest;
import com.lian.aicode.model.dto.user.UserUpdateRequest;
import com.lian.aicode.model.entity.UserAccount;
import com.lian.aicode.model.vo.LoginUserVO;
import com.lian.aicode.model.vo.PageResult;
import com.lian.aicode.model.vo.UserVO;
import com.lian.aicode.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 用户注册、登录和管理员用户维护接口。 */
@Tag(name = "用户接口")
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserService userService;

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public BaseResponse<Long> register(@Valid @RequestBody UserRegisterRequest request) {
        Long userId = userService.register(request);
        log.info("用户注册接口完成：actor={}, userId={}, result=成功", request.getUserAccount(), userId);
        return ResultUtils.success(userId);
    }

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public BaseResponse<LoginUserVO> login(@Valid @RequestBody UserLoginRequest request,
                                           HttpServletRequest httpRequest) {
        LoginUserVO result = userService.login(request.getUserAccount(), request.getUserPassword(), httpRequest);
        log.info("用户登录接口完成：actor={}, userId={}, result=成功", result.getUserAccount(), result.getId());
        return ResultUtils.success(result);
    }

    @Operation(summary = "获取当前登录用户")
    @GetMapping("/get/login")
    public BaseResponse<LoginUserVO> getLoginUser(HttpServletRequest request) {
        return ResultUtils.success(userService.getLoginUserVO(userService.getLoginUser(request)));
    }

    @Operation(summary = "用户退出登录")
    @PostMapping("/logout")
    public BaseResponse<Boolean> logout(HttpServletRequest request) {
        return ResultUtils.success(userService.logout(request));
    }

    @Operation(summary = "更新个人资料")
    @PostMapping("/update")
    public BaseResponse<UserVO> update(@Valid @RequestBody UserUpdateRequest request,
                                       HttpServletRequest httpRequest) {
        UserAccount loginUser = userService.getLoginUser(httpRequest);
        return ResultUtils.success(userService.getUserVO(userService.updateProfile(request, loginUser)));
    }

    @Operation(summary = "查看用户公开信息")
    @GetMapping("/get/vo")
    public BaseResponse<UserVO> getVO(@RequestParam Long id) {
        UserAccount user = userService.getById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        }
        return ResultUtils.success(userService.getUserVO(user));
    }

    @Operation(summary = "管理员分页查询用户")
    @PostMapping("/admin/list/page/vo")
    public BaseResponse<PageResult<UserVO>> adminList(@Valid @RequestBody UserQueryRequest request,
                                                     HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        return ResultUtils.success(userService.listUsers(request));
    }

    @Operation(summary = "管理员更新用户")
    @PostMapping("/admin/update")
    public BaseResponse<Boolean> adminUpdate(@Valid @RequestBody UserAdminUpdateRequest request,
                                             HttpServletRequest httpRequest) {
        UserAccount admin = requireAdmin(httpRequest);
        boolean result = userService.adminUpdate(request, admin);
        log.info("管理员更新用户：actor={}, targetUserId={}, role={}, result={}",
                admin.getUserAccount(), request.getId(), request.getUserRole(), result ? "成功" : "失败");
        return ResultUtils.success(result);
    }

    @Operation(summary = "管理员删除用户")
    @DeleteMapping("/admin/delete")
    public BaseResponse<Boolean> adminDelete(@RequestParam Long id, HttpServletRequest httpRequest) {
        UserAccount admin = requireAdmin(httpRequest);
        if (admin.getId().equals(id)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不能删除当前管理员账号");
        }
        boolean result = userService.deleteUser(id, admin);
        log.info("管理员删除用户：actor={}, targetUserId={}, result={}",
                admin.getUserAccount(), id, result ? "成功" : "失败");
        return ResultUtils.success(result);
    }

    private UserAccount requireAdmin(HttpServletRequest request) {
        UserAccount loginUser = userService.getLoginUser(request);
        if (!userService.isAdmin(loginUser)) {
            log.warn("管理员权限校验失败：actor={}, result=拒绝", loginUser.getUserAccount());
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "需要管理员权限");
        }
        return loginUser;
    }
}
