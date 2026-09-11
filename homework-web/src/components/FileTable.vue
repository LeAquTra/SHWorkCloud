<template>
  <el-table
    v-loading="loading"
    :data="items"
    row-key="id"
    height="100%"
    @selection-change="onSelectionChange"
    @row-dblclick="onRowDblClick"
  >
    <el-table-column type="selection" width="46" />
    <el-table-column label="名称" min-width="240" show-overflow-tooltip>
      <template #default="{ row }">
        <span class="name-cell">
          <span class="icon">{{ fileIcon(row) }}</span>
          <el-link
            v-if="row.folder"
            type="primary"
            :underline="false"
            @click="emit('open', row)"
          >
            {{ row.name }}
          </el-link>
          <span v-else>{{ row.name }}</span>
        </span>
      </template>
    </el-table-column>
    <el-table-column label="大小" width="110" align="right">
      <template #default="{ row }">
        {{ row.folder ? '-' : formatSize(row.size) }}
      </template>
    </el-table-column>
    <el-table-column label="修改时间" width="170">
      <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
    </el-table-column>
    <el-table-column label="操作" width="260" fixed="right">
      <template #default="{ row }">
        <el-button v-if="!row.folder" link type="primary" @click="emit('download', row)">
          下载
        </el-button>
        <el-button
          v-if="!row.folder && row.previewable"
          link
          type="primary"
          @click="emit('preview', row)"
        >
          预览
        </el-button>
        <el-button link type="primary" @click="emit('rename', row)">重命名</el-button>
        <el-button link type="primary" @click="emit('move', row)">移动</el-button>
        <el-button link type="danger" @click="emit('remove', row)">删除</el-button>
      </template>
    </el-table-column>
    <template #empty>
      <el-empty description="这个文件夹还是空的，上传一个文件试试" />
    </template>
  </el-table>
</template>

<script setup lang="ts">
import type { FileItemVO } from '@/types/api'
import { fileIcon, formatSize, formatTime } from '@/utils/format'

defineProps<{ items: FileItemVO[]; loading: boolean }>()

const emit = defineEmits<{
  open: [row: FileItemVO]
  download: [row: FileItemVO]
  preview: [row: FileItemVO]
  rename: [row: FileItemVO]
  move: [row: FileItemVO]
  remove: [row: FileItemVO]
  'selection-change': [rows: FileItemVO[]]
}>()

function onSelectionChange(rows: FileItemVO[]) {
  emit('selection-change', rows)
}

function onRowDblClick(row: FileItemVO) {
  if (row.folder) {
    emit('open', row)
  }
}
</script>

<style scoped>
.name-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.icon {
  font-size: 16px;
}
</style>
