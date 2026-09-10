# 详细设计文档 09 —— 频道处理器 handlers（中）：HealOvertimeHandler ~ QuestActionHandler

> 模块路径：`org.gms.net.server.channel.handlers`
>
> 类数量：本篇覆盖 **50** 个 handler 类（另含同文件辅助类 `PairedQuicksort` 与嵌套枚举 `PlayerInteractionHandler.Action`）；handlers 目录全量 **149** 个 `.java` 文件
>
> 依赖模块：`org.gms.net`（AbstractPacketHandler / InPacket / OutPacket / SendOpcode / Server / Channel / world.\* / coordinator.\* / guild.\*）、`org.gms.client`（Character / Client / Skill / SkillFactory / BuffStat / Disease / Family / BuddyList / Mount / status.\*）、`org.gms.client.inventory`（Item / Equip / Inventory / InventoryType / Pet / PetCommand / PetDataFactory / ModifyInventory / manipulator.InventoryManipulator / manipulator.KarmaManipulator）、`org.gms.client.autoban`（AutobanFactory / AutobanManager）、`org.gms.client.keybind.KeyBinding`、`org.gms.client.processor`（action.MakerProcessor / action.PetAutopotProcessor / npc.DueyProcessor）、`org.gms.config.GameConfig`、`org.gms.constants.*`（game.GameConstants / id.\* / inventory.ItemConstants / skills.\*）、`org.gms.dao.entity.NotesDO`、`org.gms.service`（NoteService / HpMpAlertService）、`org.gms.manager.ServerManager`、`org.gms.model.pojo.NewYearCardRecord`、`org.gms.scripting`（npc.NPCScriptManager / quest.QuestScriptManager / event.EventInstanceManager）、`org.gms.server`（CashShop / Trade / StatEffect / ChatLogger / ItemInformationProvider / MTSItemInfo / quest.Quest / quest.medal.OutstandingCitizenMedal / partyquest.CarnivalFactory / partyquest.MonsterCarnival）、`org.gms.server.life`（Monster / MobSkill\* / LifeFactory.BanishInfo / NPC / PlayerNPC / MonsterInformationProvider）、`org.gms.server.maps`（MapleMap / Portal / MapObject(MapObjectType) / MapItem / HiredMerchant / PlayerShop(PlayerShopItem) / MiniGame / Dragon / Summon / FieldLimit）、`org.gms.server.movement.LifeMovementFragment`、`org.gms.util`（PacketCreator / Pair / Randomizer / DatabaseConnection / I18nUtil / packets.WeddingPackets）、`org.gms.exception.EmptyMovementException`、`java.sql`（JDBC 直连）、`org.slf4j`

## 覆盖范围与三篇分工

handlers 目录共 **149** 个文件，按文件名字节序（`LC_ALL=C sort`）均分为三篇：

| 篇 | 序号区间 | 文件区间 | 数量 |
|---|---|---|---|
| 08（handlers-A） | 1–50 | `AbstractDealDamageHandler` ~ `GuildOperationHandler` | 50 |
| **09（本篇，handlers-B）** | **51–100** | **`HealOvertimeHandler` ~ `QuestActionHandler`** | **50** |
| 10（handlers-C） | 101–149 | `QuickslotKeyMappedModifiedHandler` ~ `WhisperHandler` | 49 |

本篇 50 个类全部继承/实现 `AbstractPacketHandler` 体系（`validateState` 默认要求 `c.isLoggedIn()`，详见 06 篇），由 `net/PacketProcessor` 按 `RecvOpcode` 注册分发；其中 `MagicDamageHandler` 继承 `AbstractDealDamageHandler`、五个 `Move*Handler` 继承 `AbstractMovementPacketHandler`（两个抽象基类均在 08 篇，本篇仅摘要引用其被继承方法）。两个 handler（`PlayerLoggedinHandler`、`NoteActionHandler`）经 `PacketProcessor` 的 `ChannelDependencies` 注入 Spring bean `NoteService` 构造。

### 本篇覆盖清单（字母序 → RecvOpcode）

| # | Handler | RecvOpcode | 业务 |
|---|---|---|---|
| 51 | HealOvertimeHandler | HEAL_OVER_TIME | 自然回血/回魔（含反作弊） |
| 52 | HiredMerchantRequest | HIRED_MERCHANT_REQUEST | 请求开设雇佣商人 |
| 53 | InnerPortalHandler | USE_INNER_PORTAL | 同图内传送门 |
| 54 | InventoryMergeHandler | ITEM_SORT | 背包整理（合并堆叠+前移） |
| 55 | InventorySortHandler | ITEM_SORT2 | 背包排列（快排重排） |
| 56 | ItemMoveHandler | ITEM_MOVE | 物品移动/穿脱/丢弃 |
| 57 | ItemPickupHandler | ITEM_PICKUP | 玩家拾取掉落物 |
| 58 | ItemRewardHandler | USE_ITEM_REWARD | 使用随机奖励道具 |
| 59 | KeymapChangeHandler | CHANGE_KEYMAP | 键位/快捷键修改 |
| 60 | LeftKnockbackHandler | LEFT_KNOCKBACK | 左侧击退（反挂机）响应 |
| 61 | MagicDamageHandler | MAGIC_ATTACK | 魔法攻击 |
| 62 | MakerSkillHandler | MAKER_SKILL | 制造技能（委托处理器） |
| 63 | MesoDropHandler | MESO_DROP | 丢金币 |
| 64 | MessengerHandler | MESSENGER | 小型Messenger群聊 |
| 65 | MobBanishPlayerHandler | MOB_BANISH_PLAYER | 怪物放逐技能传送玩家 |
| 66 | MobDamageMobFriendlyHandler | MOB_DAMAGE_MOB_FRIENDLY | 友方怪被打（任务护卫怪） |
| 67 | MobDamageMobHandler | MOB_DAMAGE_MOB | 心灵控制怪打怪 |
| 68 | MonsterBombHandler | MONSTER_BOMB | 引爆暗星 |
| 69 | MonsterBookCoverHandler | MONSTER_BOOK_COVER | 怪物卡封面设置 |
| 70 | MonsterCarnivalHandler | MONSTER_CARNIVAL | CPQ 嘉年华操作 |
| 71 | MoveDragonHandler | MOVE_DRAGON | 龙同步移动 |
| 72 | MoveLifeHandler | MOVE_LIFE | 怪物移动（控制端） |
| 73 | MovePetHandler | MOVE_PET | 宠物移动 |
| 74 | MovePlayerHandler | MOVE_PLAYER | 玩家移动 |
| 75 | MoveSummonHandler | MOVE_SUMMON | 召唤兽移动 |
| 76 | MTSHandler | MTS_OPERATION | MTS 交易市场全套操作 |
| 77 | MultiChatHandler | MULTI_CHAT | 好友/组队/公会/联盟群聊 |
| 78 | NewYearCardHandler | NEW_YEAR_CARD_REQUEST | 新年贺卡寄送/领取 |
| 79 | NoteActionHandler | NOTE_ACTION | 纸条（送礼附言/丢弃领 fame） |
| 80 | NPCAnimationHandler | NPC_ACTION | NPC 表情/移动动画转发 |
| 81 | NPCMoreTalkHandler | NPC_TALK_MORE | NPC 对话后续交互 |
| 82 | NPCShopHandler | NPC_SHOP | NPC 商店买卖/充值 |
| 83 | NPCTalkHandler | NPC_TALK | 点击 NPC 开启对话/商店 |
| 84 | OpenFamilyHandler | OPEN_FAMILY | 打开家族信息面板 |
| 85 | OpenFamilyPedigreeHandler | OPEN_FAMILY_PEDIGREE | 打开家族谱系面板 |
| 86 | OwlWarpHandler | OWL_WARP | 密诺娃之枭传送到商店 |
| 87 | PartyOperationHandler | PARTY_OPERATION | 组队操作 |
| 88 | PartySearchRegisterHandler | PARTY_SEARCH_REGISTER | 组队搜索注册（空实现） |
| 89 | PartySearchStartHandler | PARTY_SEARCH_START | 开始组队搜索 |
| 90 | PartySearchUpdateHandler | PARTY_SEARCH_UPDATE | 注销组队搜索 |
| 91 | PetAutoPotHandler | PET_AUTO_POT | 宠物自动喝药 |
| 92 | PetChatHandler | PET_CHAT | 宠物聊天 |
| 93 | PetCommandHandler | PET_COMMAND | 宠物指令（抚摸等） |
| 94 | PetExcludeItemsHandler | PET_EXCLUDE_ITEMS | 宠物拾取过滤清单 |
| 95 | PetFoodHandler | PET_FOOD | 喂宠物食品 |
| 96 | PetLootHandler | PET_LOOT | 宠物拾取 |
| 97 | PlayerInteractionHandler | PLAYER_INTERACTION | 小房间（交易/小游戏/商店/雇佣商人） |
| 98 | PlayerLoggedinHandler | PLAYER_LOGGEDIN | 角色进入频道 |
| 99 | PlayerMapTransitionHandler | PLAYER_MAP_TRANSFER | 完成切图后的善后 |
| 100 | QuestActionHandler | QUEST_ACTION | 任务开始/完成/放弃 |

## 目录

1. [登录与地图切换（4 类）](#1-登录与地图切换4-类)
2. [背包与物品（7 类）](#2-背包与物品7-类)
3. [战斗与怪物（8 类）](#3-战斗与怪物8-类)
4. [移动同步（5 类 + 基类摘要）](#4-移动同步5-类--基类摘要)
5. [NPC 与任务（5 类）](#5-npc-与任务5-类)
6. [聊天与社交（4 类）](#6-聊天与社交4-类)
7. [组队（4 类）](#7-组队4-类)
8. [宠物（6 类）](#8-宠物6-类)
9. [家族（2 类）](#9-家族2-类)
10. [交易 / 玩家商店 / 雇佣商人 / MTS（4 类）](#10-交易--玩家商店--雇佣商人--mts4-类)
11. [其他（2 类）](#11-其他2-类)

---

## 1. 登录与地图切换（4 类）

### 1.1 PlayerLoggedinHandler（PLAYER_LOGGEDIN，角色进入频道入口）

**概述**：处理客户端选角后进入游戏频道的 `PLAYER_LOGGEDIN` 包，是全模块最重的 handler 之一（约 400 行主流程）。负责角色装载、登录态防重、频道/世界注册、buff/疾病恢复、社交关系（好友/家族/公会/联盟/组队/伴侣）挂接、各类定时任务启动与登录通知。OdinMS 遗留代码 + BeiDou 增强（HP/MP 预警同步、系统救援提示等）。

**关键字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `attemptingLoginAccounts` | `static Set<Integer>` | 类级防重入集合：同一账号并发登录时只放行一个 |
| `noteService` | `NoteService`（构造注入） | 纸条服务，登录时展示未读纸条（`PacketProcessor` 用 `channelDeps.noteService()` 构造） |
| `hpMpAlertService` | `static HpMpAlertService` | 经 `ServerManager.getApplicationContext().getBean(...)` 静态获取，读取 HP/MP 预警阈值 |

**公有方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `PlayerLoggedinHandler(NoteService)` | 构造器 | 保存 Spring 注入的 NoteService |
| `@Override boolean validateState(Client c)` | 反向状态校验 | 返回 `!c.isLoggedIn()`——本包必须在"尚未登录游戏世界"的过渡态处理（登录流程自己把状态推进到 LOGGEDIN） |
| `@Override void handlePacket(InPacket, Client)` | 主流程 | 见下 |

**handlePacket 关键流程**（`finally` 中必定 `c.releaseClient()`）：

1. 读取 `cid`；`c.tryacquireClient()` 抢客户端锁（失败仅发 `getAfterLoginError(10)`，注意此处并未 return，继续持锁流程）。
2. 解析 `World`/`Channel`：world 为空 → 断线；channel 为空则回退频道 1，仍空 → 断线。
3. `PlayerStorage.getCharacterById(cid)` 找在线角色（频道切换场景复用内存对象）；取 HWID——新登录走 `SessionCoordinator.pickLoginSessionHwid(c)`，切频道复用旧 client 的 HWID；`validateCharacteridInTransition` 校验登录服登记，失败断线。
4. 角色不存在（`newcomer` 场景）→ `Character.loadCharFromDB(cid, c, true)` 全量装载；仍失败 → 断线。`c.setPlayer/setAccID` 绑定。
5. **防双登**：`tryAcquireAccount(accId)`（对 `attemptingLoginAccounts` 同步去重）；登录态必须为 `LOGIN_SERVER_TRANSITION`，否则清空 player/accID 并按状态断线或报 `getAfterLoginError(7)`；通过则 `updateLoginState(LOGIN_LOGGEDIN)` 后 `releaseAccount`。
6. 非 newcomer：继承旧 client 的语言与角色槽位数，`player.newClient(c)`；`use_server_auto_pot` 开启时从 `HpMpAlertService` 读 HP/MP 预警并 `updateClientSettings`（仅发本人，注释说明该包是客户端本地设置、广播会污染他人配置）。
7. `cserv.addPlayer` + `wserv.addPlayer` + `setEnteredChannelWorld`；从 `PlayerBuffStorage` 恢复 buff（`getLocalStartTimes` 把流逝时间换算成本地起始时刻并排序后 `silentGiveBuffs`）与疾病（`silentApplyDiseases`）。
8. 发送 `getCharInfo`（登录成功主包）；GM 且 `use_auto_hide_gm` 或原隐身角色 → `toggleHide(true)`；`sendKeymap/sendQuickmap/sendMacros`；回放 91/92 键位的自动 HP/MP 药水绑定（防跨角色串用）。
9. 进图：`map.addPlayer` + `player.visitMap`；好友上线通知 `wserv.loggedOn` + `multiBuddyFind` 回填在线频道 + `updateBuddylist`。
10. 家族：`loadFamily`；有 familyId 则找回 `FamilyEntry` 并互绑、发 `getFamilyInfo`、向长辈播报上线；家族/公会数据缺失时做降级清理（`deleteGuild`、`setGuildId(0)`）。
11. 公会：`getGuild` → `setGuildMemberOnline(true)` + `showGuildInfo`；联盟缺失时 `Alliance.loadAlliance` 惰性装载，newcomer 时广播联盟成员上线。
12. `OutstandingCitizenMedal.refreshEligibility`（优秀市民勋章资格）；`noteService.show(player)`（未读纸条）；`c.getSysRescue().showMapChangeMessage(player)`（异常地图掉线提示，BeiDou 增量）。
13. 组队：`MPC` 更新 channel/mapId/online → `wserv.updateParty(LOG_ONOFF)` + `updatePartyMemberHP`。
14. 装备栏加锁遍历 `player.equippedItem((Equip) it)` 触发套装/属性重算；再次 `updateBuddylist`；`pollPendingRequest` 弹出待处理好友请求；`updateGender`、`checkMessenger`、`enableReport`；按联动角色等级授予本职业 12 号技能（`jobType*10000000+12`，等级 = linkedLevel/10）；`checkBerserk`。
15. newcomer 专属：宠物注册饥饿定时（`wserv.registerPetHunger`）、坐骑包 `updateMount`、`reloadQuestExpirations`、GM/管理员上线播报或 `use_login_notification` 全服公告、疾病 debuff 视觉包重放；非 newcomer：战舰血量 `announceBattleshipHp`。
16. 公共收尾任务：`buffExpireTask / diseaseExpireTask / skillCooldownTask / expirationTask / questExpirationTask`；龙族（`hasSPTable` 且非 2001）`createDragon`；`commitExcludedItems`（宠物过滤清单）；`showDueyNotification`（查 `dueypackages` 表 `Checked=1` 的包裹 → `sendDueyParcelNotification` 并复位标志）；费率三连 `resetPlayerRates → setPlayerRates(use_add_rates_by_level) → setWorldRates → updateCouponRates`；`receivePartyMemberHP`；已婚角色互发 `WeddingPackets.OnNotifyWeddingPartnerTransfer`；newcomer 时 `EventRecallCoordinator.recallEventInstance` 恢复活动副本；`use_npcs_scriptable` → `setNPCScriptable`（含转生 NPC 强制注册）；newcomer 记录 `setLoginTime`。

**私有方法**：`tryAcquireAccount/releaseAccount`（防双登集合的进出）、`showDueyNotification`（JDBC 直查 `dueypackages`）、`getLocalStartTimes`（buff 起始时间换算排序）。

**核心协作类**：`Server`/`World`/`Channel`/`PlayerStorage`、`SessionCoordinator`+`Hwid`、`Character`（loadCharFromDB 与十余个生命周期钩子）、`PlayerBuffStorage`、`Guild`/`Alliance`/`GuildPackets`、`Family`/`FamilyEntry`、`BuddyList`、`NoteService`/`HpMpAlertService`（Spring）、`EventRecallCoordinator`、`WeddingPackets`、`OutstandingCitizenMedal`、`GameConfig`、`PacketCreator`。

### 1.2 PlayerMapTransitionHandler（PLAYER_MAP_TRANSFER，完成切图善后）

**概述**：玩家完成切换地图后由客户端触发的收尾包：清除引导锁、取消引导蜂 buff、并把本图怪物控制权重新分配给该玩家（修复隐身角色控怪、切图后怪物状态残留等问题）。HeavenMS 作者 Ronan 移植，类注释"玩家完成切换地图触发"。

**handlePacket 关键流程**：

1. `chr.setMapTransitionComplete()`——解除"正在换图"标记（`MoveLifeHandler`、`NPCAnimationHandler` 等依赖此标记拒绝换图期间的包）。
2. 引导蜂（`BuffStat.HOMING_BEACON`）存在则 `cancelBuffStats` 并发 `giveBuff(1, beaconid, [HOMING_BEACON→0])` 复位。
3. 非隐身角色：遍历本图怪物（跳过特效刷新怪 `spawnEffect != 0` 且满血者），对每只怪：若自己正是控制者 → `stopControllingMonster` + `sendDestroyData` + `aggroRemoveController`，否则仅 `sendDestroyData`；随后统一 `sendSpawnData` 重新生成并 `aggroSwitchController(chr, false)` 争取控制权。

**核心协作类**：`Character`（buff/aggro 钩子）、`Monster`、`MapleMap.getMonsters`、`PacketCreator`。

### 1.3 InnerPortalHandler（USE_INNER_PORTAL，同图内传送门）

**概述**：处理"树洞/隐藏门"类同图传送（`USE_INNER_PORTAL`）。BeiDou 重写版：拆分为多个语义化私有方法，并带位置校验与宠物拾取补偿联动。

**常量**：`INNER_PORTAL_TRIGGER_DISTANCE_SQ = 90000.0`（约 300 像素距离平方，玩家必须贴近门才有效）。

**handlePacket 关键流程**：

1. `isPlayerReady`（玩家与地图非空）且包内尚有数据；`readPortalNameSafely`：跳 1 个标志字节读门名（异常吞掉返回 null）。
2. `resolveValidSourcePortal`：按名取门，必须存在且 `getType() == Portal.TELEPORT_PORTAL`。
3. `isPlayerNearPortal`：玩家坐标与门距离平方 ≤ 90000。
4. `isSameMapInnerTeleport`：仅当 `sourcePortal.getTargetMapId() == player.getMapId()`（跨图传送交给常规换图流程 `ChangeMapHandler`，见 08 篇）。
5. `resolveValidTargetPortal`：按 `sourcePortal.getTarget()` 取目标门，坐标非空。
6. **宠物拾取联动**：`player.setPetLootTeleportBeforePos(传送前坐标)`——供 `PetLootHandler`（8.6 节）对"传送前位置遗留掉落物"放行，防误杀。
7. `map.movePlayer(player, 目标门坐标)` + `player.markTeleportLikeMove(前坐标, 后坐标)`（供攻击距离双坐标校验，与 `AbstractMovementPacketHandler` 的瞬移记录同源）。

**核心协作类**：`MapleMap`（getPortal/movePlayer）、`Portal`、`Character`（宠物补偿/传送标记）。

### 1.4 LeftKnockbackHandler（LEFT_KNOCKBACK，反挂机左推）

**概述**：部分地图（如修炼地图左侧边界）客户端触发"左侧击退"检测时，服务端原样回 `leftKnockBack` + `enableActions` 两个包，无业务逻辑。注意该类是本篇唯一非 `final` 且方法非 `final` 的 handler。

---

## 2. 背包与物品（7 类）

### 2.1 InventoryMergeHandler（ITEM_SORT，整理背包）

**概述**：处理客户端"整理（Merge）"按钮：先合并同类可堆叠物品，再把物品前移补洞。

**handlePacket 关键流程**：

1. `readInt`（时间戳）→ `AutobanManager.setTimestamp(2, 服务器时间, 4)`（操作频率审计通道 2）。
2. 配置 `use_item_sort` 关闭 → 仅 `enableActions` 返回。
3. 读背包类型 byte，越界（<1 或 >5）→ `c.disconnect(false, false)`。
4. `inventory.lockInventory()` 加锁（try/finally 解锁）：
   - **RonanLana 槽位合并**：外层 dst 从 1 到 slotLimit，内层 src 从 dst+1 起扫；同 itemId 且 dst 未堆满（`ii.getSlotMax`）时 `InventoryManipulator.move(c, type, src, dst)` 合并。
   - **前移补洞**：循环找 `getNextFreeSlot()`，若其后还有物品槽则 move 到空槽，否则结束。
5. 发 `finishedSort(invType)` + `enableActions`。

**核心协作类**：`Inventory`（锁与槽位遍历）、`InventoryManipulator.move`、`ItemInformationProvider.getSlotMax`、`GameConfig`、`AutobanManager`。

### 2.2 InventorySortHandler（ITEM_SORT2，排列背包）+ PairedQuicksort

**概述**：处理客户端"排列（Sort）"按钮：把整个背包按双关键字快排后重排槽位，并以 `ModifyInventory` 增量包回放。同文件包含包级辅助类 `PairedQuicksort`（原版为 Hoare 风格手写快排）。

**handlePacket 关键流程**：

1. 时间戳审计通道 3（`setTimestamp(3, ..., 4)`）；`use_item_sort` 配置门；invType 1..5 校验否则断线。
2. 加背包锁：拷贝所有槽内 `Item.copy()` 到 `itemarray`，逐个 `removeSlot` 并记录 `ModifyInventory(3=删除, item)`。
3. 排序规则：主关键字 = `use_item_sort_by_name` 开启则按名称（2）否则按 itemId（0）；次关键字 = 装备栏按强化等级（3）、其他栏按数量（1）。
4. `new PairedQuicksort(itemarray, 主, 次)`；USE 栏额外对投射物分组（itemId/10000 为 206 箭矢 / 207 飞镖 / 233 子弹）按 `getWatkForProjectile` 降序（强力的排前面）。
5. 排完逐个 `inventory.addItem(item)` 回填并记录 `ModifyInventory(0=新增, item.copy())`；解锁。
6. 发 `modifyInventory(true, mods)` + `finishedSort2(invType)` + `enableActions`。

**PairedQuicksort（包级类）**：

| 方法 | 作用 |
|---|---|
| 构造器 `(ArrayList<Item>, int primarySort, int secondarySort)` | 先全局主关键字 `MapleQuicksort`；USE 栏对 206/207/233 子段 `reverseSortSublist`；随后用 `intersect` 列表记录"同 itemId 分组边界"，对每组执行次关键字排序 |
| `MapleQuicksort(Esq, Dir, A, sort)` | 按 sort 值分发到 `PartitionByItemId(0)/Quantity(1)/Name(2)/Level(3)` 的 Hoare 划分并递归 |
| `PartitionByProjectileAtk` + `getWatkForProjectile` | 按投射物攻击力划分（用于倒序子段） |
| `BinarySearchElement(A, rangeId)` | 二分定位 `itemId/10000 == rangeId` 的连续子段边界 `[st, en]` |
| `reverseSortSublist(A, range)` | 对该子段调用投射物划分实现"同组内降序" |

**核心协作类**：`PairedQuicksort`、`ModifyInventory`、`Inventory`、`ItemInformationProvider`、`GameConfig`、`AutobanManager`。

### 2.3 ItemMoveHandler（ITEM_MOVE，物品移动/穿脱/丢弃）

**概述**：处理背包内物品的四种操作：槽位移动、穿上/脱下装备、丢弃。用 `src/action` 的正负组合区分语义（源码注释「使用装备、物品、道具」）。

**handlePacket 关键流程**：

1. skip 4；spam 通道 6 的 300ms 节流（过快仅 `enableActions`）。
2. 读 `type`（背包类型）、`src`、`action`、`quantity`。
3. 分派：`src<0 && action>0` → `InventoryManipulator.unequip`（从装备栏脱到背包装备栏）；`action<0` → `equip`（穿上，action 为目标装备位负值）；`action==0` → `drop`（丢到地面）；否则 `move`（普通槽位移动）。
4. `abm.spam(6)` 记录节流时间。

**核心协作类**：`InventoryManipulator`（四个静态操作）、`InventoryType`、`AutobanManager`。

### 2.4 ItemPickupHandler（ITEM_PICKUP，玩家拾取）

**概述**：玩家按 Z 拾取地面掉落。核心是**距离反作弊**：玩家坐标与物品坐标偏差超阈值判定为吸物（VAC）外挂。

**handlePacket 关键流程**：

1. 读时间戳 int、标志 byte、玩家坐标 `readPos`（均不使用）、掉落物 `oid`。
2. `map.getMapObject(oid)`，null 直接返回（物品已被捡/消失）。
3. 距离校验：`|dx| > 800 || |dy| > 600` → `AutobanFactory.ITEM_VAC.addPoint`（累积扣分）+ warn 日志（BeiDou 汉化的日志文案，含玩家名/地图/距离），并 return。
4. `chr.pickupItem(ob)` 交给 `Character` 执行实际的背包入包/金币入账/任务物品判定。

**核心协作类**：`MapleMap.getMapObject`、`Character.pickupItem(MapObject)`、`AutobanFactory.ITEM_VAC`。

### 2.5 ItemRewardHandler（USE_ITEM_REWARD，随机奖励道具）

**概述**：使用"奖励类"道具（如活动礼盒），按 WZ 概率表随机抽一个奖励发放；可附带全服公告。

**handlePacket 关键流程**：

1. 读 USE 栏槽位 short 与 itemId；校验该槽物品存在、id 一致且 `countById ≥ 1`（防改包）。
2. `ItemInformationProvider.getItemReward(itemId)` 得 `(总概率, 奖励列表)`；`Randomizer.nextInt(totalProb)` 后按 `reward.prob` 累积命中选出 `RewardItem`。
3. 命中后：`InventoryManipulator.checkSpace` 不足 → `getShowInventoryFull`；否则 EQUIP 类走 `ii.getEquipById` 生成（`period != -1` 时设置到期时间 `now + period*60*60*10`——源码 TODO 标注疑似应为 `60*60*1000` 的笔误）后 `addFromDrop`；普通物品 `addById`。
4. `removeById` 消耗原道具 1 个。
5. 奖励配置 `worldmsg` 非空时替换 `/name`（玩家名）与 `/item`（物品名）占位符，`Server.broadcastMessage(serverNotice(6, ...))` 全世界公告。
6. 收尾 `enableActions`。

**核心协作类**：`ItemInformationProvider.getItemReward/getEquipById/getName`、`InventoryManipulator`、`Randomizer`、`Server`、`ItemConstants.getInventoryType`。

### 2.6 MesoDropHandler（MESO_DROP，丢金币）

**概述**：玩家丢出金币到地面。带并发保护（`tryacquireClient`）与钓鱼系统联动（BeiDou/Cosmic 增量）。

**handlePacket 关键流程**：

1. 死亡状态仅 `enableActions`；skip 4 后读 `meso`。
2. GM 等级低于 `minimum_gm_level_to_drop` 时禁止（提示语 `player.message`）。
3. `c.tryacquireClient()` 抢锁（防"丢金币过快"竞态）：校验 `10 ≤ meso ≤ 50000` 且 `meso ≤ 现有金币` → `gainMeso(-meso)`；否则 `enableActions` 返回；finally `releaseClient`。
4. `player.attemptCatchFish(meso)` 命中钓鱼（金币作为鱼饵被吞）→ `map.disappearingMesoDrop`（不落地直接消失）；否则 `map.spawnMesoDrop(meso, pos, player, player, true, (byte)2)` 正常落地掉落包。

**核心协作类**：`Client.tryacquireClient/releaseClient`、`Character.gainMeso/attemptCatchFish`、`MapleMap.spawnMesoDrop/disappearingMesoDrop`、`GameConfig`。

### 2.7 KeymapChangeHandler（CHANGE_KEYMAP，键位修改）

**概述**：客户端键盘设置面板提交的键位变更：普通键位批量改、自动 HP/MP 药水绑定（虚拟键 91/92）。

**handlePacket 关键流程**：

1. 包长 ≥ 8 才处理；读 `mode` int：
   - `mode 0`（批量键位）：读变更数，逐条读 `key`(int)/`type`(byte)/`action`(int)。`type == 1`（技能绑定）时校验技能合法性：`GameConstants.bannedBindSkills`（禁绑技能）、非 GM 绑 GM 技能、非 GM 且 `!isInJobTree`（不在本职业技能树，含 Dojo 竹林雨等"新手页"技能）——不合法仅 `continue` 跳过该条（历史上曾直接断线，代码注释"fk that"改为宽容跳过）。合法则 `changeKeybinding(key, new KeyBinding(type, action))`。
   - `mode 1 / 2`（自动 HP / MP 药水）：读 itemID，非 0 时必须真的拥有该 USE 栏物品（`findById == null` → `disconnect(false,false)` 防改包），然后绑定 91/92 键 `KeyBinding(7, itemID)`。

**核心协作类**：`Character.changeKeybinding`、`KeyBinding`、`SkillFactory`、`GameConstants`、`Inventory.findById`。

---

## 3. 战斗与怪物（8 类）

### 3.1 HealOvertimeHandler（HEAL_OVER_TIME，自然回复）

**概述**：客户端按角色自然回复节奏上报的 HP/MP 恢复量。服务端双重反作弊：恢复过快（FAST_HP/MP_HEALING 计分）与单次恢复量过大（HIGH_HP_HEALING 直接自动封禁）。MP 侧叠加魔法师"魔力恢复"技能的服务端下限。

**handlePacket 关键流程**：

1. `isLoggedInWorld` 校验；`AutobanManager abm` + 服务器时间戳；skip 8。
2. **HP 段**：读 short `healHP`；非 0 时 `setTimestamp(8, ts, 28)`；若 `lastSpam(0)+1500 > ts`（1.5s 内重复恢复）→ `FAST_HP_HEALING.addPoint`。阈值 `abHeal = 77 * map.getRecovery() * 1.5`（地图 recovery 系数，修复桑拿房等高回复图误判）；`healHP > abHeal` → `HIGH_HP_HEALING.autoban` 并 return；否则 `addHP` + 地图广播 `showHpHealed` + `spam(0)`。
3. **MP 段**：读 short `healMP`；非 0 且 < 1000 才处理（≥1000 视为非法直接忽略）；`setTimestamp(9, ts, 28)`；`lastSpam(1)+1500 > ts` → `FAST_MP_HEALING.addPoint` 且 **return**（防检测后仍加蓝）；`applyImprovedMpRecovery` 后 `addMP` + `spam(1)`。

**私有方法 `applyImprovedMpRecovery(chr, healMP)`**：取 `Magician.IMPROVED_MP_RECOVERY` 技能等级，>0 时按 `skillLevel * (level*0.1) + 3` 计算服务端期望恢复量，返回 `max(客户端上报值, 期望值)`——注释说明这是服务端下限，防止客户端已包含加成的场景被重复计算（BeiDou 修复）。

**核心协作类**：`AutobanManager`/`AutobanFactory`、`MapleMap.getRecovery`、`Character.addHP/addMP`、`SkillFactory`、`PacketCreator.showHpHealed`。

### 3.2 MagicDamageHandler（MAGIC_ATTACK，魔法攻击）

**概述**：魔法系攻击包。继承 `AbstractDealDamageHandler`（08 篇），复用 `parseDamage` 解析与 `applyAttack` 伤害结算，自身只做魔法特有的蓄力/冷却/MP 吸收逻辑。

**handlePacket 关键流程**：

1. `parseDamage(p, chr, ranged=false, magic=true)` → `AttackInfo`（含 skill/skilllevel/stance/allDamage/charge/speed/direction/display 等，解析与攻击盒/间隔反作弊详见 08 篇基类）。
2. 变身检查：`BuffStat.MORPH` 效果若 `isMorphWithoutAttack()`（不可攻击变身）→ 客户端不可能发出该包，`disconnect(false,false)`。
3. 道场加能量：`MapId.isDojo(mapId) && numAttacked > 0` → `dojo_energy_atk` 配置加值并发 `getEnergy("energy", ...)`。
4. 蓄力技能：Evan 火息/冰息、FP/IL 大法师与主教的全屏爆炸 `BIG_BANG` 取 `attack.charge`，其余 -1。
5. 构造 `PacketCreator.magicAttack(...)` 并 `map.broadcastMessage(chr, packet, false, true)`（不同步给发送者本人——客户端已自行表现）。
6. 冷却：`StatEffect.getCooldown() > 0` 时，`skillIsCooling` → 直接 return（重复放）;否则下发 `skillCooldown` 包并 `addCooldown`。
7. `applyAttack(attack, chr, effect.getAttackCount())` 结算伤害（基类方法：扣血、仇恨、死亡分发等）。
8. **MP 吸收（MP Eater）**：被动技能 id = `(job - job%10) * 10000`（本职业段 4 位前缀）；有等级则对 `attack.allDamage` 里每个受击对象 `applyPassive(chr, mapObject, 0)` 触发吸魔。

**核心协作类**：`AbstractDealDamageHandler.parseDamage/applyAttack/AttackInfo`（08 篇）、`PacketCreator.magicAttack/skillCooldown/getEnergy`、`SkillFactory`、`StatEffect`、`MapId`、`GameConfig`。

### 3.3 MobBanishPlayerHandler（MOB_BANISH_PLAYER，怪物放逐）

**概述**：怪物释放 BANISH 类技能把玩家放逐回指定地图时客户端上报（由 `MoveLifeHandler` 的 banish 流程配合）。

**handlePacket 关键流程**：读怪物 id → `map.getMonsterById`；怪物存在且 `getBanish()`（`LifeFactory.BanishInfo`：目标图/门/文案）非空 → `chr.changeMapBanish(map, portal, msg)` 强制换图。

**核心协作类**：`Monster.getBanish`、`Character.changeMapBanish`。

### 3.4 MobDamageMobHandler（MOB_DAMAGE_MOB，心灵控制怪打怪）

**概述**：怪物互殴包（催眠/心灵控制 INERTMOB 状态的怪攻击其他怪）。服务端必须把伤害归属到催眠者玩家，并对客户端上报伤害做上限校验。

**handlePacket 关键流程**：

1. 读攻击方 oid、（弃 1 int）、受击方 oid、`magic`（byte==0 为魔法）、`dmg`；双方均须为当前地图存在的怪物。
2. **归属判定**（BeiDou 中文注释明确）：攻击方带 `MonsterStatus.INERTMOB` → 伤害记其 controller；否则受击方带 INERTMOB → 记受击方 controller；两者皆无控制状态 → warn「2 个怪物都没有心灵控制，互殴？」并使本次攻击无效。
3. `calcMaxDamage(attacker, damaged, magic)`（私有）：攻击力按 `MonsterStatus.WEAPON/MAGIC_ATTACK_UP` 加成与 `WATK/MATK` 削减修正（`calcModifier`，基准 100）；公式 `(atk * (1.15 + 0.025*等级) - 0.75*防御) * log(|防御-攻击|)/log(magic?12:17)`。
4. `dmg > maxDmg` → warn（含怪物名、伤害、上限；注释讨论了客户端 StatEffect 默认 damage=100 可能导致的偏差）并**钳制为 maxDmg**（历史上是 autoban，已注释掉）。
5. `map.damageMonster(damageChr, damaged, dmg)` 结算 + `broadcastMessage(damageChr, damageMonster(to, dmg), false)`。

**核心协作类**：`Monster`（getStati/getController/getStats）、`MonsterStatus(MonsterStatusEffect)`、`MonsterInformationProvider.getMobNameFromId`、`MapleMap.damageMonster`。

### 3.5 MobDamageMobFriendlyHandler（MOB_DAMAGE_MOB_FRIENDLY，友方怪被打）

**概述**：任务护卫怪（月妙、护卫小浣猪、罗密欧/朱丽叶、大雪人等）被怪物攻击的处理。伤害由服务端公式生成（不信任客户端），死亡时播报场景文案。

**handlePacket 关键流程**：

1. 读攻击方 oid（弃 1 int）、受击友方 oid；双方怪物均须存在。
2. 伤害公式：`Randomizer.nextInt((maxHp/13 + PADamage*10)*2 + 500) / 10`（源码注明 Formula planned by Beng）。
3. 致死判定 `hp - damage < 1`：按怪物 id 播报中文提示（`MobId.WATCH_HOG`「被外星人殴打致重伤…」、`MOON_BUNNY`、`TYLUS`、`JULIET`、`ROMEO`、三个 `GIANT_SNOWMAN_LV1_*`、`DELLI`），随后 `map.killFriendlies(monster)`；未致死则 `EventInstanceManager.friendlyDamaged(monster)` 通知活动脚本（如 HPQ 失败判定）。
4. `applyAndGetHpDamage(damage, false)`；剩余 HP ≤ 0 时钳 0 并 `removeMapObject`。
5. 广播 `MobDamageMobFriendly(monster, damage, remainingHp)` + `enableActions`。

**核心协作类**：`Monster`、`EventInstanceManager.friendlyDamaged`、`MapleMap.killFriendlies/removeMapObject/broadcastMessage`、`MobId`、`Randomizer`。

### 3.6 MonsterBombHandler（MONSTER_BOMB，引爆暗星）

**概述**：玩家点击引爆地图上的暗星怪（扎昆舞台机关）。读 oid → 玩家存活且怪物 id 为 `MobId.HIGH_DARKSTAR`/`LOW_DARKSTAR` 时：广播 `killMonster(oid, 4)`（爆炸动画类型）+ `removeMapObject`。

### 3.7 MonsterCarnivalHandler（MONSTER_CARNIVAL，CPQ 操作）

**概述**：怪物嘉年华（CPQ）面板操作：召唤怪物（tab 0）、给敌方上 debuff（tab 1）、放置守护 statue（tab 2），消耗 CP。全程 `tryacquireClient` 包裹、内层再 try/catch 吞异常。

**handlePacket 关键流程**：

1. 读 `tab`、`num`；`neededCP = 0`。
2. **tab 0 召唤**：`map.getMobsToSpawn()` 取可召唤 `(mobId, cp)` 列表；`num` 越界或 CP 不足 → `CPQMessage(1)`；`MonsterCarnival mcpq` 按队伍校验召唤配额（`canSummonR`/`canSummonB`）不足 → `CPQMessage(2)`；通过则 `summonR()/summonB()` 计数，`LifeFactory.getMonster` 后放置到 `map.getRandomSP(team)`，`addMonsterSpawn` + `addAllMonsterSpawn`；`neededCP = mobs.get(num).right`。
3. **tab 1 debuff**：`map.getSkillIds()` 取本图可用技能，`CarnivalFactory.getSkill` 得 `MCSkill`；CP 校验失败 → `CPQMessage(1)`；`skill.targetsAll()` 时对敌方全员掷 `rollHitChance`（DARKNESS/WEAKNESS/POISON/SLOW 随机 0-99，其他 0 恒命中），≤80 才生效：`dis == null → dispel()` 否则 `giveDebuff(dis, skill.getSkill())`；单目标则从敌方随机挑一个在线且在 CPQ 图的角色施放；`neededCP = skill.cpLoss()`。
4. **tab 2 守护**：`CarnivalFactory.getGuardian(num)`；CP 校验；`canGuardianR/B` 配额 → `CPQMessage(2)`；`map.spawnGuardian(team, num)` 返回值 -1 → `CPQMessage(3)`（无空位）、0 → `CPQMessage(4)`（其他）、1 成功且 `neededCP = skill.cpLoss()`。
5. 收尾统一：`gainCP(-neededCP)` + 全图广播 `playerSummoned(名字, tab, num)`。

**私有方法 `rollHitChance(MobSkillType)`**：switch 表达式，四类 debuff 随机、其余恒 0。

**核心协作类**：`MonsterCarnival`、`CarnivalFactory.MCSkill`、`LifeFactory`、`Party.getEnemy`、`Character.giveDebuff/dispel/gainCP`、`MapleMap`（getMobsToSpawn/getSkillIds/getRandomSP/addMonsterSpawn/spawnGuardian）。

### 3.8 MakerSkillHandler（MAKER_SKILL，制造技能）

**概述**：极薄委托层：读包后直接 `MakerProcessor.makerAction(p, c)`（宝石镶嵌/装备制造/分解等全部业务在 `client.processor.action.MakerProcessor`，属于 processor 层设计）。

---

## 4. 移动同步（5 类 + 基类摘要）

> 五个 Move\*Handler 均继承 `AbstractMovementPacketHandler`（**08 篇**），其两个 protected 方法是本组流程的公共骨架：
>
> | 继承方法 | 作用 |
> |---|---|
> | `parseMovement(InPacket) → List<LifeMovementFragment>` | 解析完整移动指令流（0/5/17 绝对移动、1/2/6/12/13/16/18/19/20/22 相对移动、3/4/7/8/9/11 瞬移类、10 换装、15 跳下、14/21 跳过），空指令抛 `EmptyMovementException` |
> | `updatePosition(InPacket, AnimatedMapObject, yOffset)` | 边读边同步服务端坐标与 stance；瞬移类对 `Character` 额外 `markTeleportLikeMove(前,后)`，常规移动 `markRegularMove`，相对位移用 `estimateRelativeMovePosition` 推进（避免服务端长期持旧坐标导致攻击距离误判）——BeiDou 反作弊增强 |
>
> 共同套路：先记 `movementDataStart = p.getPosition()`，`updatePosition` 消费移动段后求 `movementDataLength` 并 `p.seek(movementDataStart)` 回卷，用原始字节构造广播包（保证广播字节流与客户端一致）。

### 4.1 MovePlayerHandler（MOVE_PLAYER）

1. `p.skip(9)`（时间戳+起始坐标）。
2. `updatePosition(p, player, 0)` 同步坐标（瞬移/突进/跳下均有轨迹记录）；回卷字节流。
3. `map.movePlayer(player, player.getPosition())` 更新地图格子索引。
4. 隐身 GM → `broadcastGMMessage(movePlayer)`，否则 `broadcastMessage(movePlayer)`（均不同步回发送者）。

### 4.2 MoveLifeHandler（MOVE_LIFE，怪物移动）

**概述**：怪物控制端（controller 客户端）上报的怪物移动与技能/攻击意图，是怪物 AI 的服务端入口（约 180 行）。

**handlePacket 关键流程**：

1. 玩家 `isChangingMaps()` 时直接丢弃（防切图期间 OID 错乱把别的图的怪移走）。
2. 读 `objectid`/`moveid`；地图对象必须为 MONSTER。
3. 读控制头：`pNibbles`、`rawActivity`、`skillId`/`skillLv`（byte 转 unsigned）、`pOption`，skip 8；`rawActivity ≥ 0` 时右移掩码修正。
4. `isAttack = rawActivity ∈ [24,41]`、`isSkill = rawActivity ∈ [42,59]`（`inRangeInclusive` 实现 `!(v<min)||(v>max)` 实际等价于 `v ≥ min`，遗留疑似笔误，本篇如实记录）。
5. **技能分支**：怪物拥有该技能（`monster.hasSkill`）→ `MobSkillFactory.getMobSkillOrThrow`；`canUseSkill` 通过且动画时间 > 0 且非 BANISH → `applyDelayedEffect`（延迟生效）；否则立即 `applyEffect`（BANISH 收集待放逐玩家到 `banishPlayers`）。
6. **攻击分支**：`castPos = (rawActivity-24)/2`，`canUseAttack < 1` 时复位 `rawActivity=-1, pOption=0`。
7. **预取下一技能**：`nextMovementCouldBeSkill = !(isSkill || pNibbles != 0)`；怪物有任何技能时随机抽一个，校验 `canUseSkill`、HP 百分比 ≥ 技能 HP 门槛、MP 够扣，不满足则清零（防止怪放不该放的技能）。
8. 读剩余头与起始坐标（`startPos` 取 y-2），记 `serverStartPos`；`aggro = monster.aggroMoveLifeUpdate(player)`——null 表示控制权已丢失，直接 return；据此发 `moveMonsterResponse(objectid, moveid, mobMp, aggro[, nextSkillId, nextSkillLevel])` 给控制端。
9. `updatePosition(p, monster, -2)`（y 偏移 -2，修复海绵类 Boss 移出场地）；回卷；`use_debug_show_life_move` 开启时打印调试日志；以 `serverStartPos` 为参考广播 `moveMonster`（含 allowSkill/act/skill 等字段）；`map.moveMonster` 更新索引。
10. 对 `banishPlayers` 逐个 `changeMapBanish`。

**核心协作类**：`Monster`（aggroMoveLifeUpdate/canUseSkill/canUseAttack/getRandomSkill/hasSkill）、`MobSkillFactory`/`MobSkill`、`MonsterInformationProvider.getMobSkillAnimationTime`、`MapleMap`、`GameConfig`。

### 4.3 MovePetHandler（MOVE_PET）

1. 读 `petId` int、skip long；`parseMovement(p)` 解析完整移动列表（空指令直接 return）。
2. `player.getPetIndex(petId) == -1`（没带这只宠）→ return。
3. `pet.updatePosition(res)` 同步宠物坐标；广播 `movePet(playerId, petId, slot, res)`。

### 4.4 MoveDragonHandler（MOVE_DRAGON）

1. 读起始坐标；`chr.getDragon()`（龙族职业的龙）非空才处理。
2. `updatePosition(p, dragon, 0)` + 回卷字节流。
3. 隐身 → `broadcastGMPacket(moveDragon)`，否则按 `dragon.getPosition()` 定向广播 `moveDragon`。

### 4.5 MoveSummonHandler（MOVE_SUMMON）

1. 读召唤兽 `oid` 与起始坐标；在 `player.getSummonsValues()` 里按 oid 线性查找（召唤兽可移动的场景，如机械师炮台外的部分召唤）。
2. 命中后 `updatePosition(p, summon, 0)` + 回卷；按 `summon.getPosition()` 定向广播 `moveSummon`。

---

## 5. NPC 与任务（5 类）

### 5.1 NPCTalkHandler（NPC_TALK，点击 NPC）

**概述**：玩家点击 NPC 的入口包，决定走脚本、特殊脚本还是 WZ 商店。含监狱封禁、死亡拦截、NPC 连点节流（BeiDou/HeavenMS 增量）。

**handlePacket 关键流程**：

1. **监狱地图**（`MapId.JAIL`）→ 提示（I18n）+ `enableActions`；死亡状态仅 `enableActions`。
2. **连点保护**：`now - getNpcCooldown() < GameConfig("block_npc_race_condition")` → `enableActions`（防脚本高频触发 NPC 竞态）。
3. 读 `oid` → `map.getMapObject(oid)`：
   - **普通 NPC**：`use_debug` 且 GM → 调试消息（I18n + npc id）。
     - Duey 快递 NPC（`NpcId.DUEY`）→ `DueyProcessor.dueySendTalk(c, false)` 专属 UI。
     - 已有对话挂起（`c.getCM() != null || c.getQM() != null`）→ `enableActions`（防重入）。
     - **特殊脚本路由**（减少脚本量的定制）：GACHAPON id 区间 → `"gachapon"` 脚本；NPC 名以 "Maple TV" 结尾 → `"mapleTV"`；`use_rebirth_system` 且 id == `rebirth_npc_id` → `"rebirth"`；否则 `NPCScriptManager.start(c, id, oid, null)` 返回是否有脚本。
     - 无脚本：NPC 无商店 → warn「NPC not coded」+ `enableActions`；玩家已在某商店 → `enableActions`；否则 `npc.sendShop(c)` 打开 WZ 商店。
   - **PlayerNPC**（玩家排名 NPC）：scriptId < `NpcId.CUSTOM_DEV` 且无专属脚本 → `"rank_user"` 脚本，否则按 scriptId 启动。

**核心协作类**：`NPCScriptManager`、`QuestScriptManager`（经 CM/QM 间接）、`DueyProcessor`、`NPC`/`PlayerNPC`、`GameConfig`、`MapId`/`NpcId`、`I18nUtil`。

### 5.2 NPCMoreTalkHandler（NPC_TALK_MORE，对话后续交互）

**概述**：NPC 对话框的"下一步"包：选择菜单项、输入文本、结束对话。需区分 NPC 脚本（CM）与任务脚本（QM）两条通道，并支持 BeiDou 的多级对话上下文。

**handlePacket 关键流程**：

1. 读 `lastMsg`（上一步消息类型）与 `action`（0=结束，1=继续）。
2. `lastMsg == 2`（文本输入框）：
   - `action != 0` → 读 `returnText`；QM 通道：`qm.setGetText` 后按 `isStart` 走 `QuestScriptManager.start/end`；CM 通道：`cm.setGetText` + `cmRouting`。
   - `action == 0` → `qm.dispose()` 或 `cm.dispose()` 结束对话。
3. 其他类型：`selection` 依剩余字节读 int（≥4 字节）或 `readUnsignedByte`；QM → `QuestScriptManager.start/end(c, action, lastMsg, selection)`；CM → `cmRouting`。
4. **私有 `cmRouting(c, action, lastMsg, selection)`**：`cm.getNextLevelContext().getLevelType() == null` → 常规 `NPCScriptManager.action(...)`；否则 `NPCScriptManager.nextLevel(...)`（BeiDou 多级对话/引导上下文续跳）。

**核心协作类**：`NPCScriptManager`、`QuestScriptManager`、`Client.getCM()/getQM()`、NPCConversationManager 的 `getNextLevelContext`。

### 5.3 NPCShopHandler（NPC_SHOP，NPC 商店交易）

**handlePacket 关键流程**（bmode 分发）：

- `0 买入`：读 slot/itemId/quantity；`quantity < 1` → `AutobanFactory.PACKET_EDIT.alert` + warn + `disconnect(true, false)`；否则 `player.getShop().buy(c, slot, itemId, quantity)`。
- `1 卖出`：读 slot/itemId/quantity → `shop.sell(c, ItemConstants.getInventoryType(itemId), slot, quantity)`。
- `2 充值（回购飞镖/子弹）`：读 slot byte → `shop.recharge(c, slot)`。
- `3 离开`：`player.setShop(null)`。

**核心协作类**：`Character.getShop()`（`server.Shop`）、`ItemConstants`、`AutobanFactory`。

### 5.4 NPCAnimationHandler（NPC_ACTION，NPC 动画回显）

**概述**：把客户端产生的 NPC 表情/移动动画回显（本包处理完发回给**发送者自己**，用于动画确认）。`isChangingMaps()` 时丢弃（修复切图 error 38）。包长 == 6（表情：int oid + 2 byte）→ 原样转发；> 6（移动）→ 转发 `length-9` 字节原始数据；用 `OutPacket.create(SendOpcode.NPC_ACTION)` 构造。

### 5.5 QuestActionHandler（QUEST_ACTION，任务操作）

**概述**：任务系统入口：开始/完成/放弃/脚本化开始/脚本化完成/找回丢失道具。带 NPC 就近校验（防远程交任务）与监狱封禁。

**常量**：`LOST_WHITE_ESSENCE_QUEST = 4522`、`CAPTAIN_LATANICA_RETURN_QUEST = 4523`、`WHITE_ESSENCE = 4000381`（白色精华，武陵桃花岛特殊引导）。

**handlePacket 关键流程**：

1. 读 `action`、`questid`；`Quest.getInstance(questid)`；监狱地图 → I18n 提示并 return。
2. **action 0 找回丢失物品**：读 2 个 int（第二为 itemid）→ `quest.restoreLostItem(player, itemid)`。
3. **action 1 开始任务**：读 npc；`isNpcNearby` 校验；`quest.canStart` 通过 → 有脚本需求（`hasScriptRequirement(false)`）且 `QuestScriptManager.checkFunctionExists(c, questid, npc, "start")` → 脚本 `start`，否则 `quest.start(player, npc)`；不满足时对 4522/4523 且身上有白色精华的两个特例发 NPC 对话提示（`sendNpcOk`）。
4. **action 2 完成任务**：同样就近校验；`canComplete` → 脚本路径或 `quest.complete(player, npc[, selection])`（剩余 ≥2 字节时读选择项）。
5. **action 3 放弃**：`quest.forfeit(player)`。
6. **action 4 / 5 脚本化开始/完成**：就近校验 + canStart/canComplete 后强制走 `QuestScriptManager.start/end`。

**私有方法**：

- `isNpcNearby(p, player, quest, npcId)`：包内自带玩家坐标（剩余 ≥4 字节）且与服务器坐标偏差 ≤1000 才采信，否则用服务器坐标；非自动接/交（`!isAutoStart && !isAutoComplete`）时要求 NPC 在图且 `|dx| ≤ 1200、|dy| ≤ 800`，否则提示（I18n）。
- `sendNpcOk(c, npc, message)`：`getNPCTalk` 打一条 NPC 说话。

**核心协作类**：`Quest`（canStart/start/canComplete/complete/forfeit/restoreLostItem/hasScriptRequirement）、`QuestScriptManager`、`NPC`、`MapId.JAIL`、`I18nUtil`。

---

## 6. 聊天与社交（4 类）

### 6.1 MultiChatHandler（MULTI_CHAT，四通道群聊）

**概述**：好友（type 0）/组队（1）/公会（2）/联盟（3）群聊。含 200ms 节流、收件人数量与包长一致性校验、文本长度反作弊与聊天审计。

**handlePacket 关键流程**：

1. `lastSpam(7)+200 > now` → 丢弃。
2. 读 `type`、`numRecipients`（**按无符号 byte 读**，源码注释：超 255 人需客户端同步改造）；`numRecipients > available/4`（数量与剩余字节不匹配，防改包）→ 丢弃。
3. 读 `recipients[]` 与 `chattext`；`text.length() > 127` 且非 GM → `PACKET_EDIT.alert` + warn + `disconnect(true, false)`。
4. 分发：type 0 → `world.buddyChat(recipients, ...)`（未校验收件人是否真为好友，信任 World 层）；type 1 且有队 → `world.partyChat`；type 2 且有公会 → `Server.getInstance().guildChat`；type 3 且公会有联盟 → `Server.allianceMessage(multiChat(name, text, 3), ...)`。
5. 每条均 `ChatLogger.log(c, 通道名, text)` 落审计日志；`spam(7)`。

**核心协作类**：`World`（buddyChat/partyChat）、`Server`（guildChat/allianceMessage）、`ChatLogger`、`AutobanFactory`、`PacketCreator.multiChat`。

### 6.2 MessengerHandler（MESSENGER，Messenger 群聊房间）

**概述**：最多 3 人的 Messenger 聊天房间：创建、加入（邀请应答）、邀请、退出、拒绝、发言。全程 `tryacquireClient` 包裹；跨频道邀请经 `World` 转发；邀请去重经 `InviteCoordinator`。

**handlePacket 关键流程**（mode 分发）：

- `0x00 进入/创建`：读 messengerid。当前不在房间：id==0 → 清残留邀请 + `world.createMessenger`（自己占 0 号位）；id!=0 → `world.getMessenger` 存在时 `InviteCoordinator.answerInvite(MESSENGER, cid, messengerid, true)`，ACCEPTED 且人数 <3 → 加入最低空位并 `world.joinMessenger`；邀请已失效则提示。当前已在房间 → `answerInvite(..., false)` 视为拒绝。
- `0x02 退出`：`player.closePlayerMessenger()`。
- `0x03 邀请`：房间人数 <3 才可邀；同频道目标 `PlayerStorage.getCharacterByName`：目标无 Messenger → `InviteCoordinator.createInvite` 成功则向目标发 `messengerInvite`、给自己回 `messengerNote(4,1)`，目标已有待处理邀请则聊天条提示；目标在其他频道（`world.find > -1`）→ `world.messengerInvite` 跨频道转投；找不到 → `messengerNote(4,0)`。
- `0x05 拒绝`：读目标名 → `world.declineChat(targeted, player)`。
- `0x06 发言`：组 `MessengerCharacter`（携带自己的房间位）→ `world.messengerChat(messenger, input, name)`。

**核心协作类**：`World`（createMessenger/getMessenger/joinMessenger/messengerInvite/messengerChat/declineChat/find）、`InviteCoordinator`（MESSENGER 类型邀请生命周期）、`Messenger`/`MessengerCharacter`、`PlayerStorage`。

### 6.3 NewYearCardHandler（NEW_YEAR_CARD_REQUEST，新年贺卡）

**概述**：新年贺卡的寄送（mode 0）与领取（其他 mode），卡片记录持久化在 `NewYearCardRecord`，寄出后启动提醒任务。

**handlePacket 关键流程**：

- **mode 0 寄送**：
  1. 必须持有 `ItemId.NEW_YEARS_CARD`（否则 `onNewYearCardRes(player, -1, 5, 0x11)`）。
  2. 读 slot/itemid；`getValidNewYearCardStatus`（私有）：非新年卡类道具 → 0x14；槽位物品不匹配 → 0x12；均以 `onNewYearCardRes(..., 5, status)` 报错。
  3. 背包能放下 `NEW_YEARS_CARD_SEND`（否则 0x10 背包满）。
  4. 读收件人名 → `getReceiverId`（私有，JDBC `characters WHERE name LIKE`，且须同世界）；找不到 → 0x13；寄给自己 → 0xF。
  5. 读留言；`new NewYearCardRecord(...) → saveNewYearCard → player.addNewYearRecord`；道具替换 `-NEW_YEARS_CARD +NEW_YEARS_CARD_SEND`；`Server.setNewYearCard` 注册 + `startNewYearCardTask()` 启动到期提醒；回 `onNewYearCardRes(player, newyear, 4, 0)`。
- **领取**：读 cardid → `loadNewYearCard`；须自己是收件人且未领取、寄方未销卡；背包能放下 `NEW_YEARS_CARD_RECEIVED`；`stopNewYearCardTask + updateNewYearCard`；发收到道具 + 非空留言以 `[New Year]` 前缀 `dropMessage(6)`；回成功码 6；向全图与在线寄方广播 `onNewYearCardRes(..., 0xD, 0)` 并提示寄方。寄方已销卡/记录不存在 → 提示无可领。

**核心协作类**：`NewYearCardRecord`（save/load/update/任务）、`AbstractPlayerInteraction.gainItem`、`DatabaseConnection`、`Server.setNewYearCard`、`PlayerStorage`。

### 6.4 NoteActionHandler（NOTE_ACTION，纸条操作）

**概述**：纸条（送礼附言/好友申请留言）的两个动作：商城内回赠留言、游戏内丢弃纸条并结算附带的 fame（人气）。**Spring 注入示例**：构造器接收 `NoteService`（`PacketProcessor` 传 `channelDeps.noteService()`），是 handler 体系与 Spring 服务层的桥接样例。

**handlePacket 关键流程**：

- `action 0`（商城送礼回言）：要求 `cashShop.getAvailableNotes() > 0`；读收件人角色名 + 留言；商城打开则先回 `showCashInventory`；`noteService.sendWithFame(message, 发件人, 收件人)` 成功后 `decreaseNotes()` 扣减可用纸条数；异常仅记 error 日志。
- `action 1`（丢弃纸条）：读数量 num（后跟 2 个 byte 弃读）；逐条读纸条 id + fame byte（fame 不信任客户端，以库里为准，源码注释 ":)"）；`noteService.delete(id)` 返回 `Optional<NotesDO>`，已删过的 warn 后 continue；累计 `NotesDO.getFame()`，>0 时 `player.gainFame(fame)` 一次性入账。

**核心协作类**：`NoteService`（Spring）、`CashShop.getAvailableNotes/decreaseNotes`、`NotesDO`、`Character.gainFame`。

---

## 7. 组队（4 类）

### 7.1 PartyOperationHandler（PARTY_OPERATION，组队操作）

**handlePacket 关键流程**（operation 分发，1–6）：

1. `1 创建`：`Party.createParty(player, false)`（静态入口，World 侧建队）。
2. `2 离开/解散`：先快照在线队友；`Party.leaveParty(party, c)`；`updatePartySearchAvailability(true)`（恢复可被搜索）；`partyOperationUpdate` 通知（脚本回调）。
3. `3 加入`：读 partyid；`InviteCoordinator.answerInvite(PARTY, cid, partyid, true)` ACCEPTED → `Party.joinParty(player, partyid, false)`；否则"邀请已过期"提示。
4. `4 邀请`：读名字 → `world.getPlayerStorage().getCharacterByName`：
   - 等级门槛：目标 <10 级且（未开 `use_party_for_starters` 或自己 ≥10）→ 提示不符；开了新手队且目标 ≥10 而自己 <10 → 同样拒绝。
   - 目标无队：自己无队则先 `createParty`；人数 <6 时 `InviteCoordinator.createInvite(PARTY, ...)` 成功 → 向目标发 `partyInvite`；目标已有待答邀请 → `partyStatusMessage(22)`；队满 → 17；目标已有队 → 16；目标不在线本世界 → 19。
5. `5 踢人`：读 cid → `Party.expelFromParty(party, c, cid)`。
6. `6 转让队长`：读新队长 cid → `world.updateParty(partyId, PartyOperation.CHANGE_LEADER, newLeader)`。

**核心协作类**：`Party`（静态 createParty/joinParty/leaveParty/expelFromParty）、`InviteCoordinator`、`World.updateParty`、`PartyCharacter`、`GameConfig`。

### 7.2 PartySearchStartHandler（PARTY_SEARCH_START，开始组队搜索）

1. 读等级下限/上限 min、max；`min > max` → 提示；`max - min > 30` → 提示（搜索范围最多 30 级）；自己等级不在区间 → 提示。
2. 弃读 members int，读 jobs int（职业过滤位图）。
3. 必须已有队伍且是队长，否则静默返回。
4. `world.getPartySearchCoordinator().registerPartyLeader(chr, min, max, jobs)` 注册为"正在招人的队长"。

### 7.3 PartySearchRegisterHandler（PARTY_SEARCH_REGISTER，空实现）

**概述**：`handlePacket` 为**空方法**（占位，保留 opcode 注册）。组队搜索的注册实际由 `PartySearchStartHandler` 完成。

### 7.4 PartySearchUpdateHandler（PARTY_SEARCH_UPDATE，注销组队搜索）

**概述**：类注释「脚本、GM 指令传送玩家到指定地图触发」——作为传送等操作的伴生信号，把玩家从组队搜索登记中移除：`world.getPartySearchCoordinator().unregisterPartyLeader(player)`。

---

## 8. 宠物（6 类）

### 8.1 PetChatHandler（PET_CHAT，宠物聊天）

1. 读 petId（弃 1 int、1 byte）、动作 `act`；`petIndex = getPetIndex(petId)` 须 0–3、`act` 须 0–9。
2. 读 text；`length > 127` → `PACKET_EDIT.alert` + warn + `disconnect(true, false)`。
3. 全图广播 `petChat(playerId, petIndex, act, text, hasPetChatballoon(petIndex))`（含聊天气泡道具标记）；`ChatLogger.log(c, "Pet", text)`。

### 8.2 PetCommandHandler（PET_COMMAND，宠物指令）

1. 读 petId → petIndex（-1 返回）；弃 1 int、1 byte 后读指令 `command`。
2. `PetDataFactory.getPetCommand(pet.getItemId(), command)` 取指令配置（无则返回）。
3. `Randomizer.nextInt(100) < petCommand.getProbability()` 命中 → `pet.gainTamenessFullness(chr, getIncrease(), 0, command)` 加亲密度，广播 `commandResponse(..., false=成功, ...)`；未命中仅广播失败动画。

**核心协作类**：`PetDataFactory`/`PetCommand`、`Pet.gainTamenessFullness`、`Randomizer`。

### 8.3 PetFoodHandler（PET_FOOD，喂食宠物食品）

1. `lastSpam(2)+500` 节流（过快 `enableActions`）+ `spam(2)`；弃时间戳 int；`setTimestamp(1, ts, 3)` 审计。
2. 无宠物（`getNoPets()==0`）→ `enableActions`。
3. 在 3 个宠物槽中找 `fullness` 最低（最饿）的那只。
4. 读槽位 pos 与 itemId；`tryacquireClient` + USE 栏加锁：校验道具存在、itemId 属 212 段（宠物食品）、槽位匹配、数量 ≥1。
5. `pet.gainTamenessFullness(chr, fullness ≤ 75 ? 1 : 0, 30, 1)`——饱食度 +30，饥饿状态（≤75）额外 +1 亲密度；`removeFromSlot` 消耗 1 个。

### 8.4 PetLootHandler（PET_LOOT，宠物拾取）

**概述**：宠物自动拾取掉落物。前置：宠物配了对应饰品（金币袋/物品袋）且不在过滤清单内；核心是 BeiDou 强化的**宠物吸物两级距离反作弊** `checkPetPickupDistance`。

**handlePacket 关键流程**：

1. `petIndex = getPetIndex(petId)`；宠物存在且 `isSummoned`，否则 `enableActions`。
2. skip 13 后读 oid → `getMapObject`；强转 `MapItem`（NPE/CCE 捕获后 `enableActions`）。
3. 金币类（`mapitem.getMeso() > 0`）：须装备 MesoMagnet（`isEquippedMesoMagnet`）；若装有 Item Ignore 且过滤集含 `Integer.MAX_VALUE`（=过滤一切）→ 拒捡。
4. 物品类：须装备 ItemPouch；过滤集含该 itemId → 拒捡。
5. **`checkPetPickupDistance(chr, pet, ob)`（私有）**：
   - 玩家本人在物品 800/600 范围内 → 合法（覆盖宠物滞后、多平台场景）。
   - 玩家最近用过内传送门且物品在**传送前坐标** 800/600 内 → 放行（配合 `InnerPortalHandler.setPetLootTeleportBeforePos`）。
   - 宠物坐标为 (0,0)（刚召唤未初始化）→ 跳过检测。
   - `diffX > 800 || diffY > 600` → `PET_ITEM_VAC.addPoint`（全图真空吸物），拦截。
   - `diffX > 400 || diffY > 400` → `PET_SHORT_ITEM_VAC.addPoint`（短距真空），拦截。
   - 通过后 `chr.pickupItem(ob, petIndex)`（带宠物槽位的拾取入口，区分主人/宠物拾取表现）。

**核心协作类**：`Pet`、`MapItem`、`Character.isEquippedMesoMagnet/isEquippedItemPouch/isEquippedPetItemIgnore/getExcludedItems/pickupItem`、`AutobanFactory.PET_ITEM_VAC/PET_SHORT_ITEM_VAC`。

### 8.5 PetAutoPotHandler（PET_AUTO_POT，宠物自动喝药）

**概述**：薄解析层：弃 1 byte、1 long、1 int，读槽位 short 与 itemId，委托 `PetAutopotProcessor.runAutopotAction(c, slot, itemId)`（自动药水校验与消耗在 processor 层）。

### 8.6 PetExcludeItemsHandler（PET_EXCLUDE_ITEMS，宠物过滤清单)

**概述**：客户端提交宠物拾取过滤列表（Item Ignore 面板）。客户端总是提交**完整列表**，服务端按差异增量写库（BeiDou 注释明确该约定）。

**handlePacket 关键流程**：

1. 读 petId、弃 4 字节时间戳；`petIndex` 有效且宠物非空。
2. 读数量 amount 与 itemId 列表；出现**负 itemId** → `PACKET_EDIT.alert` 并整体拒绝。
3. 三步提交：`loadPetExcludedItems(petId)`（确保旧集合已加载）→ `updatePetExcludedItems(petId, 新集合)`（内存差量）→ `commitExcludedItems()`（统一落库；该方法同时被 `PlayerLoggedinHandler` 登录时调用）。

---

## 9. 家族（2 类）

### 9.1 OpenFamilyHandler（OPEN_FAMILY）

`use_family_system` 关闭则忽略；否则 `c.sendPacket(PacketCreator.getFamilyInfo(chr.getFamilyEntry()))`（可能为 null 表示无家族）。

### 9.2 OpenFamilyPedigreeHandler（OPEN_FAMILY_PEDIGREE）

同配置门；读目标角色名 → `PlayerStorage.getCharacterByName`；目标存在且有家族 → `showPedigree(target.getFamilyEntry())` 展示对方谱系。

---

## 10. 交易 / 玩家商店 / 雇佣商人 / MTS（4 类）

### 10.1 HiredMerchantRequest（HIRED_MERCHANT_REQUEST，请求开雇佣商人）

**概述**：玩家在 FM 使用雇佣商人券前的**前置校验与开箱**：位置合法性、旧店清理、Fredrick 遗留物品检测。

**handlePacket 关键流程**：

1. **摆位校验**（异常仅 warn 不阻断）：扫描自身 23000 范围内的 `HIRED_MERCHANT` 与 `PLAYER` 对象——附近玩家开着店主 → `getMiniRoomError(13)`；存在雇佣商人实体 → 同 13；距最近传送门 <120px → `getMiniRoomError(10)`。
2. 必须在自由市场房间（`GameConstants.isFreeMarketRoom`），否则 `dropMessage(1)`。
3. **世界侧旧店处理** `world.getHiredMerchant(chr.getId())`：
   - 已发布（开着）→ 提示"某频道已有店"。
   - 未发布（上次没开成的残留实例）→ BeiDou 修复的清理链：`chr.setHiredMerchant(existing)` + `existing.forceClose()`；失败提示联系管理员；随后查 `ItemFactory.MERCHANT.loadItems(chr.getId(), false)` 与 `merchantMeso`：有遗留 → 提示去 Fredrick 取 + `retrieveFirstMessage`；干净 → 提示重新摆店 + `enableActions`。
4. `chr.hasMerchant()` 状态位残留 → 提示联系管理员。
5. 最终：无遗留物品与金币 → 发 `hiredMerchantBox()` 打开摆店界面；否则发 `retrieveFirstMessage()` 引导去 Fredrick。

**核心协作类**：`World.getHiredMerchant`、`HiredMerchant.forceClose/isPublished`、`ItemFactory.MERCHANT.loadItems`、`GameConstants.isFreeMarketRoom`、`MapleMap.findClosestTeleportPortal/getMapObjectsInRange`。

### 10.2 PlayerInteractionHandler（PLAYER_INTERACTION，小房间全能处理器）

**概述**：本篇最大类（约 940 行）。统一处理"小房间（MiniRoom）"全部子协议：**交易、奥妙棋/翻牌小游戏、玩家商店、雇佣商人**。以 `mode` 字节分发到 40+ 个 `Action`。全程 `tryacquireClient`（注释：防 GabrielSin 发现的交易复制漏洞竞态），`finally` 释放。

**嵌套枚举 `Action`（公有）**：`getCode()` 返回协议码。关键码：CREATE(0)、INVITE(2)、DECLINE(3)、VISIT(4)、ROOM(5)、CHAT(6)、CHAT_THING(8)、EXIT(0xA)、OPEN_STORE(0xB)、OPEN_CASH(0xE)、SET_ITEMS(0xF)、SET_MESO(0x10)、CONFIRM(0x11)、TRANSACTION(0x14)、ADD_ITEM(0x16)、BUY(0x17)、UPDATE_MERCHANT(0x19)、UPDATE_PLAYERSHOP(0x1A)、REMOVE_ITEM(0x1B)、BAN_PLAYER(0x1C)、MERCHANT_THING(0x1D)、OPEN_THING(0x1E)、PUT_ITEM(0x21)、MERCHANT_BUY(0x22)、TAKE_ITEM_BACK(0x26)、MAINTENANCE_OFF(0x27)、MERCHANT_ORGANIZE(0x28)、CLOSE_MERCHANT(0x29)、REAL_CLOSE_MERCHANT(0x2A)、MERCHANT_MESO(0x2B)、SOMETHING(0x2D)、VIEW_VISITORS(0x2E)、VIEW_BLACKLIST(0x2F)、ADD_TO_BLACKLIST(0x30)、REMOVE_FROM_BLACKLIST(0x31)、REQUEST_TIE(0x32)、ANSWER_TIE(0x33)、GIVE_UP(0x34)、EXIT_AFTER_GAME(0x38)、CANCEL_EXIT_AFTER_GAME(0x39)、READY(0x3A)、UN_READY(0x3B)、EXPEL(0x3C)、START(0x3D)、GET_RESULT(0x3E)、SKIP(0x3F)、MOVE_OMOK(0x40)、SELECT_CARD(0x44)。

**私有静态方法表**：

| 方法 | 作用 |
|---|---|
| `establishMiniroomStatus(chr, isMinigame) → int` | 开房前置状态码：地图 `FieldLimit.CANNOTMINIGAME` → 11；有小黑板 → 13；在活动副本中 → 5；0 为可开 |
| `isTradeOpen(chr) → boolean` | 交易未关闭时返回 true 并 `enableActions`——防"交易开着时存/取库"类复制漏洞（Rien 团队） |
| `canPlaceStore(chr) → boolean` | 摆店位置校验（同 10.1 的 23000 范围/120px 传送门规则，忽略自己） |

**handlePacket 关键流程**（按 Action 归组）：

- **CREATE**：存活检查（error 4）。子类型 3=交易 → `Trade.startTrade(chr)`；1=奥妙棋：状态检查 → 读描述/密码/棋子类型（0–11 钳位）且持有 `MINI_GAME_BASE+type` 道具（否则 error 6）→ 建 `MiniGame(OMOK)` 入图广播 `addOmokBox`；2=翻牌：类型 0/1/2 → 目标分 6/10/15，需持 `MATCH_CARDS`；4/5=商店：仅 FM 房（error 15）+ 状态 + 摆位；读描述与商店道具 itemId，须持有（CASH 栏 `countById ≥1`）；`isPlayerShop` → 建 `PlayerShop` + `registerPlayerShop`；`isHiredMerchant` → 防重（已有 merchant 或世界注册存在则拒绝并 warn），`channel.addHiredMerchant` + `world.registerHiredMerchant` 双注册，失败回滚 channel 侧（BeiDou 修复的重复实例/竞态防护）。
- **INVITE / DECLINE / VISIT**：交易邀请 `Trade.inviteTrade`；拒绝 `Trade.declineTrade`；VISIT：交易场景双方未满员 → `Trade.visitTrade`；否则按图对象类型：PlayerShop → `shop.visitShop(chr)`；MiniGame → 密码校验（error 22）+ 空位/非重复进入（error 2）后 `addVisitor` 并按类型 `sendOmok/sendMatchCard`；HiredMerchant（且自己未开店）→ `merchant.visitShop(chr)`。
- **CHAT / EXIT**：按当前场景路由聊天（trade.chat / shop.chat / game.chat / merchant.sendMessage）；EXIT：交易中 → `Trade.cancelTrade(PARTNER_CANCEL)`，否则统一 `closePlayerShop + closeMiniGame + closeHiredMerchant(true)`。
- **OPEN_STORE / OPEN_CASH**：后者先做生日校验（`CashOperationHandler.checkBirthday`，10 篇类；失败 I18n 提示）并回 `hiredMerchantOwnerMaintenanceLeave`；`canPlaceStore` 再验；玩家商店：可配置 `use_erase_permit_on_open_shop` 收许可证道具 → `updatePlayerShopBox` + `setOpen(true)`；雇佣商人：`setOpen(true)` 失败（被 ban）→ `closeForBan` + error 18；成功 → `setHasMerchant(true)`、入图、广播 `spawnHiredMerchantBox`、`setHiredMerchant(null)`（店主离店，商人独立运营）。
- **小游戏组**：READY/UN_READY 广播就绪态；START（omok/matchcard 分别 `minigameMatchStarted`、翻牌 `shuffleList`，广播开始与盒子状态）；GIVE_UP 按房主/访客判负；REQUEST_TIE/ANSWER_TIE（拒绝记 `denyTie`）；SKIP 广播跳过；MOVE_OMOK → `game.setPiece(x, y, piece, chr)`（内含合法性判定）；SELECT_CARD：第一张记录 `setFirstSlot`，第二张按配对与身份广播 `getMatchCardSelect` 并 `setOwnerPoints/setVisitorPoints`；EXPEL 房主踢访客；EXIT_AFTER_GAME/CANCEL → `setQuitAfterGame`。
- **交易组**：SET_ITEMS（0xF）：目标格 1–9（越界 warn + I18n）；物品存在；`trade_limit_item_cash` 关闭时拒绝不可交易类（宠物单独文案，`ii.isUnmerchable`）；数量 1..持有量；`trade_limit_item_nodrop` 关闭时不可丢物须有 Karma 剪刀标记（`KarmaManipulator.hasKarmaFlag`）；随后背包加锁二次核对槽位一致性（防位移 dupes）→ copy 设量设位 `trade.addItem` → `removeFromSlot` → 双方 `getTradeItemAdd`。SET_MESO → `trade.setMeso(readInt)`。CONFIRM → `Trade.completeTrade(chr)`。
- **商店/商人上架组 ADD_ITEM/PUT_ITEM（0x16/0x21）**：`isTradeOpen` 拒绝；道具存在且非 `isUntradeable/isUnmerchable`（宠物/现金物品分别文案）；可充值道具（飞镖/子弹）强制 `perBundle=bundles=1`；`bundles*perBundle ≤ 持有量`；价格/数量界检查（`perBundle*bundles > 2000`、`price ≤ 0`、超 int 上限）→ `PACKET_EDIT.alert`；组 `PlayerShopItem(sellItem, bundles, price)`：PlayerShop 分支——背包锁内复核槽位与数量、`shop.isOpen()` 或 `addItem` 失败（槽上限，防无限槽漏洞）拒绝、`removeFromSlot`、`getPlayerShopItemUpdate`；HiredMerchant 分支——CASH 物品在已发布商人上架直接拒绝（BeiDou 增量），同样锁内复核 + addItem + 扣除 + `updateHiredMerchant`，可配置 `use_enforce_merchant_save` 时 `saveCharToDB`，并 `merchant.saveItems(false)` 立即落库（防 Fredrick 复制）。
- **商店/商人管理组**：REMOVE_ITEM（玩家商店未营业时 `shop.takeItemBack`；slot 越界 → `PACKET_EDIT.alert + disconnect(true)`）；TAKE_ITEM_BACK（商人侧同上）；BUY/MERCHANT_BUY：`quantity < 1` → `PACKET_EDIT + disconnect(true)`；商店 `shop.buy(c, itemid, quantity)` 成功广播更新；商人 `merchant.buy` + `broadcastToVisitorsThreadsafe(updateHiredMerchant)`；MERCHANT_MESO → `merchant.withdrawMesos(chr)`；MERCHANT_ORGANIZE → 取钱 + `clearInexistentItems`，清空则 `closeOwnerMerchant`；VIEW_VISITORS/VIEW_BLACKLIST/ADD_TO_BLACKLIST/REMOVE_FROM_BLACKLIST → 商人访客记录与黑名单维护；CLOSE_MERCHANT → `closeOwnerMerchant(chr)`；MAINTENANCE_OFF：无货直接关店，否则 `clearMessages + setOpen(true)`（失败 `closeForBan`），最后 `setHiredMerchant(null) + enableActions`；BAN_PLAYER → `shop.banPlayer(name)`。

**核心协作类**：`Trade`（startTrade/inviteTrade/visitTrade/completeTrade/cancelTrade/declineTrade）、`PlayerShop`/`PlayerShopItem`、`HiredMerchant`、`MiniGame`、`InventoryManipulator`、`KarmaManipulator`、`CashOperationHandler.checkBirthday`（10 篇）、`FieldLimit`、`ItemInformationProvider`、`AutobanFactory`、`I18nUtil`（全部提示走 i18n）。

### 10.3 OwlWarpHandler（OWL_WARP，密诺娃之枭传送）

**概述**：用"密诺娃之枭"搜索到商品后点"参观商店"的传送包：跨地图/跨频道校验后把玩家传到店主地图并进入商店。

**handlePacket 关键流程**：

1. 读店主 `ownerid` 与 `mapid`；逛自己的店 → 提示拒绝。
2. **定位商店**：优先 `world.getHiredMerchant(ownerid)`，需地图匹配且商品仍含 `player.getOwlSearch()`（当前枭搜索物品）；不满足再试 `world.getPlayerShop(ownerid)`；两者都无 → `getOwlMessage(1)`（找不到），有店但不符 → `getOwlMessage(3)`（已下架）。
3. **PlayerShop 分支**：未营业 → `getOwlMessage(18)`；不在 FM 房 → serverNotice 提示当前位置；不在本频道 → serverNotice 提示所在频道；否则 `changeMap(mapid)`，因换图有延迟**二次确认 isOpen** 后 `visitShop(chr)`：失败时未进黑名单 → `getOwlMessage(2)`（满员），在黑名单 → 17。
4. **HiredMerchant 分支**：同构校验后 `changeMap` + 二次确认 + `hm.addVisitor(chr)` 成功 → `getHiredMerchant(...)` + `setHiredMerchant(hm)`；满员 → 2。（注：跨频道/非 FM 的提示分支里引用了 `hm.getChannel()`，PlayerShop 分支同样如此，为遗留写法，本篇如实记录。）

**核心协作类**：`World.getHiredMerchant/getPlayerShop`、`HiredMerchant`/`PlayerShop`（isOpen/addVisitor/visitShop/hasItem）、`Character.changeMap/getOwlSearch`、`GameConstants.isFreeMarketRoom`。

### 10.4 MTSHandler（MTS_OPERATION，MTS 交易市场）

**概述**：MTS（Maple Trading System，点券交易市场）全套协议（约 870 行），直接 JDBC 操作 `mts_items`/`mts_cart`/`accounts` 表（无 MyBatis 层）。入口门槛：`cashShop.isOpened()`。包体为空时仅回 `showMTSCash`。

**handlePacket 分支表（op byte）**：

| op | 业务 | 关键流程 |
|---|---|---|
| 2 | 上架出售 | 解析 itemtype/itemid/slot/quantity/price（装备与 207/233 投射物的包结构差异处理）；`quantity ≥ 0`、`price ≥ 110`；**先查挂单数**（`COUNT(mts_items WHERE seller)` >10 拒绝，避免扣物品后回滚，BeiDou 注释）；背包锁内校验道具与 5000 金币上架费 → `removeFromSlot` → INSERT `mts_items`（普通物品/装备全属性两套 SQL，`sell_ends = 今天+7 天`）；SQL 失败 `addFromDrop` 回滚；扣 5000 金币 → `MTSConfirmSell` + 刷新列表/转移栏/未售栏 |
| 3/4 | 出价/求购 | 空实现（4 仅消费包字段） |
| 5 | 翻页 | 读 tab/type/page → `changePage`；tab4+type0 → `getCart`；同 tab/type 且有搜索词 → `getMTSSearch`（否则清搜索词 `getMTS`）；`changeTab/changeType` + 刷新 |
| 6 | 搜索 | 读 tab/type/ci/关键词 → 保存搜索状态（setSearch/changeCI）→ `getMTSSearch` + `showMTSCash` + 刷新 |
| 7 | 取消出售 | `UPDATE mts_items SET transfer=1 WHERE id AND seller`（转入自己的转移栏）+ `DELETE mts_cart WHERE itemid` + 刷新 |
| 8 | 从转移栏取回 | 查自己的 `transfer=1` 记录 → 按 type 重建 `Item`/`Equip`（逐列恢复全部装备属性）→ `DELETE` 该行 → `addFromDrop` → `MTSConfirmTransfer` |
| 9 | 加入购物车 | 非自己的商品（`seller <> 自己`）且购物车无重复 → `INSERT mts_cart` |
| 10 | 移出购物车 | `DELETE mts_cart WHERE itemid AND cid` + `getCart` 刷新 |
| 12/13/14 | 拍卖相关 | 空实现 |
| 16 | 购买 | 实付 `price + 100 + price*10%` 税；`CashShop NX_PREPAID` 余额校验；**给卖家打款**：遍历所有频道 `PlayerStorage` 找在线卖家 → `gainCash(4, price)`，全不在线（alwaysnull）→ JDBC `UPDATE accounts SET nxPrepaid += price`；`UPDATE mts_items SET seller=买家, transfer=1`（货入买家转移栏）+ 删购物车记录 → 扣买家点券 → `MTSConfirmBuy` + 刷新；余额不足/SQL 异常 → `MTSFailBuy` |
| 17 | 购物车里购买 | 与 16 同构，但在线/离线打款逻辑写在频道循环内（离线直接走 DB），刷新时带 `getCart` |
| 其他 | — | warn「Unhandled OP (MTS)」 |

**公有方法表**（查询辅助，均 JDBC 直查并装配 `MTSItemInfo` 列表）：

| 方法签名 | 作用 |
|---|---|
| `List<MTSItemInfo> getNotYetSold(int cid)` | 自己 `transfer=0`（在售）的挂单，按 id 倒序 |
| `Packet getCart(int cid)` | 购物车（`mts_cart` join `mts_items` 逐条查），计算页数 `ceil(count/16)`，`PacketCreator.sendMTS(items, 4, 0, 0, pages)` |
| `List<MTSItemInfo> getTransfer(int cid)` | 自己 `transfer=1`（待取回）的记录 |
| `Packet getMTSSearch(int tab, int type, int cOi, String search, int page)` | 关键词搜索：`cOi != 0` 时用 `ItemInformationProvider.getAllItems()` 名称匹配拼 `itemid = ... OR ...` SQL 片段（**字符串拼接 SQL**，遗留风险点，本篇如实记录）；否则按 `sellername LIKE`；分页 16 条/页 |

**私有方法**：`static Packet getMTS(int tab, int type, int page)`——按 tab（+type）分页查 `transfer=0` 的商品并计算页数。

**核心协作类**：`DatabaseConnection`（JDBC）、`CashShop`（NX_PREPAID 读写）、`Server.getAllChannels` + `PlayerStorage`（在线卖家打款）、`ItemInformationProvider.getAllItems`、`InventoryManipulator`、`MTSItemInfo`、`PacketCreator` MTS 系列包。

---

## 11. 其他（2 类）

### 11.1 MonsterBookCoverHandler（MONSTER_BOOK_COVER，怪物卡封面）

读卡片 id int；`id == 0`（取消封面）或 `id/10000 == 238`（怪物卡段）→ `player.setBookCover(id)` + 回 `changeCover(id)`。

### 11.2 MakerSkillHandler（MAKER_SKILL）

见 3.8（制造技能委托 `MakerProcessor.makerAction`，此处按字母序归入本篇清单第 62 项）。

---

## 附：本篇共性设计要点

1. **并发控制三件套**：`c.tryacquireClient()/releaseClient()`（`MessengerHandler`、`MesoDropHandler`、`MonsterCarnivalHandler`、`PlayerInteractionHandler`、`PlayerLoggedinHandler`、`PetFoodHandler`）；`inventory.lockInventory()/unlockInventory()`（背包结构变更：Merge/Sort/SET_ITEMS/上架/PetFood/登录装备遍历）；类级同步集合（`PlayerLoggedinHandler.attemptingLoginAccounts`）。
2. **反作弊分层**：`AutobanManager` 时间戳通道（HealOvertime 8/9、Merge 2、Sort 3、PetFood 1）与 spam 节流通道（ItemMove 6/300ms、MultiChat 7/200ms、PetFood 2/500ms）；`AutobanFactory` 计分（ITEM_VAC/PET_ITEM_VAC/PET_SHORT_ITEM_VAC/FAST_\*_HEALING/PACKET_EDIT）与直接封禁（HIGH_HP_HEALING）；坐标双记录（`markTeleportLikeMove/markRegularMove`，见 08 篇基类与 `InnerPortalHandler`）。
3. **邀请统一管理**：`InviteCoordinator` 承载 MESSENGER/PARTY 等邀请的创建、应答、过期，替代各自为政的布尔标记（`MessengerHandler`、`PartyOperationHandler`）。
4. **移动包字节回卷**：`movementDataStart/movementDataLength/p.seek` 三步保证广播包与客户端原始移动字节一致（四个 Move\*Handler）。
5. **i18n 与日志规范**：`PlayerInteractionHandler`、`NPCTalkHandler`、`QuestActionHandler`、`PlayerLoggedinHandler` 等的提示/日志均已接入 `I18nUtil`/`ChatLogger`；早期 OdinMS 代码（如 `MobDamageMobFriendlyHandler`、`MTSHandler`）仍保留硬编码英文与中文串及 `printStackTrace`，属待治理遗留。
