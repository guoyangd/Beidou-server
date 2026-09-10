# 17-脚本引擎层（scripting）详细设计

- 模块路径：`gms-server/src/main/java/org/gms/scripting/`（含子包 `event`、`event/scheduler`、`item`、`map`、`npc`、`portal`、`quest`、`reactor`）
- 类数量：21（根包 3：`AbstractPlayerInteraction`、`AbstractScriptManager`、`SynchronizedInvocable`；event 4 + scheduler 1；item 2；map 2；npc 2；portal 3；quest 2；reactor 2；接口 `PortalScript` 计入 portal 3 中）
- 依赖模块：
  - 脚本引擎：GraalVM JS（`com.oracle.truffle.js.scriptengine.GraalJSScriptEngine`，经 `javax.script`（JSR-223）`ScriptEngine`/`Invocable` 接口调用；`SynchronizedInvocable` 为其线程安全包装）
  - 本项目：`org.gms.client.*`（`Client`/`Character`/`QuestStatus`/`Skill`/`inventory.*`）、`org.gms.net.server.*`（`Server`/`Channel`/`World`/`guild.*`/`world.*`）、`org.gms.server.*`（`TimerManager`/`ThreadManager`/`ItemInformationProvider`/`Marriage`/`maps.*`/`life.*`/`quest.Quest`/`partyquest.*`/`expeditions.*`/`events.gm.Event`）、`org.gms.config.GameConfig`、`org.gms.constants.*`（`game.*`/`id.*`/`inventory.*`/`string.*`）、`org.gms.util.*`（`PacketCreator`/`DatabaseConnection`/`Pair`/`NumberTool`/`I18nUtil`/`packets.WeddingPackets`）、`org.gms.dao.entity.ExtendValueDO`、`org.gms.service.GachaponService`、`org.gms.manager.ServerManager`、`org.gms.property.ServiceProperty`、`org.gms.model.pojo.*`（`SkillEntry`/`NextLevelContext`）、`org.gms.exception.EventInstanceInProgressException`
  - 外部：SLF4J、java.awt（`Point`）、JDBC（reactor 掉落表查询）
- 总体说明：所有 `.js` 脚本按「`scripts/`（英文基础）→ `scripts-<lang>/`（如 `scripts-zh-CN/`）文件级覆盖」的 i18n 规则加载（见 `AbstractScriptManager`）；NPC/quest 脚本引擎实例缓存在 `Client` 上（会话级），portal/map/event 脚本全局缓存（单例管理器内），后两者因 GraalJS 引擎不支持并发访问，调用处以 `synchronized(脚本实例)` 串行化。
- 脚本注入变量约定：NPC 脚本 `cm`（`NPCConversationManager`）、道具脚本 `im`、quest 脚本 `qm`（`QuestActionManager`）、reactor 脚本 `rm`（`ReactorActionManager`）、map 脚本无变量名（`start(msm)` 传参）、event 脚本 `em`（`EventManager`）。

---

## AbstractPlayerInteraction

脚本可调用的玩家交互 API 基类，直接以 `public` 字段 `c` 持有客户端连接；是 `NPCConversationManager`（进而 `QuestActionManager`）、`MapScriptMethods`、`PortalPlayerInteraction`、`ReactorActionManager`、`ItemScriptMethods` 的共同父类。JS 脚本通过该类暴露的方法操作玩家、地图、物品、任务等游戏对象（源码路径：`scripting/AbstractPlayerInteraction.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `c` | `public Client` | 当前客户端连接（脚本引擎直接访问的公开字段） |
| `log` | `static Logger` | SLF4J 日志 |

### 方法：基础环境获取

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public AbstractPlayerInteraction(Client c)` | 构造函数 | 保存客户端引用 |
| `public Client getClient()` / `public Character getPlayer()` / `public Character getChar()` | 取客户端/当前角色 | `getPlayer` 与 `getChar` 等价（`c.getPlayer()`），为兼容旧脚本保留两套命名 |
| `public int getJobId()` / `public Job getJob()` | 取角色职业 | 委托 `getPlayer().getJob()` |
| `public int getLevel()` | 取角色等级 | 委托 `getPlayer().getLevel()` |
| `public MapleMap getMap()` | 取当前地图 | `c.getPlayer().getMap()` |
| `public int getMapId()` | 取当前地图 ID | `c.getPlayer().getMap().getId()` |
| `public int getHourOfDay()` | 取服务器当前小时（0-23） | `Calendar.getInstance().get(Calendar.HOUR_OF_DAY)` |
| `public int getMarketPortalId(int mapId)` | 取指定地图的市场传送门 ID | 私有重载 `getMarketPortalId(MapleMap)`：优先 `findMarketPortal()`，无市场门则回退随机玩家出生点 ID |
| `public long getCurrentTime()` | 取服务器时钟 | `Server.getInstance().getCurrentTime()`（统一用服务器时间，避免计时偏差） |
| `public MapleMap getWarpMap(int map)` / `public MapleMap getMap(int map)` | 取目标地图实例 | `getPlayer().getWarpMap(map)`——事件副本内会返回 EIM 专属的地图实例；两个方法等价 |
| `public String numberWithCommas(int number)` | 数字千分位格式化 | 委托 `GameConstants.numberWithCommas` |

### 方法：传送

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void warp(int mapid)` / `public void warp(int map, int portal)` / `public void warp(int map, String portal)` | 传送当前玩家到指定地图（可指定门 ID 或门名） | 委托 `getPlayer().changeMap(...)` |
| `public void warpMap(int map)` | 把当前地图所有玩家传走 | `getPlayer().getMap().warpEveryone(map)` |
| `public void warpParty(int id)` / `warpParty(int id, int portalId)` / `warpParty(int map, String portalName)` / `warpParty(int id, int fromMinId, int fromMaxId)` | 传送整个队伍 | 最终落到 `warpParty(id, portalId, fromMinId, fromMaxId)`：遍历在线队友，仅当地图 ID 落在 `[fromMinId, fromMaxId]` 区间（默认为当前地图）时 `changeMap`；`portalName` 版本先按名查门，找不到回退 0 号门 |
| `public void warpParty(int id, int portalId, int fromMinId, int fromMaxId)` | （实现）按地图区间传送队友 | 见上 |

### 方法：地图与怪物查询/重置

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public int countAllMonstersOnMap(int map)` / `public int countMonster()` | 统计指定地图/当前地图怪物数 | `countMonsters()` |
| `public int getPlayerCount(int mapid)` | 统计指定地图玩家数 | `c.getChannelServer().getMapFactory().getMap(mapid).getCharacters().size()` |
| `public void resetMapObjects(int mapid)` | 重置地图对象 | `getWarpMap(mapid).resetMapObjects()` |
| `public void resetMap(int mapid)` | 重置一张地图（reactor/怪物/地面掉落） | 重置 reactor、清怪，然后遍历全部 ITEM 类型地图对象广播 `removeItemFromMap` 移除 |
| `public boolean isAllReactorState(int reactorId, int state)` | 判断当前地图同类 reactor 是否全部处于指定状态 | `getPlayer().getMap().isAllReactorState(...)`（PQ 检查用） |

### 方法：物品与背包

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public Inventory getInventory(int type)` / `getInventory(InventoryType type)` | 取背包 | byte 版本经 `InventoryType.getByType` 转换 |
| `public boolean hasItem(int itemid[, int quantity])` / `haveItem(int itemid[, int quantity])` | 判断是否持有（≥数量） | 两套命名等价，`getItemQuantity(itemid, false) >= quantity`（不检查已装备） |
| `public int getItemQuantity(int itemid)` | 取持有数量 | `getItemQuantity(itemid, false)` |
| `public boolean haveItemWithId(int itemid[, boolean checkEquipped])` | 判断是否持有（可选含已装备） | 委托 `getPlayer().haveItemWithId` |
| `public boolean canHold(int itemid)` / `canHold(int itemid, int quantity)` | 判断能否放入物品 | 走 `canHoldAll` → `Inventory.checkSpots` 模拟检查 |
| `public boolean canHold(int itemid, int quantity, int removeItemid, int removeQuantity)` | 先移除再判断能否放入 | 走 `canHoldAllAfterRemoving` |
| `public boolean canHoldAll(List<Object> itemids[, List<Object> quantity])` | 批量判断能否放入（JS 数组直接传入） | `convertToIntegerList` 转型后逐项构造 `Item`+`InventoryType` 对，`Inventory.checkSpots(player, addedItems)` |
| `public boolean canHoldAllAfterRemoving(List<Integer> toAddItemids, List<Integer> toAddQuantity, List<Integer> toRemoveItemids, List<Integer> toRemoveQuantity)` | 模拟「先扣后加」的背包容量检查 | 用 `InventoryProof`（CANHOLD 临时背包）实现：锁定证明背包 → 按背包类型分组 → `cloneContents` 克隆真实背包 → 在克隆上 `InventoryManipulator.removeById` → `checkSpots` 检查新增 → `flushContents` 还原（finally 保证解锁） |
| `public Item gainItem(int id[, short quantity[, boolean show[, boolean randomStats[, long expires[, Pet from]]]]])` | 核心：给玩家发放/扣除物品，6 个重载逐层补默认值（数量 1、不显示、不随机属性、不过期、非宠物进化） | ① 宠物类先 `Pet.createPet` 取 petId，`from` 非空则加载新宠物并继承名字/亲密度/饱食度/等级后存库；② 装备类经 `ItemInformationProvider.getEquipById` 生成，饰品无升级卷轴位时补 3 格，开启 `use_enhanced_crafting` 且玩家在商城状态时追加 1 格并吃一次混沌卷效果；③ 其他物品 `new Item(id, 0, quantity, petId)`；④ `expires >= 0` 则设置绝对过期时间；⑤ 背包满时提示并返回 null；⑥ `quantity < 0` 时改为 `removeById` 扣除；⑦ `showMessage` 时发送 `getShowItemGain`；返回发放的物品（可能为 null） |
| `public Item evolvePet(byte slot, int afterId)` | 宠物进化 | 90 天有效期，`gainItem(afterId, 1, false, true, period, target)` 生成新宠后从 CASH 背包槽位移除旧宠；进化逻辑主体已被注释，实际只完成「发新删旧」 |
| `public void gainFame(int delta)` | 增减人气 | `getPlayer().gainFame(delta)` |
| `public void givePartyItems(int id, short quantity, List<Character> party)` | 给全队发/扣物品 | 逐人 `InventoryManipulator.addById`（负数则 removeById）并广播物品获得提示 |
| `public void removePartyItems(int id)` | 移除全队某物品 | 无队伍时仅对自己 `removeAll`；有队伍时遍历在线成员逐人 `removeAll(id, client)` |
| `public void removeHPQItems()` | 清除 HPQ（月妙）种子 | 对 6 色 primrose seed 常量逐个 `removePartyItems` |
| `public void removeFromParty(int id, List<Character> party)` | 从队伍成员背包扣除物品 | 按 `countById` 持有数 removeById 并发送负数获得提示 |
| `public void removeAll(int id)` / `removeAll(int id, Client cl)` | 清空某物品（含已装备的装备） | 背包内全部扣除；若为 EQUIP 类型再检查 EQUIPPED 背包扣除 1 件 |
| `public void removeAllByInventory(int invType)` | 清空整个背包栏 | 遍历该栏所有槽位 `removeFromSlot` |
| `public void removeAllByInventorySlot(int invType, short slot)` | 删除指定栏指定槽位物品 | `getItem(slot)` 非空则 `removeFromSlot` |
| `public void removeEquipFromSlot(short slot)` | 卸下已装备槽位 | 从 EQUIPPED 背包 `removeFromSlot` |
| `public void gainAndEquip(int itemid, short slot)` | 直接生成装备并穿上 | 先移除该槽位旧装备，`getEquipById` 生成新装备后 `addItemFromDB` 放入 EQUIPPED 并广播背包变更 |
| `public void gainEquip(Equip equip)` | 发放现成装备对象（BeiDou 常用） | `checkSpace` 不足时 i18n 提示「装备栏已满」，随后 `addFromDrop` |

### 方法：任务（Quest）

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public QuestStatus getQuestRecord(int id)` / `getQuestNoRecord(int id)` | 取任务状态（无记录时新建/返回 null 包装） | `getQuestNAdd` / `getQuestNoAdd`（源码标注 NOT TESTED） |
| `public int getQuestStatus(int id)` | 取任务状态枚举 ID | `getQuest(...).getStatus().getId()` |
| `public boolean isQuestCompleted(int id)` / `isQuestActive(id)` / `isQuestStarted(id)` | 判断任务完成/进行中/已开始 | 私有 `getQuestStat` 比较 `QuestStatus.Status`，NPE 时打日志返回 false；`isQuestActive` 是 `isQuestStarted` 别名 |
| `public void setQuestProgress(int id, String progress)` / `(int id, int progress)` / `(int id, int infoNumber, int progress)` / `(int id, int infoNumber, String progress)` | 写任务进度（4 重载补默认 infoNumber=0） | 最终委托 `getPlayer().setQuestProgress` |
| `public String getQuestProgress(int id)` / `getQuestProgress(int id, int infoNumber)` | 读任务进度 | 若该任务绑定的 infoNumber 与入参一致且 >0，自动改读 infoNumber 对应任务本体的 progress；无记录返回 "" |
| `public int getQuestProgressInt(int id[, int infoNumber])` | 读任务进度并转 int | `Integer.parseInt`，失败返回 0 |
| `public void resetAllQuestProgress(int id)` / `resetQuestProgress(int id, int infoNumber)` | 重置全部/单条任务进度 | `QuestStatus.resetAllProgress/resetProgress` 后 `announceUpdateQuest(UPDATE)` |
| `public boolean forceStartQuest(int id[, int npc])` / `forceCompleteQuest(int id[, int npc])` | 强制开始/完成任务 | 默认 NPC 为 `NpcId.MAPLE_ADMINISTRATOR`，转发 `startQuest/completeQuest` |
| `public boolean startQuest(short|int id[, int npc])` / `completeQuest(short|int id[, int npc])` | （实现）开始/完成任务，short 重载为兼容旧脚本 | `Quest.getInstance(id).forceStart/forceComplete(player, npc)`，NPE 打日志返回 false |
| `public void openNpc(int npcid[, String script])` | 脚本中转打开另一 NPC 对话 | 若当前已有 CM 直接返回；先 `removeClickedNPC` + `NPCScriptManager.dispose` 清场再 `start` |

### 方法：消息、效果与 UI

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void playerMessage(int type, String message)` | 对玩家发系统公告 | `PacketCreator.serverNotice` |
| `public void message(String message)` / `dropMessage(int type, String message)` | 提示消息（走角色通道） | `getPlayer().message/dropMessage` |
| `public void mapMessage(int type, String message)` | 当前地图广播公告 | 地图 `broadcastMessage(serverNotice)` |
| `public void guildMessage(int type, String message)` | 公会广播 | `getGuild().guildMessage(...)`（无公会忽略） |
| `public void changeMusic(String songName)` | 换地图 BGM | 广播 `musicChange` |
| `public void mapEffect(String path)` / `mapSound(String path)` | 播放地图特效/音效 | 发送 `mapEffect/mapSound` 包 |
| `public void playSound(String sound)` | 地图广播环境音 | `environmentChange(sound, 4)` |
| `public void environmentChange(String env, int mode)` | 地图环境变更广播 | `environmentChange(env, mode)` |
| `public void showIntro(String path)` | 播放剧情动画（Direction） | `PacketCreator.showIntro` |
| `public void showInfo(String path)` | 播放 UI/教程资源 | `showInfo` + `enableActions` |
| `public void displayAranIntro()` | 战神（Aran）新手剧情 | 按当前地图 ID（`MapId.ARAN_*`）映射 `Direction1.img/aranTutorial` 各 Scene，按性别拼 0/1 |
| `public void showInstruction(String msg, int width, int height)` | 显示居中提示框 | `sendHint` + `enableActions` |
| `public void disableMinimap()` | 禁用小地图 | 发包 |
| `public void enableActions()` | 解锁客户端操作 | `enableActions` 包（对话结束/脚本收尾必备） |
| `public void showEffect(String effect)` | 播放个人特效 | 发送 `showEffect`（NPCConversationManager 覆写为地图广播） |
| `public void earnTitle(String msg)` / `showInfoText(String msg)` | 顶部称号提示/中下文字提示 | `earnTitleMessage`/`showInfoText` 包 |
| `public void openUI(byte ui)` | 打开客户端 UI | 发包 |
| `public void lockUI()` / `unlockUI()` | 锁定/解锁客户端 UI（剧情用） | `disableUI(true)`+`lockUI(true)` 成对发送 |
| `public void npcTalk(int npcid, String message)` | 直接以 NPC 名义说话 | `getNPCTalk(npcid, 0, message, "00 00", 0)` |
| `public void updateAreaInfo(Short area, String info)` / `boolean containsAreaInfo(short area, String info)` | 读写区域信息（AreaInfo） | 委托 `Character` 同名方法；写后补发 `enableActions` |

### 方法：新手引导 / 道场（Dojo）

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void spawnGuide()` / `removeGuide()` | 召出/移除新手向导精灵 | `PacketCreator.spawnGuide(true/false)` |
| `public void displayGuide(int num)` | 显示教程页 | `showInfo("UI/tutorial.img/" + num)` |
| `public void talkGuide(String message)` / `guideHint(int hint)` | 向导说话/向导提示 | 发包 |
| `public void goDojoUp()` | 武陵道场上一层 | `dojoWarpUp` 包 |
| `public void resetDojoEnergy()` / `resetPartyDojoEnergy()` | 清零道场能量（个人/全队同地图成员） | `setDojoEnergy(0)` |
| `public void dojoEnergy()` | 显示当前道场能量条 | `getEnergy("energy", ...)` |

### 方法：组队/公会/事件身份

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public Guild getGuild()` | 取公会对象 | `Server.getInstance().getGuild(guildId, world, null)`，异常打日志返回 null |
| `public Party getParty()` | 取队伍 | `getPlayer().getParty()` |
| `public boolean isLeader()` / `isPartyLeader()` | 是否队长 | 比对 `party.getLeaderId() == player.getId()`；`isLeader` 为别名 |
| `public boolean isGuildLeader()` | 是否会长 | 委托角色 |
| `public boolean isEventLeader()` | 是否当前事件实例队长 | `getEventInstance() != null && id == eim.getLeaderId()` |
| `public EventManager getEventManager(String event)` | 取事件管理器 | `getClient().getEventManager(event)` |
| `public EventInstanceManager getEventInstance()` | 取所在事件实例 | `getPlayer().getEventInstance()` |
| `public Pyramid getPyramid()` | 取金字塔 PQ 状态 | 强转 `getPlayer().getPartyQuest()` |

### 方法：经验/金币（组队发放）

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void giveCharacterExp(int amount, Character chr)` | 给单角色发经验 | `gainExp(amount * expRate)` 取整，`floatToInt` |
| `public void givePartyExp(int amount, List<Character> party)` | 给列表角色发经验 | 逐人 `giveCharacterExp` |
| `public void givePartyExp(String PQ)` / `givePartyExp(String PQ, boolean instance)` | 按 PQ 模板给全队发经验 | 人数加成：1-3 人 100%，4 人 110%……6 人 130%（`70 + size*10`）；`instance=true` 时只统计/发放实际在事件实例内的成员；基数 `PartyQuest.getExp(PQ, level)`；`pq_bonus_exp_rate > 0` 时再乘倍率 |

### 方法：技能与道具效果

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void teachSkill(int skillid, byte level, byte masterLevel, long expiration[, boolean force])` | 教授/提升技能 | 已有技能且不强制时取「现有/新值」较大者（等级、master、过期时间分别取 max）；Aran 职业新技能额外播放 `AranGetSkill` 特效；最终 `changeSkillLevel` |
| `public void useItem(int id)` | 使用消耗品效果 | `getItemEffect(id).applyTo(player)` + 发送物品使用消息 |
| `public void cancelItem(int id)` | 取消消耗品效果 | `cancelEffect(getItemEffect(id), false, -1)` |
| `public StatEffect getItemEffect(int itemId)`（在 NPCConversationManager 中暴露） | 取道具效果 | 见 NPCConversationManager |

### 方法：生命体生成

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void spawnNpc(int npcId, Point pos, MapleMap map)` | 在指定坐标生成 NPC | `LifeFactory.getNPC` 非空时设置坐标/视野范围（±50）/落脚点后 `addMapObject` + 广播 `spawnNPC` |
| `public void spawnMonster(int id, int x, int y)` | 当前地图生成怪物 | `LifeFactory.getMonster` 设坐标后 `spawnMonster` |
| `public Monster getMonsterLifeFactory(int mid)` | 构造怪物对象（不入图） | `LifeFactory.getMonster(mid)` |
| `public void spawnFakeMonster(int id)`（ReactorActionManager） | 见 ReactorActionManager | — |

### 方法：远征队（Expedition）

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public int createExpedition(ExpeditionType type[, boolean silent, int minPlayers, int maxPlayers])` | 创建远征队 | 返回码：0 成功 / 1 该频道 Boss 次数受限（`ExpeditionBossLog.attemptBoss` 拒绝）/ -1 加入频道远征列表失败 |
| `public void endExpedition(Expedition exped)` | 结束远征 | `dispose(true)` + 从频道移除 |
| `public Expedition getExpedition(ExpeditionType type)` | 取频道当前远征 | `channelServer.getExpedition(type)` |
| `public String getExpeditionMemberNames(ExpeditionType type)` | 拼接成员名列表（逗号分隔） | 遍历 `getMembers().values()` |
| `public boolean isLeaderExpedition(ExpeditionType type)` | 是否远征队长 | `exped.isLeader(player)` |

### 方法：其他杂项

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public long getJailTimeLeft()` | 剩余监狱时间 | `getJailExpirationTimeLeft()` |
| `public List<Pet> getDriedPets()` | 取全部已过期宠物 | 遍历 CASH 背包，`isPet && expiration < now` 的收集为 List |
| `public List<Item> getUnclaimedMarriageGifts()` | 未领取的婚礼礼物 | `Marriage.loadGiftItemsFromDb(client, playerId)` |
| `public boolean startDungeonInstance(int dungeonid)` | 占用一个小型副本房间 | `channelServer.addMiniDungeon(dungeonid)` |
| `public boolean canGetFirstJob(int jobType)` | 一转属性是否达标 | `use_auto_assign_starters_ap` 开启直接 true；1 战士 STR35 / 2 法师 INT20 / 3、4 弓贼 DEX25 / 5 海盗 DEX20 |
| `public String getFirstJobStatRequirement(int jobType)` | 一转属性要求文案 | 中文描述（力量 35 等），其他返回 null |
| `public void weakenAreaBoss(int monsterId, String message)` | 弱化区域 Boss（任务护送类） | 对图内指定怪依次施加 `SEAL_SKILL`（封技）与 `EVA` 2（降回避）MobSkill，随后地图蓝字公告 |
| `public int getOnlineTime()` | 账号本次在线分钟数（BeiDou 新增） | `getPlayer().getCurrentOnlineTime()` |

### 方法：扩展值存取（BeiDou 新增，脚本持久化状态）

基于 `ExtendUtil` + `ExtendValueDO`（`extend_value` 表）的脚本级 KV 存储，键空间分角色/账号 × 永久/每日/每周（`ExtendType`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public String getCharacterExtendValue(String extendName)` / `(String extendName, boolean isDaily)` | 读角色扩展值（永久 / 每日=TRUE 每周=FALSE） | `ExtendUtil.getExtendValue(charId, type, name)`，无记录返回 null |
| `public String getAccountExtendValue(String extendName)` / `(String extendName, boolean isDaily)` | 读账号扩展值 | 同上，键为 accountId |
| `public void saveOrUpdateCharacterExtendValue(String extendName, String extendValue[, boolean isDaily])` | 写角色扩展值 | `ExtendUtil.saveOrUpdateExtendValue` |
| `public void saveOrUpdateAccountExtendValue(String extendName, String extendValue[, boolean isDaily])` | 写账号扩展值 | 同上 |

---

## AbstractScriptManager

全部脚本管理器（NPC/quest/portal/map/event 等）的抽象父类，负责 GraalJS 引擎获取、双语脚本文件选择（`scripts/` + `scripts-<lang>/` 文件级覆盖）与会话级引擎缓存/重置（源码路径：`scripting/AbstractScriptManager.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `SCRIPT_DIRECTORY` | `static final String` | 基础脚本目录名 `"scripts"`（相对 `gms-server` 工作目录） |
| `sef` | `ScriptEngineFactory` | 构造时取 `graal.js` 引擎工厂（每个新脚本文件由工厂创建独立引擎实例） |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `protected AbstractScriptManager()` | 构造函数 | `new ScriptEngineManager().getEngineByName("graal.js").getFactory()` |
| `protected ScriptEngine getInvocableScriptEngine(String path)` | 按相对路径加载并执行一个 JS 脚本，返回其引擎 | ① 从 Spring 取 `ServiceProperty.getLanguage()` 拼 `scripts-<lang>/<path>`；② 语言目录有该文件用之，否则回退 `scripts/<path>`，均无则返回 null；③ `sef.getScriptEngine()` 创建引擎，非 GraalJSScriptEngine 抛 i18n 异常；④ `enableScriptHostAccess` 开启 host 访问；⑤ UTF-8 读取并 `eval`，失败打 warn 日志返回 null |
| `protected ScriptEngine getInvocableScriptEngine(String path, Client c)` | 带会话缓存的加载（NPC/quest/reactor 用） | 缓存键统一为 `scripts/<path>`；`c.getScriptEngine(key)` 命中直接返回，否则调无缓存版本加载后 `c.setScriptEngine(key, engine)` |
| `private void enableScriptHostAccess(GraalJSScriptEngine engine)` | 允许脚本 `Java.type()` 调 Java 类 | 向 ENGINE_SCOPE Bindings 写 `polyglot.js.allowHostAccess=true`、`polyglot.js.allowHostClassLookup=true` |
| `protected void resetContext(String path, Client c)` | 重置会话脚本上下文（dispose 时调用） | `c.removeScriptEngine("scripts/" + path)`，键与加载时一致 |

---

## SynchronizedInvocable

`javax.script.Invocable` 的线程安全装饰器（`@ThreadSafe`）：GraalVM 对已 eval 脚本不允许并发访问，本类通过对所有方法加 `synchronized` 把任意 Invocable 串行化（源码路径：`scripting/SynchronizedInvocable.java`）。事件脚本（event/*.js）由多线程调度触发，故 `EventScriptManager` 用它包装引擎。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `private SynchronizedInvocable(Invocable invocable)` | 私有构造 | 保存被包装对象 |
| `public static Invocable of(Invocable invocable)` | 工厂方法 | 返回包装实例（静态类型仍为 Invocable） |
| `public synchronized Object invokeMethod(Object thiz, String name, Object... args)` | 同步调用脚本对象方法 | 委托内部 invocable |
| `public synchronized Object invokeFunction(String name, Object... args)` | 同步调用脚本顶层函数 | 委托内部 invocable |
| `public synchronized <T> T getInterface(Class<T> clasz)` / `getInterface(Object thiz, Class<T> clasz)` | 同步取脚本实现的 Java 接口代理 | 委托内部 invocable |

---

## EventScriptManager（event）

频道级事件脚本管理器：每个 `Channel` 持有一个实例，按 `world.properties` 中配置的脚本名数组批量加载 `event/<name>.js`，构造后统一调用脚本 `init()`（源码路径：`scripting/event/EventScriptManager.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `INJECTED_VARIABLE_NAME` | `static final String` | 注入变量名 `"em"` |
| `fallback` | `static EventEntry` | 后备事件（名为 `0_EXAMPLE` 的示例脚本，事件名查不到时兜底） |
| `events` | `Map<String, EventEntry>`（ConcurrentHashMap） | 事件名 → 脚本条目 |
| `active` | `boolean` | 是否激活（init 后事件数 >1 时为 true） |
| `EventEntry`（内部类） | `Invocable iv` + `EventManager em` | 封装一个事件脚本的调用接口与管理器 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public EventScriptManager(Channel channel, String[] scripts)` | 构造并加载全部事件脚本 | 遍历非空脚本名 `initializeEventEntry` 入表 → `init()` → 移出 `"0_EXAMPLE"` 存为 fallback |
| `public EventManager getEventManager(String event)` | 按名取事件管理器 | 查不到返回 `fallback.em` |
| `public boolean isActive()` | 管理器是否激活 | 返回 active |
| `public final void init()` | 初始化所有事件 | 逐个 `iv.invokeFunction("init", null)`（异常仅记日志）；`active = events.size() > 1` |
| `private void reloadScripts()` | 重新加载全部脚本 | 复制当前条目集，逐个按脚本名+首个频道重建 EventEntry 放回 map |
| `private EventEntry initializeEventEntry(String script, Channel channel)` | 加载单个事件脚本 | `getInvocableScriptEngine("event/" + script + ".js")`（无会话缓存，全局共享）→ `SynchronizedInvocable.of` 包装 → new EventManager → `engine.put("em", em)` 注入 |
| `public void reload()` | 热重载 | `cancel()` → `reloadScripts()` → `init()` |
| `public void cancel()` | 取消全部事件 | active=false，逐个 `em.cancel()` |
| `public void dispose()` | 销毁管理器 | 清空 events 后逐个 `em.cancel()` |
| `public List<EventManager> getEventManagers()` | 列出全部事件管理器 | map values 映射为 List |

---

## EventManager（event）

单个事件脚本（如 KPQ、LPQ、Zakum）的服务端管理器：持有脚本引擎与频道/世界引用，负责事件实例（EIM）的创建/复用、大厅（lobby）并发槽位管理、公会排队（GPQ）、脚本定时调度（`schedule`/`scheduleAtTimestamp`）与属性存储（源码路径：`scripting/event/EventManager.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `iv` | `Invocable` | 事件脚本引擎（SynchronizedInvocable 包装） |
| `cserv` / `wserv` / `server` | `Channel`/`World`/`Server` | 频道/世界/全局服务引用（cancel 后置 null） |
| `ess` | `EventScriptScheduler` | 脚本级调度器（无任务的空转自动停表） |
| `instances` | `Map<String, EventInstanceManager>` | 已命名的事件实例表 |
| `instanceLocks` | `Map<String, Integer>` | 实例名 → 占用的大厅 ID |
| `queuedGuilds` / `queuedGuildLeaders` | `Queue<Integer>` / `Map<Integer, Integer>` | GPQ 公会等待队列与公会→会长映射 |
| `openedLobbys` | `List<Pair<Boolean, Long>>` | 8 个大厅槽位（占用标记+占用时间戳） |
| `readyInstances` / `readyId` / `onLoadInstances` | `List<EIM>` / `Integer` / `int` | 预创建 EIM 池（异步填充，降低开本延迟） |
| `props` | `Properties` | 事件级脚本属性 |
| `name` | `String` | 事件名（脚本文件名） |
| `lobbyLock` / `queueLock` / `startLock` | `ReentrantLock` | 大厅/就绪队列/启动互斥锁 |
| `playerPermit` / `startSemaphore` | `Set<Integer>` / `Semaphore(7)` | 防同一玩家重复开本 + 并发开本上限 |
| `maxLobbys` | `static final int = 8` | 最大大厅数（脚本可用 `getMaxLobbies()` 覆盖） |
| `nextScheduledTime` | `volatile Long` | 最近一次注册的调度到期时间 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public EventManager(Channel cserv, Invocable iv, String name)` | 构造函数 | 保存引用，初始化 8 个空闲大厅槽位 |
| `private boolean isDisposed()` | 是否已释放 | `onLoadInstances <= -1000`（cancel 时被压到极小值） |
| `public void cancel()` | 取消事件（须无玩家在线时调用） | `ess.dispose()` → 调脚本 `cancelSchedule` → 清空并逐个 dispose 全部实例与就绪池 → `props.clear()`、cserv/wserv/server/iv 置 null |
| `public long getLobbyDelay()` | 大厅释放延迟 | `GameConfig.getServerLong("event_lobby_delay")` |
| `private int getMaxLobbies()` | 取脚本定义的最大大厅数 | 调脚本 `getMaxLobbies`，无定义回退 8 |
| `public EventScheduledFuture schedule(String methodName, long delay[, EventInstanceManager eim])` | 延迟调用脚本函数（全局任务，如 Boss 定时刷新） | 构造 Runnable 调 `iv.invokeFunction(methodName, eim)`，`ess.registerEntry(r, delay)` 注册，返回可取消句柄 |
| `public EventScheduledFuture scheduleAtTimestamp(String methodName, long timestamp)` | 按绝对时间戳调度脚本函数 | 注册延迟 = `timestamp - server.getCurrentTime()` |
| `public World getWorldServer()` / `Channel getChannelServer()` / `Invocable getIv()` / `String getName()` | 取世界/频道/引擎/事件名 | 简单 getter |
| `public EventInstanceManager getInstance(String name)` / `Collection<EIM> getInstances()` | 按名/全量取实例 | getInstances 在 `synchronized(instances)` 下拷贝 |
| `public EventInstanceManager newInstance(String name)` | 创建事件实例（优先复用预创建池） | `getReadyInstance()` 取池内 EIM 并改名，否则 new；重名抛 `EventInstanceInProgressException`；入 instances 表 |
| `public Marriage newMarriage(String name)` | 创建婚礼实例 | 同 newInstance，但构造 `Marriage`（EIM 子类） |
| `public void disposeInstance(String name)` | 延迟释放实例并归还大厅 | `ess.registerEntry`（延迟 `event_lobby_delay` 秒）：`freeLobbyInstance(name)` + 从 instances 移除 |
| `public void setProperty(String key, String value)` / `setProperty(String key, int value)` / `setIntProperty(String key, int value)` / `String getProperty(String key)` / `int getIntProperty(String key)` | 事件属性读写 | 基于 `Properties` 的简单 KV；int 版本做字符串转换 |
| `private void setLockLobby(int lobbyId, boolean lock)` | 标记大厅占用状态 | lobbyLock 下写 `Pair(lock, now)` |
| `private boolean startLobbyInstance(int lobbyId)` | 尝试占用一个大厅槽位 | lobbyId 越界收敛到 [0,7]；槽空闲、或占用超过 `getEventTimeout()`、或 `isNobodyInPQ()`（事件图全空）时视为可占并标记 true |
| `private void freeLobbyInstance(String lobbyName)` | 归还实例占用的大厅 | 从 instanceLocks 删映射并解锁对应槽位 |
| `private int availableLobbyInstance()` | 找一个可用大厅 ID | 依 `getMaxLobbies()` 逐个 `startLobbyInstance`，全忙返回 -1 |
| `private String getInternalScriptExceptionMessage(Throwable a)` | 提取脚本异常根因消息 | 仅 ScriptException，沿 cause 链取最深层消息 |
| `private EventInstanceManager createInstance(String name, Object... args)` | 调脚本工厂函数（通常 `setup`）创建 EIM | `iv.invokeFunction(name, args)` 强转返回 |
| `private void registerEventInstance(String eventName, int lobbyId)` | 绑定实例名与大厅 | 若该实例名已绑旧大厅先解锁再覆盖 |
| `public boolean startInstance(Expedition exped)` / `(int lobbyId, Expedition exped)` / `(int lobbyId, Expedition exped, Character leader)` | 启动远征队事件（Boss 战） | 防重入（playerPermit + startSemaphore 7 并发，等待 7777ms）→ startLock 下占大厅 → `createInstance("setup", channel)` → `eim.setLeader` → `exped.start()` + `eim.registerExpedition` → `eim.startEvent()`；EIIP 异常（本内已在开）时释放大厅返回 false |
| `public boolean startInstance(Character chr)` / `(int lobbyId, Character leader)` / `(int lobbyId, Character chr, Character leader, int difficulty)` | 启动单人/公会 GPQ 事件 | 同上模式，`createInstance("setup", difficulty, lobbyId>-1?lobbyId:leaderId)`，`eim.registerPlayer(chr)` 注册开本者 |
| `public boolean startInstance(Party party, MapleMap map)` / `(int lobbyId, Party, MapleMap)` / `(int lobbyId, Party, MapleMap, Character leader)` / `(Party, MapleMap, int difficulty)` / `(int lobbyId, Party, MapleMap, int difficulty)` / `(int lobbyId, Party, MapleMap, int difficulty, Character leader)` | 启动组队 PQ（6 个重载逐步补大厅/难度/队长） | 防重入 → 占大厅 → `createInstance("setup", ...)`（无难度版传 null）→ `setLeader` → `registerParty(party, map)` + `party.setEligibleMembers(null)` → `startEvent()` |
| `public boolean startInstance(EventInstanceManager eim, String ldr)` / `(EventInstanceManager eim, Character ldr)` / `(int lobbyId, EIM, String ldr)` / `(int lobbyId, EIM, String ldr, Character leader)` | 启动外部已构造好的实例（非 PQ 流程） | 防重入 → 占大厅 → 直接 `registerEventInstance` + `setLeader` → `iv.invokeFunction("setup", eim)`（把现成 eim 传回脚本）→ `setProperty("leader", ldr)` → `startEvent()` |
| `public List<PartyCharacter> getEligibleParty(Party party)` | 问脚本哪些队员可参战 | 调脚本 `getEligibleParty(在线成员列表)`，返回数组转 List 并 `party.setEligibleMembers`；无脚本返回空表 |
| `public void clearPQ(EventInstanceManager eim[, MapleMap toMap])` | 通知脚本 PQ 通关 | 调脚本 `clearPQ(eim[, toMap])`，异常仅记日志 |
| `public long getEventTimeout()` | 大厅占用超时（BeiDou 扩展） | 默认 2h，脚本可定义 `getEventTimeout` 覆盖；超时后僵死大厅可被抢占 |
| `public boolean isNobodyInPQ()` | 事件地图是否全空（BeiDou 扩展） | 调脚本 `getEventMaps()` 取地图列表，任一图有人则 false——用于空本时强制释放大厅锁 |
| `public Monster getMonster(int mid)` | 取怪物对象 | `LifeFactory.getMonster` |
| `private void exportReadyGuild(Integer guildId)` | 公会进入备战通知 | 公会广播中文 callout（家族对抗赛 3 分钟提示） |
| `private void exportMovedQueueToGuild(Integer guildId, int place)` | 公会排队位次通知 | 公会广播队列排名 |
| `private List<Integer> getNextGuildQueue()` | 弹出队首公会 | `queuedGuilds.poll()` + 世界级去注册 + 通知其余公会位次前移；返回 [guildId, leaderId] |
| `public boolean isQueueFull()` / `int getQueueSize()` | 公会队列满/长度 | 队列大小对比 `event_max_guild_queue` |
| `public byte addGuildToQueue(Integer guildId, Integer leaderId)` | 公会报名 GPQ | 返回码：-1 已在队列 / 0 队满 / 1 排队成功 / 2 排队且立即开本；空队列时直接尝试 `attemptStartGuildInstance`，失败则回插队列 |
| `public boolean attemptStartGuildInstance()` | 尝试让队首公会开本 | 循环取队首直到找到在线会长，`startInstance(chr)` 成功则通知备战 |
| `public void startQuest(Character chr, int id, int npcid)` / `completeQuest(Character chr, int id, int npcid)` | 事件脚本内强制开始/完成任务 | `Quest.getInstance(id).forceStart/forceComplete`，NPE 打印 |
| `public int getTransportationTime(int travelTime)` | 修正船运时间 | `wserv.getTransportationTime` |
| `public int getBossTime(int BossTime)` | 修正 Boss 刷新时间 | 乘 `boss_respawn_mob_time_rate` 配置倍率 |
| `private void fillEimQueue()` | 异步填充 EIM 预创建池 | `ThreadManager` 提交 `EventManagerTask` |
| `private EventInstanceManager getReadyInstance()` | 从池中取一个就绪 EIM | queueLock 下取队首并 `fillEimQueue()` 补位；空则触发填充返回 null |
| `private void instantiateQueuedInstance()` | 递归预创建 EIM 直到阈值 | 池大小+加载中 ≥ `ceil(maxLobbys/3)` 停止；new EIM("sampleName"+id) 入池；已 disposed 则丢弃；递归自身继续填 |
| `public Long getNextScheduledTime()` | 最近调度到期时间 | 返回 volatile 字段（监控用） |

---

## EventInstanceManager（event）

事件实例（一个「房间/副本」）的运行时容器：管理参战玩家集合、怪物登记、击杀计数、事件计时器、奖励发放、地图工厂（EIM 专属地图实例）、属性与脚本回调分发（`playerEntry`/`monsterKilled`/`allMonstersDead` 等），并含 BeiDou 新增的伤害统计排名（源码路径：`scripting/event/EventInstanceManager.java`）。

### 关键字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `chars` | `Map<Integer, Character>` | 参战玩家（id→角色），读写锁保护 |
| `leaderId` | `int` | 事件队长 ID |
| `mobs` / `killCount` | `List<Monster>` / `Map<Character, Integer>` | 登记的非友方怪物与玩家击杀分 |
| `em` / `ess` / `mapManager` / `name` | `EventManager`/`EventScriptScheduler`/`MapManager`/`String` | 所属事件、实例级调度器、专属地图工厂、实例名 |
| `recordDamage` / `playerDamage` / `playerNames` | `volatile boolean` / `ConcurrentHashMap` ×2 | 伤害统计开关与累计（BeiDou 新增） |
| `props` / `objectProps` | `Properties` / `Map<String, Object>` | 字符串/对象属性（propertyLock 保护） |
| `timeStarted` / `eventTime` / `event_schedule` | `long` ×2 / `ScheduledFuture` | 事件倒计时状态 |
| `expedition` | `Expedition` | 关联远征队 |
| `mapIds` | `List<Integer>` | 实例用过的地图 |
| `readLock`/`writeLock`（公平 RW 锁）、`propertyLock`、`scriptLock` | `Lock` | 分域并发控制 |
| `disposed`/`eventCleared`/`eventStarted` | `boolean` | 生命周期标志 |
| `collectionSet`/`collectionQty`/`collectionExp` | `Map<Integer, List<Integer>>` 等 | 按 eventLevel 分层的通关奖励表 |
| `onMapClearExp` / `onMapClearMeso` | `List<Integer>` | 每阶段通关奖励 |
| `playerGrid` / `openedGates` / `exclusiveItems` | `Map` / `Map` / `Set` | 玩家状态网格 / 已开门记录 / 事件专属物品（退出时回收） |
| `MAX_DAMAGE_THRESHOLD` | `static final long` | 伤害累计溢出预警阈值 |

### 方法：生命周期与脚本回调

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public EventInstanceManager(EventManager em, String name)` | 构造函数 | 建实例级 `EventScriptScheduler` 与专属 `MapManager`，初始化公平读写锁 |
| `public void setName(String name)` / `String getName()` | 实例名读写 | 简单存取（池内 EIM 复用时改名） |
| `public EventManager getEm()` | 取所属 EventManager | scriptLock 下读取 |
| `public Object invokeScriptFunction(String name, Object... args)` | 调事件脚本函数 | 未 disposed 才调 `em.getIv().invokeFunction`，否则返回 null |
| `public synchronized void registerPlayer(Character chr[, boolean runEntryScript])` | 注册玩家进本 | 空对象/未登录/已 disposed 直接返回；写锁下入 chars 并 `chr.setEventInstance(this)`（幂等）；可选调脚本 `playerEntry(eim, chr)` |
| `public void exitPlayer(Character chr)` | 玩家退出并触发脚本 | `unregisterPlayer` 后调脚本 `playerExit` |
| `public void unregisterPlayer(Character chr)` | 注销玩家 | 先调脚本 `playerUnregistered`，写锁下移除并清角色的 EIM 引用，随后 `gridRemove` + `dropExclusiveItems` 回收专属物品 |
| `public synchronized void dispose()` / `dispose(boolean shutdown)` | 销毁实例 | 调脚本 `dispose` → disposed=true → `ess.dispose()` → 清 chars/mobs/计时器/计数/属性/伤害统计 → `disposeExpedition` → 未通关时 `em.disposeInstance(name)` 归还大厅；mapManager 延迟 1 分钟再释放（避免地图对象即刻销毁引发问题） |
| `public final synchronized void startEvent()` | 事件正式开始 | `eventStarted=true` 后调脚本 `afterSetup(eim)` |
| `public final void setEventCleared()` | 标记通关 | eventCleared=true，全员发任务点数（`quest_point_per_event_clear`），`em.disposeInstance(name)`，`disposeExpedition()` |
| `public final boolean isEventCleared()` / `isEventDisposed()` | 状态查询 | 简单 getter |
| `private void disposeExpedition()` | 结束关联远征 | `expedition.dispose(eventCleared)` + 从频道移除 |

### 方法：玩家查询与队伍/远征注册

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public int getPlayerCount()` / `Character getPlayerById(int id)` / `List<Character> getPlayers()` | 玩家数/单人/快照列表 | 均在 readLock 下访问 chars |
| `public int getEventPlayersJobs()` | 参战职业位掩码 | 每人 `1 << job.getJobNiche()`（0 初心 1 战士 2 法师 3 弓 4 贼 5 海盗） |
| `public void registerParty(Character chr)` | 以队长身份注册全队 | 仅当 `chr.isPartyLeader()` 转发 `registerParty(party, map)` |
| `public void registerParty(Party party, MapleMap map)` | 注册队伍符合条件成员 | 遍历 `party.getEligibleMembers()`（脚本过滤结果），在招募图上的在线成员逐个 `registerPlayer` |
| `public void registerExpedition(Expedition exped)` / `private void registerExpeditionTeam(...)` | 注册远征队 | 记录 expedition，把招募图上的活跃成员注册进本 |
| `public void leftParty(Character chr)` / `disbandParty()` | 队员离队/队伍解散回调 | 调脚本同名函数 |
| `public boolean isLeader(Character chr)` / `isEventLeader(Character chr)` | 队长/事件队长判断 | 比较 party leaderId / EIM leaderId |
| `public final void setLeader(Character chr)` / `Character getLeader()` / `final int getLeaderId()` | 事件队长读写 | 读写锁保护 |
| `public synchronized void changedLeader(PartyCharacter ldr)` | 队长变更 | 调脚本 `changedLeader` 后更新 leaderId |
| `public final boolean checkEventTeamLacking(boolean leavingEventMap, int minPlayers)` | 事件中队伍是否不足 | 已通关且人数>1 为 false；未通关且队长离图为 true；否则 `getPlayerCount() < minPlayers` |
| `public final boolean isExpeditionTeamLackingNow(boolean leavingEventMap, int minPlayers, Character quitter)` / `isEventTeamLackingNow(...)` | 远征/普通事件是否缺员 | 远征只需 `人数 <= 1`；普通事件额外判定队长退出 |
| `public final boolean isEventTeamTogether()` | 全员是否同图 | readLock 下遍历比较 mapId |
| `public final boolean disposeIfPlayerBelow(byte size, int towarp)` | 人数不足自动散本 | 人数 < size 时全员 unregister 并传 `towarp` 图，随后 `dispose()` 返回 true |

### 方法：事件计时器

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void startEventTimer(long time)` | 启动倒计时 | 记录起始时间/总时长，全员发包 `getClock`，到点后 `dismissEventTimer` 并调脚本 `scheduledTimeout(eim)` |
| `public void restartEventTimer(long time)` | 重置倒计时 | stop + start |
| `public void addEventTimer(long time)` | 追加时间 | 取消旧任务（成功时）按剩余+增量重排，未启动则直接 start |
| `private void dismissEventTimer()` | 收时钟 | 全员 `removeClock`，清零计时状态 |
| `public void stopEventTimer()` | 停表 | 取消任务 + dismiss |
| `public boolean isTimerStarted()` / `long getTimeLeft()` | 计时状态/剩余毫秒 | `eventTime - (now - timeStarted)` |

### 方法：战斗事件回调

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void registerMonster(Monster mob)` | 登记怪物 | 友方怪（月妙等）不入 mobs（不计入全灭判定） |
| `public void monsterKilled(Monster mob, boolean hasKiller)` | 怪物死亡分发 | scriptLock 下从 mobs 移除；eventStarted 时 scriptResult=1，mobs 清空则 =2；先调脚本 `monsterKilled(mob, eim, hasKiller)`，若 =2 再调 `allMonstersDead(eim, hasKiller)` |
| `public void monsterKilled(Character chr, Monster mob)` | 玩家击杀计分 | 调脚本 `monsterValue(eim, mobId)` 得增量，累加 killCount，远征时同步 `expedition.monsterKilled` |
| `public int getKillCount(Character chr)` | 取击杀分 | 无记录返回 0 |
| `public void friendlyKilled/friendlyDamaged/friendlyItemDrop(...)` | 友方怪事件 | 调脚本对应可选函数（缺失忽略） |
| `public void playerKilled(Character chr)` | 玩家死亡 | ThreadManager 异步调脚本 `playerDead` |
| `public void reviveMonster(Monster mob)` / `boolean revivePlayer(Character chr)` | 复活怪/玩家 | 调脚本 `monsterRevive`；`playerRevive` 返回 Boolean 时以其为准，否则 true |
| `public void playerDisconnected(Character chr)` | 玩家掉线 | 调脚本 `playerDisconnected`，并 `EventRecallCoordinator.storeEventInstance` 供重连找回 |
| `public void movePlayer(Character chr)` / `changedMap(chr, mapId)` / `afterChangedMap(chr, mapId)` | 移动/换图回调 | 调脚本 `moveMap`/`changedMap`/`afterChangedMap`（后两者可选） |

### 方法：奖励与地图

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void giveEventPlayersExp(int gain[, int mapId])` / `giveEventPlayersMeso(int gain[, int mapId])` | 全员（或指定图内成员）发经验/金币 | 各乘个人 expRate/mesoRate 后 `floatToInt`；`mapId=-1` 表示全员 |
| `public void applyEventPlayersItemBuff(int itemId)` / `applyEventPlayersSkillBuff(int skillId[, int skillLv])` | 全员上道具/技能 Buff | 取 StatEffect 后逐人 `applyTo` |
| `public void setEventRewards([int eventLevel,] List<Object> rwds, List<Object> qtys[, int expGiven])` | 配置通关奖励池（4 重载） | eventLevel 越界（>max_event_levels）忽略；分层存入 collectionSet/Qty/Exp |
| `public final boolean giveEventReward(Character player[, int eventLevel])` | 按层级抽一件奖励发放 | 无奖励池只发 EXP；`hasRewardSlot` 逐背包类型检查空位不足返回 false；`Math.random` 抽物品 `api.gainItem` + 发 EXP，返回 true |
| `public void setEventClearStageExp(List<Object> gain)` / `setEventClearStageMeso(List<Object> gain)` | 配置各阶段通关奖励序列 | 转存 onMapClearExp/onMapClearMeso |
| `public Integer getClearStageExp(int stage)` / `getClearStageMeso(int stage)` / `List<Integer> getClearStageBonus(int stage)` | 取第 N 阶段奖励（stage 从 1 计） | 越界返回 0；bonus 返回 [exp, meso] |
| `public final void giveEventPlayersStageReward(int thisStage)` | 发放阶段奖励 | exp + meso 一次性全员发放 |
| `public MapManager getMapFactory()` | 取 EIM 专属地图工厂 | — |
| `public MapleMap getMapInstance(int mapId)` | 取实例地图（首次加载可洗牌 reactor） | `map.setEventInstance(this)`；该图刚加载且事件属性 `shuffleReactors=true` 时 `map.shuffleReactors()` |
| `public final MapleMap getInstanceMap(int mapid)` | 取图并登记到 mapIds | disposed 返回 null |
| `public void spawnNpc(int npcId, Point pos, MapleMap map)` | 图内生成 NPC | 与 AbstractPlayerInteraction.spawnNpc 相同逻辑 |
| `public void dispatchRaiseQuestMobCount(int mobid, int mapid)` | 图内事件成员任务杀怪计数 | 图上有人才遍历成员 `raiseQuestMobCount` |
| `public Monster getMonster(int mid)` | 构造怪物 | `LifeFactory.getMonster` |

### 方法：属性

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void setProperty(String key, Integer/String value)` / `setIntProperty(String key, Integer value)` / `Object setProperty(String key, String value, boolean prev)` | 写属性 | propertyLock 下 `Properties.setProperty`（prev 版本返回旧值） |
| `public void setObjectProperty(String key, Object obj)` / `Object getObjectProperty(String key)` | 对象属性读写 | objectProps |
| `public String getProperty(String key)` / `int getIntProperty(String key)` | 读属性 | int 版本 null 安全（默认 "0"） |

### 方法：传送与效果

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void dropMessage(int type, String message)` | 全员公告 | 逐人 `chr.dropMessage` |
| `public final void warpEventTeam(int warpFrom, int warpTo)` / `warpEventTeam(int warpTo)` | 全员（限定来源图）传送 | 遍历玩家 `changeMap` |
| `public final void warpEventTeamToMapSpawnPoint(int warpFrom, int warpTo, int toSp)` / `(int warpTo, int toSp)` | 传送到指定出生点 | 同上带 toSp |
| `public final void showWrongEffect([int mapId])` | 播放阶段失败效果 | 广播 `quest/party/wrong_kor` + `Party1/Failed` |
| `public final void showClearEffect()` / `(boolean hasGate)` / `(int mapId)` / `(boolean hasGate, int mapId)` / `(int mapId, String mapObj, int newState)` / `(boolean hasGate, int mapId, String mapObj, int newState)` | 播放阶段通关效果（6 重载） | 广播 `quest/party/clear` + `Party1/Clear`；带门时广播 `environmentChange(mapObj, newState)` 并记录 openedGates |
| `public final void recoverOpenedGate(Character chr, int thisMapId)` | 为迟到/断线玩家补发开门状态 | 查 openedGates 有记录则补发 environmentChange |
| `public final void linkToNextStage(int thisStage, String eventFamily, int thisMapId)` | 把下阶段图 `next00` 门接到脚本 `<eventFamily><stage-1>` | 先发阶段奖励，再改门脚本名（脚本从 0 计、阶段从 1 计） |
| `public final void linkPortalToScript(int thisStage, String portalName, String scriptName, int thisMapId)` | 把指定门接到指定脚本 | 同上但门名/脚本名自定义 |

### 方法：玩家状态网格 / 专属物品

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public final void gridInsert(Character chr, int newStatus)` / `gridRemove(Character chr)` / `int gridCheck(Character chr)` / `gridSize()` / `gridClear()` | 玩家过关状态网格（如 LPQ 每人拉杆状态） | playerGrid 的加锁 CRUD，check 无记录返回 -1 |
| `private void dropExclusiveItems(Character chr)` | 回收玩家身上的事件专属物品 | 经 `AbstractPlayerInteraction.removeAll` 逐个清除 |
| `public void dropAllExclusiveItems()` | 全员回收 | 逐人调用 |
| `public final void setExclusiveItems(List<Object> items)` | 声明专属物品集 | 写锁下并入 exclusiveItems |
| `public boolean activatedAllReactorsOnMap(int mapId, int minReactorId, int maxReactorId)` / `(MapleMap map, ...)` | 指定图 ID 区间 reactor 是否全部激活完成 | 遍历 `getReactorsByIdRange`，`getReactorType() != -1` 即未完成 |

### 方法：伤害统计（BeiDou 新增）

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void startDamageRecording()` | 开启本副本伤害统计 | 全局开关 `damage_ranking` 开启才置 recordDamage=true，否则 debug 日志 |
| `public void addDamage(Character chr, int damage)` | 累加玩家伤害 | `merge` 累计并保存角色名；累计为负（溢出）重置 Long.MAX_VALUE 并 warn，超过阈值预警 |
| `public synchronized void broadcastDamageRanking()` | 通关后播报伤害排名 | 按伤害降序取前 5 名，连同总伤害以 `dropMessage(6, ...)` 中文播报 |
| `public synchronized void clearDamage()` | 清空统计 | 复位开关与两张表 |

### 方法：调度

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void schedule(String methodName, long delay)` | 实例级延迟调脚本函数 | readLock 下经 `ess.registerEntry` 注册（ess 为 null 已销毁则忽略） |

---

## EventScheduledFuture（event）

脚本调度任务的取消句柄，模拟 `ScheduledFuture` 接口供 JS 脚本 `cancel()` 使用（源码路径：`scripting/event/EventScheduledFuture.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `r` | `Runnable`（包级） | 被调度的任务 |
| `ess` | `EventScriptScheduler`（包级） | 所属调度器 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public EventScheduledFuture(Runnable r, EventScriptScheduler ess)` | 构造函数 | 保存任务与调度器 |
| `public void cancel(boolean dummy)` | 取消任务 | `ess.cancelEntry(r)`；dummy 参数被忽略——始终等价于「运行中不打断」语义 |

---

## EventScriptScheduler（event/scheduler）

轻量脚本调度器：以 `Map<Runnable, 到期时间>` + 单个周期扫描任务实现延时执行，空闲自动停表（到达 `mob_status_monitor_idle` 空转次数后取消扫描任务），有新任务时再拉起——避免每个事件常驻线程（源码路径：`scripting/event/scheduler/EventScriptScheduler.java`）。每个 `EventManager` 与每个 `EventInstanceManager` 各持有一个。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `disposed` / `idleProcs` | `boolean` / `int` | 已销毁标记 / 连续空转计数 |
| `registeredEntries` | `Map<Runnable, Long>` | 任务 → 到期服务器时间 |
| `schedulerTask` | `ScheduledFuture<?>` | 周期扫描任务（TimerManager 注册） |
| `schedulerLock` | `ReentrantLock(true)` | 公平锁保护全部状态 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `private void runBaseSchedule()` | 扫描循环体 | 锁内空则 idleProcs++，达到 `mob_status_monitor_idle` 取消扫描任务并返回；否则拷贝条目表，锁外对到期任务逐个 `run()`（异常记日志不中断），再锁内移除已完成项 |
| `public long registerEntry(Runnable scheduledAction, long duration)` | 注册延时任务，返回到期时间 | 经 ThreadManager 异步执行：重置空转计数；扫描任务未启动且未 disposed 时按 `mob_status_monitor_proc` 周期+初始延迟注册 `runBaseSchedule`；入表 |
| `public void cancelEntry(Runnable scheduledAction)` | 取消任务 | 异步从表中移除 |
| `public void dispose()` | 销毁调度器 | 异步取消扫描任务、清表、置 disposed |

---

## ItemScriptManager（item）

道具脚本管理器（单例）：使用道具（如任务道具、召唤包）触发脚本时把 `ScriptedItem` 转交给 NPC 脚本流程执行——道具脚本复用 NPC 脚本机制，注入变量名为 `im`（源码路径：`scripting/item/ItemScriptManager.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static ItemScriptManager getInstance()` | 取单例 | 饿汉式 |
| `public void runItemScript(Client c, ScriptedItem scriptItem)` | 执行道具脚本 | `NPCScriptManager.start(c, scriptItem, null)` → 走 itemScript=true 分支加载 `item/<script>.js` 并以 `im` 注入 |

---

## ItemScriptMethods（item）

道具脚本交互对象，`AbstractPlayerInteraction` 的空子类——不新增任何方法，完全复用父类 API（源码路径：`scripting/item/ItemScriptMethods.java`）。保留该类型是为了语义区分与未来扩展。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public ItemScriptMethods(Client c)` | 构造函数 | `super(c)` |

---

## MapScriptManager（map）

地图脚本管理器（单例）：按需加载 `map/<onFirstUserEnter|onUserEnter>/<name>.js` 并缓存，全局共享引擎实例（以 Invocable 为 key `synchronized` 串行调用），支持 `firstUser` 去重（每角色每图只触发一次）（源码路径：`scripting/map/MapScriptManager.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `scripts` | `Map<String, Invocable>` | 脚本路径 → 引擎（全局缓存，reloadScripts 清空） |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static MapScriptManager getInstance()` | 取单例 | — |
| `public void reloadScripts()` | 清空缓存（热重载） | `scripts.clear()` |
| `public boolean runMapScript(Client c, String mapScriptPath, boolean firstUser)` | 执行地图脚本 | ① firstUser=true 时查 `chr.hasEntered(path, mapid)` 已触发过返回 false，否则 `enteredScript` 登记；② 缓存命中则 `synchronized(iv)` 调 `start(new MapScriptMethods(c))`；③ 未命中加载 `map/<path>.js`（无会话缓存，全局共享），入缓存后同样同步调用；加载失败返回 false，异常记 error 日志 |

---

## MapScriptMethods（map）

地图脚本交互对象：在 `AbstractPlayerInteraction` 上叠加「新手剧情播放 / 定时全员传送 / 探险家勋章进度」等地图进入触发逻辑（源码路径：`scripting/map/MapScriptMethods.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `rewardstring` | `private final String` | 勋章完成提示后缀（找勋章老人领奖） |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public MapScriptMethods(Client c)` | 构造函数 | `super(c)` |
| `public void scheduleWarpMap(int seconds, int mapId)` | 定时把全图玩家传走 | 捕获当前图引用，`TimerManager.schedule` 秒级延迟后 `warpEveryone` |
| `public void displayCygnusIntro()` | 皇家骑士团（Cygnus）新手剧情 | 按当前地图（`MapId.CYGNUS_INTRO_*`）映射 `Direction.img/cygnusJobTutorial/Scene0-6`；首尾场景额外 `lockUI()` |
| `public void displayAranIntro()` | 战神新手剧情（地图版） | 按 `MapId.ARAN_TUTO_*` 映射 `Direction1.img/aranTutorial` 各 Scene，按性别拼 0/1，领取矛场景 lockUI |
| `public void startExplorerExperience()` | 冒险家五职业入职剧情 | 地图 1020100~1020500（战/法/弓/贼/海盗）按性别播放 `Direction3.img/<job>/Scene<gender>` |
| `public void goAdventure()` / `goLith()` | 出发冒险/前往金银岛剧情 | lockUI + `Direction3.img/goAdventure|goLith/Scene<gender>` |
| `public void explorerQuest(short questid, String questName)` | 探险家勋章（探索区域数）进度推进 | 已完成直接返回；未开始先 `forceStart`（NPC 9000066）；`addMedalMap` 登记当前图后把进度写回 quest 的 infoNumber 关联任务；进度达标播放完成提示（`getShowQuestCompletion`）与称号消息，否则播报 `当前/总数 区域已探索` |
| `public void touchTheSky()` | 「站在巅峰的人」勋章（quest 29004，探索 5 张巅峰图） | 同 explorerQuest 流程，硬编码 29004 与 5 区域文案 |

---

## NPCConversationManager（npc）

NPC 对话管理器（脚本变量 `cm`）：在 `AbstractPlayerInteraction` 之上提供 NPC 对话窗口发送（`sendOk/sendYesNo/sendSimple` 等全套）、外观/职业变更、联盟/公会、商店/扭蛋、CPQ（怪物嘉年华）、Ariant 竞技场、婚礼愿望单，以及 BeiDou 扩展的 `sendXxxLevel` 链式对话路由 API（配合 `NextLevelContext` 实现脚本内多级对话自动跳转）（源码路径：`scripting/npc/NPCConversationManager.java`）。

### 关键字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `npc` | `final int` | NPC ID |
| `npcOid` | `int` | NPC 地图对象 ID |
| `scriptName` / `getText` / `itemScript` | `String`/`String`/`boolean` | 脚本名 / 玩家文本输入暂存 / 是否道具脚本 |
| `otherParty` | `List<PartyCharacter>` | 对方队伍（婚礼等双队对话） |
| `gachaponService` | `static GachaponService` | 静态取 Spring Bean（启动时初始化） |
| `npcDefaultTalks` | `Map<Integer, String>` | NPC 默认对话缓存（WZ 提取） |
| `nextLevelContext` | `NextLevelContext`（`@Getter`） | 链式对话路由上下文 |

### 方法：基础信息与生命周期

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public NPCConversationManager(Client c, int npc, String scriptName)` / `(Client c, int npc, List<PartyCharacter> otherParty, boolean test)` / `(Client c, int npc, int oid, String scriptName, boolean itemScript)` | 三个构造重载 | 逐步补全 oid/itemScript/otherParty 等信息 |
| `private String getDefaultTalk(int npcid)` | 取 NPC 默认对话（带缓存） | `LifeFactory.getNPCDefaultTalk`，入 npcDefaultTalks 缓存 |
| `public int getNpc()` / `getNpcObjectId()` / `String getScriptName()` / `boolean isItemScript()` | 简单 getter | — |
| `public void resetItemScript()` | 清道具脚本标记 | itemScript=false（脚本加载失败回退 npc 脚本时用） |
| `public void dispose()` | 结束对话 | 清 nextLevelContext → `NPCScriptManager.dispose(this)` → 发 `enableActions` |

### 方法：对话窗口发送（说话者 speaker：0,1,8,9=NPC；2,3=玩家）

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void sendNext(String text[, byte speaker])` | 「下一页」对话 | `getNPCTalk(npc, 0, text, "00 01", speaker)`；每次发送前 `nextLevelContext.clear()` |
| `public void sendPrev(String text[, byte speaker])` | 「上一页」对话 | 同上，参数 `"01 00"` |
| `public void sendNextPrev(String text[, byte speaker])` | 「上一页/下一页」对话 | 参数 `"01 01"` |
| `public void sendOk(String text[, byte speaker])` | 「确认」对话 | 参数 `"00 00"` |
| `public void sendDefault()` | 发 NPC 默认对话 | `sendOk(getDefaultTalk(npc))` |
| `public void sendYesNo(String text[, byte speaker])` | 是/否选择 | type=1 |
| `public void sendAcceptDecline(String text[, byte speaker])` | 接受/拒绝 | type=0x0C |
| `public void sendSimple(String text[, byte speaker])` | 选项列表（配合 `#L..#..#l`） | type=4 |
| `public void sendStyle(String text, int[] styles)` | 外观选择窗口 | styles 空时降级 sendOk+dispose（防止客户端崩溃） |
| `public void sendGetNumber(String text, int def, int min, int max[, byte speaker])` | 数字输入窗口 | `getNPCTalkNum` |
| `public void sendGetText(String text[, byte speaker])` | 文本输入窗口 | `getNPCTalkText` |
| `public void sendDimensionalMirror(String text)` | 次元之镜（PQ 传送列表） | `getDimensionalMirror(text)`，注释列出 0-6 各索引对应的 PQ 类型 |
| `public void setGetText(String text)` / `String getText()` | 玩家输入文本的存/取 | 简单字段；action 回调链中由服务端写入 |

### 方法：任务（覆盖父类，NPC 上下文化）

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public boolean forceStartQuest(int id)` / `forceCompleteQuest(int id)` / `startQuest(short|int id)` / `completeQuest(short|int id)` | 覆盖：默认 NPC 从 `MAPLE_ADMINISTRATOR` 改为当前 `npc` | 转发带 npc 参数的父类实现 |

### 方法：经济与角色属性

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public int getMeso()` | 取金币 | — |
| `public void gainMeso(int gain)` / `gainMeso(Double gain)` | 增减金币 | Double 版本 `intValue()`（JS number 适配） |
| `public void gainExp(int gain)` | 增加经验 | `gainExp(gain, true, true)` |
| `public void setHair(int hair)` / `setFace(int face)` / `setSkin(int color)` | 换发型/脸型/肤色 | 更新属性并 `updateSingleStat` + `equipChanged` 刷新外观 |
| `public int itemQuantity(int itemid)` | 指定背包栏持有数 | `getInventory(type).countById` |
| `public StatEffect getItemEffect(int itemId)` | 取道具效果 | `ItemInformationProvider.getItemEffect` |
| `public void resetStats()` | 重置属性点 | `getPlayer().resetStats()` |
| `public void changeJobById(int a)` / `changeJob(Job job)` | 转职 | `Job.getById` 后 `changeJob` |
| `public String getJobName(int id)` | 职业中文名 | `GameConstants.getJobName` |
| `public String getName()` / `int getGender()` | 角色名/性别 | — |
| `public Character getMapleCharacter(String player)` | 按名找当前频道在线角色 | 玩家存储 `getCharacterByName` |
| `public Character getChrById(int id)` | 按 ID 找当前频道角色 | `getPlayerStorage().getCharacterById` |

### 方法：联盟与公会

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void displayGuildRanks()` | 显示公会排行 | `Guild.displayGuildRanks(client, npc)` |
| `public boolean upgradeAlliance(int cost, int maxCapacity)` | 扩建联盟容量 | 无公会/无联盟/扣费失败返回 false；成功后广播联盟信息与公告（异常记 i18n 日志） |
| `public boolean disbandAlliance(int allianceId)` | 解散联盟 | `Alliance.disbandAlliance` |
| `public boolean canBeUsedAllianceName(String name)` | 联盟名可用性 | `Alliance.canBeUsedAllianceName` |
| `public Alliance createAlliance(String name, int cost)` | 创建联盟（扣费版） | `Alliance.createAlliance(party, name, cost)` |
| `public int getAllianceCapacity()` | 联盟容量 | `Server.getAlliance(guild.allianceId).getCapacity()` |

### 方法：商店 / 扭蛋 / 雇佣商店

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void openShopNPC(int id)` | 打开 NPC 商店 | `ShopFactory.getShop(id)` 空时 warn 并回退商店 11000 |
| `public void doGachapon()` | 扭蛋（BeiDou 改为 Spring Service） | `gachaponService.doGachapon(player, npc)`（原 Gachapon.getInstance().process 逻辑被注释保留） |
| `public boolean hasMerchant()` | 是否有雇佣商店 | — |
| `public boolean hasMerchantItems()` | 雇佣商店是否有待取物品/金币 | `ItemFactory.MERCHANT.loadItems` 非空或 merchantMeso != 0 |
| `public void showFredrick()` | 打开雇佣商人（Fredrick）取货窗口 | `PacketCreator.getFredrick` |

### 方法：技能与技能书

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void maxMastery()` | 全技能满熟练度（GM 用） | 遍历 `String.img/Skill.img` 全技能 `changeSkillLevel(0级, maxLevel, -1)`，解析异常 break/continue |
| `public Object[] getAvailableMasteryBooks()` | 角色可用的精通书列表 | `ii.usableMasteryBooks(player)` |
| `public Object[] getAvailableSkillBooks()` | 可用技能书（含任务可教技能） | `ii.usableSkillBooks` + `SkillbookInformationProvider.getTeachableSkills` |
| `public String getSkillBookInfo(int itemid)` | 技能书获取途径文案 | 按 `SkillBookEntry`（UNAVAILABLE/REACTOR/SCRIPT/QUEST_*）返回带颜色码的英文说明 |

### 方法：物品查询与外观道具

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public boolean itemExists(int itemid)` | 物品是否存在 | `ii.getName(itemid) != null` |
| `public int getCosmeticItem(int itemid)` | 解析美容券可用基础 ID | itemId<30000（脸）按 `(id/1000)*1000+id%100` 回退，否则 `(id/10)*10`；最终不存在返回 -1 |
| `public boolean isCosmeticEquipped(int itemid)` | 该外观是否已装备 | <30000 比对 face，否则比对 hair |
| `public Object[] getNamesWhoDropsItem(Integer itemId)` | 掉落该物的怪物/NPC 名列表 | `ii.getWhoDrops` |
| `public void gainTameness(int tameness)` | 全部宠物加亲密度 | 逐宠物 `gainTamenessFullness` |

### 方法：PlayerNPC 与组队杂项

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public boolean canSpawnPlayerNpc(int mapid)` | 是否可生成名人 NPC | 关闭自动部署（`playernpc_auto_deploy`）且满级且非 GM 且 `PlayerNPC.canSpawnPlayerNpc` |
| `public PlayerNPC getPlayerNPCByScriptid(int scriptId)` | 当前图按脚本 ID 找名人 NPC | 遍历图上 PLAYER_NPC 对象比对 scriptId |
| `public void logLeaf(String prize)` | 枫叶兑换日志 | `MapleLeafLogger.log(player, true, prize)` |
| `public int partyMembersInMap()` | 同图队友数 | 遍历图上角色比对 party |
| `public Event getEvent()` | 频道进行中的 GM 活动 | `channelServer.getEvent()` |
| `public void divideTeams()` | GM 活动分队 | `setTeam(event.getLimit() % 2)` |
| `public boolean createPyramid(String mode, boolean party)` | 开奈特金字塔 PQ | 按 mode/组队拼基础图号，扫描 5×5 房间找空房；非组队时构造临时 Party；new Pyramid 后 warp 并 dispose |

### 方法：CPQ 怪物嘉年华（含 CPQ2）

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public int cpqCalcAvgLvl(int map)` | 图内平均等级 | 遍历 `getAllPlayer` 累加 |
| `public boolean sendCPQMapLists()` / `sendCPQMapLists2()` | 生成 CPQ/CPQ2 房间选择菜单 | 遍历 6（CPQ，图基 980000100）/3（CPQ2，图基 980031000）个房间，被占且有人显示等级/人数，空闲显示 2x2/3x3；`sendSimple` 发送，无房间返回 false |
| `public boolean fieldTaken(int field)` / `fieldTaken2(int field)` | 房间是否被占 | 频道 `canInitMonsterCarnival` 或房间/候场/场地三张图有人即占 |
| `public boolean fieldLobbied(int field)` / `fieldLobbied2(int field)` | 房间是否在候场 | 只查候场图（980000100 / 980031000 基） |
| `public void cpqLobby(int field)` / `cpqLobby2(int field)` | 全队进入候场图 | 逐人进图、公告、1.5s 后显示 3 分钟倒计时，并注册 3 分钟超时自动传送出场（`setCpqTimer`） |
| `public void cancelCPQLobby()` | 取消候场超时定时 | 全队 `clearCpqTimer` |
| `private void warpoutCPQLobby(MapleMap lobbyMap)` | 全员撤出候场 | 重置 CP/队伍/嘉年华引用并传送出口图（980000000 或 980030000） |
| `private int isCPQParty(MapleMap lobby, Party party)` | 队伍是否符合 CPQ 条件 | 按 lobby 类型取 30-50 / 51-70 级区间；返回 0 通过 / 1 有人不在场 / 2 有人等级不符 |
| `private int canStartCPQ(MapleMap lobby, Party party, Party challenger)` | 双方队伍开战校验 | 己方结果非 0 直接返回，否则返回对方结果的负值 |
| `public void startCPQ(Character challenger, int field)` | 开战 CPQ（延时 11s） | 拉对方队进图显示 10s 倒计时；延时任务内重置双方 MonsterCarnival 引用、校验 `canStartCPQ`，通过则 `new MonsterCarnival(己方, 对方, mapid+1, true, field)`，失败 `warpoutCPQLobby` |
| `public void startCPQ2(Character challenger, int field)` | 开战 CPQ2（延时 10s） | 同上，目标图 `mapid+100`、`MonsterCarnival(..., false, field/1000%10)` |
| `public void mapClock(int time)` | 地图广播倒计时 | `getClock` |
| `private boolean sendCPQChallenge(String cpqType, int leaderid)` | 发起挑战确认 | `MatchCheckerCoordinator.createMatchConfirmation(CPQ_CHALLENGE, ...)` 双队长确认 |
| `public void answerCPQChallenge(boolean accept)` | 应答挑战 | `answerMatchConfirmation` |
| `public void challengeParty(int field)` / `challengeParty2(int field)` | 挑战候场中的队伍 | 在候场图找对方队长（CPQ 先校验对方人数与己方一致），未受挑战则发起确认，否则提示；无队长提示 CPQLeaderNotFound |

### 方法：Ariant 竞技场（25 级 PvP）

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `private synchronized boolean setupAriantBattle(Expedition exped, int mapid)` | 占用竞技场图开战 | 竞技图（mapid+1）有人返回 false；`new AriantColiseum(arenaMap, exped)` |
| `public String startAriantBattle(ExpeditionType expedType, int mapid)` | 开战前置校验 | 依次校验：大厅地图（`isAriantColiseumLobby`）、已注册远征、人数上下限、全员同图、全员无队伍、等级 20~30；通过则 setup 并返回 ""，否则返回对应英文错误文案 |

### 方法：婚礼

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void sendMarriageWishlist(boolean groom)` | 查看新郎/新娘愿望单 | 依婚姻实例中 groom/bride 身份发 `WeddingPackets.onWeddingGiftResult(0xA 或 0x09, 愿望单, 礼物列表)`；本人查看 0xA、对方 0x09（先记录查看选择） |
| `public void sendMarriageGifts(List<Item> gifts)` | 查看已收礼物 | `onWeddingGiftResult(0xA, [""], gifts)` |
| `public boolean createMarriageWishlist()` | 打开愿望单编辑（未婚且未填过） | 本人对应 `groomWishlist/brideWishlist` 属性为空时发送编辑窗口返回 true |

### 方法：BeiDou 链式对话（NextLevel 系）

以下方法均「先发对话包，再把回调路由信息写入 `nextLevelContext`」，供 `NPCScriptManager.nextLevel` 在玩家点击后按 `NextLevelType` 自动 `invokeFunction("level" + ...)` 跳转到脚本内下一个函数；带 speaker 的重载指定说话者。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void sendNextLevel(String nextLevel, String text[, byte speaker])` | 「下一步」对话 | sendNext + 记 `SEND_NEXT`/nextLevel |
| `public void sendLastLevel(String lastLevel, String text[, byte speaker])` | 「上一步」对话 | sendPrev + 记 `SEND_LAST`/lastLevel |
| `public void sendLastNextLevel(String lastLevel, String nextLevel, String text[, byte speaker])` | 「上一步+下一步」对话 | sendNextPrev + 记 `SEND_LAST_NEXT` 两者 |
| `public void sendOkLevel(String nextLevel, String text[, byte speaker])` | 「确认」对话 | sendOk + 记 `SEND_OK` |
| `public void sendSelectLevel(String text[, byte speaker])` / `(String prefix, String text[, byte speaker])` | 选项对话，自动路由 `level<prefix><selection>` | sendSimple + 记 `SEND_SELECT`/prefix（多次选择用不同前缀区分） |
| `public void sendNextSelectLevel(String nextLevel, String text[, byte speaker])` | 选项对话，路由到指定方法并传入选择值 | sendSimple + 记 `SEND_NEXT_SELECT`/nextLevel |
| `public void getInputNumberLevel(String nextLevel, String text, int def, int min, int max[, byte speaker])`（带 speaker 版本名为 `getPnpcInputNumberLevel`） | 数字输入对话 | sendGetNumber + 记 `GET_INPUT_NUMBER` |
| `public void getInputTextLevel(String nextLevel, String text[, byte speaker])`（带 speaker 版本名为 `getPnpcInputTextLevel`） | 文本输入对话 | sendGetText + 记 `GET_INPUT_TEXT` |
| `public void sendAcceptDeclineLevel(String decLineLevel, String acceptLevel, String text[, byte speaker])` | 接受/拒绝对话 | sendAcceptDecline + 记 `SEND_ACCEPT_DECLINE`（last=拒绝, next=接受） |
| `public void sendYesNoLevel(String noLevel, String yesLevel, String text[, byte speaker])` | 是/否对话 | sendYesNo + 记 `SEND_YES_NO`（last=否, next=是） |

---

## NPCScriptManager（npc）

NPC 脚本管理器（单例）：维护 `Client → NPCConversationManager` 与 `Client → Invocable` 两张会话表，负责 NPC/道具脚本启动（`start` 多态重载）、玩家对话应答回调（`action` 传统模式 / `nextLevel` 链式模式）、会话清理（`dispose`）（源码路径：`scripting/npc/NPCScriptManager.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `cms` | `Map<Client, NPCConversationManager>` | 会话表（每客户端一个对话） |
| `scripts` | `Map<Client, Invocable>` | 会话对应引擎（会话级缓存于 Client） |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static NPCScriptManager getInstance()` | 取单例 | — |
| `public boolean isNpcScriptAvailable(Client c, String fileName)` | 脚本是否存在 | `getInvocableScriptEngine("npc/"+fileName+".js", c)` 非 null（会话缓存） |
| `public boolean start(Client c, int npc, Character chr)` / `(Client c, int npc, int oid, Character chr)` / `(Client c, int npc, String fileName, Character chr)` | 启动 NPC 对话（重载逐步补 oid/脚本名） | 最终统一到私有 7 参 `start` |
| `public boolean start(Client c, ScriptedItem scriptItem, Character chr)` | 启动道具脚本 | `start(c, scriptItem.getNpc(), -1, scriptItem.getScript(), chr, true, "im")`——注入名 `im` |
| `private boolean start(Client c, int npc, int oid, String fileName, Character chr, boolean itemScript, String engineName)` | （核心实现）启动对话 | ① new CM；已有会话先 dispose；② `canClickNPC()` 防重入（否则发 enableActions 返回 true）；③ 脚本查找顺序：普通 NPC `npc/<fileName>.js` → `BeiDouSpecial/<fileName>.js`（北斗自定义脚本目录）；道具脚本 `item/<fileName>.js`；仍无则回退 `npc/<npcid>.js` 并 `resetItemScript`；④ 找不到 dispose 返回 false；⑤ `engine.put(engineName, cm)` 注入、记 scripts、`setClickedNPC`；⑥ 先试 `invokeFunction("start")`，NoSuchMethod 再试 `start(chr)`；异常 dispose 返回 false |
| `public void start(String filename, Client c, int npc, List<PartyCharacter> chrs)` | 双队对话（婚礼/组队任务） | 构造带 otherParty 的 CM，已有会话直接返回；调脚本 `start(chrs)`；缺失脚本提示 "NPC uncoded" |
| `public void action(Client c, byte mode, byte type, int selection)` | 传统应答回调 | 取会话引擎，`tryacquireClient`/`setClickedNPC` 后调脚本 `action(mode, type, selection)`；异常记日志并 dispose，finally `releaseClient` |
| `public void nextLevel(Client c, byte mode, byte type, int selection)` | 链式应答回调（BeiDou 新增） | 按 `nextLevelContext.getLevelType()` 路由：`SEND_SELECT` → `level<prefix><selection>`；`GET_INPUT_NUMBER/SEND_NEXT_SELECT` → `level<nextLevel>(selection)`；`GET_INPUT_TEXT` → `level<nextLevel>(cm.getText())`；按钮类（NEXT/OK/YES_NO 等）mode==-1 退出、mode==0 走 `level<lastLevel>`（上一步/否）、否则 `level<nextLevel>`；所有输入类 mode==0 直接 dispose；异常 dispose |
| `public void dispose(NPCConversationManager cm)` | （实现）清理会话 | `setCS(false)`、设置 NPC 冷却时间戳、移除两张表；按脚本类型（item/npc）+ 脚本名或 npcId 调 `resetContext` 清引擎缓存；`flushDelayedUpdateQuests` 落任务更新 |
| `public void dispose(Client c)` / `dispose(Client c, boolean action)` | 按客户端清理（可选补发 enableActions） | 有 CM 才走 dispose(cm) |
| `public NPCConversationManager getCM(Client c)` | 取当前会话 CM | — |

---

## PortalScript（portal，接口）

传送门脚本必须实现的 Java 接口，由 JS 脚本经 `Invocable.getInterface(PortalScript.class)` 生成代理（脚本需提供 `enter(ppi)` 函数）（源码路径：`scripting/portal/PortalScript.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `boolean enter(PortalPlayerInteraction ppi)` | 玩家进入传送门 | 返回 true 表示脚本已处理本次进入（失败/拦截返回 false 走默认传送） |

---

## PortalScriptManager（portal）

传送门脚本管理器（单例）：全局缓存 `portal/<name>.js` 的 `PortalScript` 代理，执行时以脚本代理对象为锁串行调用（GraalJS 并发限制）（源码路径：`scripting/portal/PortalScriptManager.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `scripts` | `Map<String, PortalScript>` | 脚本路径 → 接口代理（全局缓存） |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static PortalScriptManager getInstance()` | 取单例 | — |
| `private PortalScript getPortalScript(String scriptName)` | 取/加载脚本代理 | 缓存命中直接返回；否则 `getInvocableScriptEngine("portal/<name>.js")`（全局共享、无会话缓存）→ `iv.getInterface(PortalScript.class)`；脚本未实现 enter 抛 ScriptException；入缓存 |
| `public boolean executePortalScript(Portal portal, Client c)` | 执行传送门脚本 | `use_debug` 且 GM 时提示脚本名；取到脚本后 `synchronized(script)` 调 `enter(new PortalPlayerInteraction(c, portal))` 返回其结果；异常 warn 返回 false |
| `public void reloadPortalScripts()` | 清缓存热重载 | `scripts.clear()` |

---

## PortalPlayerInteraction（portal）

传送门脚本交互对象（脚本入参 `ppi`）：在 `AbstractPlayerInteraction` 上补充传送门上下文与几个门户专用操作（源码路径：`scripting/portal/PortalPlayerInteraction.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `portal` | `final Portal` | 当前传送门对象 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public PortalPlayerInteraction(Client c, Portal portal)` | 构造函数 | `super(c)` + 保存门 |
| `public Portal getPortal()` | 取传送门 | — |
| `public void runMapScript()` | 触发 `onUserEnter` 地图脚本 | `runMapScript(c, "onUserEnter/" + portal.getScriptName(), false)` |
| `public boolean hasLevel30Character()` | 账号下是否有 ≥30 级角色（引导用） | 直查 `characters` 表（accountid=?）任一 level>=30；SQL 异常或无则看当前角色等级 |
| `public void blockPortal()` / `unblockPortal()` | 屏蔽/解除屏蔽该脚本门 | `chr.blockPortal/unblockPortal(scriptName)`（任务中封门常用） |
| `public void playPortalSound()` | 播放传送音效 | `PacketCreator.playPortalSound()` |

---

## QuestActionManager（quest）

任务脚本交互对象（脚本变量 `qm`）：`NPCConversationManager` 子类，记录「开始/完成」上下文，覆盖经验/金币发放走任务 Action 体系，提供勋章名查询（源码路径：`scripting/quest/QuestActionManager.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `start` | `final boolean` | true=任务开始脚本 / false=完成脚本 |
| `quest` | `final int` | 任务 ID |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public QuestActionManager(Client c, int quest, int npc, boolean start)` | 构造函数 | `super(c, npc, null)`（npc 作为对话 NPC） |
| `public int getQuest()` / `boolean isStart()` | 取任务 ID / 是否开始脚本 | — |
| `public void dispose()` | 结束会话 | `QuestScriptManager.dispose(this, getClient())`（父类版会发 enableActions 由 NPC 流程负责） |
| `public boolean forceStartQuest()` / `forceCompleteQuest()` | 对当前任务强制开始/完成 | 转发父类（npc 为当前对话 NPC） |
| `public void startQuest()` / `completeQuest()` | 旧脚本兼容别名 | 直接调 force 版本 |
| `public void gainExp(int gain)` / `gainMeso(int gain)` | 覆盖：走任务奖励 Action | `ExpAction.runAction` / `MesoAction.runAction`（保证任务统计/公告一致） |
| `public String getMedalName()` | 勋章任务（299XX）对应勋章名 | `Quest.getMedalRequirement` → `ItemInformationProvider.getName` |

---

## QuestScriptManager（quest）

任务脚本管理器（单例）：维护 `Client → QuestActionManager/Invocable` 会话，驱动任务 `start/end/raiseOpen` 三类脚本入口与应答回调，支持勋章任务回退通用脚本 `medalQuest.js`（源码路径：`scripting/quest/QuestScriptManager.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `qms` / `scripts` | `Map<Client, QuestActionManager>` / `Map<Client, Invocable>` | 会话表 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static QuestScriptManager getInstance()` | 取单例 | — |
| `private ScriptEngine getQuestScriptEngine(Client c, short questid)` | 加载任务脚本（带回退） | `quest/<id>.js` 不存在且为勋章任务（`isMedalQuest`）时回退 `quest/medalQuest.js` |
| `public void start(Client c, short questid, int npc)` | 启动任务开始脚本 | new QM(start=true)；已有会话/不可点击直接返回；引擎缺失 warn "uncoded" 并 dispose；注入 `qm` 后调 `start(1, 0, 0)`；异常 dispose |
| `public void start(Client c, byte mode, byte type, int selection)` | 开始脚本应答回调 | 调脚本 `start(mode, type, selection)` |
| `public void end(Client c, short questid, int npc)` | 启动任务完成脚本 | 前置校验：任务确为 STARTED 且 NPC 在图上或任务 autoComplete，否则 dispose 返回；流程同上但 QM(start=false)、调 `end(1, 0, 0)` |
| `public void end(Client c, byte mode, byte type, int selection)` | 完成脚本应答回调 | 调脚本 `end(mode, type, selection)` |
| `public void raiseOpen(Client c, short questid, int npc)` | 任务被打开（查看）时触发 | 同 start 流程，调脚本 `raiseOpen`（无脚本静默 dispose） |
| `public void dispose(QuestActionManager qm, Client c)` | （实现）清理会话 | 移除两张表、设 NPC 冷却、`resetContext("quest/<id>.js")` 清引擎缓存、flushDelayedUpdateQuests |
| `public void dispose(Client c)` | 按客户端清理 | 有 qm 才清理 |
| `public QuestActionManager getQM(Client c)` | 取当前会话 QM | — |
| `public void reloadQuestScripts()` | 清缓存热重载 | 清空 scripts 与 qms |
| `public boolean checkFunctionExists(Client c, short questid, int npc, String functionName)` | 检查任务脚本是否定义某函数 | 加载引擎后注入临时 QM，eval 一段 `checkFunction` JS（`typeof this[funcName] === 'function'`）并调用；异常/不存在返回 false（结尾 dispose 临时会话） |

---

## ReactorScriptManager（reactor）

反应堆脚本管理器（单例）：按 reactor ID 加载 `reactor/<id>.js`（会话缓存于 Client），分发 `hit/act/touch/untouch` 四类事件，并缓存 reactor 掉落表（DB `reactordrops`）（源码路径：`scripting/reactor/ReactorScriptManager.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `drops` | `Map<Integer, List<ReactorDropEntry>>` | reactorId → 掉落表缓存（chance >= 0） |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static ReactorScriptManager getInstance()` | 取单例 | — |
| `public void onHit(Client c, Reactor reactor)` | reactor 被击触发 | 初始化引擎后调脚本 `hit`；NoSuchMethod 静默（hit 可选），其余异常记日志 |
| `public void act(Client c, Reactor reactor)` | reactor 状态推进触发 | 初始化引擎后调脚本 `act`（缺失/异常记日志） |
| `public void touch(Client c, Reactor reactor)` / `untouch(Client c, Reactor reactor)` | 触碰/离开触发 | 私有 `touching(c, reactor, true/false)` 调 `touch/untouch`（可选函数） |
| `private void touching(Client c, Reactor reactor, boolean touching)` | （实现）触碰分发 | 按布尔选函数名调用 |
| `private Invocable initializeInvocable(Client c, Reactor reactor)` | 加载脚本并注入 `rm` | `getInvocableScriptEngine("reactor/<id>.js", c)`（会话缓存）；new `ReactorActionManager` 后 `engine.put("rm", rm)` |
| `public List<ReactorDropEntry> getDrops(int reactorId)` | 取掉落表（懒加载缓存） | 缓存未命中查 `reactordrops`（reactorid=? AND chance>=0）组装 `ReactorDropEntry(itemid, chance, questid)`；异常记日志并缓存空表 |
| `public void clearDrops()` | 清掉落缓存 | `drops.clear()`（重载用） |

---

## ReactorActionManager（reactor）

反应堆脚本交互对象（脚本变量 `rm`）：在 `AbstractPlayerInteraction` 上提供 reactor 掉落喷洒（drop/spray 两模式）、怪物批量生成/击杀、守护者 Buff 驱散等 PQ 常用操作（源码路径：`scripting/reactor/ReactorActionManager.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `reactor` | `final Reactor` | 当前反应堆 |
| `iv` | `final Invocable` | 脚本引擎 |
| `sprayTask` | `ScheduledFuture<?>` | 延时喷洒任务句柄 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public ReactorActionManager(Client c, Reactor reactor, Invocable iv)` | 构造函数 | 保存三引用 |
| `public void hitReactor()` | 手动推进 reactor 状态 | `reactor.hitReactor(c)` |
| `public Reactor getReactor()` | 取 reactor | — |
| `public Point getPosition()` | 取掉落锚点 | reactor 坐标 y-10 |
| `public void destroyNpc(int npcId)` | 销毁图上脚本生成的 NPC | `reactor.getMap().destroyNPC(npcId)` |
| `private static void sortDropEntries(...)` | 掉落三分类 | 非任务物品 / 当前玩家可见的任务物品（`needQuestItem`）/ 其他任务物品 |
| `private static List<ReactorDropEntry> assembleReactorDropEntries(Character chr, List<ReactorDropEntry> items)` | 组装并乱序掉落序列 | 三类各自 shuffle 后拼接，再奇偶拆两列、偶数列翻转拼接——模拟左右交替散落 |
| `public void sprayItems()` / `(boolean meso, int mesoChance, int minMeso, int maxMeso)` / `(... , int minItems)` | 喷洒模式掉落（延时逐个） | 全部转发 `dropItems(true, x, y, ...)`，坐标取 reactor 位置 |
| `public void dropItems()` / `(boolean meso, ...)` / `(..., int minItems)` / `(int posX, int posY, boolean meso, ...)` | 掉落模式（4 重载） | 注意：坐标版内部也传 `delayed=true`——所有 reactor 掉落实际均为逐个延时 |
| `public void dropItems(boolean delayed, int posX, int posY, boolean meso, int mesoChance, int minMeso, int maxMeso, int minItems)` | （实现）掉落主流程 | ① `generateDropList(getDropChances(), dropRate, ...)` 按概率生成掉落（mesoChance 中 1 概率加金币袋 itemId=0；`dropRate/chance` 概率掉物品；不足 minItems 补金币）；② `assembleReactorDropEntries` 排列；③ 立即模式：逐个按左右对称坐标落物（itemId==0 出金币 `spawnMesoDrop`，装备先 `randomizeStats`，其余 `dropFromReactor`）；④ 延时模式：`TimerManager.register(200ms)` 逐个喷出（`dropPos.x += 25` 步进），队列空自动 cancel |
| `private List<ReactorDropEntry> getDropChances()` | 取掉落表 | `ReactorScriptManager.getInstance().getDrops(reactor.getId())` |
| `public void spawnMonster(int id)` / `(int id, int qty)` / `(int id, int qty, int x, int y)` / `(int id, int qty, Point pos)` / `(int id, int x, int y)` | 生成怪物（5 重载） | qty 版循环 `spawnMonsterOnGroundBelow`；坐标版 set 位置后 `spawnMonster` |
| `public void spawnFakeMonster(int id)` | 生成假怪（不主动攻击） | `spawnFakeMonsterOnGroundBelow` |
| `public void killMonster(int id[, boolean withDrops])` | 击杀图上怪物 | `killMonsterWithDrops` / `killMonster` |
| `public void spawnNpc(int npcId)` / `(int npcId, Point pos)` | 图上生成 NPC | 复用父类三参方法 |
| `public void createMapMonitor(int mapId, String portal)` | 创建地图监视器（无人自动重置） | `new MapMonitor(map, portal)` |
| `public void summonBossDelayed(int mobId, int delayMs, int x, int y, String bgm, String summonMessage)` | 延时召唤 Boss（塔查/狮蝎） | delay 后 `summonBoss`：生成怪 + 换 BGM + 地图公告 |
| `private void summonBoss(int mobId, int x, int y, String bgmName, String summonMessage)` | （实现）召唤 | 组合调用 |
| `public void dispelAllMonsters(int num, int team)` | CPQ 驱散守护者 Buff | 取 `CarnivalFactory.getGuardian(num)` 技能，对指定队伍全部怪物 `dispelSkill`，并从红/蓝队 Buff 表移除 |
