import http from './http'

export function createReservation(slotId) {
  return http.post('/me/reservations', { slotId })
}
