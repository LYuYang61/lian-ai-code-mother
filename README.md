# lian-ai-code-mother

个人学习项目：AI 网页代码生成平台的全栈实现，用于练习 Spring Boot、LangChain4j、Vue 3 与 AI 应用工程化。

当前已完成后端基础工程、AI 代码生成模块和第四期 AI 应用平台：

- `backend`：Spring Boot 3.5.x、Java 21、Knife4j OpenAPI 3 接口文档、统一响应、业务异常、全局异常处理、健康检查、受限跨域配置、BCrypt + Session 用户登录、MyBatis-Flex 应用 CRUD、SSE AI 生成、版本管理、预览和部署。
- `frontend`：Vue 3、TypeScript、Vue Router、Pinia、Ant Design Vue、Axios、Vite；包含注册登录、应用创建、SSE 工作区、版本回滚/比较、部署和管理员应用管理页面。

后端和前端默认面向 Windows 开发环境运行，后端端口为 `8123`，上下文路径为 `/api`，前端 Vite 开发端口为 `5173`。

Knife4j 文档地址为 `http://localhost:8123/api/doc.html`，OpenAPI JSON 地址为 `http://localhost:8123/api/v3/api-docs`。

## 目录

```text
lian-ai-code-mother/
├── backend/
├── frontend/
├── docs/
│   ├── 02-03-项目初始化与AI代码生成学习文档.md
│   └── 04-AI应用平台学习文档.md
├── STUDY.md
└── README.md
```

## 运行

后端：

```powershell
cd backend
$env:SPRING_PROFILES_ACTIVE = 'local'
./mvnw.cmd spring-boot:run
```

第四期首次运行前，请在 Windows MySQL 8 中创建 `lian_ai_code` 数据库并执行
`backend/src/main/resources/db/schema-mysql.sql`。复制
`application-local.yml.example` 为 `application-local.yml`，并通过 Windows 环境变量提供
`MYSQL_PASSWORD` 和 `DEEPSEEK_API_KEY`；不要把真实凭据写入文件或提交。

本期不要求启动 Redis。自动化测试使用 H2 内存数据库，不连接本机 MySQL：

```powershell
cd backend
./mvnw.cmd test
```

前端：

```powershell
cd frontend
npm ci
npm run dev
```

后端离线测试不依赖外部服务：

```powershell
cd backend
./mvnw.cmd test
```

真实模型调用只使用 Windows 环境变量 `DEEPSEEK_API_KEY` 和被忽略的本地配置文件，仓库不保存任何 API Key。未配置密钥时，后端仍可运行全部离线测试，但不能调用真实模型。

详细学习说明见 [`docs/04-AI应用平台学习文档.md`](docs/04-AI应用平台学习文档.md)，阶段记录见 [`STUDY.md`](STUDY.md)。
