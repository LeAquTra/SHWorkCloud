<template>
  <div class="user-layout">
    <AppHeader />
    <AnnouncementCenter />
    <main class="user-main">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
import AppHeader from '@/components/AppHeader.vue'
import AnnouncementCenter from '@/components/AnnouncementCenter.vue'

/** 学生 / 教师侧的统一外壳：顶栏 + 路由出口。 */
</script>

<style scoped>
.user-layout {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.user-main {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
</style>
