<template>
  <div class="user-home">
    <el-skeleton v-if="loading" :rows="5" animated class="card" />

    <el-result
      v-else-if="error"
      icon="warning"
      :title="error"
      sub-title="对方可能已被禁用或注销；也可能是链接里的用户 ID 不正确。"
    >
      <template #extra>
        <el-button @click="router.back()">返回</el-button>
        <el-button type="primary" @click="load">重试</el-button>
      </template>
    </el-result>

    <template v-else-if="card">
      <section class="card hero">
        <UserAvatar :card="card" :size="76" />

        <div class="hero-text">
          <h2>
            {{ card.displayName }}
            <el-tag v-if="card.role > 0" size="small" effect="light" type="warning">
              {{ roleLabel }}
            </el-tag>
          </h2>
          <p class="sc-muted meta">
            <span>学号 {{ card.username }}</span>
            <template v-if="card.className">
              <span class="dot">·</span>
              <span>{{ card.className }}</span>
            </template>
            <template v-if="card.nickname && card.realName">
              <span class="dot">·</span>
              <span>姓名 {{ card.realName }}</span>
            </template>
          </p>
          <p class="signature">
            {{ card.signature || '这个人很懒，什么也没写' }}
          </p>
        </div>

        <!--
          按钮完全按服务端下发的 relation 分支，前端不自己推算关系：
          判断关系要同时看两个方向的边（他申请我 / 我申请他 / 互为好友），
          只拿得到自己这一侧数据的前端必然算错。
        -->
        <div class="hero-actions">
          <template v-if="card.relation === RELATION_SELF">
            <el-button type="primary" @click="router.push('/profile')">
              <el-icon><Setting /></el-icon>
              <span>编辑我的资料</span>
            </el-button>
          </template>

          <template v-else-if="card.relation === RELATION_FRIEND">
            <el-button type="primary" @click="startChat">
              <el-icon><ChatDotRound /></el-icon>
              <span>发消息</span>
            </el-button>
            <el-button @click="removeFriend">删除好友</el-button>
          </template>

          <template v-else-if="card.relation === RELATION_INCOMING">
            <el-button type="primary" @click="handleRequest(true)">
              <el-icon><Check /></el-icon>
              <span>同意好友申请</span>
            </el-button>
            <el-button @click="handleRequest(false)">拒绝</el-button>
          </template>

          <template v-else-if="card.relation === RELATION_OUTGOING">
            <el-button disabled>
              <el-icon><Clock /></el-icon>
              <span>已发送申请，等待对方处理</span>
            </el-button>
          </template>

          <template v-else>
            <el-button
              type="primary"
              :disabled="(overview?.remaining ?? 0) <= 0"
              @click="addFriend"
            >
              <el-icon><Plus /></el-icon>
              <span>加为好友</span>
            </el-button>
            <el-tooltip
              v-if="(overview?.remaining ?? 0) <= 0"
              content="好友已达上限，请先删除部分好友"
              placement="bottom"
            >
              <span class="sc-muted small">还可添加 0 人</span>
            </el-tooltip>
          </template>
        </div>
      </section>

      <section class="card info">
        <h3>关于 TA</h3>
        <ul>
          <li>
            <span class="label sc-muted">昵称</span>
            <span>{{ card.nickname || '未设置' }}</span>
          </li>
          <li>
            <span class="label sc-muted">姓名</span>
            <span>{{ card.realName || '未设置' }}</span>
          </li>
          <li>
            <span class="label sc-muted">班级</span>
            <span>{{ card.className || '未设置' }}</span>
          </li>
          <li>
            <span class="label sc-muted">身份</span>
            <span>{{ roleLabel }}</span>
          </li>
        </ul>
        <p class="privacy sc-muted">
          这里只展示公开信息。邮箱、生日、容量等资料不会对其他人开放；
          聊天内容仅你们双方可见，教师与管理员看不到。
        </p>
      </section>

      <!--
        TA 的帖子。
        只取【已通过】的内容 —— 服务端在 authorId ≠ 自己 时会过滤掉待审与被拒的帖子
        （见 PostService.feed），所以这里不必也不能自己再判断一次状态。
      -->
      <section class="card posts">
        <h3>TA 的帖子</h3>

        <el-skeleton v-if="loadingPosts" :rows="3" animated />

        <el-empty
          v-else-if="!posts.length"
          :image-size="70"
          description="TA 还没有公开发布的帖子"
        />

        <template v-else>
          <PostCard v-for="post in posts" :key="post.id" :post="post" compact />
          <div v-if="postsHasMore" class="posts-more">
            <el-button :loading="loadingPosts" @click="loadMorePosts">加载更早的</el-button>
          </div>
        </template>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Check,
  ChatDotRound,
  Clock,
  Plus,
  Setting,
} from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'
import PostCard from '@/components/PostCard.vue'
import { communityApi, friendApi, userApi } from '@/api'
import { ApiError } from '@/api/http'
import { useFriendStore } from '@/stores/friend'
import {
  RELATION_FRIEND,
  RELATION_INCOMING,
  RELATION_OUTGOING,
  RELATION_SELF,
  ROLE_LABELS,
  type FriendOverview,
  type PostVO,
  type UserCard,
} from '@/types/api'

/**
 * 别人的主页。
 *
 * <p>入口有三处：好友列表/会话列表点某人、聊天窗点对方名字、
 * （后续）社区里点作者头像。
 *
 * <p><b>数据来自 {@code GET /user/{id}/profile}</b>，它返回的是服务端
 * 收窄过的名片：没有邮箱、生日、性别、容量。看别人的主页不需要这些 ——
 * 这也是为什么这个页面不复用 {@code /profile}（那个返回本人的完整资料）。
 *
 * <p>看自己的主页会被引导到 {@code /profile}：那里有编辑能力与容量信息，
 * 在这里给自己看一份只读的公开信息没有意义。
 */
const props = defineProps<{ id: string }>()

const router = useRouter()
const friendStore = useFriendStore()

const loading = ref(true)
const error = ref('')
const card = ref<UserCard | null>(null)
const overview = ref<FriendOverview | null>(null)

const posts = ref<PostVO[]>([])
const loadingPosts = ref(false)
const postsCursor = ref<number | null>(null)
const postsHasMore = ref(false)

const roleLabel = computed(() => ROLE_LABELS[card.value?.role ?? 0] || '用户')

async function load() {
  const userId = Number(props.id)
  if (!userId || Number.isNaN(userId)) {
    error.value = '用户 ID 不正确'
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  try {
    card.value = await userApi.publicProfile(userId)
    // 名额只在需要"加好友"时才用得上，拿不到也不影响页面主体
    try {
      overview.value = await friendApi.overview()
    } catch {
      overview.value = null
    }
    // 帖子与主页解耦：社区加载失败不应该让整个主页变成错误页
    void loadPosts(true)
  } catch (err) {
    card.value = null
    error.value = err instanceof ApiError ? err.message : '加载失败'
  } finally {
    loading.value = false
  }
}

/**
 * 拉 TA 的公开帖子。
 * <p>服务端只返回**已通过**的帖子（`PostService.feed` 里 authorId ≠ 自己 时会隐去
 * 待审与被拒的内容），所以这里不需要、也不应该再按状态过滤一遍。
 */
async function loadPosts(reset = false) {
  const userId = Number(props.id)
  if (!userId || Number.isNaN(userId)) {
    return
  }
  loadingPosts.value = true
  try {
    const query: { authorId: number; beforeId?: number } = { authorId: userId }
    if (!reset && postsCursor.value) {
      query.beforeId = postsCursor.value
    }
    const feed = await communityApi.feed(query)
    posts.value = reset ? feed.posts : [...posts.value, ...feed.posts]
    postsCursor.value = feed.nextBeforeId
    postsHasMore.value = feed.hasMore
  } catch {
    // 社区可能未开通（表未迁移）—— 静默处理，主页其余部分照常可用
    if (reset) {
      posts.value = []
    }
    postsHasMore.value = false
  } finally {
    loadingPosts.value = false
  }
}

function loadMorePosts() {
  void loadPosts(false)
}

/** 加好友后重新拉一次卡片，让按钮跟着关系变化（服务端是唯一权威） */
async function refreshCard() {
  card.value = await userApi.publicProfile(Number(props.id))
}

async function addFriend() {
  try {
    overview.value = await friendApi.request(Number(props.id))
    await refreshCard()
    ElMessage.success('好友申请已发送')
  } catch (err) {
    ElMessage.error(err instanceof ApiError ? err.message : '发送申请失败')
  }
}

async function handleRequest(accept: boolean) {
  try {
    overview.value = await friendApi.handle(Number(props.id), accept)
    await refreshCard()
    await friendStore.refresh()
    ElMessage.success(accept ? '你们已经成为好友' : '已拒绝该申请')
  } catch (err) {
    ElMessage.error(err instanceof ApiError ? err.message : '处理申请失败')
  }
}

async function removeFriend() {
  try {
    await ElMessageBox.confirm(
      '删除后你们将不再是好友，聊天记录也不再展示。',
      `删除好友「${card.value?.displayName ?? ''}」`,
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    overview.value = await friendApi.remove(Number(props.id))
    await refreshCard()
    await friendStore.refresh()
    ElMessage.success('已删除好友')
  } catch (err) {
    ElMessage.error(err instanceof ApiError ? err.message : '删除失败')
  }
}

/** 跳到好友页并直接打开与 TA 的会话（见 FriendsView 的 enterChat 入参） */
function startChat() {
  void router.push({ path: '/friends', query: { chat: String(props.id) } })
}

watch(() => props.id, () => void load())
onMounted(() => void load())
</script>

<style scoped>
.user-home {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 20px var(--sc-gutter) 32px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 900px;
  width: 100%;
  margin: 0 auto;
}

.card {
  background: var(--sc-surface);
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius-lg);
  padding: 20px;
}

.hero {
  display: flex;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;
}

.hero-text {
  flex: 1;
  min-width: 200px;
}

.hero-text h2 {
  margin: 0 0 6px;
  font-size: 20px;
  letter-spacing: -0.02em;
  display: flex;
  align-items: center;
  gap: 8px;
}

.meta {
  margin: 0 0 8px;
  font-size: 12.5px;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.dot {
  opacity: 0.5;
}

.signature {
  margin: 0;
  font-size: 13.5px;
  color: var(--sc-text-2);
  line-height: 1.6;
}

.hero-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.info h3 {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 620;
}

.info ul {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 10px;
}

.info li {
  display: flex;
  gap: 12px;
  font-size: 13.5px;
}

.label {
  width: 56px;
  flex: 0 0 auto;
  font-size: 12.5px;
}

.privacy {
  margin: 16px 0 0;
  font-size: 12px;
  line-height: 1.6;
  padding-top: 14px;
  border-top: 1px dashed var(--sc-border);
}

.posts {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.posts h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 620;
}

.posts-more {
  display: flex;
  justify-content: center;
}

.small {
  font-size: 12px;
}
</style>
