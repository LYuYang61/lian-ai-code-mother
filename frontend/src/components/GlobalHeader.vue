<template>
  <a-layout-header class="header">
    <div class="header-inner">
      <RouterLink class="brand" to="/" aria-label="返回首页">
        <img class="logo" src="@/assets/logo.svg" alt="Lian AI Code Monther Logo" />
        <span class="site-title">Lian AI Code Monther</span>
      </RouterLink>

      <a-menu
        v-model:selected-keys="selectedKeys"
        class="navigation"
        mode="horizontal"
        :items="menuItems"
        @click="handleMenuClick"
      />

      <div class="header-placeholder" aria-hidden="true" />
    </div>
  </a-layout-header>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { MenuProps } from 'ant-design-vue'

const router = useRouter()
const route = useRoute()

const menuItems: MenuProps['items'] = [
  { key: '/', label: '首页', title: '首页' },
  { key: '/about', label: '关于项目', title: '关于项目' },
]

const selectedKeys = computed({
  get: () => [route.path === '/about' ? '/about' : '/'],
  set: () => undefined,
})

const handleMenuClick: MenuProps['onClick'] = ({ key }) => {
  const path = String(key)
  if (path.startsWith('/')) {
    void router.push(path)
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

.header-placeholder {
  width: 36px;
}

@media (max-width: 680px) {
  .header {
    padding: 0 12px;
  }

  .header-inner {
    gap: 12px;
  }

  .site-title,
  .header-placeholder {
    display: none;
  }
}
</style>
