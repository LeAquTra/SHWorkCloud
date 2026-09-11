<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="420px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-tree
      :data="treeData"
      :props="{ label: 'name', children: 'children' }"
      node-key="id"
      default-expand-all
      highlight-current
      :expand-on-click-node="false"
      @node-click="onSelect"
    >
      <template #default="{ data }">
        <span class="move-node" :class="{ disabled: isDisabled(data.id) }">
          <el-icon><Folder /></el-icon>
          <span>{{ data.name }}</span>
        </span>
      </template>
    </el-tree>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :disabled="selectedId === null" @click="onConfirm">
        确定
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { FolderNodeVO } from '@/types/api'

const props = defineProps<{
  modelValue: boolean
  nodes: FolderNodeVO[]
  title?: string
  /** 被移动的节点自身 id：不能移动到自身里 */
  excludeId?: number
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirm: [targetId: number]
}>()

const selectedId = ref<number | null>(null)

const treeData = computed(() => [
  { id: 0, name: '全部文件（根目录）', children: props.nodes || [] },
])

function isDisabled(id: number): boolean {
  return props.excludeId !== undefined && id === props.excludeId
}

function onSelect(data: { id: number }) {
  if (isDisabled(data.id)) {
    return
  }
  selectedId.value = data.id
}

function onConfirm() {
  if (selectedId.value !== null) {
    emit('confirm', selectedId.value)
    emit('update:modelValue', false)
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      selectedId.value = 0
    }
  },
)
</script>

<style scoped>
.move-node {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.move-node.disabled {
  color: #c0c4cc;
  cursor: not-allowed;
}
</style>
