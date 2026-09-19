import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./http', () => ({
  default: {
    get: vi.fn(),
    delete: vi.fn(),
  },
}))

import http from './http'
import {
  RESERVATION_STATUSES,
  buildAdminReservationQuery,
  cancelAdminReservation,
  emptyAdminReservationQuery,
  getAdminReservations,
  toIsoRange,
} from './adminReservation'

describe('管理端预约 API', () => {
  beforeEach(() => vi.clearAllMocks())

  it('状态枚举与后端 ReservationStatus 一致', () => {
    expect(RESERVATION_STATUSES).toEqual(['ACTIVE', 'CANCELLED', 'COMPLETED'])
  })

  it('过滤掉空筛选条件，避免把空串发给后端', () => {
    expect(
      buildAdminReservationQuery({ userId: '', resourceId: 3, status: 'ACTIVE', start: '', page: 0, size: 10 }),
    ).toEqual({ resourceId: 3, status: 'ACTIVE', page: 0, size: 10 })
  })

  it('无筛选时只保留分页参数', async () => {
    http.get.mockResolvedValue({ status: 200, data: { content: [], totalElements: 0 } })
    await getAdminReservations(emptyAdminReservationQuery())
    expect(http.get).toHaveBeenCalledWith('/admin/reservations', { params: { page: 0, size: 10 } })
  })

  it('按用户 / 资源 / 状态 / 时间区间筛选', async () => {
    http.get.mockResolvedValue({ status: 200, data: { content: [], totalElements: 0 } })
    await getAdminReservations({
      userId: 12,
      resourceId: 3,
      status: 'ACTIVE',
      start: '2026-09-20T00:00:00',
      end: '2026-09-21T00:00:00',
      page: 1,
      size: 20,
    })
    expect(http.get).toHaveBeenCalledWith('/admin/reservations', {
      params: {
        userId: 12,
        resourceId: 3,
        status: 'ACTIVE',
        start: '2026-09-20T00:00:00',
        end: '2026-09-21T00:00:00',
        page: 1,
        size: 20,
      },
    })
  })

  it('管理员取消预约使用 DELETE /admin/reservations/{id}', async () => {
    http.delete.mockResolvedValue({ status: 204, data: '' })
    await cancelAdminReservation(42)
    expect(http.delete).toHaveBeenCalledWith('/admin/reservations/42')
  })

  it('取消成功以 204 判定，不解析 JSON', async () => {
    http.delete.mockResolvedValue({ status: 204, data: '' })
    const response = await cancelAdminReservation(42)
    expect(response.status).toBe(204)
  })

  it('日期区间取起始值并格式化为 ISO', () => {
    expect(toIsoRange([new Date(2026, 8, 20, 9, 0, 0), new Date(2026, 8, 20, 10, 0, 0)])).toBe('2026-09-20T09:00:00')
    expect(toIsoRange('2026-09-20T09:00:00')).toBe('2026-09-20T09:00:00')
    expect(toIsoRange('')).toBe('')
  })

  it('403 拒绝向上抛出，由全局拦截器提示且不清登录态', async () => {
    http.get.mockRejectedValue({ response: { status: 403, data: { message: 'Access denied' } } })
    await expect(getAdminReservations({ page: 0, size: 10 })).rejects.toMatchObject({
      response: { status: 403 },
    })
  })
})
