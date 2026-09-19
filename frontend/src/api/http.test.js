import { describe, expect, it, vi } from 'vitest'
import { attachBearerToken, handleAccessError } from './http'

describe('HTTP 认证处理', () => {
  it('有 token 时自动添加 Bearer 请求头', () => {
    const config = { headers: {} }
    expect(attachBearerToken(config, 'jwt-value').headers.Authorization).toBe('Bearer jwt-value')
  })

  it('无 token 时不添加 Authorization 请求头', () => {
    const config = { headers: {} }
    expect(attachBearerToken(config, '').headers.Authorization).toBeUndefined()
  })

  it('401 清理登录态并从受保护页面跳转登录页', () => {
    const authStore = { clearSession: vi.fn() }
    const redirectToLogin = vi.fn()
    const notifyForbidden = vi.fn()

    handleAccessError({ status: 401, authStore, currentPath: '/resources', redirectToLogin, notifyForbidden })

    expect(authStore.clearSession).toHaveBeenCalledOnce()
    expect(redirectToLogin).toHaveBeenCalledOnce()
    expect(notifyForbidden).not.toHaveBeenCalled()
  })

  it('登录页收到 401 时不重复跳转', () => {
    const authStore = { clearSession: vi.fn() }
    const redirectToLogin = vi.fn()

    handleAccessError({
      status: 401,
      authStore,
      currentPath: '/login',
      redirectToLogin,
      notifyForbidden: vi.fn(),
    })

    expect(authStore.clearSession).toHaveBeenCalledOnce()
    expect(redirectToLogin).not.toHaveBeenCalled()
  })

  it('403 只提示无权限，不清理有效登录态', () => {
    const authStore = { clearSession: vi.fn() }
    const notifyForbidden = vi.fn()

    handleAccessError({
      status: 403,
      authStore,
      currentPath: '/resources',
      redirectToLogin: vi.fn(),
      notifyForbidden,
    })

    expect(notifyForbidden).toHaveBeenCalledOnce()
    expect(authStore.clearSession).not.toHaveBeenCalled()
  })
})
