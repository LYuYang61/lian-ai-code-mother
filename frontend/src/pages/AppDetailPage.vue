<template>
  <div class="workspace" v-if="app">
    <a-page-header :title="app.appName" sub-title="AI 应用工作区" @back="router.push('/')">
      <template #extra>
        <a-space wrap>
          <a-button @click="detailOpen = true">应用详情</a-button>
          <a-button v-if="canEdit && !streaming" type="primary" @click="sendMessage">开始生成</a-button>
          <a-button v-else-if="canEdit" danger @click="stop">停止生成</a-button>
          <a-button v-if="canManage && app.deploymentStatus === 'undeployed'" @click="deploy">部署当前版本</a-button>
          <a-button v-if="canManage && app.deploymentStatus === 'deployed'" @click="deploy">重新部署当前版本</a-button>
          <a-button v-if="canManage && app.deploymentStatus === 'deployed'" @click="disable">暂停部署</a-button>
          <a-button v-if="canManage && app.deploymentStatus === 'paused'" @click="enable">恢复部署</a-button>
          <a-button v-if="canManage" @click="download">下载代码</a-button>
        </a-space>
      </template>
    </a-page-header>

    <a-alert v-if="!canEdit" type="info" show-icon class="readonly-alert"
      message="当前为只读生成模式，只有应用创建者或编辑协作者可以继续与 AI 对话；管理员仍可管理资料和部署。" />
    <a-alert v-else-if="app.generationStatus === 'failed' || app.generationStatus === 'cancelled'"
      type="warning" show-icon class="readonly-alert"
      :message="app.generationMessage || '本次生成未完成，仍可继续使用上一个可用版本。'" />

    <a-row :gutter="20">
      <a-col :xs="24" :lg="9">
        <a-card title="继续描述你的想法" :bordered="false">
          <a-textarea v-model:value="prompt" :rows="7" maxlength="10000" show-count :disabled="!canEdit"
            placeholder="例如：把按钮改成绿色，并增加暗色模式切换" @keydown.ctrl.enter="sendMessage" />
          <a-space class="prompt-actions" wrap>
            <a-tag color="blue">{{ app.codeGenType === 'multi_file' ? '多文件模式' : 'HTML 模式' }}</a-tag>
            <a-tag :color="app.visibility === 'public' ? 'green' : 'default'">
              {{ app.visibility === 'public' ? '公开' : '私有' }}
            </a-tag>
            <a-tag :color="statusColor(app.generationStatus)">{{ statusText(app.generationStatus) }}</a-tag>
          </a-space>
        </a-card>

        <a-card title="模型流输出" :bordered="false" class="panel-card">
          <a-alert v-if="streaming" type="info" show-icon message="模型正在流式返回，文件会在完整结束后保存为新版本" />
          <pre class="stream-output">{{ streamOutput || '发送一条需求后，模型输出会显示在这里。' }}</pre>
        </a-card>

        <a-card title="对话历史" :bordered="false" class="panel-card">
          <template #extra>
            <a-space size="small">
              <a-button size="small" :disabled="!history.length" @click="exportHistory">导出 Markdown</a-button>
              <a-button size="small" :loading="summaryLoading"
                :disabled="!history.length || !canSummarize || streaming || summaryLoading"
                @click="summarizeHistory">生成摘要</a-button>
            </a-space>
          </template>
          <a-space v-if="historyStats" size="small" wrap class="history-stats">
            <a-tag>消息 {{ historyStats.messageCount }}</a-tag>
            <a-tag>生成轮次 {{ historyStats.roundCount }}</a-tag>
            <a-tag v-if="historyStats.summaryUpdatedTime" color="purple">已有摘要</a-tag>
          </a-space>
          <a-alert v-if="historyError" type="error" show-icon :message="historyError" />
          <a-button v-if="historyError" type="link" :loading="historyLoading" @click="retryHistory">
            重新加载对话
          </a-button>
          <div v-if="historyHasMore || historyLoading" class="history-load-more">
            <a-button type="link" :loading="historyLoading" @click="loadOlderHistory">加载更早记录</a-button>
          </div>
          <div v-if="history.length" ref="historyScrollRef" class="history-scroll">
            <a-list size="small" :data-source="history">
              <template #renderItem="{ item }">
                <a-list-item>
                  <div class="history-item">
                    <div class="history-item-header">
                      <a-tag :color="historyMessageColor(item.messageType)">
                        {{ historySenderText(item) }}
                      </a-tag>
                      <a-button v-if="(item.message || '').length > 120" type="link" size="small"
                        class="history-toggle" @click="toggleHistory(item.id)">
                        {{ expandedHistory.has(item.id) ? '收起' : '展开全文' }}
                      </a-button>
                    </div>
                    <div class="history-message" :class="{ expanded: expandedHistory.has(item.id) }">
                      {{ item.message }}
                    </div>
                  </div>
                </a-list-item>
              </template>
            </a-list>
          </div>
          <a-empty v-else-if="!historyError" description="还没有对话记录" />
        </a-card>

        <a-card v-if="canViewMembers" title="协作者" :bordered="false" class="panel-card">
          <a-space v-if="canManage" wrap>
            <a-input v-model:value="collaboratorAccount" style="width: 160px" placeholder="用户账号，如 qing"
              @keydown.enter="saveCollaborator" />
            <a-select v-model:value="collaboratorRole" style="width: 110px">
              <a-select-option value="editor">可编辑</a-select-option>
              <a-select-option value="viewer">只读</a-select-option>
            </a-select>
            <a-button type="primary" :loading="collaboratorSaving" @click="saveCollaborator">添加/更新</a-button>
          </a-space>
          <a-list v-if="collaborators.length" :data-source="collaborators" size="small" class="collaborator-list">
            <template #renderItem="{ item }">
              <a-list-item>
                <span>{{ item.userName || item.userAccount || item.userId }}</span>
                <a-space>
                  <a-tag :color="item.role === 'owner' ? 'gold' : item.role === 'editor' ? 'blue' : 'default'">
                    {{ item.role === 'owner' ? '创建者' : item.role === 'editor' ? '可编辑' : '只读' }}
                  </a-tag>
                  <a-button v-if="canManage && item.role !== 'owner'" type="link" danger size="small"
                    @click="removeCollaborator(item.userId)">移除</a-button>
                </a-space>
              </a-list-item>
            </template>
          </a-list>
          <a-empty v-else description="暂未添加协作者" />
        </a-card>
      </a-col>

      <a-col :xs="24" :lg="15">
        <a-card title="实时预览" :bordered="false">
          <template #extra>
            <a-space size="small">
              <a-tag v-if="app.currentVersion > 0">当前版本 v{{ app.currentVersion }}</a-tag>
              <a-tag v-if="app.deployedVersion" color="blue">线上版本 v{{ app.deployedVersion }}</a-tag>
            </a-space>
          </template>
          <div class="preview-frame-wrap">
            <iframe v-if="previewUrl" :key="previewUrl" class="preview-frame" :src="previewUrl" title="应用预览" />
            <a-empty v-else description="完成一次生成后，这里会显示预览" />
          </div>
        </a-card>

        <a-card title="版本管理" :bordered="false" class="panel-card">
          <template #extra v-if="versions.length > 1">
            <a-space size="small">
              <a-select v-model:value="diffFrom" style="width: 96px" aria-label="比较起始版本">
                <a-select-option v-for="item in versions" :key="`from-${item.versionNo}`" :value="item.versionNo">
                  v{{ item.versionNo }}
                </a-select-option>
              </a-select>
              <span>→</span>
              <a-select v-model:value="diffTo" style="width: 96px" aria-label="比较目标版本">
                <a-select-option v-for="item in versions" :key="`to-${item.versionNo}`" :value="item.versionNo">
                  v{{ item.versionNo }}
                </a-select-option>
              </a-select>
              <a-button v-if="canManage" size="small" @click="showDiff">比较</a-button>
            </a-space>
          </template>
          <a-list :data-source="versions" item-layout="horizontal">
            <template #renderItem="{ item }">
              <a-list-item>
                <a-list-item-meta :title="`版本 v${item.versionNo}`" :description="item.description || item.prompt">
                  <template #avatar><a-avatar>v{{ item.versionNo }}</a-avatar></template>
                </a-list-item-meta>
                <a-space>
                  <a-tag v-if="item.versionNo === app?.currentVersion" color="green">当前</a-tag>
                  <a-button v-if="canManage && item.status === 'ready' && item.versionNo !== app?.currentVersion" size="small"
                    @click="rollback(item.versionNo)">回滚</a-button>
                </a-space>
              </a-list-item>
            </template>
          </a-list>
          <a-empty v-if="!versions.length" description="暂无版本" />
        </a-card>

        <a-card v-if="app.deployUrl" title="部署地址" :bordered="false" class="panel-card">
          <a-typography-link :href="app.deployUrl" target="_blank">{{ app.deployUrl }}</a-typography-link>
          <a-tag v-if="app.deploymentStatus === 'paused'" color="warning">已暂停访问</a-tag>
        </a-card>
      </a-col>
    </a-row>
  </div>
  <a-result v-else-if="loadError" status="error" title="应用加载失败" :sub-title="loadError">
    <template #extra>
      <a-space>
        <a-button @click="router.push('/')">返回首页</a-button>
        <a-button type="primary" @click="loadPage">重新加载</a-button>
      </a-space>
    </template>
  </a-result>
  <a-spin v-else class="page-loading" />

  <a-modal v-if="app" v-model:open="detailOpen" title="应用详情" :footer="null">
    <a-descriptions :column="1" bordered size="small">
      <a-descriptions-item label="创建者">{{ app.owner?.userName || app.userId }}</a-descriptions-item>
      <a-descriptions-item label="创建时间">{{ app.createTime }}</a-descriptions-item>
      <a-descriptions-item label="生成模式">{{ app.codeGenType === 'multi_file' ? 'HTML + CSS + JS' : '单 HTML' }}</a-descriptions-item>
      <a-descriptions-item label="可见范围">{{ app.visibility === 'public' ? '公开' : '私有' }}</a-descriptions-item>
      <a-descriptions-item label="精选状态">{{ app.featuredStatus }}</a-descriptions-item>
      <a-descriptions-item label="对话轮次">{{ app.conversationRounds }}</a-descriptions-item>
      <a-descriptions-item label="初始需求">{{ app.initPrompt }}</a-descriptions-item>
    </a-descriptions>
    <a-space v-if="canManage" wrap class="detail-actions">
      <a-button @click="openEdit">编辑资料</a-button>
      <a-button v-if="canApplyFeatured" @click="applyForFeatured">申请精选</a-button>
      <a-button danger @click="removeApp">删除应用</a-button>
    </a-space>
  </a-modal>

  <a-modal v-model:open="editOpen" title="编辑应用资料" :confirm-loading="editSaving" @ok="saveEdit">
    <a-form layout="vertical">
      <a-form-item label="应用名称"><a-input v-model:value="editForm.appName" maxlength="64" /></a-form-item>
      <a-form-item label="分类"><a-input v-model:value="editForm.category" maxlength="32" /></a-form-item>
      <a-form-item label="标签"><a-input v-model:value="editForm.tags" maxlength="500" placeholder="用逗号分隔" /></a-form-item>
      <a-form-item label="可见范围">
        <a-radio-group v-model:value="editForm.visibility">
          <a-radio value="private">私有</a-radio>
          <a-radio value="public">公开</a-radio>
        </a-radio-group>
      </a-form-item>
    </a-form>
  </a-modal>

  <a-modal v-model:open="diffOpen" title="版本差异" :footer="null" width="900px">
    <a-tabs v-if="diffResult">
      <a-tab-pane v-for="(content, fileName) in diffResult.files" :key="fileName" :tab="fileName">
        <pre class="diff-output">{{ content || '两个版本在此文件上没有差异。' }}</pre>
      </a-tab-pane>
    </a-tabs>
    <a-empty v-else description="暂无差异结果" />
  </a-modal>

  <a-modal v-model:open="summaryOpen" title="对话摘要" :footer="null">
    <a-alert v-if="summary" type="info" show-icon message="摘要仅用于帮助模型恢复上下文，数据库中的原始对话不会被替换。" />
    <pre v-if="summary" class="summary-output">{{ summary.summary }}</pre>
    <a-empty v-else description="暂无摘要" />
  </a-modal>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { useRoute, useRouter } from 'vue-router'
import {
  adminUpdateApp,
  applyFeatured,
  createCodeStreamUrl,
  deleteApp,
  diffVersions,
  deployApp,
  disableDeployment,
  enableDeployment,
  getApp,
  addCollaborator,
  listCollaborators,
  listVersions,
  removeCollaborator as removeCollaboratorRequest,
  resolveApiPath,
  rollbackVersion,
  stopGeneration,
  updateApp,
} from '@/api/app'
import {
  exportChatHistory,
  getChatHistoryStats,
  listAppChatHistory,
  summarizeChatHistory,
} from '@/api/chatHistory'
import type {
  AppCollaboratorRole,
  AppCollaboratorVO,
  AppGenerationStatus,
  AppVersionDiffVO,
  AppVO,
  AppVersionVO,
  ChatHistoryStatsVO,
  ChatHistoryVO,
  ChatSummaryVO,
} from '@/api/types'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const app = ref<AppVO | null>(null)
const loadError = ref('')
const versions = ref<AppVersionVO[]>([])
const history = ref<ChatHistoryVO[]>([])
const historyCursorTime = ref<string | null>(null)
const historyCursorId = ref<string | null>(null)
const historyHasMore = ref(false)
const historyLoading = ref(false)
const historyError = ref('')
const historyStats = ref<ChatHistoryStatsVO | null>(null)
const summary = ref<ChatSummaryVO | null>(null)
const summaryOpen = ref(false)
const summaryLoading = ref(false)
const collaborators = ref<AppCollaboratorVO[]>([])
const collaboratorAccount = ref('')
const collaboratorRole = ref<AppCollaboratorRole>('editor')
const collaboratorSaving = ref(false)
// 对话历史限高滚动容器；最新消息在底部，加载后自动滚到底部。
const historyScrollRef = ref<HTMLElement | null>(null)
// 记录被展开全文的消息 id；用重建 Set 的方式变更以保证响应式。
const expandedHistory = ref(new Set<string>())

const toggleHistory = (id: string) => {
  const next = new Set(expandedHistory.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  expandedHistory.value = next
}

const scrollHistoryToBottom = async () => {
  await nextTick()
  if (historyScrollRef.value) {
    historyScrollRef.value.scrollTop = historyScrollRef.value.scrollHeight
  }
}
const prompt = ref('')
const streamOutput = ref('')
const streaming = ref(false)
const detailOpen = ref(false)
const editOpen = ref(false)
const editSaving = ref(false)
const diffOpen = ref(false)
const diffResult = ref<AppVersionDiffVO | null>(null)
const diffFrom = ref(0)
const diffTo = ref(0)
const editForm = reactive({
  appName: '',
  category: '',
  tags: '',
  visibility: 'private' as 'private' | 'public',
})
let eventSource: EventSource | null = null

const appId = computed(() => String(route.params.id))
const canManage = computed(() => {
  if (!app.value || !userStore.isLogin) return false
  return userStore.isAdmin || userStore.user?.id === app.value.userId
})

// 成员名单对创建者、管理员和所有协作者可见；添加/移除控件仍仅限 canManage。
const canViewMembers = computed(() => {
  if (!app.value || !userStore.isLogin) return false
  return canManage.value || collaborators.value.some((item) => item.userId === userStore.user?.id)
})
// 管理员可以运营应用，但不能代替创建者发起 AI 生成，避免误触发他人的模型费用。
const canEdit = computed(() => {
  if (!app.value || !userStore.isLogin || !userStore.user) return false
  return app.value.userId === userStore.user.id
    || collaborators.value.some((item) => item.userId === userStore.user?.id && item.role === 'editor')
})
const canSummarize = computed(() => canManage.value || canEdit.value)
const canApplyFeatured = computed(() => canManage.value
  && userStore.user?.id === app.value?.userId
  && app.value?.visibility === 'public'
  && app.value?.currentVersion > 0
  && app.value?.featuredStatus !== 'approved'
  && app.value?.featuredStatus !== 'pending')
const previewUrl = computed(() => {
  if (!app.value || app.value.currentVersion <= 0) return ''
  return resolveApiPath(`/preview/${app.value.id}/${app.value.currentVersion}/`)
})

const getErrorMessage = (error: unknown, fallback: string) => {
  if (error instanceof Error && error.message) return error.message
  return fallback
}

const resetHistory = () => {
  history.value = []
  historyCursorTime.value = null
  historyCursorId.value = null
  historyHasMore.value = false
  historyError.value = ''
  historyStats.value = null
  summary.value = null
}

const loadHistory = async (reset = false, notifyError = false) => {
  if (!userStore.isLogin || historyLoading.value) return false
  if (reset) resetHistory()
  historyError.value = ''
  historyLoading.value = true
  const oldHeight = historyScrollRef.value?.scrollHeight || 0
  const oldTop = historyScrollRef.value?.scrollTop || 0
  try {
    const response = await listAppChatHistory(appId.value, {
      pageSize: 10,
      lastCreateTime: reset ? undefined : historyCursorTime.value || undefined,
      lastId: reset ? undefined : historyCursorId.value || undefined,
    })
    if (response.data.code !== 0 || !response.data.data) {
      if (reset) resetHistory()
      throw new Error(response.data.message || (reset ? '加载对话历史失败' : '加载更早对话失败'))
    }
    const page = response.data.data
    // 后端按最新到最旧返回；界面按时间正序展示，便于阅读对话流。
    const incoming = [...page.records].reverse()
    history.value = reset ? incoming : [...incoming, ...history.value]
    historyCursorTime.value = page.nextCreateTime || null
    historyCursorId.value = page.nextId || null
    historyHasMore.value = page.hasMore
    if (reset) {
      void scrollHistoryToBottom()
    } else {
      await nextTick()
      const scrollElement = historyScrollRef.value
      if (scrollElement) {
        scrollElement.scrollTop = oldTop + scrollElement.scrollHeight - oldHeight
      }
    }
    return true
  } catch (error) {
    historyError.value = getErrorMessage(error, reset ? '加载对话历史失败' : '加载更早对话失败')
    if (notifyError) throw error
    return false
  } finally {
    historyLoading.value = false
  }
}

const loadOlderHistory = async () => {
  await loadHistory(false, true).catch((error: unknown) => {
    message.error(getErrorMessage(error, '加载更早对话失败'))
  })
}

const retryHistory = async () => {
  await loadHistory(true, true).catch((error: unknown) => {
    message.error(getErrorMessage(error, '加载对话历史失败'))
  })
}

const loadHistoryStats = async () => {
  if (!userStore.isLogin) return
  try {
    const response = await getChatHistoryStats(appId.value)
    if (response.data.code === 0) historyStats.value = response.data.data
  } catch {
    // 统计不是工作区主功能，权限不足或网络失败不阻断应用页面。
  }
}

const loadCollaborators = async () => {
  if (!userStore.isLogin) return
  try {
    const response = await listCollaborators(appId.value)
    if (response.data.code === 0) collaborators.value = response.data.data || []
  } catch {
    collaborators.value = []
  }
}

const loadAll = async () => {
  const appResponse = await getApp(appId.value)
  if (appResponse.data.code !== 0 || !appResponse.data.data) throw new Error(appResponse.data.message)
  const loadedApp = appResponse.data.data
  const versionResponse = await listVersions(appId.value)
  if (versionResponse.data.code !== 0 || !versionResponse.data.data) {
    throw new Error(versionResponse.data.message)
  }
  // 版本接口也成功后再提交页面主状态，避免应用详情请求成功、版本请求失败时永久展示半加载页面。
  app.value = loadedApp
  versions.value = versionResponse.data.data?.filter((item) => item.status === 'ready') || []
  collaborators.value = []
  resetHistory()
  // 成员、历史和统计彼此独立；任何一个辅助请求失败都不应让工作区永久转圈。
  await loadCollaborators()
  const historyLoaded = await loadHistory(true, true).catch((error: unknown) => {
    // 历史是工作区的重要数据，首屏失败必须给出反馈，但不能覆盖已经成功加载的应用详情。
    message.error(getErrorMessage(error, '加载对话历史失败'))
    return false
  })
  await loadHistoryStats()
  if (versions.value.length > 1) {
    const [newest, previous] = versions.value
    if (newest && previous) {
      diffFrom.value = previous.versionNo
      diffTo.value = newest.versionNo
    }
  }
  return historyLoaded
}

const refreshApp = async (fallback = '刷新应用状态失败') => {
  try {
    await loadAll()
    return true
  } catch (error) {
    message.error(getErrorMessage(error, fallback))
    return false
  }
}

const loadPage = async () => {
  loadError.value = ''
  // 先清空旧应用状态，避免路由复用或版本接口失败时继续展示旧应用内容。
  eventSource?.close()
  eventSource = null
  streaming.value = false
  app.value = null
  versions.value = []
  prompt.value = ''
  streamOutput.value = ''
  diffOpen.value = false
  diffResult.value = null
  diffFrom.value = 0
  diffTo.value = 0
  summaryOpen.value = false
  detailOpen.value = false
  editOpen.value = false
  expandedHistory.value = new Set<string>()
  resetHistory()
  try {
    const historyLoaded = await loadAll()
    // loadAll 在异步函数内部填充 ref，显式保留其运行时联合类型，避免 TS 按当前函数内赋值把它收窄为 never。
    const loadedApp = app.value as AppVO | null
    // 只有历史确实加载成功且为空时，才自动使用初始化需求；网络/权限错误不能误触发模型调用。
    if (historyLoaded && loadedApp && route.query.view !== '1' && canEdit.value
      && loadedApp.generationStatus === 'draft' && history.value.length === 0) {
      prompt.value = loadedApp.initPrompt
      sendMessage()
    }
  } catch (error) {
    loadError.value = getErrorMessage(error, '加载应用失败')
    message.error(loadError.value)
  }
}

const sendMessage = () => {
  if (!canEdit.value) {
    message.info('当前应用只读，只有创建者或编辑协作者可以生成代码')
    return
  }
  if (streaming.value || !prompt.value.trim()) {
    if (!prompt.value.trim()) message.warning('请先描述需求')
    return
  }
  streaming.value = true
  streamOutput.value = ''
  const source = new EventSource(createCodeStreamUrl(appId.value, prompt.value.trim()), { withCredentials: true })
  let streamSettled = false
  let serverErrorHandled = false
  eventSource = source
  const closeStream = () => {
    if (streamSettled) return false
    streamSettled = true
    source.close()
    if (eventSource === source) eventSource = null
    streaming.value = false
    return true
  }
  source.onmessage = (event) => {
    try {
      const payload = JSON.parse(event.data) as { d?: string }
      streamOutput.value += payload.d || ''
    } catch {
      streamOutput.value += event.data
    }
  }
  source.addEventListener('done', () => {
    if (!closeStream()) return
    prompt.value = ''
    void (async () => {
      if (await refreshApp('生成已完成，但刷新应用状态失败')) {
        message.success('生成完成，已保存为新版本')
      }
    })()
  })
  source.addEventListener('cancelled', () => {
    if (!closeStream()) return
    void (async () => {
      await refreshApp('生成已取消，但刷新应用状态失败')
      message.info('本次生成已取消，已有版本仍然可用')
    })()
  })
  source.addEventListener('error', (event) => {
    serverErrorHandled = true
    if (!closeStream()) return
    const customEvent = event as MessageEvent<string>
    try {
      const payload = JSON.parse(customEvent.data) as { message?: string }
      message.error(payload.message || '生成失败')
    } catch {
      message.error('生成连接中断')
    }
    void refreshApp()
  })
  source.onerror = () => {
    if (serverErrorHandled) return
    if (!closeStream()) return
    message.error('SSE 连接中断，请查看应用状态后重试')
    void refreshApp()
  }
}

const stop = async () => {
  if (!canEdit.value) return
  try {
    const response = await stopGeneration(appId.value)
    if (response.data.code !== 0 || !response.data.data) {
      throw new Error(response.data.message || '当前没有可停止的生成任务')
    }
    message.info('已请求停止生成')
    eventSource?.close()
    eventSource = null
    streaming.value = false
  } catch (error) {
    message.error(getErrorMessage(error, '停止生成失败'))
  } finally {
    await refreshApp()
  }
}

const deploy = async () => {
  if (!canManage.value) return
  try {
    const response = await deployApp(appId.value)
    if (response.data.code !== 0 || !response.data.data) throw new Error(response.data.message)
    if (await refreshApp('部署成功，但刷新应用状态失败')) message.success('部署完成')
  } catch (error) {
    message.error(getErrorMessage(error, '部署失败'))
  }
}

const disable = async () => {
  if (!canManage.value) return
  try {
    const response = await disableDeployment(appId.value)
    if (response.data.code !== 0 || !response.data.data) throw new Error(response.data.message)
    if (await refreshApp('暂停成功，但刷新应用状态失败')) message.success('部署访问已暂停')
  } catch (error) {
    message.error(getErrorMessage(error, '暂停部署失败'))
  }
}

const enable = async () => {
  if (!canManage.value) return
  try {
    const response = await enableDeployment(appId.value)
    if (response.data.code !== 0 || !response.data.data) throw new Error(response.data.message)
    if (await refreshApp('恢复成功，但刷新应用状态失败')) message.success('部署访问已恢复')
  } catch (error) {
    message.error(getErrorMessage(error, '恢复部署失败'))
  }
}

const rollback = (versionNo: number) => {
  if (!canManage.value) return
  Modal.confirm({
    title: `回滚到 v${versionNo}？`,
    content: '回滚只切换当前版本指针，不会删除其他版本。',
    onOk: async () => {
      try {
        const response = await rollbackVersion(appId.value, versionNo)
        if (response.data.code !== 0 || !response.data.data) throw new Error(response.data.message)
        if (await refreshApp('回滚成功，但刷新应用状态失败')) message.success(`已切换到 v${versionNo}`)
      } catch (error) {
        message.error(getErrorMessage(error, '回滚失败'))
      }
    },
  })
}

const download = () => {
  if (!canManage.value) return
  window.open(resolveApiPath(`/app/download?appId=${encodeURIComponent(appId.value)}`), '_blank')
}

const openEdit = () => {
  if (!app.value || !canManage.value) return
  editForm.appName = app.value.appName
  editForm.category = app.value.category || ''
  editForm.tags = app.value.tags || ''
  editForm.visibility = app.value.visibility
  detailOpen.value = false
  editOpen.value = true
}

const saveEdit = async () => {
  if (!app.value) return
  if (!editForm.appName.trim()) {
    message.warning('应用名称不能为空')
    return
  }
  editSaving.value = true
  try {
    const payload = {
      id: app.value.id,
      appName: editForm.appName.trim(),
      category: editForm.category.trim(),
      tags: editForm.tags.trim(),
      visibility: editForm.visibility,
    }
    // 管理员编辑他人应用时走管理员接口；普通更新接口只允许创建者修改。
    const response = userStore.isAdmin && userStore.user?.id !== app.value.userId
      ? await adminUpdateApp(payload)
      : await updateApp(payload)
    if (response.data.code !== 0) throw new Error(response.data.message)
    editOpen.value = false
    await loadAll()
    message.success('应用资料已更新')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '更新失败')
  } finally {
    editSaving.value = false
  }
}

const removeApp = () => {
  if (!app.value || !canManage.value) return
  Modal.confirm({
    title: '确认删除这个应用？',
    content: '应用版本、对话记录和已部署文件都会被删除，且无法恢复。',
    okType: 'danger',
    onOk: async () => {
      try {
        const response = await deleteApp(app.value!.id)
        if (response.data.code !== 0 || !response.data.data) throw new Error(response.data.message)
        message.success('应用已删除')
        await router.push('/')
      } catch (error) {
        message.error(getErrorMessage(error, '删除应用失败'))
      }
    },
  })
}

const applyForFeatured = async () => {
  if (!app.value || !canApplyFeatured.value) return
  try {
    const response = await applyFeatured(app.value.id, '希望展示给其他学习者')
    if (response.data.code !== 0 || !response.data.data) throw new Error(response.data.message)
    if (await refreshApp('申请成功，但刷新应用状态失败')) {
      message.success('精选申请已提交，等待管理员审核')
    }
  } catch (error) {
    message.error(getErrorMessage(error, '申请精选失败'))
  }
}

const showDiff = async () => {
  if (!canManage.value) {
    message.info('只有应用创建者或管理员可以比较历史版本')
    return
  }
  if (!diffFrom.value || !diffTo.value || diffFrom.value === diffTo.value) {
    message.warning('请选择两个不同的版本')
    return
  }
  try {
    const response = await diffVersions(appId.value, diffFrom.value, diffTo.value)
    if (response.data.code !== 0 || !response.data.data) throw new Error(response.data.message)
    diffResult.value = response.data.data
    diffOpen.value = true
  } catch (error) {
    message.error(getErrorMessage(error, '版本比较失败'))
  }
}

const exportHistory = async () => {
  try {
    const response = await exportChatHistory(appId.value)
    const contentType = String(response.headers['content-type'] || '').toLowerCase()
    if (contentType.includes('application/json')) {
      const raw = response.data instanceof Blob ? await response.data.text() : ''
      let errorMessage = '导出对话历史失败'
      try {
        const payload = JSON.parse(raw) as { message?: string }
        errorMessage = payload.message || errorMessage
      } catch {
        // 错误响应不是 JSON 时使用统一兜底提示，不能把 JSON 错误体当 Markdown 下载。
      }
      throw new Error(errorMessage)
    }
    const url = URL.createObjectURL(response.data)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `chat-history-${appId.value}.md`
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(url)
    message.success('对话历史已导出')
  } catch (error) {
    message.error(getErrorMessage(error, '导出对话历史失败'))
  }
}

const summarizeHistory = async () => {
  if (!canSummarize.value) {
    message.info('只有创建者、编辑协作者或管理员可以生成摘要')
    return
  }
  if (summaryLoading.value || streaming.value) return
  summaryLoading.value = true
  try {
    const response = await summarizeChatHistory(appId.value)
    if (response.data.code !== 0 || !response.data.data) throw new Error(response.data.message)
    summary.value = response.data.data
    summaryOpen.value = true
    await loadHistoryStats()
    message.success('对话摘要已生成')
  } catch (error) {
    const errorMessage = getErrorMessage(error, '生成对话摘要失败')
    if (errorMessage.toLowerCase().includes('timeout')) {
      message.warning('摘要请求等待超时，后端可能仍在处理，请稍后刷新查看摘要状态，避免重复提交')
    } else {
      message.error(errorMessage)
    }
  } finally {
    summaryLoading.value = false
  }
}

const saveCollaborator = async () => {
  if (!app.value || !canManage.value) return
  const account = collaboratorAccount.value.trim()
  if (!account) {
    message.warning('请输入协作者的用户账号')
    return
  }
  collaboratorSaving.value = true
  try {
    const response = await addCollaborator({ appId: app.value.id, userAccount: account, role: collaboratorRole.value })
    if (response.data.code !== 0 || !response.data.data) throw new Error(response.data.message)
    collaboratorAccount.value = ''
    await loadCollaborators()
    message.success('协作者已保存')
  } catch (error) {
    message.error(getErrorMessage(error, '保存协作者失败'))
  } finally {
    collaboratorSaving.value = false
  }
}

const removeCollaborator = (userId: string) => {
  if (!app.value || !canManage.value) return
  Modal.confirm({
    title: '确认移除该协作者？',
    content: '移除后，该用户将不能继续查看或编辑这个应用。',
    onOk: async () => {
      try {
        const response = await removeCollaboratorRequest({ appId: app.value!.id, userId })
        if (response.data.code !== 0 || !response.data.data) throw new Error(response.data.message)
        await loadCollaborators()
        message.success('协作者已移除')
      } catch (error) {
        message.error(getErrorMessage(error, '移除协作者失败'))
      }
    },
  })
}

const historyMessageText = (messageType: ChatHistoryVO['messageType']) => ({
  user: '我',
  ai: 'AI',
  error: '状态',
}[messageType])

const historySenderText = (item: ChatHistoryVO) => {
  if (item.messageType === 'user') {
    return item.userId === userStore.user?.id ? '我' : (item.userName || item.userAccount || '成员')
  }
  return historyMessageText(item.messageType)
}

const historyMessageColor = (messageType: ChatHistoryVO['messageType']) => ({
  user: 'blue',
  ai: 'green',
  error: 'red',
}[messageType])

const statusText = (status: AppGenerationStatus) => ({
  draft: '待生成', generating: '生成中', ready: '已完成', failed: '失败', cancelled: '已取消',
}[status])
const statusColor = (status: AppGenerationStatus) => ({
  draft: 'default', generating: 'processing', ready: 'success', failed: 'error', cancelled: 'warning',
}[status])

onMounted(() => void loadPage())
watch(appId, () => void loadPage())
onBeforeUnmount(() => eventSource?.close())
</script>

<style scoped>
.workspace {
  width: min(100%, 1400px);
  margin: 0 auto;
}

.panel-card {
  margin-top: 20px;
}

.readonly-alert {
  margin-bottom: 20px;
}

.prompt-actions {
  margin-top: 14px;
}

.stream-output {
  min-height: 180px;
  max-height: 360px;
  margin: 16px 0 0;
  padding: 12px;
  overflow: auto;
  color: #dbeafe;
  white-space: pre-wrap;
  word-break: break-word;
  background: #111827;
  border-radius: 8px;
}

.preview-frame-wrap {
  display: grid;
  min-height: 560px;
  place-items: center;
  overflow: hidden;
  background: #f3f4f6;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
}

.preview-frame {
  width: 100%;
  min-height: 560px;
  border: 0;
  background: white;
}

.history-scroll {
  max-height: 320px;
  padding-right: 4px;
  overflow-y: auto;
}

.history-stats {
  margin-bottom: 8px;
}

.history-load-more {
  text-align: center;
}

.history-item {
  width: 100%;
}

.history-item-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.history-toggle {
  height: auto;
  padding: 0;
}

/* antdv Typography 的 ellipsis 只对纯文本内容生效，混合 a-tag 时会失效，
   这里用 line-clamp 自行实现三行折叠，长消息通过按钮展开。 */
.history-message {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  white-space: pre-wrap;
  word-break: break-word;
}

.history-message.expanded {
  display: block;
  -webkit-line-clamp: unset;
}

.detail-actions {
  margin-top: 20px;
}

.collaborator-list {
  margin-top: 12px;
}

.summary-output {
  max-height: 480px;
  margin-top: 16px;
  padding: 12px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  background: #f8fafc;
  border-radius: 8px;
}

.diff-output {
  max-height: 520px;
  margin: 0;
  padding: 16px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  background: #111827;
  color: #e5e7eb;
  border-radius: 8px;
}

.page-loading {
  display: block;
  margin: 80px auto;
}
</style>
