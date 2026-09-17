<template>
  <a-card class="about-page" :bordered="false">
    <a-typography-title :level="2">关于这个学习项目</a-typography-title>
    <a-typography-paragraph>
      本项目只借鉴教程的学习路线和功能目标，代码、依赖和安全边界均独立实现。
    </a-typography-paragraph>
    <a-typography-paragraph>
      后端和前端实际在 Windows 环境运行；WSL2 仅作为辅助工具和后续 Redis Docker 运行环境。
    </a-typography-paragraph>
  </a-card>
</template>

<style scoped>
.about-page {
  width: min(100%, 1040px);
  margin: 0 auto;
}
</style>
