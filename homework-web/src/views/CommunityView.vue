<template>
  <div class="community">
    <!--
      两栏：左侧是"读 + 写"的主列，右侧是放发帖须知与个人概览的窄栏。
      主列限宽 660px —— 帖子是短文，一行太长会让人读到下一行时找不到行首；
      右栏在大屏才有（< 1180px 折到主列下方），避免小屏把正文挤成一条缝。
    -->
    <div class="wrap">
      <div class="main-col">
        <!-- ============ 发帖框 ============ -->
        <section class="composer">
          <header class="composer-head">
            <strong>{{ editing ? '修改帖子' : '发点什么' }}</strong>
            <span class="sc-muted small">
              <template v-if="editing">修改后需要重新审核</template>
              <template v-else>提交后由管理员审核，通过后所有同学可见</template>
            </span>
          </header>

          <el-input
            v-model="draft"
            type="textarea"
            :rows="4"
            resize="none"
            :maxlength="MAX_CHARS"
            show-word-limit
            placeholder="说点什么吧…（链接会自动识别并显示成可点击的样式）"
          />

          <div class="composer-foot">
            <span class="sc-muted small composer-tip">
              <el-icon><InfoFilled /></el-icon>
              <span>外部链接点开请注意甄别</span>
            </span>
            <div class="composer-actions">
              <el-button v-if="editing" @click="cancelEdit">取消</el-button>
              <el-button
                type="primary"
                :loading="submitting"
                :disabled="!draft.trim()"
                @click="submit"
              >
                <el-icon><Promotion /></el-icon>
                <span>{{ editing ? '提交修改' : '发布' }}</span>
              </el-button>
            </div>
          </div>
        </section>

        <!-- ============ 页签 ============ -->
        <el-tabs v-model="tab" class="tabs" @tab-change="onTabChange">
          <el-tab-pane name="all">
            <template #label>
              <span class="tab-label">广场</span>
            </template>
          </el-tab-pane>
          <el-tab-pane name="mine">
            <template #label>
              <span class="tab-label">
                我的
                <el-badge
                  v-if="summary && summary.pending > 0"
                  :value="summary.pending"
                  class="tab-badge"
                />
              </span>
            </template>
          </el-tab-pane>
        </el-tabs>

        <!-- 我的：三个状态各多少，一眼看清哪条在等审核、哪条被拒了 -->
        <div v-if="tab === 'mine' && summary" class="summary">
          <div class="sum-item">
            <span class="sc-muted">待审核</span>
            <strong>{{ summary.pending }}</strong>
          </div>
          <div class="sum-item">
            <span class="sc-muted">已通过</span>
            <strong>{{ summary.approved }}</strong>
          </div>
          <div class="sum-item">
            <span class="sc-muted">已拒绝</span>
            <strong class="danger">{{ summary.rejected }}</strong>
          </div>
        </div>

        <!-- ============ 列表 ============ -->
        <el-skeleton v-if="loading && !posts.length" :rows="4" animated class="sc-surface pad" />

        <el-empty
          v-else-if="!posts.length"
          :image-size="90"
          :description="tab === 'mine' ? '你还没有发过帖子' : '还没有人发帖，来当第一个吧'"
        />

        <div v-else class="feed">
          <PostCard v-for="post in posts" :key="post.id" :post="post">
            <template v-if="tab === 'mine' || post.canEdit" #actions>
              <div class="post-actions">
                <el-button v-if="post.canEdit" link type="primary" @click="startEdit(post)">
                  <el-icon><EditPen /></el-icon>
                  <span>修改</span>
                </el-button>
                <el-button v-if="post.canDelete" link type="danger" @click="removePost(post)">
                  <el-icon><Delete /></el-icon>
                  <span>删除</span>
                </el-button>
                <!-- 待审核时改不了：解释一句比一个灰按钮好懂 -->
                <span v-if="post.status === POST_STATUS_PENDING" class="sc-muted small">
                  审核中，暂不能修改或删除
                </span>
              </div>
            </template>
          </PostCard>

          <div class="more">
            <el-button v-if="hasMore" :loading="loadingMore" @click="loadMore">
              加载更早的帖子
            </el-button>
            <span v-else class="sc-muted small">没有更多了</span>
          </div>
        </div>
      </div>

      <!-- ============ 右栏：概览 + 须知 ============ -->
      <aside class="side-col">
        <section class="side-card">
          <h3>我的社区</h3>
          <ul class="stat">
            <li>
              <span class="sc-muted">我的帖子</span>
              <strong>{{ totalMine }}</strong>
            </li>
            <li>
              <span class="sc-muted">待审核</span>
              <strong :class="{ warn: (summary?.pending ?? 0) > 0 }">
                {{ summary?.pending ?? 0 }}
              </strong>
            </li>
            <li>
              <span class="sc-muted">已通过</span>
              <strong>{{ summary?.approved ?? 0 }}</strong>
            </li>
            <li>
              <span class="sc-muted">已拒绝</span>
              <strong :class="{ danger: (summary?.rejected ?? 0) > 0 }">
                {{ summary?.rejected ?? 0 }}
              </strong>
            </li>
          </ul>
          <el-button
            v-if="(summary?.pending ?? 0) > 0"
            class="side-btn"
            @click="switchToMine"
          >
            查看待审核的 {{ summary?.pending }} 条
          </el-button>
        </section>

        <section class="side-card">
          <h3>发帖须知</h3>
          <ol class="rules">
            <li>只支持<b>文字</b>；图片、文件请到网盘里分享。</li>
            <li>发布后需要管理员<b>审核通过</b>才会出现在广场。</li>
            <li><b>修改已通过的帖子会重新送审</b>，期间广场上看不到它。</li>
            <li>正文里的链接会自动识别成可点击样式，<b>点开前请自行甄别</b>。</li>
            <li>每小时最多发 {{ postHourHint }} 条；违规内容会被拒绝并附上理由。</li>
          </ol>
        </section>

        <section class="side-card">
          <h3>找同学</h3>
          <p class="sc-muted side-note">
            社区里点作者头像可以进 TA 的主页看更多帖子。
            加好友请到「好友」页，按<b>用户 ID</b> 查找。
          </p>
          <el-button class="side-btn" @click="router.push('/friends')">
            <el-icon><ChatDotRound /></el-icon>
            <span>去好友页</span>
          </el-button>
        </section>
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatDotRound,
  Delete,
  EditPen,
  InfoFilled,
  Promotion,
} from '@element-plus/icons-vue'
import PostCard from '@/components/PostCard.vue'
import { communityApi } from '@/api'
import { ApiError } from '@/api/http'
import {
  POST_STATUS_PENDING,
  type MyPostsSummaryVO,
  type PostVO,
} from '@/types/api'

/**
 * 社区广场。
 *
 * <p><b>两条最重要的行为约定：</b>
 * <ol>
 *   <li>发帖/编辑成功后<b>不把帖子插进时间线</b>，而是提示"已提交，等待管理员审核"。
 *       时间线只放已通过的内容；把自己的待审帖混进去会让人以为已经发出去了；</li>
 *   <li>编辑已通过的帖子会让它<b>回到待审核</b>并从广场暂时消失，界面上必须说清楚 ——
 *       否则用户会以为帖子被删了。这是防"先过审再改内容"的必要代价。</li>
 * </ol>
 *
 * <p>链接的识别与分段全部在服务端（`LinkSegmenter`），这里只负责把
 * `type=link` 的段渲染出来 —— 前端不做任何链接解析，也就不存在
 * "什么算链接"的第二份实现。
 */
const MAX_CHARS = 2000

/** 与后端 PostRules.POST_HOUR_LIMIT 一致，只用于提示文案 */
const postHourHint = 10

const router = useRouter()

const tab = ref<'all' | 'mine'>('all')
const posts = ref<PostVO[]>([])
const summary = ref<MyPostsSummaryVO | null>(null)
const cursor = ref<number | null>(null)
const hasMore = ref(false)
const loading = ref(false)
const loadingMore = ref(false)
const submitting = ref(false)
const draft = ref('')
/** 非空表示正在编辑这条（编辑时也回到待审核，见类注释） */
const editing = ref<PostVO | null>(null)

async function loadFeed(reset = true) {
  if (reset) {
    loading.value = true
  } else {
    loadingMore.value = true
  }
  try {
    const query: { beforeId?: number; mine?: boolean } = {}
    if (tab.value === 'mine') {
      query.mine = true
    }
    if (!reset && cursor.value) {
      query.beforeId = cursor.value
    }
    const feed = await communityApi.feed(query)
    posts.value = reset ? feed.posts : [...posts.value, ...feed.posts]
    cursor.value = feed.nextBeforeId
    hasMore.value = feed.hasMore
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载失败')
    if (reset) {
      posts.value = []
    }
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

async function loadSummary() {
  try {
    summary.value = await communityApi.mySummary()
  } catch {
    summary.value = null
  }
}

function loadMore() {
  void loadFeed(false)
}

/** 右栏"我的社区"里的总数：三个状态相加，避免再多一次请求 */
const totalMine = computed(() => {
  const s = summary.value
  return s ? s.pending + s.approved + s.rejected : 0
})

/** 右栏"查看待审核的 N 条"：切到"我的"页签并滚到顶部 */
function switchToMine() {
  if (tab.value !== 'mine') {
    tab.value = 'mine'
    onTabChange()
  }
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function onTabChange() {
  cursor.value = null
  posts.value = []
  void loadFeed(true)
  if (tab.value === 'mine') {
    void loadSummary()
  }
}

async function submit() {
  const content = draft.value.trim()
  if (!content || submitting.value) {
    return
  }
  submitting.value = true
  try {
    if (editing.value) {
      const result = await communityApi.update(editing.value.id, content)
      cancelEdit()
      ElMessage.success(result.message || '修改已提交，需重新审核')
    } else {
      const result = await communityApi.create(content)
      draft.value = ''
      ElMessage.success(result.message || '已提交，等待管理员审核')
    }
    // 我的列表要立刻反映新状态；广场则要等审核通过才可能出现
    await Promise.all([loadSummary(), loadFeed(true)])
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '提交失败')
  } finally {
    submitting.value = false
  }
}

function startEdit(post: PostVO) {
  editing.value = post
  draft.value = post.content
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function cancelEdit() {
  editing.value = null
  draft.value = ''
}

async function removePost(post: PostVO) {
  try {
    await ElMessageBox.confirm('删除后无法恢复。', '删除这条帖子？', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await communityApi.remove(post.id)
    posts.value = posts.value.filter((item) => item.id !== post.id)
    await loadSummary()
    ElMessage.success('已删除')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

onMounted(async () => {
  await Promise.all([loadFeed(true), loadSummary()])
})
</script>

<style scoped>
.community {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 18px var(--sc-gutter) 32px;
}

.wrap {
  max-width: 1040px;
  margin: 0 auto;
  display: grid;
  /* 主列限宽 660px（一行太长会读丢行首），右栏固定 260px。
     minmax(0, 1fr) 而不是 1fr：否则长链接会把列撑破（grid 的经典坑）。 */
  grid-template-columns: minmax(0, 1fr) 260px;
  align-items: start;
  gap: 20px;
}

.main-col {
  min-width: 0;
  max-width: 660px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* ---------------- 发帖框 ---------------- */

.composer {
  background: var(--sc-surface);
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius-lg);
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.composer-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.composer-head strong {
  font-size: 15px;
  font-weight: 620;
}

.composer-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.composer-tip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
}

.composer-actions {
  display: flex;
  gap: 8px;
}

/* ---------------- 页签与概览 ---------------- */

.tabs {
  margin-bottom: -8px;
}

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
  transform: translateY(-8px) translateX(4px);
}

.summary {
  display: flex;
  gap: 26px;
  padding: 12px 18px;
  background: var(--sc-surface-2);
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius);
}

.sum-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
}

.sum-item strong {
  font-size: 18px;
  font-weight: 650;
}

.sum-item .danger {
  color: var(--sc-danger);
}

/* ---------------- 帖子列表 ---------------- */

.feed {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.pad {
  padding: 16px;
  border-radius: var(--sc-radius-lg);
}

.post-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.more {
  display: flex;
  justify-content: center;
  padding: 6px 0 10px;
}

/* ---------------- 右栏 ---------------- */

.side-col {
  /* 跟随滚动：长列表里右栏一直可见，不必滚回顶部找"我的社区" */
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.side-card {
  background: var(--sc-surface);
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius-lg);
  padding: 14px 16px;
}

.side-card h3 {
  margin: 0 0 10px;
  font-size: 13.5px;
  font-weight: 620;
}

.stat {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.stat li {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  font-size: 12.5px;
}

.stat strong {
  font-size: 15px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}

.stat .warn {
  color: var(--sc-warning);
}

.stat .danger {
  color: var(--sc-danger);
}

.side-btn {
  width: 100%;
  margin-top: 12px;
}

.rules {
  margin: 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 7px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--sc-text-2);
}

.rules b {
  color: var(--sc-text);
  font-weight: 620;
}

.side-note {
  margin: 0 0 4px;
  font-size: 12px;
  line-height: 1.6;
}

.small {
  font-size: 11.5px;
}

/*
 * 窄屏（平板竖屏 / 分屏窗口）：右栏折到主列下方，主列放开限宽。
 * 用 order 把"我的社区"放到正文之前 —— 小屏上它是最需要一眼看到的信息，
 * 排在长列表后面等于没有。
 */
@media (max-width: 1180px) {
  .wrap {
    grid-template-columns: minmax(0, 1fr);
  }

  .main-col {
    max-width: none;
  }

  .side-col {
    position: static;
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
    align-items: start;
  }
}

@media (max-width: 640px) {
  .summary {
    gap: 16px;
    padding: 10px 14px;
  }

  .composer,
  .side-card {
    padding: 12px 14px;
  }
}
</style>
