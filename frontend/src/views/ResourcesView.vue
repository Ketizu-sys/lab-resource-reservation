<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getResources } from '../api/resource'

const router = useRouter()
const loading = ref(false)
const resources = ref([])
const total = ref(0)
const query = reactive({ page: 0, size: 10, type: '', status: '', location: '' })

async function loadResources() {
  loading.value = true
  try {
    const params = Object.fromEntries(Object.entries(query).filter(([, value]) => value !== ''))
    const { data } = await getResources(params)
    resources.value = data.content || []
    total.value = data.totalElements || 0
  } catch (error) {
    if (![401, 403].includes(error.response?.status)) ElMessage.error('资源加载失败')
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 0
  loadResources()
}

function changePage(page) {
  query.page = page - 1
  loadResources()
}

function viewSlots(resource) {
  router.push({ path: '/slots', query: { resourceId: resource.id, resourceName: resource.name } })
}

onMounted(loadResources)
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <div><h1>实验室资源</h1><p>选择资源并查看未来可预约时段</p></div>
      <el-button type="primary" @click="$router.push('/slots')">查看全部时段</el-button>
    </div>
    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="query.type" clearable placeholder="资源类型" style="width: 160px">
          <el-option label="实验室" value="LAB" />
          <el-option label="会议室" value="MEETING_ROOM" />
          <el-option label="GPU 计算节点" value="GPU" />
          <el-option label="设备" value="EQUIPMENT" />
          <el-option label="工作站" value="WORKSTATION" />
        </el-select>
        <el-select v-model="query.status" clearable placeholder="资源状态" style="width: 160px">
          <el-option label="可用" value="ACTIVE" />
          <el-option label="停用" value="DISABLED" />
          <el-option label="维护中" value="MAINTENANCE" />
        </el-select>
        <el-input v-model.trim="query.location" clearable placeholder="地点" style="width: 200px" @keyup.enter="search" />
        <el-button type="primary" @click="search">查询</el-button>
      </div>
      <el-table v-loading="loading" :data="resources" empty-text="暂无资源">
        <el-table-column prop="name" label="名称" min-width="160" fixed />
        <el-table-column prop="type" label="类型" width="130" />
        <el-table-column prop="location" label="地点" min-width="140" />
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column prop="capacity" label="容量" width="90" />
        <el-table-column label="描述" min-width="240">
          <template #default="scope"><span class="resource-description">{{ scope.row.description || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="scope">
            <el-button type="primary" link :disabled="scope.row.status !== 'ACTIVE'" @click="viewSlots(scope.row)">查看时段</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination">
        <el-pagination background layout="prev, pager, next, total" :current-page="query.page + 1" :page-size="query.size" :total="total" @current-change="changePage" />
      </div>
    </el-card>
  </section>
</template>
