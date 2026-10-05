<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import api from '../api'
import { getDutyOperator, setDutyOperator } from '../utils/operator'

const route = useRoute()
const router = useRouter()

const rows = ref([])
const detail = ref(null)
const tanks = ref([])
const filters = ref([])
const loading = ref(false)
const detailLoading = ref(false)
const error = ref('')
const takeoverTip = ref('')
const statusFilter = ref('')
const dutyOperator = ref(getDutyOperator())
const action = ref('')
const selectedId = ref(null)
let timer = null

const addDialog = ref(false)
const addType = ref('沉淀池')
const addEquipmentId = ref(null)
const resolveDialog = ref(false)
const resolveLink = ref(null)
const resolveMode = ref('replace')
const resolveForm = ref({ replacementEquipmentId: null, excludedReason: '' })
const dosingDialog = ref(false)
const dosingForm = ref(blankDosing())
const releaseDialog = ref(false)
const releaseConclusion = ref('')
const dosingRequestKey = ref('')

const statuses = ['待处置', '执行中', '已解除']
const chems = ['聚合氯化铝', '次氯酸钠']

const selected = computed(() => detail.value)
const activeStatus = computed(() => detail.value && detail.value.status !== '已解除')
const conflictLinks = computed(() => (detail.value?.equipments || []).filter((x) => x.status === '冲突待处理'))
const pendingLinks = computed(() => (detail.value?.equipments || []).filter((x) => x.status === '待确认'))
const effectiveTanks = computed(() => (detail.value?.equipments || [])
  .filter((x) => x.equipmentType === '沉淀池' && x.status === '已确认'))
const effectiveFilters = computed(() => (detail.value?.equipments || [])
  .filter((x) => x.equipmentType === '滤池' && x.status === '已确认'))
const canProgress = computed(() => effectiveTanks.value.length > 0 && effectiveFilters.value.length
  && conflictLinks.value.length === 0 && pendingLinks.value.length === 0)

const availableTanks = computed(() => {
  const linked = new Set((detail.value?.equipments || [])
    .filter((x) => x.equipmentType === '沉淀池' && ['待确认', '已确认', '冲突待处理'].includes(x.status))
    .map((x) => x.equipmentId))
  return tanks.value.filter((x) => x.status === '运行' && !linked.has(x.id))
})
const availableFilters = computed(() => {
  const linked = new Set((detail.value?.equipments || [])
    .filter((x) => x.equipmentType === '滤池' && ['待确认', '已确认', '冲突待处理'].includes(x.status))
    .map((x) => x.equipmentId))
  return filters.value.filter((x) => x.status === '运行' && !linked.has(x.id))
})

function blankDosing() {
  return { chemName: '聚合氯化铝', doseMgPerL: '', targetTank: '', dosedAt: '', note: '' }
}
function uuid() {
  if (window.crypto?.randomUUID) return window.crypto.randomUUID()
  return `dose-${Date.now()}-${Math.random().toString(16).slice(2)}`
}
function rememberOperator() {
  setDutyOperator(dutyOperator.value.trim())
  dutyOperator.value = getDutyOperator()
}
function requireOperator() {
  rememberOperator()
  if (!dutyOperator.value) {
    error.value = '请先填写值班人员姓名'
    return false
  }
  return true
}

async function loadLists(showLoading = false) {
  if (showLoading) loading.value = true
  try {
    const [list, tankRows, filterRows] = await Promise.all([
      api.listDisposals(statusFilter.value ? { status: statusFilter.value } : {}),
      api.listTanks(),
      api.listFilters()
    ])
    rows.value = list || []
    tanks.value = tankRows || []
    filters.value = filterRows || []
    if (route.query.id) {
      selectedId.value = Number(route.query.id)
    } else if (!selectedId.value && rows.value.length) {
      selectedId.value = rows.value[0].id
    }
    if (selectedId.value) await loadDetail(false)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function loadDetail(showLoading = true) {
  if (!selectedId.value) {
    detail.value = null
    return
  }
  if (showLoading) detailLoading.value = true
  try {
    detail.value = await api.getDisposal(selectedId.value)
    error.value = ''
  } catch (e) {
    error.value = e.message
  } finally {
    detailLoading.value = false
  }
}

async function selectRow(row) {
  selectedId.value = row.id
  takeoverTip.value = ''
  router.replace({ path: '/disposal', query: { id: row.id } })
  await loadDetail()
}

function openAdd(type) {
  addType.value = type
  addEquipmentId.value = null
  addDialog.value = true
}

async function submitAdd() {
  if (!requireOperator()) return
  if (!addEquipmentId.value) {
    error.value = '请选择运行中的设备'
    return
  }
  action.value = 'add'
  try {
    detail.value = await api.addDisposalEquipment(selectedId.value, {
      equipmentType: addType.value,
      equipmentId: addEquipmentId.value,
      operator: dutyOperator.value
    })
    addDialog.value = false
    await loadLists()
  } catch (e) { error.value = e.message } finally { action.value = '' }
}

async function confirmLink(link) {
  if (!requireOperator()) return
  action.value = `confirm-${link.id}`
  try {
    detail.value = await api.confirmDisposalEquipment(detail.value.id, link.id, {
      operator: dutyOperator.value
    })
    await loadLists()
  } catch (e) { error.value = e.message } finally { action.value = '' }
}

function openResolve(link) {
  resolveLink.value = link
  resolveMode.value = 'replace'
  resolveForm.value = { replacementEquipmentId: null, excludedReason: '' }
  resolveDialog.value = true
}

async function submitResolve() {
  if (!requireOperator()) return
  const payload = { operator: dutyOperator.value }
  if (resolveMode.value === 'replace') {
    if (!resolveForm.value.replacementEquipmentId) {
      error.value = '请改选一个运行中的设备'
      return
    }
    payload.replacementEquipmentId = resolveForm.value.replacementEquipmentId
  } else {
    if (!resolveForm.value.excludedReason.trim()) {
      error.value = '请填写排除原因'
      return
    }
    payload.excludedReason = resolveForm.value.excludedReason.trim()
  }
  action.value = `resolve-${resolveLink.value.id}`
  try {
    detail.value = await api.resolveDisposalConflict(detail.value.id, resolveLink.value.id, payload)
    resolveDialog.value = false
    await loadLists()
  } catch (e) { error.value = e.message } finally { action.value = '' }
}

async function refreshConflicts() {
  if (!requireOperator()) return
  action.value = 'refresh'
  try {
    detail.value = await api.refreshDisposalConflicts(detail.value.id, { operator: dutyOperator.value })
  } catch (e) { error.value = e.message } finally { action.value = '' }
}

async function start() {
  if (!requireOperator()) return
  action.value = 'start'
  try {
    detail.value = await api.startDisposal(detail.value.id, { operator: dutyOperator.value })
    await loadLists()
  } catch (e) {
    error.value = e.message
    await loadDetail(false)
  } finally { action.value = '' }
}

function openRelease() {
  releaseConclusion.value = ''
  releaseDialog.value = true
}

async function submitRelease() {
  if (!requireOperator()) return
  if (!releaseConclusion.value.trim()) {
    error.value = '请填写处理结论'
    return
  }
  action.value = 'release'
  try {
    detail.value = await api.releaseDisposal(detail.value.id, {
      conclusion: releaseConclusion.value.trim(),
      operator: dutyOperator.value
    })
    releaseDialog.value = false
    await loadLists()
  } catch (e) {
    error.value = e.message
    await loadDetail(false)
  } finally { action.value = '' }
}

function openDosing() {
  dosingForm.value = blankDosing()
  dosingRequestKey.value = uuid()
  dosingDialog.value = true
}

async function submitDosing() {
  if (!requireOperator()) return
  const f = dosingForm.value
  if (!f.doseMgPerL || Number(f.doseMgPerL) <= 0) {
    error.value = '投加量必须大于 0'
    return
  }
  action.value = 'dosing'
  try {
    const result = await api.registerDisposalDosing(detail.value.id, {
      chemName: f.chemName,
      doseMgPerL: Number(f.doseMgPerL),
      targetTank: f.targetTank,
      dosedAt: f.dosedAt || null,
      note: f.note,
      operator: dutyOperator.value,
      requestId: dosingRequestKey.value
    })
    if (!result.idempotent) error.value = result.message
    dosingDialog.value = !result.idempotent
    await loadDetail(false)
  } catch (e) {
    error.value = e.message
  } finally { action.value = '' }
}

function statusType(s) {
  if (s === '已解除') return 'success'
  if (s === '执行中') return 'warning'
  return 'info'
}
function linkType(s) {
  if (s === '已确认') return 'success'
  if (s === '冲突待处理') return 'danger'
  if (s === '待确认') return 'warning'
  return 'info'
}

watch(() => route.query.id, async (v) => {
  if (v) {
    selectedId.value = Number(v)
    await loadDetail()
  }
})
watch(() => route.query.takeover, (v) => {
  if (v) takeoverTip.value = '该水源的处置已由另一名值班人员成功发起，你看到的是同一条后端记录。'
})

onMounted(async () => {
  if (route.query.takeover) takeoverTip.value = '该水源的处置已由另一名值班人员成功发起，你看到的是同一条后端记录。'
  await loadLists(true)
  timer = window.setInterval(() => loadLists(false), 8000)
})
onUnmounted(() => window.clearInterval(timer))
</script>

<template>
  <div class="disposal-page">
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">原水超标联动处置闭环</span>
        <span class="panel-desc">待处置 → 执行中 → 已解除；同一水源同一时刻只允许一条未解除处置</span>
      </div>
      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="takeoverTip" class="warn-tip">{{ takeoverTip }}</div>

      <div class="toolbar">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 150px" @change="loadLists(true)">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
        <el-input v-model="dutyOperator" placeholder="当前值班人员" style="width: 200px" @change="rememberOperator" />
        <el-button @click="loadLists(true)">刷新</el-button>
        <span class="spacer"></span>
        <el-button :loading="action === 'refresh'" :disabled="!activeStatus" @click="refreshConflicts">重新核对设备冲突</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe size="small" highlight-current-row
        :current-row-key="selectedId" row-key="id" @row-click="selectRow">
        <el-table-column prop="id" label="单号" width="70" />
        <el-table-column prop="sourceName" label="水源" min-width="130" />
        <el-table-column prop="assignee" label="责任人" width="100" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag :type="statusType(row.status)">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="工段" width="130">
          <template #default="{ row }">
            沉 {{ row.effectiveTankCount || 0 }} / 滤 {{ row.effectiveFilterCount || 0 }}
          </template>
        </el-table-column>
        <el-table-column label="冲突" width="70">
          <template #default="{ row }">
            <el-tag :type="row.conflictCount ? 'danger' : 'success'">{{ row.conflictCount || 0 }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="dosingCount" label="加药" width="70" />
        <el-table-column prop="createdAt" label="发起时间" min-width="160" />
      </el-table>
    </div>

    <div v-if="detail" class="panel detail-panel" v-loading="detailLoading">
      <div class="detail-head">
        <div>
          <div class="panel-title">#{{ detail.id }} · {{ detail.sourceName }}</div>
          <div class="panel-desc">原水记录 #{{ detail.rawWaterId }}，取样 {{ detail.rawWater?.sampleAt }}</div>
        </div>
        <el-tag :type="statusType(detail.status)" size="large">{{ detail.status }}</el-tag>
      </div>

      <div class="meta-grid">
        <div><b>当前责任人：</b>{{ detail.assignee }}</div>
        <div><b>发起时间：</b>{{ detail.createdAt }}</div>
        <div><b>执行时间：</b>{{ detail.startedAt || '—' }}</div>
        <div><b>解除时间：</b>{{ detail.releasedAt || '—' }}</div>
        <div><b>解除人：</b>{{ detail.releaseOperator || '—' }}</div>
        <div><b>关联工段：</b>沉淀池 {{ detail.effectiveTankCount }} 个 / 滤池 {{ detail.effectiveFilterCount }} 个</div>
      </div>

      <div class="raw-badges">
        <el-tag type="danger">pH {{ detail.rawWater?.waterSample?.ph }}</el-tag>
        <el-tag type="danger">浊度 {{ detail.rawWater?.waterSample?.turbidityNtu }} NTU</el-tag>
        <el-tag type="danger">余氯 {{ detail.rawWater?.waterSample?.chlorineMgL }} mg/L</el-tag>
      </div>

      <el-alert
        v-if="conflictLinks.length"
        type="error"
        show-icon
        :closable="false"
        :title="`有 ${conflictLinks.length} 个设备冲突未处理，不能进入执行或解除`"
        description="原关联已保留并标红；请改选运行设备，或填写排除原因。"
        class="conflict-alert"
      />

      <div class="section-title">
        关联沉淀池 / 滤池
        <template v-if="activeStatus">
          <el-button size="small" @click="openAdd('沉淀池')">关联沉淀池</el-button>
          <el-button size="small" @click="openAdd('滤池')">关联滤池</el-button>
        </template>
      </div>
      <el-table :data="detail.equipments" border size="small">
        <el-table-column prop="equipmentType" label="类型" width="80" />
        <el-table-column prop="equipmentCode" label="设备" width="110" />
        <el-table-column label="关联状态" width="120">
          <template #default="{ row }"><el-tag :type="linkType(row.status)">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="设备当前状态" width="120">
          <template #default="{ row }">
            <el-tag :type="row.currentStatus === '运行' ? 'success' : 'danger'">{{ row.currentStatus }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="conflictReason" label="冲突原因" min-width="180" />
        <el-table-column prop="excludedReason" label="排除原因" min-width="160" />
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button v-if="activeStatus && row.status === '待确认'" size="small" type="success"
              :loading="action === `confirm-${row.id}`" @click="confirmLink(row)">确认</el-button>
            <el-button v-if="activeStatus && row.status === '冲突待处理'" size="small" type="danger"
              :loading="action === `resolve-${row.id}`" @click="openResolve(row)">处理冲突</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="action-row">
        <el-button type="warning" :disabled="detail.status !== '待处置' || !canProgress"
          :loading="action === 'start'" @click="start">进入执行中</el-button>
        <el-button type="success" :disabled="detail.status !== '执行中' || !canProgress" @click="openRelease">解除处置</el-button>
        <span v-if="!canProgress && activeStatus" class="action-hint">
          需确认至少 1 个沉淀池和 1 个滤池，且无待确认 / 冲突设备
        </span>
      </div>
      <div v-if="detail.conclusion" class="conclusion"><b>处理结论：</b>{{ detail.conclusion }}</div>

      <div class="section-title">
        本次调整加药
        <el-button v-if="activeStatus" size="small" type="primary" @click="openDosing">登记加药</el-button>
      </div>
      <el-table :data="detail.dosings" border size="small">
        <el-table-column prop="chemName" label="药剂" width="120" />
        <el-table-column prop="doseMgPerL" label="投加量(mg/L)" width="120" />
        <el-table-column prop="targetTank" label="目标工段" min-width="130" />
        <el-table-column prop="dosedAt" label="投加时间" min-width="160" />
        <el-table-column prop="note" label="备注" min-width="120" />
      </el-table>

      <div class="section-title">完整流转记录</div>
      <el-timeline>
        <el-timeline-item
          v-for="log in detail.flowLogs"
          :key="log.id"
          :timestamp="`${log.createdAt} · ${log.operator}`"
          :type="log.action.includes('冲突') ? 'danger' : 'primary'"
        >
          <b>{{ log.action }}</b>
          <span v-if="log.fromStatus || log.toStatus">：{{ log.fromStatus || '—' }} → {{ log.toStatus || '—' }}</span>
          <div class="log-detail">{{ log.detail }}</div>
        </el-timeline-item>
      </el-timeline>
    </div>

    <el-dialog v-model="addDialog" title="关联运行中设备" width="460px">
      <el-form label-width="80px">
        <el-form-item label="类型">
          <el-radio-group v-model="addType">
            <el-radio-button label="沉淀池">沉淀池</el-radio-button>
            <el-radio-button label="滤池">滤池</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="设备">
          <el-select v-if="addType === '沉淀池'" v-model="addEquipmentId" style="width: 100%" placeholder="选择运行中的沉淀池">
            <el-option v-for="x in availableTanks" :key="x.id" :label="`${x.tankCode}（${x.note || '运行'}）`" :value="x.id" />
          </el-select>
          <el-select v-else v-model="addEquipmentId" style="width: 100%" placeholder="选择运行中的滤池">
            <el-option v-for="x in availableFilters" :key="x.id"
              :label="`${x.filterCode} / ${x.type}（${x.note || '运行'}）`" :value="x.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addDialog = false">取消</el-button>
        <el-button type="primary" :loading="action === 'add'" @click="submitAdd">关联并确认</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resolveDialog" title="处理设备冲突" width="520px">
      <el-radio-group v-model="resolveMode" class="resolve-mode">
        <el-radio-button label="replace">改选可用设备</el-radio-button>
        <el-radio-button label="exclude">填写排除原因</el-radio-button>
      </el-radio-group>
      <el-form label-width="90px" style="margin-top: 14px">
        <el-form-item v-if="resolveMode === 'replace'" label="改选设备">
          <el-select v-if="resolveLink?.equipmentType === '沉淀池'" v-model="resolveForm.replacementEquipmentId" style="width: 100%">
            <el-option v-for="x in tanks.filter(t => t.status === '运行')" :key="x.id" :label="x.tankCode" :value="x.id" />
          </el-select>
          <el-select v-else v-model="resolveForm.replacementEquipmentId" style="width: 100%">
            <el-option v-for="x in filters.filter(t => t.status === '运行')" :key="x.id" :label="x.filterCode" :value="x.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-else label="排除原因">
          <el-input v-model="resolveForm.excludedReason" type="textarea" :rows="3" maxlength="128" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveDialog = false">取消</el-button>
        <el-button type="primary" :loading="!!action && action.startsWith('resolve')" @click="submitResolve">提交处理</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dosingDialog" title="登记本次调整加药" width="560px">
      <el-form :model="dosingForm" label-width="110px" class="dialog-form">
        <el-form-item label="药剂">
          <el-select v-model="dosingForm.chemName" style="width: 100%">
            <el-option v-for="c in chems" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="投加量(mg/L)"><el-input v-model="dosingForm.doseMgPerL" type="number" step="0.1" /></el-form-item>
        <el-form-item label="目标工段" class="full"><el-input v-model="dosingForm.targetTank" placeholder="如 SED-01 / F-02 / 清水池" /></el-form-item>
        <el-form-item label="投加时间" class="full">
          <el-date-picker v-model="dosingForm.dosedAt" type="datetime" style="width: 100%"
            value-format="YYYY-MM-DDTHH:mm:ss" placeholder="留空取当前时间" />
        </el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="dosingForm.note" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dosingDialog = false">取消</el-button>
        <el-button type="primary" :loading="action === 'dosing'" @click="submitDosing">提交（防重复提交）</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="releaseDialog" title="解除联动处置" width="560px">
      <el-alert type="warning" :closable="false" show-icon
        message="后端会再次核对所有设备冲突与有效工段；不满足条件时保持原状态，不写入半完成数据。" />
      <el-input v-model="releaseConclusion" type="textarea" :rows="4" maxlength="500" show-word-limit
        placeholder="记录处理结论、水质恢复情况和交接事项" style="margin-top: 14px" />
      <template #footer>
        <el-button @click="releaseDialog = false">取消</el-button>
        <el-button type="success" :loading="action === 'release'" @click="submitRelease">确认解除</el-button>
      </template>
    </el-dialog>
  </div>
</template>
