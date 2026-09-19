<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import {
  DUPLICATE_QUEUE_REQUEST_MESSAGE,
  isQueuedResponse,
  requestAutoReservation,
} from '../api/reservation'
import {
  REQUEST_STATUS_LABELS,
  REQUEST_STATUS_TAG_TYPES,
  REQUEST_STEPS,
  TIMEOUT_MESSAGE,
  requestStepIndex,
  useReservationRequestPolling,
} from '../composables/useReservationRequestPolling'

const router = useRouter()
const authStore = useAuthStore()

const submitting = ref(false)
const submitted = ref(false)
/** sync：后端 HTTP 200 同步完成；async：后端 HTTP 202 进入 Redis 队列。 */
const mode = ref('')
const syncRequestId = ref('')
const failureMessage = ref('')

const {
  status: requestStatus,
  requestId: trackedRequestId,
  polling: isPolling,
  timedOut: pollTimedOut,
  attempts: pollAttempts,
  start: startPolling,
  stop: stopPolling,
  reset: resetPolling,
} = useReservationRequestPolling({
  onUpdate: (next) => {
    if (next === 'FAILED') {
      failureMessage.value = '队列处理失败，可能是当前没有可用时段，或该时段已被其他请求占用'
    }
  },
  onTimeout: () => ElMessage.warning(TIMEOUT_MESSAGE),
  onError: handlePollError,
})

const finalStatus = computed(() => (mode.value === 'sync' ? 'SUCCESS' : requestStatus.value))
const isFinished = computed(() => finalStatus.value === 'SUCCESS' || finalStatus.value === 'FAILED')

const activeStep = computed(() => {
  if (!submitted.value) return 0
  if (mode.value === 'sync') return REQUEST_STEPS.length - 1
  return requestStepIndex(requestStatus.value)
})

const statusLabel = computed(() => {
  if (!submitted.value) return '未提交'
  return REQUEST_STATUS_LABELS[finalStatus.value] || finalStatus.value || '处理中'
})

const statusTagType = computed(() => REQUEST_STATUS_TAG_TYPES[finalStatus.value] || 'info')

const currentRequestId = computed(() =>
  mode.value === 'sync' ? syncRequestId.value : trackedRequestId.value,
)

function backendMessage(error, fallback) {
  return error?.response?.data?.message || fallback
}

function resetState() {
  resetPolling()
  submitted.value = false
  mode.value = ''
  syncRequestId.value = ''
  failureMessage.value = ''
}

function handleSubmitError(error) {
  const status = error?.response?.status
  // 401/403 由 Axios 全局拦截器统一处理（清登录态或提示无权限），此处不再重复提示。
  if (status === 401 || status === 403) return
  if (status === 409) {
    ElMessage.warning(DUPLICATE_QUEUE_REQUEST_MESSAGE)
    return
  }
  ElMessage.error(backendMessage(error, '自动预约请求失败'))
}

function handlePollError(error) {
  const status = error?.response?.status
  if (status === 401 || status === 403) return
  if (status === 404) {
    ElMessage.warning('未查询到该请求的状态，请稍后在“我的预约”中确认最终结果')
    return
  }
  ElMessage.error(backendMessage(error, '队列状态查询失败'))
}

async function submit() {
  if (submitting.value || isPolling.value) return
  resetState()
  submitting.value = true
  try {
    const response = await requestAutoReservation()
    const { data } = response
    // 同步/异步只能依据 HTTP 状态码判断。
    if (isQueuedResponse(response.status)) {
      mode.value = 'async'
      submitted.value = true
      startPolling(data?.requestId, data?.status || 'QUEUED')
      ElMessage.info('请求已进入 Redis 队列，正在等待后台处理')
    } else {
      mode.value = 'sync'
      submitted.value = true
      syncRequestId.value = data?.requestId || ''
      ElMessage.success('预约成功，已同步写入数据库')
    }
  } catch (error) {
    handleSubmitError(error)
  } finally {
    submitting.value = false
  }
}

function goToMyReservations() {
  router.push('/my-reservations')
}

// 退出登录后不得继续后台轮询。
watch(
  () => authStore.isAuthenticated,
  (authenticated) => {
    if (!authenticated) stopPolling()
  },
)

// 组件卸载后释放定时器。
onUnmounted(stopPolling)
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <div>
        <h1>自动预约</h1>
        <p>一键预约最近的空闲时段；高并发时后端会转入 Redis 异步队列处理</p>
      </div>
      <el-button @click="goToMyReservations">查看我的预约</el-button>
    </div>

    <el-card shadow="never">
      <div class="page-heading">
        <div>
          <h1 style="font-size: 18px; margin: 0 0 6px">发起请求</h1>
          <p class="hint">
            后端根据当前实例并发压力自动选择处理方式：低负载同步返回 HTTP 200，
            高负载写入 Redis 队列并返回 HTTP 202。
          </p>
        </div>
        <el-button type="primary" :loading="submitting" :disabled="isPolling" @click="submit">
          {{ isPolling ? '处理中…' : '预约最近空闲时段' }}
        </el-button>
      </div>

      <el-steps
        v-if="submitted"
        :active="activeStep"
        :finish-status="finalStatus === 'FAILED' ? 'error' : 'success'"
        align-center
        style="margin: 24px 0 12px"
      >
        <el-step v-for="step in REQUEST_STEPS" :key="step" :title="step" />
      </el-steps>

      <div v-if="submitted && mode === 'async'" class="toolbar" style="margin-bottom: 8px">
        <span class="hint">请求 ID：{{ currentRequestId || '—' }}</span>
        <el-tag :type="statusTagType">{{ statusLabel }}</el-tag>
        <span v-if="isPolling" class="hint">已查询 {{ pollAttempts }} 次</span>
      </div>

      <el-alert
        v-if="pollTimedOut"
        type="warning"
        show-icon
        :closable="false"
        :title="TIMEOUT_MESSAGE"
        style="margin-top: 12px"
      />

      <el-result
        v-if="isFinished"
        :icon="finalStatus === 'SUCCESS' ? 'success' : 'error'"
        :title="finalStatus === 'SUCCESS' ? '预约成功' : '预约失败'"
        :sub-title="
          finalStatus === 'SUCCESS'
            ? mode === 'sync'
              ? `同步处理完成（HTTP 200），请求 ID：${currentRequestId}`
              : `异步队列处理完成，请求 ID：${currentRequestId}`
            : failureMessage || '后端未能完成本次预约，可能是当前没有可用时段'
        "
      >
        <template #extra>
          <el-button v-if="finalStatus === 'SUCCESS'" type="primary" @click="goToMyReservations">
            查看我的预约
          </el-button>
          <el-button v-else type="primary" @click="resetState">重新提交</el-button>
          <el-button @click="goToMyReservations">我的预约</el-button>
        </template>
      </el-result>

      <el-empty v-if="!submitted" description="点击上方按钮发起自动预约请求" />
    </el-card>
  </section>
</template>
