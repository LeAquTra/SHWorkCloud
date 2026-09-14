<template>
  <router-view />
  <!--
    人机验证弹窗挂在根组件：登录页、注册弹窗、文件列表都要用它，
    而且全站同一时刻只允许有一个（并发上传时多个请求会合并到同一个弹窗）。
  -->
  <HumanCheckDialog />
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import HumanCheckDialog from '@/components/HumanCheckDialog.vue'
import { useHumanCheckStore } from '@/stores/humanCheck'
import { setUploadCaptchaGate } from '@/utils/uploader'

const humanCheck = useHumanCheckStore()

/**
 * 把"上传需要人机验证"这件事接到界面上。
 *
 * <p>方向是 uploader → 界面：uploader 只管在上传逻辑里问一句"有凭证吗"，
 * 至于弹窗长什么样、要不要合并并发请求，都由 store 决定。
 * `force = true` 是因为调用这个闸门的前提就是**服务端刚说了需要验证** ——
 * 此时不能因为本地那份配置写着"不需要"就跳过（配置可能在页面停留期间变了）。
 */
setUploadCaptchaGate(() => humanCheck.ensure('upload', true))

onMounted(() => {
  // 页面一进来就问清楚三个场景要不要验证码，免得用户点了按钮才多等一个来回
  void humanCheck.load()
})
</script>
