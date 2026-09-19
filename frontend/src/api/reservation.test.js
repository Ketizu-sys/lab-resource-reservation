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
  getReservationRequestStatus,
  isDuplicateQueueRequest,
  isQueuedResponse,
  isTerminalRequestStatus,
  requestAutoReservation,
  shouldContinuePolling,
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

describe('自动预约 API', () => {
  beforeEach(() => vi.clearAllMocks())

  it('发起自动预约使用统一实例且不携带请求体', async () => {
    http.post.mockResolvedValue({ status: 200, data: { requestId: 'direct-9', status: 'SUCCESS' } })
    await requestAutoReservation()
    expect(http.post).toHaveBeenCalledWith('/me/reservation-requests')
  })

  it('状态查询按 requestId 组装路径', async () => {
    http.get.mockResolvedValue({ status: 200, data: { requestId: 'abc', status: 'QUEUED' } })
    await getReservationRequestStatus('abc')
    expect(http.get).toHaveBeenCalledWith('/me/reservation-requests/abc')
  })

  it('同步与异步只能依据 HTTP 状态码区分', () => {
    expect(isQueuedResponse(202)).toBe(true)
    expect(isQueuedResponse(200)).toBe(false)
  })

  it('只有 SUCCESS / FAILED 是终态', () => {
    expect(isTerminalRequestStatus('SUCCESS')).toBe(true)
    expect(isTerminalRequestStatus('FAILED')).toBe(true)
    expect(isTerminalRequestStatus('QUEUED')).toBe(false)
    expect(isTerminalRequestStatus('PROCESSING')).toBe(false)
  })

  it('QUEUED 与 PROCESSING 需要继续轮询', () => {
    expect(shouldContinuePolling('QUEUED')).toBe(true)
    expect(shouldContinuePolling('PROCESSING')).toBe(true)
    expect(shouldContinuePolling('SUCCESS')).toBe(false)
    expect(shouldContinuePolling('FAILED')).toBe(false)
  })

  it('409 识别为已有排队请求，401 不当作排队冲突', () => {
    expect(isDuplicateQueueRequest({ response: { status: 409 } })).toBe(true)
    expect(isDuplicateQueueRequest({ response: { status: 401 } })).toBe(false)
    expect(isDuplicateQueueRequest({ response: { status: 500 } })).toBe(false)
    expect(isDuplicateQueueRequest({})).toBe(false)
  })
})
