<template>
  <!--
    品牌标识：一朵云。

    只画「云」本身，不画底板 —— 底板由调用方的 CSS 提供
    （页头与登录页都用 `--sc-gradient` + `--sc-radius`，尺寸各自不同）。
    glyph 用 currentColor 上色，所以父级 `color` 决定它是什么颜色。

    与 public/favicon.svg 是同一份几何定义，改一处要同步另一处。
  -->
  <svg
    class="brand-mark"
    viewBox="0 0 24 24"
    fill="currentColor"
    aria-hidden="true"
    focusable="false"
  >
    <ellipse cx="12" cy="14.6" rx="8.2" ry="3.4" />
    <circle cx="8.8" cy="11.6" r="3.5" />
    <circle cx="14.2" cy="10.6" r="4.3" />
  </svg>
</template>

<script setup lang="ts">
/**
 * 站点标识图标。
 *
 * 尺寸跟随父级 font-size（1.15em）：调用方的 `.mark` 已经用 font-size
 * 控制过 Element 图标的大小，换成 SVG 后不必新增一套尺寸变量。
 */
</script>

<style scoped>
.brand-mark {
  width: 1.15em;
  height: 1.15em;
  display: block;
}
</style>
