import http from './http'

/**
 * 管理端资源接口。
 * 后端 DELETE /admin/resources/{id} 是**软删除**（仅把状态置为 DISABLED），
 * 因此这里的函数名与页面文案都使用 disable / 停用。
 */

export const RESOURCE_TYPES = ['LAB', 'MEETING_ROOM', 'GPU', 'EQUIPMENT', 'WORKSTATION']
export const RESOURCE_STATUSES = ['ACTIVE', 'DISABLED', 'MAINTENANCE']

export function getAdminResources(params) {
  return http.get('/admin/resources', { params })
}

export function createAdminResource(payload) {
  return http.post('/admin/resources', payload)
}

export function updateAdminResource(id, payload) {
  return http.put(`/admin/resources/${id}`, payload)
}

/** 停用资源（后端软删除），成功返回 204。 */
export function disableAdminResource(id) {
  return http.delete(`/admin/resources/${id}`)
}

export function emptyResourceForm() {
  return {
    name: '',
    type: 'LAB',
    location: '',
    status: 'ACTIVE',
    capacity: 1,
    description: '',
  }
}
