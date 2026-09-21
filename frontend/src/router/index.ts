import { createRouter, createWebHistory } from 'vue-router'
import AboutPage from '@/pages/AboutPage.vue'
import HomePage from '@/pages/HomePage.vue'
import LoginPage from '@/pages/LoginPage.vue'
import RegisterPage from '@/pages/RegisterPage.vue'
import AppDetailPage from '@/pages/AppDetailPage.vue'
import AdminLayoutPage from '@/pages/AdminLayoutPage.vue'
import AdminAppsPage from '@/pages/AdminAppsPage.vue'
import AdminUsersPage from '@/pages/AdminUsersPage.vue'
import AdminChatHistoryPage from '@/pages/AdminChatHistoryPage.vue'
import { useUserStore } from '@/stores/user'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomePage,
    },
    {
      path: '/about',
      name: 'about',
      component: AboutPage,
    },
    {
      path: '/login',
      name: 'login',
      component: LoginPage,
    },
    {
      path: '/register',
      name: 'register',
      component: RegisterPage,
    },
    {
      path: '/app/:id',
      name: 'app-detail',
      component: AppDetailPage,
    },
    {
      path: '/admin',
      component: AdminLayoutPage,
      redirect: '/admin/apps',
      meta: { requiresAdmin: true },
      children: [
        {
          path: 'apps',
          name: 'admin-apps',
          component: AdminAppsPage,
          meta: { requiresAdmin: true },
        },
        {
          path: 'users',
          name: 'admin-users',
          component: AdminUsersPage,
          meta: { requiresAdmin: true },
        },
        {
          path: 'chats',
          name: 'admin-chats',
          component: AdminChatHistoryPage,
          meta: { requiresAdmin: true },
        },
      ],
    },
  ],
})

router.beforeEach(async (to) => {
  const userStore = useUserStore()
  await userStore.initialize()
  if (to.meta.requiresAdmin && !userStore.isAdmin) {
    return userStore.isLogin ? '/' : `/login?redirect=${encodeURIComponent(to.fullPath)}`
  }
  if (to.meta.requiresAuth && !userStore.isLogin) {
    return `/login?redirect=${encodeURIComponent(to.fullPath)}`
  }
  return true
})

export default router
