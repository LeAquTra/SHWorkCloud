import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIcons from '@element-plus/icons-vue'

// 样式顺序不能调换：
//   ① Element Plus 亮色基础变量
//   ② Element Plus 暗色变量（挂在 html.dark 上）
//   ③ 本项目的设计令牌与组件精修 —— 必须最后加载才能覆盖前两者
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'

import App from './App.vue'
import router from './router'
import { useThemeStore } from './stores/theme'
import './styles/main.css'

const app = createApp(App)

// 图标全局注册，模板里直接用 <el-icon><Folder /></el-icon>
for (const [name, component] of Object.entries(ElementPlusIcons)) {
  app.component(name, component)
}

const pinia = createPinia()
app.use(pinia)

// 尽早对齐主题状态：index.html 的内联脚本已经刷过一次 class，
// 这里补上系统主题监听与 store 状态同步。
useThemeStore(pinia).init()

app.use(router)
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
