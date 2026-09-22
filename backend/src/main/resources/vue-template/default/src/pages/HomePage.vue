<script setup>
import { ref } from 'vue'

const message = ref('这是 Vue 工程模板的首页，AI 会根据你的需求继续完善它。')
</script>

<template>
  <section class="hero-card">
    <p class="eyebrow">Lian AI Code</p>
    <h1>从一个想法开始构建网页应用</h1>
    <p>{{ message }}</p>
    <button type="button" @click="message = '模板已经可以正常交互，接下来可以继续扩展业务。'">体验交互</button>
  </section>
</template>

<style scoped>
.hero-card { padding: clamp(28px, 7vw, 72px); border-radius: 28px; color: white; background: linear-gradient(135deg, #315cce, #7c3aed); box-shadow: 0 24px 60px rgb(49 92 206 / 22%); }
.eyebrow { opacity: .8; text-transform: uppercase; letter-spacing: .16em; }
h1 { max-width: 720px; margin: 12px 0 20px; font-size: clamp(2rem, 5vw, 4.5rem); line-height: 1.08; }
p { max-width: 680px; line-height: 1.8; }
button { margin-top: 18px; padding: 12px 18px; border: 0; border-radius: 999px; color: #26315a; background: white; cursor: pointer; }
</style>
