import http from './http'

/**
 * 管理端时段接口。
 *
 * 时间一律按用户本地日历字段拼字符串（getFullYear / getMonth ...），
 * 不做任何时区换算，避免把 09:00 变成 01:00 或 17:00。
 */

function pad(value) {
  return String(value).padStart(2, '0')
}

function toDate(value) {
  if (value instanceof Date) return value
  if (typeof value === 'string') {
    // 只把带时间部分的 ISO 串交给 Date；纯日期串 "YYYY-MM-DD" 会被当成 UTC 午夜，
    // 在负时区下 getDate() 会退一天，因此单独走字符串分支。
    if (/^\d{4}-\d{2}-\d{2}T/.test(value)) return new Date(value)
    return null
  }
  return null
}

function normalizeTime(text) {
  const parts = text.split(':')
  return `${pad(parts[0])}:${pad(parts[1] || '00')}:${pad(parts[2] || '00')}`
}

/** Date 或 "YYYY-MM-DD" / ISO 串 -> "YYYY-MM-DD"，对应后端 LocalDate。 */
export function formatLocalDate(value) {
  if (!value) return ''
  if (typeof value === 'string') {
    const match = value.match(/^(\d{4}-\d{2}-\d{2})/)
    if (match) return match[1]
  }
  const date = toDate(value)
  if (!date || Number.isNaN(date.getTime())) return ''
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** Date 或 "HH:mm[:ss]" 串 -> "HH:mm:ss"，对应后端 LocalTime。 */
export function formatLocalTime(value) {
  if (!value) return ''
  if (typeof value === 'string' && /^\d{1,2}:\d{2}(:\d{2})?$/.test(value)) {
    return normalizeTime(value)
  }
  const date = toDate(value)
  if (!date || Number.isNaN(date.getTime())) return ''
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/** Date 或 ISO 串 -> "YYYY-MM-DDTHH:mm:ss"，对应后端 LocalDateTime + ISO DATE_TIME。 */
export function formatLocalDateTime(value) {
  if (!value) return ''
  if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?/.test(value)) {
    const [datePart, timePart = '00:00:00'] = value.split('T')
    return `${datePart}T${normalizeTime(timePart)}`
  }
  const date = toDate(value)
  if (!date || Number.isNaN(date.getTime())) return ''
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export function getAdminSlots(params) {
  return http.get('/admin/slots', { params })
}

export function createAdminSlot(payload) {
  return http.post('/admin/slots', payload)
}

/** 批量生成，后端返回 201 + List<AdminSlotResponse>。 */
export function createAdminSlotBatch(payload) {
  return http.post('/admin/slots/batches', payload)
}

export function updateAdminSlot(id, payload) {
  return http.put(`/admin/slots/${id}`, payload)
}

export function deleteAdminSlot(id) {
  return http.delete(`/admin/slots/${id}`)
}

/** 把页面表单转换成 AdminSlotRequest。 */
export function buildAdminSlotPayload(form) {
  return {
    resourceId: Number(form.resourceId),
    startTime: formatLocalDateTime(form.startTime),
    endTime: formatLocalDateTime(form.endTime),
  }
}

/** 把页面表单转换成 BatchSlotRequest。 */
export function buildBatchSlotPayload(form) {
  return {
    resourceId: Number(form.resourceId),
    startDate: formatLocalDate(form.startDate),
    endDate: formatLocalDate(form.endDate),
    dailyStartTime: formatLocalTime(form.dailyStartTime),
    dailyEndTime: formatLocalTime(form.dailyEndTime),
    durationMinutes: Number(form.durationMinutes),
  }
}
