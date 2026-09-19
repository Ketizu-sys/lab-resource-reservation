<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  RESERVATION_STATUSES,
  cancelAdminReservation,
  emptyAdminReservationQuery,
  getAdminReservations,
} from '../../api/adminReservation'
import { getAdminResources } from '../../api/adminResource'
import { formatLocalDateTime } from '../../api/adminSlot'

const RESERVATION_STATUS_LABELS = {
  ACTIVE: '生效中',
  CANCELLED: '已取消',
  COMPLETED: '已完成',
}
const RESERVATION_STATUS_TAG_TYPES = {
  ACTIVE: 'success',
  CANCELLED: 'info',
  COMPLETED: 'warning',
}

const loading = ref(false)
const reservations = ref([])
const total = ref(0)
const query = reactive(emptyAdminReservationQuery())
const resourceOptions = ref([])

function backendMessage(error, fallback) {
  return error?.response?.data?.message || fallback
}

function isHandledGlobally(error) {
  return [401, 403].includes(error?.response?.status)
}

function buildParams() {
  const params = { page: query.page, size: query.size }
  if (query.userId !== '') params.userId = query.userId
  if (query.resourceId !== '') params.resourceId = query.resourceId
  if (query.status !== '') params.status = query.status
  if (query.start !== '') params.start = formatLocalDateTime(query.start)
  if (query.end !== '') params.end = formatLocalDateTime(query.end)
  return params
}

async function loadReservations() {
  loading.value = true
  try {
    const { data } = await getAdminReservations(buildParams())
    reservations.value = data.content || []
    total.value = data.totalElements || 0
  } catch (error) {
    if (!isHandledGlobally(error)) ElMessage.error(backendMessage(error, '预约列表加载失败'))
  } finally {
    loading.value = false
  }
}

async function loadResourceOptions() {
  try {
    const { data } = await getAdminResources({ page: 0, size: 200 })
    resourceOptions.value = data.content || []
  } catch (error) {
    if (!isHandledGlobally(error)) ElMessage.error(backendMessage(error, '资源选项加载失败'))
  }
}

function search() {
  query.page = 0
  loadReservations()
}

function resetQuery() {
  Object.assign(query, emptyAdminReservationQuery())
  loadReservations()
}

function changePage(page) {
  query.page = page - 1
  loadReservations()
}

const activeCount = computed(
  () => reservations.value.filter((item) => item.reservation?.status === 'ACTIVE').length,
)

async function cancelReservation(row) {
  const id = row.reservation?.id
  try {
    await ElMessageBox.confirm(
      `确认取消预约 #${id}（${row.userEmail}）？取消后时段将重新可预约。`,
      '管理员取消预约',
      { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '放弃' },
    )
  } catch {
    return
  }
  try {
    const response = await cancelAdminReservation(id)
    if (response.status !== 204) {
      ElMessage.error(`取消返回了未预期的状态码：${response.status}`)
      return
    }
    ElMessage.success('预约已取消')
    await loadReservations()
  } catch (error) {
    if (isHandledGlobally(error)) return
    if (error?.response?.status === 404) {
      ElMessage.error('预约不存在，可能已被删除')
    } else {
      ElMessage.error(backendMessage(error, '取消预约失败'))
    }
  }
}

onMounted(async () => {
  await loadResourceOptions()
  await loadReservations()
})
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <div>
        <h1>预约管理</h1>
        <p>按用户、资源、状态与时间区间检索全部预约，必要时代为取消。</p>
      </div>
      <div class="hint">当前页生效中预约：{{ activeCount }}</div>
    </div>

    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model.trim="query.userId" clearable placeholder="用户 ID" style="width: 140px" @keyup.enter="search" />
        <el-select v-model="query.resourceId" clearable filterable placeholder="资源" style="width: 200px">
          <el-option v-for="item in resourceOptions" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-select v-model="query.status" clearable placeholder="状态" style="width: 140px">
          <el-option
            v-for="status in RESERVATION_STATUSES"
            :key="status"
            :label="RESERVATION_STATUS_LABELS[status]"
            :value="status"
          />
        </el-select>
        <el-date-picker
          v-model="query.start"
          type="datetime"
          value-format="YYYY-MM-DDTHH:mm:ss"
          placeholder="起始时间"
          style="width: 200px"
        />
        <el-date-picker
          v-model="query.end"
          type="datetime"
          value-format="YYYY-MM-DDTHH:mm:ss"
          placeholder="结束时间"
          style="width: 200px"
        />
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
      </div>

      <el-table v-loading="loading" :data="reservations" empty-text="暂无预约">
        <el-table-column label="预约 ID" width="90">
          <template #default="scope">{{ scope.row.reservation?.id }}</template>
        </el-table-column>
        <el-table-column label="用户" min-width="180">
          <template #default="scope">
            {{ scope.row.userEmail }}
            <span class="hint">（#{{ scope.row.userId }}）</span>
          </template>
        </el-table-column>
        <el-table-column label="资源" min-width="170">
          <template #default="scope">{{ scope.row.reservation?.resource?.name || '—' }}</template>
        </el-table-column>
        <el-table-column label="时段" min-width="300">
          <template #default="scope">
            {{ scope.row.reservation?.startTime }} ~ {{ scope.row.reservation?.endTime }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="scope">
            <el-tag
              :type="RESERVATION_STATUS_TAG_TYPES[scope.row.reservation?.status] || 'info'"
              disable-transitions
            >
              {{ RESERVATION_STATUS_LABELS[scope.row.reservation?.status] || scope.row.reservation?.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="预约时间" min-width="170">
          <template #default="scope">{{ scope.row.reservation?.reservedAt || '—' }}</template>
        </el-table-column>
        <el-table-column label="取消/完成时间" min-width="170">
          <template #default="scope">
            {{ scope.row.reservation?.cancelledAt || scope.row.reservation?.completedAt || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="scope">
            <el-button
              v-if="scope.row.reservation?.status === 'ACTIVE'"
              type="danger"
              link
              @click="cancelReservation(scope.row)"
            >取消</el-button>
            <span v-else class="hint">—</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination">
        <el-pagination
          background
          layout="prev, pager, next, total"
          :current-page="query.page + 1"
          :page-size="query.size"
          :total="total"
          @current-change="changePage"
        />
      </div>
    </el-card>
  </section>
</template>
