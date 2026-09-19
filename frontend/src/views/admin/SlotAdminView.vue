<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  buildAdminSlotPayload,
  buildBatchSlotPayload,
  createAdminSlot,
  createAdminSlotBatch,
  deleteAdminSlot,
  getAdminSlots,
  updateAdminSlot,
} from '../../api/adminSlot'
import { getAdminResources } from '../../api/adminResource'

const loading = ref(false)
const submitting = ref(false)
const slots = ref([])
const total = ref(0)
const query = reactive({ page: 0, size: 10 })
const resourceOptions = ref([])

const dialogVisible = ref(false)
const editingId = ref(null)
const form = ref({ resourceId: '', startTime: '', endTime: '' })

const batchVisible = ref(false)
const batchForm = ref({
  resourceId: '',
  startDate: '',
  endDate: '',
  dailyStartTime: '',
  dailyEndTime: '',
  durationMinutes: 60,
})

function backendMessage(error, fallback) {
  return error?.response?.data?.message || fallback
}

function isHandledGlobally(error) {
  return [401, 403].includes(error?.response?.status)
}

function notifyFailure(error, fallback) {
  if (isHandledGlobally(error)) return
  const status = error?.response?.status
  if (status === 404) {
    ElMessage.error('时段不存在，可能已被删除')
  } else {
    ElMessage.error(backendMessage(error, fallback))
  }
}

async function loadSlots() {
  loading.value = true
  try {
    const { data } = await getAdminSlots({ page: query.page, size: query.size })
    slots.value = data.content || []
    total.value = data.totalElements || 0
  } catch (error) {
    if (!isHandledGlobally(error)) ElMessage.error(backendMessage(error, '时段列表加载失败'))
  } finally {
    loading.value = false
  }
}

async function loadResourceOptions() {
  try {
    const { data } = await getAdminResources({ page: 0, size: 200 })
    resourceOptions.value = (data.content || []).filter((item) => item.status !== 'DISABLED')
  } catch (error) {
    if (!isHandledGlobally(error)) ElMessage.error(backendMessage(error, '资源选项加载失败'))
  }
}

function changePage(page) {
  query.page = page - 1
  loadSlots()
}

function openCreate() {
  editingId.value = null
  form.value = { resourceId: '', startTime: '', endTime: '' }
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  form.value = {
    resourceId: row.resourceId,
    startTime: row.startTime ? new Date(row.startTime) : '',
    endTime: row.endTime ? new Date(row.endTime) : '',
  }
  dialogVisible.value = true
}

async function submitForm() {
  submitting.value = true
  try {
    const payload = buildAdminSlotPayload(form.value)
    if (editingId.value === null) {
      await createAdminSlot(payload)
      ElMessage.success('时段创建成功')
    } else {
      await updateAdminSlot(editingId.value, payload)
      ElMessage.success('时段更新成功')
    }
    dialogVisible.value = false
    await loadSlots()
  } catch (error) {
    notifyFailure(error, '时段保存失败')
  } finally {
    submitting.value = false
  }
}

async function submitBatch() {
  submitting.value = true
  try {
    const { data } = await createAdminSlotBatch(buildBatchSlotPayload(batchForm.value))
    ElMessage.success(`批量生成成功，共创建 ${Array.isArray(data) ? data.length : 0} 个时段`)
    batchVisible.value = false
    await loadSlots()
  } catch (error) {
    notifyFailure(error, '批量生成失败')
  } finally {
    submitting.value = false
  }
}

async function removeSlot(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除时段 #${row.id}（${row.startTime} ~ ${row.endTime}）？已预约的时段不允许删除。`,
      '删除时段',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await deleteAdminSlot(row.id)
    ElMessage.success('时段已删除')
    await loadSlots()
  } catch (error) {
    notifyFailure(error, '时段删除失败')
  }
}

onMounted(async () => {
  await loadResourceOptions()
  await loadSlots()
})
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <div>
        <h1>时段管理</h1>
        <p>创建、批量生成、修改与删除可预约时段。</p>
      </div>
      <div class="header-actions">
        <el-button @click="batchVisible = true">批量生成</el-button>
        <el-button type="primary" @click="openCreate">新建时段</el-button>
      </div>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="slots" empty-text="暂无时段">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column label="资源" min-width="160">
          <template #default="scope">{{ scope.row.resourceName || scope.row.resourceId }}</template>
        </el-table-column>
        <el-table-column prop="startTime" label="开始时间" min-width="170" />
        <el-table-column prop="endTime" label="结束时间" min-width="170" />
        <el-table-column label="占用" width="100">
          <template #default="scope">
            <el-tag :type="scope.row.reserved ? 'warning' : 'success'" disable-transitions>
              {{ scope.row.reserved ? '已预约' : '空闲' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="scope">
            <el-button type="primary" link @click="openEdit(scope.row)">编辑</el-button>
            <el-button type="danger" link :disabled="scope.row.reserved" @click="removeSlot(scope.row)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" :title="editingId === null ? '新建时段' : '编辑时段'" width="520">
      <el-form :model="form" label-width="100px">
        <el-form-item label="资源" required>
          <el-select v-model="form.resourceId" filterable placeholder="选择资源" style="width: 100%">
            <el-option
              v-for="item in resourceOptions"
              :key="item.id"
              :label="`${item.name}（${item.location}）`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="开始时间" required>
          <el-date-picker v-model="form.startTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="选择开始时间" style="width: 100%" />
        </el-form-item>
        <el-form-item label="结束时间" required>
          <el-date-picker v-model="form.endTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="选择结束时间" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="batchVisible" title="批量生成时段" width="560">
      <el-form :model="batchForm" label-width="120px">
        <el-form-item label="资源" required>
          <el-select v-model="batchForm.resourceId" filterable placeholder="选择资源" style="width: 100%">
            <el-option
              v-for="item in resourceOptions"
              :key="item.id"
              :label="`${item.name}（${item.location}）`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="起始日期" required>
          <el-date-picker v-model="batchForm.startDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="结束日期" required>
          <el-date-picker v-model="batchForm.endDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="每日开始" required>
          <el-time-picker v-model="batchForm.dailyStartTime" format="HH:mm" value-format="HH:mm:ss" style="width: 100%" />
        </el-form-item>
        <el-form-item label="每日结束" required>
          <el-time-picker v-model="batchForm.dailyEndTime" format="HH:mm" value-format="HH:mm:ss" style="width: 100%" />
        </el-form-item>
        <el-form-item label="单个时长(分钟)" required>
          <el-input-number v-model="batchForm.durationMinutes" :min="1" :max="1440" />
        </el-form-item>
      </el-form>
      <p class="hint">按日期范围 × 每日时间窗切分等长时段；后端会自动跳过已存在的重叠时段。</p>
      <template #footer>
        <el-button @click="batchVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitBatch">生成</el-button>
      </template>
    </el-dialog>
  </section>
</template>
