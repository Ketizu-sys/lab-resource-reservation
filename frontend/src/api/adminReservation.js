import http from './http'
import { formatLocalDateTime } from './adminSlot'

/**
 * 管理端预约接口。
 * 后端 GET /admin/reservations 支持 userId / resourceId / status / start / end 筛选，
 * 返回 Page<AdminReservationResponse>（每条含 userId、userEmail 与 reservation 详情）。
 */

export const RESERVATION_STATUSES = ['ACTIVE', 'CANCELLED', 'COMPLETED']

/** 过滤掉空值，避免把空字符串作为查询参数发给后端。 */
export function buildAdminReservationQuery(query = {}) {
  return Object.fromEntries(
    Object.entries(query).filter(([, value]) => value !== '' && value !== null && value !== undefined),
  )
}

export function getAdminReservations(query = {}) {
  return http.get('/admin/reservations', { params: buildAdminReservationQuery(query) })
}

/** 管理员取消预约，成功返回 204。 */
export function cancelAdminReservation(id) {
  return http.delete(`/admin/reservations/${id}`)
}

export function emptyAdminReservationQuery() {
  return { userId: '', resourceId: '', status: '', start: '', end: '', page: 0, size: 10 }
}

/** 把日期区间转成后端需要的 ISO 字符串；传入 Date 或 Date[]。 */
export function toIsoRange(value) {
  if (!value) return ''
  if (Array.isArray(value)) return formatLocalDateTime(value[0])
  return formatLocalDateTime(value)
}
