import http from './http'

export function getResources(params) {
  return http.get('/resources', { params })
}
