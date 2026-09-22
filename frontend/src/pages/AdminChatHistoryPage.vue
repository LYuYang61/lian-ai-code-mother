<template>
  <div class="admin-page">
    <a-card title="对话历史管理" :bordered="false">
      <template #extra><a-button @click="load">刷新</a-button></template>
      <a-space wrap class="filter-bar">
        <a-select v-model:value="filters.appId" placeholder="全部应用" allow-clear show-search
          option-filter-prop="label" style="min-width: 230px" :options="appOptions"
          @change="search" />
        <a-input v-model:value="filters.message" placeholder="搜索消息内容" allow-clear style="width: 170px"
          @keydown.enter="search" />
        <a-input v-model:value="filters.userId" placeholder="用户 ID" allow-clear style="width: 160px" />
        <a-select v-model:value="filters.messageType" placeholder="消息类型" allow-clear style="width: 100px"
          @change="search">
          <a-select-option value="user">用户</a-select-option>
          <a-select-option value="ai">AI</a-select-option>
          <a-select-option value="error">错误</a-select-option>
        </a-select>
        <a-radio-group v-model:value="sortMode" button-style="solid" @change="search">
          <a-radio-button value="app">按应用</a-radio-button>
          <a-radio-button value="time">按时间</a-radio-button>
        </a-radio-group>
        <a-button type="primary" @click="search">查询</a-button>
      </a-space>
      <a-table :data-source="records" :columns="columns" :loading="loading" :pagination="pagination"
        row-key="id" @change="handleTableChange">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'app'">
            <RouterLink :to="`/app/${record.appId}`" :title="record.appId">
              {{ record.appName || record.appId }}
            </RouterLink>
          </template>
          <template v-else-if="column.key === 'sender'">
            <span v-if="record.messageType === 'ai'" class="sender-ai">AI</span>
            <span v-else-if="record.messageType === 'error'" class="sender-ai">系统</span>
            <span v-else>{{ record.userAccount || record.userName || record.userId }}</span>
          </template>
          <template v-else-if="column.key === 'messageType'">
            <a-tag :color="typeColor(record.messageType)">{{ typeText(record.messageType) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'message'">
            <div class="message-cell" :class="{ expanded: expandedIds.has(record.id) }">{{ record.message }}</div>
            <a-button v-if="(record.message || '').length > 120" type="link" size="small" class="message-toggle"
              @click="toggleMessage(record.id)">
              {{ expandedIds.has(record.id) ? '收起' : '展开全文' }}
            </a-button>
          </template>
          <template v-else-if="column.key === 'action'">
            <a-button danger type="link" size="small" @click="remove(record.id)">删除</a-button>
          </template>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import type { TablePaginationConfig, TableProps } from 'ant-design-vue'
import { deleteAdminChatHistory, listAdminChatHistory } from '@/api/chatHistory'
import type { ChatHistoryQueryRequest } from '@/api/chatHistory'
import { listAdminApps } from '@/api/app'
import type { ChatHistoryVO } from '@/api/types'

const records = ref<ChatHistoryVO[]>([])
const loading = ref(false)
const pagination = reactive<TablePaginationConfig>({ current: 1, pageSize: 20, total: 0 })
const filters = reactive({
  message: '',
  appId: undefined as string | undefined,
  userId: '',
  messageType: undefined as ChatHistoryQueryRequest['messageType'],
})
const sortMode = ref<'app' | 'time'>('app')
const expandedIds = ref(new Set<string>())
const appOptions = ref<Array<{ label: string; value: string }>>([])

const columns = [
  { title: '应用', key: 'app', dataIndex: 'appName', width: 180 },
  { title: '发送者', key: 'sender', width: 110 },
  { title: '类型', key: 'messageType', dataIndex: 'messageType', width: 80 },
  { title: '消息', key: 'message', dataIndex: 'message' },
  { title: '版本', key: 'versionNo', dataIndex: 'versionNo', width: 64 },
  { title: '时间', key: 'createTime', dataIndex: 'createTime', width: 170 },
  { title: '操作', key: 'action', width: 70 },
]

const loadApps = async () => {
  try {
    const response = await listAdminApps({ pageNum: 1, pageSize: 200 })
    if (response.data.code !== 0 || !response.data.data) throw new Error(response.data.message)
    appOptions.value = (response.data.data.records || []).map((item) => ({
      label: item.appName || item.id,
      value: item.id,
    }))
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载应用列表失败')
  }
}

const load = async () => {
  loading.value = true
  try {
    const query: ChatHistoryQueryRequest = {
      pageNum: pagination.current || 1,
      pageSize: pagination.pageSize || 20,
      message: filters.message || undefined,
      appId: filters.appId || undefined,
      userId: filters.userId || undefined,
      messageType: filters.messageType,
      // 默认按应用分组（组内由后端按时间正序二次排序），避免多应用消息交错。
      sortField: sortMode.value === 'app' ? 'appId' : 'createTime',
      sortOrder: sortMode.value === 'app' ? 'asc' : 'desc',
    }
    const response = await listAdminChatHistory(query)
    if (response.data.code !== 0 || !response.data.data) throw new Error(response.data.message)
    records.value = response.data.data.records
    pagination.total = Number(response.data.data.total)
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载对话历史失败')
  } finally {
    loading.value = false
  }
}

const search = () => {
  pagination.current = 1
  void load()
}

const toggleMessage = (id: string) => {
  const next = new Set(expandedIds.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  expandedIds.value = next
}

const remove = (id: string) => {
  Modal.confirm({
    title: '确认删除这条对话记录？',
    content: '删除后该记录不会再出现在管理列表中，也不会写入新的上下文恢复窗口。',
    okType: 'danger',
    onOk: async () => {
      try {
        const response = await deleteAdminChatHistory(id)
        if (response.data.code !== 0 || !response.data.data) throw new Error(response.data.message)
        message.success('对话记录已删除')
        await load()
      } catch (error) {
        message.error(error instanceof Error ? error.message : '删除对话记录失败')
      }
    },
  })
}

const handleTableChange: TableProps<ChatHistoryVO>['onChange'] = (page) => {
  pagination.current = page.current
  pagination.pageSize = page.pageSize
  void load()
}

const typeText = (type: ChatHistoryVO['messageType']) => {
  if (type === 'user') return '用户'
  if (type === 'ai') return 'AI'
  return '错误'
}
const typeColor = (type: ChatHistoryVO['messageType']) => {
  if (type === 'user') return 'blue'
  if (type === 'ai') return 'green'
  return 'red'
}

onMounted(() => {
  void loadApps()
  void load()
})
</script>

<style scoped>
.admin-page {
  width: min(100%, 1400px);
  margin: 0 auto;
}

.filter-bar {
  margin-bottom: 16px;
}

.sender-ai {
  color: #6b7280;
}

/* antdv Typography 的 ellipsis 在表格内不可靠（与工作区面板同因），这里用 line-clamp 自实现折叠。 */
.message-cell {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  white-space: pre-wrap;
  word-break: break-word;
}

.message-cell.expanded {
  display: block;
  -webkit-line-clamp: unset;
}

.message-toggle {
  height: auto;
  padding: 0;
}
</style>
