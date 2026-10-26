import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  DEFAULT_MAX_ATTEMPTS,
  DEFAULT_MAX_TRANSIENT_ERRORS,
  DEFAULT_POLL_INTERVAL_MS,
  REQUEST_STEPS,
  TIMEOUT_MESSAGE,
  requestStepIndex,
  useReservationRequestPolling,
} from './useReservationRequestPolling'

function sequencePolling(statuses, options = {}) {
  let calls = 0
  const fetchStatus = vi.fn(async () => statuses[Math.min(calls++, statuses.length - 1)])
  return { fetchStatus, polling: useReservationRequestPolling({ fetchStatus, intervalMs: 1000, ...options }) }
}

describe('自动预约轮询控制器', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('默认间隔 4000ms、最大 60 次，并允许有限临时错误重试', () => {
    expect(DEFAULT_POLL_INTERVAL_MS).toBe(4000)
    expect(DEFAULT_MAX_ATTEMPTS).toBe(60)
    expect(DEFAULT_MAX_TRANSIENT_ERRORS).toBe(3)
    expect(TIMEOUT_MESSAGE).toContain('我的预约')
  })

  it('HTTP 202 后启动轮询并记录 requestId', () => {
    const { polling } = sequencePolling(['QUEUED'])
    polling.start('req-1', 'QUEUED')
    expect(polling.polling.value).toBe(true)
    expect(polling.requestId.value).toBe('req-1')
    expect(polling.status.value).toBe('QUEUED')
  })

  it('QUEUED 与 PROCESSING 继续轮询，SUCCESS 立即停止', async () => {
    const { fetchStatus, polling } = sequencePolling(['QUEUED', 'PROCESSING', 'SUCCESS'])
    polling.start('req-1')

    await vi.advanceTimersByTimeAsync(1000)
    expect(polling.status.value).toBe('QUEUED')
    expect(polling.polling.value).toBe(true)

    await vi.advanceTimersByTimeAsync(1000)
    expect(polling.status.value).toBe('PROCESSING')
    expect(polling.polling.value).toBe(true)

    await vi.advanceTimersByTimeAsync(1000)
    expect(polling.status.value).toBe('SUCCESS')
    expect(polling.polling.value).toBe(false)
    expect(fetchStatus).toHaveBeenCalledTimes(3)
  })

  it('FAILED 立即停止轮询', async () => {
    const { fetchStatus, polling } = sequencePolling(['QUEUED', 'FAILED'])
    polling.start('req-2')

    await vi.advanceTimersByTimeAsync(1000)
    expect(polling.polling.value).toBe(true)

    await vi.advanceTimersByTimeAsync(1000)
    expect(polling.status.value).toBe('FAILED')
    expect(polling.polling.value).toBe(false)
    expect(fetchStatus).toHaveBeenCalledTimes(2)
  })

  it('达到最大次数后停止并提示超时', async () => {
    const onTimeout = vi.fn()
    const { fetchStatus, polling } = sequencePolling(['QUEUED'], { maxAttempts: 3, onTimeout })
    polling.start('req-3')

    await vi.advanceTimersByTimeAsync(3000)
    expect(fetchStatus).toHaveBeenCalledTimes(3)
    expect(polling.timedOut.value).toBe(true)
    expect(polling.polling.value).toBe(false)
    expect(onTimeout).toHaveBeenCalledOnce()
  })

  it('stop 之后不再产生新的查询（页面卸载/退出登录）', async () => {
    const { fetchStatus, polling } = sequencePolling(['QUEUED'])
    polling.start('req-4')
    polling.stop()

    await vi.advanceTimersByTimeAsync(5000)
    expect(fetchStatus).not.toHaveBeenCalled()
    expect(polling.polling.value).toBe(false)
  })

  it('重新提交前 reset 会清理旧状态', async () => {
    const { polling } = sequencePolling(['QUEUED'])
    polling.start('req-5')
    polling.reset()

    expect(polling.requestId.value).toBe('')
    expect(polling.status.value).toBe('')
    expect(polling.attempts.value).toBe(0)
    expect(polling.timedOut.value).toBe(false)
    expect(polling.polling.value).toBe(false)
  })

  it('同一时刻只允许一个轮询任务', async () => {
    const { fetchStatus, polling } = sequencePolling(['QUEUED'])
    polling.start('req-a')
    polling.start('req-b')

    await vi.advanceTimersByTimeAsync(1000)
    expect(fetchStatus).toHaveBeenCalledTimes(1)
    expect(polling.requestId.value).toBe('req-b')
  })

  it('网络临时错误重试三次后才停止轮询并回调错误', async () => {
    const onError = vi.fn()
    const fetchStatus = vi.fn(async () => {
      throw new Error('network down')
    })
    const polling = useReservationRequestPolling({
      fetchStatus,
      intervalMs: 1000,
      maxTransientErrors: 3,
      onError,
    })
    polling.start('req-6')

    await vi.advanceTimersByTimeAsync(4000)
    expect(onError).toHaveBeenCalledOnce()
    expect(polling.polling.value).toBe(false)
    expect(fetchStatus).toHaveBeenCalledTimes(4)
  })

  it('收到 429 时遵循 Retry-After，之后继续查询', async () => {
    const fetchStatus = vi.fn()
      .mockRejectedValueOnce({ response: { status: 429, headers: { 'retry-after': '3' } } })
      .mockResolvedValueOnce('SUCCESS')
    const onError = vi.fn()
    const polling = useReservationRequestPolling({ fetchStatus, intervalMs: 1000, onError })
    polling.start('req-rate-limited')

    await vi.advanceTimersByTimeAsync(1000)
    expect(fetchStatus).toHaveBeenCalledTimes(1)
    expect(polling.polling.value).toBe(true)
    expect(onError).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(2999)
    expect(fetchStatus).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(fetchStatus).toHaveBeenCalledTimes(2)
    expect(polling.status.value).toBe('SUCCESS')
    expect(polling.polling.value).toBe(false)
  })

  it('旧请求返回时不会覆盖新一轮轮询状态', async () => {
    let resolveOldRequest
    const fetchStatus = vi.fn((id) => {
      if (id === 'req-old') {
        return new Promise((resolve) => { resolveOldRequest = resolve })
      }
      return Promise.resolve('SUCCESS')
    })
    const polling = useReservationRequestPolling({ fetchStatus, intervalMs: 1000 })

    polling.start('req-old')
    const oldRun = polling.runOnce()
    polling.start('req-new')
    resolveOldRequest('FAILED')
    await oldRun

    expect(polling.requestId.value).toBe('req-new')
    expect(polling.status.value).toBe('QUEUED')
    expect(polling.polling.value).toBe(true)

    await polling.runOnce()
    expect(polling.status.value).toBe('SUCCESS')
    expect(polling.polling.value).toBe(false)
  })

  it('状态到步骤下标的映射', () => {
    expect(requestStepIndex('QUEUED')).toBe(1)
    expect(requestStepIndex('PROCESSING')).toBe(2)
    expect(requestStepIndex('SUCCESS')).toBe(3)
    expect(requestStepIndex('FAILED')).toBe(3)
    expect(requestStepIndex('')).toBe(0)
    expect(REQUEST_STEPS).toEqual(['已提交', '排队中', '处理中', '完成'])
  })
})
