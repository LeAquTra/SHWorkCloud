<template>
  <div class="post-review">
    <header class="head">
      <div>
        <h2>社区审核</h2>
        <p class="sc-muted">
          用户发布的文字动态需要审核通过后才会出现在广场。
          <strong v-if="postReview.hasPending" class="pending">
            当前还有 {{ postReview.pending }} 条待审核
          </strong>
          <span v-else>当前没有待审核的帖子。</span>
        </p>
      </div>
      <el-button :loading="loading" @click="reload()">
        <el-icon><Refresh /></el-icon>
        <span>刷新</span>
      </el-button>
    </header>

    <el-tabs v-model="statusTab" @tab-change="reload(1)">
      <el-tab-pane name="0">
        <template #label>
          <span class="tab-label">
            待审核
            <!-- 待审数与侧栏红点同源；为 0 时不显示角标 -->
            <el-badge
              v-if="postReview.hasPending"
              :value="postReview.pending"
              :max="99"
              class="tab-badge"
            />
          </span>
        </template>
      </el-tab-pane>
      <el-tab-pane label="已通过" name="1" />
      <el-tab-pane label="已拒绝" name="2" />
      <el-tab-pane label="全部" name="all" />
    </el-tabs>

    <!--
      加载失败时常驻显示原因（而不是只弹一条会被忽略的 toast）：
      这个页面曾经因为"加载失败"四个字没有下文，只能登服务器翻日志才定位到根因。
      曾经的真实根因是「点刷新把鼠标事件当成了页码」（见 reload 的注释）——
      现在原因会连同业务码一起显示在页面上。
    -->
    <el-alert
      v-if="lastError"
      type="error"
      :closable="false"
      show-icon
      title="加载审核队列失败"
      :description="`${lastError}　—— 可点右上角「刷新」重试；若持续失败请把这句话发给管理员。`"
    />

    <el-alert
      v-else-if="newPostHint"
      type="info"
      :closable="false"
      show-icon
      :title="newPostHint"
    />

    <el-table v-loading="loading" :data="records" row-key="id" class="table">
      <el-table-column label="作者" width="180">
        <template #default="{ row }">
          <div class="author-cell" @click="openAuthor(row)">
            <UserAvatar :card="row.author" :size="30" />
            <div class="author-text">
              <strong>{{ row.author.displayName }}</strong>
              <small class="sc-muted">
                {{ row.author.className || '—' }} · {{ row.author.username }}
              </small>
            </div>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="内容" min-width="300">
        <template #default="{ row }">
          <!--
            审核要看的是**原文**（作者写的是什么），所以这里直接显示 content 而不是
            分段渲染：审核员需要看到 "https://x.com" 这类原始形态，
            而不是它被渲染成蓝色链接后的样子。
          -->
          <div class="content-preview">{{ row.content }}</div>
        </template>
      </el-table-column>

      <el-table-column label="链接" width="80" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.linkCount > 0" size="small" type="warning" effect="light">
            {{ row.linkCount }}
          </el-tag>
          <span v-else class="sc-muted">—</span>
        </template>
      </el-table-column>

      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" effect="light" :type="statusType(row.status)">
            {{ POST_STATUS_LABELS[row.status] }}
          </el-tag>
        </template>
      </el-table-column>

      <el-table-column label="提交时间" width="160">
        <template #default="{ row }">
          <span class="sc-tabular sc-subtle">{{ formatTime(row.createTime) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <template v-if="row.status === POST_STATUS_PENDING">
            <el-button type="primary" size="small" @click="approve(row)">通过</el-button>
            <el-button type="danger" size="small" plain @click="openReject(row)">拒绝</el-button>
          </template>
          <template v-else>
            <el-tooltip
              :content="row.status === POST_STATUS_REJECTED && row.rejectReason
                ? `拒绝理由：${row.rejectReason}`
                : '已处理，不可重复审核'"
              placement="top"
            >
              <span class="sc-muted small">
                {{ row.status === POST_STATUS_APPROVED ? '已通过' : '已拒绝' }}
              </span>
            </el-tooltip>
          </template>
        </template>
      </el-table-column>

      <template #empty>
        <el-empty :image-size="80" :description="emptyText" />
      </template>
    </el-table>

    <el-pagination
      v-model:current-page="page"
      :page-size="size"
      :total="total"
      layout="total, prev, pager, next"
      class="pager"
      @current-change="() => reload()"
    />

    <!-- 拒绝：理由会被作者看到 -->
    <el-dialog v-model="rejectVisible" title="拒绝这条帖子" width="460px">
      <p class="sc-muted dialog-tip">
        理由会显示给作者（建议填写，但不强制）。留空也可以直接拒绝。
      </p>
      <el-input
        v-model="rejectReason"
        type="textarea"
        :rows="3"
        resize="none"
        maxlength="200"
        show-word-limit
        placeholder="例如：含不良信息 / 与学习无关的广告"
      />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="reviewing" @click="confirmReject">确认拒绝</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'
import { adminApi } from '@/api'
import { ApiError } from '@/api/http'
import {
  POST_STATUS_APPROVED,
  POST_STATUS_LABELS,
  POST_STATUS_PENDING,
  POST_STATUS_REJECTED,
  type PostVO,
} from '@/types/api'
import { startPendingPolling, stopPendingPolling, usePostReviewStore } from '@/stores/postReview'
import { formatTime } from '@/utils/format'

/**
 * 社区审核（管理员及以上：管理员 / 教师 / 超管）。
 *
 * <p>权限由后端两层把关（`@SaCheckRole` + `PostService.requireReviewer`），
 * 这里的可见性只是体验控制 —— 与项目其它后台页一致。
 *
 * <p><b>审核列表显示原文而不是渲染后的分段</b>：审核员需要看到作者写下的原始形态
 * （"https://x.com" 而不是一个蓝色链接），否则无法判断链接指向哪里。
 */
const router = useRouter()

const statusTab = ref<string>('0')
const records = ref<PostVO[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const reviewing = ref(false)

const rejectVisible = ref(false)
const rejectReason = ref('')
const rejectTarget = ref<PostVO | null>(null)

/** 上一次加载失败的原因（页面上常驻显示，不必去翻控制台） */
const lastError = ref('')

/**
 * 待审数由**共享 store** 提供，本页不再自己维护一份。
 * <p>侧栏「社区审核」的红点读的是同一个数字 —— 两处各拉一次会出现
 * "侧栏显示 3 条、点进来页面说 0 条"。轮询的启停也统一在 store 里，
 * 本组件只订阅它的变化：变大 = 有人刚发帖 → 自动刷新列表并提示。
 */
const postReview = usePostReviewStore()

/** 自动刷新时的提示语（有则显示一条 info 条） */
const newPostHint = ref('')

const emptyText = computed(() =>
  statusTab.value === '0' ? '没有待审核的帖子' : '这里还没有内容',
)

/**
 * 页签 → 查询参数里的 status。
 * <p>「全部」必须是 `undefined`（axios 才会整个不传这个参数）。
 * <p>⚠️ 不能写成 `Number(statusTab)` 再靠意外兜住：`Number('all')` 是 `NaN`，
 * 一旦它进了查询串就是 `status=NaN`，服务端 `Integer status` 绑不上 → 40000。
 * 这里显式判「全部」，其余取值走一次白名单校验，NaN 永远没有机会离开这个函数。
 */
function statusQuery(): number | undefined {
  if (statusTab.value === 'all') {
    return undefined
  }
  const value = Number(statusTab.value)
  return Number.isInteger(value) ? value : undefined
}

function statusType(status: number) {
  if (status === POST_STATUS_APPROVED) return 'success'
  if (status === POST_STATUS_REJECTED) return 'danger'
  return 'info'
}

async function reload(toPage?: number) {
  // ⚠️ 这里必须判类型，不能只写 `if (toPage)`（真实故障，用户报的"点刷新提示加载失败"）。
  //
  // 模板里的刷新按钮写的是 `@click="reload"` —— Vue 会把**鼠标事件对象**当成第一个
  // 实参传进来。只判真假的话事件对象是真值，于是 `page.value` 被赋成一个 PointerEvent，
  // axios 把它序列化成 `page=[object PointerEvent]`，服务端 `Long page` 绑不上，
  // 返回 40000「参数类型错误: page」——现象正好是"点刷新就加载失败"。
  // 带参数的调用（`reload(1)`）走的是同一段代码，所以修在这里而不是改模板。
  if (typeof toPage === 'number' && Number.isFinite(toPage)) {
    page.value = toPage
  }
  loading.value = true
  try {
    const result = await adminApi.postQueue({
      status: statusQuery(),
      page: page.value,
      size: size.value,
    })
    records.value = result.records
    total.value = result.total
    // 顺手把 store 里的待审数对齐（与侧栏红点同一个数字，不再各拉各的）
    postReview.set(result.pendingTotal)
    lastError.value = ''
  } catch (error) {
    // 把真实原因展示出来，而不是笼统的"加载失败"。
    // 这个页面曾经因为一句没有下文的"加载失败"而需要登服务器翻日志才能定位 ——
    // 现在带上业务码与后端 message（后端已把数据库错误的根因放进 message）。
    const detail = describeError(error)
    lastError.value = detail
    ElMessage.error(`加载失败：${detail}`)
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/**
 * 页码越界时回退到最后一页。
 * <p>典型的越界方式是"审完最后一页上的最后一条"：那一页就空了，
 * 而审核员看到的是**一个空列表加一句"没有待审核的帖子"**，很容易以为队列真的清空了
 * （其实前面几页还有）。这里在数据回来之后自动退一页。
 */
watch([total, page], ([nextTotal, currentPage]) => {
  const lastPage = Math.max(1, Math.ceil(nextTotal / size.value))
  if (currentPage > lastPage) {
    void reload(lastPage)
  }
})

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

async function approve(post: PostVO) {
  reviewing.value = true
  try {
    await adminApi.reviewPost(post.id, true)
    ElMessage.success('已通过，该帖子已出现在广场')
    // reload 会把服务端最新的待审数写回 store，侧栏红点随之立刻 -1
    await reload()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
  } finally {
    reviewing.value = false
  }
}

function openReject(post: PostVO) {
  rejectTarget.value = post
  rejectReason.value = ''
  rejectVisible.value = true
}

async function confirmReject() {
  const target = rejectTarget.value
  if (!target) {
    return
  }
  reviewing.value = true
  try {
    await adminApi.reviewPost(target.id, false, rejectReason.value.trim() || undefined)
    rejectVisible.value = false
    ElMessage.success('已拒绝，作者可以看到理由')
    await reload()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
  } finally {
    reviewing.value = false
  }
}

/** 点作者进主页：审核时经常需要先看看这个人发过什么 */
function openAuthor(post: PostVO) {
  if (post.author?.userId) {
    void router.push(`/user/${post.author.userId}`)
  }
}

/**
 * 待审数变化 → 列表跟着动。
 *
 * <p>这个页面和侧栏红点读的是同一个数字（{@code postReview.pending}），
 * 所以只需要盯着一个来源：
 * <ul>
 *   <li><b>变大</b>：有学生刚发帖（或作者改了帖子重新送审）→ 列表自动刷新并提示一句。
 *       审核员开着这个页面等学生交作业时，不该需要反复手点刷新；</li>
 *   <li><b>变小</b>：有人在别处审过了 → 也刷新，让列表与队列保持一致
 *       （否则页面上还挂着一条已经被同事审掉的帖子，点"通过"会报状态错误）。</li>
 * </ul>
 * 首次进入页面时 store 里可能还是 0（后台布局刚拉、值还没回来），
 * 那种"从 0 涨上来"的首帧不算新帖，所以用 {@code baseline} 把第一次看到的数字记下来。
 */
let baseline: number | null = null

watch(
  () => postReview.pending,
  async (count) => {
    const previous = baseline
    baseline = count
    // 首次只记基线：否则一进页面就报"有新帖提交"
    if (previous === null || previous === count) {
      return
    }
    if (count > previous) {
      newPostHint.value = `有 ${count - previous} 条新帖提交，已自动刷新`
      ElMessage.info(newPostHint.value)
    }
    await reload()
  },
)

onMounted(async () => {
  await reload()
  // 列表拿到之后把待审数记为基线，再起轮询：
  // 轮询本身在 store 里（侧栏也要用同一个数字），这里只是订阅它的变化。
  baseline = postReview.pending
  startPendingPolling(postReview)
})

onBeforeUnmount(stopPendingPolling)
</script>

<style scoped>
.post-review {
  padding: 4px 2px 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
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

.pending {
  color: var(--sc-danger);
}

/* ---------------- 页签上的待审角标 ---------------- */

.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.tab-badge :deep(.el-badge__content) {
  border: none;
  font-size: 10px;
  height: 15px;
  line-height: 15px;
  padding: 0 4px;
  /* 角标默认叠在文字右上角会压到页签，抬起来一点 */
  transform: translateY(-8px) translateX(4px);
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
  /* 审核要看原文：保留换行，最多显示 4 行，其余折叠 */
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.6;
  max-height: 6.4em;
  overflow: hidden;
  position: relative;
}

.pager {
  align-self: flex-end;
}

.dialog-tip {
  margin: 0 0 10px;
  font-size: 12.5px;
}

.small {
  font-size: 11.5px;
}
</style>
