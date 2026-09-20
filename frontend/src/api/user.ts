import type { BaseResponse, LoginUser, PageResult, UserVO } from '@/api/types'
import request from '@/request'

export interface UserRegisterRequest {
  userAccount: string
  userPassword: string
  userName?: string
}

export interface UserLoginRequest {
  userAccount: string
  userPassword: string
}

export function register(data: UserRegisterRequest) {
  return request.post<BaseResponse<string>>('/user/register', data)
}

export function login(data: UserLoginRequest) {
  return request.post<BaseResponse<LoginUser>>('/user/login', data)
}

export function getLoginUser() {
  return request.get<BaseResponse<LoginUser>>('/user/get/login')
}

export function logout() {
  return request.post<BaseResponse<boolean>>('/user/logout')
}

export function updateUser(data: {
  id: string
  userName?: string
  userAvatar?: string
  userProfile?: string
}) {
  return request.post<BaseResponse<UserVO>>('/user/update', data)
}

export function listAdminUsers(data: { pageNum: number; pageSize: number; userAccount?: string; userRole?: string }) {
  return request.post<BaseResponse<PageResult<UserVO>>>('/user/admin/list/page/vo', data)
}

export function adminUpdateUser(data: {
  id: string
  userName?: string
  userAvatar?: string
  userProfile?: string
  userRole?: 'user' | 'admin'
}) {
  return request.post<BaseResponse<boolean>>('/user/admin/update', data)
}

export function adminDeleteUser(id: string) {
  return request.delete<BaseResponse<boolean>>('/user/admin/delete', { params: { id } })
}
