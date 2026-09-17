import type { BaseResponse } from '@/api/types'
import request from '@/request'

/** 初始化阶段手写的最小 API；OpenAPI 类型生成只负责同步协议，不替代请求拦截器。 */
export function healthCheck() {
  return request.get<BaseResponse<string>>('/health/')
}
