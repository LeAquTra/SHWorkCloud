<template>
  <div class="post-review">
    <header class="head">
      <div>
        <h2>社区审核</h2>
        <p class="sc-muted">
          用户发布的文字动态需要审核通过后才会出现在广场。
          <strong v-if="pendingTotal > 0" class="pending">当前还有 {{ pendingTotal }} 条待审核</strong>
          <span v-else>当前没有待审核的帖子。</span>
        </p>
      </div>
      <el-button :loading="loading" @click="reload">
        <el-icon><Refresh /></el-icon>
        <span>刷新</span>
      </el-button>
    </header>

    <el-tabs v-model="statusTab" @tab-change="reload(1)">
      <el-tab-pane label="待审核" name="0" />
      <el-tab-pane label="已通过" name="1" />
      <el-tab-pane label="已拒绝" name="2" />
      <el-tab-pane label="全部" name="all" />
    </el-tabs>

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
import { computed, onMounted, ref } from 'vue'
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
const pendingTotal = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const reviewing = ref(false)

const rejectVisible = ref(false)
const rejectReason = ref('')
const rejectTarget = ref<PostVO | null>(null)

const emptyText = computed(() =>
  statusTab.value === '0' ? '没有待审核的帖子' : '这里还没有内容',
)

function statusQuery(): number | undefined {
  return statusTab.value === 'all' ? undefined : Number(statusTab.value)
}

function statusType(status: number) {
  if (status === POST_STATUS_APPROVED) return 'success'
  if (status === POST_STATUS_REJECTED) return 'danger'
  return 'info'
}

async function reload(toPage?: number) {
  if (toPage) {
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
    pendingTotal.value = result.pendingTotal
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载失败')
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function approve(post: PostVO) {
  reviewing.value = true
  try {
    await adminApi.reviewPost(post.id, true)
    ElMessage.success('已通过，该帖子已出现在广场')
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

onMounted(() => void reload())
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
