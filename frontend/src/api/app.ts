import type {
  AppVO,
  AppVersionVO,
  AppVersionDiffVO,
  AppCollaboratorRole,
  AppCollaboratorVO,
  BaseResponse,
  ChatHistoryVO,
  CodeGenType,
  PageResult,
} from '@/api/types'
import { API_BASE_URL } from '@/config/env'
import request from '@/request'

export interface AppQueryRequest {
  pageNum: number
  pageSize: number
  searchText?: string
  category?: string
  tag?: string
  appName?: string
  tags?: string
  visibility?: string
  generationStatus?: string
  currentVersion?: number
  generationMessage?: string
  deploymentStatus?: string
  featuredStatus?: string
  featuredReason?: string
  id?: string
  cover?: string
  initPrompt?: string
  codeGenType?: CodeGenType
  deployKey?: string
  priority?: number
  userId?: string
  sortField?: string
  sortOrder?: string
}

export function createApp(data: {
  initPrompt: string
  codeGenType: CodeGenType
  category?: string
  tags?: string
  visibility: 'private' | 'public'
}) {
  return request.post<BaseResponse<string>>('/app/add', data)
}

export function getApp(id: string) {
  return request.get<BaseResponse<AppVO>>('/app/get/vo', { params: { id } })
}

export function listMyApps(data: AppQueryRequest) {
  return request.post<BaseResponse<PageResult<AppVO>>>('/app/my/list/page/vo', data)
}

export function listFeaturedApps(data: AppQueryRequest) {
  return request.post<BaseResponse<PageResult<AppVO>>>('/app/good/list/page/vo', data)
}

export function updateApp(data: {
  id: string
  appName?: string
  category?: string
  tags?: string
  visibility?: 'private' | 'public'
}) {
  return request.post<BaseResponse<boolean>>('/app/update', data)
}

export function deleteApp(id: string) {
  return request.post<BaseResponse<boolean>>('/app/delete', { id })
}

export function listVersions(appId: string) {
  return request.get<BaseResponse<AppVersionVO[]>>('/app/version/list', { params: { appId } })
}

export function rollbackVersion(appId: string, versionNo: number) {
  return request.post<BaseResponse<boolean>>('/app/version/rollback', { appId, versionNo })
}

export function diffVersions(appId: string, fromVersion: number, toVersion: number) {
  return request.get<BaseResponse<AppVersionDiffVO>>('/app/version/diff', {
    params: { appId, fromVersion, toVersion },
  })
}

export function listHistory(appId: string) {
  return request.get<BaseResponse<ChatHistoryVO[]>>('/app/chat/history', { params: { appId } })
}

export function listCollaborators(appId: string) {
  return request.get<BaseResponse<AppCollaboratorVO[]>>('/app/collaborator/list', { params: { appId } })
}

export function addCollaborator(data: { appId: string; userId: string; role: AppCollaboratorRole }) {
  return request.post<BaseResponse<boolean>>('/app/collaborator/add', data)
}

export function removeCollaborator(data: { appId: string; userId: string }) {
  return request.post<BaseResponse<boolean>>('/app/collaborator/remove', data)
}

export function deployApp(appId: string) {
  return request.post<BaseResponse<string>>('/app/deploy', { appId })
}

export function disableDeployment(appId: string) {
  return request.post<BaseResponse<boolean>>('/app/deploy/disable', { appId })
}

export function enableDeployment(appId: string) {
  return request.post<BaseResponse<string>>('/app/deploy/enable', { appId })
}

export function stopGeneration(appId: string) {
  return request.post<BaseResponse<boolean>>('/app/chat/stop', { id: appId })
}

export function applyFeatured(appId: string, reason?: string) {
  return request.post<BaseResponse<boolean>>('/app/featured/apply', { appId, reason })
}

export function listAdminApps(data: AppQueryRequest) {
  return request.post<BaseResponse<PageResult<AppVO>>>('/app/admin/list/page/vo', data)
}

export function adminGetApp(id: string) {
  return request.get<BaseResponse<AppVO>>('/app/admin/get/vo', { params: { id } })
}

export function adminUpdateApp(data: {
  id: string
  appName?: string
  cover?: string
  priority?: number
  featuredStatus?: string
  featuredReason?: string
  category?: string
  tags?: string
  visibility?: 'private' | 'public'
}) {
  return request.post<BaseResponse<boolean>>('/app/admin/update', data)
}

export function adminDeleteApp(id: string) {
  return request.post<BaseResponse<boolean>>('/app/admin/delete', { id })
}

export function createCodeStreamUrl(appId: string, message: string) {
  const search = new URLSearchParams({ appId, message })
  return `${API_BASE_URL}/app/chat/gen/code?${search.toString()}`
}

export function resolveApiPath(path: string) {
  if (path.startsWith('http://') || path.startsWith('https://')) {
    return path
  }
  return `${API_BASE_URL.replace(/\/$/, '')}/${path.replace(/^\//, '')}`
}
