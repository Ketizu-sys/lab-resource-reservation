<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login } from '../api/auth'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const formRef = ref()
const submitting = ref(false)
const form = reactive({ email: '', password: '' })
const rules = {
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '请输入有效邮箱', trigger: 'blur' },
  ],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function submit() {
  if (!await formRef.value.validate().catch(() => false)) return
  submitting.value = true
  try {
    const { data } = await login(form)
    authStore.setSession({
      token: data.token,
      email: data.email,
      username: data.userName,
      role: data.role,
    })
    ElMessage.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/')
      ? route.query.redirect
      : '/resources'
    await router.replace(redirect)
  } catch (error) {
    if (error.response?.status !== 401) ElMessage.error('登录失败，请稍后重试')
    else ElMessage.error('邮箱或密码错误')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-card class="auth-card" shadow="hover">
    <h1 class="auth-title">欢迎回来</h1>
    <p class="auth-subtitle">登录后查询实验室资源并预约可用时段</p>
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="submit">
      <el-form-item label="邮箱" prop="email">
        <el-input v-model.trim="form.email" autocomplete="email" placeholder="user@example.com" />
      </el-form-item>
      <el-form-item label="密码" prop="password">
        <el-input v-model="form.password" type="password" show-password autocomplete="current-password" />
      </el-form-item>
      <el-button type="primary" size="large" :loading="submitting" style="width: 100%" @click="submit">
        登录
      </el-button>
    </el-form>
    <el-divider />
    <div style="text-align: center">还没有账号？<el-link type="primary" @click="$router.push('/register')">立即注册</el-link></div>
  </el-card>
</template>
