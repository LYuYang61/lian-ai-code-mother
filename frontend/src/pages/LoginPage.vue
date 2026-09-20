<template>
  <a-card class="auth-card" :bordered="false">
    <a-typography-title :level="2">登录 Lian AI Code</a-typography-title>
    <a-form layout="vertical" :model="form" @finish="handleSubmit">
      <a-form-item label="账号" name="userAccount" :rules="[{ required: true, message: '请输入账号' }]">
        <a-input v-model:value="form.userAccount" autocomplete="username" />
      </a-form-item>
      <a-form-item label="密码" name="userPassword" :rules="[{ required: true, message: '请输入密码' }]">
        <a-input-password v-model:value="form.userPassword" autocomplete="current-password" />
      </a-form-item>
      <a-button type="primary" html-type="submit" block :loading="loading">登录</a-button>
    </a-form>
    <a-typography-paragraph class="auth-link">
      还没有账号？<RouterLink to="/register">立即注册</RouterLink>
    </a-typography-paragraph>
  </a-card>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const loading = ref(false)
const form = reactive({ userAccount: '', userPassword: '' })

const handleSubmit = async () => {
  loading.value = true
  try {
    await userStore.login(form.userAccount, form.userPassword)
    message.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.push(redirect)
  } catch (error) {
    message.error(error instanceof Error ? error.message : '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.auth-card {
  width: min(100%, 420px);
  margin: 48px auto;
}

.auth-link {
  margin-top: 18px;
  text-align: center;
}
</style>
