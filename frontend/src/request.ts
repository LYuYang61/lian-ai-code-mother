import axios from 'axios'
import { message } from 'ant-design-vue'
import { API_BASE_URL } from '@/config/env'

const request = axios.create({
  baseURL: API_BASE_URL,
  timeout: 15_000,
  withCredentials: true,
})

request.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status >= 500) {
      message.error('服务器暂时不可用，请稍后重试')
    }
    return Promise.reject(error)
  },
)

export default request
