/**
 * 路由守卫的纯逻辑部分。
 *
 * 抽成独立模块是为了能脱离 vue-router 直接单测（项目未安装 jsdom，
 * 无法在 Node 环境里真正 createRouter）。
 *
 * 注意：这里的 role 判断只用于前端路由体验与菜单展示，
 * 真正的权限校验始终由后端 Spring Security 完成。
 */

export const ADMIN_ROLE = 'ADMIN'

export function isAdmin(role) {
  return role === ADMIN_ROLE
}

/**
 * 计算路由跳转目标。
 * @returns {null | object} null 表示放行；对象表示需要重定向到该 location
 */
export function resolveGuardRedirect({ isAuthenticated, role, meta, fullPath } = {}) {
  const requiresAuth = Boolean(meta?.requiresAuth)
  const requiresAdmin = Boolean(meta?.requiresAdmin)

  if (!requiresAuth && !requiresAdmin) return null

  if (!isAuthenticated) {
    return { name: 'login', query: { redirect: fullPath } }
  }

  if (requiresAdmin && !isAdmin(role)) {
    return { name: 'resources' }
  }

  return null
}
