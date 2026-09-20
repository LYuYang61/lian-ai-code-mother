<template>
  <div class="admin-layout">
    <a-page-header class="admin-header" title="后台管理" sub-title="应用运营与用户管理" @back="router.push('/')" />
    <a-menu
      class="admin-nav"
      mode="horizontal"
      :selected-keys="[activeKey]"
      :items="menuItems"
      @click="handleMenuClick"
    />
    <div class="admin-content">
      <RouterView />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { MenuProps } from 'ant-design-vue'

const route = useRoute()
const router = useRouter()

const menuItems: MenuProps['items'] = [
  { key: '/admin/apps', label: '应用运营管理', title: '应用运营管理' },
  { key: '/admin/users', label: '用户管理', title: '用户管理' },
]

// 子页面路径前缀一致（如 /admin/apps），用当前路径直接作为选中项即可。
const activeKey = computed(() => (route.path.startsWith('/admin/users') ? '/admin/users' : '/admin/apps'))

const handleMenuClick: MenuProps['onClick'] = ({ key }) => {
  const path = String(key)
  if (path.startsWith('/admin') && path !== route.path) {
    void router.push(path)
  }
}
</script>

<style scoped>
.admin-layout {
  width: min(100%, 1200px);
  margin: 0 auto;
  padding: 0 16px 24px;
}

.admin-header {
  padding-left: 0;
}

.admin-nav {
  margin-bottom: 16px;
  border-bottom: 1px solid #e5e7eb;
}

.admin-content {
  padding-top: 16px;
}
</style>
