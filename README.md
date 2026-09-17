# lian-ai-code-monther

个人学习项目：AI 网页代码生成平台的全栈实现，用于练习 Spring Boot、LangChain4j、Vue 3 与 AI 应用工程化。

当前已完成后端基础工程与 AI 代码生成模块：

- `backend`：Spring Boot 3.5.x、Java 21、Knife4j OpenAPI 3 接口文档、统一响应、业务异常、全局异常处理、健康检查、受限跨域配置，以及基于 LangChain4j 的 AI 代码生成能力（单 HTML / 多文件两种模式，结构化输出与流式输出）。
- `frontend`：Vue 3、TypeScript、Vue Router、Pinia、Ant Design Vue、Axios、Vite 和 OpenAPI 请求代码生成基础配置。

后端和前端默认面向 Windows 开发环境运行，后端端口为 `8123`，上下文路径为 `/api`，前端 Vite 开发端口为 `5173`。

Knife4j 文档地址为 `http://localhost:8123/api/doc.html`，OpenAPI JSON 地址为 `http://localhost:8123/api/v3/api-docs`。

## 目录

```text
lian-ai-code-monther/
├── backend/
├── frontend/
└── README.md
```

## 运行

后端：

```powershell
cd backend
./mvnw.cmd spring-boot:run
```

前端：

```powershell
cd frontend
npm install
npm run dev
```

后端离线测试不依赖外部服务：

```powershell
cd backend
./mvnw.cmd test
```

真实模型调用只使用 Windows 环境变量 `DEEPSEEK_API_KEY` 和被忽略的本地配置文件，仓库不保存任何 API Key。未配置密钥时，后端仍可运行全部离线测试，但不能调用真实模型。
