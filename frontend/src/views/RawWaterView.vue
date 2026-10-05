<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'
import { getDutyOperator, setDutyOperator } from '../utils/operator'

const router = useRouter()

const props = defineProps({ role: String })

const rows = ref([])
const error = ref('')
const tip = ref('')
const loading = ref(false)
const statusFilter = ref('')
const dutyOperator = ref(getDutyOperator())
const startingId = ref(null)
const tankRows = ref([])
const filterRows = ref([])
const disposalDialog = ref(false)
const disposalRow = ref(null)
const disposalForm = ref({ assignee: '', tankIds: [], filterIds: [] })

const canCreate = computed(() => props.role === '化验员' || props.role === '管理员')
const canReclassify = computed(() => props.role === '管理员')

const statuses = ['合格', '预警', '超标']
const dialog = ref(false)
const saving = ref(false)
const form = ref(blank())

function blank() {
  return {
    sourceName: '',
    sampleAt: '',
    ph: '',
    turbidityNtu: '',
    chlorineMgL: '',
    waterSample: null
  }
}

async function load() {
  loading.value = true; error.value = ''
  try {
    const data = await api.listRawWaters(statusFilter.value ? { status: statusFilter.value } : {})
    rows.value = data || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function openCreate() {
  form.value = blank(); dialog.value = true; tip.value = ''; error.value = ''
}

async function save() {
  saving.value = true; error.value = ''
  try {
    const d = {
      sourceName: form.value.sourceName,
      sampleAt: form.value.sampleAt ? form.value.sampleAt : null,
      waterSample: {
        ph: Number(form.value.ph),
        turbidityNtu: Number(form.value.turbidityNtu),
        chlorineMgL: Number(form.value.chlorineMgL)
      }
    }
    await api.createRawWater(d)
    tip.value = `原水「${d.sourceName}」已建档`
    dialog.value = false
    await load()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

async function reclassify(row, target) {
  error.value = ''; tip.value = ''
  try {
    await api.reclassifyRawWater(row.id, target)
    tip.value = `${row.sourceName} 已重判为「${target}」`
    await load()
  } catch (e) { error.value = e.message }
}

function rememberOperator() {
  setDutyOperator(dutyOperator.value.trim())
  dutyOperator.value = getDutyOperator()
}

async function startDisposal(row) {
  error.value = ''; tip.value = ''
  rememberOperator()
  try {
    const [tanks, filters] = await Promise.all([api.listTanks({ status: '运行' }), api.listFilters({ status: '运行' })])
    tankRows.value = tanks || []
    filterRows.value = filters || []
    disposalRow.value = row
    disposalForm.value = { assignee: dutyOperator.value, tankIds: [], filterIds: [] }
    disposalDialog.value = true
  } catch (e) { error.value = e.message }
}

async function confirmStartDisposal() {
  error.value = ''; tip.value = ''
  disposalForm.value.assignee = disposalForm.value.assignee.trim()
  if (!disposalForm.value.assignee) {
    error.value = '请先填写发起处置的责任值班人员'
    return
  }
  dutyOperator.value = disposalForm.value.assignee
  rememberOperator()
  startingId.value = disposalRow.value.id
  try {
    const disposal = await api.createDisposal({
      rawWaterId: disposalRow.value.id,
      assignee: disposalForm.value.assignee,
      tankIds: disposalForm.value.tankIds,
      filterIds: disposalForm.value.filterIds
    })
    tip.value = `已接管 ${disposalRow.value.sourceName} 的超标联动处置 #${disposal.id}`
    disposalDialog.value = false
    router.push({ path: '/disposal', query: { id: disposal.id } })
  } catch (e) {
    if (e.status === 409 && e.data?.disposalId) {
      error.value = `${e.message}。页面将打开已接管处置单。`
      disposalDialog.value = false
      setTimeout(() => router.push({
        path: '/disposal',
        query: { id: e.data.disposalId, takeover: 1 }
      }), 700)
    } else {
      error.value = e.message
    }
  } finally { startingId.value = null }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">原水监测 · 取水口水质</span>
        <span class="panel-desc">建档硬约束：pH 6.5~8.5、浊度 &lt; 3 NTU、余氯 0.3~4 mg/L，否则拒绝建档</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">记录数</div><div class="v">{{ rows.length }}</div></div>
        <div class="stat"><div class="k">合格</div><div class="v">{{ rows.filter(r => r.status === '合格').length }}</div></div>
        <div class="stat"><div class="k">预警</div><div class="v">{{ rows.filter(r => r.status === '预警').length }}</div></div>
        <div class="stat"><div class="k">超标</div><div class="v">{{ rows.filter(r => r.status === '超标').length }}</div></div>
      </div>

      <div class="toolbar">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px" @change="load">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
        <el-input
          v-model="dutyOperator"
          placeholder="值班人员姓名（并发接管留痕）"
          style="width: 230px"
          @change="rememberOperator"
        />
        <span class="spacer"></span>
        <el-button type="primary" :disabled="!canCreate" @click="openCreate">
          {{ canCreate ? '新建取样' : '仅化验员/管理员可建档' }}
        </el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="sourceName" label="水源" min-width="130" />
        <el-table-column prop="sampleAt" label="取样时间" min-width="170" />
        <el-table-column label="pH" width="90">
          <template #default="{ row }">{{ row.waterSample?.ph }}</template>
        </el-table-column>
        <el-table-column label="浊度(NTU)" width="100">
          <template #default="{ row }">{{ row.waterSample?.turbidityNtu }}</template>
        </el-table-column>
        <el-table-column label="余氯(mg/L)" width="100">
          <template #default="{ row }">{{ row.waterSample?.chlorineMgL }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === '合格' ? 'success' : row.status === '预警' ? 'warning' : 'danger'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="danger" plain :disabled="!canReclassify" @click="reclassify(row, '超标')">判超标</el-button>
            <el-button size="small" :disabled="!canReclassify" @click="reclassify(row, '合格')">复位合格</el-button>
            <el-button
              size="small"
              type="warning"
              :disabled="row.status !== '超标'"
              :loading="startingId === row.id"
              @click="startDisposal(row)"
            >发起联动处置</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="新建原水取样" width="600px">
      <el-form :model="form" label-width="120px" class="dialog-form">
        <el-form-item label="水源名称" class="full">
          <el-input v-model="form.sourceName" placeholder="如 长江取水泵房 / 一号取水口" />
        </el-form-item>
        <el-form-item label="取样时间" class="full">
          <el-date-picker v-model="form.sampleAt" type="datetime" style="width: 100%"
            value-format="YYYY-MM-DDTHH:mm:ss" placeholder="留空取当前时间" />
        </el-form-item>
        <div class="triple">
          <el-form-item label="pH"><el-input v-model="form.ph" type="number" step="0.01" /></el-form-item>
          <el-form-item label="浊度(NTU)"><el-input v-model="form.turbidityNtu" type="number" step="0.01" /></el-form-item>
          <el-form-item label="余氯(mg/L)"><el-input v-model="form.chlorineMgL" type="number" step="0.01" /></el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">建档</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="disposalDialog" title="发起原水超标联动处置" width="620px">
      <div class="ok-tip">
        发起后将锁定该水源的未解除处置；可先关联当前运行中的沉淀池和滤池，进入执行前仍可调整。
      </div>
      <el-form label-width="120px" class="dialog-form">
        <el-form-item label="责任值班人员" class="full">
          <el-input v-model="disposalForm.assignee" placeholder="并发发起时用于明确接管人" />
        </el-form-item>
        <el-form-item label="沉淀池" class="full">
          <el-select v-model="disposalForm.tankIds" multiple collapse-tags style="width: 100%"
            placeholder="仅可选择运行中的沉淀池">
            <el-option v-for="t in tankRows" :key="t.id"
              :label="`${t.tankCode}（${t.note || '运行'}）`" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="滤池" class="full">
          <el-select v-model="disposalForm.filterIds" multiple collapse-tags style="width: 100%"
            placeholder="仅可选择运行中的滤池">
            <el-option v-for="f in filterRows" :key="f.id"
              :label="`${f.filterCode} / ${f.type}（${f.note || '运行'}）`" :value="f.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="disposalDialog = false">取消</el-button>
        <el-button type="warning" :loading="startingId" @click="confirmStartDisposal">确认发起</el-button>
      </template>
    </el-dialog>
  </div>
</template>
