<template>
  <el-tree
    ref="treeRef"
    :data="treeData"
    :props="{ label: 'name', children: 'children' }"
    node-key="id"
    :expand-on-click-node="false"
    default-expand-all
    highlight-current
    @node-click="onNodeClick"
  >
    <template #default="{ data }">
      <span class="tree-node">
        <el-icon><Folder /></el-icon>
        <span class="tree-label">{{ data.name }}</span>
      </span>
    </template>
  </el-tree>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { FolderNodeVO } from '@/types/api'

const props = defineProps<{ nodes: FolderNodeVO[]; currentId?: number }>()
const emit = defineEmits<{ select: [id: number] }>()

const treeRef = ref()

/** 顶部固定一个"全部文件"根节点 */
const treeData = computed(() => [
  { id: 0, name: '全部文件', children: props.nodes || [] },
])

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
  gap: 4px;
}

.tree-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
