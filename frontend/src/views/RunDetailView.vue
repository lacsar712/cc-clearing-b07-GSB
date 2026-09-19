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
        <el-descriptions :column="2" border>
          <el-descriptions-item label="Run ID"><span class="mono">{{ detail.run.runId }}</span></el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType(detail.run.status)">{{ statusLabel(detail.run.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="交割日">{{ detail.run.settleDate }}</el-descriptions-item>
          <el-descriptions-item label="币种">{{ detail.run.currency }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatTime(detail.run.createdAt) }}</el-descriptions-item>
          <el-descriptions-item v-if="isActive(detail.run.status)" label="当前阶段">
            <span>{{ stageText(detail.run.stage) }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-else label="ΣnetAmount">{{ detail.sumNetAmount }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.run.failureReason" label="失败原因" :span="2">
            <span class="failure-text">{{ detail.run.failureReason }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <!-- 进行中：与列表/轧差页同一套服务端阶段 -->
        <el-alert
          v-if="isActive(detail.run.status)"
          class="progress-alert"
          type="warning"
          :closable="false"
          show-icon
          :title="`批次进行中 · ${stageShort(detail.run.stage)}`"
          :description="stageText(detail.run.stage)"
          data-testid="detail-running"
        />

        <h3 style="margin:20px 0 10px">净头寸</h3>
        <el-table :data="detail.positions" stripe>
          <el-table-column prop="memberId" label="会员 ID" min-width="220">
            <template #default="{ row }"><span class="mono">{{ row.memberId }}</span></template>
          </el-table-column>
          <el-table-column prop="currency" label="币种" width="90" />
          <el-table-column prop="netAmount" label="净头寸" min-width="160" />
        </el-table>

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
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api/client'
import { useAuthStore } from '../stores/auth'

const POLL_INTERVAL_MS = 1000

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
  return v ? new Date(v).toLocaleString() : '-'
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(load, POLL_INTERVAL_MS)
}

async function load() {
  loading.value = true
  try {
    const { data } = await api.get(`/netting-runs/${route.params.id}`)
    detail.value = data
    if (isActive(data.run.status)) {
      if (!pollTimer) startPolling()
    } else {
      stopPolling()
    }
  } finally {
    loading.value = false
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
onBeforeUnmount(stopPolling)
</script>

<style scoped>
.progress-alert {
  margin-top: 16px;
}
.failure-text {
  color: var(--el-color-danger);
}
</style>
