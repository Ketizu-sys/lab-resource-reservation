import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { resolveGuardRedirect } from './guards'
// 登录/注册是首屏直达页，静态引入；其余页面按需加载，避免全部打进主 chunk。
import LoginView from '../views/LoginView.vue'
import RegisterView from '../views/RegisterView.vue'

const ResourcesView = () => import('../views/ResourcesView.vue')
const SlotsView = () => import('../views/SlotsView.vue')
const MyReservationsView = () => import('../views/MyReservationsView.vue')
const AutoReservationView = () => import('../views/AutoReservationView.vue')
const ResourceAdminView = () => import('../views/admin/ResourceAdminView.vue')
const SlotAdminView = () => import('../views/admin/SlotAdminView.vue')
const ReservationAdminView = () => import('../views/admin/ReservationAdminView.vue')

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/resources' },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/register', name: 'register', component: RegisterView },
    { path: '/resources', name: 'resources', component: ResourcesView, meta: { requiresAuth: true } },
    { path: '/slots', name: 'slots', component: SlotsView, meta: { requiresAuth: true } },
    { path: '/my-reservations', name: 'my-reservations', component: MyReservationsView, meta: { requiresAuth: true } },
    { path: '/auto-reservation', name: 'auto-reservation', component: AutoReservationView, meta: { requiresAuth: true } },
    {
      path: '/admin/resources',
      name: 'admin-resources',
      component: ResourceAdminView,
      meta: { requiresAuth: true, requiresAdmin: true },
    },
    {
      path: '/admin/slots',
      name: 'admin-slots',
      component: SlotAdminView,
      meta: { requiresAuth: true, requiresAdmin: true },
    },
    {
      path: '/admin/reservations',
      name: 'admin-reservations',
      component: ReservationAdminView,
      meta: { requiresAuth: true, requiresAdmin: true },
    },
    { path: '/:pathMatch(.*)*', redirect: '/resources' },
  ],
})

router.beforeEach((to) => {
  const authStore = useAuthStore()

  // 前端守卫只负责路由体验；真正的权限校验始终由后端 Spring Security 完成，
  // 绕过前端直接请求 admin 接口仍会被后端以 403 拒绝。
  const redirect = resolveGuardRedirect({
    isAuthenticated: authStore.isAuthenticated,
    role: authStore.role,
    meta: to.meta,
    fullPath: to.fullPath,
  })
  if (redirect) return redirect

  if (to.name === 'login' && authStore.isAuthenticated) {
    return { name: 'resources' }
  }
  return true
})

export default router
