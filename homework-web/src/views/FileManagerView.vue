<template>
  <div class="layout">
    <!-- ============ 顶部 ============ -->
    <header class="header">
      <div class="brand">
        <span class="logo">📚</span>
        <span class="title">作业云盘</span>
      </div>

      <el-input
        v-model="keyword"
        class="search"
        placeholder="搜索文件名（前缀匹配）"
        clearable
        :prefix-icon="Search"
        @keyup.enter="reload(1)"
        @clear="reload(1)"
      />

      <div class="quota" v-if="user.profile">
        <el-tooltip :content="`回收站占用 ${formatSize(user.profile.recycleUsed)}，清空可释放`">
          <span class="quota-text">
            {{ formatSize(user.profile.used) }} / {{ formatSize(user.profile.quota) }}
          </span>
        </el-tooltip>
        <el-progress
          :percentage="usedPercent"
          :stroke-width="6"
          :status="usedPercent > 90 ? 'exception' : undefined"
          :show-text="false"
          class="quota-bar"
        />
      </div>

      <el-button v-if="user.canEnterAdmin" link type="primary" @click="router.push('/admin')">
        <el-icon><Setting /></el-icon>
        <span>管理后台</span>
      </el-button>

      <SessionBadge />
    </header>

    <!-- 下课前倒计时提醒（VITE_CLASS_END_TIME 配置后生效） -->
    <el-alert
      v-if="showCheckoutWarning"
      class="checkout"
      type="error"
      :closable="false"
      show-icon
      :title="`距离下课还有 ${minutesLeft} 分钟，请确认文件已「保存到网盘」`"
      description="上传进度到 100% 还不算保存完成，必须看到「已保存到网盘」和提交凭证。"
    />

    <div class="body">
      <!-- ============ 左侧 ============ -->
      <aside class="sidebar">
        <div class="section-title">分类</div>
        <ul class="category">
          <li
            v-for="item in categories"
            :key="item.value"
            :class="{ active: mode === 'files' && category === item.value }"
            @click="switchCategory(item.value)"
          >
            <span>{{ item.label }}</span>
          </li>
          <li :class="{ active: mode === 'recycle' }" @click="switchToRecycle">
            <span>回收站</span>
            <el-badge v-if="recycleCount > 0" :value="recycleCount" class="badge" />
          </li>
        </ul>

        <div class="section-title">文件夹</div>
        <FolderTree :nodes="treeNodes" :current-id="currentParentId" @select="switchFolder" />
      </aside>

      <!-- ============ 中间 ============ -->
      <main class="main">
        <div class="toolbar">
          <template v-if="mode === 'files'">
            <el-button type="primary" @click="newFolderVisible = true">
              <el-icon><FolderAdd /></el-icon>
              <span>新建文件夹</span>
            </el-button>
            <el-button @click="pickFiles">
              <el-icon><Upload /></el-icon>
              <span>上传文件</span>
            </el-button>
            <el-button @click="pickFolder">
              <el-icon><FolderOpened /></el-icon>
              <span>上传文件夹</span>
            </el-button>
            <el-button
              type="danger"
              plain
              :disabled="selected.length === 0"
              @click="removeSelected"
            >
              <el-icon><Delete /></el-icon>
              <span>删除{{ selected.length ? `(${selected.length})` : '' }}</span>
            </el-button>
          </template>
          <template v-else>
            <el-button
              type="primary"
              plain
              :disabled="selected.length === 0"
              @click="restoreSelected"
            >
              <el-icon><RefreshLeft /></el-icon>
              <span>还原</span>
            </el-button>
            <el-button
              type="danger"
              plain
              :disabled="selected.length === 0"
              @click="purgeSelected"
            >
              <el-icon><Delete /></el-icon>
              <span>彻底删除</span>
            </el-button>
            <el-button type="danger" @click="emptyRecycle">清空回收站</el-button>
            <span class="recycle-hint">
              回收站中的文件仍占用容量（{{ formatSize(user.profile?.recycleUsed || 0) }}）
            </span>
          </template>

          <div class="spacer" />
          <el-button link :loading="loading" @click="reload()">
            <el-icon><Refresh /></el-icon>
            <span>刷新</span>
          </el-button>
        </div>

        <FileBreadcrumb v-if="mode === 'files'" :items="breadcrumb" @navigate="switchFolder" />
        <div v-else class="recycle-title">回收站</div>

        <div class="table-wrap">
          <FileTable
            :items="items"
            :loading="loading"
            @open="openFolder"
            @download="onDownload"
            @preview="onPreview"
            @rename="openRename"
            @move="openMove"
            @remove="onRemoveOne"
            @selection-change="onSelectionChange"
          />
        </div>

        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          class="pager"
          @current-change="() => reload()"
        />
      </main>

      <!-- ============ 右侧上传面板 ============ -->
      <aside class="uploader">
        <UploadPanel />
      </aside>
    </div>

    <!-- 隐藏的文件选择器 -->
    <input ref="fileInput" type="file" multiple hidden @change="onFilesPicked" />
    <input ref="folderInput" type="file" webkitdirectory hidden @change="onFolderPicked" />

    <!-- 新建文件夹 -->
    <el-dialog v-model="newFolderVisible" title="新建文件夹" width="400px">
      <el-input v-model="newFolderName" placeholder="请输入文件夹名称" @keyup.enter="createFolder" />
      <template #footer>
        <el-button @click="newFolderVisible = false">取消</el-button>
        <el-button type="primary" @click="createFolder">创建</el-button>
      </template>
    </el-dialog>

    <!-- 重命名 -->
    <el-dialog v-model="renameVisible" title="重命名" width="400px">
      <el-input v-model="renameName" placeholder="请输入新名称" @keyup.enter="doRename" />
      <template #footer>
        <el-button @click="renameVisible = false">取消</el-button>
        <el-button type="primary" @click="doRename">确定</el-button>
      </template>
    </el-dialog>

    <!-- 移动 -->
    <MoveDialog
      v-model="moveVisible"
      :nodes="treeNodes"
      :exclude-id="moveTarget?.id"
      :title="`移动「${moveTarget?.name || ''}」到`"
      @confirm="doMove"
    />

    <!-- 预览 -->
    <el-dialog v-model="previewVisible" :title="previewItem?.name" width="70%" top="5vh">
      <div class="preview-body">
        <img v-if="isImage" :src="previewUrl" alt="预览" />
        <iframe v-else :src="previewUrl" class="pdf" />
      </div>
    </el-dialog>

    <!-- 空闲自动登出提醒 -->
    <el-dialog :model-value="warnVisible" title="即将自动退出" width="380px" :close-on-click-modal="false">
      <p>检测到 {{ user.idleLogoutMinutes }} 分钟无操作，系统将在 {{ secondsLeft }} 秒后自动退出登录。</p>
      <p class="muted">这是为了防止机房共用电脑时，下一位同学进入你的网盘。</p>
      <template #footer>
        <el-button type="primary" @click="continueSession">继续使用</el-button>
        <el-button @click="doLogout">立即退出</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Delete,
  FolderAdd,
  FolderOpened,
  Refresh,
  RefreshLeft,
  Search,
  Setting,
  Upload,
} from '@element-plus/icons-vue'
import { fileApi } from '@/api'
import { ApiError } from '@/api/http'
import FileBreadcrumb from '@/components/FileBreadcrumb.vue'
import FileTable from '@/components/FileTable.vue'
import FolderTree from '@/components/FolderTree.vue'
import MoveDialog from '@/components/MoveDialog.vue'
import SessionBadge from '@/components/SessionBadge.vue'
import UploadPanel from '@/components/UploadPanel.vue'
import { useIdleLogout } from '@/composables/useIdleLogout'
import { useUploadStore } from '@/stores/uploader'
import { useUserStore } from '@/stores/user'
import type { BreadcrumbVO, FileItemVO, FolderNodeVO } from '@/types/api'
import { formatSize, triggerDownload } from '@/utils/format'

const router = useRouter()
const user = useUserStore()
const uploadStore = useUploadStore()
const { warnVisible, secondsLeft, continueSession } = useIdleLogout()

const categories = [
  { label: '全部文件', value: '' },
  { label: '图片', value: 'image' },
  { label: '文档', value: 'document' },
  { label: '视频', value: 'video' },
  { label: '音乐', value: 'audio' },
  { label: '其他', value: 'other' },
]

const mode = ref<'files' | 'recycle'>('files')
const category = ref('')
const keyword = ref('')
const currentParentId = ref(0)
const page = ref(1)
const size = ref(50)
const total = ref(0)
const items = ref<FileItemVO[]>([])
const selected = ref<FileItemVO[]>([])
const loading = ref(false)
const treeNodes = ref<FolderNodeVO[]>([])
const breadcrumb = ref<BreadcrumbVO[]>([])
const recycleCount = ref(0)

const fileInput = ref<HTMLInputElement>()
const folderInput = ref<HTMLInputElement>()

const newFolderVisible = ref(false)
const newFolderName = ref('')
const renameVisible = ref(false)
const renameTarget = ref<FileItemVO | null>(null)
const renameName = ref('')
const moveVisible = ref(false)
const moveTarget = ref<FileItemVO | null>(null)
const previewVisible = ref(false)
const previewItem = ref<FileItemVO | null>(null)
const previewUrl = ref('')

const isImage = computed(() => {
  const suffix = (previewItem.value?.suffix || '').toLowerCase()
  return ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp'].includes(suffix)
})

const usedPercent = computed(() => {
  const profile = user.profile
  if (!profile || !profile.quota) {
    return 0
  }
  return Math.min(100, Math.round((profile.used / profile.quota) * 100))
})

// ------------------------------------------------ 下课前提醒

const classEnd = import.meta.env.VITE_CLASS_END_TIME || ''
const minutesLeft = ref<number | null>(null)
let classTimer: number | undefined

function tickClassTime() {
  if (!classEnd) {
    return
  }
  const [hh, mm] = classEnd.split(':').map((v) => Number(v))
  if (Number.isNaN(hh) || Number.isNaN(mm)) {
    return
  }
  const now = new Date()
  const end = new Date(now)
  end.setHours(hh, mm, 0, 0)
  const diff = Math.floor((end.getTime() - now.getTime()) / 60000)
  minutesLeft.value = diff
}

const showCheckoutWarning = computed(
  () =>
    minutesLeft.value !== null
    && minutesLeft.value > 0
    && minutesLeft.value <= (user.checkoutWarnMinutes || 5),
)

// ------------------------------------------------ 数据加载

async function loadTree() {
  try {
    treeNodes.value = await fileApi.tree()
  } catch {
    treeNodes.value = []
  }
}

async function loadBreadcrumb() {
  if (mode.value !== 'files' || currentParentId.value === 0) {
    breadcrumb.value = []
    return
  }
  try {
    breadcrumb.value = await fileApi.breadcrumb(currentParentId.value)
  } catch {
    breadcrumb.value = []
  }
}

async function loadList() {
  loading.value = true
  try {
    if (mode.value === 'recycle') {
      const result = await fileApi.recycleList(page.value, size.value)
      items.value = result.records
      total.value = result.total
      recycleCount.value = result.total
    } else {
      const result = await fileApi.list({
        parentId: currentParentId.value,
        category: category.value || undefined,
        keyword: keyword.value || undefined,
        page: page.value,
        size: size.value,
      })
      items.value = result.records
      total.value = result.total
    }
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载列表失败')
    items.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function reload(toPage?: number) {
  if (toPage) {
    page.value = toPage
  }
  await Promise.all([loadList(), loadBreadcrumb()])
}

function switchFolder(id: number) {
  mode.value = 'files'
  currentParentId.value = id
  page.value = 1
  selected.value = []
  void reload()
}

function switchCategory(value: string) {
  mode.value = 'files'
  category.value = value
  page.value = 1
  void reload(1)
}

function switchToRecycle() {
  mode.value = 'recycle'
  page.value = 1
  selected.value = []
  void reload()
}

function openFolder(row: FileItemVO) {
  switchFolder(row.id)
}

function onSelectionChange(rows: FileItemVO[]) {
  selected.value = rows
}

// ------------------------------------------------ 上传

function pickFiles() {
  fileInput.value?.click()
}

function pickFolder() {
  folderInput.value?.click()
}

function onFilesPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  if (files.length) {
    uploadStore.enqueue(files, currentParentId.value)
  }
  input.value = ''
}

/** 文件夹上传：先按相对路径建目录，再逐个入队 */
async function onFolderPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  input.value = ''
  if (!files.length) {
    return
  }

  const rows = files.map((file) => ({
    file,
    rel: (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name,
  }))

  // 收集所有需要创建的目录（按层级从浅到深）
  const dirs = new Set<string>()
  rows.forEach((row) => {
    const parts = row.rel.split('/')
    parts.pop()
    let acc = ''
    parts.forEach((part) => {
      acc = acc ? `${acc}/${part}` : part
      dirs.add(acc)
    })
  })
  const ordered = Array.from(dirs).sort((a, b) => a.split('/').length - b.split('/').length)

  const dirIdMap = new Map<string, number>()
  try {
    for (const dir of ordered) {
      const parentRel = dir.includes('/') ? dir.slice(0, dir.lastIndexOf('/')) : ''
      const parentId = parentRel ? dirIdMap.get(parentRel) ?? currentParentId.value : currentParentId.value
      const name = dir.slice(dir.lastIndexOf('/') + 1)
      const existed = await findExistingFolder(parentId, name)
      dirIdMap.set(dir, existed ?? (await fileApi.createFolder(parentId, name)))
    }
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '创建目录失败，已上传部分文件')
  }

  for (const row of rows) {
    const dirRel = row.rel.includes('/') ? row.rel.slice(0, row.rel.lastIndexOf('/')) : ''
    const parentId = dirRel ? dirIdMap.get(dirRel) ?? currentParentId.value : currentParentId.value
    uploadStore.enqueue([row.file], parentId)
  }
  await loadTree()
}

async function findExistingFolder(parentId: number, name: string): Promise<number | null> {
  try {
    const result = await fileApi.list({ parentId, keyword: name, page: 1, size: 200 })
    const hit = result.records.find((item) => item.folder && item.name === name)
    return hit ? hit.id : null
  } catch {
    return null
  }
}

// 上传完成后自动刷新列表与容量
watch(
  () => uploadStore.finishedCount,
  async (value, oldValue) => {
    if (value > (oldValue || 0)) {
      await loadTree()
      await loadList()
      await user.loadProfile()
    }
  },
)

// ------------------------------------------------ 文件操作

async function createFolder() {
  const name = newFolderName.value.trim()
  if (!name) {
    ElMessage.warning('请输入文件夹名称')
    return
  }
  try {
    await fileApi.createFolder(currentParentId.value, name)
    newFolderVisible.value = false
    newFolderName.value = ''
    await loadTree()
    await loadList()
    ElMessage.success('文件夹已创建')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '创建失败')
  }
}

function openRename(row: FileItemVO) {
  renameTarget.value = row
  renameName.value = row.name
  renameVisible.value = true
}

async function doRename() {
  const target = renameTarget.value
  const name = renameName.value.trim()
  if (!target || !name) {
    return
  }
  try {
    await fileApi.rename(target.id, name)
    renameVisible.value = false
    await loadList()
    ElMessage.success('已重命名')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '重命名失败')
  }
}

function openMove(row: FileItemVO) {
  moveTarget.value = row
  moveVisible.value = true
}

async function doMove(targetParentId: number) {
  const target = moveTarget.value
  if (!target) {
    return
  }
  try {
    await fileApi.move(target.id, targetParentId)
    await loadTree()
    await loadList()
    ElMessage.success('已移动')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '移动失败')
  }
}

async function onDownload(row: FileItemVO) {
  try {
    const url = await fileApi.downloadUrl(row.id)
    triggerDownload(url)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '获取下载地址失败')
  }
}

async function onPreview(row: FileItemVO) {
  try {
    previewUrl.value = await fileApi.previewUrl(row.id)
    previewItem.value = row
    previewVisible.value = true
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '该文件不支持预览')
  }
}

async function onRemoveOne(row: FileItemVO) {
  await removeItems([row.id], `确定把「${row.name}」放入回收站吗？`)
}

async function removeSelected() {
  await removeItems(
    selected.value.map((item) => item.id),
    `确定把选中的 ${selected.value.length} 项放入回收站吗？`,
  )
}

async function removeItems(ids: number[], question: string) {
  if (!ids.length) {
    return
  }
  try {
    await ElMessageBox.confirm(`${question}\n可在回收站还原，30 天后自动清理。`, '确认删除', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await fileApi.softDelete(ids)
    await loadTree()
    await loadList()
    ElMessage.success('已放入回收站')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

// ------------------------------------------------ 回收站

async function restoreSelected() {
  const ids = selected.value.map((item) => item.id)
  if (!ids.length) {
    return
  }
  try {
    await fileApi.restore(ids)
    await loadTree()
    await loadList()
    ElMessage.success('已还原')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '还原失败')
  }
}

async function purgeSelected() {
  const ids = selected.value.map((item) => item.id)
  if (!ids.length) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `将彻底删除选中的 ${ids.length} 项，文件本体也会从 OSS 删除，且无法恢复。`,
      '彻底删除',
      { type: 'error', confirmButtonText: '彻底删除' },
    )
  } catch {
    return
  }
  try {
    await fileApi.purge(ids)
    await loadList()
    await user.loadProfile()
    ElMessage.success('已彻底删除')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

async function emptyRecycle() {
  try {
    await ElMessageBox.confirm('将清空回收站并释放容量，此操作不可恢复。', '清空回收站', {
      type: 'error',
    })
  } catch {
    return
  }
  try {
    const count = await fileApi.emptyRecycle()
    await loadList()
    await user.loadProfile()
    ElMessage.success(`已清空 ${count} 项`)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '清空失败')
  }
}

// ------------------------------------------------ 生命周期

function onBeforeUnload(event: BeforeUnloadEvent) {
  if (uploadStore.hasUnfinished) {
    event.preventDefault()
    event.returnValue = ''
  }
}

async function doLogout() {
  await user.logout()
  location.assign('/login')
}

onMounted(async () => {
  if (!user.isLoggedIn) {
    await router.replace('/login')
    return
  }
  try {
    await user.loadProfile()
  } catch {
    /* 401 已由拦截器处理 */
  }
  await Promise.all([loadTree(), reload()])
  window.addEventListener('beforeunload', onBeforeUnload)
  tickClassTime()
  if (classEnd) {
    classTimer = window.setInterval(tickClassTime, 30_000)
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', onBeforeUnload)
  if (classTimer) {
    window.clearInterval(classTimer)
  }
})
</script>

<style scoped>
.layout {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #fff;
}

.header {
  height: var(--sc-header-h);
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 0 16px;
  border-bottom: 1px solid #ebeef5;
}

.brand {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 17px;
  font-weight: 700;
  white-space: nowrap;
}

.search {
  max-width: 320px;
}

.quota {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 150px;
}

.quota-text {
  font-size: 12px;
  color: #606266;
}

.quota-bar {
  width: 150px;
}

.checkout {
  border-radius: 0;
}

.body {
  flex: 1;
  display: flex;
  min-height: 0;
}

.sidebar {
  width: 220px;
  flex: 0 0 220px;
  border-right: 1px solid #ebeef5;
  padding: 8px 0;
  overflow: auto;
}

.section-title {
  padding: 8px 16px 4px;
  font-size: 12px;
  color: #909399;
}

.category {
  list-style: none;
  margin: 0;
  padding: 0;
}

.category li {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  cursor: pointer;
  font-size: 14px;
}

.category li:hover {
  background: #f5f7fa;
}

.category li.active {
  background: #ecf5ff;
  color: #409eff;
  font-weight: 600;
}

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  padding: 12px 16px;
  gap: 10px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.spacer {
  flex: 1;
}

.recycle-hint {
  font-size: 12px;
  color: #e6a23c;
}

.recycle-title {
  font-weight: 600;
}

.table-wrap {
  flex: 1;
  min-height: 0;
}

.pager {
  justify-content: flex-end;
}

.uploader {
  width: 330px;
  flex: 0 0 330px;
  border-left: 1px solid #ebeef5;
  overflow: hidden;
}

.preview-body {
  max-height: 70vh;
  overflow: auto;
  text-align: center;
}

.preview-body img {
  max-width: 100%;
}

.pdf {
  width: 100%;
  height: 70vh;
  border: none;
}

.muted {
  color: #909399;
  font-size: 12px;
}
</style>
