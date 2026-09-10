# 详细设计文档 — Service 层（org.gms.service）

- **模块路径**：`gms-server/src/main/java/org/gms/service/`
- **类数量**：31（Service 29 个 + Spring Security `UserDetails` 实现 2 个）
- **依赖模块**：
  - 持久层 `org.gms.dao.mapper` / `org.gms.dao.entity`（MyBatis-Flex，实体后缀 `DO`）
  - 遗留游戏服运行时 `org.gms.net.server.Server`（单例，非 Spring bean）、`client.Character`、`server.*`
  - 工具层 `org.gms.util`（`I18nUtil`、`RequireUtil`、`BasePageUtil`、`DatabaseConnection`、`BCrypt` 等）
  - 配置层 `org.gms.config.GameConfig`（动态游戏配置单例）、`org.gms.property.ServiceProperty`
  - 数据模型 `org.gms.model.dto` / `org.gms.model.pojo`（见《05-model-dto-pojo.md》）
- **通用约定**：Lombok 全量使用（`@Service` + `@AllArgsConstructor` + `@Slf4j` 为主）；入参分页 DTO 继承 `BasePageDTO`，出参分页统一 MyBatis-Flex `Page<T>`；REST 信封为 `SubmitBody<T>` / `ResultBody<T>`（由 controller 层处理，Service 不感知）；所有面向人的文案走 `I18nUtil`。

---

## AccountService

> 源码：`service/AccountService.java`

账号中心服务：账号查询/分页、增改、密码加密校验、封禁/解封、登录态重置、快捷键读取；并负责"封号时收雇佣商店"这一横跨在线世界的收店逻辑。

**依赖**：`AccountsMapper`、`CharactersMapper`、`IpbansMapper`、`MacbansMapper`、`QuickslotkeymappedMapper`；静态依赖 `Server`（取 World/在线角色）、`GameConfig`（bcrypt 迁移开关）、`HiredMerchant`。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `AccountsDO findByName(String name)` | 按用户名查账号 | 直接 `selectOneByName`，无结果返回 null |
| `AccountsDO findById(int id)` | 按主键查账号 | `selectOneById` |
| `AccountsDO getCurrentUser()` | 取当前 REST 请求登录账号 | 从 `SecurityContextHolder` 取 `UserDetails` 用户名再 `findByName` |
| `Page<AccountsDO> getAccountList(Integer page, Integer size, Integer id, String name, String lastLoginStart, String lastLoginEnd, String createdAtStart, String createdAtEnd)` | 账号分页检索 | 动态拼接 QueryWrapper（id 精确、name like、最后登录/创建时间范围）；page 默认 1，size 默认 `Integer.MAX_VALUE`（即不分页）；`paginateWithRelations` 带关联 |
| `void update(AccountsDO condition)` | 更新账号（部分字段） | `update > 0` 且被封禁（`banned=TRUE` 或 tempban 在未来）时，级联 `closeAccountHiredMerchants` |
| `void closeAccountHiredMerchants(int accountId)` | 按账号全角色、全世界关闭雇佣商店 | 查账号下角色（仅 id+world），遍历所有 World 的 `getHiredMerchant(chrId)`，非空则 `closeForBan()`；每步 try-catch 记 i18n 错误日志不中断（雇佣商店不依赖角色在线） |
| `void addAccount(AddAccountDTO submitData)` | 新增账号 | 校验 language 非空（防 swagger 直调）、用户名唯一；`encryptPassword` 加密；tempban/lastlogin 用 `DefaultDates.getTempban()` 默认值；`insertSelective` 忽略 null |
| `void updateAccountByUser(UpdateAccountByUserDTO submitData)` | 用户自助改资料 | 先 `checkPassword` 校验旧密码；新密码长度 ≥6 才更新；只写 pin/pic/birthday/nick/email/language 等低权限字段 |
| `void updateAccountByGM(int id, UpdateAccountByGmDTO submitData)` | GM 改任意账号 | 校验账号存在、language 非空、账号未登录（`LOGIN_LOGGEDIN` 拒绝）；可改密码、三种 NX 货币、角色槽位、性别、webadmin、mute、点数等高权限字段 |
| `String encryptPassword(String password)` | 密码加密 | `GameConfig.getServerBoolean("bcrypt_migration")` 为真用 `BCrypt.hashpw(…, gensalt(12))`，否则旧版 `hashpwSHA512` |
| `boolean checkPassword(String pwd, AccountsDO accountsDO)` | 密码校验（多算法兼容） | 先识别 `$2` 前缀走 `BCrypt.checkpw`；否则依次尝试明文相等、SHA-1、SHA-512 十六进制比对（兼容历史遗留账号） |
| `void resetAllLoggedIn(int id)` | 重置单账号登录态 | 置 `loggedin=LOGIN_NOTLOGGEDIN` |
| `void banAccount(int accountId, String reason)` | 封停账号并追封在线角色 | 账号置 banned+原因；遍历账号角色，在线者：`player.setBanned(true)`、`c.banMacs()`、插入 `IpbansDO`（HWID 封禁因不可逆被注释掉）、`c.disconnect(false,false)` 强制下线 |
| `void unbanAccount(int accountId)` | 解封 | 账号 banned=false；按 aid 删除 macbans、ipbans 记录 |
| `void resetAllLoggedIn()` | 服务启动时全量重置登录态 | `updateAllLoggedIn(0)`（Mapper 自定义 SQL） |
| `void ban(Character chr, String reason)` | 封禁在线角色所在账号 | `update`（封号+原因）并同步内存 `chr.setBanned(true)` |
| `void ban(String str, String reason, boolean isAccount)` | 按名字/IP 封禁 | 参数匹配 IP 正则则插 ipbans；否则 isAccount 时按账号名、否则按角色名反查 accountId；找不到抛 `NoSuchElementException` |
| `boolean isBanned(String ip)` | IP 是否被封 | ipbans 计数 > 0 |
| `QuickslotkeymappedDO getQuickSlotKeyMap(int accountId)` | 取账号快捷键配置 | `selectOneById`（主键即 accountId） |

私有：`checkHash(hash, type, password)` 用 `MessageDigest` 做 SHA-1/512 十六进制比对。

---

## AuthService

> 源码：`service/AuthService.java`

REST 后台登录鉴权服务：签发与刷新 JWT。

**依赖**：`AccountService`、`JwtUtils`（`org.gms.util`）。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `Map<String, String> getToken(String name, String password)` | 登录换 token | `findByName` + `checkPassword` 校验，失败抛 i18n 业务异常；成功返回 `{token: jwt}` |
| `Map<String, String> refreshToken(String token)` | 刷新 token | 校验 `Bearer ` 前缀，从旧 token 解用户名，账号存在则重签；不合法返回 null |

---

## AutobanConfigService

> 源码：`service/AutobanConfigService.java`

自动封禁（autoban）配置服务：把 `autoban_config` 表加载进 `AutobanFactory` 内存缓存，并提供后台 CRUD。积分单位为"点"，检测周期前端秒 / 库内毫秒互转，`-1` 表示永不过期（保持不变）。

**依赖**：`AutobanConfigMapper`；静态依赖 `client.autoban.AutobanFactory`（枚举 + 配置缓存）。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void loadConfigs()` | 启动时全量加载配置到内存 | `selectAll` → `Map<type, AutobanConfigDO>` → `AutobanFactory.initConfig` |
| `List<AutobanConfigDTO> getConfigList()` | 配置列表（含枚举默认值） | 遍历 `AutobanFactory.values()`，组装 DTO：库有记录则覆盖 points/expireTime/disabled，并按字段是否非 null 设置 `changePoints/changeExpireTime`（前端据此决定勾选态）；无记录则给枚举默认值 |
| `void updateConfig(AutobanConfigDTO dto)` | 新增或更新单条配置（`@Transactional`） | type 非空校验；按 changeXxx 决定 points/expireTime 是取值还是置 null；无既有记录且无可存内容 → 只调 `AutobanFactory.updateConfig(type, null)` 清缓存返回；否则 insertSelective / update，回写 DTO id，并同步 `AutobanFactory` 缓存 |

---

## CashShopService

> 源码：`service/CashShopService.java`

点券商城（CashShop）商品管理：以 wz 数据为基底、`modified_cash_item` 表为覆盖层（仅存差异字段），支持按分类/单品查询与上下架、批量上架。

**依赖**：`ModifiedCashItemMapper`；静态依赖 `CashShop.CashItemFactory`（wz 商品+DB 覆盖缓存）、`ItemInformationProvider`、`DataProviderFactory/WZFiles`（读 ETC/Category.img）、`CategoryType`。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `List<ModifiedCashItemDO> loadAllModifiedCashItems()` | 加载全部 DB 覆盖商品 | `selectAll`，供 CashItemFactory 启动时调用 |
| `List<CashCategory> getAllCategoryList()` | 商城分类树 | 解析 wz `ETC/Category.img`，每节点取 Category/CategorySub/Name，主分类名经 `CategoryType.toName` |
| `Page<CashShopSearchRtnDTO> getCommodityByCategory(CashCategory data)` | 按分类分页查商品 | id/subId 必填；`pageSize` 固定 10（与客户端一致）；以 SN 前缀（id+两位 subId）过滤 wz 商品；DB 覆盖层按 sn 匹配后用 `setDbItemValue` 覆盖（null 不覆盖）；再按 onSale/itemId 过滤；补 itemName；按 priority 降序 + itemId 升序排序分页（`BasePageUtil`） |
| `CashShopSearchRtnDTO getCommodityBySn(Integer sn)` | 按 SN 查单个商品 | SN 第 1 位为分类 id、2-3 位为 subId；取 wz 商品后套 DB 覆盖 |
| `void changeOnSale(ModifiedCashItemDO data)` | 上下架/修改单个商品（`@Transactional`） | 先删该 sn 的 DB 覆盖记录；下架时若 wz 在售则插入 `onSale=0`；上架时逐字段与 wz 值相等则置 null（只存差异）；最后 `insertSelective` 并 `CashShop.CashItemFactory.loadAllModifiedCashItems()` 热刷新 |
| `void batchChangeOnSale(CashShopBatchOnSaleReqDTO submit)` | 批量上架（`@Transactional`） | 遍历 data 置 onSale=1，按 type（价格/数量/有效期）统一设置 value，逐条复用 `changeOnSale` |

私有：`getCategory(id, subId)` 从 CashItemFactory 分类缓存查找（找不到抛 BizException）；`fromCashItem` 把 wz 商品 + 分类转 DTO（每字段都带 default* 备份）；`setDbItemValue` 用 DB 覆盖层的非空字段覆盖 DTO。

---

## CharacterService

> 源码：`service/CharacterService.java`（全模块最大的 Service，角色全生命周期）

角色服务：角色查询/排行/在线列表、角色级倍率扩展值管理、角色删除（含全量级联）、角色删除与账号删除、从 DB 加载 `Character`、存档入口等。

**依赖**（Mapper 30+ 个）：`CharactersMapper`、`SkillsMapper`、`SkillmacrosMapper`、`GuildsMapper`、`BuddiesMapper`、`BbsThreadsMapper`、`BbsRepliesMapper`、`WishlistsMapper`、`CooldownsMapper`、`PlayerdiseasesMapper`、`AreaInfoMapper`、`MonsterbookMapper`、`FamilyCharacterMapper`、`FamelogMapper`、`FredstorageMapper`、`KeymapMapper`、`SavedlocationsMapper`、`TrocklocationsMapper`、`EventstatsMapper`、`ServerQueueMapper`、`BosslogDailyMapper`、`BosslogWeeklyMapper`、`FamilyEntitlementMapper`、`InventorymerchantMapper`、`AccountsMapper`、`QuickslotkeymappedMapper`、`StoragesMapper`、`InventoryitemsMapper`、`HwidaccountsMapper`、`IpbansMapper`、`MacbansMapper`、`ExtendValueMapper`；Service：`InventoryService`、`QuestService`、`MtsService`、`NameChangeService`、`WorldTransferService`；另注入 `ApplicationContext`（自代理保证事务传播）。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `CharactersDO findById(int id)` | 按主键查角色 | `selectOneById` |
| `void update(CharactersDO condition)` | 部分字段更新角色 | `charactersMapper.update` |
| `Page<ChrOnlineListRtnDTO> getChrOnlineList(ChrOnlineListReqDTO request)` | 在线角色分页 | 从指定 world 的 `PlayerStorage` 取全部在线 `Character`，内存过滤（id/name/map），`BasePageUtil` 映射为 DTO |
| `void updateRate(ExtendValueDO data)` | 设置角色/账号倍率扩展值 | `checkName` 只允许 expRate/dropRate/mesoRate；按 extendId+type+name 查 `ExtendValue`，无则 insertSelective、有则 update（保留 createTime）；随后找到在线 `Character` 调 `resetPlayerRates/setWorldRates/setCouponRates` 热生效 |
| `void resetRate(ExtendValueDO data)` | 删除单个扩展值 | 按 extendId+type+name 删除后同样热刷新在线角色倍率 |
| `void resetRates(ExtendValueDO data)` | 删除三项倍率扩展值 | name 用 `in("expRate","dropRate","mesoRate")`，其余同上 |
| `void resetMerchant()` | 重置全部角色雇佣商店标记 | `updateAllHasMerchant(0)`（启动时清理脏状态） |
| `List<List<CharactersDO>> getWorldsRankPlayers(int worldSize)` | 排行榜（全服或分世界） | `use_whole_server_ranking` 开启则查全服前 50 单列表；否则逐世界 `getWorldRankPlayers` |
| `List<CharactersDO> getWorldRankPlayers(int worldId)` | 单世界前 50 | characters join accounts，过滤 GM≥2 与封禁账号，按 level desc、exp desc、lastExpGainTime asc，limit 50 |
| `CharactersDO findByName(String name)` | 按名查角色 | name 精确查询取第一条 |
| `void removeSkill(SkillsDO skillsDO)` | 删除技能记录 | `deleteByQuery(QueryWrapper.create(skillsDO))`（按实体条件） |
| `void deleteGuild(GuildsDO guildsDO)` | 删除公会（`@Transactional`） | 先把成员 guildid=0/guildrank=5，再删公会记录 |
| `void deleteCharFromDB(Character player, int senderAccId)` | 客户端删角入口（`@Transactional`） | 鉴权 `Server.haveCharacterEntry(senderAccId, cid)`（防越权删角漏洞），通过后 `deleteCharacterById` |
| `void deleteCharacterById(int cid)` | 按角色 ID 级联删除全部关联数据（`@Transactional`） | 无登录态鉴权，GM 后台/账号级联复用。顺序：guild（`deleteGuildCharacter`，传 null character 只 leave/disband）→ 好友（双向，在线 buddy 同步 `deleteBuddy`）→ BBS 帖/回 → wishlists → cooldowns → playerdiseases → area_info → monsterbook → characters → family_character → famelog（双向）→ 背包（`inventoryService.deleteInventoryByCharacterId`，含宠物/戒指与 CashIdGenerator 回收）→ 任务（`questService.deleteQuestProgressByCharacter`）→ fredstorage → 拍卖行（`mtsService.deleteMtsByCharacterId`）→ keymap → savedlocations → trocklocations → skills/skillmacros → eventstats → server_queue → bosslog daily/weekly → family_entitlement → inventorymerchant → character 三类扩展值（EXTEND_VALUE 表按 extendId+type in 删）→ characterexplogs（无 Mapper，原生 JDBC try-with-resources）→ `nameChangeService/worldTransferService.cancelPending*(cid,false)`（补 Heaven 缺失的两张表清理） |
| `void saveCharToDB(Character player, boolean notAutosave)` | 角色存档（**INSERT 版**，见下文专节） | 未登录直接返回；`updateCharacterEntry` 后 `Character.toCharactersDO` + `charactersMapper.insertSelective(cdo)` |
| `Character loadCharFromDB(int cid, Client client, boolean channelServer)` | 从 DB 加载角色对象 | `Character.fromCharactersDO`；channelServer=true 时：绑图（map 不存在回退 Henesys、portal 不存在回退 0 号并重置出生点）、恢复队伍/群聊 messenger、置 loggedIn；随后装载任务（`questService.getQuestStatusByCharacter`）、技能（SkillFactory + SkillEntry）、冷却（过滤已过期与 5221999，装载后即删表）、疾病（转 MobSkill 存入 PlayerBuffStorage，装载后删表）、技能宏、键位、保存点、30 天内人气记录、好友列表、账号仓库（不存在则 load）、`reapplyLocalStats`、恢复 HP/MP |
| `List<TrocklocationsDO> getTrockLocationByCharacter(Integer cid)` | 传送石定位 | 按 characterid 查 |
| `List<AreaInfoDO> getAreaInfoByCharacter(Integer cid)` | 区域信息 | 同上 |
| `List<EventstatsDO> getEventStatsByCharacter(Integer cid)` | 活动统计 | 同上 |
| `List<WishlistsDO> getWishlistsByCharacter(Integer cid)` | 愿望单 | 同上 |
| `List<CharactersDO> getCharacterByAccountId(int accountId)` | 账号下全部角色 | 按 accountid 查 |
| `List<CharacterListItemDTO> getCharacterListByAccountId(int accountId)` | 账号角色列表（后台展示） | `getCharacterByAccountId` 映射 DTO：补 worldName（越界回退字符串）、jobName、online（遍历世界找在线对象） |
| `void deleteCharacterWithOnlineCheck(int cid)` | 后台删角入口 | `prepareCharacterOffline` 先下线并取 accountId → `applicationContext.getBean(CharacterService.class).deleteCharacterById(cid)`（代理调用使 `@Transactional` 生效）→ `safeDeleteCharacterEntry` |
| `void safeDeleteCharacterEntry(int accountId, int cid)` | 安全清理登录缓存 | 直接调 `Server.deleteCharacterEntry` 会因账号未登录 NPE，此处捕获 NPE 兜底忽略 |
| `void deleteAccount(int id)` | 删除账号（`@Transactional`） | 账号存在性校验；自代理遍历账号下角色：`prepareCharacterOffline` + `deleteCharacterById` + `safeDeleteCharacterEntry`；再删账号级关联表（quickslotkeymapped、storages、inventoryitems、ipbans、macbans、hwidaccounts、server_queue、账号三类扩展值）；最后删 accounts。见 CLAUDE.md「账号/角色级联删除有坑」 |
| `int prepareCharacterOffline(int cid)` | 在线角色先下线并返回 accountId | 在线：`forceDisconnect` 后重新取 `getClient().closeSession()`；离线：从 DB 取 accountid；不存在返回 0 |

私有：`findOnlineCharacter(cid)` 遍历全部 World 的 PlayerStorage；`checkName/check` 校验扩展值入参防篡改（extendName 只许三项倍率）；`getCharacter(ExtendValueDO)` 按 extendType 是账号/角色在全部在线角色中定位目标，找不到抛非法参数异常。

### 特别说明：saveCharToDB 的两个版本

CLAUDE.md 提到「**saveCharToDB 有两个版本：角色保存实际生效的是 UPDATE 版本，而 CharacterService.insertSelective 那版并非实际生效路径**」，在代码中的体现：

1. **INSERT 版**（`CharacterService.saveCharToDB(Character player, boolean notAutosave)`，`service/CharacterService.java`）：仅 `Character.toCharactersDO(player)` + `charactersMapper.insertSelective(cdo)`，写整行 INSERT。全仓库检索该方法**没有任何调用方**（controller、`Client`、`Character`、任务类均未调用），是被 Spring 化改造时留下的"半成品/备份"路径。它存在真实缺陷：忽略 null 的 INSERT 无法承载"字段归零"语义，且缺少 skills/inventory/keymap 等关联表保存。
2. **UPDATE 版（实际生效）**：`client/Character.java` 的 `public synchronized void saveCharToDB(boolean notAutosave)`（约 7617 行起）。这是 OdinMS 血统的原生 JDBC 实现：手写 `UPDATE characters SET level = ?, fame = ?, … WHERE id = ?`（50+ 字段），自行管理 `con.setAutoCommit(false)` + `TRANSACTION_READ_UNCOMMITTED` 事务，持 `effLock/statWlock` 写锁保存属性，随后级联保存 skills、库存（`ItemFactory.saveItems`，注释明确"不接管事务，因 Character.saveCharToDB 已 setAutoCommit(false)"）、monsterbook、keymap 等关联表。调用方遍布实际链路：`Client`（断线/换图）、`CharacterAutosaverTask`（自动存档，经 `CharacterSaveService` 队列）、`SaveAllCommand`、`HiredMerchant`、`EnterCashShopHandler`/`EnterMTSHandler`/`WeddingHandler`/`PlayerInteractionHandler` 等。另有无参重载 `saveCharToDB()` 根据 `use_autosave` 配置决定走 `CharacterSaveService` 排队或直接同步保存。

排查角色存档问题时，必须以 `Character.saveCharToDB(boolean)`（UPDATE 版）为准，`CharacterService` 内的版本不参与运行时保存。

---

## CommandService

> 源码：`service/CommandService.java`

GM 指令服务：从 `command_info` 表加载指令注册表到 `CommandsExecutor`，并提供后台查询/开关/等级调整与 reload 类操作。

**依赖**：`CommandInfoMapper`；静态依赖 `CommandsExecutor`（单例）、`Server`、`PortalScriptManager`。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void loadCommands(HashMap<String, Command> registeredCommands, List<Pair<List<String>, List<String>>> commandsNameDesc)` | 启动时加载全部指令 | 清空后 `selectAll`，按 level 分组，level 0..6 逐级 `registerCommands`；空表仅告警 |
| `Page<CommandReqDTO> getCommandListFromDB(CommandReqDTO request)` | 指令分页查询 | 按 level/defaultLevel 多选 in、syntax like、enabled 精确；`paginateWithRelations` 后逐条转 DTO（description 由 `getDescriptionByCommandInfoDO` 反射取实例），并置 pageNo/pageSize 为 null 防递归序列化 |
| `String getDescriptionByCommandInfoDO(CommandInfoDO CommandDO)` | 取指令描述 | `getCommandInstance` 反射实例化并 `getDescription`；实例化失败返回 i18n 告警文案 |
| `CommandInfoDO updateCommand(CommandReqDTO request)` | 更新指令开关/等级（`@Transactional`） | enabled/id 必填；只允许改 level 与 enabled（注释说明 syntax/defaultLevel/clazz 改动会破坏提示一致性）；先 update DB 再 `updateRegisteredCommands` 同步内存注册表 |
| `void reloadEventsByGMCommand()` | 重载活动脚本 | 遍历所有 Channel `reloadEventScriptManager` |
| `void reloadPortalsByGMCommand()` | 重载传送门脚本 | `PortalScriptManager.reloadPortalScripts` |
| `void reloadMapsByGMCommand()` | 重载全部地图 | 遍历世界→频道→地图：先收集地图玩家，`resetMap` 重建后把玩家 `saveLocationOnWarp` + `changeMap` 回新图并提示 |

私有：`updateRegisteredCommands` 处理"未注册→开启则注册 / 已注册→按新等级迁移 nameDesc 或关闭则移除"的内存增量同步；`registerCommands` 按 level 填充注册表与 nameDesc（跳过未启用、实例化失败、重名指令）；`getCommandInstance` 以 `Class.forName("org.gms.client.command.commands.gm{defaultLevel}.{clazz}")` 反射构造并 setRank。

---

## CommonService

> 源码：`service/CommonService.java`

通用查询服务：装备基础属性查询、在线人数统计、通用信息检索（CommonInformation）。

**依赖**：`ItemService`（`@Autowired` 字段注入）；静态依赖 `Server`、`CommonInformation`。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `EquipmentInfoRtnDTO getEquipmentInfoByItemId(EquipmentInfoReqDTO submitData)` | 按物品 ID 查装备模板属性 | id 非空校验后委托 `itemService.getEquipmentInfoByItemId`，把 `Equip` 各属性映射到 DTO（`getInt→_int`、`getWatk→pAtk` 等命名转换） |
| `Integer getOnlinePlayerCountByWorldId(Integer worldId)` | 单世界在线人数 | worldId 为 null 返回 0；否则 `PlayerStorage.getSize()` |
| `Integer getAllWorldsOnlinePlayersCount(List<Integer> worldIdList)` | 多世界在线人数加总 | null 容错为空列表，stream mapToInt 求和 |
| `List<InformationResult> getInformation(InformationSearch condition)` | 通用信息检索 | filter 必填；types 为空则默认全部 `InformationType`；委托 `CommonInformation.getInstance().getStringInformation` |

---

## ConfigService

> 源码：`service/ConfigService.java`

动态游戏配置（game_config 表 / `GameConfig` 单例）后台管理：分页查询、增删改（热生效）、application.yml 导入导出。

**依赖**：`GameConfigMapper`、`ServiceProperty`（当前语言）、`LangResourceService`；静态依赖 `GameConfig`、`Server`、`DatabaseConnection`、SnakeYAML。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `List<GameConfigDO> loadGameConfigs()` | 全量加载 | `selectAll`，供 GameConfig 启动构建 JSON 树 |
| `ConfigTypeDTO getConfigTypeList()` | 配置类型去重列表 | 分别 distinct 查 configType、configSubType |
| `Page<GameConfigDO> getConfigList(GameConfigReqDTO condition)` | 配置分页查询 | join `lang_resources`（langCode=configDesc、langType=当前语言、langBase=game_config）带出描述列；按 type/subType 精确 + filter 对 code/描述 like |
| `void addConfig(GameConfigDO condition)` | 新增配置（`@Transactional`） | type/subType/code/value 必填；查重（world 类型额外带 subType）；描述写入 i18n 表（`langResourceService.insertOrUpdateI18n`）；DO 落库后 `GameConfig.add` 热生效 |
| `void updateConfig(GameConfigDO condition)` | 更新配置值（`@Transactional`） | id/value 必填；同步更新描述 i18n；只更新 value+updateTime；`GameConfig.update` 热生效（部分世界倍率会即时写回 World 对象） |
| `void deleteConfig(Long id)` | 删除单条（`@Transactional`） | 删除关联 i18n（不限定 langType，全语言删）→ 删配置 → `GameConfig.remove` |
| `void deleteConfigList(List<Long> ids)` | 批量删除（`@Transactional`） | 逐条 `deleteConfig` |
| `int importYml(MultipartFile file)` | 导入旧版 application.yml | 校验 .yml/.yaml；SnakeYAML 解析出 `gms.world.worlds[]` 与 `gms.server`；旧键名→新 config_code 映射（`replaceWithEquals` 精确 / `replaceWithContains` 包含替换，如 `wldlist_size→channel_size`、`host→wan_host`）；拼 UPDATE SQL 逐条执行（原生 JDBC）；最后 `Thread.startVirtualThread(Server.getInstance().shutdown(true))` 异步重启（注释：不能用 ThreadManager，停服会注销其线程）；固定返回 1 |
| `ResponseEntity<Resource> exportYml()` | 导出为 application.yml | world/server 两组配置按 configClazz 反序列化为原生类型（Map/BigDecimal 防科学计数/反射 Class）组装 `gms` 树，SnakeYAML dump 为 `export.yml` 下载流，暴露 CONTENT_DISPOSITION 头 |

私有：`parseObject`（Float/Double 用 `DecimalFormat("#.################")` 防科学计数）、`replaceWithEquals/replaceWithContains`（键名映射）、`toMap()`（按 configClazz 反序列化 value 的 Collector，重复 key 取第一个）。

---

## DropService

> 源码：`service/DropService.java`

怪物/全局掉落配置管理：分页查询与增删改，改完清缓存。

**依赖**：`DropDataMapper`、`DropDataGlobalMapper`；静态依赖 `ItemInformationProvider`、`MonsterInformationProvider`、`Quest`。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `Page<DropSearchRtnDTO> getDropList(DropSearchReqDTO data, boolean isGlobal)` | 掉落分页查询 | isGlobal 走 drop_data_global（continent/itemId/questId 条件），否则 drop_data（dropperId 条件）；dropperName/itemName 模糊先经 InformationProvider 反查 id 列表再 `in`（无匹配直接返回空页避免全表）；结果逐条补 itemName/mobName/questName |
| `Long modifyDropData(DropSearchRtnDTO data, boolean isGlobal, boolean isDelete)` | 增/改/删掉落记录 | 删除按 id；否则 builder 组装 DO（忽略差异字段）后 `insertOrUpdate(do, true)`（true=忽略 null）；最后 `MonsterInformationProvider.clearDrops()` 清运行时缓存；返回记录 id |

私有：`getItemName/getMobName/getQuestName` 三个名称补全辅助。

---

## FamilyService

> 源码：`service/FamilyService.java`

家族（Family）加载服务：启动时把 `family_character`/`family_entitlement` 表重建为各 World 内存中的 `Family`/`FamilyEntry` 对象树。

**依赖**：`FamilyCharacterMapper`、`FamilyEntitlementMapper`、`CharacterService`；静态依赖 `Server`、`client.Family/FamilyEntry`、`Job`。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void loadAllFamilies()` | 全量加载家族到内存 | 遍历 `family_character`：按角色查 world（角色/世界缺失跳过），world 无该 family 则新建注册；构造 `FamilyEntry`（含 reputation/todaysRep/totalReputation/repsToSenior），`family.addEntry`；seniorid≤0 设为族长并设家训；senior 已加载则直接挂链，否则进 `unmatchedJuniors` 延迟二轮匹配；逐角色查 entitlement 已用列表 `setEntitlementUsed`。第二轮处理未匹配 junior；最后对所有 family 族长 `doFullCount()` 刷新家族统计 |

---

## FileTreeService

> 源码：`service/FileTreeService.java`

服务端脚本/wz 文件树服务：供后台在线编辑 `scripts`、`scripts-zh-CN`、`wz`、`wz-zh-CN` 四个白名单目录。无 Mapper 依赖，纯文件 IO。

**依赖**：无（仅 Spring `@Service`）；基础目录为 `System.getProperty("user.dir")`。

**常量**：`FILE_TREE_KEY_DELIMITER="-"`（树 key 以目录列表下标路径编码，如 `3-1-2`）；`FILE_TREE_LIMITED_PATTERNS` 白名单四目录；`FILE_TREE_PATH_STRICT_MODE=true`（强校验）。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `String readFile(String currentKey, String filename)` | 读文件内容 | `resolveByTreeKey` 定位；`filename` 与实际文件名不一致抛"目录变动"异常；UTF-8 读取，遇 `MalformedInputException` 回退 ISO-8859-1 |
| `void writeFile(String currentKey, String filename, String content)` | 写文件 | 同样校验文件名一致性后 UTF-8 写回 |
| `List<FileTreeNodeDTO> tree(String currentKey)` | 列目录节点 | currentKey 空=根目录；要求当前是目录；父目录已在白名单内则列出全部子项，否则只列白名单命中的子项；子 key 为父 key + 下标拼接 |
| `File resolveByTreeKey(String currentKey)` | key → File 定位 | 按 `-` 分段下标逐层 `listFiles()[key]` 下钻；越界/非数字/NPE 抛"不存在"；严格模式下做两重防逃逸：绝对路径 normalize 后必须仍在基础目录内、必须命中白名单前缀，否则抛"路径逃逸尝试" |

私有：`matchAnyLimitPattern(Path)` 判断路径是否以基础目录下任一白名单目录开头。

---

## GachaponService

> 源码：`service/GachaponService.java`

百宝箱（Gachapon）奖池服务：奖池/奖品后台 CRUD + 运行时抽奖。公共奖池按概率（万分比）抽取，专属奖池按权重分摊剩余概率，总抽样空间 100 万点。

**依赖**：`GachaponRewardPoolMapper`、`GachaponRewardMapper`（`@Autowired` 字段注入）；静态依赖 `LifeFactory`（NPC 名）、`ItemInformationProvider`、`Gachapon`（日志）、`Randomizer`、`PacketCreator`、`Server`。

**并发**：静态 `poolRewardsCache`（奖池→奖品列表缓存）+ 公平 `ReentrantReadWriteLock`：读操作（查询/抽奖）读锁，写操作（增删改）写锁并失效对应缓存。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void updatePool(GachaponRewardPoolDO submit)` | 新增/更新奖池 | 写锁内：startTime 必填；公共池强制 gachaponId=-1、weight=0 且 prob 必填，专属池强制 prob=0 且 gachaponId/weight 必填；`insertOrUpdate` 后失效缓存 |
| `void deletePool(Integer id)` | 删奖池及奖品（`@Transactional`） | 写锁内删 pool + `pool_id=?` 的全部 reward，失效缓存 |
| `Page<GachaponPoolSearchRtnDTO> getPools(GachaponPoolSearchReqDTO condition)` | 奖池分页查询 | 读锁内：isPublic 降序；指定 gachaponId 时附带全部公共池；补 NPC 名（gachaponName）；指定具体百宝箱时 `setRealProb` 计算展示概率 |
| `List<GachaponRewardDO> getRewards(Integer poolId)` | 查奖池奖品 | 读锁；按 itemId 升序；逐条补 itemName |
| `void updateReward(GachaponRewardDO reward)` | 新增/更新奖品 | 写锁；`insertOrUpdate` 后失效所属池缓存 |
| `void deleteReward(Integer id)` | 删奖品 | 写锁；先查回所属 poolId 再删，失效缓存 |
| `void doGachapon(Character player, int gachaponId)` | 执行抽奖 | 读锁内：取生效奖池（时间窗内，按 id 排序）；空则提示并记 error；公共池积分=`prob*100`，专属池按权重瓜分 `1000000-公共总积分`；随机数落点确定奖池（理论极小概率全部未命中则兜底取第一个池）；`doReward` 发奖 |
| `List<GachaponRewardDO> getRewardsByNpcId(Integer npcId)` | NPC 下全部生效奖品 | 生效池 flatMap 各池奖品 |

私有：`setRealProb` 同抽奖口径计算展示概率；`getActivePools`（时间窗过滤，因有效期不能缓存）；`doReward`（池内等概率抽一件 → `gainItem`，背包满 gainItem 返回 null 直接返回防空指针；发玩家提示、`Gachapon.log` 记录、开启通知时全服广播）；`getPoolRewards`（带缓存读取）。

---

## GiveService

> 源码：`service/GiveService.java`

后台发放资源服务：对单角色或全服在线角色发放点券/金币/经验/物品/装备/倍率/GM 等级/人气/传送。

**依赖**：`CharacterService`（`@Autowired` 字段注入，倍率走 `updateRate`）；静态依赖 `Server`、`ItemInformationProvider`、`InventoryManipulator`、`CashShop` 常量、`ItemConstants`。

type 语义：0/1/2=点券三种、3=mesos、4=exp、5=物品、6=装备、7/8/9=exp/meso/dropRate、11=GM 等级、12=人气、13=传送。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void give(GiveResourceReqDTO submitData)` | 发放总入口 | `playerId==0` 走全服在线，否则单角色 |
| `private void giveAllOnlineChr(GiveResourceReqDTO)` | 全服分发 | 按 type 分发；物品/装备全服复用同一校验；全服不支持倍率设置（对应 case 被注释） |
| `private void giveChr(GiveResourceReqDTO)` | 单角色分发 | 校验 worldId/playerId 合法；从 PlayerStorage 取在线角色（离线抛 CHR_OFFLINE）；按 type 分发 |
| `private void giveItemChr/giveItemAllOnlineChr(...)` | 发物品 | 物品必须存在且非 EQUIP 类型（装备另走 giveEquip）；宠物特殊处理：quantity 视为天数，生成 petId 与过期时间后 `InventoryManipulator.addById` |
| `private void giveEquipChr/giveEquipAllOnlineChr(...)` | 发装备 | 校验 itemId 存在且为 EQUIP；带全套自选属性调 `chr.gainEquip(...)`（17 个属性参数透传） |
| `private void giveRateChr(Character, String, float)` | 设置角色倍率 | rate>0 校验；组 `ExtendValueDO`（extendId=角色id、type=CHARACTER_EXTEND）委托 `characterService.updateRate` |
| `private void giveGMChr(Character, Integer)` | 设 GM 等级 | 0~127 校验；升降序处理 hide（先降 hide 再降级 / 先升级再 hide，避免无权限时 hide 失败） |
| `private void giveFameChr(Character, Integer)` | 设置人气 | `setFame` + `updateSingleStat(Stat.FAME)` |
| `private void changeMap(Character, Integer)` | 传送 | 910000000（自由市场）先 `saveLocation("FREE_MARKET")` 再从 out00 进，其余直接 `changeMap` |
| `private void doGainCash/doGainExp/doGainMeso(...)` | 数值加法兜底 | 全部 long 溢出检查：负数只能清到 0，正数封顶 Integer.MAX_VALUE（exp 用 sum 语义微异） |

---

## HpMpAlertService

> 源码：`service/HpMpAlertService.java`

HP/MP 药水警戒线服务：客户端上报 0~19 共 20 个挡位，服务端按"挡位/20"换算比例（最高 95%）。采用"写缓存、集中落库"策略。

**依赖**：`HpMpAlertMapper`；静态缓存 `public static Map<Integer, HpMpAlertDO> cacheMap`（ConcurrentHashMap，角色 id → DO）。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `byte getHpAlert(int characterId)` | 取 HP 警戒挡位 | 先查缓存；无则查 DB（c_id）并回填缓存；返回前 `normalizeAlertStep` 归一化（0~19 钳位）并回写；无记录返回 0 |
| `void setHpAlert(int characterId, byte alert)` | 设置 HP 警戒挡位 | 归一化后：有缓存直接改内存（不落库）；无缓存则查 DB，无记录则新建（mp 默认挡位 10）入缓存 |
| `float getHpAlertPer(int characterId)` | HP 警戒百分比 | 挡位无符号值 / 20 |
| `byte getMpAlert(int characterId)` | 取 MP 警戒挡位 | 同 HP 逻辑 |
| `void setMpAlert(int characterId, byte alert)` | 设置 MP 警戒挡位 | 同 HP 逻辑（新建时 hp 默认挡位 10） |
| `float getMpAlertPer(int characterId)` | MP 警戒百分比 | 同上 |
| `void saveAll()` | 缓存全量落库 | 遍历 cacheMap `insertOrUpdate`；仅 saveall 命令与关服时调用 |
| `void clear()` | 清空缓存 | 仅关服函数（重启）调用 |

私有：`normalizeAlertStep(byte)` 用 `Byte.toUnsignedInt` 钳位到 0~19。

---

## InventoryService

> 源码：`service/InventoryService.java`

背包服务（类级 `@Transactional`）：背包类型、角色背包查询（在线读内存/离线读 DB 双路）、物品修改/删除、角色背包级联删除、宠物忽略列表管理。

**依赖**：`InventoryitemsMapper`、`InventoryequipmentMapper`、`RingsMapper`、`PetsMapper`、`PetignoresMapper`；静态依赖 `Server`、`ItemInformationProvider`、`CashIdGenerator`、`PacketCreator`。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `List<InventoryTypeRtnDTO> getInventoryTypeList()` | 背包栏类型枚举 | 遍历 `InventoryType.values()` 转 DTO |
| `Page<InventorySearchReqDTO> getCharacterList(InventorySearchReqDTO data)` | 有背包数据的角色分页 | inventoryitems（type=INVENTORY）join characters，distinct 角色；按 characterId/characterName 过滤；逐条补在线状态（遍历世界找在线对象）；过滤 null（删角色未删背包的脏数据防 NPE） |
| `List<InventorySearchRtnDTO> getInventoryList(InventorySearchReqDTO data)` | 角色某背包栏全部物品 | inventoryType/characterId 必填且合法；items left join equipment 全列查询（限定栏目+背包类型+角色）；**在线角色的 DB 行丢弃**（只收集在线对象集合），离线行走 `buildByDb`，在线角色改走 `buildByOnline` 读内存 Inventory（id=-1 标识未落库） |
| `void deleteInventoryByCharacterId(int cid)` | 级联删角色背包（`@Transactional`） | 查角色全部 items；宠物 id 批量删 pets 并 `CashIdGenerator.freeCashId` 回收；装备的 ringid 批量删 rings 并回收；最后删 equipment、items |
| `void updateInventory(InventorySearchRtnDTO data)` | 修改物品（`@Transactional`） | `modifyInventoryCheck` 必填校验；**在线状态一致性检查**（请求时的 online 与当前实际不符抛异常）；在线走 `updateOnline`（直接改 Item/Equip 对象，仅白名单字段，发 `modifyInventory` 包刷新客户端），离线走 `updateDb`（equipment 按 inventoryitemid 条件 update、items 更新 quantity/expiration；装备数量强制 1） |
| `void deleteInventory(InventorySearchRtnDTO data)` | 删除物品（`@Transactional`） | 同样在线一致性检查；在线：`inventory.removeSlot(position)` + 删除包；离线：删 equipment + items |
| `List<PetignoresDO> getPetIgnoreByPetId(Integer petId)` | 宠物忽略物品列表 | 按 petid 查 |
| `void addPetIgnoreItems(Integer petId, Collection<Integer> itemIds)` | 批量添加忽略 | 空参直接返回；`insertBatch` |
| `void removePetIgnoreItems(Integer petId, Collection<Integer> itemIds)` | 批量移除忽略 | petid + itemid in 条件删除 |
| `void deletePetData(Integer petId)` | 删宠物数据 | 删 petignores + pets（Long 主键） |

私有：`getCharacterById`（遍历世界找在线角色）；`buildByDb(Row)`（联查行→DTO，含装备子对象）；`buildByOnline(Character, InventoryType)`（内存 Inventory→DTO）；`modifyInventoryCheck`（itemId/inventoryType/characterId/position 必填 + 类型合法）；`getModifyItemOnline`（按 position 定位并校验 itemId 一致）；`getModifyItemOffline`（characterId+itemId+position+inventoryType 定位 DB 行并校验）。

---

## ItemService

> 源码：`service/ItemService.java`

物品服务：装备模板查询（无 DB 依赖，纯 wz 数据）。

**依赖**：无 Mapper；静态依赖 `ItemInformationProvider`、`ItemConstants`。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `Equip getEquipmentInfoByItemId(Integer itemId)` | 按物品 ID 取装备模板 | 名称不存在抛 EQUIP_NOT_FOUND；非 EQUIP 背包类型抛 ONLY_SUPPORT_GIVE_EQUIP；返回 `getEquipById` 强转的 `Equip` |

---

## LangResourceService

> 源码：`service/LangResourceService.java`

数据库 i18n 资源（lang_resources 表）读写服务，供 ConfigService 等模块维护表内多语言文案。

**依赖**：`ServiceProperty`、`LangResourcesMapper`。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `String getI18n(LangResourcesDO langResourcesDO)` | 按条件取单条译文 | 实体条件查询，结果数必须恰为 1 否则抛异常；返回 langValue |
| `void insertOrUpdateI18n(LangResourcesDO langResourcesDO)` | 插入或更新译文（`@Transactional`） | 无 id 时按 code+base+当前语言查既有；恰 1 条则 update；多条则先批量删除去脏再 insert；0 条直接 insert |
| `void deleteI18n(LangResourcesDO langResourcesDO)` | 按条件删除译文（`@Transactional`） | 实体条件 deleteByQuery（调用方可通过不设 langType 删除全语言） |

---

## MonsterBookService

> 源码：`service/MonsterBookService.java`

怪物手册（怪物卡收集）查询服务。

**依赖**：`MonsterbookMapper`。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `List<MonsterbookDO> getByCharacterId(int cid)` | 角色已收集怪物卡 | 按 charid 查询并按 charid 升序 |

---

## MtsService

> 源码：`service/MtsService.java`

MTS（拍卖行）数据清理服务，目前仅承担角色删除时的级联清理。

**依赖**：`MtsCartMapper`、`MtsItemsMapper`。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void deleteMtsByCharacterId(int cid)` | 删角色拍卖数据（`@Transactional`） | 按 cid 查 mts_cart，取其 id 列表批量删 mts_items（id in），再删 mts_cart；空列表短路 |

---

## NameChangeService

> 源码：`service/NameChangeService.java`

角色改名服务：注册申请、下线/启动时应用改名、撤销申请。

**依赖**：`NamechangesMapper`、`CharactersMapper`、`RingsMapper`、`InventoryitemsMapper`；静态依赖 `GameConfig`（冷却配置 `name_change_cooldown`）、`ServerManager`（自代理取事务）、`ItemId.NAME_CHANGE`。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void applyAllNameChange()` | 启动时应用全部待改名 | 查 completion_time 为 null 的记录，逐条经自代理 `doNameChange`（事务隔离），异常仅记日志继续 |
| `void applyNameChange(int characterId, String characterName)` | 下线时检测并应用改名 | 查该角色未完成记录，取第一条，重建 DO（补 older=当前名）后自代理 `doNameChange` |
| `List<NamechangesDO> getAllNameChanges()` | 全部待办改名 | completion_time is null |
| `void doNameChange(NamechangesDO data)` | 执行改名（`@Transactional`） | 更新 characters.name；rings 表 partnername 同步改名；记录 completion_time；一刀切删除该角色背包与账号商城内所有改名卡（ItemId.NAME_CHANGE）——注释说明：撤销改名会致客户端 38 错误闪退，故不保留撤销途径 |
| `boolean registerNameChange(Character chr, String newName)` | 注册改名申请 | 已有未完成记录或上次改名仍在 `name_change_cooldown` 冷却内返回 false；否则 insertSelective 新记录返回 true |
| `void cancelPendingNameChange(Character chr, boolean needFinish)` | 撤销改名（对象重载） | 委托 int 重载 |
| `void cancelPendingNameChange(int cid, boolean needFinish)` | 撤销改名 | 按 cid 删记录；needFinish=true 时仅删未完成记录（false 时全删，供角色删除级联用） |

---

## NewYearCardService

> 源码：`service/NewYearCardService.java`

新年卡服务：启动时恢复未领取卡片的投递任务；加载玩家卡片列表。

**依赖**：`NewyearMapper`；静态依赖 `Server`（卡片缓存）。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void startPendingNewYearCardRequests()` | 恢复待投递卡片 | 查 timereceived=0 且 senderdiscard=0 的记录，逐条构造 `NewYearCardRecord`（setExtraNewYearCardRecord 补 DB 状态）、放入 Server 卡片缓存并 `startNewYearCardTask()` 启动定时提醒 |
| `List<NewyearDO> loadPlayerNewYearCards(Character chr)` | 玩家相关全部卡片 | sender 或 receiver 为该角色 |

---

## NoteService

> 源码：`service/NoteService.java`

玩家留言（Note）服务：发送普通/带人气留言、展示未读、删除已读。

**依赖**：`NotesMapper`；静态依赖 `Server`（取服务器时间）。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void sendNormal(String message, String senderName, String receiverName)` | 发普通留言 | insertSelective（timestamp=服务器当前时间） |
| `void sendWithFame(String message, String senderName, String receiverName)` | 发带人气留言 | 同上，fame=1（ receiver 领取时涨 1 人气） |
| `void show(Character chr)` | 展示未读留言 | chr 判空；查 deleted=0 且 to=角色名的记录，非空则发 `ShowNotesPacket` |
| `Optional<NotesDO> delete(int noteId)` | 删除已读留言 | 查回并删除，成功返回该 DO；异常记日志返回 empty |

---

## NpcService

> 源码：`service/NpcService.java`

玩家 NPC（PlayerNPC，角色死亡掉落的世界内 NPC）服务：查询与创建。

**依赖**：`PlayernpcsMapper`、`PlayernpcsEquipMapper`、`PlayernpcsFieldMapper`；静态依赖 `server.life.PlayerNPC`。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `List<PlayernpcsFieldDO> getPlayerNpcFields(PlayernpcsFieldDO condition)` | 查玩家 NPC 所在地图字段 | 实体条件查询 |
| `List<PlayernpcsDO> getPlayerNpcDOs(PlayernpcsDO condition)` | 查玩家 NPC 记录 | 同上 |
| `List<PlayernpcsEquipDO> getPlayerNpcEquipDOs(PlayernpcsEquipDO condition)` | 查玩家 NPC 装备 | 同上 |
| `List<PlayerNPC> getPlayerNPC(PlayernpcsDO condition)` | 组装 PlayerNPC 对象 | 查 DO 列表，逐个按 npcid 查装备后 `new PlayerNPC(do, equips)` |
| `PlayerNPC createPlayerNPC(PlayernpcsDO playerNpcDO, List<PlayernpcsEquipDO> playerNpcEquipDOS)` | 创建玩家 NPC（`@Transactional`） | id 置 null 后 insertSelective；装备批量回填 npcid 后 insertBatch；重新 `getPlayerNPC` 按 id 查回完整对象，空返回 null |

---

## NxCodeService

> 源码：`service/NxCodeService.java`

NX 兑换码清理服务。

**依赖**：`NxcodeMapper`、`NxcodeItemsMapper`。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void clearExpirations()` | 清理 14 天前的兑换码 | 以 `now - 14d` 为界先清 nxcode_items 再清 nxcode（Mapper 自定义 SQL） |

---

## NxCouponService

> 源码：`service/NxCouponService.java`

NX 优惠券服务（按星期/小时生效的商城折扣）。

**依赖**：`NxcouponsMapper`。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `List<Integer> selectActiveCouponIds(int weekDay, int hourDay)` | 查当前生效优惠券 id | 委托 Mapper 自定义 SQL（weekDay/hourDay 条件） |
| `List<NxcouponsDO> getNxCoupons(NxcouponsDO condition)` | 条件查优惠券 | 实体条件查询 |

---

## QuestService

> 源码：`service/QuestService.java`

任务服务：任务状态三表（queststatus/questprogress/medalmaps）的聚合加载与角色级联删除。

**依赖**：`MedalmapsMapper`、`QuestprogressMapper`、`QueststatusMapper`；静态依赖 `server.quest.Quest`、`client.QuestStatus`。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void deleteQuestProgressByCharacter(int cid)` | 删角色全部任务数据（`@Transactional`） | 依次删 medalmaps、questprogress、queststatus（均按 characterid） |
| `List<QuestStatus> getQuestStatusByCharacter(int cid)` | 聚合加载角色任务状态 | 三表各查全量后内存组装：每条 queststatus 构造 `QuestStatus`（Quest.getInstance + Status.getById），time> -1 时秒→毫秒换算完成时间，expires>0 设过期，补 forfeited/completed；progress 按 queststatusid 关联 `setProgress`；medalmap 同理 `addMedalMap` |

---

## ServerService

> 源码：`service/ServerService.java`

服务器世界/频道列表查询（无状态、无 Mapper）。

**依赖**：静态依赖 `Server`。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `List<WorldListRtnDTO> worldList()` | 世界列表及各倍率 | 遍历 `Server.getWorlds()`，映射 id + 8 项倍率（exp/drop/meso/bossDrop/quest/travel/fishing） |
| `List<ChannelListRtnDTO> channelList(int worldId)` | 指定世界的频道列表 | 遍历该 world 的 channels，映射 id + worldId |

---

## ShopService

> 源码：`service/ShopService.java`

NPC 商店管理：商店检索（按 NPC/物品）、商店商品分页与增删改（改完热重载 ShopFactory）。

**依赖**：`ShopsMapper`、`ShopitemsMapper`；静态依赖 `LifeFactory`（NPC 名）、`ItemInformationProvider`、`ShopFactory`、`BasePageUtil`。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `Page<ShopSearchRtnDTO> getShopList(ShopSearchReqDTO data)` | 商店检索 | shops left join shopitems（NPC/商店/物品 id 条件）；内存二次过滤：NPC 名不存在的行丢弃、npcName/itemName 模糊匹配；distinct 后 `BasePageUtil` 内存分页 |
| `Page<ShopItemSearchRtnDTO> getShopItemList(ShopSearchReqDTO data)` | 商店商品分页 | 按 shopid 查 shopitems 分页，逐条 `fromShopItemDO` 补名称/描述 |
| `ShopItemSearchRtnDTO getShopItem(Long id)` | 单个商品 | `selectOneById` 后转 DTO |
| `Long modifyShopItem(ShopItemSearchRtnDTO data, boolean isDelete)` | 增/改/删商品 | 删除按 id；否则 builder 组装（shopitemid/shopid/itemid/price/pitch/position）`insertOrUpdate(do, true)` 忽略 null；最后 `ShopFactory.reloadShops()` 热重载；返回商品 id |

私有：`fromShopItemDO` 用 `getNameDesc` 同时取物品名与描述。

---

## UserDetailsImpl

> 源码：`service/UserDetailsImpl.java`

Spring Security `UserDetails` 适配器，包装 `AccountsDO` 为认证主体。无 Service 依赖、无注解（普通类）。

**字段**：`id`（账号 id）、`username`、`password`（`@JsonIgnore` 不序列化）、`authorities`。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `UserDetailsImpl(Integer id, String name, String password, Collection<? extends GrantedAuthority> authorities)` | 构造器 | 全字段 final 赋值 |
| `static UserDetailsImpl build(AccountsDO user, Collection<? extends GrantedAuthority> authorities)` | 从 DO 构建 | 取 id/name/password 组装 |
| `Integer getId()` | 取账号 id | — |
| `getAuthorities() / getPassword() / getUsername()` | UserDetails 接口实现 | 直接返回字段 |
| `isAccountNonExpired() / isAccountNonLocked() / isCredentialsNonExpired() / isEnabled()` | 账号状态 | 恒 true（封禁判断不在此层） |
| `equals(Object o)` | 相等性 | 仅按 id 比较 |

---

## UserDetailsServiceImpl

> 源码：`service/UserDetailsServiceImpl.java`

Spring Security `UserDetailsService` 实现：按用户名加载后台管理员主体。**仅 webadmin=1 的账号可登录后台**，其余一律返回 null（认证失败）。

**依赖**：`AccountsMapper`（构造器注入）。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `UserDetails loadUserByUsername(String username)` | 加载用户（`@Transactional`） | `selectOneByName` 查账号；null 返回 null；`webadmin==1` 时授予 `ROLE_ADMIN` 并 `UserDetailsImpl.build`；非管理员返回 null |

---

## WorldTransferService

> 源码：`service/WorldTransferService.java`

角色转区服务：注册申请、资格校验、执行转区、撤销申请。

**依赖**：`WorldtransfersMapper`、`CharactersMapper`、`AccountService`、`BuddiesMapper`；静态依赖 `GameConfig`（`allow_cash_shop_world_transfer`、`world_transfer_cooldown`）、`ServerManager`（自代理）、`Server`。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void applyAllWorldTransfer()` | 启动时应用全部待转区 | 查 completion_time 为 null 的记录，逐条先 `checkWorldTransferEligibility`，通过才自代理 `doWorldTransfer`；异常记日志继续 |
| `boolean checkWorldTransferEligibility(WorldtransfersDO data)` | 转区资格校验 | 依次否决：全局开关关闭、角色不存在、已婚（partnerId 非空）、账号不存在/被封/有 tempban（与默认占位时间不同即视为封禁中）、目标世界名字被占用、目标世界不存在 |
| `void doWorldTransfer(WorldtransfersDO data)` | 执行转区（`@Transactional`） | 更新角色 world=to、meso 钳位到 100 万上限、guildid/guildrank 清零（退公会）；删除双向好友记录；回写 completion_time |
| `boolean registerWorldTransfer(Character chr, int newWorld)` | 注册转区申请 | 冷却与未完成检查（同改名逻辑，`world_transfer_cooldown`）；通过则 insert 新记录 |
| `void cancelPendingWorldTransfer(Character chr, boolean needFinish)` | 撤销（对象重载） | 委托 int 重载 |
| `void cancelPendingWorldTransfer(int cid, boolean needFinish)` | 撤销转区记录 | 按 cid 删；needFinish=true 仅删未完成（false 全删，供角色删除级联） |
