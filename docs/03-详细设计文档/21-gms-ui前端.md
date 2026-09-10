# 21. gms-ui 前端详细设计

| 项目 | 内容 |
| --- | --- |
| 模块路径 | `gms-ui/`（源码根 `gms-ui/src/`，构建配置 `gms-ui/config/`） |
| 文件数量 | `src/` 共 163 个文件：api 17、views 55、components 17、store 14、router 15、hooks 9、utils 10、locale 5、types 3、directive 2、mock 3、layout 2、assets 7、config 1、入口 3（main.ts / App.vue / env.d.ts） |
| 依赖模块 | 后端 REST API（`gms-server` 8686 端口：`/auth`、`/account`、`/character`、`/give`、`/common`、`/server`、`/command`、`/config`、`/cashShop`、`/shop`、`/drop`、`/gachapon`、`/inventory`、`/autoban`、`/file` 各 v1 控制器）；外部服务 maplestory.io（图标）、unpkg（Monaco CDN）、cdn.jsdelivr.net（脚本 d.ts）；第三方库 Vue 3 / Vite 3 / TypeScript / Pinia / vue-router 4 / vue-i18n 9 / axios / Arco Design Vue / @guolao/vue-monaco-editor / echarts + vue-echarts / @vueuse/core / mockjs / nprogress / mitt / lodash / dayjs / query-string / sortablejs |

## 21.1 总体架构

gms-ui 基于 Arco Design Pro Vue 模板改造的**北斗私服管理后台**（单页应用），技术栈：Vue 3（Composition API + `<script setup>`）+ Vite 3 + TypeScript + Pinia + vue-router 4 + vue-i18n 9 + axios + Arco Design Vue，代码编辑使用 Monaco Editor（@guolao/vue-monaco-editor）。

分层结构：

```
src/
├── main.ts / App.vue        # 应用入口与根组件
├── api/                     # HTTP 接口层（17 个模块 + axios 拦截器）
├── store/modules/           # Pinia 状态（app/user/tab-bar）+ 6 个纯类型模块
├── router/                  # 路由表（按菜单模块拆分）+ 3 个全局守卫
├── views/                   # 页面（login/dashboard/account/game/not-found/redirect）
├── components/              # 布局级公共组件（菜单/导航/标签栏/设置抽屉等）
├── layout/                  # 默认布局（default-layout / page-layout）
├── locale/                  # i18n 聚合（zh-CN 默认、en-US 回退）
├── hooks/                   # 组合式函数（loading/permission/locale 等 9 个）
├── utils/                   # 工具（auth/mapleStoryAPI/stringUtils 等 10 个）
├── types/                   # 全局 TS 类型
├── directive/               # v-permission 指令
├── mock/                    # mockjs（仅 dev 生效的模板遗留 mock）
├── config/settings.json     # 布局默认设置（app store 初始 state）
└── assets/                  # 图片与全局 less（含 world.json、inv_full.png）
```

**部署模型**：开发时前端 8787 直连后端 8686（`.env.development` 配 `VITE_API_BASE_URL`，靠后端 `CorsConfig` 放行）；生产时 `.env.production` 为空（同源相对路径），`yarn build` 产物 `dist/` 拷入 `gms-server/src/main/resources/static/`，由服务端 8686 同源托管。

**关键横切约定**（详见 21.3.1 interceptor）：

- 请求拦截器自动把 `config.data` 包成 `{ requestId: uuid, data }` 信封，与后端 `SubmitBody<T>` 对齐；multipart 上传不包信封。
- 响应统一 `HttpResponse { status, message, code, data }`，成功码 `20000`（对应后端 `BizExceptionEnum.SUCCESS`），否则 `Message.error` 并 reject。
- 携带 `Authorization: Bearer <token>`（token 存 localStorage，key 为 `token`）。
- `responseType === 'blob'` 走浏览器文件下载（从 `Content-Disposition` 解析文件名）。
- 401 触发 `userStore.logoutCallBack()` 并跳转首页 `/`。

## 21.2 入口与全局

### 21.2.1 index.html

单页宿主页面：`<title>BeiDou</title>`，挂载点 `<div id="app">`，模块入口 `/src/main.ts`，favicon 为仓库根 `favicon.ico`。

### 21.2.2 main.ts（39 行）

应用装配入口，执行顺序：

1. `createApp(App)`。
2. `app.use(ArcoVue)`（Arco 组件全量注册）+ `app.use(ArcoVueIcon)`（图标）。
3. `app.use(router)`（`./router`，含守卫）、`app.use(store)`（Pinia）、`app.use(i18n)`（vue-i18n）、`app.use(globalComponents)`（`@/components`，注册全局 `Chart`/`Breadcrumb` 并按需注册 echarts 模块）、`app.use(directive)`（注册 `v-permission`）。
4. `import './mock'` 引入 mockjs（内部按 dev 环境判断是否生效）。
5. `import '@/assets/style/global.less'` 全局样式、`import '@/api/interceptor'` 注册 axios 拦截器（副作用导入）。
6. `loader.config({ paths: { vs: 'https://unpkg.com/monaco-editor@0.52.2/min/vs' } })`：Monaco Editor loader 指向 unpkg CDN（注释中保留 jsdelivr/cdnjs/bootcdn 备选），供文件管理页在线编辑器使用。
7. `app.mount('#app')`。

### 21.2.3 App.vue

根组件，仅两件事：

- `<a-config-provider :locale="locale">`：根据 `useLocale().currentLocale`（`zh-CN` → arco zh-cn 语言包，`en-US` → en-us，默认 en-us）切换 Arco 组件内置文案。
- `<router-view />` + `<global-setting />`（设置抽屉，见 21.7.4）。

附带全局 less：`.container` 内边距、表格最后一列表头标题缩进、`.arco-row` 下边距。

### 21.2.4 环境变量文件

| 文件 | 内容 | 说明 |
| --- | --- | --- |
| `.env.development` | `VITE_API_BASE_URL= 'http://localhost:8686'` | dev 直连本地后端，axios `defaults.baseURL` 即此值 |
| `.env.production` | 空文件 | prod 不设 baseURL，请求走同源相对路径（由服务端 static/ 托管） |
| `src/env.d.ts` | `ImportMetaEnv { VITE_API_BASE_URL: string }` 与 `*.vue` 模块声明 | Vite 环境变量与 SFC 的 TS 类型补充 |

### 21.2.5 Vite 配置（`config/` 目录）

| 文件 | 要点 |
| --- | --- |
| `vite.config.base.ts` | 插件：`@vitejs/plugin-vue`、`vue-jsx`（menu 组件用 tsx）、`vite-svg-loader`、`vitePluginForArco`（Arco 样式按需/主题导入）。`server.port = 8787`。别名：`@` → `../src`、`assets` → `../src/assets`、`vue-i18n` → cjs 版（消除 i18n 警告）、`vue` → `vue/dist/vue.esm-bundler.js`（运行时模板编译，供 menu 组件 `compile()` 动态 icon）。`resolve.extensions: ['.ts', '.js']`。`define: { 'process.env': {} }`。css.less：注入 `breakpoint.less` 引用 + `javascriptEnabled`。 |
| `vite.config.dev.ts` | merge base；`mode: development`；`server.open: true` 自动开浏览器；`vite-plugin-eslint` 实时 lint（含 src 下 ts/tsx/vue）。 |
| `vite.config.prod.ts` | merge base；`mode: production`；插件：gzip 压缩（`vite-plugin-compression`，`.gz`）、打包分析（`rollup-plugin-visualizer`，仅 `REPORT=true` 时）、`unplugin-vue-components` + `ArcoResolver` 按需解析、`vite-plugin-imagemin` 图片压缩（mozjpeg quality 20 等）；`build.rollupOptions.manualChunks` 手动分包 `arco` / `chart`(echarts+vue-echarts) / `vue`(vue+vue-router+pinia+@vueuse/core+vue-i18n)；`chunkSizeWarningLimit: 2000`。 |
| `config/utils/index.ts` | `isReportMode()`：`process.env.REPORT === 'true'` 判断是否生成打包报告（配合 `yarn report`）。 |
| `config/plugin/*.ts` | 上表 4 个 prod 插件 + `arcoStyleImport.ts` 的独立封装，见各行说明。 |

`package.json` 脚本：`dev`（vite dev 配置）、`build`（先 `vue-tsc --noEmit` 类型检查再 vite build）、`report`、`preview`、`type:check`、`lint-staged`（提交时 prettier/eslint/stylelint）；commitlint 强制 conventional 提交信息。

### 21.2.6 src/config/settings.json

布局默认配置，作为 `app` Pinia store 的初始 state（整体展开）：

```json
{ "theme": "light", "colorWeak": false, "navbar": true, "menu": true, "topMenu": false,
  "hideMenu": false, "menuCollapse": false, "footer": true, "themeColor": "#165DFF",
  "menuWidth": 220, "globalSettings": false, "device": "desktop", "tabBar": false,
  "menuFromServer": false, "serverMenu": [] }
```

注意 `tabBar: false`（多标签栏默认关闭）、`menuFromServer: false`（菜单默认来自前端路由而非服务端）。

## 21.3 API 层（src/api/，17 个文件）

所有模块直接使用全局 `axios` 单例（拦截器在 `interceptor.ts` 中对全局 axios 注册，`main.ts` 副作用导入）。函数返回 `Promise<HttpResponse<T>>`（响应拦截器已剥掉 axios 外壳），调用方统一 `const { data } = await xxx()` 解构。分页响应统一为 `PageState`（见 21.4.2）。

### 21.3.1 interceptor.ts —— 请求/响应拦截器（核心）

**导出**：`interface HttpResponse<T = unknown> { status: string; message: string; code: number; data: T }`。

**内部函数** `generateUUID()`：以 `'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'` 模板 + `Math.random` 生成 v4 风格 UUID，作为 `requestId`。

**初始化**：若 `import.meta.env.VITE_API_BASE_URL` 存在则设置 `axios.defaults.baseURL`（dev 指向 8686）。

**请求拦截器**：

1. `getToken()`（localStorage `token`）非空则加 `config.headers.Authorization = 'Bearer <token>'`。
2. 判断 `config.headers?.['Content-type'] === 'multipart/form-data'` 为上传请求。
3. 非上传且有 `config.data` 时，把 data 重包装为 `{ requestId: generateUUID(), data: 原 data }` —— 与后端 `SubmitBody<T>` 信封对齐（GET 请求无 body 不受影响）。

**响应拦截器（成功分支）**：

- `responseType === 'blob'`（文件下载）：状态非 200 报错 reject；否则 `URL.createObjectURL` + 隐式 `<a>` 标签触发下载，文件名从 `response.headers['content-disposition']` 按 `filename=` 切分并去引号，用后 `revokeObjectURL`，返回 `null`。
- 普通响应：`res.code !== 20000` → `Message.error(res.message || 'Error')`（5 秒）+ reject；否则返回 `res`（即 HttpResponse）。

**响应拦截器（失败分支）**：

- `error.message === 'Network Error'` 时提示中文「无法连接到服务器」。
- HTTP 状态 401：调用 `useUserStore().logoutCallBack()` 清空登录态，`window.location.href = '/'` 跳回登录页，reject `Error('登录已过期')`。
- 其他：`Message.error` + reject。

### 21.3.2 user.ts —— 认证与当前用户

| 函数 | 方法 + 端点 | 请求类型 | 响应 data 类型 |
| --- | --- | --- | --- |
| `login(data)` | POST `/auth/v1/login` | `LoginData { username, password }` | `LoginRes { token }` |
| `logout()` | DELETE `/auth/v1/logout` | — | `LoginRes` |
| `getUserInfo()` | GET `/account/v1/info` | — | `UserState`（见 21.4.4） |
| `getMenuList()` | GET `/account/v1/menu` | — | `RouteRecordNormalized[]`（服务端菜单） |
| `refreshToken()` | GET `/auth/v1/refreshToken` | — | `LoginRes { token }` |

另导出 `SubmitBody { requestId: string; data: any }`（信封类型，文档性声明）。

### 21.3.3 account.ts —— 账号管理

类型：`RegisterForm { name?, password?, checkPassword?, birthday?, language? }`（新建账号）；`GMUpdateForm`（GM 修改账号：`newPwd/newPwdCheck/pin/pic/birthday/nxCredit/maplePoint/nxPrepaid/characterslots/gender/webadmin/nick/mute/email/rewardpoints/votepoints/language`）。

| 函数 | 方法 + 端点 | 说明 |
| --- | --- | --- |
| `getAccountList(page, size, id?, name?, lastLoginStart?, lastLoginEnd?, createdAtStart?, createdAtEnd?)` | GET `/account/v1?page=&size=&id=&name=&lastLoginStart=...` | 分页查询账号；条件用 `isValidString` 过滤空串后拼 query；响应 `PageState` |
| `addAccount(data: RegisterForm)` | POST `/account/v1` | 新建账号 |
| `updateAccountByGM(id, data: GMUpdateForm)` | PUT `/account/v1/{id}` | GM 修改账号 |
| `deleteAccount(id)` | DELETE `/account/v1/{id}` | 删除账号（级联角色） |
| `banAccount(id, reason?)` | PUT `/account/v1/{id}/ban` | 封禁（body `{ reason }`） |
| `unbanAccount(id)` | PUT `/account/v1/{id}/unban` | 解封 |
| `resetLoggedIn(id)` | PUT `/account/v1/{id}/reset/logged` | 重置登录状态 |

### 21.3.4 character.ts —— 角色查询/删除

类型：`CharacterListItem { id, name, job, jobName, level, world, worldName, gm, meso, fame, guildid, createdate, lastLogoutTime, online }`。

| 函数 | 方法 + 端点 | 说明 |
| --- | --- | --- |
| `getAccountCharacters(accountId)` | GET `/character/v1/account/{accountId}` | 查账号下全部角色，响应 `CharacterListItem[]` |
| `deleteCharacter(cid)` | DELETE `/character/v1/{cid}` | 删除角色 |

### 21.3.5 player.ts —— 在线玩家与资源发放

类型：`GiveForm { worldId?, playerId?, player?, type, id?, quantity?, rate?, str?, dex?, int?, luk?, hp?, mp?, pAtk?, mAtk?, pDef?, mDef?, acc?, avoid?, hands?, speed?, jump?, upgradeSlot?, expire? }`。`type` 语义：0 nxCredit / 1 nxPrepaid / 2 maplePoint / 3 mesos / 4 exp / 5 普通道具 / 6 装备 / 7 经验倍率 / 8 金币倍率 / 9 爆率倍率 / 11 GM 等级 / 12 人气（前端下拉定义）。

| 函数 | 方法 + 端点 | 说明 |
| --- | --- | --- |
| `getPlayerList(pageNo, pageSize, id?, name?, map?)` | POST `/character/v1/online/list` | 在线/角色分页查询，body `{ pageNo, pageSize, id, name, map }`，响应 `PageState` |
| `givePlayerSrc(data: GiveForm)` | POST `/give/v1/resource` | 向玩家（`playerId=0` 表示全服）发放资源 |
| `getEquInitialInfo(id)` | POST `/common/v1/getEquipmentInfoByItemId` | body `{ id }`，按物品 id 查装备初始属性（str/dex/patk/upgradeSlot/expire 等） |

### 21.3.6 dashboard.ts —— 服务器生命周期

| 函数 | 方法 + 端点 | 说明 |
| --- | --- | --- |
| `getServerStatus()` | GET `/server/v1/online` | 游戏服是否运行，响应 `boolean` |
| `startServer()` | GET `/server/v1/startServer` | 启动游戏服 |
| `stopServer(params)` | POST `/server/v1/stopServerWithMsgAndInternal` | 定时停服，`StopServerParams { minutes, shutdownMsg, showServerMsg, showCenterMsg, showChatMsg }`（内部接口，未导出类型） |
| `restartServer()` | GET `/server/v1/restartServer` | 重启 |
| `shutdown()` | GET `/server/v1/shutdown` | 完全停服并退出进程 |
| `getVersion()` | GET `/server/v1/version` | 服务端版本号（导航栏展示） |

### 21.3.7 command.ts —— GM 指令管理

类型：`CommandReq { id?, level?, levelList?, syntax?, defaultLevel?, defaultLevelList?, clazz?, description?, enabled? }`。

| 函数 | 方法 + 端点 |
| --- | --- |
| `getCommandList(data: any)` | POST `/command/v1/getCommandListFromDB`（分页 + levelList/syntax 过滤，响应 `PageState`） |
| `updateCommand(data: CommandReq)` | POST `/command/v1/updateCommand` |
| `reloadEventsByGMCommand()` | GET `/command/v1/reloadEventsByGMCommand`（重载事件脚本） |
| `reloadPortalsByGMCommand()` | GET `/command/v1/reloadPortalsByGMCommand` |
| `reloadMapsByGMCommand()` | GET `/command/v1/reloadMapsByGMCommand` |

### 21.3.8 config.ts —— GameConfig 运行参数管理

类型：`ConfigSearch { type, subType, filter, pageNo, pageSize }`；`ConfigResult { id, configType, configSubType, configClazz, configCode, configValue, configDesc }`（对应后端 `game_config` 表一行）。

| 函数 | 方法 + 端点 | 说明 |
| --- | --- | --- |
| `getConfigTypeList()` | GET `/config/v1/getConfigTypeList` | 返回 `{ types: string[], subTypes: string[] }` |
| `getConfigList(data: ConfigSearch)` | POST `/config/v1/getConfigList` | 分页条件查询，响应 `PageState` |
| `addConfig(data)` / `updateConfig(data)` | POST `/config/v1/addConfig`、`/config/v1/updateConfig` | 新增/更新 |
| `deleteConfig(id)` | DELETE `/config/v1/deleteConfig/{id}` | 单条删除 |
| `deleteConfigList(ids: number[])` | POST `/config/v1/deleteConfigList` | 批量删除（body 为 id 数组，仍被信封包裹） |
| `importYml(option: RequestOption)` | POST（`option.action`，即 `/config/v1/importYml`） | Arco Upload 自定义上传：FormData 带 `file`，`Content-type: multipart/form-data`（不包信封），成功后回调 `option.onSuccess` |
| `exportYml()` | GET `/config/v1/exportYml`（`responseType: 'blob'`） | 导出 yml，由响应拦截器触发浏览器下载 |

### 21.3.9 cashShop.ts —— 点券商城

类型引用 `cashShopState`（store 类型，含 current 值与 `default*` wz 默认值对照字段）。另定义 `conditionState { id, subId, onSale?, pageNo, itemId? }`（分类查询条件）、`cashShopFormState { sn, itemId, count?, price?, bonus?, priority?, period?, maplePoint?, meso?, forPremiumUser?, commodityGender?, onSale?, clz?, limit?, pbCash?, pbPoint?, pbGift?, packageSn? }`（编辑提交体）、`batchFormState { data: cashShopState[], type: string, value?: number }`（批量编辑：type 为中文「价格/数量/有效期」）。

| 函数 | 方法 + 端点 |
| --- | --- |
| `getAllCategoryList()` | GET `/cashShop/v1/getAllCategoryList`（全部分类树，响应 `categoryState[]`） |
| `getCommodityByCategory(condition)` | POST `/cashShop/v1/getCommodityByCategory`（分页商品，响应 `PageState`） |
| `onSale(data)` / `offSale(data)` | POST `/cashShop/v1/onSale`、`/cashShop/v1/offSale`（上架/下架即整条商品更新） |
| `batchOnSale(data: batchFormState)` | POST `/cashShop/v1/batchOnSale`（批量改价格/数量/有效期） |

### 21.3.10 npcShop.ts —— NPC 商店

类型引用 `NpcShopItemState`（store 类型）。另定义 `getShopFilter { pageNo?, pageSize?, onlyTotal, notPage, shopId?, npcId?, npcName?, itemId?, itemName? }`。

| 函数 | 方法 + 端点 |
| --- | --- |
| `getShopList(data)` | POST `/shop/v1/getShopList`（商店分页，响应 `PageState`） |
| `getShopItemList(data)` | POST `/shop/v1/getShopItemList`（商店内商品分页） |
| `deleteShopItem(id)` | DELETE `/shop/v1/deleteShopItem/{id}` |
| `addShopItem(data: NpcShopItemState)` | PUT `/shop/v1/addShopItem` |
| `updateShopItem(data)` | POST `/shop/v1/updateShopItem` |

### 21.3.11 drop.ts —— 爆率管理（怪物掉落 + 全局掉落）

类型引用 `DropState`（store 类型）。另定义 `DropConditionState { dropperId?, dropperName?, continent?, itemId?, itemName?, questId?, pageNo?, pageSize?, onlyTotal?, notPage? }`。

| 函数 | 方法 + 端点 |
| --- | --- |
| `getDrop(data)` | POST `/drop/v1/getDropList`（怪物掉落分页） |
| `updateDrop(data)` / `insertDrop(data)` | POST `/drop/v1/updateDropData`、PUT `/drop/v1/addDropData` |
| `deleteDrop(data)` | DELETE `/drop/v1/deleteDropData/{data.id}` |
| `getGlobalDrop(data)` | POST `/drop/v1/getGlobalDropList`（全局掉落分页） |
| `updateGlobalDrop(data)` / `insertGlobalDrop(data)` | POST `/drop/v1/updateGlobalDropData`、PUT `/drop/v1/addGlobalDropData` |
| `deleteGlobalDrop(data)` | DELETE `/drop/v1/deleteGlobalDropData/{data.id}` |

### 21.3.12 gachapon.ts —— 百宝箱（转蛋）

类型引用 `GachaponPoolState` / `GachaponRewardState`（store 类型）。另定义 `GachaponPoolSearchCondition { gachaponId?, pageNo, pageSize }`。

| 函数 | 方法 + 端点 |
| --- | --- |
| `getPools(condition)` | POST `/gachapon/v1/getPools`（奖池分页） |
| `updatePool(data)` / `deletePool(data)` | POST `/gachapon/v1/updatePool`、`/gachapon/v1/deletePool`（新增复用 updatePool，id 为空即新建） |
| `getRewards(condition: GachaponPoolState)` | POST `/gachapon/v1/getRewards`（某奖池奖品列表，响应数组非分页） |
| `updateReward(data)` / `deleteReward(data)` | POST `/gachapon/v1/updateReward`、`/gachapon/v1/deleteReward` |

### 21.3.13 inventory.ts —— 背包管理

类型引用 `InventoryState`（store 类型）。另定义 `InventoryCondition { inventoryType?, characterId?, characterName?, accountId?, pageNo, pageSize }`。

| 函数 | 方法 + 端点 |
| --- | --- |
| `getInventoryTypeList()` | GET `/inventory/v1/getInventoryTypeList`（背包类型枚举，响应 `InventoryTypeState[]`） |
| `getCharacterList(condition)` | POST `/inventory/v1/getCharacterList`（角色分页，含在线状态） |
| `getInventoryList(condition)` | POST `/inventory/v1/getInventoryList`（某角色某类型背包物品；`notPage: true` 时返回全量数组） |
| `updateInventory(data)` | POST `/inventory/v1/updateInventory`（更新物品/装备属性，新增行亦走此接口） |
| `deleteInventory(data)` | POST `/inventory/v1/deleteInventory` |

### 21.3.14 autoban.ts —— 自动封禁配置

类型：`AutobanConfigResult { id, type, name, disabled, points | null, expireTimeSeconds | null, description, defaultPoints, defaultExpireTimeSeconds, changePoints, changeExpireTime }`。

| 函数 | 方法 + 端点 |
| --- | --- |
| `getAutobanConfigList()` | GET `/autoban/v1/getConfigList`（全量列表） |
| `updateAutobanConfig(data)` | POST `/autoban/v1/updateConfig`（逐行保存） |

### 21.3.15 fileTree.ts —— 服务端脚本文件树

类型：`FileTreeForm { currentKey }`（目录 key，根为 `''`）；`ReadForm { currentKey, title }`；`WriteForm { currentKey, title, content }`。

| 函数 | 方法 + 端点 |
| --- | --- |
| `treeFile(data)` | POST `/file/v1/tree`（列目录子节点，响应 Arco a-tree 节点数组） |
| `readFile(data)` | POST `/file/v1/tree/read`（读文件内容，响应 string） |
| `writeFile(data)` | POST `/file/v1/tree/write`（写文件内容） |

### 21.3.16 information.ts —— 资料查询

类型：`InformationSearch { types: [], filter }`（types 为 cash/consume/eqp/etc/ins/map/mob/npc/pet/skill 多选）；`InformationResult { type, id, name, desc }`。

| 函数 | 方法 + 端点 |
| --- | --- |
| `informationSearch(condition)` | POST `/common/v1/informationSearch`（跨 wz 资料检索，响应 `InformationResult[]`） |

### 21.3.17 message.ts —— 消息中心（模板遗留，mock 数据）

类型：`MessageRecord { id, type, title, subTitle, avatar?, content, time, status: 0|1, messageType? }`、`MessageListType`、`ChatRecord { id, username, content, time, isCollect }`。

| 函数 | 方法 + 端点 | 说明 |
| --- | --- | --- |
| `queryMessageList()` | POST `/api/message/list` | mock（dev 下由 `src/mock/message-box.ts` 提供） |
| `setMessageStatus({ ids })` | POST `/api/message/read` | 标记已读 |
| `queryChatList()` | POST `/api/chat/list` | mock 聊天列表（未被页面使用） |

注意 `/api/*` 前缀与真实后端 `/xxx/v1` 前缀不同，是 Arco Pro 模板遗留；`message-box` 组件目前未挂载到导航栏。

## 21.4 状态管理（src/store/）

### 21.4.1 store/index.ts

`createPinia()` 并默认导出；命名导出 `useAppStore`、`useUserStore`、`useTabBarStore` 三个 store 工厂。

### 21.4.2 store/page.ts

`interface PageState { records: any; pageNumber: number; pageSize: number; totalPage: number; totalRow: number }` —— 后端 MyBatis-Flex 分页结构的前端镜像，所有列表接口的响应 data 类型（页面只用 `records` 与 `totalRow`）。

### 21.4.3 modules/app（应用全局设置 store，id `app`）

**state**：`AppState`（`types.ts`），初始值整体来自 `settings.json`：`theme/colorWeak/navbar/menu/topMenu/hideMenu/menuCollapse/footer/themeColor/menuWidth/globalSettings/device/tabBar/menuFromServer/serverMenu`（带索引签名 `[key: string]: unknown` 便于 `$patch`）。

**getters**：`appCurrentSetting`（state 快照）、`appDevice`（当前设备）、`appAsyncMenus`（`serverMenu` 断言为 `RouteRecordNormalized[]`）。

**actions**：

- `updateSettings(partial)`：`$patch` 部分设置。
- `toggleTheme(dark)`：`theme = 'dark'|'light'`，并在 `document.body` 上增删 `arco-theme="dark"` 属性（Arco 暗色开关）。
- `toggleDevice(device)` / `toggleMenu(value)`：更新设备类型 / `hideMenu`。
- `fetchServerMenuConfig()`：以固定 id `menuNotice` 的 Notification 提示 loading，调用 `getMenuList()`（GET `/account/v1/menu`）写入 `serverMenu`，成功/失败分别弹 success/error 通知。
- `clearServerMenu()`：清空服务端菜单。

### 21.4.4 modules/user（登录用户 store，id `user`）

**state**：`UserState`（`types.ts`）——后端 `accounts` 表字段的镜像（`id/name/pin/pic/loggedin/lastlogin/createdat/birthday/banned/banreason/macs/nxCredit/maplePoint/nxPrepaid/characterslots/gender/tempban/greason/tos/sitelogged/webadmin/nick/mute/email/ip/rewardpoints/votepoints/hwid/language`），加派生字段 `role: RoleType`（`'' | '*' | 'admin' | 'user'`）与 `avatar`。初始全 undefined。

**getters**：`userInfo`（state 快照）。

**actions**：

- `switchRoles()`：模板遗留的角色切换（admin/user 互切，未被业务使用）。
- `setInfo(partial)`：`$patch` 前先按 `partial.webadmin ? 'admin' : 'user'` 计算 `role` —— **权限判定核心**：只有 `webadmin=true` 的账号得到 `admin` 角色。
- `resetInfo()`：`$reset()` 回初始值。
- `info()`：调 `getUserInfo()`（GET `/account/v1/info`）并 `setInfo(res.data)`（登录守卫刷新会话时使用）。
- `login(loginForm)`：调 `login()` API，成功 `setToken(res.data.token)`；失败 `clearToken()` 并向上抛错。
- `logoutCallBack()`：本地清理（不调后端）——`resetInfo()` + `clearToken()` + `removeRouteListener()` + `appStore.clearServerMenu()`。
- `logout()`：调 DELETE `/auth/v1/logout`（无论成败）后执行 `logoutCallBack()`。

### 21.4.5 modules/tab-bar（多标签栏 store，id `tabBar`）

**state**：`TabBarState { tagList: TagProps[]; cacheTabList: Set<string> }`，初始 `tagList = [DEFAULT_ROUTE]`（工作台）、`cacheTabList = new Set([DEFAULT_ROUTE_NAME])`。`TagProps { title, name, fullPath, query?, ignoreCache? }`（`types.ts`）。

模块级辅助：`formatTag(route)` 从路由提取 title（`meta.locale`）/name/fullPath/query/ignoreCache；`BAN_LIST = ['Redirect']`（重定向路由不进标签栏）。

**getters**：`getTabList`、`getCacheList`（Set 转数组，供 keep-alive `include`）。

**actions**：`updateTabList(route)`（BAN 名单外 push 标签并按 `ignoreCache` 决定是否加入缓存）、`deleteTag(idx, tag)`、`addCache(name)`、`deleteCache(tag)`、`freshTabList(tags)`（整体替换并重建缓存）、`resetTabList()`。

### 21.4.6 纯类型 store 模块（无 defineStore）

以下 6 个目录只导出 TS 接口，被 api 层与视图层共享（避免重复定义实体结构）：

| 模块 | 导出类型 | 字段要点 |
| --- | --- | --- |
| `modules/account/types.ts` | `AccountState` | accounts 表完整字段（同 UserState 去掉 role/avatar），账号列表页行数据 |
| `modules/cashShop/type.ts` | `categoryState`（id/name/subId/subName + 分页控制）、`cashShopState` | 商品行：`sn/itemId/price/period/priority/count/onSale/bonus/maplePoint/meso/forPremiumUser/gender/clz/limit/pbCash/pbPoint/pbGift/packageSn`，每字段配 `default*` 对照（wz 原始默认值） |
| `modules/drop/type.ts` | `DropState` | `id/dropperId/dropperName/continent/itemId/itemName/minimumQuantity/maximumQuantity/questId/questName/chance/comments` |
| `modules/gachapon/type.ts` | `GachaponPoolState`（奖池：`id/name/gachaponId/weight/isPublic/prob/startTime/endTime/notification/realProb/comment`）、`GachaponRewardState`（奖品：`id/poolId/itemId/quantity/createTime/comment`） | `prob` 为万分比固定概率；`realProb` 为前端计算的展示值 |
| `modules/inventory/type.ts` | `InventoryTypeState`、`InventoryEquipmentState`（22 个装备属性：upgradeSlots/level/attStr/attDex/attInt/attLuk/hp/mp/patk/matk/pdef/mdef/acc/avoid/hands/speed/jump/locked/vicious/itemLevel/itemExp/ringId）、`InventoryState`（背包物品 + 内嵌 `inventoryEquipment`） | |
| `modules/npcShop/type.ts` | `NpcShopState`（shopId/npcId/npcName）、`NpcShopItemState`（id/shopId/itemId/price/pitch/position/itemName/itemDesc） | |

## 21.5 路由（src/router/，15 个文件）

### 21.5.1 router/index.ts

`createRouter({ history: createWebHistory(), routes: [...], scrollBehavior → top: 0 })`，NProgress 关闭 spinner。路由表顺序：

1. `{ path: '/login', redirect: '/' }` —— 旧登录地址重定向到根。
2. `{ path: '/', name: 'login', component: views/login/index.vue, meta: { requiresAuth: false } }` —— **登录页即首页**（未登录访问任何页面都会被守卫带回这里）。
3. `...appRoutes`（`routes/index.ts` 聚合的业务路由）。
4. `REDIRECT_MAIN`（`/redirect` 包装层 + 子路由 `/redirect/:path`，用于标签栏刷新）。
5. `NOT_FOUND_ROUTE`（`/:pathMatch(.*)*`，name `notFound`）。

随后 `createRouteGuard(router)` 注册守卫。

### 21.5.2 routes/index.ts（路由装配）

用 `import.meta.glob('./modules/*.ts', { eager: true })` 与 `./externalModules/*.ts` 静态收集路由模块，`formatModules()` 把每个模块 default 导出（对象或数组）摊平进数组，产出：

- `appRoutes`：业务路由（进入 router）。
- `appExternalRoutes`：外链路由（**只进菜单不进 router**，见 app-menus）。

### 21.5.3 routes/base.ts

- `DEFAULT_LAYOUT = () => import('@/layout/default-layout.vue')`：所有业务模块共用默认布局（懒加载）。
- `REDIRECT_MAIN`：`/redirect`（name `redirectWrapper`，DEFAULT_LAYOUT，`meta.requiresAuth/hideInMenu`）+ 子路由 `/redirect/:path`（name 取 `REDIRECT_ROUTE_NAME = 'Redirect'`，组件 `views/redirect/index.vue`）。
- `NOT_FOUND_ROUTE`：通配 `/:pathMatch(.*)*`（name `notFound`，组件 `views/not-found/index.vue`，无布局）。

### 21.5.4 routes/types.ts

`Component` 联合类型（defineComponent 返回值 / 懒加载 Promise）；`AppRouteRecordRaw`（path/name/meta/redirect/component/children/alias/props/beforeEnter/fullPath）。

### 21.5.5 routes/typings.d.ts

扩展 vue-router `RouteMeta`：`roles?: string[]`、`requiresAuth: boolean`（每条路由必须声明）、`icon?`、`locale?`（菜单与面包屑文案 key）、`hideInMenu?`、`hideChildrenInMenu?`、`activeMenu?`、`order?`、`noAffix?`、`ignoreCache?`。

### 21.5.6 routes/modules/*.ts（按菜单模块拆分）

三个模块，全部 `component: DEFAULT_LAYOUT`，全部子路由 `meta: { requiresAuth: true, roles: ['admin'] }`（仅 webadmin 可见）：

**dashboard.ts**（`/dashboard`，locale `menu.dashboard`，icon `icon-dashboard`，order 0）：

| 子路径 | name | 组件 | locale |
| --- | --- | --- | --- |
| `workplace` | `Workplace` | `views/dashboard/workplace/index.vue` | `menu.dashboard.workplace` |
| `informationSearch` | `informationSearch` | `views/dashboard/informationSearch/index.vue` | `menu.dashboard.informationSearch` |

**account.ts**（`/account`，icon `icon-user`，order 1）：

| 子路径 | name | 组件 | locale |
| --- | --- | --- | --- |
| `list` | `AccountList` | `views/account/list/index.vue` | `menu.account.list` |
| `player` | `PlayerList` | `views/account/player/index.vue` | `menu.account.player` |

**game.ts**（`/game`，icon `icon-dice`，order 0，10 个子路由）：

| 子路径 | name | 组件 | locale |
| --- | --- | --- | --- |
| `config` | `Config` | `views/game/config/index.vue` | `menu.game.config` |
| `cashShop` | `CashShop` | `views/game/cashShop/index.vue` | `menu.game.cashShop` |
| `npcShop` | `NpcShop` | `views/game/npcShop/index.vue` | `menu.game.npcShop` |
| `drop` | `drop` | `views/game/drop/index.vue` | `menu.game.drop` |
| `drop/global` | `globalDrop` | `views/game/drop/global.vue` | `menu.game.drop.global` |
| `inventory` | `inventory` | `views/game/inventory/index.vue` | `menu.game.inventory` |
| `gachapon` | `gachapon` | `views/game/gachapon/index.vue` | `menu.game.gachapon` |
| `commandInfo` | `commandInfo` | `views/game/commandInfo/index.vue` | `menu.game.command` |
| `file` | `file` | `views/game/file/index.vue` | `menu.game.file` |
| `autoban` | `autoban` | `views/game/autoban/index.vue` | `menu.game.autoban` |

### 21.5.7 routes/externalModules/*.ts（外链菜单）

- `arco.ts`：`path: 'https://arco.design/vue/docs/start'`，name `arcoWebsite`，locale `menu.arco`（UI 开发文档），icon `icon-link`，order 8。
- `beidou.ts`：`path: 'https://github.com/BeiDouMS/BeiDou-Server'`，name `beiDou`，locale `menu.beiDou`（关于北斗），icon `icon-github`，order 99。

二者仅作为菜单项（menu 组件用 `regexUrl` 识别外链并 `openWindow` 新窗口打开），未注册为真实路由。

### 21.5.8 router/constants.ts

`WHITE_LIST`（`notFound`、`login` 两名字，服务端菜单模式下白名单）、`NOT_FOUND`（`{ name: 'notFound' }` 重定向目标）、`REDIRECT_ROUTE_NAME = 'Redirect'`、`DEFAULT_ROUTE_NAME = 'Workplace'`、`DEFAULT_ROUTE`（标签栏初始标签：title `menu.dashboard.workplace`，fullPath `/dashboard/workplace`）。

### 21.5.9 router/app-menus/index.ts

把 `appRoutes + appExternalRoutes` 摊平为 `appClientMenus`（仅保留 name/path/meta/redirect/children），作为侧边菜单数据源（`use-menu-tree` 消费）。

### 21.5.10 路由守卫（router/guard/）

**guard/index.ts —— `createRouteGuard(router)`** 依序注册三个 `beforeEach`：

1. `setupPageGuard`：`setRouteEmitter(to)` 发布路由变更事件（mitt，供菜单/标签栏订阅，避免多处直接监听路由浪费渲染）。
2. `setupUserLoginInfoGuard`（userLoginInfo.ts，登录态守卫）：
   - `NProgress.start()`。
   - `isLogin()`（localStorage 有 token）为真：
     - `userStore.role` 已有值 → 直接 `next()`（会话内直接放行）。
     - 否则 `await userStore.info()`（GET `/account/v1/info` 拉取用户并推导 role）：成功 `next()`；失败 `await userStore.logout()` 并重定向 `{ name: 'login', query: { redirect: to.name, ...to.query } }`（token 失效自动登出回登录页）。
   - 未登录：目标就是 `login` → `next()`；否则重定向 `login` 并携带 `redirect` query。
3. `setupPermissionGuard`（permission.ts，权限守卫）：
   - `Permission = usePermission()`，先算 `permissionsAllow = Permission.accessRouter(to)`。
   - `appStore.menuFromServer` 为真（服务端菜单模式，默认关）：若 `appAsyncMenus` 为空且目标不在白名单则 `await appStore.fetchServerMenuConfig()` 拉取服务端菜单；用 while 队列（含 children 展开）检查目标 name 是否存在于 `serverMenuConfig`，存在且 `permissionsAllow` 才 `next()`，否则 `next(NOT_FOUND)`。
   - 否则（前端路由模式）：`permissionsAllow` 为真 `next()`；为假则 `Permission.findFirstPermissionRoute(appRoutes, userStore.role)` 找第一个有权限的路由作为落点，找不到落 `NOT_FOUND`。
   - 最后 `NProgress.done()`。

**hooks/permission.ts —— `usePermission()`**（守卫与菜单共用）：

- `accessRouter(route)`：`!requiresAuth || 无 roles || roles 含 '*' || roles 含当前用户 role` 四者其一即放行。
- `findFirstPermissionRoute(routers, role)`：BFS 克隆队列，找第一条 `meta.roles` 含 `*` 或当前 role 的路由返回 `{ name }`。

## 21.6 视图（src/views/，55 个文件）

通用页面骨架：`<div class="container">` + `<Breadcrumb />`（面包屑）+ `<a-card class="general-card" :title="$t('菜单 locale')">`；表格统一 `a-table`（`column-resizable`、`:pagination="false"`、外置 `a-pagination`）；弹窗表单用 `a-modal` + `on-before-ok` 异步校验提交；加载态统一 `useLoading()`。

### 21.6.1 login（登录页）

**index.vue**：全屏容器，内容区居中 `LoginForm`，底部 `Footer`；保留响应式 banner 样式（当前未启用 banner）。

**components/login-form.vue**（登录表单，关键组件）：

- 表单字段：`username`（icon-user 前缀，回车聚焦密码框 `focusToPassword`）、`password`（a-input-password，回车直接提交）；两项均 required 校验。
- 「记住密码」复选框 + 「忘记密码」链接（无实现）+ 登录/注册按钮（注册按钮无实现）。
- `loginConfig = useStorage('login-config', { rememberPassword: true, username: 'admin', password: 'admin' })`：@vueuse 持久化到 localStorage，**默认预填 admin/admin 演示账号**（注释说明生产需加密存储）。
- `handleSubmit({ errors, values })`：校验通过后 `userStore.login(values)`（POST `/auth/v1/login` 换 token），成功跳转 `router.currentRoute.query.redirect || 'Workplace'`，`Message.success(t('message.login.success'))`，并按 rememberPassword 决定是否明文记住账号密码；失败把 `err.message` 显示到表单上方错误位（TypeError 时显示「错误的请求」）。

### 21.6.2 dashboard/workplace（工作台 —— 服务器控制台）

**index.vue**（组件名 `Dashboard`）。四块卡片：

1. **状态卡**：`onMounted → loadSeverStatus()` 调 `getServerStatus()`（GET `/server/v1/online`），布尔映射 `serverStatus: 'running' | 'resting'`，绿/灰 a-tag 展示。
2. **服务器控制卡**：`serverControlButtons` 配置数组渲染四按钮——start（运行中禁用，调 `startServer()`）、stop（停止态禁用，**不直接停服**而是弹停服配置框）、restart（停止态禁用，先弹确认框）、shutdown（恒可用，弹确认框）。`handleButtonClick(action)` 分发；成功 `Message.success(t('common.operationSuccess'))`，失败 `Message.error(t('common.requestFailed'))`，finally 一律 `loadSeverStatus()` 刷新状态。
3. **数据重载卡**：`dataReloadButtons` 三按钮——reloadEvents / reloadMaps / reloadPortals，分别调 `reloadEventsByGMCommand()` / `reloadMapsByGMCommand()` / `reloadPortalsByGMCommand()`（重载事件/地图/传送门脚本数据）。
4. **三个 a-modal**：
   - shutdown 确认框：`handleShutdownConfirm` 调 `shutdown()`（完全停服退出进程）。
   - restart 确认框：`handleRestartConfirm` 调 `restartServer()`。
   - **停服配置框**（`stopConfigData { minutes, shutdownMsg, showServerMsg, showCenterMsg, showChatMsg }`）：`handleStopConfigOk` 调 `stopServer(params)`（POST `/server/v1/stopServerWithMsgAndInternal`，定时停服 + 三种广播渠道开关）；若 `minutes > 0` 用 `setTimeout(minutes*60*1000)` 延迟刷新状态；取消时重置表单。

**mock.ts**：模板遗留的 `/api/content-data`、`/api/popular/list` mock（当前页面已不消费）。

### 21.6.3 dashboard/informationSearch（资料查询 + 道具下发）

**index.vue**（组件名 `InformationSearch`）。

- **搜索区**：`condition { types: [], filter }`；types 为多选 a-select（cash/consume/eqp/etc/ins/map/mob/npc/pet/skill 十类 wz 资料）；filter 关键字（回车触发）。`searchData()` 校验 filter 非空后调 `informationSearch(condition)`（POST `/common/v1/informationSearch`）填充 `informationList`；`resetSearch()` 清空。
- **结果表**：列 type（a-tag，`getTag()` 做 i18n 映射）、id、name（a-popover 悬浮显示图片，`getImg()` 把道具类 type 归一为 `item` 后拼 `getIconUrl`）、desc（宽 400）、操作列——`consume/eqp/etc/ins` 四类显示「下发」按钮 `handleDistribute(record)`。
- **玩家选择弹窗**（`selectorVisible`）：按 id/name 查 `getPlayerList(pageNo, pageSize, id, name)`（POST `/character/v1/online/list`）列出玩家（id/name/选择按钮），空结果 `Message.warning`。
- `selectCharacter(record)` 按当前物品类型分流：
  - `eqp` → **装备下发弹窗**：`equipForm`（type=6）带 17 个属性输入（str/dex/int/luk/hp/mp/pAtk/mAtk/pDef/mDef/acc/avoid/hands/speed/jump/upgradeSlot/expire）；打开时 `loadEquipInitialInfo(itemId)` 调 `getEquInitialInfo`（POST `/common/v1/getEquipmentInfoByItemId`）自动回填装备初始属性。
  - 其他 → **普通道具下发弹窗**：`itemForm`（type=5）仅需 quantity（min 1）。
  - 两个弹窗均 `on-before-ok` → `givePlayerSrc(form)`（POST `/give/v1/resource`）。

### 21.6.4 account/list（账户列表）

**index.vue**（组件名 `AccountList`）：

- **筛选表单** `filterForm { id?, name?, lastLoginStart/End?, createdAtStart/End? }`（id 数字、name 文本、四个 a-date-picker 日期）；「加载」触发 `loadData()`，重置清空后回第 1 页。
- `loadData()`：`getAccountList(page, size, ...筛选)`（GET `/account/v1`），写 `tableData = data.records`、`total = data.totalRow`；默认 `size = 14`。
- **表格列**：id、name、loggedin（蓝/灰 tag：在线状态，loggedin===2 时禁用编辑按钮）、banned（红 tag + a-tooltip 显示 `banreason` / 绿 tag）、gender（0 男蓝 / 1 女红 / 其他灰）、lastlogin、createdat、操作列（编辑 / 查看角色 / 重置登录状态 / 解封(popconfirm) 或 封禁 / 删除(popconfirm 提示含角色级联删除)）。
- **操作方法**：`restLoggedInClick → resetLoggedIn(id)`；`unbanClick → unbanAccount(id)`；`banClick` 打开**封禁原因弹窗**（`reasonVisible` + `reason` 输入），`submitBanClick → banAccount(id, reason)`；`deleteClick → deleteAccount(id)`；均成功后 `Message.success` + `loadData()`。
- 子组件（ref + `defineExpose({ init })` 模式）：`account-add-form`、`account-update-form`、`account-char-list`，`@reload="loadData"` 回调刷新。
- 底部 `a-pagination`（show-total/jumper/page-size，10/20/50/100）。

**addForm.vue**（AccountAddForm，新建账号弹窗）：字段 name/password/checkPassword/birthday/a-date-picker/language（a-select：2=English、3=中文，默认 3）；校验规则——name 与 password 必填且 minLength 6，checkPassword 自定义 validator 比对一致，birthday/language 必填；`submitClick` 先 `formRef.validate()`，通过后 `addAccount(formData)`（POST `/account/v1`），`emit('reload')`。

**updateForm.vue**（AccountUpdateForm，GM 编辑账号弹窗）：`init(accountData)` 把行数据灌入 `GMUpdateForm`（id 存组件内变量）；字段 newPwd/newPwdCheck（可选，长度 ≥6，一致性校验）、pin（空或 4 位数字）、pic（空或 6 位数字）、birthday（必填）、nxCredit/nxPrepaid/maplePoint/characterslots（数字）、gender（0/1）、webadmin（0/1，即是否后台管理员）、nick、mute（0/1）、email、rewardpoints、votepoints、language（2/3）；`submitClick → updateAccountByGM(id, formData)`（PUT `/account/v1/{id}`）。

**charList.vue**（AccountCharList，账号角色弹窗，宽 1000）：`init(id, name)` 拼标题并 `getAccountCharacters(accountId)`（GET `/character/v1/account/{id}`）填充表格；列 id/name/jobName/worldName/level/gm/meso/fame/online（绿/灰 tag）/createdate/操作（删除，popconfirm → `deleteCharacter(record.id)`，DELETE `/character/v1/{cid}`，成功后重载）。

### 21.6.5 account/player（玩家管理 —— 在线玩家与资源发放）

**index.vue**（组件名 `Player`）：

- **筛选**：id/name/map（地图 id）三条件 + 加载/重置；「刷新」重置页码重查；`loadData → getPlayerList(page, size, id, name, map)`（POST `/character/v1/online/list`），默认 size 14。
- **表格列**：id、name、map、job、jobName、level、gm、操作（发放）。
- **「全服发放」按钮**（`globalGiveClick`）：`playerId = 0` 表示全服，typeOptions 仅 0~6（nxCredit/nxPrepaid/maplePoint/mesos/exp/item/equip）；标题硬编码「全服发放资源」。
- **行「发放」按钮**（`giveClick(data)`）：带 worldId/playerId/player，typeOptions 0~9/11/12（额外含 expRate/mesosRate/dropRate/gm/fame，bossRate 注释停用）。
- **发放弹窗**（动态表单，按 `formData.type` 条件渲染）：
  - type 5/6 显示物品 id 输入（`itemChanged`：type=6 时调 `getEquInitialInfo(id)` 自动回填装备属性）。
  - type <6 或 11/12 显示 quantity；type 7/8/9 显示 rate（必填且 ≥3 的倍率）。
  - type 6 显示 17 个装备属性输入（同 informationSearch 装备表单）。
  - 提交 `submitClick → givePlayerSrc(formData)`（POST `/give/v1/resource`）。

### 21.6.6 game/config（运行参数管理）

**index.vue**（组件名 `Config`）——GameConfig 表的可视化 CRUD：

- **分类筛选**：两排 radio button——type（`loadTypes()` 从 `getConfigTypeList()` 取 `{ types, subTypes }`，前端加 `all` 占位）与 subType（加 `All` 占位；`transI18nType()` 做显示翻译：纯数字 subType 显示「世界 N」，其余查 `config.subType.*` 词条）。切换即 `loadConfigs()`。
- **工具条**：filter 关键字输入（回车搜索）+ 搜索/重置/新增（选中行为空时可用）/删除（有选中时可用）/导入/导出。
- **表格**（checkbox 多选，`selectedKeys` 为 id 数组）：列 configType（红 tag）、configSubType（紫 tag）、configClazz（绿 tag，`transI18nClz` 把 java 类名映射 int/float/bool/string 词条）、configCode、configValue、configDesc（宽 400）、操作（编辑）。
- `loadConfigs()`：`getConfigList(param)`（type/subType 为 all/All 时传空串），响应 `PageState`；每次查询后清空选中。
- **编辑弹窗**（新增与编辑共用 `editData`，id 为 0 表示新增）：configType/configSubType/configClazz/configCode 四项在**编辑态禁用**（只允许改 value/desc）；新增态 clazz 仅可选 `clzTypes`（Integer/String/Float/Boolean 四类），编辑态放开 `clzFull`（追加 Long/Byte/Short/Double/Map）；configValue 按 clazz 类型渲染——bool 用 a-switch（checked-value 字符串 'true'/'false'），其余用文本输入（小数也走字符串避免精度问题）；`editOk()` 按 id 分流 `updateConfig` / `addConfig`。
- **删除确认弹窗**：`confirmOk → deleteConfigList(selectedKeys)`（POST `/config/v1/deleteConfigList` 批量删）。
- **YML 导入弹窗**：红色警告文案 + `a-upload`（limit 1，`auto-upload=false`，action `/config/v1/importYml`，custom-request 走 `importYml(option)`（multipart FormData，不包信封））；「上传」按钮触发 `uploadRef.submit()`，`uploadSuccess` 关弹窗并重载。
- **导出**：`exportClick → exportYml()`（GET `/config/v1/exportYml`，blob 由响应拦截器触发浏览器下载）。
- 分页 `pageNo/pageSize`（10/20/40/80/100，默认 20）。

### 21.6.7 game/cashShop（商城管理）

**index.vue**（组件名 `CashShop`）——两级标签页外壳：`loadCategories()` 调 `getAllCategoryList()`（GET `/cashShop/v1/getAllCategoryList`）取扁平分类数组：一级分类去重入 `topCategoryList`（**过滤 `id === 8`** 的特殊分类），`id === 1` 的行初始化 `subCategoryList`；`topCategoryChange(tab)` 把 `allCategoryList` 中 `id === tab` 的行作为二级分类并重置 subTab=0。每个二级 tab 渲染一个 `<cash-shop-table :top-id :sub-id />`（lazy-load + destroy-on-hide）。

**table.vue**（CashShopTable，单分类商品表）：

- props：`{ topId, subId }`；`condition { id: topId, subId, onSale: 1, pageNo: 1, itemId? }`，创建时同步 props 并立即 `loadData()`。
- 工具条：上架中/待售/全部三个互斥按钮（`changeOnSaleFilter(1|0|undefined)`）、itemId 搜索、搜索/批量编辑。
- `loadData() → getCommodityByCategory(condition)`（POST `/cashShop/v1/getCommodityByCategory`）。
- **表格**（`:scroll={x:2000}` 横向滚动，checkbox 多选 rowKey=sn，onlyCurrent 选中）：列 SN、物品图标（`getIconUrl('item', itemId)`）、物品ID、物品名称、数量、优先级、售价、Bonus、有效期（`{period} 天`）、抵用券、金币、会员专属、性别（0 男蓝/1 女红/2 通用绿 tag）、上架（上架中绿/待售红 tag）、标签（clz：0 NEW 金/1 SALE 绿/2 HOT 橙红/3 EVENT 蓝）、Limit、PbCash、PbPoint、PbGift、礼包合集、操作（编辑）。表头文案硬编码中文。
- **批量编辑弹窗**：展示已选 SN tag；编辑类型 a-select（价格/数量/有效期）；值数字输入；`handleBatchFormBeforeOk` 校验选中与值后 `batchOnSale(batchFormData)`（POST `/cashShop/v1/batchOnSale`，body 为 `{ data: 选中商品行[], type: 中文类型, value }`）。
- 分页仅 `show-total/show-jumper`（page-size 固定）。

**form.vue**（CashShopForm，编辑商品弹窗）：`initForm(data: cashShopState)` 把行数据拷入 `formData`（`onSale` 转 0/1，`gender → commodityGender`），`tempData` 保留原行用于展示各字段「wz 默认值」（`default*` 字段，`#extra` 插槽显示）；可编辑字段：数量/价格/优先级/有效期/状态开关（上架中/待售）/Bonus/抵用券/金币/PremiumUser/性别/标签/Limit/pbCash/pbPoint/pbGift/packageSn；`handleBeforeOk` 按 onSale 分流 `onSale(formData)` / `offSale(formData)`。

### 21.6.8 game/npcShop（NPC 商店）

**index.vue**（组件名 `NpcShop`）——**两级视图切换**（`shopId: -1`=商店列表模式，>0=某商店商品模式）：

- 筛选：商店ID/NPC ID/NPC 名称/物品ID/物品名称 + 搜索/重置（商品模式下追加「新增」按钮）。
- **商店列表**（`v-show="shopId <= 0"`）：`loadShopList() → getShopList(shopFilter)`（POST `/shop/v1/getShopList`），列 shopId/npcId/npcName/NPC图片（`getIconUrl('npc', npcId)`）/操作（查看 → `showShopItemClick(shopId)` 切换到商品模式并 `loadShopItemList()`）。
- **商品列表**（`v-if="shopId > 0"`）：`getShopItemList(shopFilter)`（POST `/shop/v1/getShopItemList`）；列 id/shopId/物品图片/物品ID（新增行或 `editMode === record.id` 时为输入框）/itemName/价格/音符（pitch）/位置/描述（itemDesc）/操作。行内编辑模式：编辑（进入 editMode）/删除（popconfirm → `deleteShopItem(id)`）/保存（`id === -1` 新增行 → `addShopItem`，否则 `updateShopItem`）/返回（`rollbackClick`：新增行直接从数组移除，退出编辑）。
- `insertItemClick()`：unshift 一行 `{ id: -1, shopId, price: 0, pitch: 0, position: 1, ... }` 进入新增态。
- 分页 `pageNo/pageSize`（7/14/35/70，默认 20），按当前模式分流加载。

### 21.6.9 game/drop（爆率管理）

**index.vue**（组件名 `Drop`，怪物掉落 `/game/drop`）：

- 筛选：怪物ID/怪物名称/物品ID/物品名称/任务ID + 查询/重置/新增。文案硬编码中文。
- `loadData() → getDrop(condition)`（POST `/drop/v1/getDropList`），每次加载重置 `editId = 0`。
- **行内编辑**（`editId === record.id` 时单元格变输入框）：怪物ID/物品ID/最少/最多/爆率（显示态 `chance/10000` 保留 4 位小数，编辑态改 chance 原值）/任务ID；操作列 编辑/取消/保存/删除(popconfirm)。
- `saveClick(data)`：`data.id === 0`（unshift 出的新增行）→ `insertDrop(data)`（PUT `/drop/v1/addDropData`），否则 `updateDrop(data)`（POST `/drop/v1/updateDropData`）；`deleteClick → deleteDrop(data)`（DELETE `/drop/v1/deleteDropData/{id}`）。
- `insertClick()`：unshift 新行 `{ id: 0, dropperId: 当前筛选, itemId: 当前筛选, minimumQuantity: 1, maximumQuantity: 1, questId: 筛选或 0, ... }`。
- 怪物/物品单元格为按钮：点击即以该 id 为条件快捷过滤（`filterMobClick`/`filterItemClick`，`Message.success` 提示，itemId=0 显示「金币」），a-popover 悬浮展示图标。
- 分页 20/40/60/100。

**global.vue**（GlobalDrop，全局掉落 `/game/drop/global`）：结构与 index.vue 相同，差异点——筛选与编辑字段为**大区ID（continent）**而非怪物；多一列可编辑「备注（comments）」；新增行 `continent` 默认取筛选值否则 `-1`；调用 `getGlobalDrop/updateGlobalDrop/insertGlobalDrop/deleteGlobalDrop` 四个 `/drop/v1/*GlobalDrop*` 端点。

### 21.6.10 game/inventory（背包管理）

**index.vue**（组件名 InventoryMain）：

- 顶部：`<character-selector @use-character="useCharacter" />` 选择目标角色（显示 `[id] name` 与在线状态徽标）；「背包绘图」按钮（选中角色且类型不是 0/-1/6 时可用）打开 `InventoryUI` 模态框。
- `loadType()`：`getInventoryTypeList()`（GET `/inventory/v1/getInventoryTypeList`）取背包类型枚举渲染 `a-tabs`（lazy-load + destroy-on-hide），每 tab 内嵌 `<inventory-list :character-id :current-type />`。
- `typeMap`：inventoryType → i18n key（0 undefined / 1 装备 / 2 消耗 / 3 设置 / 4 其他 / 5 点券 / 6 可拾取 / -1 已装备）。

**table.vue**（InventoryList，物品列表）：props `{ currentType, characterId }`；无角色直接返回；`loadData() → getInventoryList({ inventoryType, characterId, pageNo: 1, pageSize: 20 })`。列：id、itemId、物品图标（a-popover；**特殊物品 2430033 使用本地资源 `assets/2430033.png`（北斗卫星指导书）**，其余走 `getIconUrl('item', itemId)`）、itemType、position、quantity（行内编辑）、owner、petId、flag、giftFrom、expiration（`timestampToChineseTime` 显示，-1 显示「永久」，行内编辑）、操作（编辑/保存/取消/删除 popconfirm）。`editClick`：装备类（`record.equipment`）转交 `inventory-equip-form`，普通物品行内编辑。保存/删除调 `updateInventory` / `deleteInventory`（POST `/inventory/v1/*`）。

**characterSelector.vue**（CharacterSelector）：a-input-search 样式按钮打开选择弹窗；条件 characterId/characterName + 搜索（`getCharacterList(condition)`，POST `/inventory/v1/getCharacterList`）；表格列 characterId/characterName/onlineStatus（绿/灰 tag）/选择按钮，`selectClick` 记录并 `emit('useCharacter', cid, cName, onlineStatus)` 关弹窗。

**inventoryEquipForm.vue**（InventoryEquipForm，装备属性编辑弹窗）：只读展示装备表ID与物品表ID；可编辑 22 项（可升级次数 upgradeSlots、升级次数 level、力 attStr、敏 attDex、智 attInt、运 attLuk、HP/MP、物攻 patk、魔法力 matk、物防 pdef、魔防 mdef、命中率 acc、回避率 avoid、手技 hands、移动速度 speed、跳跃力 jump、locked、vicious、道具等级 itemLevel、道具经验 itemExp、ringId）；`handleBeforeOk → updateInventory(formData)`（整条 InventoryState 含内嵌 equipment 提交）。文案硬编码中文。

**InventoryUI.vue**（背包绘图，Options API 风格 defineComponent）：

- props：`characterId`、`inventoryType`（均 required）。
- 模块级：独立 axios 实例 `maplestoryioAPI`（baseURL `https://maplestory.io/api`）；`getFromCacheOrDownload(itemId)` 拉 `/GMS/83/item/{id}/icon?resize=4` blob 并 `URL.createObjectURL`；`calculatePositionOffsets()` 以三个基准坐标推算 **4 页 × 4×6 = 96 个槽位**的像素坐标数组。
- `fetchInventoryData()`：`getInventoryList({ notPage: true, inventoryType, characterId, pageSize: 100 })` 全量取物品，watch props 立即执行。
- `drawInventory()`：canvas（600×290）先绘制背景 `assets/inv_full.png`，再按 `position` 映射槽位逐个绘制图标（30×30）；非装备类型且 quantity>1 时描边绘制数量文字。
- `handleMouseMove`：命中槽位（30×30 命中框）时显示 tooltip（itemId + itemName，跟随鼠标偏移 15px）并隐藏光标；`handleMouseLeave` 复位。
- 特殊物品 2430033 使用本地图片资源。

### 21.6.11 game/gachapon（百宝箱/转蛋奖池）

**index.vue**（组件名 Gachapon）：

- 筛选：gachaponId（百宝箱 NPC id）+ 搜索/重置/创建。
- `loadData() → getPools(condition)`（POST `/gachapon/v1/getPools`），同时记录 `filterGachaponId = condition.gachaponId`（有值=聚焦某百宝箱模式）。
- 列：ID、奖池名称 name、gachaponId（按钮，点击 `gachaponIdClick` 以该百宝箱过滤）、gachaponName、百宝箱头像（`getIconUrl('npc', gachaponId)`）、isPublic（公共池红 / 非公共池蓝 tag）、真实概率（仅聚焦模式显示 `realProb/10000 %`）、startTime/endTime（`timestampToChineseTime`，空显示默认文案）、notification（绿/红 tag）、comment、操作。
- 操作列：聚焦模式显示 编辑/详情(奖品)/删除(popconfirm `deletePool`)；非聚焦显示「操作」按钮（进入聚焦）。
- 子表单：`GachaponForm`（`initForm(record?)`）与 `GachaponRewardForm`（`initForm(record)`）。

**form.vue**（GachaponForm，奖池创建/编辑弹窗，宽 600）：字段——id（只读）、公共池开关、奖池名称、百宝箱ID（非公共池显示）、**权重**（非公共池显示：a-slider 0~10000 与数字输入联动）、**固定中奖率 prob**（公共池显示：万分比输入，旁注 `/10000` 与换算百分比）、生效/结束时间（show-time 日期）、全服通知开关、备注（max 255）。编辑且 `gachaponId !== -1` 时加载同百宝箱的其他奖池（`getPools({ gachaponId, pageSize: 9999 })` 过滤自身）渲染对照表（id/name/公共池/权重/真实概率）。`calcRealProb(weight)` 计算真实概率：公共池 `prob*100`；权重池按 `(1000000 - 公共池概率点数) × 权重占比` 分摊；`formatter` 作为 slider tooltip（「权重: N 概率 x%」）。提交 `updatePool(formData)`（POST `/gachapon/v1/updatePool`，新增复用）。文案硬编码中文。

**reward.vue**（GachaponRewardForm，奖池奖品弹窗，宽 1000）：`initForm(pool)` 记录当前奖池并 `getRewards(curPool)`（POST `/gachapon/v1/getRewards`，非分页，前端加序号列）；行内编辑 itemId/quantity/comment；`insertClick` unshift `{ poolId, quantity: 1 }` 新行；保存 `updateReward`、删除 popconfirm `deleteReward`；表格自带前端分页（10/20/50）。

### 21.6.12 game/commandInfo（GM 指令管理）

**index.vue**（组件名 CommandInfo）：

- 筛选：GM 等级 radio（-1 全部 + 1~6）+ syntax 指令名关键字 + 搜索/重置。`loadCommands()` 组参 `{ ...condition, levelList: level === -1 ? undefined : [level] }` → `getCommandList`（POST `/command/v1/getCommandListFromDB`）。
- 列：syntax、clazz、description、defaultLevel（只读）、level、enabled（a-switch 禁用态展示）、操作（编辑）。
- **编辑弹窗**：syntax/clazz/defaultLevel/description 只读；可改 level（0~6 数字）与 enabled 开关；`editOk → updateCommand(editData)` 后重载。分页 10/20/40/80/100。

### 21.6.13 game/file（文件管理 —— 服务端脚本在线编辑）

**index.vue**（组件名 ScriptFileManage）：

- **左栏** `a-tree`（theme dark、虚拟滚动 buffer 100、`load-more` 懒加载）：`treeRoot()` 用 `treeFile({ currentKey: '' })`（POST `/file/v1/tree`）取根目录；`onTreeSelectDiretory(node)` 展开目录时拉子节点；`onTreeSelectFile`——点目录切换展开态，点文件记录 `treeEditingNode` 后 `readFile({ currentKey, title })`（POST `/file/v1/tree/read`）取内容写入编辑器，并按扩展名查 `languageMap`（js/html/xml/json/java/md/sh/bat/yml/yaml/properties/sql → monaco 语言 id，缺省 txt）设置高亮。
- **右栏** `vue-monaco-editor`（vs-dark 主题，`automaticLayout/formatOnType/formatOnPaste`）。
- **自动保存**：`onEditorTextChange` → `useDebounceFn(debounceSaveFile, 1000, { maxWait: 10000 })`（VSCode 默认延迟），有正在编辑文件时 `writeFile({ currentKey, title, content })`（POST `/file/v1/tree/write`）。
- **代码补全**：`onEditorMount` 后 `registerCodeCompletion(monaco)`——优先 fetch `https://cdn.jsdelivr.net/gh/shinobi9/beidoums-scripts-snippets/types/beidoums-scripts.d.ts`（浏览器缓存），失败回退本地 `./types/beidoums-scripts.d.ts.txt`（`?raw` 内联，4027 行，声明 `cm/rm/qm/im/em` 等脚本全局变量）；`addExtraLib` + `setCompilerOptions`（allowJs/ES6/allowNonTsExtensions/noLib）。
- `onUnmounted` dispose 补全 provider 与编辑器。

### 21.6.14 game/autoban（自动封禁配置）

**index.vue**（组件名 Autoban）——单表行内编辑页：`loadConfigs() → getAutobanConfigList()`（GET `/autoban/v1/getConfigList`）。列：type（a-tag 显示 name，tooltip 显示原始 type）、disabled（a-switch 直接改行数据）、defaultPoints（只读）、points（勾选「自定义」checkbox 后出现数字输入 min 1）、defaultExpireTimeSeconds（-1 显示「永不过期」）、expireTimeSeconds（勾选自定义后出现输入 min -1）、description（a-input max 128）、操作（保存 → `updateAutobanConfig(record)`（POST `/autoban/v1/updateConfig`）+ `Message.success` + 重载）。

### 21.6.15 not-found / redirect

- **not-found/index.vue**：`a-result status=404` + back 按钮，点击 `router.push({ name: 'Workplace' })` 回工作台。
- **redirect/index.vue**：空渲染组件，setup 中读 `route.params.path` 并 `router.replace` 到该路径（配合标签栏「重新加载」实现页面自刷新）。

### 21.6.16 views 下 locale 文件

每个页面模块带 `locale/zh-CN.ts` 与 `locale/en-US.ts`（key-value 文案，如 `workplace.*`、`account.list.*`、`config.*` 等），由 `src/locale/zh-CN.ts` / `en-US.ts` 聚合（见 21.8）。**注意**：drop/cashShop/npcShop/gachapon/inventory 等页面仍有大量硬编码中文表格文案（历史上未完成 i18n），新增文案应走 locale 文件。

## 21.7 公共组件（src/components/，17 个文件）

### 21.7.1 components/index.ts

插件式注册：按需 `use([...])` echarts 模块（CanvasRenderer、Bar/Line/Pie/Radar 图、Grid/Tooltip/Legend/DataZoom/Graphic 组件，减小包体），并全局注册 `Chart` 与 `Breadcrumb` 两个组件。

### 21.7.2 breadcrumb/index.vue

面包屑：首项 icon-apps，其后遍历 `router.currentRoute.value.matched` 渲染各级 `meta.locale` 文案；末级或无子级的中间级渲染为可点击 a-link（`$router.push({ name })`）。

### 21.7.3 chart/index.vue

vue-echarts 封装：props `options/autoResize/width/height`；`renderChart` 初始 false，`nextTick` 后置 true（等待容器展开再渲染，避免初始尺寸为 0）。

### 21.7.4 global-setting/（设置抽屉，3 个文件）

- **index.vue**：`navbar` 关闭时页面右侧显示固定设置按钮；`a-drawer`（宽 300）展示「内容区域」（navbar/menu/topMenu/footer/tabBar/menuFromServer 开关 + menuWidth 数字）与「其他设置」（colorWeak 色弱）两组配置；「复制配置」把 `appStore.$state` JSON 复制到剪贴板（提示粘贴回 `settings.json` 持久化）。
- **block.vue**：标题 + 若干 `form-wrapper`；`handleChange({ key, value })` 特殊逻辑——colorWeak 直接给 body 加 `invert(80%)` 滤镜；menuFromServer 开启时 `appStore.fetchServerMenuConfig()`；topMenu 开启时重置 menuCollapse；最后 `appStore.updateSettings({ [key]: value })`。
- **form-wrapper.vue**：type=number 渲染 a-input-number，否则 a-switch，change 时 `emit('inputChange', { value, key: props.name })`。

### 21.7.5 menu/（侧边菜单，2 个文件）

- **index.vue**（tsx + @ts-nocheck）：由 `useMenuTree().menuTree` 递归渲染 a-menu（vertical/horizontal 由 `appStore.topMenu` 决定，带折叠按钮）。关键方法：`goto(item)` —— `regexUrl` 命中外链则 `openWindow` 新窗口打开；否则同路由去重后 `router.push`；`findMenuOpenKeys(target)` 回溯查找展开链；`listenerRouteChange(cb, true)` 订阅路由变化（mitt）维护 `openKeys/selectedKey`（支持 `activeMenu` 高亮代理）；icon 用 `h(compile('<icon-x/>'))` 动态编译（依赖 base 配置的完整版 vue）。
- **use-menu-tree.ts**：数据源二选一——`appStore.menuFromServer` 为真用 `appAsyncMenus`，否则 `appClientMenus`；`menuTree` computed：cloneDeep 后按 `meta.order` 升序排序，递归 `travel()` 用 `permission.accessRouter` 剪掉无权限节点、过滤 `hideInMenu`、处理 `hideChildrenInMenu` 叶子化。

### 21.7.6 navbar/index.vue（顶栏）

左侧：logo（`/src/assets/logo.png`，暗色主题下 CSS 反色）+ 标题（`$t('title')` 即「北斗」）+ 移动端菜单折叠按钮（`inject('toggleDrawerMenu')`，default-layout provide）。`topMenu` 开启时中部渲染 Menu。右侧：

- **版本 tag**：`loadVersion()` 调 `getVersion()`（GET `/server/v1/version`）展示。
- **语言切换**：a-dropdown（手动 `setDropDownVisible` 派发点击事件展开），选项来自 `LOCALE_OPTIONS`，`changeLocale` 来自 `useLocale()`。
- **主题切换**：`useDark({ selector: 'body', attribute: 'arco-theme', storageKey: 'arco-theme', onChanged → appStore.toggleTheme(dark) })` + `useToggle`。
- **全屏**：`useFullscreen()`。
- **用户菜单**：a-avatar + dropdown（用户中心 → `$router.push({ name: 'Info' })`、用户设置 → `{ name: 'Setting' }`、退出登录 → `useUser().logout()`）。注意 `Info`/`Setting` 两个路由名未在路由表注册，点击会落到 404（模板遗留）。

### 21.7.7 tab-bar/（多标签栏，settings.json 默认关闭）

- **index.vue**：a-affix 吸顶（offsetTop 随 navbar 60/0）；订阅 `listenerRouteChange`，路由非 `noAffix` 且 fullPath 不重复时 `tabBarStore.updateTabList(route)`；卸载时 `removeRouteListener()`。
- **tab-item.vue**：单个标签（title 走 i18n；当前路由高亮）；右键 a-dropdown 六操作——重新加载（`deleteCache` → 跳 Redirect 路由自刷新 → `addCache`）、关闭当前（首标签不可关）、关闭左侧/右侧/其它（`freshTabList` + 必要时跳转）、关闭全部（`resetTabList` + 回 Workplace）。操作文案硬编码中文。

### 21.7.8 message-box/（消息中心，模板遗留 + mock）

- **index.vue**：消息/通知/待办三 tab；`fetchSourceData → queryMessageList()`（mock `/api/message/list`）；`renderList` 按 tab type 过滤；`readMessage → setMessageStatus({ ids })` 标记已读；「清空」仅前端清数组。
- **list.vue**：a-list 渲染 `MessageRecord`（已读半透明、messageType 0~3 状态 tag、全部已读/查看更多链接）。
- **locale/{zh-CN,en-US}.ts**：`messageBox.*` 文案。
- 该组件当前未被任何页面挂载（navbar 未引入）。

### 21.7.9 footer/index.vue

`a-layout-footer`，居中显示 `$t('title')`。

## 21.8 布局（src/layout/，2 个文件）

### 21.8.1 default-layout.vue（业务页统一外壳）

结构：`a-layout` → 顶部固定 `layout-navbar`（NavBar，60px）→ 左侧固定 `layout-sider`（a-layout-sider，breakpoint=xl，宽度 `menuCollapse ? 48 : menuWidth`，内含 Menu）→ 移动端（`hideMenu`）改用 a-drawer 承载 Menu → 内容区 `layout-content`（`TabBar`（settings 默认关）+ `a-layout-content > PageLayout` + `Footer`）。

关键逻辑：

- 计算属性：`navbar/renderMenu(menu && !topMenu)/hideMenu/footer/menuWidth/collapsed` 均取自 appStore；`paddingStyle` 按菜单与导航存在与否计算内容区 padding。
- `useResponsive(true)`：窗口 < 992px 判定 mobile，自动 `toggleDevice` + `toggleMenu`。
- `setCollapsed(val)`：跳过初始化期间的 sider 回调，`updateSettings({ menuCollapse })`。
- `watch(userStore.role)`：角色变化且当前路由无权限时 `router.push({ name: 'notFound' })`。
- `provide('toggleDrawerMenu', ...)` 给 navbar 控制移动端抽屉。

### 21.8.2 page-layout.vue（内容区路由出口）

`router-view` + `transition(fade, out-in)`：`route.meta.ignoreCache` 的页面直接渲染不缓存；其余包 `keep-alive :include="cacheList"`（cacheList 来自 tabBar store 的 `getCacheList`，实现按标签关闭的缓存回收）。

## 21.9 国际化（src/locale/，5 个文件）

- **index.ts**：`LOCALE_OPTIONS`（中文/English）；默认语言取 `localStorage['arco-locale']` 否则 `zh-CN`；`createI18n({ locale, fallbackLocale: 'en-US', legacy: false, allowComposition: true, messages })`。
- **zh-CN.ts / en-US.ts**：聚合入口——内置菜单（`menu.dashboard/game/account/arco/beiDou` 全量）、`message.*`、`settings.*` 词条，再展开 `zh-CN/base.ts`（`title`、`operation`、`button.*` 通用按钮文案）与各 views 模块 locale（workplace/login/account/npcShop/cashShop/drop/gachapon/commandInfo/informationSearch/inventory/config/autoban）。
- 切换由 `hooks/locale.ts` 的 `changeLocale` 完成（写 localStorage + 提示）；Arco 组件内置文案由 App.vue 的 a-config-provider 同步。

## 21.10 组合式函数（src/hooks/，9 个文件）

| 文件 | 导出 | 设计要点 |
| --- | --- | --- |
| `loading.ts` | `useLoading(initValue = false) → { loading, setLoading, toggle }` | **隐式 token 续期**：`setLoading(false)` 时若本地有 token 则异步调 `refreshToken()`（GET `/auth/v1/refreshToken`）重写 token，失败 `clearToken()` 并抛错（注释解释：请求未统一封装，只能借每次 loading 复位时机刷新） |
| `permission.ts` | `usePermission() → { accessRouter, findFirstPermissionRoute }` | 角色鉴权核心，见 21.5.10 |
| `locale.ts` | `useLocale() → { currentLocale, changeLocale }` | 当前语言 computed；切换写 `arco-locale` 并 Message 提示 |
| `user.ts` | `useUser() → { logout }` | `userStore.logout()` 后 Message 成功提示并 `router.push({ name: 'login', query: { redirect: 当前路由名 } })` |
| `responsive.ts` | `useResponsive(immediate?)` | body 宽 < 992 判 mobile；debounce 100ms 的 resize 监听，`toggleDevice/toggleMenu` |
| `visible.ts` | `useVisible(initValue) → { visible, setVisible, toggle }` | 弹窗可见性通用封装 |
| `chart-option.ts` | `useChartOption(sourceOption) → { chartOption }` | 传入 `(isDark) => EChartsOption` 工厂，computed 感知主题返回配置 |
| `themes.ts` | `useThemes() → { isDark }` | appStore.theme === 'dark' |
| `request.ts` | `useRequest<T>(api, defaultValue, isLoading) → { loading, response }` | 立即执行 api 并装载数据的轻封装（需用 bind 传参）；当前业务页面基本直接手写 loadData，此 hook 少用 |

## 21.11 工具（src/utils/，10 个文件）

| 文件 | 导出 | 说明 |
| --- | --- | --- |
| `auth.ts` | `isLogin / getToken / setToken / clearToken` | token 存取，localStorage key 固定 `'token'` |
| `mapleStoryAPI.ts` | `getIconUrl(category, id, location='GMS', version='83')`、`nothing()` | 拼 `https://maplestory.io/api/GMS/83/{category}/{id}/icon` 图标地址（id ≤ 0 返回空串）；`nothing` 占位 |
| `stringUtils.ts` | `isValidString(data)`、`timestampToChineseTime(timestamp)` | 前者判非空字符串（account 列表拼 query 用）；后者时间戳格式化，`-1` 显示「永久/Permanent」，按 i18n locale 输出「yyyy年M月d日 HH时mm分ss秒」或英文格式 |
| `route-listener.ts` | `setRouteEmitter / listenerRouteChange / removeRouteListener` | 基于 mitt 的路由变更发布订阅（Symbol key），守卫发布、菜单/标签栏订阅，`immediate` 时回放最近一次路由 |
| `event.ts` | `addEventListen / removeEventListen` | add/removeEventListener 安全封装 |
| `is.ts` | `isArray/isObject/isString/isNumber/isRegExp/isFile/isBlob/isUndefined/isNull/isFunction/isEmptyObject/isExist/isWindow` | `Object.prototype.toString` 判型工具集 |
| `index.ts` | `openWindow(url, opts)`、`regexUrl` | window.open 特性串拼接；http(s)/ftp/localhost URL 正则（菜单外链识别） |
| `env.ts` | `debug`（默认导出） | `import.meta.env.MODE !== 'production'` |
| `setup-mock.ts` | 默认 `({ mock, setup })`（mock && debug 才执行 setup）、`successResponseWrap`、`failResponseWrap` | mockjs 启动器与统一响应包装（code 20000/50000） |
| `monitor.ts` | `handleError(Vue, baseUrl)` | 全局 errorHandler 上报（`POST {baseUrl}/report-error`），**当前未被 main.ts 启用** |

## 21.12 类型（src/types/，3 个文件）

- `echarts.ts`：`ToolTipFormatterParams`（echarts tooltip 参数扩展）。
- `global.ts`：`AnyObject/Options/NodeOptions/GetParams/PostData/Pagination/TimeRanger/GeneralChart` 通用类型（多为模板遗留，mock 与图表用）。
- `mock.ts`：`MockParams { url, type, body }`。

## 21.13 指令（src/directive/，2 个文件）

- `index.ts`：注册 `v-permission`。
- `permission/index.ts`：`mounted/updated` 时 `checkPermission` —— 绑定值必须是数组（如 `v-permission="['admin']"`），不含当前 `userStore.role` 则从 DOM 移除元素；非数组抛错。

## 21.14 Mock（src/mock/，3 个文件）

`index.ts` 引入 user / message-box / workplace 三个 mock 并设 `Mock.setup({ timeout: '600-1000' })`；所有 mock 经 `setup-mock` 判断仅 **dev** 生效。内容为 Arco Pro 模板遗留：`/api/user/login|info|logout|menu`（admin/user 演示账号）、`/api/message/*`、`/api/content-data`、`/api/popular/list`。真实业务登录走 `/auth/v1/login`，与 mock 无交集（路径前缀不同）。

## 21.15 附录

### 21.15.1 页面 → API 映射总表

| 页面（路由） | 调用的 api 函数（端点） |
| --- | --- |
| 登录 `/` | login（POST /auth/v1/login）、getUserInfo（GET /account/v1/info，守卫触发） |
| 工作台 `/dashboard/workplace` | getServerStatus、startServer、stopServer、restartServer、shutdown（/server/v1/*）、reloadEvents/Portals/MapsByGMCommand（/command/v1/*） |
| 资料查询 `/dashboard/informationSearch` | informationSearch（/common/v1/informationSearch）、getPlayerList（/character/v1/online/list）、getEquInitialInfo（/common/v1/getEquipmentInfoByItemId）、givePlayerSrc（/give/v1/resource） |
| 账户列表 `/account/list` | getAccountList、addAccount、updateAccountByGM、deleteAccount、banAccount、unbanAccount、resetLoggedIn（/account/v1/*）、getAccountCharacters、deleteCharacter（/character/v1/*） |
| 玩家管理 `/account/player` | getPlayerList、givePlayerSrc、getEquInitialInfo |
| 参数管理 `/game/config` | getConfigTypeList、getConfigList、addConfig、updateConfig、deleteConfigList、importYml、exportYml（/config/v1/*） |
| 商城管理 `/game/cashShop` | getAllCategoryList、getCommodityByCategory、onSale、offSale、batchOnSale（/cashShop/v1/*） |
| NPC商店 `/game/npcShop` | getShopList、getShopItemList、addShopItem、updateShopItem、deleteShopItem（/shop/v1/*） |
| 怪物爆率 `/game/drop` | getDrop、insertDrop、updateDrop、deleteDrop（/drop/v1/*） |
| 全局爆率 `/game/drop/global` | getGlobalDrop、insertGlobalDrop、updateGlobalDrop、deleteGlobalDrop |
| 背包管理 `/game/inventory` | getInventoryTypeList、getCharacterList、getInventoryList、updateInventory、deleteInventory（/inventory/v1/*） |
| 百宝箱 `/game/gachapon` | getPools、updatePool、deletePool、getRewards、updateReward、deleteReward（/gachapon/v1/*） |
| GM指令 `/game/commandInfo` | getCommandList、updateCommand（/command/v1/*） |
| 文件管理 `/game/file` | treeFile、readFile、writeFile（/file/v1/tree*） |
| 自动封禁 `/game/autoban` | getAutobanConfigList、updateAutobanConfig（/autoban/v1/*） |
| 全局（导航栏） | getVersion（/server/v1/version）、refreshToken（/auth/v1/refreshToken，loading 钩子触发）、getMenuList（/account/v1/menu，服务端菜单模式） |

### 21.15.2 已知设计债 / 注意点

1. **登录页即首页**：`/` 就是 login 路由，登录成功按 `redirect` query 或 `Workplace` 跳转；旧 `/login` 重定向到 `/`。
2. **权限模型**：所有业务路由 `roles: ['admin']`，角色由 `userStore.setInfo` 依 `webadmin` 布尔派生（admin/user），非 webadmin 账号登录后无任何可用菜单（落到 404）。
3. **token 静默续期**挂在 `useLoading().setLoading(false)` 上（hooks/loading.ts），任何页面 loading 复位都会触发一次 refreshToken。
4. **外链菜单**（arco 文档 / GitHub）不注册路由，menu 组件 regexUrl 识别后 openWindow 打开。
5. **navbar 的 Info/Setting 路由未定义**，点击用户中心/用户设置会 404（模板遗留）。
6. **message-box 组件与 /api/* mock** 为模板遗留，未在生产链路使用。
7. drop / cashShop / npcShop / gachapon / inventory(装备表单) / tab-bar 右键菜单 / player 全服发放标题等处仍有**硬编码中文文案**，未完全 i18n 化；按仓库规范新文案应走 locale。
8. `giveClick` 的 `typeOptions.value` 中 bossRate（value 10）被注释停用；下发 `type` 语义以后端 `/give/v1/resource` 实现为准。
9. **InventoryUI** 直连 maplestory.io 下载图标（不走后端），离线环境图标缺失但功能不受影响；特殊物品 2430033 用本地资源。
10. **文件管理页**的 d.ts 补全依赖公网 CDN，失败自动回退打包内置的 `beidoums-scripts.d.ts.txt`。
