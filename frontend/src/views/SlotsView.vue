<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getSlots } from '../api/slot'
import { createReservation } from '../api/reservation'

const route = useRoute()
const loading = ref(false)
const reservingId = ref(null)
const slots = ref([])
const total = ref(0)
const query = reactive({
  resourceId: route.query.resourceId ? String(route.query.resourceId) : '',
  page: 0,
  size: 10,
})

function displayTime(value) {
  return value ? String(value).replace('T', ' ') : '—'
}

async function loadSlots() {
  loading.value = true
  try {
    const params = { page: query.page, size: query.size }
    if (query.resourceId) params.resourceId = query.resourceId
    const { data } = await getSlots(params)
    slots.value = data.content || []
    total.value = data.totalElements || 0
  } catch (error) {
    if (![401, 403].includes(error.response?.status)) ElMessage.error('时段加载失败')
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 0
  loadSlots()
}

function changePage(page) {
  query.page = page - 1
  loadSlots()
}

async function reserve(slot) {
  try {
    await ElMessageBox.confirm(
      `确认预约 ${slot.resource.name}：${displayTime(slot.startTime)} 至 ${displayTime(slot.endTime)}？`,
      '确认预约',
      { confirmButtonText: '确认', cancelButtonText: '取消', type: 'info' },
    )
  } catch {
    return
  }

  reservingId.value = slot.id
  try {
    await createReservation(slot.id)
    ElMessage.success('预约成功')
    await loadSlots()
  } catch (error) {
    if (![401, 403].includes(error.response?.status)) {
      ElMessage.error(error.response?.status === 409 ? '该时段已被预约' : '预约失败，请刷新后重试')
    }
  } finally {
    reservingId.value = null
  }
}

onMounted(loadSlots)

// 从某个资源返回“全部时段”时组件会被复用，需要同步清除旧的 resourceId 筛选。
watch(() => route.query.resourceId, (resourceId) => {
  query.resourceId = resourceId ? String(resourceId) : ''
  query.page = 0
  loadSlots()
})
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <div>
        <h1>可用时段</h1>
        <p>{{ route.query.resourceName ? `当前资源：${route.query.resourceName}` : '查询并预约实验室资源时段' }}</p>
      </div>
      <el-button @click="$router.push('/resources')">返回资源列表</el-button>
    </div>
    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model.trim="query.resourceId" clearable placeholder="输入 resourceId" style="width: 220px" @keyup.enter="search" />
        <el-button type="primary" @click="search">查询</el-button>
      </div>
      <el-table v-loading="loading" :data="slots" empty-text="暂无可预约时段">
        <el-table-column prop="resource.name" label="资源" min-width="160" />
        <el-table-column prop="resource.type" label="类型" width="130" />
        <el-table-column prop="resource.location" label="地点" min-width="140" />
        <el-table-column label="开始时间" min-width="180"><template #default="scope">{{ displayTime(scope.row.startTime) }}</template></el-table-column>
        <el-table-column label="结束时间" min-width="180"><template #default="scope">{{ displayTime(scope.row.endTime) }}</template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="scope"><el-tag :type="scope.row.available ? 'success' : 'info'">{{ scope.row.available ? '可预约' : '不可预约' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="scope">
            <el-button type="primary" :disabled="!scope.row.available" :loading="reservingId === scope.row.id" @click="reserve(scope.row)">预约</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination">
        <el-pagination background layout="prev, pager, next, total" :current-page="query.page + 1" :page-size="query.size" :total="total" @current-change="changePage" />
      </div>
    </el-card>
  </section>
</template>
