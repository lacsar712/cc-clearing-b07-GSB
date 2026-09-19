<template>
  <div class="page">
    <h2 class="page-title">批次详情</h2>
    <p class="page-desc">查看批次状态、参与义务、净头寸，并可确认 settle</p>

    <div class="toolbar">
      <el-button @click="$router.back()">返回</el-button>
      <el-button @click="load">刷新</el-button>
      <el-button
        type="success"
        :disabled="!auth.isOperator || detail?.run?.status !== 'COMPLETED' || alreadySettled"
        :loading="settling"
        @click="settle"
      >确认 Settle</el-button>
    </div>

    <div class="card-panel" v-loading="loading">
      <template v-if="detail">
        <el-steps
          v-if="detail.run.status === 'RUNNING'"
          :active="stageIndex"
          align-center
          style="margin:4px 8px 20px"
        >
          <el-step title="校验" />
          <el-step title="计算" />
          <el-step title="落库" />
        </el-steps>

        <el-alert
          v-if="detail.run.status === 'RUNNING'"
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom:18px"
          :title="`轧差执行中 · ${stageText}`"
        />

        <el-descriptions :column="2" border>
          <el-descriptions-item label="Run ID"><span class="mono">{{ detail.run.runId }}</span></el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType(detail.run.status)">
              {{ detail.run.status }}<template v-if="detail.run.status === 'RUNNING' && detail.run.stage"> · {{ stageLabel(detail.run.stage) }}</template>
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="交割日">{{ detail.run.settleDate }}</el-descriptions-item>
          <el-descriptions-item label="币种">{{ detail.run.currency }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatTime(detail.run.createdAt) }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.run.status === 'COMPLETED'" label="ΣnetAmount">{{ detail.sumNetAmount }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.run.failureReason" label="失败原因" :span="2">
            <span class="failure-reason">{{ detail.run.failureReason }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <template v-if="detail.run.status === 'COMPLETED'">
          <h3 style="margin:20px 0 10px">净头寸</h3>
          <el-table :data="detail.positions" stripe>
            <el-table-column prop="memberId" label="会员 ID" min-width="220">
              <template #default="{ row }"><span class="mono">{{ row.memberId }}</span></template>
            </el-table-column>
            <el-table-column prop="currency" label="币种" width="90" />
            <el-table-column prop="netAmount" label="净头寸" min-width="160" />
          </el-table>
        </template>

        <h3 style="margin:20px 0 10px">参与义务</h3>
        <el-table :data="detail.obligations" stripe>
          <el-table-column prop="obligationId" label="义务 ID" min-width="200">
            <template #default="{ row }"><span class="mono">{{ row.obligationId }}</span></template>
          </el-table-column>
          <el-table-column prop="payerMemberId" label="付款方" min-width="180" />
          <el-table-column prop="payeeMemberId" label="收款方" min-width="180" />
          <el-table-column prop="amount" label="金额" width="140" />
          <el-table-column prop="status" label="状态" width="110" />
        </el-table>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api/client'
import { useAuthStore } from '../stores/auth'

const POLL_INTERVAL_MS = 1000

const STAGE_TEXT = {
  VALIDATING: '校验中：检查待轧差义务与会员状态',
  CALCULATING: '计算中：汇总多边净头寸并校验 Σnet = 0',
  PERSISTING: '落库中：写入净头寸并更新义务状态'
}

const auth = useAuthStore()
const route = useRoute()
const loading = ref(false)
const settling = ref(false)
const detail = ref(null)
let pollTimer = null

const alreadySettled = computed(() =>
  (detail.value?.obligations || []).every((o) => o.status === 'SETTLED') &&
  (detail.value?.obligations || []).length > 0
)

const stageIndex = computed(() => {
  const stage = detail.value?.run?.stage
  if (stage === 'VALIDATING') return 0
  if (stage === 'CALCULATING') return 1
  if (stage === 'PERSISTING') return 2
  return 0
})

const stageText = computed(() => STAGE_TEXT[detail.value?.run?.stage] || '执行中…')

function stageLabel(stage) {
  return { VALIDATING: '校验', CALCULATING: '计算', PERSISTING: '落库' }[stage] || stage
}

function statusTagType(status) {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'RUNNING') return 'warning'
  return 'info'
}

function formatTime(v) {
  return v ? new Date(v).toLocaleString() : '-'
}

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const { data } = await api.get(`/netting-runs/${route.params.id}`)
    detail.value = data
    if (data.run.status === 'RUNNING' || data.run.status === 'CREATED') {
      startPolling()
    } else {
      stopPolling()
    }
  } finally {
    loading.value = false
  }
}

function startPolling() {
  if (pollTimer) return
  pollTimer = setInterval(() => load(true), POLL_INTERVAL_MS)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function settle() {
  settling.value = true
  try {
    await api.post(`/netting-runs/${route.params.id}/settle`)
    ElMessage.success('Settle 完成，义务已 SETTLED')
    await load()
  } finally {
    settling.value = false
  }
}

onMounted(load)
onUnmounted(stopPolling)
</script>

<style scoped>
.failure-reason {
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
