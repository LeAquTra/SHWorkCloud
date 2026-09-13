<template>
  <div
    class="fm"
    @dragenter.prevent="onDragEnter"
    @dragover.prevent="onDragOver"
    @dragleave.prevent="onDragLeave"
    @drop.prevent="onDrop"
  >
    <!-- ============ 左侧：分类与目录 ============ -->
    <aside class="rail sc-scroll-y">
      <div class="rail-block">
        <p class="sc-section-title"><span class="sc-dot" />分类</p>
        <ul class="rail-list">
          <li
            v-for="item in categories"
            :key="item.value"
            :class="{ active: mode === 'files' && category === item.value }"
            @click="switchCategory(item.value)"
          >
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
          </li>
        </ul>
      </div>

      <div class="rail-block">
        <p class="sc-section-title"><span class="sc-dot" />回收站</p>
        <ul class="rail-list">
          <li :class="{ active: mode === 'recycle' }" @click="gotoRecycle">
            <el-icon><Delete /></el-icon>
            <span>回收站</span>
            <el-tag v-if="recycleCount > 0" size="small" type="warning" effect="light">
              {{ recycleCount }}
            </el-tag>
          </li>
        </ul>
      </div>

      <div class="rail-block tree-block">
        <p class="sc-section-title"><span class="sc-dot" />文件夹</p>
        <FolderTree
          :nodes="treeNodes"
          :current-id="mode === 'files' ? currentParentId : undefined"
          @select="switchFolder"
        />
      </div>
    </aside>

    <!-- ============ 中间：列表 ============ -->
    <main class="main">
      <!-- 下课前提醒（配置 VITE_CLASS_END_TIME 后生效） -->
      <el-alert
        v-if="showCheckoutWarning"
        class="checkout"
        type="error"
        :closable="false"
        show-icon
        :title="`距离下课还有 ${minutesLeft} 分钟，请确认文件已「保存到网盘」`"
        description="上传进度到 100% 还不算保存完成，必须看到「已保存到网盘」和提交凭证。"
      />

      <div class="toolbar">
        <div class="tool-left">
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
            <el-button v-if="selected.length" type="danger" plain @click="removeSelected">
              <el-icon><Delete /></el-icon>
              <span>删除 {{ selected.length }} 项</span>
            </el-button>
            <el-button v-if="selected.length" plain @click="moveSelected">
              <el-icon><Rank /></el-icon>
              <span>移动</span>
            </el-button>
          </template>

          <template v-else>
            <el-button type="primary" plain :disabled="!selected.length" @click="restoreSelected">
              <el-icon><RefreshLeft /></el-icon>
              <span>还原</span>
            </el-button>
            <el-button type="danger" plain :disabled="!selected.length" @click="purgeSelected">
              <el-icon><Delete /></el-icon>
              <span>彻底删除</span>
            </el-button>
            <el-button type="danger" @click="emptyRecycle">清空回收站</el-button>
          </template>
        </div>

        <div class="tool-right">
          <el-input
            v-model="keyword"
            class="search"
            placeholder="输入文件名开头几个字"
            clearable
            :prefix-icon="Search"
            @keyup.enter="reload(1)"
            @clear="reload(1)"
          />
          <el-button :loading="loading" circle :title="'刷新'" @click="reload()">
            <el-icon><Refresh /></el-icon>
          </el-button>
        </div>
      </div>

      <div class="sub-bar">
        <FileBreadcrumb v-if="mode === 'files'" :items="breadcrumb" @navigate="switchFolder" />
        <div v-else class="recycle-note">
          <el-icon><InfoFilled /></el-icon>
          <span>
            回收站中的文件仍占用容量（{{ formatSize(user.profile?.recycleUsed || 0) }}），
            清空后才会释放。
          </span>
        </div>
        <div class="spacer" />
        <span class="sc-muted sc-tabular">共 {{ total }} 项</span>
      </div>

      <section class="list-surface">
        <FileTable
          :items="items"
          :loading="loading"
          :mode="mode"
          @open="openFolder"
          @download="onDownload"
          @preview="onPreview"
          @rename="openRename"
          @move="openMove"
          @copy="onCopy"
          @remove="onRemoveOne"
          @restore="restoreOne"
          @purge="purgeOne"
          @selection-change="onSelectionChange"
        >
          <template #empty>
            <div class="empty">
              <div class="empty-art">
                <el-icon><component :is="mode === 'recycle' ? Delete : FolderOpened" /></el-icon>
              </div>
              <strong>{{ mode === 'recycle' ? '回收站是空的' : emptyTitle }}</strong>
              <p class="sc-muted">{{ mode === 'recycle' ? '删除的文件会先放到这里' : emptyHint }}</p>
              <el-button v-if="mode === 'files' && !keyword" type="primary" @click="pickFiles">
                <el-icon><Upload /></el-icon>
                <span>上传第一个文件</span>
              </el-button>
            </div>
          </template>
        </FileTable>
      </section>

      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @current-change="() => reload()"
        @size-change="() => reload(1)"
      />
    </main>

    <!-- ============ 右侧：传输列表 ============ -->
    <aside class="panel">
      <UploadPanel />
    </aside>

    <!-- 拖拽遮罩 -->
    <transition name="fade">
      <div v-if="dragging" class="drop-overlay">
        <div class="drop-inner">
          <el-icon><UploadFilled /></el-icon>
          <strong>松开即可上传到「{{ currentFolderName }}」</strong>
          <span class="sc-muted">支持同时拖入多个文件或整个文件夹</span>
        </div>
      </div>
    </transition>

    <!-- 隐藏的文件选择器 -->
    <input ref="fileInput" type="file" multiple hidden @change="onFilesPicked" />
    <input ref="folderInput" type="file" webkitdirectory hidden @change="onFolderPicked" />

    <!-- 新建文件夹 -->
    <el-dialog v-model="newFolderVisible" title="新建文件夹" width="400px">
      <el-input
        v-model="newFolderName"
        size="large"
        placeholder="请输入文件夹名称"
        maxlength="64"
        show-word-limit
        @keyup.enter="createFolder"
      />
      <template #footer>
        <el-button @click="newFolderVisible = false">取消</el-button>
        <el-button type="primary" @click="createFolder">创建</el-button>
      </template>
    </el-dialog>

    <!-- 重命名 -->
    <el-dialog v-model="renameVisible" title="重命名" width="400px">
      <el-input
        v-model="renameName"
        size="large"
        placeholder="请输入新名称"
        maxlength="128"
        @keyup.enter="doRename"
      />
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
      :title="moveTitle"
      @confirm="doMove"
    />

    <!-- 在线阅览 -->
    <PreviewDialog v-model="previewVisible" :item="previewItem" />

    <!-- 空闲自动登出提醒 -->
    <el-dialog
      :model-value="warnVisible"
      title="即将自动退出"
      width="400px"
      :close-on-click-modal="false"
      align-center
    >
      <p>
        检测到 {{ user.idleLogoutMinutes }} 分钟无操作，系统将在
        <strong class="warn-num">{{ secondsLeft }}</strong> 秒后自动退出登录。
      </p>
      <p class="sc-muted small">
        这是为了防止机房共用电脑时，下一位同学进入你的网盘。有任何操作（鼠标移动、按键）都会重新计时。
      </p>
      <template #footer>
        <el-button type="primary" @click="continueSession">继续使用</el-button>
        <el-button @click="doLogout">立即退出</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Box,
  Delete,
  Document,
  Files,
  FolderAdd,
  FolderOpened,
  Headset,
  InfoFilled,
  Picture,
  Rank,
  Refresh,
  RefreshLeft,
  Search,
  Upload,
  UploadFilled,
  VideoCamera,
} from '@element-plus/icons-vue'
import FileBreadcrumb from '@/components/FileBreadcrumb.vue'
import FileTable from '@/components/FileTable.vue'
import FolderTree from '@/components/FolderTree.vue'
import MoveDialog from '@/components/MoveDialog.vue'
import PreviewDialog from '@/components/PreviewDialog.vue'
import UploadPanel from '@/components/UploadPanel.vue'
import { fileApi, userApi } from '@/api'
import { ApiError } from '@/api/http'
import { useIdleLogout } from '@/composables/useIdleLogout'
import { useUploadStore } from '@/stores/uploader'
import { useUserStore } from '@/stores/user'
import type { BreadcrumbVO, FileItemVO, FolderNodeVO } from '@/types/api'
import { formatSize } from '@/utils/format'

const props = withDefaults(defineProps<{ initialMode?: 'files' | 'recycle' }>(), {
  initialMode: 'files',
})

const router = useRouter()
const route = useRoute()
const user = useUserStore()
const uploadStore = useUploadStore()
const { warnVisible, secondsLeft, continueSession } = useIdleLogout()

const categories = [
  { label: '全部文件', value: '', icon: Files },
  { label: '图片', value: 'image', icon: Picture },
  { label: '文档', value: 'document', icon: Document },
  { label: '视频', value: 'video', icon: VideoCamera },
  { label: '音乐', value: 'audio', icon: Headset },
  { label: '其他', value: 'other', icon: Box },
]

const mode = ref<'files' | 'recycle'>(props.initialMode)
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

// ------------------------------------------------ 派生状态

const currentFolderName = computed(() => {
  const last = breadcrumb.value[breadcrumb.value.length - 1]
  return last?.name || '全部文件'
})

const emptyTitle = computed(() =>
  keyword.value ? '没有匹配的文件' : category.value ? '这个分类下还没有文件' : '这个文件夹还是空的',
)

const emptyHint = computed(() =>
  keyword.value
    ? '搜索是前缀匹配。试试只输入文件名开头的几个字。'
    : '把文件拖到这里，或点上面的「上传文件」。',
)

const moveTitle = computed(() => {
  if (selected.value.length > 1 && !moveTarget.value) {
    return `移动选中的 ${selected.value.length} 项到`
  }
  return `移动「${moveTarget.value?.name || ''}」到`
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
  minutesLeft.value = Math.floor((end.getTime() - now.getTime()) / 60000)
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
    selected.value = []
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

async function switchFolder(id: number) {
  currentParentId.value = id
  page.value = 1
  selected.value = []
  if (mode.value === 'recycle') {
    // 让路由先回到文件列表，路由 watch 会用刚设好的 currentParentId 重新加载
    mode.value = 'files'
    await router.push('/')
    return
  }
  await reload()
}

async function switchCategory(value: string) {
  category.value = value
  page.value = 1
  if (value === '') {
    currentParentId.value = 0
  }
  if (mode.value === 'recycle') {
    mode.value = 'files'
    await router.push('/')
    return
  }
  await reload(1)
}

function gotoRecycle() {
  void router.push('/recycle')
}

function openFolder(row: FileItemVO) {
  switchFolder(row.id)
}

function onSelectionChange(rows: FileItemVO[]) {
  selected.value = rows
}

// 同一个组件被 / 与 /recycle 复用，路由变化时手动同步模式；
// 相册页的「跳转到所在文件夹」通过 ?folder=ID 把目标目录带进来
watch(
  () => [route.name, route.query.folder] as const,
  ([name, folder]) => {
    if (name !== 'files' && name !== 'recycle') {
      return
    }
    mode.value = name === 'recycle' ? 'recycle' : 'files'
    if (name === 'files' && folder) {
      const id = Number(folder)
      if (Number.isFinite(id) && id > 0) {
        currentParentId.value = id
      }
    }
    page.value = 1
    selected.value = []
    void reload()
  },
)

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
  input.value = ''
  if (!files.length) {
    return
  }
  void startUpload(files)
}

/** 空间不够就先问一句；返回 true 表示"这件事已经有人接管了，别再入队" */
async function startUpload(files: File[]): Promise<void> {
  const handled = await checkQuota(files)
  if (!handled) {
    uploadStore.enqueue(files, currentParentId.value)
  }
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
  await enqueueWithTree(rows)
}

/**
 * 按相对路径建好目录树后再入队。
 * 拖拽文件夹与 webkitdirectory 选择共用这一段逻辑。
 */
async function enqueueWithTree(rows: { file: File; rel: string }[]) {
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
      const parentId = parentRel
        ? dirIdMap.get(parentRel) ?? currentParentId.value
        : currentParentId.value
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

/** 空间不够就别开始传：否则一整车文件都会停在"存储空间不足"的失败态 */
async function checkQuota(files: File[]): Promise<boolean> {
  const totalBytes = files.reduce((sum, file) => sum + file.size, 0)
  try {
    const quota = await userApi.quota()
    if (totalBytes <= quota.free) {
      return false
    }
    try {
      await ElMessageBox.confirm(
        `本次共 ${formatSize(totalBytes)}，剩余空间 ${formatSize(quota.free)}。`
          + `回收站还占着 ${formatSize(quota.recycleUsed)}，清空可以立刻释放。`,
        '空间可能不足',
        { type: 'warning', confirmButtonText: '去清空回收站', cancelButtonText: '仍然上传' },
      )
      await router.push('/recycle')
    } catch {
      // 用户选择"仍然上传"：交给服务端最终判定，失败会落在任务列表里
      uploadStore.enqueue(files, currentParentId.value)
    }
    return true
  } catch {
    // 查容量失败不阻塞上传
    return false
  }
}

// 上传完成后自动刷新列表与容量
watch(
  () => uploadStore.finishedCount,
  async (value, oldValue) => {
    if (value > (oldValue || 0)) {
      await loadTree()
      await loadList()
      try {
        await user.loadProfile()
      } catch {
        /* 忽略：容量刷新失败不影响主流程 */
      }
    }
  },
)

// ------------------------------------------------ 拖拽上传

let dragDepth = 0
const dragging = ref(false)

function hasFiles(event: DragEvent): boolean {
  return Array.from(event.dataTransfer?.types || []).includes('Files')
}

function onDragEnter(event: DragEvent) {
  if (!hasFiles(event)) {
    return
  }
  dragDepth += 1
  dragging.value = true
}

function onDragOver(event: DragEvent) {
  if (hasFiles(event) && event.dataTransfer) {
    event.dataTransfer.dropEffect = 'copy'
  }
}

function onDragLeave() {
  dragDepth = Math.max(0, dragDepth - 1)
  if (dragDepth === 0) {
    dragging.value = false
  }
}

async function onDrop(event: DragEvent) {
  dragDepth = 0
  dragging.value = false
  const dataTransfer = event.dataTransfer
  if (!dataTransfer) {
    return
  }

  if (mode.value === 'recycle') {
    ElMessage.warning('回收站里不能上传文件')
    return
  }

  // 优先走 FileSystemEntry：这样才能把整个文件夹连同层级一起拖进来
  const entries = Array.from(dataTransfer.items || [])
    .map((item) => (item.webkitGetAsEntry ? item.webkitGetAsEntry() : null))
    .filter(Boolean) as FileSystemEntry[]

  if (entries.length) {
    try {
      const rows: { file: File; rel: string }[] = []
      for (const entry of entries) {
        await readEntry(entry, '', rows)
      }
      if (rows.length) {
        await enqueueWithTree(rows)
        return
      }
    } catch {
      // 读目录失败就退回普通文件列表
    }
  }

  const files = Array.from(dataTransfer.files || [])
  if (files.length) {
    uploadStore.enqueue(files, currentParentId.value)
  }
}

/** 递归读取拖入的目录；readEntries 一次最多回 100 条，必须循环取到空 */
async function readEntry(
  entry: FileSystemEntry,
  prefix: string,
  rows: { file: File; rel: string }[],
): Promise<void> {
  if (entry.isFile) {
    const file = await new Promise<File>((resolve, reject) => {
      ;(entry as FileSystemFileEntry).file(resolve, reject)
    })
    rows.push({ file, rel: prefix ? `${prefix}/${file.name}` : file.name })
    return
  }
  if (entry.isDirectory) {
    const reader = (entry as FileSystemDirectoryEntry).createReader()
    const dirPrefix = prefix ? `${prefix}/${entry.name}` : entry.name
    for (;;) {
      const batch = await new Promise<FileSystemEntry[]>((resolve, reject) => {
        reader.readEntries(resolve, reject)
      })
      if (!batch.length) {
        break
      }
      for (const child of batch) {
        await readEntry(child, dirPrefix, rows)
      }
    }
  }
}

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
  if (!target || !name || name === target.name) {
    renameVisible.value = false
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

function moveSelected() {
  moveTarget.value = null
  moveVisible.value = true
}

async function doMove(targetParentId: number) {
  const targets = moveTarget.value ? [moveTarget.value] : selected.value
  if (!targets.length) {
    return
  }
  // 后端一次只移动一个节点，这里串行提交，避免同名前缀互相干扰
  const failed: string[] = []
  for (const item of targets) {
    try {
      await fileApi.move(item.id, targetParentId)
    } catch (error) {
      failed.push(`${item.name}：${error instanceof ApiError ? error.message : '失败'}`)
    }
  }
  await loadTree()
  await loadList()
  if (failed.length) {
    ElMessage.error(`${failed.length} 个未能移动：${failed[0]}`)
  } else {
    ElMessage.success(targets.length > 1 ? `已移动 ${targets.length} 项` : '已移动')
  }
}

async function onCopy(row: FileItemVO) {
  const dot = row.name.lastIndexOf('.')
  const base = dot > 0 ? row.name.slice(0, dot) : row.name
  const ext = dot > 0 ? row.name.slice(dot) : ''
  try {
    const { value } = await ElMessageBox.prompt('新副本的名称', '创建副本', {
      inputValue: `${base} - 副本${ext}`,
      inputPlaceholder: '留空则由服务端自动命名',
      confirmButtonText: '创建',
      cancelButtonText: '取消',
    })
    await fileApi.copy(row.id, currentParentId.value, (value || '').trim() || undefined)
    await loadTree()
    await loadList()
    ElMessage.success('副本已创建')
  } catch (error) {
    if (error === 'cancel' || error === 'close') {
      return
    }
    ElMessage.error(error instanceof ApiError ? error.message : '创建副本失败')
  }
}

async function onDownload(row: FileItemVO) {
  try {
    const { url } = await fileApi.downloadUrl(row.id)
    const a = document.createElement('a')
    a.href = url
    a.rel = 'noopener'
    a.target = '_blank'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '获取下载地址失败')
  }
}

function onPreview(row: FileItemVO) {
  previewItem.value = row
  previewVisible.value = true
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
    await ElMessageBox.confirm(`${question}\n可在回收站还原。`, '确认删除', { type: 'warning' })
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

async function restoreOne(row: FileItemVO) {
  await restoreIds([row.id])
}

async function restoreSelected() {
  await restoreIds(selected.value.map((item) => item.id))
}

async function restoreIds(ids: number[]) {
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

async function purgeOne(row: FileItemVO) {
  await purgeIds([row.id])
}

async function purgeSelected() {
  await purgeIds(selected.value.map((item) => item.id))
}

async function purgeIds(ids: number[]) {
  if (!ids.length) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `将彻底删除 ${ids.length} 项，文件本体也会从对象存储删除，且无法恢复。`,
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
      confirmButtonText: '清空',
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

/** 上传队列里有未完成任务时离开页面要拦截：下课前最容易漏掉这一步 */
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
  // 从相册跳过来时带 ?folder=ID，直接落在目标目录
  const folderQuery = Number(route.query.folder)
  if (Number.isFinite(folderQuery) && folderQuery > 0) {
    currentParentId.value = folderQuery
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
.fm {
  flex: 1;
  min-height: 0;
  display: flex;
  position: relative;
}

/* ---------------- 左侧 rail ---------------- */

.rail {
  width: var(--sc-rail-w);
  flex: 0 0 var(--sc-rail-w);
  padding: 16px 10px 24px;
  border-right: 1px solid var(--sc-border);
  background: var(--sc-glass);
  backdrop-filter: blur(14px);
}

.rail-block + .rail-block {
  margin-top: 18px;
}

.rail-block .sc-section-title {
  padding: 0 10px;
}

.rail-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.rail-list li {
  display: flex;
  align-items: center;
  gap: 9px;
  height: 36px;
  padding: 0 11px;
  margin: 2px 0;
  border-radius: var(--sc-radius-sm);
  font-size: 13.5px;
  color: var(--sc-text-2);
  cursor: pointer;
  transition: var(--sc-transition);
}

.rail-list li:hover {
  background: var(--sc-hover);
  color: var(--sc-text);
}

.rail-list li.active {
  background: var(--sc-brand-soft);
  color: var(--sc-brand);
  font-weight: 620;
}

.rail-list li span {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tree-block {
  min-height: 0;
}

/* ---------------- 中间 ---------------- */

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  padding: 16px var(--sc-gutter);
  gap: 12px;
}

.checkout {
  margin: 0;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.tool-left,
.tool-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tool-right {
  margin-left: auto;
}

.search {
  width: 240px;
}

.sub-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 28px;
}

.sub-bar .spacer {
  flex: 1;
}

.recycle-note {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12.5px;
  color: var(--sc-warning);
}

.list-surface {
  flex: 1;
  min-height: 0;
  border: 1px solid var(--sc-border);
  border-radius: var(--sc-radius-lg);
  background: var(--sc-surface);
  box-shadow: var(--sc-shadow-sm);
  overflow: hidden;
}

.list-surface :deep(.el-table) {
  --el-table-border-color: transparent;
  --el-table-header-bg-color: var(--sc-surface-2);
}

.pager {
  justify-content: flex-end;
}

/* ---------------- 空态 ---------------- */

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 54px 20px;
}

.empty-art {
  width: 76px;
  height: 76px;
  border-radius: var(--sc-radius-xl);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 32px;
  color: var(--sc-brand);
  background-image: var(--sc-gradient-veil);
  border: 1px solid var(--sc-border);
  margin-bottom: 8px;
}

.empty strong {
  font-size: 14.5px;
}

.empty p {
  margin: 0 0 12px;
  font-size: 12.5px;
}

/* ---------------- 右侧面板 ---------------- */

.panel {
  width: var(--sc-panel-w);
  flex: 0 0 var(--sc-panel-w);
  border-left: 1px solid var(--sc-border);
  background: var(--sc-glass);
  backdrop-filter: blur(14px);
  min-height: 0;
}

/* ---------------- 拖拽遮罩 ---------------- */

.drop-overlay {
  position: absolute;
  inset: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--sc-canvas) 78%, transparent);
  backdrop-filter: blur(6px);
}

.drop-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 46px 60px;
  border-radius: var(--sc-radius-xl);
  border: 2px dashed var(--sc-brand);
  background: var(--sc-brand-softer);
  animation: sc-pop var(--sc-dur) var(--sc-ease) both;
}

.drop-inner .el-icon {
  font-size: 40px;
  color: var(--sc-brand);
}

.drop-inner strong {
  font-size: 15px;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity var(--sc-dur) var(--sc-ease);
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.warn-num {
  color: var(--sc-danger);
  font-size: 17px;
  font-variant-numeric: tabular-nums;
}

.small {
  font-size: 12px;
}

/* ---------------- 窄屏 ---------------- */

@media (max-width: 1280px) {
  .panel {
    width: 300px;
    flex-basis: 300px;
  }
}

@media (max-width: 1080px) {
  .panel {
    display: none;
  }

  .rail {
    width: 200px;
    flex-basis: 200px;
  }
}
</style>
