<template>
  <div class="page">
    <h2 class="page-title">历史净头寸查询</h2>
    <p class="page-desc">按会员汇总已完成批次的净头寸，按交割日、币种分行展示</p>

    <div class="card-panel">
      <div class="toolbar" style="margin-bottom:12px">
        <el-select
          v-model="memberId"
          filterable
          placeholder="选择会员"
          style="width:360px"
          @change="onMemberChange"
        >
          <el-option
            v-for="m in members"
            :key="m.memberId"
            :label="`${m.name}（${m.memberId}）`"
            :value="m.memberId"
          />
        </el-select>
        <el-button :disabled="!memberId" @click="load">刷新</el-button>
      </div>
      <el-alert
        type="info"
        :closable="false"
        title="汇总规则：仅统计状态为 COMPLETED 的批次（已 settle 的批次状态仍为 COMPLETED，计入汇总）；同一会员同一交割日同一币种出现在多个批次时，汇总净头寸 = 各批次净头寸的代数和。展开行可逐批核对，点击 Run ID 进入批次详情。"
      />
    </div>

    <div v-if="memberId" class="card-panel" style="margin-top:16px">
      <div class="toolbar" style="justify-content:space-between;margin-bottom:12px">
        <strong>{{ memberLabel }}</strong>
        <span style="color:var(--muted)">共 {{ rows.length }} 行</span>
      </div>
      <el-table :data="rows" v-loading="loading" stripe empty-text="该会员暂无历史净头寸">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="source-panel">
              <el-table :data="row.sources" size="small">
                <el-table-column label="来源批次 Run ID" min-width="260">
                  <template #default="{ row: s }">
                    <router-link class="mono" :to="`/netting-runs/${s.runId}`">{{ s.runId }}</router-link>
                  </template>
                </el-table-column>
                <el-table-column label="批次状态" width="110">
                  <template #default="{ row: s }">
                    <el-tag type="success">{{ s.runStatus }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="netAmount" label="该批次净头寸" min-width="160" />
                <el-table-column label="批次创建时间" min-width="180">
                  <template #default="{ row: s }">{{ formatTime(s.runCreatedAt) }}</template>
                </el-table-column>
              </el-table>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="settleDate" label="交割日" width="130" />
        <el-table-column prop="currency" label="币种" width="90" />
        <el-table-column prop="totalNetAmount" label="汇总净头寸（正应收/负应付）" min-width="220" />
        <el-table-column prop="runCount" label="来源批次数" width="110" />
      </el-table>
    </div>
    <el-empty v-else description="请选择会员查看历史净头寸" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import api from '../api/client'

const route = useRoute()
const router = useRouter()
const members = ref([])
const memberId = ref('')
const rows = ref([])
const loading = ref(false)

const memberLabel = computed(() => {
  const m = members.value.find((x) => x.memberId === memberId.value)
  return m ? `${m.name}（${m.memberId}）` : memberId.value
})

function formatTime(v) {
  return v ? new Date(v).toLocaleString() : '-'
}

function onMemberChange() {
  router.replace({ name: 'position-history', query: memberId.value ? { memberId: memberId.value } : {} })
  load()
}

async function load() {
  if (!memberId.value) {
    rows.value = []
    return
  }
  loading.value = true
  try {
    const { data } = await api.get(`/members/${memberId.value}/position-history`)
    rows.value = data
  } finally {
    loading.value = false
  }
}

watch(
  () => route.query.memberId,
  (id) => {
    if (id && id !== memberId.value) {
      memberId.value = id
      load()
    }
  }
)

onMounted(async () => {
  const { data } = await api.get('/members')
  members.value = data
  if (route.query.memberId) {
    memberId.value = route.query.memberId
    load()
  }
})
</script>

<style scoped>
.source-panel {
  padding: 8px 24px 16px 48px;
  background: var(--bg);
}
</style>
