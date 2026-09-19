<template>
  <div class="page">
    <h2 class="page-title">会员历史净头寸</h2>
    <p class="page-desc">按会员查看已完成批次中的历史净头寸，按交割日、币种分行汇总，可逐批追溯核对</p>

    <div class="card-panel">
      <div class="toolbar" style="margin-bottom:0">
        <span style="color:var(--muted)">选择会员</span>
        <el-select
          v-model="selectedMemberId"
          filterable
          placeholder="请选择会员"
          style="width:320px"
          @change="onSelect"
        >
          <el-option
            v-for="m in members"
            :key="m.memberId"
            :label="`${m.name}（${m.memberId}）`"
            :value="m.memberId"
          />
        </el-select>
        <el-button @click="load" :disabled="!selectedMemberId">刷新</el-button>
        <span v-if="history" style="color:var(--muted)">
          共 {{ history.rows.length }} 行汇总，来自 {{ totalSources }} 个已完成批次
        </span>
      </div>
    </div>

    <template v-if="selectedMemberId">
      <el-alert
        class="rule"
        type="info"
        :closable="false"
        show-icon
        title="汇总口径"
        :description="history?.aggregationRule || defaultRule"
      />

      <div class="card-panel">
        <div class="toolbar" style="justify-content:space-between">
          <strong>{{ history?.memberName || selectedMemberId }} 的历史净头寸</strong>
          <span class="hint">点击行首箭头展开来源批次；点 Run ID 可进入批次详情逐笔核对</span>
        </div>
        <el-table
          :data="rows"
          v-loading="loading"
          stripe
          row-key="rowKey"
          :empty-text="'该会员在已完成（含已 settle）批次中暂无净头寸记录'"
        >
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="sources">
                <div class="sources-title">
                  来源批次（{{ row.sources.length }}）— 各行净头寸代数求和应等于本行汇总 {{ formatAmount(row.totalNetAmount) }}
                </div>
                <el-table :data="row.sources" size="small" border>
                  <el-table-column prop="runId" label="Run ID" min-width="220">
                    <template #default="{ row: s }">
                      <router-link class="mono link" :to="`/netting-runs/${s.runId}`">{{ s.runId }}</router-link>
                    </template>
                  </el-table-column>
                  <el-table-column label="批次创建时间" min-width="180">
                    <template #default="{ row: s }">{{ formatTime(s.createdAt) }}</template>
                  </el-table-column>
                  <el-table-column label="批次状态" width="120">
                    <template #default="{ row: s }">
                      <el-tag :type="s.runStatus === 'COMPLETED' ? 'success' : 'info'" size="small">
                        {{ s.runStatus }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="义务结算" width="110">
                    <template #default="{ row: s }">
                      <el-tag :type="s.settled ? 'success' : 'warning'" size="small">
                        {{ s.settled ? '已 SETTLED' : '未 SETTLE' }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="该批次净头寸" min-width="170">
                    <template #default="{ row: s }">
                      <span :class="amountClass(s.netAmount)">{{ formatAmount(s.netAmount) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="操作" width="130">
                    <template #default="{ row: s }">
                      <el-button link type="primary" size="small" @click="openRun(s.runId)">批次详情</el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="settleDate" label="交割日" width="140" sortable :sort-method="sortByDate" />
          <el-table-column prop="currency" label="币种" width="100" />
          <el-table-column label="汇总净头寸（正应收 / 负应付）" min-width="240">
            <template #default="{ row }">
              <span :class="amountClass(row.totalNetAmount)">{{ formatAmount(row.totalNetAmount) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="sourceCount" label="来源批次数" width="120" />
        </el-table>
      </div>
    </template>

    <el-empty v-else description="请先选择会员" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import api from '../api/client'

const route = useRoute()
const router = useRouter()

const defaultRule =
  '仅聚合状态为 COMPLETED 的轧差批次（义务已 SETTLED 的批次其批次状态仍为 COMPLETED，故同样纳入）；' +
  '同一会员在多个批次中的净头寸，按【交割日 + 币种】分组做代数求和（正=应收，负=应付），' +
  '每个来源批次的净头寸在展开行中逐项列出，可与批次详情逐笔核对。'

const members = ref([])
const selectedMemberId = ref('')
const history = ref(null)
const loading = ref(false)

const rows = computed(() =>
  (history.value?.rows || []).map((r) => ({ ...r, rowKey: `${r.settleDate}-${r.currency}` }))
)
const totalSources = computed(() =>
  (history.value?.rows || []).reduce((n, r) => n + r.sources.length, 0)
)

function formatAmount(v) {
  if (v === null || v === undefined || v === '') return '-'
  return Number(v).toLocaleString('en-US', { minimumFractionDigits: 8, maximumFractionDigits: 8 })
}

function amountClass(v) {
  const n = Number(v)
  if (n > 0) return 'amt-pos'
  if (n < 0) return 'amt-neg'
  return 'amt-zero'
}

function formatTime(v) {
  return v ? new Date(v).toLocaleString() : '-'
}

function sortByDate(a, b) {
  return String(a.settleDate).localeCompare(String(b.settleDate))
}

function openRun(runId) {
  router.push(`/netting-runs/${runId}`)
}

async function loadMembers() {
  const { data } = await api.get('/members')
  members.value = data
}

async function load() {
  if (!selectedMemberId.value) {
    history.value = null
    return
  }
  loading.value = true
  try {
    const { data } = await api.get(`/members/${selectedMemberId.value}/position-history`)
    history.value = data
  } finally {
    loading.value = false
  }
}

function onSelect(memberId) {
  router.replace({ name: 'member-positions', query: memberId ? { memberId } : {} })
  load()
}

watch(
  () => route.query.memberId,
  (id) => {
    if (id && id !== selectedMemberId.value) {
      selectedMemberId.value = id
      load()
    }
  }
)

onMounted(async () => {
  await loadMembers()
  const qid = route.query.memberId
  if (qid && members.value.some((m) => m.memberId === qid)) {
    selectedMemberId.value = qid
    await load()
  }
})
</script>

<style scoped>
.rule {
  margin: 16px 0;
}
.hint {
  color: var(--muted);
  font-size: 13px;
}
.sources {
  padding: 8px 16px 16px 48px;
}
.sources-title {
  color: var(--muted);
  font-size: 13px;
  margin-bottom: 8px;
}
.link {
  color: var(--el-color-primary);
}
.amt-pos {
  color: #0f6e56;
  font-weight: 600;
}
.amt-neg {
  color: #c0392b;
  font-weight: 600;
}
.amt-zero {
  color: var(--muted);
}
</style>
