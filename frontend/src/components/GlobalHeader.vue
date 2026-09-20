<template>
  <a-layout-header class="header">
    <div class="header-inner">
      <RouterLink class="brand" to="/" aria-label="返回首页">
        <img class="logo" src="@/assets/logo.svg" alt="Lian AI Code Mother Logo" />
        <span class="site-title">Lian AI Code Mother</span>
      </RouterLink>

      <a-menu
        v-model:selected-keys="selectedKeys"
        class="navigation"
        mode="horizontal"
        :items="menuItems"
        @click="handleMenuClick"
      />

      <a-space class="account-area">
        <template v-if="userStore.isLogin">
          <a-dropdown>
            <a-button type="text">{{ userStore.user?.userName || userStore.user?.userAccount }}</a-button>
            <template #overlay>
              <a-menu>
                <a-menu-item v-if="userStore.isAdmin" key="admin" @click="goAdmin">管理后台</a-menu-item>
                <a-menu-item key="logout" @click="handleLogout">退出登录</a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </template>
        <template v-else>
          <a-button type="link" @click="router.push('/login')">登录</a-button>
          <a-button type="primary" ghost @click="router.push('/register')">注册</a-button>
        </template>
      </a-space>
    </div>
  </a-layout-header>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { MenuProps } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const menuItems = computed<MenuProps['items']>(() => {
  const items: MenuProps['items'] = [
    { key: '/', label: '首页', title: '首页' },
  ]
  // 工作区标识项：仅指示当前位于某个应用的工作区，没有固定跳转目标。
  if (route.path.startsWith('/app/')) {
    items.push({ key: '/app', label: '应用工作区', title: '当前处于应用工作区' })
  }
  if (userStore.isAdmin) {
    items.push({ key: '/admin', label: '后台管理', title: '后台管理' })
  }
  items.push({ key: '/about', label: '关于项目', title: '关于项目' })
  return items
})

// 高亮精确跟随当前界面：首页只在首页高亮，工作区和后台各有独立标识，
// 登录/注册等辅助页面不高亮任何项，避免“明明在工作区，光标却停在首页”。
const selectedKeys = computed({
  get: () => {
    if (route.path.startsWith('/admin')) return ['/admin']
    if (route.path.startsWith('/app/')) return ['/app']
    if (route.path === '/about') return ['/about']
    if (route.path === '/') return ['/']
    return []
  },
  set: () => undefined,
})

const handleMenuClick: MenuProps['onClick'] = ({ key }) => {
  const path = String(key)
  if (path === '/app') return
  if (path.startsWith('/')) {
    void router.push(path)
  }
}

const goAdmin = () => void router.push('/admin')

const handleLogout = async () => {
  try {
    await userStore.logout()
    message.success('已退出登录')
    await router.push('/')
  } catch {
    message.error('服务端退出失败，已清理本地登录状态')
    await router.push('/')
  }
}
</script>

<style scoped>
.header {
  height: auto;
  padding: 0 24px;
  background: #ffffff;
  border-bottom: 1px solid #e5e7eb;
  line-height: normal;
}

.header-inner {
  display: flex;
  align-items: center;
  max-width: 1200px;
  min-height: 64px;
  margin: 0 auto;
  gap: 24px;
}

.brand {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 10px;
  color: #111827;
  font-weight: 650;
  white-space: nowrap;
}

.logo {
  width: 36px;
  height: 36px;
}

.site-title {
  font-size: 16px;
}

.navigation {
  flex: 1;
  min-width: 0;
  border-bottom: 0;
}

.account-area {
  flex: 0 0 auto;
}

@media (max-width: 680px) {
  .header {
    padding: 0 12px;
  }

  .header-inner {
    gap: 12px;
  }

  .site-title {
    display: none;
  }
}
</style>
