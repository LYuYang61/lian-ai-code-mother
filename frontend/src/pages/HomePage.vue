<template>
  <div class="home-page">
    <a-card class="hero-card" :bordered="false">
      <div class="hero-copy">
        <a-typography-title :level="1">用一句话生成自己的网页应用</a-typography-title>
        <a-typography-paragraph class="subtitle">
          这是第四期的完整练习入口：登录、创建应用、通过 SSE 观察 AI 输出、保存版本并部署预览。
        </a-typography-paragraph>
        <a-space wrap>
          <a-tag color="blue">Spring Boot 3.5</a-tag>
          <a-tag color="green">MyBatis-Flex</a-tag>
          <a-tag color="purple">LangChain4j</a-tag>
          <a-tag color="orange">SSE</a-tag>
        </a-space>
      </div>
      <a-button type="primary" size="large" @click="openCreate">创建应用</a-button>
    </a-card>

    <a-card title="精选应用" :bordered="false" class="section-card">
      <template #extra>
        <a-space wrap>
          <a-input-search v-model:value="featuredSearch" placeholder="搜索名称、需求或标签" allow-clear @search="searchFeatured" />
          <a-input v-model:value="featuredCategory" placeholder="分类" allow-clear @change="searchFeatured" />
          <a-input v-model:value="featuredTag" placeholder="标签" allow-clear @change="searchFeatured" />
        </a-space>
      </template>
      <a-empty v-if="!featured.length && !loading" description="暂时还没有精选应用" />
      <a-row v-else :gutter="16">
        <a-col v-for="app in featured" :key="app.id" :xs="24" :sm="12" :lg="8" :xl="6">
          <a-card hoverable class="app-card" @click="openApp(app.id)">
            <div class="app-card-cover">
              <img v-if="app.cover" :src="app.cover" :alt="app.appName" class="app-card-cover-image" />
              <span v-else>{{ app.codeGenType === 'multi_file' ? '多文件网页' : 'HTML 网页' }}</span>
            </div>
            <a-typography-title :level="5" ellipsis>{{ app.appName }}</a-typography-title>
            <a-space wrap size="small">
              <a-tag v-if="app.category">{{ app.category }}</a-tag>
              <a-tag v-for="tag in splitTags(app.tags)" :key="tag" color="blue">{{ tag }}</a-tag>
            </a-space>
          </a-card>
        </a-col>
      </a-row>
      <a-pagination v-if="featuredTotal > featuredPageSize" v-model:current="featuredPage" :page-size="featuredPageSize"
        :total="featuredTotal" show-less-items @change="loadFeatured" />
    </a-card>

    <a-card v-if="userStore.isLogin" :bordered="false" class="section-card">
      <template #title>
        <a-segmented v-model:value="appTab" :options="appTabOptions" @change="handleTabChange" />
      </template>
      <template #extra><a-button type="link" @click="refreshActiveTab">刷新</a-button></template>
      <a-list v-if="activeApps.length" :data-source="activeApps" item-layout="horizontal">
        <template #renderItem="{ item }">
          <a-list-item>
            <a-list-item-meta :title="item.appName" :description="item.generationMessage || item.initPrompt">
              <template #avatar><a-avatar>{{ item.appName.slice(0, 1) }}</a-avatar></template>
            </a-list-item-meta>
            <a-space>
              <a-tag :color="statusColor(item.generationStatus)">{{ statusText(item.generationStatus) }}</a-tag>
              <a-button type="link" @click="openApp(item.id)">进入工作区</a-button>
            </a-space>
          </a-list-item>
        </template>
      </a-list>
      <a-empty v-else :description="appTab === 'mine' ? '还没有应用，先创建一个吧' : '还没有协作应用，被添加为协作者后会出现在这里'" />
      <a-pagination v-if="activeTotal > minePageSize" v-model:current="activePage" :page-size="minePageSize"
        :total="activeTotal" show-less-items @change="handleActivePageChange" />
    </a-card>

    <a-modal v-model:open="createOpen" title="创建 AI 应用" :confirm-loading="creating" @ok="handleCreate">
      <a-form layout="vertical" :model="createForm">
        <a-form-item label="初始需求" required>
          <a-textarea v-model:value="createForm.initPrompt" :rows="5" maxlength="10000" show-count
            placeholder="例如：生成一个有渐变背景、待办列表和本地存储的网页" />
        </a-form-item>
        <a-form-item label="代码模式">
          <a-radio-group v-model:value="createForm.codeGenType">
            <a-radio value="html">单 HTML</a-radio>
            <a-radio value="multi_file">HTML + CSS + JS</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item label="可见范围">
          <a-radio-group v-model:value="createForm.visibility">
            <a-radio value="private">私有</a-radio>
            <a-radio value="public">公开</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item label="分类"><a-input v-model:value="createForm.category" maxlength="32" /></a-form-item>
        <a-form-item label="标签"><a-input v-model:value="createForm.tags" placeholder="用逗号分隔，例如：效率,工具" /></a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { createApp, listCollaboratedApps, listFeaturedApps, listMyApps } from '@/api/app'
import type { AppGenerationStatus, AppVO } from '@/api/types'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()
const featured = ref<AppVO[]>([])
const mine = ref<AppVO[]>([])
const featuredSearch = ref('')
const featuredCategory = ref('')
const featuredTag = ref('')
const featuredPage = ref(1)
const featuredPageSize = 12
const featuredTotal = ref(0)
const minePage = ref(1)
const minePageSize = 20
const mineTotal = ref(0)
const loading = ref(false)
const createOpen = ref(false)
const creating = ref(false)
const createForm = reactive({
  initPrompt: '',
  codeGenType: 'html' as 'html' | 'multi_file',
  visibility: 'private' as 'private' | 'public',
  category: '',
  tags: '',
})

const loadFeatured = async () => {
  loading.value = true
  try {
    const response = await listFeaturedApps({
      pageNum: featuredPage.value,
      pageSize: featuredPageSize,
      searchText: featuredSearch.value || undefined,
      category: featuredCategory.value || undefined,
      tag: featuredTag.value || undefined,
    })
    if (response.data.code !== 0 || !response.data.data) throw new Error(response.data.message)
    featured.value = response.data.data.records
    featuredTotal.value = response.data.data.total
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载精选应用失败')
  } finally {
    loading.value = false
  }
}

const loadMine = async () => {
  if (!userStore.isLogin) return
  try {
    const response = await listMyApps({ pageNum: minePage.value, pageSize: minePageSize, sortField: 'updateTime', sortOrder: 'desc' })
    if (response.data.code !== 0 || !response.data.data) throw new Error(response.data.message)
    mine.value = response.data.data.records
    mineTotal.value = response.data.data.total
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载我的应用失败')
  }
}

const appTab = ref<'mine' | 'collaborated'>('mine')
const appTabOptions = [
  { label: '我的应用', value: 'mine' },
  { label: '协作应用', value: 'collaborated' },
]
const collaborated = ref<AppVO[]>([])
const collabPage = ref(1)
const collabTotal = ref(0)
const collabLoaded = ref(false)

const loadCollaborated = async () => {
  if (!userStore.isLogin) return
  try {
    const response = await listCollaboratedApps({
      pageNum: collabPage.value,
      pageSize: minePageSize,
      sortField: 'updateTime',
      sortOrder: 'desc',
    })
    if (response.data.code !== 0 || !response.data.data) throw new Error(response.data.message)
    collaborated.value = response.data.data.records
    collabTotal.value = response.data.data.total
    collabLoaded.value = true
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载协作应用失败')
  }
}

const activeApps = computed(() => (appTab.value === 'mine' ? mine.value : collaborated.value))
const activeTotal = computed(() => (appTab.value === 'mine' ? mineTotal.value : collabTotal.value))
const activePage = computed({
  get: () => (appTab.value === 'mine' ? minePage.value : collabPage.value),
  set: (value: number) => {
    if (appTab.value === 'mine') {
      minePage.value = value
    } else {
      collabPage.value = value
    }
  },
})

const handleTabChange = () => {
  // 协作列表懒加载：首次切换时才请求，避免未参与协作的用户多打一次接口。
  if (appTab.value === 'collaborated' && !collabLoaded.value) {
    void loadCollaborated()
  }
}

const refreshActiveTab = () => {
  if (appTab.value === 'mine') {
    void loadMine()
  } else {
    void loadCollaborated()
  }
}

const handleActivePageChange = () => {
  if (appTab.value === 'mine') {
    void loadMine()
  } else {
    void loadCollaborated()
  }
}

const searchFeatured = () => {
  featuredPage.value = 1
  void loadFeatured()
}

const openCreate = () => {
  if (!userStore.isLogin) {
    void router.push('/login?redirect=/')
    return
  }
  createOpen.value = true
}

const handleCreate = async () => {
  if (!createForm.initPrompt.trim()) {
    message.warning('请先填写初始需求')
    return
  }
  creating.value = true
  try {
    const response = await createApp({ ...createForm, initPrompt: createForm.initPrompt.trim() })
    if (response.data.code !== 0 || !response.data.data) throw new Error(response.data.message)
    createOpen.value = false
    await router.push(`/app/${response.data.data}`)
  } catch (error) {
    message.error(error instanceof Error ? error.message : '创建应用失败')
  } finally {
    creating.value = false
  }
}

const openApp = (id: string) => void router.push(`/app/${id}`)
const splitTags = (tags?: string | null) => (tags ? tags.split(',').filter(Boolean) : [])
const statusText = (status: AppGenerationStatus) => ({
  draft: '待生成', generating: '生成中', ready: '已完成', failed: '失败', cancelled: '已取消',
}[status])
const statusColor = (status: AppGenerationStatus) => ({
  draft: 'default', generating: 'processing', ready: 'success', failed: 'error', cancelled: 'warning',
}[status])

onMounted(() => {
  // 两个区域相互独立；精选接口失败时仍然尝试加载我的应用。
  void Promise.allSettled([loadFeatured(), loadMine()])
})
</script>

<style scoped>
.home-page {
  width: min(100%, 1200px);
  margin: 0 auto;
}

.hero-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 28px;
  background: linear-gradient(135deg, #ffffff 0%, #eef4ff 100%);
}

.hero-copy {
  min-width: 0;
}

.subtitle {
  max-width: 720px;
  font-size: 16px;
}

.section-card {
  margin-top: 24px;
}

.app-card {
  margin-bottom: 16px;
}

.app-card-cover {
  display: grid;
  height: 100px;
  place-items: center;
  margin: -24px -24px 16px;
  color: #fff;
  font-weight: 600;
  background: linear-gradient(135deg, #2563eb, #7c3aed);
}

.app-card-cover-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

@media (max-width: 680px) {
  .hero-card {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
