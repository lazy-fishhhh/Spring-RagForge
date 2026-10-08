import { ref } from 'vue'

/** localStorage 键名：存登录后的 userId，刷新不丢。 */
const USER_ID_KEY = 'ragforge.userId'

// 模块级单例 ref：所有调用方共享同一份登录态
const _userId = ref<string>(localStorage.getItem(USER_ID_KEY) ?? '')

/**
 * 登录态。userId 为空表示未登录；App.vue 据此在登录页与聊天页之间切换。
 */
export function useAuth() {
  function setUserId(id: string): void {
    _userId.value = id
    localStorage.setItem(USER_ID_KEY, id)
  }

  function logout(): void {
    _userId.value = ''
    localStorage.removeItem(USER_ID_KEY)
  }

  return { userId: _userId, setUserId, logout }
}
