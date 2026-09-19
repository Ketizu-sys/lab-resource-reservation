<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  canCancelReservation,
  cancelReservationAndRefresh,
  getMyReservations,
  getReservation,
} from '../api/reservation'

const router = useRouter()
const loading = ref(false)
const detailLoading = ref(false)
const cancellingId = ref(null)
const reservations = ref([])
const total = ref(0)
const detailVisible = ref(false)
const selectedReservation = ref(null)
const query = reactive({ status: '', page: 0, size: 10 })

const statusLabels = { ACTIVE: '生效中', CANCELLED: '已取消', COMPLETED: '已完成' }
const statusTagTypes = { ACTIVE: 'success', CANCELLED: 'info', COMPLETED: 'warning' }

function displayTime(value) {
  return value ? String(value).replace('T', ' ') : '—'
}

function backendMessage(error, fallback) {
  return error.response?.data?.message || fallback
}

async function loadReservations() {
  loading.value = true
  try {
    const { data } = await getMyReservations(query)
    reservations.value = data.content || []
    total.value = data.totalElements || 0
  } catch (error) {
    if (![401, 403].includes(error.response?.status)) {
      ElMessage.error(backendMessage(error, '预约列表加载失败'))
    }
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 0
  loadReservations()
}

function changePage(page) {
  query.page = page - 1
  loadReservations()
}

async function showDetails(reservation) {
  detailVisible.value = true
  detailLoading.value = true
  selectedReservation.value = reservation
  try {
    const { data } = await getReservation(reservation.id)
    selectedReservation.value = data
  } catch (error) {
    detailVisible.value = false
    if (![401, 403].includes(error.response?.status)) {
      ElMessage.error(backendMessage(error, '预约详情加载失败'))
    }
  } finally {
    detailLoading.value = false
  }
}

async function cancel(reservation) {
  try {
    await ElMessageBox.confirm(
      `确认取消预约 #${reservation.id}（${reservation.resource.name}）？取消后对应时段将重新开放。`,
      '确认取消预约',
      { confirmButtonText: '确认取消', cancelButtonText: '暂不取消', type: 'warning' },
    )
  } catch {
    return
  }

  cancellingId.value = reservation.id
  try {
    await cancelReservationAndRefresh(reservation.id, loadReservations)
    ElMessage.success('取消成功')
    if (selectedReservation.value?.id === reservation.id) {
      const { data } = await getReservation(reservation.id)
      selectedReservation.value = data
    }
  } catch (error) {
    if (![401, 403].includes(error.response?.status)) {
      ElMessage.error(backendMessage(error, '取消预约失败'))
    }
  } finally {
    cancellingId.value = null
  }
}

function viewSlots(reservation) {
  router.push({
    path: '/slots',
    query: {
      resourceId: reservation.resource.id,
      resourceName: reservation.resource.name,
    },
  })
}

onMounted(loadReservations)
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <div>
        <h1>我的预约</h1>
        <p>查看预约状态、详情，并取消仍处于生效中的预约</p>
      </div>
      <el-button type="primary" @click="$router.push('/resources')">继续预约</el-button>
    </div>

    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="query.status" clearable placeholder="预约状态" style="width: 180px" @change="search">
          <el-option label="生效中" value="ACTIVE" />
          <el-option label="已取消" value="CANCELLED" />
          <el-option label="已完成" value="COMPLETED" />
        </el-select>
        <el-button type="primary" @click="search">查询</el-button>
      </div>

      <el-table v-loading="loading" :data="reservations" empty-text="暂无预约记录">
        <el-table-column prop="id" label="预约 ID" width="95" fixed />
        <el-table-column prop="resource.name" label="资源" min-width="170" />
        <el-table-column prop="resource.type" label="类型" width="130" />
        <el-table-column prop="resource.location" label="地点" min-width="150" />
        <el-table-column label="开始时间" min-width="180">
          <template #default="scope">{{ displayTime(scope.row.startTime) }}</template>
        </el-table-column>
        <el-table-column label="结束时间" min-width="180">
          <template #default="scope">{{ displayTime(scope.row.endTime) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="105">
          <template #default="scope">
            <el-tag :type="statusTagTypes[scope.row.status]">{{ statusLabels[scope.row.status] || scope.row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="预约时间" min-width="180">
          <template #default="scope">{{ displayTime(scope.row.reservedAt) }}</template>
        </el-table-column>
        <el-table-column label="取消时间" min-width="180">
          <template #default="scope">{{ displayTime(scope.row.cancelledAt) }}</template>
        </el-table-column>
        <el-table-column label="完成时间" min-width="180">
          <template #default="scope">{{ displayTime(scope.row.completedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="scope">
            <el-button link type="primary" @click="showDetails(scope.row)">查看详情</el-button>
            <el-button link @click="viewSlots(scope.row)">查看时段</el-button>
            <el-button
              v-if="canCancelReservation(scope.row.status)"
              link
              type="danger"
              :loading="cancellingId === scope.row.id"
              @click="cancel(scope.row)"
            >取消预约</el-button>
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

    <el-drawer v-model="detailVisible" title="预约详情" size="min(520px, 92%)">
      <div v-loading="detailLoading">
        <el-descriptions v-if="selectedReservation" :column="1" border>
          <el-descriptions-item label="预约 ID">{{ selectedReservation.id }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagTypes[selectedReservation.status]">
              {{ statusLabels[selectedReservation.status] || selectedReservation.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="资源名称">{{ selectedReservation.resource.name }}</el-descriptions-item>
          <el-descriptions-item label="资源类型">{{ selectedReservation.resource.type }}</el-descriptions-item>
          <el-descriptions-item label="资源地点">{{ selectedReservation.resource.location }}</el-descriptions-item>
          <el-descriptions-item label="Slot ID">{{ selectedReservation.slotId }}</el-descriptions-item>
          <el-descriptions-item label="开始时间">{{ displayTime(selectedReservation.startTime) }}</el-descriptions-item>
          <el-descriptions-item label="结束时间">{{ displayTime(selectedReservation.endTime) }}</el-descriptions-item>
          <el-descriptions-item label="预约时间">{{ displayTime(selectedReservation.reservedAt) }}</el-descriptions-item>
          <el-descriptions-item v-if="selectedReservation.cancelledAt" label="取消时间">
            {{ displayTime(selectedReservation.cancelledAt) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="selectedReservation.completedAt" label="完成时间">
            {{ displayTime(selectedReservation.completedAt) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="selectedReservation.cancelReason" label="取消原因">
            {{ selectedReservation.cancelReason }}
          </el-descriptions-item>
        </el-descriptions>
      </div>
    </el-drawer>
  </section>
</template>
