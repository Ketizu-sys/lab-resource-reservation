import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./http', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}))

import http from './http'
import {
  buildAdminSlotPayload,
  buildBatchSlotPayload,
  createAdminSlot,
  createAdminSlotBatch,
  deleteAdminSlot,
  formatLocalDate,
  formatLocalDateTime,
  formatLocalTime,
  getAdminSlots,
  updateAdminSlot,
} from './adminSlot'

describe('时间格式化（不做任何时区换算）', () => {
  it('Date 按本地日历字段格式化，不产生 ±8 小时偏移', () => {
    expect(formatLocalDateTime(new Date(2026, 8, 20, 9, 0, 0))).toBe('2026-09-20T09:00:00')
  })

  it('ISO 字符串原样保留，不经过 Date 解析', () => {
    expect(formatLocalDateTime('2026-09-20T09:00:00')).toBe('2026-09-20T09:00:00')
    expect(formatLocalDateTime('2026-09-20T09:00')).toBe('2026-09-20T09:00:00')
  })

  it('纯日期串不会被当成 UTC 午夜解析（避免负时区退一天）', () => {
    expect(formatLocalDate('2026-09-20')).toBe('2026-09-20')
    expect(formatLocalDate(new Date(2026, 8, 20, 23, 59, 59))).toBe('2026-09-20')
  })

  it('时间串补齐到 HH:mm:ss', () => {
    expect(formatLocalTime('09:00:00')).toBe('09:00:00')
    expect(formatLocalTime('09:00')).toBe('09:00:00')
    expect(formatLocalTime(new Date(2026, 8, 20, 9, 5, 0))).toBe('09:05:00')
  })

  it('空值返回空串', () => {
    expect(formatLocalDateTime('')).toBe('')
    expect(formatLocalDate(null)).toBe('')
    expect(formatLocalTime(undefined)).toBe('')
  })
})

describe('管理端时段 API', () => {
  beforeEach(() => vi.clearAllMocks())

  it('列表使用 0-based 分页', async () => {
    http.get.mockResolvedValue({ status: 200, data: { content: [], totalElements: 0 } })
    await getAdminSlots({ page: 0, size: 10 })
    expect(http.get).toHaveBeenCalledWith('/admin/slots', { params: { page: 0, size: 10 } })
  })

  it('创建时段提交 AdminSlotRequest', async () => {
    http.post.mockResolvedValue({ status: 201, data: { id: 1 } })
    await createAdminSlot({ resourceId: 3, startTime: '2026-09-20T09:00:00', endTime: '2026-09-20T10:00:00' })
    expect(http.post).toHaveBeenCalledWith('/admin/slots', {
      resourceId: 3,
      startTime: '2026-09-20T09:00:00',
      endTime: '2026-09-20T10:00:00',
    })
  })

  it('表单转换成 AdminSlotRequest（数字化 resourceId）', () => {
    expect(
      buildAdminSlotPayload({
        resourceId: '3',
        startTime: new Date(2026, 8, 20, 9, 0, 0),
        endTime: new Date(2026, 8, 20, 10, 0, 0),
      }),
    ).toEqual({ resourceId: 3, startTime: '2026-09-20T09:00:00', endTime: '2026-09-20T10:00:00' })
  })

  it('批量生成走 /admin/slots/batches 且字段与 BatchSlotRequest 一致', async () => {
    http.post.mockResolvedValue({ status: 201, data: [{ id: 1 }, { id: 2 }] })
    await createAdminSlotBatch({
      resourceId: 3,
      startDate: '2026-09-20',
      endDate: '2026-09-21',
      dailyStartTime: '09:00:00',
      dailyEndTime: '17:00:00',
      durationMinutes: 60,
    })
    expect(http.post).toHaveBeenCalledWith('/admin/slots/batches', {
      resourceId: 3,
      startDate: '2026-09-20',
      endDate: '2026-09-21',
      dailyStartTime: '09:00:00',
      dailyEndTime: '17:00:00',
      durationMinutes: 60,
    })
  })

  it('批量表单转换补齐时间格式', () => {
    expect(
      buildBatchSlotPayload({
        resourceId: '5',
        startDate: new Date(2026, 8, 20),
        endDate: new Date(2026, 8, 21),
        dailyStartTime: '9:00',
        dailyEndTime: '17:00',
        durationMinutes: '30',
      }),
    ).toEqual({
      resourceId: 5,
      startDate: '2026-09-20',
      endDate: '2026-09-21',
      dailyStartTime: '09:00:00',
      dailyEndTime: '17:00:00',
      durationMinutes: 30,
    })
  })

  it('更新与删除使用正确的路径', async () => {
    http.put.mockResolvedValue({ status: 200, data: { id: 4 } })
    http.delete.mockResolvedValue({ status: 204, data: '' })
    await updateAdminSlot(4, { resourceId: 3, startTime: '2026-09-20T09:00:00', endTime: '2026-09-20T10:00:00' })
    await deleteAdminSlot(4)
    expect(http.put).toHaveBeenCalledWith('/admin/slots/4', expect.any(Object))
    expect(http.delete).toHaveBeenCalledWith('/admin/slots/4')
  })
})
