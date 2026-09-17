# lian-ai-code-monther frontend

本目录是学习项目的 Vue 3 前端，实际在 Windows Node.js 环境运行。

## Recommended IDE Setup

[VS Code](https://code.visualstudio.com/) + [Vue (Official)](https://marketplace.visualstudio.com/items?itemName=Vue.volar) (and disable Vetur).

## Recommended Browser Setup

- Chromium-based browsers (Chrome, Edge, Brave, etc.):
  - [Vue.js devtools](https://chromewebstore.google.com/detail/vuejs-devtools/nhdogjmejiglipccpnnnanhbledajbpd)
  - [Turn on Custom Object Formatter in Chrome DevTools](http://bit.ly/object-formatters)
- Firefox:
  - [Vue.js devtools](https://addons.mozilla.org/en-US/firefox/addon/vue-js-devtools/)
  - [Turn on Custom Object Formatter in Firefox DevTools](https://fxdx.dev/firefox-devtools-custom-object-formatters/)

## Type Support for `.vue` Imports in TS

TypeScript cannot handle type information for `.vue` imports by default, so we replace the `tsc` CLI with `vue-tsc` for type checking. In editors, we need [Volar](https://marketplace.visualstudio.com/items?itemName=Vue.volar) to make the TypeScript language service aware of `.vue` types.

## Customize configuration

See [Vite Configuration Reference](https://vite.dev/config/).

## Project Setup

```powershell
npm install
```

### Compile and Hot-Reload for Development

```powershell
npm run dev
```

开发服务器默认运行在 `http://localhost:5173`，`/api` 请求由 Vite 代理到 Windows 后端的 `http://localhost:8123`。

### Type-Check, Compile and Minify for Production

```powershell
npm run build
```

### Lint with [ESLint](https://eslint.org/)

```powershell
npm run lint
```

后端启动并暴露 OpenAPI 文档后，可以生成协议类型：

```powershell
npm run openapi-types
```

生成目录为 `src/api/generated/`，属于构建产物，不直接手工修改。
