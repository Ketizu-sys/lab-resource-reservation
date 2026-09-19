import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'

const http = axios.create({
  baseURL: '/api/v1',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
})

export function attachBearerToken(config, token) {
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
}

export function handleAccessError({ status, authStore, currentPath, redirectToLogin, notifyForbidden }) {
  if (status === 401) {
    authStore.clearSession()
    if (currentPath !== '/login') redirectToLogin()
  } else if (status === 403) {
    notifyForbidden()
  }
}

http.interceptors.request.use((config) => {
  const authStore = useAuthStore()
  return attachBearerToken(config, authStore.token)
})

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error.response?.status
    handleAccessError({
      status,
      authStore: useAuthStore(),
      currentPath: window.location.pathname,
      redirectToLogin: () => window.location.replace('/login'),
      notifyForbidden: () => ElMessage.warning('当前账号无权执行此操作'),
    })
    return Promise.reject(error)
  },
)

export default http
