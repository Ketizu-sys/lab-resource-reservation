import http from './http'

export function getSlots(params) {
  return http.get('/slots', { params })
}
