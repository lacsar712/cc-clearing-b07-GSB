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
        <el-button
          type="primary"
          :disabled="!auth.isOperator || hasRunning"
          :loading="submitting"
          @click="execute"
        >执行轧差</el-button>
        <el-button @click="loadRuns">刷新批次</el-button>
      </div>
    </div>

    <!-- 服务端持久化的执行进度：刷新页面 / 重开页面后仍按后端状态恢复 -->
    <div v-if="progress" class="card-panel" style="margin-top:16px">
      <div class="toolbar" style="justify-content:space-between">
        <div>
          <strong>执行进度</strong>
          <span class="mono" style="margin-left:10px">{{ progress.runId }}</span>
        </div>
        <el-button link @click="dismissProgress">关闭</el-button>
      </div>

      <el-steps
        :active="stageIndex"
        :process-status="progress.status === 'FAILED' ? 'error' : 'process'"
        :finish-status="progress.status === 'FAILED' ? 'error' : 'finish'"
        align-center
        style="margin:20px 8px 8px"
      >
        <el-step title="校验" :description="progress.status === 'RUNNING' && stageIndex === 0 ? stageText : ''" />
        <el-step title="计算" :description="progress.status === 'RUNNING' && stageIndex === 1 ? stageText : ''" />
        <el-step title="落库" :description="progress.status === 'RUNNING' && stageIndex === 2 ? stageText : ''" />
      </el-steps>

      <div v-if="progress.status === 'RUNNING'" class="progress-line">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>{{ stageText }}（{{ progress.currency }} · {{ progress.settleDate }}）</span>
      </div>

      <el-alert
        v-else-if="progress.status === 'FAILED'"
        type="error"
        :closable="false"
        show-icon
        style="margin-top:14px"
        :title="`轧差失败（${progress.stage ? stageLabel(progress.stage) + '阶段' : '执行中断'}）`"
      >
        <template #default>
          <div class="failure-reason">{{ progress.failureReason || '未知错误' }}</div>
        </template>
      </el-alert>

      <template v-else-if="progress.status === 'COMPLETED'">
        <el-result icon="success" title="轧差完成，守恒校验通过" sub-title="批次已进入 COMPLETED，可查看详情或前往确认 Settle">
          <template #extra>
            <el-button type="primary" @click="$router.push(`/netting-runs/${progress.runId}`)">查看详情</el-button>
          </template>
        </el-result>
        <el-table v-if="progressDetail" :data="progressDetail.positions" stripe style="margin-top:8px">
          <el-table-column prop="memberId" label="会员 ID" min-width="220">
            <template #default="{ row }">
              <span class="mono">{{ row.memberId }}</span>
              <div>{{ nameOf(row.memberId) }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="currency" label="币种" width="90" />
          <el-table-column prop="netAmount" label="净头寸（正应收/负应付）" min-width="200" />
        </el-table>
        <div v-if="progressDetail" style="margin-top:10px">ΣnetAmount = {{ progressDetail.sumNetAmount }}</div>
      </template>
    </div>

    <div class="card-panel" style="margin-top:16px">
      <strong>历史批次</strong>
      <el-table :data="runs" v-loading="loading" stripe style="margin-top:12px">
        <el-table-column prop="runId" label="Run ID" min-width="220">
          <template #default="{ row }">
            <router-link class="mono" :to="`/netting-runs/${row.runId}`">{{ row.runId }}</router-link>
          </template>
        </el-table-column>
        <el-table-column prop="settleDate" label="交割日" width="120" />
        <el-table-column prop="currency" label="币种" width="90" />
        <el-table-column label="状态" min-width="150">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">
              {{ row.status }}<template v-if="row.status === 'RUNNING' && row.stage"> · {{ stageLabel(row.stage) }}</template>
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="failureReason" label="失败原因" min-width="220" />
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import api from '../api/client'
import { useAuthStore } from '../stores/auth'

const ACTIVE_RUN_KEY = 'netting:activeRunId'
const NOTIFIED_KEY = 'netting:notifiedRunIds'
const POLL_INTERVAL_MS = 1000

const STAGE_TEXT = {
  VALIDATING: '校验中：检查待轧差义务与会员状态',
  CALCULATING: '计算中：汇总多边净头寸并校验 Σnet = 0',
  PERSISTING: '落库中：写入净头寸并更新义务状态'
}

const auth = useAuthStore()
const settleDate = ref(new Date().toISOString().slice(0, 10))
const currency = ref('USD')
const submitting = ref(false)
const loading = ref(false)
const progress = ref(null)
const progressDetail = ref(null)
const runs = ref([])
const memberMap = ref({})

let trackedRunId = null
let pollTimer = null

const stageIndex = computed(() => {
  const stage = progress.value?.stage
  if (stage === 'VALIDATING') return 0
  if (stage === 'CALCULATING') return 1
  if (stage === 'PERSISTING') return 2
  // RUNNING 但阶段未知时停在第一步；终态按结果着色
  return progress.value?.status === 'COMPLETED' ? 3 : 0
})

const stageText = computed(() => STAGE_TEXT[progress.value?.stage] || '执行中…')

const hasRunning = computed(() => progress.value?.status === 'RUNNING')

function stageLabel(stage) {
  return { VALIDATING: '校验', CALCULATING: '计算', PERSISTING: '落库' }[stage] || stage
}

function statusTagType(status) {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'RUNNING') return 'warning'
  return 'info'
}

function nameOf(id) {
  return memberMap.value[id] || ''
}

function markNotified(runId) {
  const seen = new Set(JSON.parse(sessionStorage.getItem(NOTIFIED_KEY) || '[]'))
  if (seen.has(runId)) return false
  seen.add(runId)
  sessionStorage.setItem(NOTIFIED_KEY, JSON.stringify([...seen]))
  return true
}

async function loadRuns() {
  loading.value = true
  try {
    const [r, m] = await Promise.all([api.get('/netting-runs'), api.get('/members')])
    runs.value = r.data
    memberMap.value = Object.fromEntries(m.data.map((x) => [x.memberId, x.name]))
    return r.data
  } finally {
    loading.value = false
  }
}

function track(runId) {
  trackedRunId = runId
  localStorage.setItem(ACTIVE_RUN_KEY, runId)
  startPolling()
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(pollTracked, POLL_INTERVAL_MS)
  pollTracked()
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function pollTracked() {
  if (!trackedRunId) return
  try {
    const { data: detail } = await api.get(`/netting-runs/${trackedRunId}`)
    progress.value = detail.run
    if (detail.run.status === 'RUNNING') {
      // 列表同步进行中阶段，保证刷新前列表也不是“未开始”
      const idx = runs.value.findIndex((r) => r.runId === detail.run.runId)
      if (idx >= 0) runs.value[idx] = detail.run
      return
    }
    // 终态：停止轮询，持久化追踪清除，终态信息保留在进度区
    stopPolling()
    localStorage.removeItem(ACTIVE_RUN_KEY)
    trackedRunId = null
    await loadRuns()

    if (detail.run.status === 'COMPLETED') {
      progressDetail.value = detail
      if (markNotified(detail.run.runId)) {
        ElMessage.success('轧差完成，守恒校验通过')
      }
    } else if (detail.run.status === 'FAILED') {
      // 失败：只展示服务端 failureReason，绝不弹成功提示
      progressDetail.value = null
    }
  } catch (e) {
    // 单次轮询失败不中断，下一轮继续；批次被删除等情况交由列表刷新处理
  }
}

function dismissProgress() {
  progress.value = null
  progressDetail.value = null
  trackedRunId = null
  localStorage.removeItem(ACTIVE_RUN_KEY)
  stopPolling()
}

async function execute() {
  submitting.value = true
  try {
    const { data } = await api.post('/netting-runs', {
      settleDate: settleDate.value,
      currency: currency.value
    })
    progress.value = data
    progressDetail.value = null
    await loadRuns()
    track(data.runId)
  } catch (e) {
    // 409：同交割日/币种已有进行中批次 —— 接上它的进度而不是放假成功
    if (e.response?.status === 409) {
      const list = await loadRuns()
      const existing = list.find(
        (r) =>
          (r.status === 'RUNNING' || r.status === 'CREATED') &&
          r.settleDate === settleDate.value &&
          r.currency === currency.value
      )
      if (existing) {
        progress.value = existing
        progressDetail.value = null
        track(existing.runId)
      }
    }
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  const list = await loadRuns()

  // 恢复顺序：本页面上次追踪的批次 > 任何仍在进行中的最新批次
  let activeId = localStorage.getItem(ACTIVE_RUN_KEY)
  let active = activeId ? list.find((r) => r.runId === activeId) : null
  if (!active) {
    active = list.find((r) => r.status === 'RUNNING' || r.status === 'CREATED')
    activeId = active?.runId || null
  }

  if (active) {
    progress.value = active
    if (active.status === 'RUNNING' || active.status === 'CREATED') {
      track(activeId)
    } else if (active.status === 'COMPLETED') {
      // 刷新前已完成：直接展示结果区，不重复弹成功提示
      pollTrackedSafe(activeId)
      localStorage.removeItem(ACTIVE_RUN_KEY)
    } else {
      localStorage.removeItem(ACTIVE_RUN_KEY)
    }
  }
})

async function pollTrackedSafe(runId) {
  try {
    const { data } = await api.get(`/netting-runs/${runId}`)
    progress.value = data.run
    progressDetail.value = data
  } catch (e) {
    // ignore
  }
}

onUnmounted(stopPolling)
</script>

<style scoped>
.progress-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  color: var(--el-color-warning);
}
.failure-reason {
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
