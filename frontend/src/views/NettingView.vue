<template>
  <div class="page">
    <h2 class="page-title">轧差执行</h2>
    <p class="page-desc">指定交割日与币种执行单币种多边轧差，校验 Σnet = 0</p>

    <div class="card-panel">
      <div class="toolbar">
        <el-date-picker v-model="settleDate" type="date" value-format="YYYY-MM-DD" placeholder="交割日" />
        <el-select v-model="currency" style="width:120px">
          <el-option label="USD" value="USD" />
          <el-option label="CNY" value="CNY" />
          <el-option label="EUR" value="EUR" />
        </el-select>
        <el-button type="primary" :disabled="!auth.isOperator" :loading="submitting" @click="execute">执行轧差</el-button>
        <el-button @click="loadRuns">刷新批次</el-button>
      </div>
    </div>

    <!-- 服务端阶段进度区：状态来自后端落库，刷新/重开页面自动接续 -->
    <div v-if="current" class="card-panel" style="margin-top:16px" :data-testid="'run-progress-' + current.status">
      <div class="toolbar" style="justify-content:space-between">
        <div>
          <strong>批次执行进度</strong>
          <el-tag
            style="margin-left:8px"
            :type="statusTagType(current.status)"
            disable-transitions
          >{{ statusLabel(current.status) }}</el-tag>
          <span class="mono" style="margin-left:12px;color:var(--muted);font-size:12px">{{ current.runId }}</span>
        </div>
        <el-button link type="primary" @click="$router.push(`/netting-runs/${current.runId}`)">查看详情</el-button>
      </div>

      <!-- 进行中：校验 / 计算 / 落库 三阶段 -->
      <div v-if="isActive(current.status)" class="progress-body">
        <el-steps :active="stepActive" align-center finish-status="success" style="margin-top:18px">
          <el-step title="校验" description="义务与会员检查" />
          <el-step title="计算" description="多边净头寸轧差" />
          <el-step title="落库" description="头寸写入 / 义务更新" />
        </el-steps>
        <div class="stage-line">
          <el-icon class="is-loading"><Loading /></el-icon>
          <span data-testid="stage-text">{{ stageText(current.stage) }}</span>
          <span class="stage-time" v-if="current.stageUpdatedAt">
            阶段更新于 {{ formatTime(current.stageUpdatedAt) }}
          </span>
        </div>
        <div class="stage-hint">执行由服务端分阶段落库驱动，刷新页面或重开轧差页都会自动接续该批次，不会回到未开始。</div>
      </div>

      <!-- 完成态 -->
      <el-alert
        v-else-if="current.status === 'COMPLETED'"
        class="progress-body"
        type="success"
        :closable="false"
        show-icon
        title="轧差完成，守恒校验通过"
        :description="`ΣnetAmount = ${completedDetail?.sumNetAmount ?? 0}`"
        data-testid="run-completed"
      />

      <!-- 失败态：只展示后端 failureReason，绝不弹成功提示 -->
      <el-alert
        v-else-if="current.status === 'FAILED'"
        class="progress-body"
        type="error"
        :closable="false"
        show-icon
        title="轧差失败"
        :description="current.failureReason || '未返回失败原因'"
        data-testid="run-failed"
      />

      <el-table v-if="completedDetail" :data="completedDetail.positions" stripe style="margin-top:12px">
        <el-table-column prop="memberId" label="会员 ID" min-width="220">
          <template #default="{ row }">
            <span class="mono">{{ row.memberId }}</span>
            <div>{{ nameOf(row.memberId) }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="currency" label="币种" width="90" />
        <el-table-column prop="netAmount" label="净头寸（正应收/负应付）" min-width="200" />
      </el-table>
    </div>

    <div class="card-panel" style="margin-top:16px">
      <strong>历史批次</strong>
      <el-table :data="runs" v-loading="loading" stripe style="margin-top:12px" data-testid="runs-table">
        <el-table-column prop="runId" label="Run ID" min-width="220">
          <template #default="{ row }">
            <router-link class="mono" :to="`/netting-runs/${row.runId}`">{{ row.runId }}</router-link>
          </template>
        </el-table-column>
        <el-table-column prop="settleDate" label="交割日" width="120" />
        <el-table-column prop="currency" label="币种" width="90" />
        <el-table-column label="状态" width="170">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" disable-transitions>{{ statusLabel(row.status) }}</el-tag>
            <div v-if="isActive(row.status) && row.stage" class="stage-sub">进行中 · {{ stageShort(row.stage) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="失败原因" min-width="200">
          <template #default="{ row }">
            <span v-if="row.failureReason" class="failure-text">{{ row.failureReason }}</span>
            <span v-else style="color:var(--muted)">-</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import api from '../api/client'
import { useAuthStore } from '../stores/auth'

const POLL_INTERVAL_MS = 1000

const auth = useAuthStore()
const settleDate = ref(new Date().toISOString().slice(0, 10))
const currency = ref('USD')
const submitting = ref(false)
const loading = ref(false)
const current = ref(null)
const completedDetail = ref(null)
const runs = ref([])
const memberMap = ref({})

let pollTimer = null

const stepActive = computed(() => {
  switch (current.value?.stage) {
    case 'CALCULATING':
      return 1
    case 'PERSISTING':
      return 2
    case 'VALIDATING':
    default:
      return 0
  }
})

function nameOf(id) {
  return memberMap.value[id] || ''
}

function isActive(status) {
  return status === 'RUNNING' || status === 'CREATED'
}

function statusTagType(status) {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (isActive(status)) return 'warning'
  return 'info'
}

function statusLabel(status) {
  if (isActive(status)) return '进行中'
  if (status === 'COMPLETED') return 'COMPLETED'
  if (status === 'FAILED') return 'FAILED'
  return status
}

function stageText(stage) {
  switch (stage) {
    case 'VALIDATING':
      return '正在校验待轧差义务与会员状态…'
    case 'CALCULATING':
      return '校验通过，正在计算多边净头寸…'
    case 'PERSISTING':
      return '计算完成，正在写入净头寸并更新义务状态…'
    default:
      return '批次已创建，等待服务端开始执行…'
  }
}

function stageShort(stage) {
  if (stage === 'VALIDATING') return '校验中'
  if (stage === 'CALCULATING') return '计算中'
  if (stage === 'PERSISTING') return '落库中'
  return '等待中'
}

function formatTime(v) {
  return v ? new Date(v).toLocaleTimeString() : '-'
}

async function loadRuns() {
  loading.value = true
  try {
    const [r, m] = await Promise.all([api.get('/netting-runs'), api.get('/members')])
    runs.value = r.data
    memberMap.value = Object.fromEntries(m.data.map((x) => [x.memberId, x.name]))
  } finally {
    loading.value = false
  }
}

async function fetchRun(runId) {
  const { data } = await api.get(`/netting-runs/${runId}`)
  return data.run
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(poll, POLL_INTERVAL_MS)
}

async function poll() {
  if (!current.value) {
    stopPolling()
    return
  }
  try {
    const run = await fetchRun(current.value.runId)
    current.value = run
    await loadRuns()
    if (run.status === 'COMPLETED') {
      stopPolling()
      const { data } = await api.get(`/netting-runs/${run.runId}`)
      completedDetail.value = data
      // 仅在真实由进行中流转到完成时提示成功；FAILED 永远不会走到这里
      ElMessage.success('轧差完成，守恒校验通过')
    } else if (run.status === 'FAILED') {
      stopPolling()
      completedDetail.value = null
    }
  } catch (e) {
    // 单次轮询失败不打断进度条（拦截器已提示网络错误）
  }
}

async function execute() {
  if (submitting.value) return
  submitting.value = true
  completedDetail.value = null
  try {
    const { data } = await api.post('/netting-runs', {
      settleDate: settleDate.value,
      currency: currency.value
    })
    // data 是已落库的 RUNNING 批次，进度全部来自服务端轮询，不存在纯前端 loading
    current.value = data
    await loadRuns()
    startPolling()
  } catch (e) {
    current.value = null
  } finally {
    submitting.value = false
  }
}

/**
 * 刷新 / 重开页面后的接续：列表里只要存在服务端 RUNNING 的批次，
 * 进度区就自动挂载并继续轮询，而不是回到“未开始”。
 */
async function resumeActiveRun() {
  const active = runs.value.find((r) => isActive(r.status))
  if (active) {
    current.value = active
    startPolling()
  }
}

onMounted(async () => {
  await loadRuns()
  await resumeActiveRun()
})

onBeforeUnmount(stopPolling)
</script>

<style scoped>
.progress-body {
  margin-top: 14px;
}
.stage-line {
  margin-top: 18px;
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--el-color-primary);
}
.stage-time {
  margin-left: 8px;
  color: var(--muted);
  font-size: 12px;
}
.stage-hint {
  margin-top: 8px;
  color: var(--muted);
  font-size: 12px;
}
.stage-sub {
  margin-top: 4px;
  color: var(--el-color-warning);
  font-size: 12px;
}
.failure-text {
  color: var(--el-color-danger);
}
</style>
