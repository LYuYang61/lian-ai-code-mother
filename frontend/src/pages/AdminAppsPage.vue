<template>
  <div class="admin-page">
    <a-card title="应用运营管理" :bordered="false">
      <template #extra><a-button @click="load">刷新</a-button></template>
      <a-space wrap class="filter-bar">
        <a-input v-model:value="filters.searchText" placeholder="搜索名称、需求或标签" allow-clear />
        <a-input v-model:value="filters.category" placeholder="分类" allow-clear />
        <a-input v-model:value="filters.tag" placeholder="标签" allow-clear />
        <a-select v-model:value="filters.featuredStatus" placeholder="精选状态" allow-clear style="width: 120px">
          <a-select-option value="pending">待审核</a-select-option>
          <a-select-option value="approved">已精选</a-select-option>
          <a-select-option value="rejected">已拒绝</a-select-option>
        </a-select>
        <a-button type="primary" @click="search">查询</a-button>
      </a-space>
      <a-table :data-source="apps" :columns="columns" :loading="loading" :pagination="pagination" row-key="id"
        @change="handleTableChange">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'appName'">
            <a-input v-model:value="record.appName" size="small" />
            <RouterLink :to="`/app/${record.id}`">打开工作区</RouterLink>
          </template>
          <template v-else-if="column.key === 'cover'">
            <a-input v-model:value="record.cover" size="small" placeholder="可选图片地址" />
          </template>
          <template v-else-if="column.key === 'category'">
            <a-input v-model:value="record.category" size="small" placeholder="分类" />
          </template>
          <template v-else-if="column.key === 'tags'">
            <a-input v-model:value="record.tags" size="small" placeholder="逗号分隔" />
          </template>
          <template v-else-if="column.key === 'status'">
            <a-space wrap>
              <a-select v-model:value="record.visibility" size="small" style="width: 92px">
                <a-select-option value="private">私有</a-select-option>
                <a-select-option value="public">公开</a-select-option>
              </a-select>
              <a-select v-model:value="record.featuredStatus" size="small" style="width: 112px">
                <a-select-option value="none">未申请</a-select-option>
                <a-select-option value="pending">待审核</a-select-option>
                <a-select-option value="approved">已精选</a-select-option>
                <a-select-option value="rejected">已拒绝</a-select-option>
              </a-select>
            </a-space>
          </template>
          <template v-else-if="column.key === 'priority'">
            <a-input-number v-model:value="record.priority" :min="0" :max="9999" size="small" />
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space>
              <a-button size="small" type="link" @click="save(record)">保存</a-button>
              <a-button size="small" danger type="link" @click="remove(record.id)">删除</a-button>
            </a-space>
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
import { adminDeleteApp, adminUpdateApp, listAdminApps } from '@/api/app'
import type { AppVO } from '@/api/types'

const apps = ref<AppVO[]>([])
const loading = ref(false)
const pagination = reactive<TablePaginationConfig>({ current: 1, pageSize: 20, total: 0 })
const filters = reactive({ searchText: '', category: '', tag: '', featuredStatus: undefined as string | undefined })
const columns = [
  { title: '应用', key: 'appName', dataIndex: 'appName' },
  { title: '封面', key: 'cover', dataIndex: 'cover' },
  { title: '分类', key: 'category', dataIndex: 'category' },
  { title: '标签', key: 'tags', dataIndex: 'tags' },
  { title: '所有者', key: 'owner', dataIndex: ['owner', 'userName'] },
  { title: '状态', key: 'status' },
  { title: '优先级', key: 'priority' },
  { title: '操作', key: 'action' },
]

const load = async () => {
  loading.value = true
  try {
    const response = await listAdminApps({
      pageNum: pagination.current || 1,
      pageSize: pagination.pageSize || 20,
      searchText: filters.searchText || undefined,
      category: filters.category || undefined,
      tag: filters.tag || undefined,
      featuredStatus: filters.featuredStatus,
    })
    if (response.data.code !== 0 || !response.data.data) throw new Error(response.data.message)
    apps.value = response.data.data.records
    pagination.total = Number(response.data.data.total)
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载管理数据失败')
  } finally {
    loading.value = false
  }
}

const save = async (app: AppVO) => {
  try {
    const response = await adminUpdateApp({
      id: app.id,
      appName: app.appName,
      cover: app.cover || undefined,
      priority: app.priority,
      category: app.category || undefined,
      tags: app.tags || undefined,
      featuredStatus: app.featuredStatus,
      featuredReason: app.featuredReason || undefined,
      visibility: app.visibility,
    })
    if (response.data.code !== 0) throw new Error(response.data.message)
    message.success('已保存')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '保存失败')
  }
}

const search = () => {
  pagination.current = 1
  void load()
}

const remove = (id: string) => {
  Modal.confirm({
    title: '确认删除这个应用？',
    content: '应用版本、对话记录和已部署文件都会被删除，且无法恢复。',
    okType: 'danger',
    onOk: async () => {
      try {
        const response = await adminDeleteApp(id)
        if (response.data.code !== 0 || !response.data.data) throw new Error(response.data.message)
        apps.value = apps.value.filter((item) => item.id !== id)
        message.success('已删除应用及其文件')
      } catch (error) {
        message.error(error instanceof Error ? error.message : '删除失败')
      }
    },
  })
}

const handleTableChange: TableProps<AppVO>['onChange'] = (page) => {
  pagination.current = page.current
  pagination.pageSize = page.pageSize
  void load()
}

onMounted(() => void load())
</script>

<style scoped>
.admin-page {
  width: min(100%, 1200px);
  margin: 0 auto;
}

.filter-bar {
  margin-bottom: 16px;
}
</style>
