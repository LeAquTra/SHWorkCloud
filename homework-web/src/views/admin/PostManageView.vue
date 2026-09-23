<template>
  <div class="post-manage">
    <header class="head">
      <div>
        <h2>社区管理</h2>
        <p class="sc-muted">
          按 ID 或关键字定位站内任何一条内容，支持下架、拒绝与彻底删除。
          <strong class="warn">彻底删除不可恢复</strong>，请先确认再操作。
        </p>
      </div>
      <el-button :loading="loading" @click="reload()">
        <el-icon><Refresh /></el-icon>
        <span>刷新</span>
      </el-button>
    </header>

    <!-- 统计卡片：三个状态各多少条。刻意不受筛选影响（管理员要看的是站上的总量） -->
    <div class="stats">
      <div class="stat" :class="{ active: query.status === undefined }" @click="filterStatus(undefined)">
        <span class="sc-muted">全部内容</span>
        <strong>{{ statsTotal }}</strong>
      </div>
      <div class="stat" :class="{ active: query.status === 0 }" @click="filterStatus(0)">
        <span class="sc-muted">待审核</span>
        <strong class="warn">{{ counts.pending }}</strong>
      </div>
      <div class="stat" :class="{ active: query.status === 1 }" @click="filterStatus(1)">
        <span class="sc-muted">已通过（广场可见）</span>
        <strong class="ok">{{ counts.approved }}</strong>
      </div>
      <div class="stat" :class="{ active: query.status === 2 }" @click="filterStatus(2)">
        <span class="sc-muted">已拒绝</span>
        <strong class="danger">{{ counts.rejected }}</strong>
      </div>
    </div>

    <!-- 筛选：ID 定位是这一页的核心诉求（举报时手上往往只有一个帖子 ID） -->
    <el-card shadow="never" class="filters-card">
      <div class="filters">
        <el-input
          v-model="query.postId"
          placeholder="帖子 ID（精确）"
          clearable
          class="w130"
          @keyup.enter="reload(1)"
        />
        <el-input
          v-model="query.authorId"
          placeholder="作者用户 ID"
          clearable
          class="w140"
          @keyup.enter="reload(1)"
        />
        <el-input
          v-model="query.keyword"
          placeholder="正文关键字"
          clearable
          class="w200"
          @keyup.enter="reload(1)"
        />
        <el-select v-model="query.status" placeholder="状态" clearable class="w140">
          <el-option label="待审核" :value="0" />
          <el-option label="已通过" :value="1" />
          <el-option label="已拒绝" :value="2" />
        </el-select>
        <el-button type="primary" @click="reload(1)">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
      </div>
    </el-card>

    <el-alert
      v-if="lastError"
      type="error"
      :closable="false"
      show-icon
      title="加载失败"
      :description="`${lastError}　—— 可点右上角「刷新」重试；若持续失败请把这句话发给管理员。`"
    />

    <el-card shadow="never" class="table-card">
      <template #header>
        <div class="table-head">
          <span class="sc-muted">
            共 <b>{{ total }}</b> 条
            <template v-if="idsFilter.length">
              （按粘贴的 {{ idsFilter.length }} 个 ID 筛选中）
            </template>
          </span>
          <div class="spacer" />
          <span class="sc-muted selected-hint">已选 {{ selected.length }} 条</span>
          <!--
            批量 ID 选择：这是"选择 id"最直接的落地方式。
            举报清单、工单里给的都是一串 ID，逐个搜索太慢。
          -->
          <el-button @click="openIdPicker">
            <el-icon><Select /></el-icon>
            <span>按 ID 选择</span>
          </el-button>
          <el-button :disabled="selected.length === 0" @click="batchUnpublish">批量下架</el-button>
          <el-button :disabled="selected.length === 0" @click="openBatchReject">批量拒绝</el-button>
          <el-button
            type="danger"
            plain
            :disabled="selected.length === 0"
            :loading="working"
            @click="batchDelete"
          >
            彻底删除
          </el-button>
        </div>
      </template>

      <el-table
        v-loading="loading"
        :data="records"
        row-key="id"
        height="100%"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="46" />
        <el-table-column label="ID" width="90">
          <template #default="{ row }">
            <span class="sc-tabular">{{ row.id }}</span>
          </template>
        </el-table-column>
        <el-table-column label="作者" width="170">
          <template #default="{ row }">
            <div class="author-cell" @click="openAuthor(row)">
              <UserAvatar :card="row.author" :size="26" />
              <div class="author-text">
                <strong>{{ row.author.displayName }}</strong>
                <small class="sc-muted">{{ row.author.username }}</small>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="内容" min-width="280">
          <template #default="{ row }">
            <!-- 管理要看原文：与审核页同一取舍，不渲染链接 -->
            <div class="content-preview">{{ row.content }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" effect="light" :type="statusType(row.status)">
              {{ POST_STATUS_LABELS[row.status] || '未知' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="160">
          <template #default="{ row }">
            <span class="sc-tabular sc-subtle">{{ formatTime(row.createTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-dropdown
              trigger="click"
              placement="bottom-end"
              @command="(cmd: string) => onRowCommand(cmd, row)"
            >
              <el-button link type="primary">
                操作
                <el-icon><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="view">
                    <el-icon><View /></el-icon>
                    <span>查看详情</span>
                  </el-dropdown-item>
                  <el-dropdown-item v-if="row.status === POST_STATUS_PENDING" command="approve">
                    <el-icon><Check /></el-icon>
                    <span>通过</span>
                  </el-dropdown-item>
                  <el-dropdown-item v-if="row.status === POST_STATUS_PENDING" command="reject">
                    <el-icon><Close /></el-icon>
                    <span>拒绝</span>
                  </el-dropdown-item>
                  <el-dropdown-item v-if="row.status !== POST_STATUS_PENDING" command="unpublish">
                    <el-icon><Download /></el-icon>
                    <span>下架（回待审）</span>
                  </el-dropdown-item>
                  <el-dropdown-item command="delete" divided>
                    <el-icon><Delete /></el-icon>
                    <span class="danger-text">彻底删除</span>
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty :image-size="80" :description="emptyText" />
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        :page-size="query.size"
        :total="total"
        layout="total, prev, pager, next"
        class="pager"
        @current-change="() => reload()"
      />
    </el-card>

    <!-- 按 ID 选择：粘贴一串 ID，只把这些内容捞出来 -->
    <el-dialog v-model="idPickerVisible" title="按 ID 选择内容" width="520px">
      <p class="sc-muted dialog-tip">
        粘贴一个或多个帖子 ID，用<b>逗号、空格或换行</b>分隔（最多 {{ MAX_IDS }} 个）。
        提交后列表只显示这些内容，便于逐条核对或批量处理。
      </p>
      <el-input
        v-model="idPickerText"
        type="textarea"
        :rows="4"
        resize="none"
        placeholder="例如：12, 15, 18"
      />
      <p v-if="idPickerError" class="dialog-error">{{ idPickerError }}</p>
      <template #footer>
        <el-button @click="idPickerVisible = false">取消</el-button>
        <el-button @click="clearIdFilter">清除筛选</el-button>
        <el-button type="primary" @click="applyIdPicker">按这些 ID 查询</el-button>
      </template>
    </el-dialog>

    <!-- 拒绝：理由会被作者看到 -->
    <el-dialog v-model="rejectVisible" :title="rejectTarget ? '拒绝这条帖子' : '批量拒绝'" width="480px">
      <p class="sc-muted dialog-tip">
        理由会显示给作者（建议填写，但不强制）。留空也可以直接拒绝。
        <template v-if="!rejectTarget">本次将处理 <b>{{ selected.length }}</b> 条。</template>
      </p>
      <el-input
        v-model="rejectReason"
        type="textarea"
        :rows="3"
        resize="none"
        :maxlength="200"
        show-word-limit
        placeholder="例如：含不良信息 / 与学习无关的广告"
      />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="working" @click="confirmReject">确认拒绝</el-button>
      </template>
    </el-dialog>

    <!-- 详情：只读，避免"顺手改用户内容"这种无法追溯的操作 -->
    <el-dialog v-model="detailVisible" title="内容详情" width="560px">
      <div v-if="detailPost" class="detail">
        <div class="detail-row">
          <span class="sc-muted">ID</span>
          <span class="sc-tabular">{{ detailPost.id }}</span>
        </div>
        <div class="detail-row">
          <span class="sc-muted">作者</span>
          <span>{{ detailPost.author.displayName }}（{{ detailPost.author.username }}）</span>
        </div>
        <div class="detail-row">
          <span class="sc-muted">状态</span>
          <el-tag size="small" effect="light" :type="statusType(detailPost.status)">
            {{ POST_STATUS_LABELS[detailPost.status] || '未知' }}
          </el-tag>
        </div>
        <div class="detail-row">
          <span class="sc-muted">提交时间</span>
          <span class="sc-tabular">{{ formatTime(detailPost.createTime) }}</span>
        </div>
        <div class="detail-row" v-if="detailPost.reviewTime">
          <span class="sc-muted">审核时间</span>
          <span class="sc-tabular">{{ formatTime(detailPost.reviewTime) }}</span>
        </div>
        <div class="detail-row" v-if="detailPost.rejectReason">
          <span class="sc-muted">拒绝理由</span>
          <span>{{ detailPost.rejectReason }}</span>
        </div>
        <div class="detail-content">{{ detailPost.content }}</div>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button v-if="detailPost" type="primary" @click="openAuthor(detailPost)">
          查看作者主页
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Check, Close, Delete, Download, Refresh, Select, View } from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'
import { adminApi } from '@/api'
import { ApiError } from '@/api/http'
import {
  POST_STATUS_APPROVED,
  POST_STATUS_LABELS,
  POST_STATUS_PENDING,
  POST_STATUS_REJECTED,
  type PostBatchAction,
  type PostStatusCountsVO,
  type PostVO,
} from '@/types/api'
import { usePostReviewStore } from '@/stores/postReview'
import { formatTime } from '@/utils/format'

/**
 * 社区管理（**管理员 / 超管**；教师只能审、不能管 —— 与后端 `@SaCheckRole` 及
 * 路由 `meta.roles = ADMIN_ROLES` 一致）。
 *
 * <p><b>和「社区审核」的区别</b>：审核页只处理待审队列（通过/拒绝），
 * 管理页面向"站上已有的内容"，可以做三件审核页做不到的事：
 * <ol>
 *   <li><b>按 ID 定位</b> —— 收到举报时手上通常只有一个帖子 ID 或一句原文，
 *       没有这个入口就只能一页页翻；</li>
 *   <li><b>下架</b>（退回待审）—— 已通过的内容立刻从广场消失，但内容还在，
 *       重新审一遍就能恢复。这是"有问题但不确定要不要留"的默认动作；</li>
 *   <li><b>彻底删除</b> —— 物理删除且不可恢复，所以必须二次确认。</li>
 * </ol>
 *
 * <p><b>刻意不做"编辑用户内容"</b>：改完就分不清哪句话是作者写的、哪句是管理员写的，
 * 而社区的价值恰恰在于"作者是谁说了什么"可追溯。处理内容只有下架/拒绝/删除三种出口，
 * 理由通过"拒绝理由"告诉作者。
 */
const MAX_IDS = 200

const router = useRouter()
const postReview = usePostReviewStore()

const records = ref<PostVO[]>([])
const total = ref(0)
const loading = ref(false)
const working = ref(false)
const lastError = ref('')

/** 全局状态分布（不受筛选影响） */
const counts = ref<PostStatusCountsVO>({ pending: 0, approved: 0, rejected: 0 })
const statsTotal = computed(() => counts.value.pending + counts.value.approved + counts.value.rejected)

const query = reactive<{
  postId: string
  authorId: string
  keyword: string
  status: number | undefined
  page: number
  size: number
}>({
  postId: '',
  authorId: '',
  keyword: '',
  status: undefined,
  page: 1,
  size: 20,
})

/** "按 ID 选择"提交后的 ID 列表（空表示不按 ID 筛） */
const idsFilter = ref<number[]>([])

const selected = ref<PostVO[]>([])
/** 拒绝对话框：rejectTarget 为空表示这是批量拒绝 */
const rejectVisible = ref(false)
const rejectReason = ref('')
const rejectTarget = ref<PostVO | null>(null)

const detailVisible = ref(false)
const detailPost = ref<PostVO | null>(null)

const idPickerVisible = ref(false)
const idPickerText = ref('')
const idPickerError = ref('')

const emptyText = computed(() => {
  if (idsFilter.value.length) {
    return '这些 ID 里没有符合条件的内容（可能已被作者删除，或与其它筛选条件冲突）'
  }
  return '没有符合条件的内容'
})

function statusType(status: number) {
  if (status === POST_STATUS_APPROVED) return 'success'
  if (status === POST_STATUS_REJECTED) return 'danger'
  return 'info'
}

/** 把字符串输入解析成正整数；非法返回 undefined（不筛），而不是 NaN */
function toPositiveInt(raw: string): number | undefined {
  const trimmed = raw.trim()
  if (!trimmed) {
    return undefined
  }
  const value = Number(trimmed)
  // ⚠️ 这里必须判整数：NaN / 小数 / 负数一旦进了查询串，服务端 Long 绑不上就是 40000
  return Number.isInteger(value) && value > 0 ? value : undefined
}

/** 把异常转成一句能直接看出原因的话：业务码 + 后端 message */
function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    return `[${error.code}] ${error.message}`
  }
  if (error instanceof Error) {
    return error.message
  }
  return '未知错误'
}

async function reload(toPage?: number) {
  // ⚠️ 只接受数字：模板里 `@click="reload()"` 是显式调用，但把这个函数交给
  // 别的组件当回调时，Vue/组件库可能把事件对象或其它值塞进来（本项目真实踩过：
  // 审核页 `@click="reload"` 让 page 变成了 PointerEvent）。判类型是最省事的保险。
  if (typeof toPage === 'number' && Number.isFinite(toPage)) {
    query.page = toPage
  }
  loading.value = true
  try {
    const result = await adminApi.postQueue({
      status: query.status,
      postId: toPositiveInt(query.postId),
      authorId: toPositiveInt(query.authorId),
      ids: idsFilter.value.length ? idsFilter.value : undefined,
      keyword: query.keyword.trim() || undefined,
      page: query.page,
      size: query.size,
    })
    records.value = result.records
    total.value = result.total
    counts.value = result.statusCounts ?? counts.value
    // 与侧栏红点同源：这次请求顺带带回了全局待审数，不必再单独拉一次
    postReview.set(result.pendingTotal)
    lastError.value = ''
  } catch (error) {
    const detail = describeError(error)
    lastError.value = detail
    ElMessage.error(`加载失败：${detail}`)
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 统计卡片点击：等价于把状态下拉改成对应值 */
function filterStatus(status: number | undefined) {
  query.status = status
  void reload(1)
}

function resetQuery() {
  query.postId = ''
  query.authorId = ''
  query.keyword = ''
  query.status = undefined
  idsFilter.value = []
  void reload(1)
}

function onSelectionChange(value: PostVO[]) {
  selected.value = value
}

// ---------------------------------------------------------------- 按 ID 选择

function openIdPicker() {
  idPickerText.value = idsFilter.value.join(', ')
  idPickerError.value = ''
  idPickerVisible.value = true
}

/**
 * 解析粘贴的 ID：逗号 / 空格 / 换行 / 顿号都能当分隔符。
 * <p>刻意"遇到非法值就报错"而不是静默丢弃：管理员粘了一串 ID 却看到
 * 少了几个，比看到一句"第 3 个不是数字"更让人不敢动手。
 */
function parseIds(raw: string): { ids: number[]; error: string } {
  const parts = raw
    .split(/[\s,，、;；]+/)
    .map((s) => s.trim())
    .filter(Boolean)
  const ids: number[] = []
  for (const part of parts) {
    const value = Number(part)
    if (!Number.isInteger(value) || value <= 0) {
      return { ids: [], error: `「${part}」不是合法的帖子 ID（应为正整数）` }
    }
    if (!ids.includes(value)) {
      ids.push(value)
    }
  }
  if (ids.length > MAX_IDS) {
    return { ids: [], error: `一次最多 ${MAX_IDS} 个 ID，当前 ${ids.length} 个` }
  }
  return { ids, error: '' }
}

function applyIdPicker() {
  const { ids, error } = parseIds(idPickerText.value)
  if (error) {
    idPickerError.value = error
    return
  }
  idsFilter.value = ids
  idPickerVisible.value = false
  void reload(1)
}

function clearIdFilter() {
  idsFilter.value = []
  idPickerText.value = ''
  idPickerError.value = ''
  idPickerVisible.value = false
  void reload(1)
}

// ---------------------------------------------------------------- 行内操作

function onRowCommand(command: string, row: PostVO) {
  switch (command) {
    case 'view':
      detailPost.value = row
      detailVisible.value = true
      break
    case 'approve':
      void approve(row)
      break
    case 'reject':
      rejectTarget.value = row
      rejectReason.value = ''
      rejectVisible.value = true
      break
    case 'unpublish':
      void unpublishOne(row)
      break
    case 'delete':
      void deleteOne(row)
      break
  }
}

async function approve(row: PostVO) {
  working.value = true
  try {
    await adminApi.reviewPost(row.id, true)
    ElMessage.success('已通过，该内容已出现在广场')
    await reload()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
  } finally {
    working.value = false
  }
}

async function unpublishOne(row: PostVO) {
  try {
    await ElMessageBox.confirm(
      `下架后广场上立刻看不到这条内容，它会回到待审核队列（内容本身不删除，可以重新审）。`,
      `下架 ID ${row.id}？`,
      { type: 'warning', confirmButtonText: '下架', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await runBatch([row.id], 'unpublish')
}

async function deleteOne(row: PostVO) {
  try {
    await ElMessageBox.confirm(
      `将【彻底删除】ID ${row.id}（${row.author.displayName}）。\n`
      + '这是物理删除，无法恢复，也不会出现在回收站。',
      '确认彻底删除？',
      { type: 'error', confirmButtonText: '彻底删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await runBatch([row.id], 'delete')
}

// ---------------------------------------------------------------- 批量操作

/** 统一的批量执行入口：负责二次确认之外的一切（调用、提示、刷新、错误展示） */
async function runBatch(ids: number[], action: PostBatchAction, reason?: string) {
  working.value = true
  try {
    const result = await adminApi.postBatch(ids, action, reason)
    if (result.affected === 0) {
      // 0 条也要说清楚：批量操作最容易出的事就是"以为成功了其实一条没动"
      ElMessage.warning(result.message || '没有内容被处理')
    } else {
      ElMessage.success(result.message)
    }
    selected.value = []
    await reload()
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    working.value = false
  }
}

function batchUnpublish() {
  const ids = selected.value.map((row) => row.id)
  if (!ids.length) {
    return
  }
  void ElMessageBox.confirm(
    `将下架选中的 ${ids.length} 条内容：广场上立刻看不到，它们会回到待审核队列。`,
    '批量下架',
    { type: 'warning', confirmButtonText: '下架', cancelButtonText: '取消' },
  )
    .then(() => runBatch(ids, 'unpublish'))
    .catch(() => undefined)
}

function openBatchReject() {
  rejectTarget.value = null
  rejectReason.value = ''
  rejectVisible.value = true
}

async function confirmReject() {
  const target = rejectTarget.value
  if (target) {
    working.value = true
    try {
      await adminApi.reviewPost(target.id, false, rejectReason.value.trim() || undefined)
      rejectVisible.value = false
      ElMessage.success('已拒绝，作者可以看到理由')
      await reload()
    } catch (error) {
      ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
    } finally {
      working.value = false
    }
    return
  }
  const ids = selected.value.map((row) => row.id)
  rejectVisible.value = false
  await runBatch(ids, 'reject', rejectReason.value.trim() || undefined)
}

function batchDelete() {
  const ids = selected.value.map((row) => row.id)
  if (!ids.length) {
    return
  }
  // 破坏性操作：让管理员把数字念一遍再确认
  void ElMessageBox.prompt(
    `将【彻底删除】选中的 ${ids.length} 条内容。这是物理删除，无法恢复。\n`
    + `请输入 ${ids.length} 确认：`,
    '确认彻底删除？',
    {
      type: 'error',
      confirmButtonText: '彻底删除',
      cancelButtonText: '取消',
      inputPattern: new RegExp(`^\\s*${ids.length}\\s*$`),
      inputErrorMessage: `请输入 ${ids.length} 以确认`,
    },
  )
    .then(() => runBatch(ids, 'delete'))
    .catch(() => undefined)
}

/** 点作者进主页：处理举报时经常要先看看这个人还发过什么 */
function openAuthor(post: PostVO) {
  if (post.author?.userId) {
    void router.push(`/user/${post.author.userId}`)
  }
}

onMounted(() => void reload())
</script>

<style scoped>
.post-manage {
  padding: 4px 2px 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
  min-height: 0;
}

.head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
}

.head h2 {
  margin: 0 0 4px;
  font-size: 19px;
  letter-spacing: -0.02em;
}

.head p {
  margin: 0;
  font-size: 12.5px;
}

.warn {
  color: var(--sc-warning);
}

.ok {
  color: var(--sc-success);
}

.danger {
  color: var(--sc-danger);
}

.danger-text {
  color: var(--sc-danger);
}

/* ---------------- 统计卡片 ---------------- */

.stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 10px;
}

.stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 14px;
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius);
  background: var(--sc-surface);
  cursor: pointer;
  transition: var(--sc-transition);
}

.stat:hover {
  background: var(--sc-hover);
}

.stat.active {
  border-color: var(--sc-brand);
  background: var(--sc-brand-soft);
}

.stat span {
  font-size: 12px;
}

.stat strong {
  font-size: 20px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}

/* ---------------- 筛选与表格 ---------------- */

.filters-card :deep(.el-card__body) {
  padding: 12px 14px;
}

.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.w130 {
  width: 130px;
}

.w140 {
  width: 140px;
}

.w200 {
  width: 200px;
}

.table-card {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.table-card :deep(.el-card__body) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 0;
}

.table-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.spacer {
  flex: 1;
}

.selected-hint {
  font-size: 12.5px;
}

.author-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.author-text {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
  min-width: 0;
}

.author-text strong {
  font-size: 13px;
  font-weight: 600;
}

.author-text small {
  font-size: 11px;
}

.content-preview {
  /* 管理要看原文：保留换行，最多 3 行，其余折叠 */
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.6;
  max-height: 4.8em;
  overflow: hidden;
}

.pager {
  align-self: flex-end;
  padding: 10px 14px 0;
}

.dialog-tip {
  margin: 0 0 10px;
  font-size: 12.5px;
}

.dialog-error {
  margin: 8px 0 0;
  font-size: 12.5px;
  color: var(--sc-danger);
}

.detail {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 13px;
}

.detail-row {
  display: flex;
  gap: 10px;
  align-items: baseline;
}

.detail-row > .sc-muted {
  flex: 0 0 68px;
  font-size: 12.5px;
}

.detail-content {
  margin-top: 4px;
  padding: 10px 12px;
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius-sm);
  background: var(--sc-surface-2);
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.7;
  max-height: 40vh;
  overflow-y: auto;
}
</style>
