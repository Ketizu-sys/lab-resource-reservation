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
