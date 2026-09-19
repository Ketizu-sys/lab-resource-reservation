<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { register } from '../api/auth'

const router = useRouter()
const formRef = ref()
const submitting = ref(false)
const form = reactive({ email: '', username: '', password: '' })
const rules = {
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '请输入有效邮箱', trigger: 'blur' },
  ],
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 50, message: '用户名长度应为 3 到 50 个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 72, message: '密码长度应为 8 到 72 个字符', trigger: 'blur' },
  ],
}

function errorMessage(error) {
  if (error.response?.status === 409) return '邮箱或用户名已被使用'
  if (error.response?.status === 400) return '注册信息不符合要求，请检查后重试'
  return '注册失败，请稍后重试'
}

async function submit() {
  if (!await formRef.value.validate().catch(() => false)) return
  submitting.value = true
  try {
    await register(form)
    ElMessage.success('注册成功，请登录')
    await router.replace('/login')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-card class="auth-card" shadow="hover">
    <h1 class="auth-title">创建普通用户账号</h1>
    <p class="auth-subtitle">注册后请使用邮箱和密码登录</p>
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="submit">
      <el-form-item label="邮箱" prop="email">
        <el-input v-model.trim="form.email" autocomplete="email" placeholder="user@example.com" />
      </el-form-item>
      <el-form-item label="用户名" prop="username">
        <el-input v-model.trim="form.username" autocomplete="username" placeholder="user123" />
      </el-form-item>
      <el-form-item label="密码" prop="password">
        <el-input v-model="form.password" type="password" show-password autocomplete="new-password" />
      </el-form-item>
      <p class="hint">密码需为 8–72 位，并包含大小写字母、数字和特殊字符。</p>
      <el-button type="primary" size="large" :loading="submitting" style="width: 100%" @click="submit">
        注册
      </el-button>
    </el-form>
    <el-divider />
    <div style="text-align: center">已有账号？<el-link type="primary" @click="$router.push('/login')">返回登录</el-link></div>
  </el-card>
</template>
