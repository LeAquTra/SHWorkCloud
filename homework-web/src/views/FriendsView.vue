<template>
  <div class="friends">
    <!-- ============ 左栏：好友 / 申请 / 搜索 ============ -->
    <aside class="side">
      <div class="side-head">
        <el-input
          v-model="searchId"
          placeholder="输入对方用户 ID"
          clearable
          type="number"
          min="1"
          :prefix-icon="Search"
          @keyup.enter="doSearch"
          @clear="clearSearch"
        />
        <el-button type="primary" :loading="searching" @click="doSearch">查找</el-button>
      </div>

      <!-- 名额：由服务端下发（maxFriends），前端不写死 50 -->
      <div class="quota-line">
        <el-icon><UserFilled /></el-icon>
        <span class="sc-muted">
          好友 {{ overview?.friends.length ?? 0 }} / {{ overview?.maxFriends ?? '—' }}
        </span>
        <el-tag size="small" effect="light" :type="slotTagType">
          还可添加 {{ overview?.remaining ?? '—' }} 人
        </el-tag>
      </div>

      <!-- 搜索模式 -->
      <template v-if="searchMode">
        <div class="list-head">
          <span>查找结果</span>
          <el-button link type="primary" @click="clearSearch">返回会话</el-button>
        </div>
        <el-empty
          v-if="!searching && !searchResults.length"
          :image-size="70"
          :description="`没有找到 ID 为 ${searchedId} 的用户`"
        >
          <p class="sc-muted hint">
            只能按<b>用户 ID</b> 精确查找。ID 可以问对方，或在 TA 的主页地址里看到
            （<code>/user/<b>1002</b></code> 里那个数字）。
          </p>
        </el-empty>
        <ul v-else class="user-list">
          <li v-for="item in searchResults" :key="item.userId" class="user-row">
            <UserAvatar :card="item" :size="38" @click="openHome(item.userId)" />
            <div class="user-text" @click="openHome(item.userId)">
              <strong>{{ item.displayName }}</strong>
              <small class="sc-muted">
                {{ subtitleOf(item) }}
              </small>
            </div>
            <div class="user-action">
              <el-button
                v-if="item.relation === RELATION_NONE"
                type="primary"
                size="small"
                :disabled="(overview?.remaining ?? 0) <= 0"
                @click="addFriend(item)"
              >
                加好友
              </el-button>
              <el-tag v-else-if="item.relation === RELATION_OUTGOING" size="small" type="info">
                已申请
              </el-tag>
              <template v-else-if="item.relation === RELATION_INCOMING">
                <el-button type="primary" size="small" @click="handleRequest(item, true)">
                  同意
                </el-button>
                <el-button size="small" @click="handleRequest(item, false)">拒绝</el-button>
              </template>
              <el-tag v-else-if="item.relation === RELATION_FRIEND" size="small" type="success">
                已是好友
              </el-tag>
              <el-tag v-else size="small" type="info">我自己</el-tag>
            </div>
          </li>
        </ul>
      </template>

      <!-- 会话模式 -->
      <template v-else>
        <!-- 待处理申请置顶：不这么做的话，别人加了你而你没往下翻，申请就一直被忽略 -->
        <div v-if="incoming.length" class="req-block">
          <div class="list-head">
            <span>好友申请</span>
            <el-tag size="small" type="danger" effect="dark">{{ incoming.length }}</el-tag>
          </div>
          <ul class="user-list">
            <li v-for="item in incoming" :key="`req-${item.userId}`" class="user-row">
              <UserAvatar :card="item" :size="38" @click="openHome(item.userId)" />
              <div class="user-text" @click="openHome(item.userId)">
                <strong>{{ item.displayName }}</strong>
                <small class="sc-muted">请求添加你为好友</small>
              </div>
              <div class="user-action">
                <el-button type="primary" size="small" @click="handleRequest(item, true)">
                  同意
                </el-button>
                <el-button size="small" @click="handleRequest(item, false)">拒绝</el-button>
              </div>
            </li>
          </ul>
        </div>

        <div class="list-head">
          <span>好友</span>
          <el-button link type="primary" @click="reloadConversations">
            <el-icon><Refresh /></el-icon>
          </el-button>
        </div>

        <el-empty
          v-if="!loadingConversations && !conversations.length"
          :image-size="70"
          description="还没有好友"
        >
          <p class="sc-muted hint">
            用上面的输入框按<b>用户 ID</b> 找人；也可以先请对方加你，
            然后在上方「好友申请」里同意。
          </p>
        </el-empty>

        <ul v-else class="user-list conv-list">
          <li
            v-for="item in conversations"
            :key="item.peer.userId"
            class="user-row conv-row"
            :class="{ active: activePeer?.userId === item.peer.userId }"
            @click="openChat(item.peer)"
          >
            <UserAvatar :card="item.peer" :size="38" :badge="item.unread" />
            <div class="user-text">
              <strong>{{ item.peer.displayName }}</strong>
              <small class="sc-muted ellipsis">
                <template v-if="item.lastMessage">
                  <span v-if="item.lastFromMe">我：</span>{{ item.lastMessage }}
                </template>
                <template v-else>还没有聊过</template>
              </small>
            </div>
            <time v-if="item.lastMessageTime" class="sc-tabular sc-muted conv-time">
              {{ shortTime(item.lastMessageTime) }}
            </time>
          </li>
        </ul>

        <!-- 我发出的申请：不显示出来的话，点完"加好友"界面没变化，用户会反复点 -->
        <template v-if="outgoing.length">
          <div class="list-head">
            <span>已发送的申请</span>
          </div>
          <ul class="user-list">
            <li v-for="item in outgoing" :key="`out-${item.userId}`" class="user-row">
              <UserAvatar :card="item" :size="32" @click="openHome(item.userId)" />
              <div class="user-text" @click="openHome(item.userId)">
                <strong>{{ item.displayName }}</strong>
                <small class="sc-muted">等待对方处理</small>
              </div>
            </li>
          </ul>
        </template>
      </template>
    </aside>

    <!-- ============ 右栏：聊天 ============ -->
    <main class="chat">
      <template v-if="activePeer">
        <header class="chat-head">
          <UserAvatar :card="activePeer" :size="36" @click="openHome(activePeer.userId)" />
          <div class="chat-title">
            <strong>{{ activePeer.displayName }}</strong>
            <small class="sc-muted">
              <template v-if="activePeer.className">{{ activePeer.className }} · </template>
              学号 {{ activePeer.username }}
            </small>
          </div>
          <el-button link type="primary" @click="openHome(activePeer.userId)">
            查看主页
          </el-button>
          <el-dropdown trigger="click" placement="bottom-end" @command="onChatCommand">
            <el-button link>
              更多
              <el-icon><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="remove">
                  <el-icon><Delete /></el-icon>
                  <span>删除好友</span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </header>

        <div ref="scrollBox" class="msg-box" @scroll="onScroll">
          <div v-if="loadingHistory" class="loading-line sc-muted">加载中…</div>
          <div v-else-if="hasMore" class="loading-line">
            <el-button link type="primary" @click="loadHistory">查看更早的消息</el-button>
          </div>

          <el-empty
            v-if="!messages.length && !loadingHistory"
            :image-size="64"
            description="还没有消息，打个招呼吧"
          />

          <div v-for="msg in messages" :key="msg.id" class="msg-row" :class="{ mine: msg.mine }">
            <UserAvatar
              :card="msg.mine ? meCard : activePeer"
              :size="32"
              @click="openHome(msg.mine ? undefined : activePeer.userId)"
            />
            <div class="bubble-wrap">
              <!--
                ⚠️ 必须用文本插值渲染：content 是用户输入。
                这里一旦改成 v-html，任何一条消息都能在对方会话里执行脚本。
              -->
              <div class="bubble">{{ msg.content }}</div>
              <div class="bubble-meta sc-muted sc-tabular">
                <span>{{ formatTime(msg.createTime) }}</span>
                <span v-if="msg.mine && showReadFor(msg)" class="read-flag">已读</span>
              </div>
            </div>
          </div>
        </div>

        <footer class="composer">
          <el-input
            v-model="draft"
            type="textarea"
            :rows="3"
            resize="none"
            :maxlength="MESSAGE_MAX_CHARS"
            show-word-limit
            placeholder="输入消息，回车发送（Shift + 回车换行）"
            @keydown.enter.exact.prevent="sendMessage"
          />
          <div class="composer-foot">
            <span class="sc-muted small">
              只能发文字。消息会保存在服务器上，教师与管理员<b>看不到</b>聊天内容。
            </span>
            <el-button
              type="primary"
              :loading="sending"
              :disabled="!draft.trim()"
              @click="sendMessage"
            >
              <el-icon><Promotion /></el-icon>
              <span>发送</span>
            </el-button>
          </div>
        </footer>
      </template>

      <div v-else class="chat-empty">
        <div class="empty-art"><el-icon><ChatDotRound /></el-icon></div>
        <strong>选择一位好友开始聊天</strong>
        <p class="sc-muted">
          只能发送文字；对方不在线时消息会留着，下次登录仍能看到。
        </p>
        <el-button v-if="!conversations.length" type="primary" @click="focusSearch">
          搜索同学
        </el-button>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowDown,
  ChatDotRound,
  Delete,
  Promotion,
  Refresh,
  Search,
  UserFilled,
} from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'
import { chatApi, friendApi, userApi } from '@/api'
import { ApiError } from '@/api/http'
import { useFriendStore } from '@/stores/friend'
import { useUserStore } from '@/stores/user'
import {
  RELATION_FRIEND,
  RELATION_INCOMING,
  RELATION_NONE,
  RELATION_OUTGOING,
  type ChatMessageVO,
  type ConversationVO,
  type FriendOverview,
  type UserCard,
} from '@/types/api'
import { formatTime } from '@/utils/format'

/**
 * 好友与私聊。
 *
 * <p><b>为什么聊天用轮询而不是 WebSocket：</b>
 * 本项目的会话在内存（未引入 `sa-token-redis-jackson`），WebSocket 依赖也不在
 * 离线本地仓库里；而服务端的接口契约是**游标式**的（`afterId` / `maxId`），
 * 换成 SSE 或 WebSocket 时**只有这一处传输层要改**，接口与数据结构都不用动。
 *
 * <p><b>轮询策略：</b>聊天窗打开时 4 秒一次拉增量（用户确实在等消息），
 * 页面切到后台（`document.hidden`）时自动跳过，避免几十个标签页白刷。
 * 顶栏红点是另一个 30 秒的独立轮询，见 `stores/friend.ts`。
 */
const POLL_MS = 4000

/** 与服务端 `FriendRules.MESSAGE_MAX_CHARS` 一致。改这里必须同步改后端校验 */
const MESSAGE_MAX_CHARS = 1000

const props = withDefaults(defineProps<{ enterChat?: number }>(), { enterChat: 0 })

const route = useRoute()
const router = useRouter()
const user = useUserStore()
const friendStore = useFriendStore()

const overview = ref<FriendOverview | null>(null)
const conversations = ref<ConversationVO[]>([])
const loadingConversations = ref(false)

const searchId = ref('')
const searchMode = ref(false)
const searching = ref(false)
const searchResults = ref<UserCard[]>([])
/** 实际查过的那个 ID，用于"没有找到 ID 为 x 的用户"的提示 */
const searchedId = ref('')

const activePeer = ref<UserCard | null>(null)
const messages = ref<ChatMessageVO[]>([])
const cursor = ref(0)
const peerReadId = ref(0)
const hasMore = ref(false)
const loadingHistory = ref(false)
const sending = ref(false)
const draft = ref('')

const scrollBox = ref<HTMLElement>()

const incoming = computed(() => overview.value?.incoming ?? [])
const outgoing = computed(() => overview.value?.outgoing ?? [])

const slotTagType = computed(() => {
  const remaining = overview.value?.remaining ?? 0
  if (remaining <= 0) return 'danger'
  return remaining <= 5 ? 'warning' : 'info'
})

/** 自己的名片：聊天里自己的头像用（昵称/头像都取当前登录用户） */
const meCard = computed<UserCard>(() => ({
  userId: user.userId,
  username: user.username,
  displayName: user.displayName,
  nickname: null,
  realName: user.realName,
  className: null,
  avatarUrl: user.profile?.avatarUrl ?? null,
  avatarVersion: user.profile?.avatarVersion ?? null,
  signature: null,
  role: user.role,
  relation: 'SELF',
  requestedAt: null,
}))

// ------------------------------------------------ 数据加载

async function reloadOverview() {
  try {
    overview.value = await friendApi.overview()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '好友列表加载失败')
  }
}

async function reloadConversations() {
  loadingConversations.value = true
  try {
    conversations.value = await chatApi.conversations()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '会话列表加载失败')
  } finally {
    loadingConversations.value = false
  }
}

async function reloadAll() {
  await Promise.all([reloadOverview(), reloadConversations(), friendStore.refresh()])
}

// ------------------------------------------------ 搜索

/**
 * 按用户 ID 查找。
 *
 * <p>只接受 ID（服务端也是如此）：不做姓名/学号模糊搜，避免把"翻一遍全校人"
 * 变成一项功能 —— 详情见后端 {@code FriendRelationMapper.searchById} 的注释。
 * 所以这里先做一次整数校验，非法输入直接提示，不发请求。
 */
async function doSearch() {
  const raw = searchId.value.trim()
  const userId = Number(raw)
  if (!raw || !Number.isInteger(userId) || userId <= 0) {
    ElMessage.warning('请输入对方的用户 ID（正整数）')
    return
  }
  searching.value = true
  searchMode.value = true
  searchedId.value = raw
  try {
    searchResults.value = await friendApi.search(userId)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '查找失败')
    searchResults.value = []
  } finally {
    searching.value = false
  }
}

function clearSearch() {
  searchId.value = ''
  searchedId.value = ''
  searchResults.value = []
  searchMode.value = false
}

function focusSearch() {
  const input = document.querySelector<HTMLInputElement>('.side-head input')
  input?.focus()
}

// ------------------------------------------------ 好友操作

async function addFriend(card: UserCard) {
  try {
    overview.value = await friendApi.request(card.userId)
    // 搜索结果的 relation 已经是旧的了，必须重搜一次，
    // 否则按钮还显示"加好友"，用户会再点一次然后收到"已发送过申请"
    await doSearch()
    ElMessage.success(`已向 ${card.displayName} 发送好友申请`)
    // 对方此前已申请过我时，服务端会直接互相成为好友 —— 这时会话列表要跟着更新
    await reloadConversations()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '发送申请失败')
  }
}

async function handleRequest(card: UserCard, accept: boolean) {
  try {
    overview.value = await friendApi.handle(card.userId, accept)
    if (searchMode.value) {
      await doSearch()
    }
    await reloadConversations()
    await friendStore.refresh()
    ElMessage.success(accept ? `已和 ${card.displayName} 成为好友` : '已拒绝该申请')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '处理申请失败')
  }
}

async function removeFriend(card: UserCard) {
  try {
    await ElMessageBox.confirm(
      `删除后你们将不再是好友，聊天记录也不再展示，且需要重新申请才能加回来。`,
      `删除好友「${card.displayName}」`,
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    overview.value = await friendApi.remove(card.userId)
    if (activePeer.value?.userId === card.userId) {
      closeChat()
    }
    await reloadConversations()
    await friendStore.refresh()
    ElMessage.success('已删除好友')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

function onChatCommand(command: string) {
  if (command === 'remove' && activePeer.value) {
    void removeFriend(activePeer.value)
  }
}

// ------------------------------------------------ 聊天

async function openChat(card: UserCard) {
  if (activePeer.value?.userId === card.userId) {
    return
  }
  activePeer.value = card
  messages.value = []
  cursor.value = 0
  peerReadId.value = 0
  hasMore.value = false
  await loadLatest()
  // 进入会话即视为已读：把人放进会话里还要他再点一下"标记已读"是不合理的
  await markRead()
  scrollToBottom()
}

function closeChat() {
  activePeer.value = null
  messages.value = []
  cursor.value = 0
}

/** 首次进入：取最新一页 */
async function loadLatest() {
  const peer = activePeer.value
  if (!peer) return
  loadingHistory.value = true
  try {
    const thread = await chatApi.messages({ peerId: peer.userId })
    messages.value = thread.messages
    cursor.value = thread.maxId
    peerReadId.value = thread.lastReadIdByPeer
    hasMore.value = thread.hasMore
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '消息加载失败')
  } finally {
    loadingHistory.value = false
  }
}

/** 向上翻历史（保持当前滚动位置，否则内容会跳） */
async function loadHistory() {
  const peer = activePeer.value
  const oldest = messages.value[0]
  if (!peer || !oldest || loadingHistory.value) {
    return
  }
  loadingHistory.value = true
  const box = scrollBox.value
  const prevHeight = box?.scrollHeight ?? 0
  try {
    const thread = await chatApi.messages({ peerId: peer.userId, beforeId: oldest.id, size: 30 })
    const known = new Set(messages.value.map((m) => m.id))
    const older = thread.messages.filter((m) => !known.has(m.id))
    messages.value = [...older, ...messages.value]
    hasMore.value = thread.hasMore
    // 补完历史后把视口定位回原来的那条消息，避免"加载更多"导致跳到顶部
    await nextTick()
    if (box) {
      box.scrollTop = box.scrollHeight - prevHeight
    }
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '历史消息加载失败')
  } finally {
    loadingHistory.value = false
  }
}

/** 增量拉取（轮询）。只追加，不重置已有消息 */
async function pollNew() {
  const peer = activePeer.value
  if (!peer || document.hidden) {
    return
  }
  try {
    const thread = await chatApi.messages({ peerId: peer.userId, afterId: cursor.value })
    if (thread.messages.length) {
      const known = new Set(messages.value.map((m) => m.id))
      const fresh = thread.messages.filter((m) => !known.has(m.id))
      if (fresh.length) {
        const atBottom = isNearBottom()
        messages.value = [...messages.value, ...fresh]
        if (atBottom) {
          scrollToBottom()
        }
      }
      await markRead()
    }
    cursor.value = thread.maxId
    // 已读位置要跟着刷新：对方可能刚刚读了我之前发的消息
    peerReadId.value = thread.lastReadIdByPeer
    // 会话列表的最后一条消息也要更新，否则左侧栏一直显示旧内容
    await reloadConversations()
  } catch {
    // 轮询失败静默：网络抖一下不该弹窗，下一轮会自己补上
  }
}

async function markRead() {
  const peer = activePeer.value
  if (!peer) return
  try {
    const { updated } = await chatApi.markRead(peer.userId)
    if (updated > 0) {
      // 立刻把顶栏红点减掉，不必等 30 秒的轮询周期
      friendStore.consume(updated)
      const row = conversations.value.find((c) => c.peer.userId === peer.userId)
      if (row) {
        row.unread = 0
      }
    }
  } catch {
    // 已读标记失败不影响聊天本身
  }
}

async function sendMessage() {
  const peer = activePeer.value
  const content = draft.value.trim()
  if (!peer || !content || sending.value) {
    return
  }
  sending.value = true
  try {
    const saved = await chatApi.send(peer.userId, content)
    // 用服务端返回的实体而不是本地拼一条：id 与时间都以服务端为准，
    // 否则下轮增量拉取会因为 id 对不上而把这条消息重复插进来
    messages.value = [...messages.value, saved]
    cursor.value = Math.max(cursor.value, saved.id)
    draft.value = ''
    scrollToBottom()
    await reloadConversations()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '发送失败')
  } finally {
    sending.value = false
  }
}

// ------------------------------------------------ 滚动

function isNearBottom(): boolean {
  const box = scrollBox.value
  if (!box) return true
  return box.scrollHeight - box.scrollTop - box.clientHeight < 120
}

function scrollToBottom() {
  void nextTick(() => {
    const box = scrollBox.value
    if (box) {
      box.scrollTop = box.scrollHeight
    }
  })
}

function onScroll() {
  // 预留钩子：将来要做"滚动到顶自动加载更早的消息"时在这里判断
}

// ------------------------------------------------ 展示辅助

/** 只给"最新一条我发的消息"显示已读，避免每个气泡后面都挂两个字 */
function showReadFor(msg: ChatMessageVO): boolean {
  const lastMine = [...messages.value].reverse().find((m) => m.mine)
  return !!lastMine && lastMine.id === msg.id && peerReadId.value >= msg.id
}

function subtitleOf(card: UserCard): string {
  const parts: string[] = []
  if (card.className) parts.push(card.className)
  parts.push(`学号 ${card.username}`)
  if (card.signature) parts.push(card.signature)
  return parts.join(' · ')
}

/** 会话列表里的短时间：今天显示 HH:mm，更早显示 M-D */
function shortTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  const now = new Date()
  const sameDay =
    date.getFullYear() === now.getFullYear()
    && date.getMonth() === now.getMonth()
    && date.getDate() === now.getDate()
  if (sameDay) {
    return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
  }
  return `${date.getMonth() + 1}-${date.getDate()}`
}

function openHome(userId?: number) {
  if (userId) {
    void router.push(`/user/${userId}`)
  }
}

// ------------------------------------------------ 生命周期与联动

let pollTimer: number | undefined

function startPolling() {
  stopPolling()
  pollTimer = window.setInterval(() => void pollNew(), POLL_MS)
}

function stopPolling() {
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
}

/** 从别的页面（如用户主页点"发消息"）带着 enterChat 进来时，直接打开会话 */
async function openFromQuery() {
  const target = props.enterChat || Number(route.query.chat || 0)
  if (!target) {
    return
  }
  const existing = conversations.value.find((c) => c.peer.userId === target)
  if (existing) {
    await openChat(existing.peer)
    return
  }
  // 会话列表里没有（比如刚成为好友还没刷新）：直接查名片再开
  try {
    const card = await userApi.publicProfile(target)
    await openChat(card)
  } catch {
    // 查不到就算了，用户可以在列表里自己点
  }
}

watch(
  () => props.enterChat,
  () => void openFromQuery(),
)

onMounted(async () => {
  await reloadAll()
  await openFromQuery()
  startPolling()
})

onBeforeUnmount(stopPolling)
</script>

<style scoped>
.friends {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 340px 1fr;
  gap: 14px;
  padding: 16px var(--sc-gutter) 20px;
}

/* ---------------- 左栏 ---------------- */

.side {
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--sc-surface);
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius-lg);
  overflow: hidden;
}

.side-head {
  display: flex;
  gap: 8px;
  padding: 12px;
  border-bottom: 1px solid var(--sc-border);
}

.quota-line {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 9px 12px;
  font-size: 12.5px;
  border-bottom: 1px solid var(--sc-border);
  background: var(--sc-surface-2);
}

.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px 6px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--sc-text-3);
  text-transform: uppercase;
}

.req-block {
  border-bottom: 1px solid var(--sc-border);
  background: var(--sc-brand-softer);
}

.user-list {
  list-style: none;
  margin: 0;
  padding: 0 6px 8px;
  overflow-y: auto;
  min-height: 0;
}

.user-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 8px;
  border-radius: var(--sc-radius-sm);
  transition: var(--sc-transition);
}

.user-row:hover {
  background: var(--sc-hover);
}

.conv-row {
  cursor: pointer;
}

.conv-row.active {
  background: var(--sc-active);
}

.user-text {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  line-height: 1.35;
  cursor: pointer;
}

.user-text strong {
  font-size: 13.5px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-text small {
  font-size: 11.5px;
}

.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-action {
  display: flex;
  gap: 4px;
  flex: 0 0 auto;
}

.conv-time {
  font-size: 10.5px;
  flex: 0 0 auto;
}

.hint {
  font-size: 12px;
  margin: 0;
}

/* ---------------- 聊天区 ---------------- */

.chat {
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--sc-surface);
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius-lg);
  overflow: hidden;
}

.chat-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--sc-border);
  background: var(--sc-glass-strong);
}

.chat-title {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
  min-width: 0;
  flex: 1;
}

.chat-title strong {
  font-size: 14.5px;
  font-weight: 620;
}

.chat-title small {
  font-size: 11.5px;
}

.msg-box {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  background: var(--sc-surface-2);
}

.loading-line {
  text-align: center;
  font-size: 12px;
  padding: 2px 0;
}

.msg-row {
  display: flex;
  gap: 9px;
  align-items: flex-start;
}

.msg-row.mine {
  flex-direction: row-reverse;
}

.bubble-wrap {
  display: flex;
  flex-direction: column;
  gap: 3px;
  max-width: min(72%, 560px);
}

.msg-row.mine .bubble-wrap {
  align-items: flex-end;
}

.bubble {
  /* 只显示文字：换行按用户输入原样保留，但不解析任何标记 */
  white-space: pre-wrap;
  word-break: break-word;
  padding: 9px 12px;
  border-radius: var(--sc-radius);
  background: var(--sc-surface);
  border: 1px solid var(--sc-border);
  font-size: 13.5px;
  line-height: 1.55;
  color: var(--sc-text);
}

.msg-row.mine .bubble {
  background-image: var(--sc-gradient);
  border-color: transparent;
  color: #fff;
}

.bubble-meta {
  font-size: 10.5px;
  display: flex;
  gap: 6px;
}

.read-flag {
  color: var(--sc-brand);
}

.composer {
  border-top: 1px solid var(--sc-border);
  padding: 10px 14px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.composer-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.small {
  font-size: 11.5px;
}

.chat-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  text-align: center;
  padding: 30px;
}

.empty-art {
  width: 68px;
  height: 68px;
  border-radius: var(--sc-radius-lg);
  display: grid;
  place-items: center;
  font-size: 30px;
  color: var(--sc-brand);
  background: var(--sc-brand-soft);
}

.chat-empty p {
  margin: 0;
  font-size: 12.5px;
  max-width: 340px;
}

@media (max-width: 980px) {
  .friends {
    grid-template-columns: 300px 1fr;
  }
}
</style>
