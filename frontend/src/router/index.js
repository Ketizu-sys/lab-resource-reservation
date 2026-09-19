import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import LoginView from '../views/LoginView.vue'
import RegisterView from '../views/RegisterView.vue'
import ResourcesView from '../views/ResourcesView.vue'
import SlotsView from '../views/SlotsView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/resources' },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/register', name: 'register', component: RegisterView },
    { path: '/resources', name: 'resources', component: ResourcesView, meta: { requiresAuth: true } },
    { path: '/slots', name: 'slots', component: SlotsView, meta: { requiresAuth: true } },
    { path: '/:pathMatch(.*)*', redirect: '/resources' },
  ],
})

router.beforeEach((to) => {
  const authStore = useAuthStore()
  if (to.meta.requiresAuth && !authStore.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && authStore.isAuthenticated) {
    return { name: 'resources' }
  }
  return true
})

export default router
