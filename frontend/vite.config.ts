import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

// 后端地址：dev server 把接口请求转发过去，和生产（同一个 Spring Boot 端口）保持一致
const backend = 'http://localhost:8099'

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  build: {
    // 直接产出到 Spring Boot 的静态资源目录，jar 里就有页面，不需要额外拷贝步骤
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      // SSE 是长连接：changeOrigin 解决 Host 不匹配，proxyTimeout 0 关掉代理层超时，
      // 否则长回答会被半路掐断
      '/chat': { target: backend, changeOrigin: true, proxyTimeout: 0 },
      '/files': { target: backend, changeOrigin: true },
      '/admin': { target: backend, changeOrigin: true },
    },
  },
})
