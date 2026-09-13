<template>
  <el-table
    v-loading="loading"
    element-loading-text="加载中…"
    :data="items"
    row-key="id"
    height="100%"
    highlight-current-row
    @selection-change="onSelectionChange"
    @row-click="onRowClick"
    @row-dblclick="onRowDblClick"
  >
    <el-table-column type="selection" width="46" />

    <el-table-column label="名称" min-width="260" show-overflow-tooltip>
      <template #default="{ row }">
        <div class="name-cell">
          <!--
            图片直接显示真缩略图（后端在列表项里下发了 1 小时签名地址），
            像头像那样一眼能看到内容；其它类型仍用类型图标。
          -->
          <img
            v-if="row.previewUrl && !failedThumbs.has(row.id)"
            :src="row.previewUrl"
            :alt="row.name"
            class="thumb"
            loading="lazy"
            decoding="async"
            @error="onThumbError(row.id)"
          />
          <FileGlyph v-else :tone="fileTone(row)" :size="32" />
          <div class="name-text">
            <el-link
              v-if="row.folder && !isRecycle"
              type="primary"
              :underline="false"
              class="name-link"
              @click.stop="emit('open', row)"
            >
              {{ row.name }}
            </el-link>
            <span v-else class="name-plain">{{ row.name }}</span>
            <small class="sc-muted">
              <template v-if="row.folder">文件夹</template>
              <template v-else>
                {{ (row.suffix || '文件').toUpperCase() }}
                <span v-if="isRecycle"> · 删除于 {{ formatTime(row.updateTime) }}</span>
              </template>
            </small>
          </div>
        </div>
      </template>
    </el-table-column>

    <el-table-column v-if="!isRecycle" label="大小" width="118" align="right">
      <template #default="{ row }">
        <span class="sc-tabular sc-subtle">
          {{ row.folder ? '—' : formatSize(row.size) }}
        </span>
      </template>
    </el-table-column>

    <el-table-column v-if="!isRecycle" label="修改时间" width="180">
      <template #default="{ row }">
        <span class="sc-tabular sc-subtle">{{ formatTime(row.updateTime) }}</span>
      </template>
    </el-table-column>

    <el-table-column label="操作" :width="isRecycle ? 108 : 118" fixed="right">
      <template #default="{ row }">
        <!--
          所有操作收进一个下拉框。@click.stop 很关键：
          整行点击会触发预览，点"操作"时不能再顺带把预览也打开。
        -->
        <el-dropdown
          trigger="click"
          placement="bottom-end"
          @click.stop
          @command="(cmd: string) => onCommand(cmd, row)"
        >
          <el-button link type="primary" @click.stop>
            操作
            <el-icon><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <template v-if="isRecycle">
                <el-dropdown-item command="restore">
                  <el-icon><RefreshLeft /></el-icon>
                  <span>还原</span>
                </el-dropdown-item>
                <el-dropdown-item command="purge">
                  <el-icon><Delete /></el-icon>
                  <span>彻底删除</span>
                </el-dropdown-item>
              </template>

              <template v-else>
                <el-dropdown-item v-if="!row.folder && canPreview(row)" command="preview">
                  <el-icon><View /></el-icon>
                  <span>在线预览</span>
                </el-dropdown-item>
                <el-dropdown-item v-if="!row.folder" command="download">
                  <el-icon><Download /></el-icon>
                  <span>下载</span>
                </el-dropdown-item>
                <el-dropdown-item command="rename" divided>
                  <el-icon><EditPen /></el-icon>
                  <span>重命名</span>
                </el-dropdown-item>
                <el-dropdown-item command="move">
                  <el-icon><Rank /></el-icon>
                  <span>移动到…</span>
                </el-dropdown-item>
                <el-dropdown-item v-if="!row.folder" command="copy">
                  <el-icon><CopyDocument /></el-icon>
                  <span>创建副本</span>
                </el-dropdown-item>
                <el-dropdown-item command="remove" divided>
                  <el-icon><Delete /></el-icon>
                  <span>删除</span>
                </el-dropdown-item>
              </template>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </template>
    </el-table-column>

    <template #empty>
      <slot name="empty">
        <el-empty description="这里还是空的" />
      </slot>
    </template>
  </el-table>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  ArrowDown,
  CopyDocument,
  Delete,
  Download,
  EditPen,
  Rank,
  RefreshLeft,
  View,
} from '@element-plus/icons-vue'
import FileGlyph from '@/components/FileGlyph.vue'
import type { FileItemVO } from '@/types/api'
import { canPreview, fileTone, formatSize, formatTime } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    items: FileItemVO[]
    loading: boolean
    mode?: 'files' | 'recycle'
  }>(),
  { mode: 'files' },
)

const emit = defineEmits<{
  open: [row: FileItemVO]
  download: [row: FileItemVO]
  preview: [row: FileItemVO]
  rename: [row: FileItemVO]
  move: [row: FileItemVO]
  copy: [row: FileItemVO]
  remove: [row: FileItemVO]
  restore: [row: FileItemVO]
  purge: [row: FileItemVO]
  'selection-change': [rows: FileItemVO[]]
}>()

const isRecycle = computed(() => props.mode === 'recycle')

/**
 * 缩略图加载失败的文件 id。
 * <p>签名失效或对象已被清理时，`<img>` 只会裂成一个小方块，
 * 比类型图标还难看；所以失败后回退成类型图标，列表整体观感不受影响。
 */
const failedThumbs = ref<Set<number>>(new Set())

function onThumbError(id: number) {
  const next = new Set(failedThumbs.value)
  next.add(id)
  failedThumbs.value = next
}

function onSelectionChange(rows: FileItemVO[]) {
  emit('selection-change', rows)
}

function onRowDblClick(row: FileItemVO) {
  if (row.folder && !isRecycle.value) {
    emit('open', row)
  }
}

/**
 * 点击整行即预览。
 *
 * <p>两处必须排除，否则会误触发：
 * <ol>
 *   <li>勾选框列 —— 用户是在多选，不是想看文件；</li>
 *   <li>操作下拉框、链接、按钮 —— 这些有各自的点击语义。
 *       下拉框另在模板上加了 {@code @click.stop}，这里是第二道保险。</li>
 * </ol>
 * 文件夹不在此处理：单击进目录太容易误触（想勾选却进了文件夹），
 * 仍保留双击进入与名称链接触发。
 */
function onRowClick(row: FileItemVO, _column: unknown, event: Event) {
  if (isRecycle.value) {
    return
  }
  const target = event.target as HTMLElement | null
  if (target && target.closest('.el-checkbox, .el-dropdown, button, a, .el-link')) {
    return
  }
  if (row.folder) {
    return
  }
  if (canPreview(row)) {
    emit('preview', row)
  }
}

function onCommand(command: string, row: FileItemVO) {
  switch (command) {
    case 'preview':
      emit('preview', row)
      break
    case 'download':
      emit('download', row)
      break
    case 'rename':
      emit('rename', row)
      break
    case 'move':
      emit('move', row)
      break
    case 'copy':
      emit('copy', row)
      break
    case 'remove':
      emit('remove', row)
      break
    case 'restore':
      emit('restore', row)
      break
    case 'purge':
      emit('purge', row)
      break
  }
}
</script>

<style scoped>
.name-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.name-text {
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.35;
}

.name-link,
.name-plain {
  font-weight: 550;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.name-plain {
  color: var(--sc-text);
}

.name-text small {
  font-size: 11px;
  letter-spacing: 0.02em;
}

/* 图片缩略图：32px 见方，圆角，和 FileGlyph 的视觉尺寸对齐 */
.thumb {
  width: 32px;
  height: 32px;
  flex: 0 0 32px;
  object-fit: cover;
  border-radius: var(--sc-radius-xs);
  border: 1px solid var(--sc-border);
  background: var(--sc-surface-2);
}

.more {
  margin-left: 4px;
}
</style>
