import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./http', () => ({
  default: {
    get: vi.fn(),
    delete: vi.fn(),
    post: vi.fn(),
  },
}))

import http from './http'
import {
  buildReservationQuery,
  canCancelReservation,
  cancelReservation,
  cancelReservationAndRefresh,
  getMyReservations,
} from './reservation'

describe('我的预约 API', () => {
  beforeEach(() => vi.clearAllMocks())

  it('构造状态筛选和后端 0-based 分页参数', () => {
    expect(buildReservationQuery({ status: 'ACTIVE', page: 0, size: 10 })).toEqual({
      status: 'ACTIVE',
      page: 0,
      size: 10,
    })
    expect(buildReservationQuery({ status: '', page: 1, size: 20 })).toEqual({ page: 1, size: 20 })
  })

  it('列表请求使用构造后的查询参数', async () => {
    http.get.mockResolvedValue({ data: { content: [] } })
    await getMyReservations({ status: 'CANCELLED', page: 0, size: 10 })
    expect(http.get).toHaveBeenCalledWith('/me/reservations', {
      params: { status: 'CANCELLED', page: 0, size: 10 },
    })
  })

  it('只有 ACTIVE 状态显示取消操作', () => {
    expect(canCancelReservation('ACTIVE')).toBe(true)
    expect(canCancelReservation('CANCELLED')).toBe(false)
    expect(canCancelReservation('COMPLETED')).toBe(false)
  })

  it('正确处理后端 204 取消响应', async () => {
    http.delete.mockResolvedValue({ status: 204, data: '' })
    const response = await cancelReservation(21)
    expect(http.delete).toHaveBeenCalledWith('/me/reservations/21')
    expect(response.status).toBe(204)
    expect(response.data).toBe('')
  })

  it('取消成功后刷新预约列表', async () => {
    http.delete.mockResolvedValue({ status: 204, data: '' })
    const refresh = vi.fn().mockResolvedValue()
    await cancelReservationAndRefresh(22, refresh)
    expect(http.delete).toHaveBeenCalledWith('/me/reservations/22')
    expect(refresh).toHaveBeenCalledOnce()
  })
})
