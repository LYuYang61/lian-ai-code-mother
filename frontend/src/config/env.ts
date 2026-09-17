const apiBaseUrl = import.meta.env.VITE_API_BASE_URL

/**
 * 前端只读取 VITE_ 前缀的公开配置，不能把任何 API Key 放在这里。
 * 开发环境通常使用 /api 交给 Vite 代理，生产环境再由 Nginx 或同源部署承接。
 */
export const API_BASE_URL = apiBaseUrl || '/api'
