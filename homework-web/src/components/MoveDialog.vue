<template>
  <el-dialog
    :model-value="modelValue"
    :title="title || '移动到'"
    width="440px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-tree
      :data="treeData"
      :props="{ label: 'name', children: 'children' }"
      node-key="id"
      default-expand-all
      highlight-current
      :expand-on-click-node="false"
      :indent="16"
      @node-click="onSelect"
    >
      <template #default="{ data }">
        <span class="move-node" :class="{ disabled: isDisabled(data.id) }">
          <FileGlyph tone="folder" :size="15" variant="plain" />
          <span>{{ data.name }}</span>
          <el-tag v-if="isDisabled(data.id)" size="small" type="info" effect="plain">不可选</el-tag>
        </span>
      </template>
    </el-tree>

    <template #footer>
      <span class="foot-hint sc-muted">目录层级上限 20 层，超出会被服务端拒绝。</span>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :disabled="selectedId === null" @click="onConfirm">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import FileGlyph from '@/components/FileGlyph.vue'
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
  gap: 7px;
}

.move-node.disabled {
  color: var(--sc-text-3);
  cursor: not-allowed;
}

.foot-hint {
  font-size: 12px;
  float: left;
  line-height: 32px;
}
</style>
