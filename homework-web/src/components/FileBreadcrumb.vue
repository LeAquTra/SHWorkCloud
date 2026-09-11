<template>
  <el-breadcrumb separator="/">
    <el-breadcrumb-item>
      <el-link type="primary" :underline="false" @click="emit('navigate', 0)">全部文件</el-link>
    </el-breadcrumb-item>
    <el-breadcrumb-item v-for="item in items" :key="item.id">
      <el-link type="primary" :underline="false" @click="emit('navigate', item.id)">
        {{ item.name }}
      </el-link>
    </el-breadcrumb-item>
  </el-breadcrumb>
</template>

<script setup lang="ts">
import type { BreadcrumbVO } from '@/types/api'

defineProps<{ items: BreadcrumbVO[] }>()
const emit = defineEmits<{ navigate: [id: number] }>()
</script>
