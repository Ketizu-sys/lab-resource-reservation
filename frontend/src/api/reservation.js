import http from './http'

export function createReservation(slotId) {
  return http.post('/me/reservations', { slotId })
}

export function buildReservationQuery({ status = '', page = 0, size = 10 } = {}) {
  const params = { page, size }
  if (status) params.status = status
  return params
}

export function getMyReservations(query) {
  return http.get('/me/reservations', { params: buildReservationQuery(query) })
}

export function getReservation(id) {
  return http.get(`/me/reservations/${id}`)
}

export function cancelReservation(id) {
  return http.delete(`/me/reservations/${id}`)
}

export function canCancelReservation(status) {
  return status === 'ACTIVE'
}

export async function cancelReservationAndRefresh(id, refresh) {
  const response = await cancelReservation(id)
  if (response.status !== 204) {
    throw new Error(`Unexpected cancellation status: ${response.status}`)
  }
  await refresh()
  return response
}

/** 轮询超时后展示给用户的提示，与后端状态机无关。 */
export const RESERVATION_REQUEST_TIMEOUT_MESSAGE =
  '状态查询超时，请稍后在“我的预约”中确认最终结果。'

/** 后端对“同一用户已有请求在排队”返回的提示文案。 */
export const DUPLICATE_QUEUE_REQUEST_MESSAGE =
  '你已有一个自动预约请求正在处理中，请等待当前请求完成。'

/**
 * 发起自动预约：由后端决定同步处理还是进入 Redis 队列。
 * 返回 HTTP 200 表示同步完成，202 表示已进入异步队列。
 */
export function requestAutoReservation() {
  return http.post('/me/reservation-requests')
}

export function getReservationRequestStatus(requestId) {
  return http.get(`/me/reservation-requests/${encodeURIComponent(requestId)}`)
}

/** 同步/异步只能依据 HTTP 状态码判断，不能依赖 requestId 前缀。 */
export function isQueuedResponse(status) {
  return status === 202
}

/** 后端 ReservationQueueService.RequestStatus 的终态。 */
export function isTerminalRequestStatus(status) {
  return status === 'SUCCESS' || status === 'FAILED'
}

export function shouldContinuePolling(status) {
  return status === 'QUEUED' || status === 'PROCESSING'
}

/** 409 表示同一用户已有排队请求，需要友好提示而不是当作登录失效。 */
export function isDuplicateQueueRequest(error) {
  return error?.response?.status === 409
}
