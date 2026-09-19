import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./http', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}))

import http from './http'
import {
  RESOURCE_STATUSES,
  RESOURCE_TYPES,
  createAdminResource,
  disableAdminResource,
  emptyResourceForm,
  getAdminResources,
  updateAdminResource,
} from './adminResource'

describe('管理端资源 API', () => {
  beforeEach(() => vi.clearAllMocks())

  it('枚举与后端 ResourceType / ResourceStatus 完全一致', () => {
    expect(RESOURCE_TYPES).toEqual(['LAB', 'MEETING_ROOM', 'GPU', 'EQUIPMENT', 'WORKSTATION'])
    expect(RESOURCE_STATUSES).toEqual(['ACTIVE', 'DISABLED', 'MAINTENANCE'])
  })

  it('列表使用后端 0-based 分页', async () => {
    http.get.mockResolvedValue({ status: 200, data: { content: [], totalElements: 0 } })
    await getAdminResources({ page: 0, size: 10 })
    expect(http.get).toHaveBeenCalledWith('/admin/resources', { params: { page: 0, size: 10 } })
  })

  it('创建资源提交完整 AdminResourceRequest 字段', async () => {
    http.post.mockResolvedValue({ status: 201, data: { id: 1 } })
    const payload = {
      name: '验收测试资源',
      type: 'LAB',
      location: 'A-101',
      status: 'ACTIVE',
      capacity: 4,
      description: '联调用',
    }
    await createAdminResource(payload)
    expect(http.post).toHaveBeenCalledWith('/admin/resources', payload)
  })

  it('更新资源走 PUT /admin/resources/{id}', async () => {
    http.put.mockResolvedValue({ status: 200, data: { id: 7 } })
    await updateAdminResource(7, { name: '改名', type: 'GPU', location: 'B-2', status: 'MAINTENANCE', capacity: 1, description: '' })
    expect(http.put).toHaveBeenCalledWith('/admin/resources/7', expect.objectContaining({ name: '改名' }))
  })

  it('停用资源走 DELETE /admin/resources/{id}（后端为软删除）', async () => {
    http.delete.mockResolvedValue({ status: 204, data: '' })
    await disableAdminResource(7)
    expect(http.delete).toHaveBeenCalledWith('/admin/resources/7')
  })

  it('403 拒绝会向调用方抛出，交由全局拦截器处理（不清登录态）', async () => {
    http.get.mockRejectedValue({ response: { status: 403, data: { message: 'Access denied' } } })
    await expect(getAdminResources({ page: 0, size: 10 })).rejects.toMatchObject({
      response: { status: 403 },
    })
  })

  it('默认表单给出合法初值', () => {
    expect(emptyResourceForm()).toEqual({
      name: '',
      type: 'LAB',
      location: '',
      status: 'ACTIVE',
      capacity: 1,
      description: '',
    })
  })
})
