import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getLoginUser, login as loginRequest, logout as logoutRequest } from '@/api/user'
import type { LoginUser } from '@/api/types'

export const useUserStore = defineStore('user', () => {
  const user = ref<LoginUser | null>(null)
  const initialized = ref(false)

  const isLogin = computed(() => user.value !== null)
  const isAdmin = computed(() => user.value?.userRole === 'admin')

  async function initialize() {
    if (initialized.value) return
    try {
      const response = await getLoginUser()
      if (response.data.code === 0) {
        user.value = response.data.data
      }
    } catch {
      user.value = null
    } finally {
      initialized.value = true
    }
  }

  async function login(userAccount: string, userPassword: string) {
    const response = await loginRequest({ userAccount, userPassword })
    if (response.data.code !== 0 || !response.data.data) {
      throw new Error(response.data.message)
    }
    user.value = response.data.data
  }

  async function logout() {
    try {
      await logoutRequest()
    } finally {
      // 服务端退出失败时也清理本地会话，避免界面继续把失效 Cookie 当作已登录状态。
      user.value = null
    }
  }

  return { user, initialized, isLogin, isAdmin, initialize, login, logout }
})
