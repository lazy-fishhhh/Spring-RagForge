import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import App from './App.vue'
// Element Plus 的 CSS 在 style.css 里以 layer(vendor) 引入，别在这里再引一次：
// 未分层的样式会盖住全部 Tailwind utilities
import './style.css'

createApp(App).use(ElementPlus).mount('#app')
