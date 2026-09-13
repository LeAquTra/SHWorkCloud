<template>
  <div class="album">
    <header class="album-head">
      <div>
        <h2>我的相册</h2>
        <p class="sc-muted">
          跨目录汇总网盘里所有可在线预览的图片，按上传时间倒序（共 {{ total }} 张）
        </p>
      </div>
      <div class="head-actions">
        <el-select v-model="size" class="size-select" @change="reload(1)">
          <el-option label="每页 30 张" :value="30" />
          <el-option label="每页 60 张" :value="60" />
          <el-option label="每页 120 张" :value="120" />
        </el-select>
        <el-button :loading="loading" @click="reload()">
          <el-icon><Refresh /></el-icon>
          <span>刷新</span>
        </el-button>
      </div>
    </header>

    <el-skeleton v-if="loading && !images.length" :rows="6" animated class="skeleton" />

    <div v-else-if="!images.length" class="empty sc-surface">
      <div class="empty-art"><el-icon><PictureFilled /></el-icon></div>
      <strong>还没有图片</strong>
      <p class="sc-muted">上传 jpg / png / gif / webp / bmp 后，这里会自动汇总成相册</p>
      <el-button type="primary" @click="router.push('/')">
        <el-icon><Upload /></el-icon>
        <span>去上传图片</span>
      </el-button>
    </div>

    <template v-else>
      <div class="grid">
        <figure v-for="image in images" :key="image.id" class="tile" @click="open(image)">
          <!-- loading=lazy：一页 120 张时避免把带宽一次性打满 -->
          <img :src="image.previewUrl" :alt="image.name" loading="lazy" decoding="async" />
          <figcaption class="overlay">
            <span class="name" :title="image.name">{{ image.name }}</span>
            <span class="meta sc-tabular">{{ formatSize(image.size) }}</span>
            <div class="tile-actions">
              <el-button link @click.stop="open(image)">
                <el-icon><ZoomIn /></el-icon>
              </el-button>
              <el-button link @click.stop="jumpToFolder(image)">
                <el-icon><FolderOpened /></el-icon>
              </el-button>
              <el-button link @click.stop="download(image)">
                <el-icon><Download /></el-icon>
              </el-button>
            </div>
          </figcaption>
        </figure>
      </div>

      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="total, prev, pager, next"
        class="pager"
        @current-change="() => reload()"
      />
    </template>

    <PreviewDialog v-model="previewVisible" :item="previewItem" :presigned-url="previewPresigned" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Download,
  FolderOpened,
  PictureFilled,
  Refresh,
  Upload,
  ZoomIn,
} from '@element-plus/icons-vue'
import PreviewDialog from '@/components/PreviewDialog.vue'
import { fileApi, imageApi } from '@/api'
import { ApiError } from '@/api/http'
import type { FileItemVO, ImageItemVO } from '@/types/api'
import { formatSize, triggerDownload } from '@/utils/format'

/**
 * 相册页。
 *
 * <p>用 `GET /images` 而不是 `GET /files?category=image`：后者只筛当前目录，
 * 这个是跨目录摊平整个网盘，并且每项已经带了 1 小时签名地址 —— 铺网格时
 * 不需要再为每张图发一次请求。
 */

const router = useRouter()

const images = ref<ImageItemVO[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(60)
const loading = ref(false)

const previewVisible = ref(false)
const previewItem = ref<FileItemVO | null>(null)
const previewPresigned = ref('')

async function reload(toPage?: number) {
  if (toPage) {
    page.value = toPage
  }
  loading.value = true
  try {
    const result = await imageApi.list(page.value, size.value)
    images.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '相册加载失败')
    images.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** ImageItemVO → FileItemVO：预览弹窗只认后者，顺便把签名地址带过去复用 */
function toFileItem(image: ImageItemVO): FileItemVO {
  return {
    id: image.id,
    name: image.name,
    folder: false,
    size: image.size,
    suffix: image.suffix,
    contentType: null,
    parentId: image.parentId,
    viewType: image.viewType || 'image',
    previewable: true,
    createTime: image.createTime,
    updateTime: image.updateTime,
  }
}

function open(image: ImageItemVO) {
  previewItem.value = toFileItem(image)
  previewPresigned.value = image.previewUrl
  previewVisible.value = true
}

/** 文档里给的 parentId 就是"跳转到所在文件夹"用的 */
async function jumpToFolder(image: ImageItemVO) {
  await router.push({ path: '/', query: { folder: String(image.parentId) } })
}

async function download(image: ImageItemVO) {
  try {
    // 走共用的 triggerDownload：那里带响应格式防呆（详见 utils/format.ts 的注释）
    const { url } = await fileApi.downloadUrl(image.id)
    triggerDownload(url)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '获取下载地址失败')
  }
}

onMounted(() => void reload(1))
</script>

<style scoped>
.album {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 22px var(--sc-gutter) 32px;
}

.album-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}

.album-head h2 {
  margin: 0 0 3px;
  font-size: 21px;
  letter-spacing: -0.025em;
}

.album-head p {
  margin: 0;
  font-size: 12.5px;
}

.head-actions {
  display: flex;
  gap: 8px;
}

.size-select {
  width: 132px;
}

.skeleton {
  padding: 8px;
}

/* ---------------- 网格 ---------------- */

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: 14px;
}

.tile {
  position: relative;
  margin: 0;
  aspect-ratio: 1 / 1;
  border-radius: var(--sc-radius-lg);
  overflow: hidden;
  border: 1px solid var(--sc-border);
  background: var(--sc-surface-2);
  cursor: pointer;
  box-shadow: var(--sc-shadow-xs);
  transition: transform var(--sc-dur) var(--sc-ease), box-shadow var(--sc-dur) var(--sc-ease),
    border-color var(--sc-dur) var(--sc-ease);
}

.tile:hover {
  transform: translateY(-3px);
  box-shadow: var(--sc-shadow-md);
  border-color: var(--sc-border-2);
}

.tile img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  transition: transform var(--sc-dur-slow) var(--sc-ease);
}

.tile:hover img {
  transform: scale(1.05);
}

.overlay {
  position: absolute;
  inset: auto 0 0 0;
  padding: 26px 12px 11px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  color: #fff;
  background: linear-gradient(to top, rgba(6, 9, 18, 0.86) 0%, rgba(6, 9, 18, 0.42) 55%, transparent 100%);
  opacity: 0;
  transform: translateY(8px);
  transition: opacity var(--sc-dur) var(--sc-ease), transform var(--sc-dur) var(--sc-ease);
}

.tile:hover .overlay,
.tile:focus-within .overlay {
  opacity: 1;
  transform: none;
}

.overlay .name {
  font-size: 12.5px;
  font-weight: 550;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.overlay .meta {
  font-size: 11px;
  opacity: 0.75;
}

.tile-actions {
  display: flex;
  gap: 2px;
  margin-top: 5px;
}

.tile-actions :deep(.el-button) {
  color: #fff;
  padding: 4px 6px;
}

.tile-actions :deep(.el-button:hover) {
  color: var(--sc-brand-cyan);
}

/* ---------------- 空态 ---------------- */

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 64px 20px;
  text-align: center;
}

.empty-art {
  width: 80px;
  height: 80px;
  border-radius: var(--sc-radius-xl);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 34px;
  color: #fff;
  background-image: var(--sc-gradient);
  box-shadow: var(--sc-shadow-brand);
  margin-bottom: 10px;
}

.empty strong {
  font-size: 15px;
}

.empty p {
  margin: 0 0 14px;
  font-size: 12.5px;
}

.pager {
  justify-content: center;
  margin-top: 22px;
}

@media (max-width: 600px) {
  .grid {
    grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  }
}
</style>
