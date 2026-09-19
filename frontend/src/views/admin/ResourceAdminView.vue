<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  RESOURCE_STATUSES,
  RESOURCE_TYPES,
  createAdminResource,
  disableAdminResource,
  emptyResourceForm,
  getAdminResources,
  updateAdminResource,
} from '../../api/adminResource'

const RESOURCE_TYPE_LABELS = {
  LAB: '实验室',
  MEETING_ROOM: '会议室',
  GPU: 'GPU 计算节点',
  EQUIPMENT: '设备',
  WORKSTATION: '工作站',
}
const RESOURCE_STATUS_LABELS = {
  ACTIVE: '可用',
  DISABLED: '停用',
  MAINTENANCE: '维护中',
}

const loading = ref(false)
const submitting = ref(false)
const resources = ref([])
const total = ref(0)
const query = reactive({ page: 0, size: 10 })

const dialogVisible = ref(false)
const editingId = ref(null)
const form = ref(emptyResourceForm())

function backendMessage(error, fallback) {
  return error?.response?.data?.message || fallback
}

/** 401/403 由 Axios 全局拦截器统一处理，此处不重复提示。 */
function isHandledGlobally(error) {
  return [401, 403].includes(error?.response?.status)
}

async function loadResources() {
  loading.value = true
  try {
    const { data } = await getAdminResources({ page: query.page, size: query.size })
    resources.value = data.content || []
    total.value = data.totalElements || 0
  } catch (error) {
    if (!isHandledGlobally(error)) ElMessage.error(backendMessage(error, '资源列表加载失败'))
  } finally {
    loading.value = false
  }
}

function changePage(page) {
  query.page = page - 1
  loadResources()
}

function openCreate() {
  editingId.value = null
  form.value = emptyResourceForm()
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  form.value = {
    name: row.name || '',
    type: row.type || 'LAB',
    location: row.location || '',
    status: row.status || 'ACTIVE',
    capacity: row.capacity ?? 1,
    description: row.description || '',
  }
  dialogVisible.value = true
}

async function submitForm() {
  submitting.value = true
  try {
    if (editingId.value === null) {
      await createAdminResource(form.value)
      ElMessage.success('资源创建成功')
    } else {
      await updateAdminResource(editingId.value, form.value)
      ElMessage.success('资源更新成功')
    }
    dialogVisible.value = false
    await loadResources()
  } catch (error) {
    if (isHandledGlobally(error)) return
    const status = error?.response?.status
    if (status === 409) {
      ElMessage.warning(backendMessage(error, '资源存在冲突'))
    } else if (status === 404) {
      ElMessage.error('资源不存在，可能已被删除')
    } else {
      ElMessage.error(backendMessage(error, '资源保存失败'))
    }
  } finally {
    submitting.value = false
  }
}

async function disableResource(row) {
  try {
    await ElMessageBox.confirm(
      `停用后资源「${row.name}」将不再出现在预约列表中，历史预约记录保留。确认停用？`,
      '停用资源',
      { type: 'warning', confirmButtonText: '确认停用', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await disableAdminResource(row.id)
    ElMessage.success('资源已停用')
    await loadResources()
  } catch (error) {
    if (isHandledGlobally(error)) return
    if (error?.response?.status === 404) {
      ElMessage.error('资源不存在，可能已被删除')
    } else {
      ElMessage.error(backendMessage(error, '资源停用失败'))
    }
  }
}

onMounted(loadResources)
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <div>
        <h1>资源管理</h1>
        <p>创建、修改与停用实验室资源。停用为软删除，仅变更状态。</p>
      </div>
      <el-button type="primary" @click="openCreate">新建资源</el-button>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="resources" empty-text="暂无资源">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="name" label="名称" min-width="150" />
        <el-table-column label="类型" width="130">
          <template #default="scope">{{ RESOURCE_TYPE_LABELS[scope.row.type] || scope.row.type }}</template>
        </el-table-column>
        <el-table-column prop="location" label="地点" min-width="130" />
        <el-table-column label="状态" width="100">
          <template #default="scope">
            <el-tag :type="scope.row.status === 'ACTIVE' ? 'success' : 'info'" disable-transitions>
              {{ RESOURCE_STATUS_LABELS[scope.row.status] || scope.row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="capacity" label="容量" width="80" />
        <el-table-column label="描述" min-width="200">
          <template #default="scope"><span class="resource-description">{{ scope.row.description || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="scope">
            <el-button type="primary" link @click="openEdit(scope.row)">编辑</el-button>
            <el-button
              type="danger"
              link
              :disabled="scope.row.status === 'DISABLED'"
              @click="disableResource(scope.row)"
            >停用</el-button>
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

    <el-dialog v-model="dialogVisible" :title="editingId === null ? '新建资源' : '编辑资源'" width="520">
      <el-form :model="form" label-width="90px">
        <el-form-item label="名称" required><el-input v-model="form.name" maxlength="120" /></el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="form.type" style="width: 100%">
            <el-option v-for="type in RESOURCE_TYPES" :key="type" :label="RESOURCE_TYPE_LABELS[type]" :value="type" />
          </el-select>
        </el-form-item>
        <el-form-item label="地点" required><el-input v-model="form.location" maxlength="160" /></el-form-item>
        <el-form-item label="状态" required>
          <el-select v-model="form.status" style="width: 100%">
            <el-option v-for="status in RESOURCE_STATUSES" :key="status" :label="RESOURCE_STATUS_LABELS[status]" :value="status" />
          </el-select>
        </el-form-item>
        <el-form-item label="容量" required>
          <el-input-number v-model="form.capacity" :min="1" :max="9999" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="1000" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>
