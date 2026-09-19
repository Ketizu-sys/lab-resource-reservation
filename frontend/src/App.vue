<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'

const authStore = useAuthStore()
const route = useRoute()
const router = useRouter()

const showHeader = computed(() => authStore.isAuthenticated && !['login', 'register'].includes(route.name))

function logout() {
  authStore.clearSession()
  router.replace('/login')
}
</script>

<template>
  <el-container class="app-shell">
    <el-header v-if="showHeader" class="app-header">
      <div>
        <div class="brand">实验室资源预约系统</div>
        <div class="identity">{{ authStore.username || authStore.email }} · {{ authStore.role }}</div>
      </div>
      <div class="header-actions">
        <el-button text @click="$router.push('/resources')">资源列表</el-button>
        <el-button text @click="$router.push('/slots')">可用时段</el-button>
        <el-button type="danger" plain @click="logout">退出登录</el-button>
      </div>
    </el-header>
    <el-main :class="{ 'auth-main': !showHeader }">
      <router-view />
    </el-main>
  </el-container>
</template>
