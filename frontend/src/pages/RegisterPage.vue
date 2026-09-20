<template>
  <a-card class="auth-card" :bordered="false">
    <a-typography-title :level="2">创建账号</a-typography-title>
    <a-form layout="vertical" :model="form" @finish="handleSubmit">
      <a-form-item label="账号" name="userAccount" :rules="[{ required: true, message: '请输入账号' }]">
        <a-input v-model:value="form.userAccount" autocomplete="username" />
      </a-form-item>
      <a-form-item label="昵称" name="userName">
        <a-input v-model:value="form.userName" />
      </a-form-item>
      <a-form-item label="密码" name="userPassword" :rules="[{ required: true, min: 8, message: '密码至少 8 位' }]">
        <a-input-password v-model:value="form.userPassword" autocomplete="new-password" />
      </a-form-item>
      <a-button type="primary" html-type="submit" block :loading="loading">注册</a-button>
    </a-form>
    <a-typography-paragraph class="auth-link">
      已有账号？<RouterLink to="/login">返回登录</RouterLink>
    </a-typography-paragraph>
  </a-card>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { register } from '@/api/user'

const router = useRouter()
const loading = ref(false)
const form = reactive({ userAccount: '', userName: '', userPassword: '' })

const handleSubmit = async () => {
  loading.value = true
  try {
    const response = await register(form)
    if (response.data.code !== 0) throw new Error(response.data.message)
    message.success('注册成功，请登录')
    await router.push('/login')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '注册失败')
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
