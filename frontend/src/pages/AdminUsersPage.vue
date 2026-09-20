<template>
  <div class="admin-users-page">
    <a-card title="用户管理" :bordered="false">
      <template #extra><a-button @click="load">刷新</a-button></template>
      <a-space wrap class="filter-bar">
        <a-input-search
          v-model:value="filters.userAccount"
          placeholder="按账号模糊搜索"
          allow-clear
          style="width: 220px"
          @search="search"
        />
        <a-select
          v-model:value="filters.userRole"
          placeholder="角色"
          allow-clear
          style="width: 120px"
          @change="search"
        >
          <a-select-option value="user">普通用户</a-select-option>
          <a-select-option value="admin">管理员</a-select-option>
        </a-select>
        <a-button type="primary" @click="search">查询</a-button>
      </a-space>
      <a-table
        :data-source="users"
        :columns="columns"
        :loading="loading"
        :pagination="pagination"
        row-key="id"
        @change="handleTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'userRole'">
            <a-tag :color="record.userRole === 'admin' ? 'purple' : 'default'">
              {{ record.userRole === 'admin' ? '管理员' : '普通用户' }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'userProfile'">
            <span class="profile-cell">{{ record.userProfile || '—' }}</span>
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space>
              <a-button size="small" type="link" @click="openEdit(record)">编辑</a-button>
              <a-button
                size="small"
                danger
                type="link"
                :disabled="record.id === userStore.user?.id"
                :title="record.id === userStore.user?.id ? '不能删除当前登录的管理员账号' : undefined"
                @click="remove(record)"
              >
                删除
              </a-button>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <a-modal v-model:open="editOpen" title="编辑用户" :confirm-loading="saving" @ok="save">
      <a-form layout="vertical">
        <a-form-item label="账号">
          <a-input :value="editForm.userAccount" disabled />
        </a-form-item>
        <a-form-item label="昵称">
          <a-input v-model:value="editForm.userName" :maxlength="32" />
        </a-form-item>
        <a-form-item label="头像地址">
          <a-input v-model:value="editForm.userAvatar" :maxlength="512" placeholder="可选，图片 URL" />
        </a-form-item>
        <a-form-item label="简介">
          <a-textarea v-model:value="editForm.userProfile" :rows="3" :maxlength="512" />
        </a-form-item>
        <a-form-item label="角色">
          <a-radio-group v-model:value="editForm.userRole">
            <a-radio value="user">普通用户</a-radio>
            <a-radio value="admin">管理员</a-radio>
          </a-radio-group>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import type { TablePaginationConfig, TableProps } from 'ant-design-vue'
import { adminDeleteUser, adminUpdateUser, listAdminUsers } from '@/api/user'
import type { UserVO } from '@/api/types'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const users = ref<UserVO[]>([])
const loading = ref(false)
const pagination = reactive<TablePaginationConfig>({ current: 1, pageSize: 10, total: 0 })
const filters = reactive({ userAccount: '', userRole: undefined as string | undefined })
const editOpen = ref(false)
const saving = ref(false)
const editForm = reactive({
  id: '',
  userAccount: '',
  userName: '',
  userAvatar: '',
  userProfile: '',
  userRole: 'user' as 'user' | 'admin',
})

const columns = [
  { title: '账号', key: 'userAccount', dataIndex: 'userAccount' },
  { title: '昵称', key: 'userName', dataIndex: 'userName' },
  { title: '角色', key: 'userRole', dataIndex: 'userRole' },
  { title: '简介', key: 'userProfile', dataIndex: 'userProfile' },
  { title: '创建时间', key: 'createTime', dataIndex: 'createTime' },
  { title: '操作', key: 'action' },
]

const load = async () => {
  loading.value = true
  try {
    const response = await listAdminUsers({
      pageNum: pagination.current || 1,
      pageSize: pagination.pageSize || 10,
      userAccount: filters.userAccount || undefined,
      userRole: filters.userRole,
    })
    if (response.data.code !== 0 || !response.data.data) throw new Error(response.data.message)
    users.value = response.data.data.records
    pagination.total = response.data.data.total
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载用户列表失败')
  } finally {
    loading.value = false
  }
}

const search = () => {
  pagination.current = 1
  void load()
}

const openEdit = (record: UserVO) => {
  editForm.id = record.id
  editForm.userAccount = record.userAccount
  editForm.userName = record.userName || ''
  editForm.userAvatar = record.userAvatar || ''
  editForm.userProfile = record.userProfile || ''
  editForm.userRole = record.userRole === 'admin' ? 'admin' : 'user'
  editOpen.value = true
}

const save = async () => {
  saving.value = true
  try {
    const response = await adminUpdateUser({
      id: editForm.id,
      userName: editForm.userName,
      userAvatar: editForm.userAvatar,
      userProfile: editForm.userProfile,
      userRole: editForm.userRole,
    })
    if (response.data.code !== 0) throw new Error(response.data.message)
    message.success('用户信息已保存')
    editOpen.value = false
    void load()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '保存用户信息失败')
  } finally {
    saving.value = false
  }
}

const remove = (record: UserVO) => {
  Modal.confirm({
    title: `确认删除用户 ${record.userAccount}？`,
    content: '删除后该用户将无法登录，其创建的应用和文件不会自动清理。',
    okType: 'danger',
    onOk: async () => {
      try {
        const response = await adminDeleteUser(record.id)
        if (response.data.code !== 0 || !response.data.data) throw new Error(response.data.message)
        users.value = users.value.filter((item) => item.id !== record.id)
        message.success('用户已删除')
      } catch (error) {
        message.error(error instanceof Error ? error.message : '删除用户失败')
      }
    },
  })
}

const handleTableChange: TableProps<UserVO>['onChange'] = (page) => {
  pagination.current = page.current
  pagination.pageSize = page.pageSize
  void load()
}

onMounted(() => void load())
</script>

<style scoped>
.filter-bar {
  margin-bottom: 16px;
}

.profile-cell {
  display: inline-block;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
