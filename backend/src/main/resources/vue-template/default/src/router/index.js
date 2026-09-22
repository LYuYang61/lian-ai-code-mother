import { createRouter, createWebHashHistory } from 'vue-router'
import HomePage from '@/pages/HomePage.vue'

export default createRouter({
  history: createWebHashHistory(),
  routes: [{ path: '/', name: 'home', component: HomePage }],
})
