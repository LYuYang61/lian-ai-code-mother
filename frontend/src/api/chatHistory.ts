import type {
  BaseResponse,
  ChatHistoryStatsVO,
  ChatHistoryVO,
  ChatSummaryVO,
  CursorPageResult,
  PageResult,
} from '@/api/types'
import request from '@/request'

export interface AppChatHistoryQuery {
  pageSize?: number
  lastCreateTime?: string
  lastId?: string
}

export interface ChatHistoryQueryRequest {
  pageNum: number
  pageSize: number
  id?: string
  message?: string
  messageType?: 'user' | 'ai' | 'error'
  appId?: string
  userId?: string
  sortField?: string
  sortOrder?: string
}

export function listAppChatHistory(appId: string, params: AppChatHistoryQuery = {}) {
  return request.get<BaseResponse<CursorPageResult<ChatHistoryVO>>>(`/chatHistory/app/${appId}`, { params })
}

export function getChatHistoryStats(appId: string) {
  return request.get<BaseResponse<ChatHistoryStatsVO>>(`/chatHistory/app/${appId}/stats`)
}

export function exportChatHistory(appId: string) {
  return request.get<Blob>(`/chatHistory/app/${appId}/export`, { responseType: 'blob' })
}

export function summarizeChatHistory(appId: string) {
  // 摘要会同步调用大模型，不能沿用普通接口的 15 秒超时。
  return request.post<BaseResponse<ChatSummaryVO>>(`/chatHistory/app/${appId}/summarize`, {}, {
    timeout: 120_000,
  })
}

export function listAdminChatHistory(data: ChatHistoryQueryRequest) {
  return request.post<BaseResponse<PageResult<ChatHistoryVO>>>('/chatHistory/admin/list/page/vo', data)
}

export function deleteAdminChatHistory(id: string) {
  return request.delete<BaseResponse<boolean>>('/chatHistory/admin/delete', { params: { id } })
}
