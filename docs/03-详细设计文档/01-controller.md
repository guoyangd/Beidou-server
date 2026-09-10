# 01-Controller 层详细设计

- 模块路径：`gms-server/src/main/java/org/gms/controller/`
- 类数量：15（`AccountController`、`AuthController`、`AutobanConfigController`、`CashShopController`、`CharacterController`、`CommandController`、`CommonController`、`ConfigController`、`DropController`、`FileController`、`GachaponController`、`GiveController`、`InventoryController`、`ServerController`、`ShopController`）
- 依赖模块：`org.gms.service.*`（各业务 Service）、`org.gms.model.dto.*`（`ResultBody`/`SubmitBody` 及各 DTO）、`org.gms.dao.entity.*`（DO）、`org.gms.net.server.Server`（游戏服单例）、`org.gms.constants.api.ApiConstant`（`LATEST = V1 = "v1"`）、`org.gms.util.*`（`RequireUtil`、`I18nUtil`）
- 通用约定：
  - 路径模式 `/{controller}/{ApiConstant.LATEST}/{action}`，Swagger `@Tag` 与之一致。
  - 响应统一 `ResultBody<T>`（`code/message/responseId/data`），成功码 `20000`；POST 请求体统一 `SubmitBody<T>`（`requestId/data`）信封，取业务参数用 `submitBody.getData()`。
  - 鉴权由 `AuthTokenFilter`/`SpringSecurityConfig` 完成（`/auth/**` 放行），限流与封禁校验由 `ServerFilter` 完成；本文不重复。

---

## AccountController

账号管理接口，`@RestController` + `@RequestMapping("/account")`，构造器注入 `AccountService` 与 `CharacterService`（源码路径：`src/main/java/org/gms/controller/AccountController.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `ResultBody<AccountsDO> info()`<br>GET `/account/v1/info` | 获取当前登录用户信息 | 调 `accountService.getCurrentUser()`，从 SecurityContext 取当前账号实体返回 |
| `ResultBody<Page<AccountsDO>> getAccountList(Integer page, Integer size, Integer id, String name, String lastLoginStart, String lastLoginEnd, String createdAtStart, String createdAtEnd)`<br>GET `/account/v1` | 分页查询账号列表 | 8 个查询参数均可选（页码/页大小/账号 ID/账号名/最后登录与创建时间的起止区间），全部透传 `accountService.getAccountList(...)` |
| `ResultBody<Object> register(SubmitBody<AddAccountDTO> submitBody)`<br>POST `/account/v1` | 注册账号 | `accountService.addAccount(submitBody.getData())`（BCrypt 加密等在 Service 做；抛 `NoSuchAlgorithmException`） |
| `ResultBody<Object> updateByUser(SubmitBody<UpdateAccountByUserDTO> submitBody)`<br>PUT `/account/v1` | 用户自助更新账号资料 | 调 `accountService.updateAccountByUser(...)`；需校验旧密码，新密码留空则不改密码 |
| `ResultBody<Object> updateByGm(int id, SubmitBody<UpdateAccountByGmDTO> submitBody)`<br>PUT `/account/v1/{id}` | GM 更新指定账号资料 | 路径变量 `id` + `accountService.updateAccountByGM(id, data)` |
| `ResultBody<Object> delete(int id)`<br>DELETE `/account/v1/{id}` | 删除账号 | 委托 `characterService.deleteAccount(id)`（连同账号下角色一并删除） |
| `ResultBody<Object> resetLoggedIn(int id)`<br>PUT `/account/v1/{id}/reset/logged` | 重置在线状态 | `accountService.resetAllLoggedIn(id)`（服务器异常宕机后清掉残留的loggedin标记） |
| `ResultBody<Object> banAccount(int id, SubmitBody<Map<String, String>> submitBody)`<br>PUT `/account/v1/{id}/ban` | 封停账号 | 从 Map 信封取 `"reason"`，`accountService.banAccount(id, reason)` |
| `ResultBody<Object> unbanAccount(int id)`<br>PUT `/account/v1/{id}/unban` | 解封账号 | `accountService.unbanAccount(id)` |

调用 Service：`AccountService`、`CharacterService`。

## AuthController

认证接口，`@RestController` + `@RequestMapping("/auth")`，构造器注入 `AuthService`；本路径被 Spring Security 放行（源码路径：`src/main/java/org/gms/controller/AuthController.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `ResultBody<Map<String, String>> login(SubmitBody<Map<String, String>> data)`<br>POST `/auth/v1/login` | 登录 | 从 Map 信封取 `"username"`/`"password"`，`authService.getToken(...)` 认证成功后返回含 token 的 Map |
| `ResultBody<Object> logout()`<br>DELETE `/auth/v1/logout` | 登出 | 无状态 JWT 下仅返回成功（客户端删除 token） |
| `ResultBody<Map<String, String>> refreshToken(String token)`<br>GET `/auth/v1/refreshToken` | 刷新 token | 从 `Authorization` 请求头取原 token，`authService.refreshToken(token)` 返回新 token |

调用 Service：`AuthService`。

## AutobanConfigController

自动封禁配置接口，`@RestController` + `@RequestMapping("/autoban")` + `@AllArgsConstructor`，注入 `AutobanConfigService`（源码路径：`src/main/java/org/gms/controller/AutobanConfigController.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `ResultBody<List<AutobanConfigDTO>> getConfigList()`<br>GET `/autoban/v1/getConfigList` | 获取自动封禁配置列表 | `autobanConfigService.getConfigList()` |
| `ResultBody<Object> updateConfig(SubmitBody<AutobanConfigDTO> request)`<br>POST `/autoban/v1/updateConfig` | 更新自动封禁配置 | `autobanConfigService.updateConfig(request.getData())`，成功回带原请求信封 |

调用 Service：`AutobanConfigService`。

## CashShopController

点卷商城（现金商城）管理接口，`@RestController` + `@RequestMapping("/cashShop")` + `@AllArgsConstructor`，注入 `CashShopService`（源码路径：`src/main/java/org/gms/controller/CashShopController.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `ResultBody<List<CashCategory>> getAllCategoryList()`<br>GET `/cashShop/v1/getAllCategoryList` | 获取商城全部分类 | `cashShopService.getAllCategoryList()`（pojo `CashCategory`） |
| `ResultBody<Page<CashShopSearchRtnDTO>> getCommodityByCategory(SubmitBody<CashCategory> request)`<br>POST `/cashShop/v1/getCommodityByCategory` | 分页分类查询商品列表 | 以分类条件 `cashShopService.getCommodityByCategory(request.getData())` |
| `ResultBody<CashShopSearchRtnDTO> getCommodityBySn(Integer sn)`<br>GET `/cashShop/v1/getCommodityBySn/{sn}` | 按 SN 查询商品明细 | `cashShopService.getCommodityBySn(sn)` |
| `ResultBody<Object> onSale(SubmitBody<ModifiedCashItemDO> request)`<br>POST `/cashShop/v1/onSale` | 上架商品 | 先 `request.getData().setOnSale(1)` 再 `cashShopService.changeOnSale(...)` |
| `ResultBody<Object> offSale(SubmitBody<ModifiedCashItemDO> request)`<br>POST `/cashShop/v1/offSale` | 下架商品 | 先 `setOnSale(0)` 再 `changeOnSale(...)` |
| `ResultBody<Object> batchOnSale(SubmitBody<CashShopBatchOnSaleReqDTO> request)`<br>POST `/cashShop/v1/batchOnSale` | 批量上架商品 | `cashShopService.batchChangeOnSale(request.getData())` |

调用 Service：`CashShopService`。

## CharacterController

角色管理接口，`@RestController` + `@RequestMapping("/character")` + `@AllArgsConstructor`，注入 `CharacterService`（源码路径：`src/main/java/org/gms/controller/CharacterController.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `ResultBody<Object> updateRate(SubmitBody<ExtendValueDO> submitBody)`<br>POST `/character/v1/updateRate` | 调整玩家个人倍率 | `characterService.updateRate(...)`；`extendName` 取 `expRate`/`mesoRate`/`dropRate` |
| `ResultBody<Object> resetRate(SubmitBody<ExtendValueDO> submitBody)`<br>POST `/character/v1/resetRate` | 重置玩家个人单项倍率 | `characterService.resetRate(...)`，同上三种 extendName |
| `ResultBody<Object> resetRates(SubmitBody<ExtendValueDO> submitBody)`<br>GET `/character/v1/resetRates` | 重置玩家全部个人倍率 | `characterService.resetRates(...)`（注意源码用 GET 却带 `@RequestBody`，依赖工具支持） |
| `ResultBody<Page<ChrOnlineListRtnDTO>> onlineList(SubmitBody<ChrOnlineListReqDTO> submitBody)`<br>POST `/character/v1/online/list` | 分页查询在线玩家列表 | `characterService.getChrOnlineList(...)` |
| `ResultBody<List<CharacterListItemDTO>> getAccountCharacters(int accountId)`<br>GET `/character/v1/account/{accountId}` | 获取指定账号下角色列表 | `characterService.getCharacterListByAccountId(accountId)` |
| `ResultBody<Object> delete(int cid)`<br>DELETE `/character/v1/{cid}` | 删除角色 | `characterService.deleteCharacterWithOnlineCheck(cid)`（在线校验后删除） |

调用 Service：`CharacterService`。

## CommandController

GM 命令库与游戏资源重载接口，`@RestController` + `@RequestMapping("/command")` + `@AllArgsConstructor`，注入 `CommandService`（源码路径：`src/main/java/org/gms/controller/CommandController.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `ResultBody<Page<CommandReqDTO>> getCommandListFromDB(SubmitBody<CommandReqDTO> submitBody)`<br>POST `/command/v1/getCommandListFromDB` | 分页查询命令库全部指令与启用状态 | `commandService.getCommandListFromDB(...)` |
| `ResultBody<CommandInfoDO> updateCommand(SubmitBody<CommandReqDTO> submitBody)`<br>POST `/command/v1/updateCommand` | 更新命令配置 | `commandService.updateCommand(...)` 返回更新后的 DO |
| `ResultBody reloadEventsByGMCommand()`<br>GET `/command/v1/reloadEventsByGMCommand` | 重载活动事件 | 复用 GM 命令代码：`commandService.reloadEventsByGMCommand()`（注意返回裸 `ResultBody` 未带泛型） |
| `ResultBody reloadPortalsByGMCommand()`<br>GET `/command/v1/reloadPortalsByGMCommand` | 重装传送点 | `commandService.reloadPortalsByGMCommand()` |
| `ResultBody reloadMapsByGMCommand()`<br>GET `/command/v1/reloadMapsByGMCommand` | 重装地图 | `commandService.reloadMapsByGMCommand()` |

调用 Service：`CommandService`。

## CommonController

通用查询接口（装备属性、在线人数、资料搜索），`@RestController` + `@RequestMapping("/common")` + `@AllArgsConstructor`，注入 `CommonService`（源码路径：`src/main/java/org/gms/controller/CommonController.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `ResultBody<Object> getEquipmentInfoByItemId(SubmitBody<EquipmentInfoReqDTO> submitBody)`<br>POST `/common/v1/getEquipmentInfoByItemId` | 查询装备基础属性 | `commonService.getEquipmentInfoByItemId(...)`（读 WZ 数据） |
| `ResultBody<Integer> getAllWorldsOnlinePlayersCount(SubmitBody<ServerInfoReqDto> submitBody)`<br>POST `/common/v1/getAllWorldsOnlinePlayersCount` | 查询指定世界列表当前在线人数 | 取 `data.getWorldIdList()` 传 `commonService.getAllWorldsOnlinePlayersCount(...)` 求和返回 |
| `ResultBody<List<InformationResult>> informationSearch(SubmitBody<InformationSearch> submitBody)`<br>POST `/common/v1/informationSearch` | 资料（物品/怪物等）搜索 | `commonService.getInformation(...)`，按 id 或 name 模糊匹配 |

调用 Service：`CommonService`。

## ConfigController

动态游戏参数（`game_config` 表 / `GameConfig`）管理接口，`@RestController` + `@RequestMapping("/config")` + `@AllArgsConstructor`，注入 `ConfigService`（源码路径：`src/main/java/org/gms/controller/ConfigController.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `ResultBody<ConfigTypeDTO> getConfigTypeList()`<br>GET `/config/v1/getConfigTypeList` | 获取参数大类与参数类型树 | `configService.getConfigTypeList()`（供前端下拉筛选） |
| `ResultBody<Page<GameConfigDO>> getConfigList(SubmitBody<GameConfigReqDTO> request)`<br>POST `/config/v1/getConfigList` | 分页获取参数列表 | `configService.getConfigList(request.getData())`，成功回带原信封 |
| `ResultBody<Object> addConfig(SubmitBody<GameConfigDO> request)`<br>POST `/config/v1/addConfig` | 新增参数 | `configService.addConfig(...)`（Service 内同步写入 `GameConfig` 树） |
| `ResultBody<Object> updateConfig(SubmitBody<GameConfigDO> request)`<br>POST `/config/v1/updateConfig` | 修改参数 | `configService.updateConfig(...)`（热重载，`GameConfig.update` 会写回 World 倍率等） |
| `ResultBody<Object> deleteConfig(Long id)`<br>DELETE `/config/v1/deleteConfig/{id}` | 删除单条参数 | `configService.deleteConfig(id)` |
| `ResultBody<Object> deleteConfigList(SubmitBody<List<Long>> request)`<br>POST `/config/v1/deleteConfigList` | 批量删除参数 | `configService.deleteConfigList(request.getData())` |
| `ResultBody<Object> importYml(MultipartFile file)`<br>POST `/config/v1/importYml`（multipart/form-data） | 从上传的 yml 导入参数 | `configService.importYml(file)`，不走 `SubmitBody` 信封 |
| `ResponseEntity<Resource> exportYml()`<br>GET `/config/v1/exportYml` | 导出参数为 yml 文件 | 直接返回 `configService.exportYml()` 的文件流响应（Content-Disposition 下载） |

调用 Service：`ConfigService`。

## DropController

怪物掉落与全局掉落管理接口，`@RestController` + `@RequestMapping("/drop")` + `@AllArgsConstructor`，注入 `DropService`（源码路径：`src/main/java/org/gms/controller/DropController.java`）。普通掉落与全局掉落成对出现，以 `global` 布尔参数区分，统一走 `dropService.getDropList(data, global)` / `dropService.modifyDropData(data, global, delete)`。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `ResultBody<Page<DropSearchRtnDTO>> getDropList(SubmitBody<DropSearchReqDTO> request)`<br>POST `/drop/v1/getDropList` | 分页获取掉落列表 | `getDropList(data, false)` |
| `ResultBody<Page<DropSearchRtnDTO>> getGlobalDropList(SubmitBody<DropSearchReqDTO> request)`<br>POST `/drop/v1/getGlobalDropList` | 分页获取全局掉落列表 | `getDropList(data, true)` |
| `ResultBody<Long> addDropData(SubmitBody<DropSearchRtnDTO> request)`<br>PUT `/drop/v1/addDropData` | 新增掉落，返回新 id | 先 `data.setId(null)` 防误带 id，`modifyDropData(data, false, false)` |
| `ResultBody<Long> addGlobalDropData(SubmitBody<DropSearchRtnDTO> request)`<br>PUT `/drop/v1/addGlobalDropData` | 新增全局掉落，返回新 id | `setId(null)` 后 `modifyDropData(data, true, false)` |
| `ResultBody<Object> updateDropData(SubmitBody<DropSearchRtnDTO> request)`<br>POST `/drop/v1/updateDropData` | 按 id 更新掉落 | 先 `RequireUtil.requireNotNull(data.getId(), i18n "PARAMETER_SHOULD_NOT_NULL"("id"))`，再 `modifyDropData(data, false, false)` |
| `ResultBody<Object> updateGlobalDropData(SubmitBody<DropSearchRtnDTO> request)`<br>POST `/drop/v1/updateGlobalDropData` | 按 id 更新全局掉落 | 同上，`modifyDropData(data, true, false)` |
| `ResultBody<Object> deleteDropData(Long id)`<br>DELETE `/drop/v1/deleteDropData/{id}` | 按 id 删除掉落 | 用 `DropSearchRtnDTO.builder().id(id).build()` 调 `modifyDropData(dto, false, true)` |
| `ResultBody<Object> deleteGlobalDropData(Long id)`<br>DELETE `/drop/v1/deleteGlobalDropData/{id}` | 按 id 删除全局掉落 | 同上，`modifyDropData(dto, true, true)` |

调用 Service：`DropService`。

## FileController

服务器脚本文件树编辑接口，`@RestController` + `@RequestMapping("/file")` + `@AllArgsConstructor`，注入 `FileTreeService`（源码路径：`src/main/java/org/gms/controller/FileController.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `ResultBody<String> treeRead(SubmitBody<FileReadDTO> request)`<br>POST `/file/v1/tree/read` | 读取文件内容 | `fileTreeService.readFile(data.getCurrentKey(), data.getTitle())`（key 定位根目录，title 为文件标题） |
| `ResultBody<String> treeWrite(SubmitBody<FileWriteDTO> request)`<br>POST `/file/v1/tree/write` | 写入文件 | `fileTreeService.writeFile(currentKey, title, content)`，成功消息「写入成功」 |
| `ResultBody<List<FileTreeNodeDTO>> tree(SubmitBody<FileTreeDTO> request)`<br>POST `/file/v1/tree` | 读取目录文件树 | `fileTreeService.tree(data.getCurrentKey())` 返回节点列表 |

调用 Service：`FileTreeService`。

## GachaponController

扭蛋机奖池/奖品管理接口，`@RestController` + `@RequestMapping("/gachapon")`，`@Autowired` 字段注入 `GachaponService`（源码路径：`src/main/java/org/gms/controller/GachaponController.java`）。全部 POST。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `ResultBody<Page<GachaponPoolSearchRtnDTO>> getPools(SubmitBody<GachaponPoolSearchReqDTO> request)`<br>POST `/gachapon/v1/getPools` | 分页获取奖池列表 | `gachaponService.getPools(data)` |
| `ResultBody<Object> updatePool(SubmitBody<GachaponRewardPoolDO> request)`<br>POST `/gachapon/v1/updatePool` | 创建或更新奖池 | `gachaponService.updatePool(data)`（id 空即新建） |
| `ResultBody<Object> deletePool(SubmitBody<GachaponRewardPoolDO> request)`<br>POST `/gachapon/v1/deletePool` | 删除奖池 | `gachaponService.deletePool(data.getId())` |
| `ResultBody<List<GachaponRewardDO>> getRewards(SubmitBody<GachaponRewardPoolDO> request)`<br>POST `/gachapon/v1/getRewards` | 获取奖池下奖品列表 | `gachaponService.getRewards(data.getId())` |
| `ResultBody<Object> updateReward(SubmitBody<GachaponRewardDO> request)`<br>POST `/gachapon/v1/updateReward` | 创建或更新奖品 | `gachaponService.updateReward(data)` |
| `ResultBody<Object> deleteReward(SubmitBody<GachaponRewardDO> request)`<br>POST `/gachapon/v1/deleteReward` | 删除奖品 | `gachaponService.deleteReward(data.getId())` |

调用 Service：`GachaponService`。

## GiveController

资源发放接口，`@RestController` + `@RequestMapping("/give")` + `@AllArgsConstructor` + 字段 `@Autowired`，注入 `GiveService`（源码路径：`src/main/java/org/gms/controller/GiveController.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `ResultBody<Object> giveResource(SubmitBody<GiveResourceReqDTO> submitBody)`<br>POST `/give/v1/resource` | 给在线玩家发放资源（点卷/道具等） | `giveService.give(data)`，由 Service 定位在线角色并投递 |

调用 Service：`GiveService`。

## InventoryController

玩家背包管理接口，`@RestController` + `@RequestMapping("/inventory")` + `@AllArgsConstructor`，注入 `InventoryService`（源码路径：`src/main/java/org/gms/controller/InventoryController.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `ResultBody<List<InventoryTypeRtnDTO>> getInventoryTypeList()`<br>GET `/inventory/v1/getInventoryTypeList` | 获取所有背包分类（装备/消耗/设置等） | `inventoryService.getInventoryTypeList()` |
| `ResultBody<Page<InventorySearchReqDTO>> getCharacterList(SubmitBody<InventorySearchReqDTO> request)`<br>POST `/inventory/v1/getCharacterList` | 按条件分页查询持有物品的玩家列表 | `inventoryService.getCharacterList(data)` |
| `ResultBody<List<InventorySearchRtnDTO>> getInventoryList(SubmitBody<InventorySearchReqDTO> request)`<br>POST `/inventory/v1/getInventoryList` | 获取指定玩家某背包分类下全部物品 | `inventoryService.getInventoryList(data)` |
| `ResultBody<Object> updateInventory(SubmitBody<InventorySearchRtnDTO> request)`<br>POST `/inventory/v1/updateInventory` | 修改玩家背包物品（数量/位置等） | `inventoryService.updateInventory(data)` |
| `ResultBody<Object> deleteInventory(SubmitBody<InventorySearchRtnDTO> request)`<br>POST `/inventory/v1/deleteInventory` | 删除玩家背包物品 | `inventoryService.deleteInventory(data)` |

调用 Service：`InventoryService`。

## ServerController

游戏服生命周期与大区/频道查询接口，`@RestController` + `@RequestMapping("/server")` + `@AllArgsConstructor`，注入 `ApplicationContext` 与 `ServerService`，并直接操作 Netty 单例 `Server.getInstance()`（源码路径：`src/main/java/org/gms/controller/ServerController.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `void shutdown()`<br>GET `/server/v1/shutdown` | 停止整个 JVM | 先 `SpringApplication.exit(applicationContext)` 触发容器销毁（含 `ServerManager.destroy` 关服），再 `System.exit(0)` 真正退出；返回 void（进程即将终止） |
| `ResultBody<Object> stopServer()`<br>GET `/server/v1/stopServer` | 停止游戏服（保留 Spring） | `Server.getInstance().shutdownInternal(false)`（无停服公告） |
| `ResultBody<Object> stopServerWithMsgAndInternal(SubmitBody<ServerShutdownDTO> request)`<br>POST `/server/v1/stopServerWithMsgAndInternal` | 自定义停服 | `System.out.println(data)` 后 `Server.getInstance().shutdownWithMsgAndInternal(data)`；DTO 含停服自定义消息与倒计时（分钟） |
| `ResultBody<Object> startServer()`<br>GET `/server/v1/startServer` | 启动游戏服 | `Server.getInstance().init()`（与 `ServerManager.run` 启动逻辑相同，用于停服后再次拉起） |
| `ResultBody<Object> restartServer()`<br>GET `/server/v1/restartServer` | 重启游戏服 | `Server.getInstance().shutdownInternal(true)`（内部完成关停后重启） |
| `ResultBody<Boolean> online()`<br>GET `/server/v1/online` | 查询游戏服状态 | `ResultBody.success(Server.getInstance().isOnline())` |
| `ResultBody<Object> worldList()`<br>GET `/server/v1/world/list` | 大区列表 | `serverService.worldList()` |
| `ResultBody<List<ChannelListRtnDTO>> channelList(int worldId)`<br>GET `/server/v1/channel/list?worldId=` | 指定大区频道列表 | `serverService.channelList(worldId)` |
| `ResultBody<String> version()`<br>GET `/server/v1/version` | 查询服务端版本号 | 返回 `ServerConstants.BEI_DOU_VERSION` |

调用 Service：`ServerService`（另直接调用 `org.gms.net.server.Server` 与 `SpringApplication`）。

## ShopController

游戏内 NPC 商店管理接口，`@RestController` + `@RequestMapping("/shop")` + `@AllArgsConstructor`，注入 `ShopService`；类注释注明「商城（点卷）相关用 cashShop 命名」（源码路径：`src/main/java/org/gms/controller/ShopController.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `ResultBody<Page<ShopSearchRtnDTO>> getShopList(SubmitBody<ShopSearchReqDTO> request)`<br>POST `/shop/v1/getShopList` | 分页获取商店列表 | `shopService.getShopList(data)` |
| `ResultBody<Page<ShopItemSearchRtnDTO>> getShopItemList(SubmitBody<ShopSearchReqDTO> request)`<br>POST `/shop/v1/getShopItemList` | 按商店 id 分页获取商品列表 | `shopService.getShopItemList(data)` |
| `ResultBody<ShopItemSearchRtnDTO> getShopItem(Long id)`<br>GET `/shop/v1/getShopItem/{id}` | 按 id 查询商品明细 | `shopService.getShopItem(id)` |
| `ResultBody<Long> addShopItem(SubmitBody<ShopItemSearchRtnDTO> request)`<br>PUT `/shop/v1/addShopItem` | 新增商品，返回新 id | 先 `data.setId(null)`，`shopService.modifyShopItem(data, false)` |
| `ResultBody<Object> updateShopItem(SubmitBody<ShopItemSearchRtnDTO> request)`<br>POST `/shop/v1/updateShopItem` | 按 id 更新商品 | 先 `RequireUtil.requireNotNull(data.getId(), i18n "PARAMETER_SHOULD_NOT_NULL"("id"))`，再 `modifyShopItem(data, false)` |
| `ResultBody<Object> deleteShopItem(Long id)`<br>DELETE `/shop/v1/deleteShopItem/{id}` | 按 id 删除商品 | `shopService.modifyShopItem(ShopItemSearchRtnDTO.builder().id(id).build(), true)` |

调用 Service：`ShopService`。
