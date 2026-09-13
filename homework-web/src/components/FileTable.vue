<template>
  <el-table
    v-loading="loading"
    element-loading-text="加载中…"
    :data="items"
    row-key="id"
    height="100%"
    highlight-current-row
    @selection-change="onSelectionChange"
    @row-dblclick="onRowDblClick"
  >
    <el-table-column type="selection" width="46" />

    <el-table-column label="名称" min-width="260" show-overflow-tooltip>
      <template #default="{ row }">
        <div class="name-cell">
          <FileGlyph :tone="fileTone(row)" :size="32" />
          <div class="name-text">
            <el-link
              v-if="row.folder && !isRecycle"
              type="primary"
              :underline="false"
              class="name-link"
              @click="emit('open', row)"
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

    <el-table-column label="操作" :width="isRecycle ? 170 : 190" fixed="right">
      <template #default="{ row }">
        <template v-if="isRecycle">
          <el-button link type="primary" @click="emit('restore', row)">
            <el-icon><RefreshLeft /></el-icon>
            <span>还原</span>
          </el-button>
          <el-button link type="danger" @click="emit('purge', row)">彻底删除</el-button>
        </template>

        <template v-else>
          <el-button v-if="!row.folder && canPreview(row)" link type="primary" @click="emit('preview', row)">
            预览
          </el-button>
          <el-button v-if="!row.folder" link type="primary" @click="emit('download', row)">
            下载
          </el-button>

          <el-dropdown trigger="click" placement="bottom-end" @command="(cmd: string) => onCommand(cmd, row)">
            <el-button link type="primary" class="more">
              更多
              <el-icon><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="rename">
                  <el-icon><EditPen /></el-icon>
                  <span>重命名</span>
                </el-dropdown-item>
                <el-dropdown-item command="move">
                  <el-icon><Rank /></el-icon>
                  <span>移动到…</span>
                </el-dropdown-item>
                <el-dropdown-item command="copy">
                  <el-icon><CopyDocument /></el-icon>
                  <span>创建副本</span>
                </el-dropdown-item>
                <el-dropdown-item command="remove" divided>
                  <el-icon><Delete /></el-icon>
                  <span>删除</span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
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
import { computed } from 'vue'
import { ArrowDown, CopyDocument, Delete, EditPen, Rank, RefreshLeft } from '@element-plus/icons-vue'
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

function onSelectionChange(rows: FileItemVO[]) {
  emit('selection-change', rows)
}

function onRowDblClick(row: FileItemVO) {
  if (row.folder && !isRecycle.value) {
    emit('open', row)
  }
}

function onCommand(command: string, row: FileItemVO) {
  switch (command) {
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

.more {
  margin-left: 4px;
}
</style>
