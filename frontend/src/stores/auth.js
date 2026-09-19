import { computed, ref } from 'vue'
import { createPinia, defineStore } from 'pinia'

const STORAGE_KEY = 'lab-reservation-auth'

function readStoredSession() {
  try {
    const value = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}')
    return {
      token: typeof value.token === 'string' ? value.token : '',
      email: typeof value.email === 'string' ? value.email : '',
      username: typeof value.username === 'string' ? value.username : '',
      role: typeof value.role === 'string' ? value.role : '',
    }
  } catch {
    localStorage.removeItem(STORAGE_KEY)
    return { token: '', email: '', username: '', role: '' }
  }
}

export const pinia = createPinia()

export const useAuthStore = defineStore('auth', () => {
  const initial = readStoredSession()
  const token = ref(initial.token)
  const email = ref(initial.email)
  const username = ref(initial.username)
  const role = ref(initial.role)
  const isAuthenticated = computed(() => Boolean(token.value))

  function setSession(session) {
    token.value = session.token || ''
    email.value = session.email || ''
    username.value = session.username || ''
    role.value = session.role || ''
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      token: token.value,
      email: email.value,
      username: username.value,
      role: role.value,
    }))
  }

  function clearSession() {
    token.value = ''
    email.value = ''
    username.value = ''
    role.value = ''
    localStorage.removeItem(STORAGE_KEY)
  }

  return { token, email, username, role, isAuthenticated, setSession, clearSession }
})
