<template>
  <div class="admin-page">
    <a-card title="对话历史管理" :bordered="false">
      <template #extra><a-button @click="load">刷新</a-button></template>
      <a-space wrap class="filter-bar">
        <a-input v-model:value="filters.message" placeholder="搜索消息内容" allow-clear />
        <a-input v-model:value="filters.appId" placeholder="应用 ID" allow-clear />
        <a-input v-model:value="filters.userId" placeholder="用户 ID" allow-clear />
        <a-select v-model:value="filters.messageType" placeholder="消息类型" allow-clear style="width: 110px">
          <a-select-option value="user">用户</a-select-option>
          <a-select-option value="ai">AI</a-select-option>
          <a-select-option value="error">错误</a-select-option>
        </a-select>
        <a-button type="primary" @click="search">查询</a-button>
      </a-space>
      <a-table :data-source="records" :columns="columns" :loading="loading" :pagination="pagination"
        row-key="id" @change="handleTableChange">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'messageType'">
            <a-tag :color="typeColor(record.messageType)">{{ typeText(record.messageType) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'message'">
            <a-typography-paragraph :ellipsis="{ rows: 3, expandable: true, symbol: '展开' }">
              {{ record.message }}
            </a-typography-paragraph>
          </template>
          <template v-else-if="column.key === 'appId'">
            <RouterLink :to="`/app/${record.appId}`">{{ record.appId }}</RouterLink>
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
import type { ChatHistoryVO } from '@/api/types'

const records = ref<ChatHistoryVO[]>([])
const loading = ref(false)
const pagination = reactive<TablePaginationConfig>({ current: 1, pageSize: 20, total: 0 })
const filters = reactive({
  message: '',
  appId: '',
  userId: '',
  messageType: undefined as ChatHistoryQueryRequest['messageType'],
})
const columns = [
  { title: '类型', key: 'messageType', dataIndex: 'messageType', width: 90 },
  { title: '消息', key: 'message', dataIndex: 'message' },
  { title: '应用', key: 'appId', dataIndex: 'appId', width: 170 },
  { title: '用户 ID', key: 'userId', dataIndex: 'userId', width: 170 },
  { title: '版本', key: 'versionNo', dataIndex: 'versionNo', width: 70 },
  { title: '时间', key: 'createTime', dataIndex: 'createTime', width: 190 },
  { title: '操作', key: 'action', width: 80 },
]

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
      sortField: 'createTime',
      sortOrder: 'desc',
    }
    const response = await listAdminChatHistory(query)
    if (response.data.code !== 0 || !response.data.data) throw new Error(response.data.message)
    records.value = response.data.data.records
    pagination.total = response.data.data.total
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

onMounted(() => void load())
</script>

<style scoped>
.admin-page {
  width: min(100%, 1400px);
  margin: 0 auto;
}

.filter-bar {
  margin-bottom: 16px;
}
</style>
