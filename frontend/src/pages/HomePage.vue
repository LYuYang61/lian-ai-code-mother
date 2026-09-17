<template>
  <div class="home-page">
    <a-card class="hero-card" :bordered="false">
      <a-typography-title :level="1">AI 应用生成平台</a-typography-title>
      <a-typography-paragraph class="subtitle">
        从一句自然语言描述开始，逐步学习 AI 全栈应用的设计与实现。
      </a-typography-paragraph>
      <a-space wrap>
        <a-tag color="blue">Spring Boot 3.5</a-tag>
        <a-tag color="green">Vue 3</a-tag>
        <a-tag color="purple">TypeScript</a-tag>
      </a-space>
    </a-card>

    <a-card title="初始化验证" class="check-card">
      <a-alert
        v-if="healthState === 'success'"
        message="后端健康检查成功"
        description="前端已经通过 Axios 请求 /api/health/，可以继续进行后续期次。"
        type="success"
        show-icon
      />
      <a-alert
        v-else-if="healthState === 'error'"
        message="暂时无法连接后端"
        description="请确认 Windows 后端运行在 localhost:8123，并检查 Vite 代理配置。"
        type="warning"
        show-icon
      />
      <a-skeleton v-else active />
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { healthCheck } from '@/api/healthController'

type HealthState = 'loading' | 'success' | 'error'

const healthState = ref<HealthState>('loading')

onMounted(async () => {
  try {
    const response = await healthCheck()
    healthState.value = response.data.code === 0 && response.data.data === 'ok' ? 'success' : 'error'
  } catch {
    healthState.value = 'error'
  }
})
</script>

<style scoped>
.home-page {
  width: min(100%, 1040px);
  margin: 0 auto;
}

.hero-card {
  padding: 24px;
  background: linear-gradient(135deg, #ffffff 0%, #eef4ff 100%);
}

.subtitle {
  max-width: 640px;
  font-size: 16px;
}

.check-card {
  margin-top: 24px;
}
</style>
