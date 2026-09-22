export interface BaseResponse<T> {
  code: number
  data: T | null
  message: string
}

export interface PageResult<T> {
  records: T[]
  pageNum: number
  pageSize: number
  total: number
  pages: number
}

export interface LoginUser {
  id: string
  userAccount: string
  userName: string
  userAvatar?: string | null
  userProfile?: string | null
  userRole: 'user' | 'admin'
}

export interface UserVO extends LoginUser {
  createTime?: string | null
}

export type CodeGenType = 'html' | 'multi_file'
export type AppVisibility = 'private' | 'public'
export type AppGenerationStatus = 'draft' | 'generating' | 'ready' | 'failed' | 'cancelled'
export type AppDeploymentStatus = 'undeployed' | 'deployed' | 'paused'

export interface AppVO {
  id: string
  appName: string
  cover?: string | null
  initPrompt: string
  codeGenType: CodeGenType
  deployKey?: string | null
  deployedTime?: string | null
  priority: number
  userId: string
  visibility: AppVisibility
  category?: string | null
  tags?: string | null
  generationStatus: AppGenerationStatus
  currentVersion: number
  deployedVersion?: number | null
  conversationRounds: number
  generationMessage?: string | null
  featuredStatus: string
  featuredReason?: string | null
  deploymentStatus: AppDeploymentStatus
  createTime: string
  updateTime: string
  owner?: UserVO | null
  previewUrl?: string | null
  deployUrl?: string | null
}

export interface AppVersionVO {
  id: string
  appId: string
  versionNo: number
  codeGenType: CodeGenType
  status: string
  description?: string | null
  prompt: string
  previewUrl?: string | null
  createTime: string
}

export interface ChatHistoryVO {
  id: string
  appId: string
  appName?: string | null
  userId: string
  userAccount?: string | null
  userName?: string | null
  message: string
  messageType: 'user' | 'ai' | 'error'
  parentId?: string | null
  versionNo?: number | null
  fileList?: string | null
  createTime: string
}

export interface CursorPageResult<T> {
  records: T[]
  hasMore: boolean
  nextCreateTime?: string | null
  nextId?: string | null
}

export interface ChatHistoryStatsVO {
  appId: string
  messageCount: number
  roundCount: number
  lastCreateTime?: string | null
  summaryUpdatedTime?: string | null
}

export interface ChatSummaryVO {
  appId: string
  summary: string
  coveredUntilId?: string | null
  coveredUntilTime?: string | null
  messageCount: number
  updateTime?: string | null
}

export type AppCollaboratorRole = 'viewer' | 'editor'

export interface AppCollaboratorVO {
  id: string
  appId: string
  userId: string
  userAccount?: string | null
  userName?: string | null
  userAvatar?: string | null
  role: AppCollaboratorRole | 'owner'
  createTime?: string | null
}

export interface AppVersionDiffVO {
  appId: string
  fromVersion: number
  toVersion: number
  files: Record<string, string>
}
