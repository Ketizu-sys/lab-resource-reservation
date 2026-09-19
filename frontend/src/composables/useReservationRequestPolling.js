import { ref } from 'vue'
import {
  RESERVATION_REQUEST_TIMEOUT_MESSAGE,
  getReservationRequestStatus,
  isTerminalRequestStatus,
} from '../api/reservation'

export const DEFAULT_POLL_INTERVAL_MS = 1000
export const DEFAULT_MAX_ATTEMPTS = 60
export const TIMEOUT_MESSAGE = RESERVATION_REQUEST_TIMEOUT_MESSAGE

/** el-steps 的活动下标：已提交 / 排队中 / 处理中 / 完成。 */
export const REQUEST_STEPS = ['已提交', '排队中', '处理中', '完成']

export function requestStepIndex(status) {
  if (status === 'QUEUED') return 1
  if (status === 'PROCESSING') return 2
  if (status === 'SUCCESS' || status === 'FAILED') return 3
  return 0
}

export const REQUEST_STATUS_LABELS = {
  QUEUED: '排队中',
  PROCESSING: '处理中',
  SUCCESS: '预约成功',
  FAILED: '预约失败',
}

export const REQUEST_STATUS_TAG_TYPES = {
  QUEUED: 'warning',
  PROCESSING: 'primary',
  SUCCESS: 'success',
  FAILED: 'danger',
}

async function defaultFetchStatus(requestId) {
  const { data } = await getReservationRequestStatus(requestId)
  return data?.status || ''
}

/**
 * 异步预约请求的轮询控制器。
 *
 * 不依赖组件生命周期，因此可以在 Node 环境下直接单测；
 * 组件负责在 onUnmounted 中调用 stop() 释放定时器。
 *
 * @param {object} [options]
 * @param {number} [options.intervalMs=1000] 轮询间隔
 * @param {number} [options.maxAttempts=60] 最大轮询次数
 * @param {(requestId: string) => Promise<string>} [options.fetchStatus] 状态查询实现
 * @param {(status: string) => void} [options.onUpdate] 每次拿到新状态时回调
 * @param {(error: unknown) => void} [options.onError] 查询失败时回调
 * @param {() => void} [options.onTimeout] 达到最大次数仍未结束时回调
 */
export function useReservationRequestPolling(options = {}) {
  const {
    intervalMs = DEFAULT_POLL_INTERVAL_MS,
    maxAttempts = DEFAULT_MAX_ATTEMPTS,
    fetchStatus = defaultFetchStatus,
    onUpdate,
    onError,
    onTimeout,
  } = options

  const status = ref('')
  const requestId = ref('')
  const polling = ref(false)
  const timedOut = ref(false)
  const attempts = ref(0)

  let timer = null

  function clearTimer() {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  function stop() {
    clearTimer()
    polling.value = false
  }

  function schedule() {
    clearTimer()
    timer = setTimeout(runOnce, intervalMs)
  }

  async function runOnce() {
    timer = null
    attempts.value += 1

    let next = ''
    try {
      next = await fetchStatus(requestId.value)
    } catch (error) {
      // 401/403/500 已由 Axios 全局拦截器处理，这里只负责停止轮询避免定时器泄漏。
      stop()
      onError?.(error)
      return
    }

    status.value = next || ''
    onUpdate?.(status.value)

    if (isTerminalRequestStatus(status.value)) {
      stop()
      return
    }

    if (attempts.value >= maxAttempts) {
      timedOut.value = true
      stop()
      onTimeout?.()
      return
    }

    schedule()
  }

  /** 开始跟踪一个 requestId；调用前总会先停掉旧任务，保证同一时刻只有一个轮询。 */
  function start(id, initialStatus = 'QUEUED') {
    stop()
    requestId.value = id
    status.value = initialStatus || 'QUEUED'
    attempts.value = 0
    timedOut.value = false
    polling.value = true
    schedule()
    return id
  }

  /** 重新提交前清理旧状态。 */
  function reset() {
    stop()
    requestId.value = ''
    status.value = ''
    attempts.value = 0
    timedOut.value = false
  }

  return { status, requestId, polling, timedOut, attempts, start, stop, reset, runOnce }
}
