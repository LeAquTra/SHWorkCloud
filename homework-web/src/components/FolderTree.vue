<template>
  <el-tree
    ref="treeRef"
    :data="treeData"
    :props="{ label: 'name', children: 'children' }"
    node-key="id"
    :expand-on-click-node="false"
    :indent="14"
    default-expand-all
    highlight-current
    @node-click="onNodeClick"
  >
    <template #default="{ data }">
      <span class="tree-node">
        <FileGlyph tone="folder" :size="15" variant="plain" />
        <span class="tree-label">{{ data.name }}</span>
      </span>
    </template>
  </el-tree>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import FileGlyph from '@/components/FileGlyph.vue'
import type { FolderNodeVO } from '@/types/api'

const props = defineProps<{ nodes: FolderNodeVO[]; currentId?: number }>()
const emit = defineEmits<{ select: [id: number] }>()

const treeRef = ref()

/** 顶部固定一个"全部文件"根节点 */
const treeData = computed(() => [{ id: 0, name: '全部文件', children: props.nodes || [] }])

function onNodeClick(data: { id: number }) {
  emit('select', data.id)
}

watch(
  () => props.currentId,
  (id) => {
    if (id !== undefined && treeRef.value) {
      treeRef.value.setCurrentKey(id)
    }
  },
  { immediate: true },
)
</script>

<style scoped>
.tree-node {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
}

.tree-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13.5px;
}
</style>
