import { describe, expect, it } from 'vitest'
import { ADMIN_ROLE, isAdmin, resolveGuardRedirect } from './guards'

const ADMIN_META = { requiresAuth: true, requiresAdmin: true }
const USER_META = { requiresAuth: true }

describe('管理员路由守卫', () => {
  it('只有 ADMIN 角色被认为具备管理权限', () => {
    expect(isAdmin('ADMIN')).toBe(true)
    expect(isAdmin('USER')).toBe(false)
    expect(isAdmin('')).toBe(false)
    expect(isAdmin(undefined)).toBe(false)
  })

  it('USER 访问管理页面被拦回资源页（对应需求：USER 无法进入 admin）', () => {
    expect(
      resolveGuardRedirect({
        isAuthenticated: true,
        role: 'USER',
        meta: ADMIN_META,
        fullPath: '/admin/resources',
      }),
    ).toEqual({ name: 'resources' })
  })

  it('ADMIN 访问管理页面放行', () => {
    expect(
      resolveGuardRedirect({
        isAuthenticated: true,
        role: ADMIN_ROLE,
        meta: ADMIN_META,
        fullPath: '/admin/resources',
      }),
    ).toBeNull()
  })

  it('未登录访问管理页面跳转登录并带上回跳路径', () => {
    expect(
      resolveGuardRedirect({
        isAuthenticated: false,
        role: '',
        meta: ADMIN_META,
        fullPath: '/admin/slots',
      }),
    ).toEqual({ name: 'login', query: { redirect: '/admin/slots' } })
  })

  it('USER 访问普通页面不受影响', () => {
    expect(
      resolveGuardRedirect({
        isAuthenticated: true,
        role: 'USER',
        meta: USER_META,
        fullPath: '/resources',
      }),
    ).toBeNull()
  })

  it('未登录访问普通受保护页面仍跳转登录', () => {
    expect(
      resolveGuardRedirect({
        isAuthenticated: false,
        role: '',
        meta: USER_META,
        fullPath: '/my-reservations',
      }),
    ).toEqual({ name: 'login', query: { redirect: '/my-reservations' } })
  })

  it('公开页面不做拦截', () => {
    expect(
      resolveGuardRedirect({ isAuthenticated: false, role: '', meta: {}, fullPath: '/login' }),
    ).toBeNull()
  })

  it('菜单可见性判定与管理权限一致', () => {
    expect(isAdmin('ADMIN')).toBe(true)
    expect(isAdmin('USER')).toBe(false)
  })
})
