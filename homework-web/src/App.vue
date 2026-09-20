<template>
  <router-view />
  <!--
    人机验证弹窗挂在根组件：登录页、注册弹窗、文件列表都要用它，
    而且全站同一时刻只允许有一个（并发上传时多个请求会合并到同一个弹窗）。
  -->
  <HumanCheckDialog />
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, watch } from 'vue'
import HumanCheckDialog from '@/components/HumanCheckDialog.vue'
import { useFriendStore, FRIEND_POLL_INTERVAL_MS } from '@/stores/friend'
import { useHumanCheckStore } from '@/stores/humanCheck'
import { useUserStore } from '@/stores/user'
import { setUploadCaptchaGate } from '@/utils/uploader'

const humanCheck = useHumanCheckStore()
const user = useUserStore()
const friend = useFriendStore()

/**
 * 把"上传需要人机验证"这件事接到界面上。
 *
 * <p>方向是 uploader → 界面：uploader 只管在上传逻辑里问一句"有凭证吗"，
 * 至于弹窗长什么样、要不要合并并发请求，都由 store 决定。
 * `force = true` 是因为调用这个闸门的前提就是**服务端刚说了需要验证** ——
 * 此时不能因为本地那份配置写着"不需要"就跳过（配置可能在页面停留期间变了）。
 */
setUploadCaptchaGate(() => humanCheck.ensure('upload', true))

/**
 * 未读红点轮询。
 *
 * <p>放在根组件而不是顶栏里：顶栏在登录页并不存在，而"登录后要开始轮询、
 * 退出后要停"这件事需要一个稳定的生命周期宿主。这里用 watch 盯着登录态，
 * 登录时立刻拉一次（否则要等 30 秒才出红点）并起定时器，退出时清掉定时器与数值。
 *
 * <p>⚠️ 用的是 `setInterval` 而不是自我递归的 `setTimeout`：两者在"请求比间隔还慢"
 * 时行为不同 —— 递归写法会自然退避，而 setInterval 会堆积并发请求。
 * 这里选 setInterval 是因为接口极轻（两个 COUNT），且 `refresh()` 内部
 * 对失败是静默的；真遇到慢请求，最多也就是多几个在途请求。
 */
let unreadTimer: number | undefined

function stopUnreadPolling() {
  if (unreadTimer !== undefined) {
    window.clearInterval(unreadTimer)
    unreadTimer = undefined
  }
}

function startUnreadPolling() {
  stopUnreadPolling()
  void friend.refresh()
  unreadTimer = window.setInterval(() => void friend.refresh(), FRIEND_POLL_INTERVAL_MS)
}

watch(
  () => user.isLoggedIn,
  (loggedIn) => {
    if (loggedIn) {
      startUnreadPolling()
    } else {
      stopUnreadPolling()
      friend.reset()
    }
  },
  { immediate: true },
)

onMounted(() => {
  // 页面一进来就问清楚三个场景要不要验证码，免得用户点了按钮才多等一个来回
  void humanCheck.load()
})

onBeforeUnmount(stopUnreadPolling)
</script>
