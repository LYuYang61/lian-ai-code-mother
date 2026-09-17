# OpenAPI 类型生成

后端启动后，在前端目录执行：

```shell
npm run openapi-types
```

命令会从 `http://localhost:8123/api/v3/api-docs` 生成 `src/api/generated/schema.d.ts`。
运行时请求仍由 `src/request.ts` 统一管理，避免生成器把鉴权、Cookie 和错误处理逻辑散落到业务页面。
