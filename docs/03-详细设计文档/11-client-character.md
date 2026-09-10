# 详细设计文档 11 —— 客户端会话与角色：Client / Character 及 client 根包

> 模块路径：`org.gms.client`（**仅根包 25 个类**；子包 `autoban`、`command`、`creator`、`inventory`、`keybind`、`processor`、`status` 由其它篇章负责）
>
> 类数量：**25**（Character 1 + Client 1 + AbstractCharacterObject 1 + 监听器 2 + 技能 2 + 好友 3 + 家族 3 + 任务 1 + 游戏对象 4 + 枚举/值对象 7）
>
> 依赖模块：`org.gms.client.*` 子包（Inventory/Item/Equip/Pet/KeyBinding/QuickslotBinding/AutobanManager/CharacterFactoryRecipe/InventoryManipulator 等）、`org.gms.net.packet`（Packet/InPacket）、`org.gms.net.server.*`（Server/Channel/World/PlayerStorage/SessionCoordinator/InviteCoordinator/guild/world/PlayerBuffValueHolder/PlayerCoolDownValueHolder/CharacterSaveService）、`org.gms.provider.*`（DataProvider/DataTool/WZFiles，仅 SkillFactory）、`org.gms.constants.*`（game/skills/id/net/inventory/string）、`org.gms.server.*`（StatEffect/TimerManager/ThreadManager/ExpLogger/ItemInformationProvider/SystemRescue/maps/life/quest/partyquest/events/gm/minigame/Fitness/Ola/RockPaperScissor）、`org.gms.scripting.*`（event/npc/quest/item、AbstractPlayerInteraction）、`org.gms.dao.entity` + `org.gms.service` + `org.gms.manager.ServerManager`（Spring 桥接：CharacterService/AccountService/NameChangeService/WorldTransferService/InventoryService/HpMpAlertService/MonsterBookService）、`org.gms.model.pojo`（SkillEntry/NewYearCardRecord）、`org.gms.model.dto`、`org.gms.config.GameConfig`（动态游戏配置）、`org.gms.util.*`（DatabaseConnection/PacketCreator/Pair/Randomizer/I18nUtil/NumberTool/ExtendUtil 等）、`io.netty.channel`（Client 继承 Netty Handler）、SLF4J、Lombok、JDBC（大量遗留代码直连 SQL）

## 目录

1. [总览](#1-总览)
2. [Character（核心角色类，10211 行）](#2-character核心角色类)
3. [Client（客户端会话）](#3-client客户端会话)
4. [AbstractCharacterObject（角色属性基类）](#4-abstractcharacterobject角色属性基类)
5. [监听器：AbstractCharacterListener / CharacterListener](#5-监听器)
6. [技能体系：Skill / SkillFactory](#6-技能体系)
7. [好友：BuddyList / BuddylistEntry / CharacterNameAndId](#7-好友)
8. [家族：Family / FamilyEntry / FamilyEntitlement](#8-家族)
9. [QuestStatus（任务状态）](#9-queststatus任务状态)
10. [其它游戏对象：Mount / MonsterBook / Ring / SkillMacro](#10-其它游戏对象)
11. [枚举与值对象：Job / Stat / BuffStat / Disease / DiseaseValueHolder / SkinColor / DefaultDates](#11-枚举与值对象)

---

## 1. 总览

`org.gms.client` 根包承载了游戏服最核心的两条对象线：

- **会话线**：`Client`（继承 Netty `ChannelInboundHandlerAdapter`，一个 TCP 连接一个实例）持有 `Character`（一个在线角色一个实例）。Client 负责账号登录态、封包分发、PIN/PIC、多开校验、频道切换与断线清理；Character 负责角色全部业务状态。
- **角色线**：`AbstractCharacterObject` 抽出 STR/DEX/INT/LUK/HP/MP/AP/SP 的读写锁模型与「属性池 long 打包」变更引擎；`Character` 在其上堆叠物品、技能、Buff、任务、社交、地图等全部子系统。属性变更通过 `AbstractCharacterListener`（实现类 `CharacterListener`）回调重算本地属性并广播封包。

辅助类分工：`Skill`/`SkillFactory`（WZ 技能数据装载）、`BuddyList`/`BuddylistEntry`（好友）、`Family`/`FamilyEntry`/`FamilyEntitlement`（家族树与权益）、`QuestStatus`（任务进度）、`Mount`（坐骑）、`MonsterBook`（怪物图鉴）、`Ring`（戒指）、`SkillMacro`（技能宏）、`Job`/`Stat`/`BuffStat`/`Disease`/`SkinColor`（协议枚举）。

数据库访问模式为混合式：遗留代码大量直连 JDBC（`DatabaseConnection`），BeiDou 新增/改造路径逐步迁移到 Spring service（`ServerManager.getApplicationContext().getBean(...)` 静态注入，见 Character 的 6 个静态 service 字段）。

---

## 2. Character（核心角色类）

**类声明**：`public class Character extends AbstractCharacterObject`（文件 `gms-server/src/main/java/org/gms/client/Character.java`，10211 行，OdinMS/Cosmic 遗留 + BeiDou 大量汉化改造）

**概述**：在线角色的完整运行时镜像。一个频道内玩家进入游戏后由 `CharacterService.loadCharFromDB` 构建并挂到 `Client.player`。类内用五把可重入锁划分并发域：`chrLock`（buff/冷却/疾病/宠物过滤等角色容器）、`effLock`（效果引擎与外层写锁）、`petLock`（宠物数组与金币/人气）、`prtLock`（组队/门）、`evtLock`（事件/任务过期），另继承父类 `statRlock/statWlock`（属性读写锁）。绝大多数方法按「加锁 → 改容器 → 组包下发」模式实现。

### 2.1 关键字段（分组）

| 分组 | 字段 | 说明 |
|---|---|---|
| 标识 | `id`/`accountId`/`name`/`world`/`gender`/`hair`/`face`/`skinColor`/`level`/`job` | 角色/账号/外观/等级职业，多数带 Lombok `@Getter/@Setter` |
| 排名 | `rank`/`rankMove`/`jobRank`/`jobRankMove` | 全服与职业排名 |
| 属性 | （父类）`attrStr/attrDex/attrInt/attrLuk/hp/mp/maxHp/maxMp/remainingAp/remainingSp[10]` | 见第 4 节 |
| 本地属性 | `localstr/localdex/localluk/localint_/localmagic/localwatk`、`equipmaxhp…equipwatk`、`localchairrate/localchairhp/localchairmp` | 装备与 buff 叠加后的最终值；equip* 为装备合计缓存（`equipchanged` 脏标记） |
| 货币/经验 | `exp`/`gachaExp`/`meso`（AtomicInteger）、`merchantmeso`、`expRate/mesoRate/dropRate`、`expCoupon/mesoCoupon/dropCoupon`、`totalExpGained`、`lastExpGainTime`、`mobExpRate` | 原子化防并发丢失；倍率=世界倍率×玩家等级加成×优惠券 |
| 社交 | `buddylist`、`party`+`mpc`、`mgc`+`guildId/guildRank/allianceRank`、`messenger`+`messengerPosition`、`familyEntry`/`familyId`、`marriageRing`/`partnerId`/`marriageItemId`/`crushRings`/`friendshipRings`、`newyears` | 好友/组队/公会/信使/家族/婚姻各有容器 |
| 物品 | `inventory[InventoryType 个数]`（CANHOLD 槽为 `InventoryProof`）、`keymap`、`quickSlotKeyMapped`/`quickSlotLoaded`、`skillMacros[5]`、`storage`、`cashShop`、`monsterBook` | |
| 技能/Buff | `skills: Map<Skill,SkillEntry>`、`effects: EnumMap<BuffStat,BuffStatValueHolder>`、`buffEffects: Map<srcid,Map<BuffStat,BSVH>>`、`buffExpires`、`buffEffectsCount`、`coolDowns`、`summons`、`diseases`+`diseaseExpires` | effects 为「当前生效」，buffEffects 为「全部持有」（支持可叠加 buff） |
| 地图 | （父类）`map`、`mapId`、`initialSpawnPoint`、`visibleMapObjects`、`controlled`、`lastVisitedMaps`（WeakReference LRU）、`ownedMap`、`entered`、`savedLocations[]`、`trockmaps`/`viptrockmaps`、`blockedPortals`、`area_info`、`portaldelay`、`banishMap/banishSp/banishTime`、`newWarpMap/canWarpMap/canWarpCounter`、`mapTransitioning`（AtomicBoolean） | |
| 定时器 | `dragonBloodSchedule`、`hpDecreaseTask`、`beholderHealingSchedule/beholderBuffSchedule`、`berserkSchedule`、`skillCooldownTask`、`buffExpireTask`、`itemExpireTask`、`diseaseExpireTask`、`questExpireTask`+`questExpirations`、`recoveryTask`、`extraRecoveryTask`、`chairRecoveryTask`、`pendantOfSpirit`、`cpqSchedule`、`FamilyBuffTimer` | 全部在 `empty()` 统一取消 |
| 反作弊/移动 | `normalAttackTimes`、`skillWindows`（AttackWindow）、`globalAttackTime`、`lastTeleportLikeMoveTime`、`teleportBeforePos/AfterPos` 等 8 个传送上下文字段、`movementBeforePos/AfterPos` 等 6 个移动上下文字段、`petLootTeleportBeforePos(+Time)` | 见 2.24 |
| 玩法 | `dojoPoints/dojoStage/dojoEnergy`、`vanquisherStage/vanquisherKills`、`ariantPoints`+`ariantColiseum`、`cp/totCP`+`monsterCarnival/monsterCarnivalParty`、`FestivalPoints`、`team`、`fitness/ola`、`snowballattack`、`eventInstance`、`partyQuest`、`events: Map<String,Events>`、`dragon`、`rps`、`miniGame`、`playerShop/shop/trade/hiredMerchant`、`omok*/matchcard*` | |
| 其它 | `gmLevel`+`hidden`+`whiteChat`、`banned`+`jailExpiration`、`autoBan`、`client`、`loggedIn`、`awayFromWorld`（AtomicBoolean，商城/MTS 算离世界）、`hasMerchant`、`hasSandboxItem`、`chalktext`、`commandtext`、`dataString`、`search`、`ci`、`currentTab/Type/Page`、`lastfametime`/`lastmonthfameids`、`possibleReports`、`mesosTraded`、`pendantExp`、`battleshipHp`、`usedSafetyCharm`、`canRecvPartySearchInvite`+`disabledPartySearchInvites`、`linkedLevel/linkedName`、`targetHpBarHash/Time`、`nextWarningTime`、`npcCd`、`lastExpression`、`lastCombo`/`combocounter`、`energyBar`、`pendingNameChange`、`loginTime`、`m_iCurrentOnlineTime`+`timeUpdating`、`npcUpdateQuests`（NPC 对话期间延迟下发的任务更新队列） | |
| 静态 Spring 服务 | `characterService`/`nameChangeService`/`worldTransferService`/`accountService`/`hpMpAlertService`/`inventoryService` | 类加载时从 `ServerManager` 取 bean |

### 2.2 构造、默认值与基础信息

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `private Character()` | 私有构造 | 注册 `CharacterListener`；`useCS=false`、`stance=0`；按类型初始化 5 个背包（CASH 96 槽、其余 24 槽，CANHOLD 换成 `InventoryProof`）；`savedLocations`/`quests` 置空结构；坐标 (0,0) |
| `static Character getDefault(Client c)` | 构建建角界面的默认角色模板 | hp50/mp5、STR12/DEX5/INT4/LUK4、1 级初心者、BuddyList(20)；按 `use_custom_keyset` 配置填默认键位；trock 5 格 + vip trock 10 格填 `MapId.NONE` |
| `Job getJobStyle()` / `Job getJobStyle(byte opt)` | 取武器流派职业（战士/法师/弓手/飞侠/海盗） | 委托 `Job.getJobStyleInternal`；opt 按 `str>dex` 传 0x80/0x40（海盗分拳/枪） |
| `void setHair(int hair)` | 换发型并通知勋章系统 | 记录 oldHair 后调用 `DynamicHairMedal.onHairChanged` |
| `boolean isMale()` | 性别判断 | `gender == 0` |
| `String getMedalText()` | 取已装备勋章的展示前缀 | 装备栏 -49 槽有物品时拼 `<勋章名> ` |
| `static String makeMapleReadable(String in)` | 名字转「枫叶可读」防仿冒 | `I→i、l→L、rn→Rn、vv/VV→Vv` |
| `int getFh()` | 取脚下平台 Y 坐标 | 当前坐标 y-6 后 `map.getFootholds().findBelow`，无则 0 |

### 2.3 在线状态、世界与频道

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `boolean isLoggedInWorld()` | 是否「真正在线」（非商城/MTS） | `isLoggedIn() && !isAwayFromWorld()` |
| `boolean isAwayFromWorld()` / `void setAwayFromChannelWorld()` / `void setDisconnectedFromChannelWorld()` | 商城离世界标记 | 后两者都走私有 `setAwayFromChannelWorld(disconnect)`：置 `awayFromWorld=true`；非断线时 `insertPlayerAway`，断线时 `removePlayerAway` |
| `void setEnteredChannelWorld()` | 从商城/进场回到世界 | `awayFromWorld=false` + `removePlayerAway`；若允许组队招募则 `attachPlayer` 到 PartySearchCoordinator |
| `void setSessionTransitionState()` | 进入频道切换过渡态 | 委托 `client.setCharacterOnSessionTransitionState(id)` |
| `void newClient(Client c)` | 换频道后绑定新 Client | 置 loggedIn、继承账号名；从新频道 MapFactory 取回地图，找最近出生点（无则 0 号门）重设坐标与 `initialSpawnPoint` |
| `void setCS(boolean cs)` / `boolean getUseCS()`（Lombok） | 标记是否正用混沌卷轴强化 | |
| `long getNpcCooldown()` / `void setNpcCooldown(long d)` | NPC 对话冷却时间戳 | |
| `void logOff()` | 登出收尾 | `loggedIn=false`；`characterService.update` 写 `lastLogoutTime` |
| `long getLoggedInTime()` / get/set `loginTime` | 本次在线时长 | `now - loginTime` |
| `int getCurrentOnlineTime()` / `void setCurrentOnlineTime(int)` / `void updateOnlineTime()` | 累计在线分钟数模块 | -1 表示重启初始值不落库；update 时经 `AbstractPlayerInteraction.saveOrUpdateAccountExtendValue(ONLINE_TIME)` 写账号扩展值 |
| `void checkMessenger()` | 登录后恢复信使在线状态 | 信使存在且位置合法时 `silentJoinMessenger` + `updateMessenger` |

### 2.4 GM、隐身、封禁与监狱

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void setGMLevel(int level)` | 设置 GM 等级并钳制 | `min(max(level,0),6)`；≥4 自动开启白色聊天 |
| `int gmLevel()` / `boolean isGM()` / `boolean isGmJob()` | 等级查询 | isGM：`gmLevel>1`；isGmJob：职业 niche ∈ [8,9]（800/900/910 系） |
| `void hide(boolean hide, boolean login)` | GM 隐身/现身 | 仅 GM 且状态变化时生效。现身：下发 GM 效果、广播 DARKSIGHT 取消、重发 spawn、召唤兽重播、全图怪物 `aggroUpdateController`；隐身：广播非 GM 移除自己、以 GM 广播 DARKSIGHT、`releaseControlledMonsters()` 放弃怪物控制权 |
| `void hide(boolean hide)` / `void toggleHide(boolean login)` | 便捷重载 | |
| `boolean getWhiteChat()` / `void toggleWhiteChat()` | GM 白字聊天开关 | get 需同时 isGM |
| `void ban(String reason)` | 封禁当前角色账号 | 委托 `accountService.ban(this, reason)` |
| `static boolean ban(String id, String reason, boolean accountId)` | 按名/账号封禁 | 委托 service，异常时 i18n 日志并返回 false |
| `void autoBan(String reason)` | 自动封禁（反作弊触发） | GM 或已封禁直接跳过；`ban` + `sendPolice` 提示 + 5 秒后断线 + 全 GM 广播 |
| `void block(int reason, int days, String desc)` | 临时封禁 | `accountService.update` 写 `banreason/tempban/greason`，天数加在当前日期上 |
| `void sendPolice(int greason, String reason, int duration)` | 弹警察警告窗并定时断线 | 下发 `PacketCreator.sendPolice`，置 `banned=true`，`duration` 毫秒后 `client.disconnect` |
| `void sendPolice(String text)` | 轻量违规警告 | 有 GM 在线则黄条广播，否则直接断线；记录 info 日志 |
| `long getJailExpirationTimeLeft()` / `void addJailExpirationTime(long)` / `void removeJailExpirationTime()` | 监狱刑期 | 剩余毫秒；累加在剩余时间上；清零释放 |
| `AutobanManager getAutoBanManager()` / `void setAutoBanManager(AutobanManager)` | 自动封禁管理器 | |

### 2.5 地图切换与传送（changeMap 家族）

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `MapleMap getWarpMap(int map)` | 解析传送目标地图 | 优先 EIM 实例图 → 怪物嘉年华事件图 → 频道 MapFactory |
| `MapleMap getMap(int mapid, boolean showMsg)` | 按-id 取图（BeiDou 增强版） | 取不到且 showMsg 时红字+弹窗提示、记 warn 日志、`enableActions` 解假死 |
| `void warpAhead(int map)` | OnUserEnter 脚本内「再传送一次」 | 仅记录 `newWarpMap`，由 `changeMapInternal` 完成后接续执行 |
| `void changeMap(int map)` / `changeMap(int map, Object pt)` | 按-id 传送 | EIM 图或 `getMap(map,true)`（不存在直接返回）；pt 支持 null/Integer/String/Portal 四态解析传送门 |
| `void changeMap(MapleMap to)` / `changeMap(MapleMap to, int portal)` / `changeMap(MapleMap target, Portal pto)` / `changeMap(MapleMap target, Point pos)` | 主传送入口 | 全部走 `changeMapInternal`；`canWarpCounter++/--` 计数防重入（归零才恢复 `canWarpMap`）；前后触发 `eventChangedMap`/`eventAfterChangedMap` |
| `void forceChangeMap(MapleMap target, Portal pto)` | 强制进指定事件图 | 目标图有 EIM 时让玩家退出原 EIM（空则 dispose）并 `registerPlayer` 到新 EIM，再 `changeMapInternal` |
| `void changeMapBanish(int mapid, String portal, String msg)` | 怪物放逐传送 | `use_spikes_avoid_banish` 开且装备带 SPIKES 标记则免疫；记录放逐前地图/门/时间，提示后传送 |
| `boolean canRecoverLastBanish()` / `Pair<Integer,Integer> getLastBanishData()` / `void clearBanishPlayerData()` / `void setBanishPlayerData(int,int,long)` | 放逐恢复上下文 | 5 分钟内可回原点；换图即清 |
| `void changeMapInternal(MapleMap to, Point pos, Packet warpPacket)`（private） | 传送核心 | `canWarpMap` 为 false 时丢弃；置 `mapTransitioning`、清传送距离上下文；先下 warp 包再 `map.removePlayer/addPlayer`；玩家不在 PlayerStorage 则视为卡死强制断线；更新 mpc 地图、SILENT_UPDATE 组队包、`visitMap`；处理 `newWarpMap` 接续、EIM 已开门恢复、环境障碍广播 |
| `boolean isChangingMaps()` / `void setMapTransitionComplete()` | 客户端换图状态 | `mapTransitioning` AtomicBoolean 读写 |
| `void visitMap(MapleMap map)` / `List<Integer> getLastVisitedMapIds()` | 最近访问地图 LRU（弱引用） | 已存在则移到尾部；超 `map_visited_size` 删头。供组队掉落归属判定 |
| `void setOwnedMap(MapleMap map)` / `MapleMap getOwnedMap()` | 「我先来的」地图占有标记 | WeakReference，不阻止回收 |
| `void leaveMap()` | 离开当前地图 | 放弃怪物控制、清可见对象、下椅子、取消地图掉血任务；阿里安竞技场离场 |
| `void respawn(int returnMap)` / `void respawn(EventInstanceManager eim, int returnMap)` | 死亡回城复活 | eim 非空先注销玩家；传送后 `cancelAllBuffs(false)`；用过护身符回 30% HP/MP，否则 HP=50； stance 归零 |
| `void doHurtHp()` | 危险地图周期掉血 | 装备 HPDecProtect 道具或 `buffMapProtection` 免疫，否则 `addHP(-map.getHPDec())` 并通知客户端 |
| `void portalDelay(long delay)` / `long portalDelay()` | 传送门冷却时间戳 | now+delay |
| `void blockPortal(String scriptName)` / `void unblockPortal(String scriptName)` | 任务封锁传送门 | 入 blockedPortals 列表并 enableActions |
| `int getMapId()` / `void setMap(int)` | 当前地图-id | map 为空（如商城）时退回 `mapId` 字段 |
| `void saveLocation(String type)` / `void saveLocationOnWarp()` / `int getSavedLocation(String type)` / `int peekSavedLocation(String type)` / `void clearSavedLocation(SavedLocationType)` | 回城点存取 | 按类型写 `savedLocations[]`（记录最近门）；get 取后即清，peek 只读；OnWarp 版本填所有空槽 |
| `void showDojoClock()` | 道场 Boss 房倒计时 | `getDojoFinishTime - now` 秒下发时钟 |
| Mount/坐骑见 2.19；trock（传送石地图）见 2.21 | | |

### 2.6 门（魔法门/组队门）

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `Collection<Door> getDoors()` | 玩家可用门集合 | 有组队取队伍门表，否则取个人 `pdoor` |
| `Door getPlayerDoor()` / `Door getMainTownDoor()` | 个人门 / 主城门 | 主城门 = town portal id 0x80 的门 |
| `void applyPartyDoor(Door door, boolean partyUpdate)` | 注册门 | 写 `pdoor`（非组队更新时）并 `party.addDoor`，随后 silentPartyUpdate |
| `Door removePartyDoor(boolean partyUpdate)` / `void removePartyDoor(Party)`（private） | 摘除门 | 从队伍门表与 pdoor 移除 |
| `boolean canDoor()` | 能否再开门 | 无门或门激活且部署超 5 秒 |
| `void cancelMagicDoor()` | 强制取消魔法门效果 | 遍历生效 buff 找 `isMagicDoor` 后 `cancelEffect` |
| `int getDoorSlot()` / `int fetchDoorSlot()` | 门 UI 槽位 | 无组队恒 0；有组队取 `party.getPartyDoor(id)` 并缓存 `doorSlot` |

### 2.7 拾取与库存

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void pickupItem(MapObject ob)` / `void pickupItem(MapObject ob, int petIndex)` | 玩家/宠物拾取地上掉落 | 掉落 400ms 内不可拾；两段 `mapitem.lockItem()` 校验 `canBePickedBy`/`isPickedUp`；金币按同图存活队友均分；专属拾取图（圣诞树/公会 PQ）只许自拾；任务物品先过 `needQuestItem`；243 段脚本道具触发 `ItemScriptManager`；NX 卡直充账户；`applyConsumeOnPickup` 处理即拾即用；普通物 `InventoryManipulator.addFromDrop`；背包满下发满包提示；最后 `map.pickItemDrop` 广播 |
| `boolean applyConsumeOnPickup(int itemId)` | 拾取即消耗道具 | 仅 2xxxxx 消耗品且 `isConsumeOnPickup`；队伍道具对同图存活队友逐一 `applyTo`（全体解除类改 `dispelDebuffs`）；238xxx 怪物卡入 `MonsterBook.addCard` |
| `int countItem(int itemid)` | 按包计数 | 对应背包 `countById` |
| `boolean canHold(int itemid)` / `canHold(int itemid, int quantity)` | 背包能否放入 | 委托 `AbstractPlayerInteraction.canHold` |
| `boolean canHoldUniques(List<Integer> itemids)` | 拾取受限唯一物校验 | 对每个 `isPickupRestricted` 物检查 `haveItem` |
| `boolean canHoldMeso(int gain)` | 金币是否会溢出上限 | `meso+gain <= Integer.MAX_VALUE` |
| `boolean haveItem(int itemid)` / `haveCleanItem(int itemid)` / `haveItemWithId(int itemid, boolean checkEquipped)` / `haveItemEquipped(int itemid)` | 持有判断 | clean 版排除刻名（not owned）物品 |
| `int getItemQuantity(int itemid, boolean checkEquipped)` / `int getCleanItemQuantity(...)` | 数量统计 | 装备类默认计入已装备栏 |
| `boolean hasEmptySlot(int itemId)` / `hasEmptySlot(byte invType)` | 有空格否 | `getNextFreeSlot() > -1` |
| `Inventory getInventory(InventoryType type)` | 取背包 | 数组下标取 |
| `void forceUpdateItem(Item item)` | 强刷客户端物品 | `modifyInventory` 先 remove(3) 再 add(0) |
| `int getSlot()` / `void setSlot(int)` / `byte getSlots(int type)` / `boolean canGainSlots(int,int)` / `boolean gainSlots(int,int)` / `boolean gainSlots(int,int,boolean update)` | 背包格扩容 | 上限 96（CASH 恒 96）；扩容成功即 `saveCharToDB` 并下发新格数 |
| `void setHasSandboxItem()` / `void removeSandboxItems()` | GM 沙盒道具清理 | 登录检测到 SANDBOX 标记后逐背包删除并红字提示 |
| `void gainEquip(int itemId, Short attStr, …, Byte upgradeSlot, Long expireTime)` | GM/脚本发装备（BeiDou 增强） | 非 EQUIP 类型提示退出；`getEquipById` 生成基础装备，17 个属性参数 null 取默认（`RequireUtil.requireNotEmptyAndThen`），expireTime>0 按分钟折算，-1 永久；`checkSpace` 后 `addFromDrop` |
| `void increaseEquipExp(int expGain)` | 装备吃经验 | `allowExpGain` 才生效；遍历可升级装备（可配置含现金装）`Equip.gainItemExp` |
| `void showAllEquipFeatures()` | 弹窗展示全身装备特性 | 拼接各 `Equip.showEquipFeatures` 后 `showHint` |
| `void equippedItem(Equip equip)` / `void unequippedItem(Equip equip)` | 穿脱回调 | 精灵吊坠（1122017）启动/停止 1 小时计时器，`pendantExp` 最高 3（+30% exp） |
| `int sellAllItemsFromName(byte invTypeId, String name)` / `int sellAllItemsFromPosition(ItemInformationProvider, InventoryType, short)` | 商店一键出售 | 按名找首个物品起，逐格 `standaloneSell` |
| `boolean mergeAllItemsFromName(String name)` / `void mergeAllItemsFromPosition(Map<StatUpgrade,Float>, short)` | 装备融合（合成） | 从指定位置起 `standaloneMerge` 汇总属性（攻防取对数），按 80%/20% 概率分配到已装备同属性/全部可升级装备，打 MERGE_UNTRADEABLE+UNTRADEABLE 标记并 forceUpdate |
| `private int standaloneSell(...)` / `private void standaloneMerge(...)` | 单格出售/融合 | 出售：婚戒婚证禁卖、可充能物全数量、`ii.getPrice` 结算；融合：现金装/已融合/不可升级跳过，攻防类属性取 `log` |

### 2.8 属性、经验、升级与死亡

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void gainExp(int gain)` ×4 重载 → `gainExp(int gain, int party, boolean show, boolean inChat, boolean white)` | 获得经验（角色/组队/吊坠三路） | CURSE 病减半；负数视为溢出取 MAX；吊坠经验 = gain/10×pendantExp；汇总进 `gainExpInternal` |
| `void loseExp(int loss, boolean show, boolean inChat[, boolean white])` | 扣经验 | 以负数调 `gainExpInternal`（不低于 0） |
| `private synchronized void gainExpInternal(long gain, int equip, int party, boolean show, boolean inChat, boolean white)` | 经验核心（synchronized） | 总增益下限 −当前 exp；`allowExpGain` 或在 EIM 中才入账；溢出部分 leftover 递归；while 循环升级（`use_level_up_protect` 可只升一级）；满级置 0；全局升级公告可配置（商城玩家跳过防弹窗轰炸）；`use_exp_gain_log` 时写 ExpLogger |
| `void levelUp(boolean takeexp)` | 升级（synchronized） | 新手 <11 级可自动配点；AP 按 `level_up_ap_gain`（骑士团加成）；按职业掷 HP/MP 成长骰并叠加「强化 HP/MP」技能；`use_randomize_hpmp_gain` 时 MP 再加 INT 加成；`addMaxMPMaxHP`、扣升级所需 exp、`level++`（满级触发 PlayerNPC 部署与全服公告）；`levelUpGainSp`；重算本地属性并回满 HP/MP；广播升级特效；组队/公会/家族联动（家族长辈拿声望）；每 20 级可扩格/提倍率；30 级+发完美音调券；10 级自动退新手队 |
| `void gainGachaExp()` / `void addGachaExp(int gain)` | 转蛋经验结算/累计 | 结算时最多连升 2 级的量转入 gainExp，余量存回 |
| `int getExp()` / `void setExp(int)` / `int getGachaExp()` / `void setGachaExp(int)` | 原子字段读写 | |
| `synchronized void resetStats()` | AP 重置（自动配点服务器） | 需 `use_auto_assign_starters_ap`；按一转职业回收全部点数到 35/20/25/20 主属性，多余的进 AP、SP 补 3/级，`updateStrDexIntLukSp` 一次下发 |
| `int getMaxLevel()` / `int getMaxClassLevel()` | 等级上限 | 骑士团 120、其余 200；`use_enforce_job_level_range` 时按职业表限 |
| `boolean applyHpMpChange(int hpCon, int hpchange, int mpchange)` | 技能 HP/MP 消耗与伤害统一入口 | ZOMBIFY 下治疗反转；HP 将≤0（非僵尸治疗）或 MP<0 时拒绝（GM 钳 1）；`updateHpMp` 后按需触发服务端自动喝药（`use_server_auto_pot` 读 HpMpAlertService 阈值 / `use_compulsory_auto_pot` 全局比例，键位 91/92 上的药） |
| `void hpChangeAction(int oldHp)` | HP 变化回调（供监听器） | 由旧新 HP 判定死亡；组装 Runnable（更新组队 HP；死亡走 `playerDead`，否则 `checkBerserk`）交给 `map.registerCharacterStatUpdate` 延迟执行以避开锁内发包 |
| `private void playerDead()` | 死亡处理 | CPQ 图扣 CP 广播后返回；取消全 buff、dispelDebuffs；EIM `playerKilled`；优先消耗安全护符/复活节道具（道场除外）免掉经验；否则按图类型与 LUK 阈值扣 1%/5%/10% 经验；取消变身/骑乘；起立并 enableActions |
| `void setCombo(short count)` / `short getCombo()` | Aran 连击数 | 变小则取消 ARAN_COMBO buff；上限 30000，>0 时下发 `showCombo` |
| `void gainFame(int delta)` / `boolean gainFame(int delta, Character fromPlayer, int mode)` | 人气 ± | `applyFame` 钳 [-30000,30000]；带来源时双向下发人气包 |
| `void hasGivenFame(Character to)` | 记录已给人气 | 更新 `lastfametime`、`lastmonthfameids`，并写 famelog 表 |

### 2.9 经验/掉落/金币倍率体系

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `boolean hasNoviceExpRate()` | 新手保护 | `use_enforce_novice_exp_rate` 且初心者且 <11 级 |
| `float getExpRate()` | 角色最终经验倍率 | 新手保护返回 1，否则 `expRate`（世界×玩家×优惠券的乘积，由下面一组方法维护） |
| `float getLevelExpRate()` | 等级成长经验加成 | `1 + level_exp_rate × level`（世界配置） |
| `float getQuickLevelExpRate()` | 冲级加成 | 未达 `quick_level` 前按差值线性加成 |
| `void updateMobExpRate()` / `float getMobExpRate()` | 打怪综合倍率缓存 | `level×quick` 乘积；≤0 时懒更新 |
| `int getCouponExpRate()` / `getCouponDropRate()` / `getCouponMesoRate()` | 优惠券倍率 | |
| `float getRawExpRate()` / `getRawDropRate()` / `getRawMesoRate()` | 逆推「玩家私有倍率」 | 除掉优惠券与世界倍率，用于玩家个人加成展示 |
| `float getBossDropRate()` | Boss 掉落倍率 | 玩家 dropRate 换算后乘世界 boss 倍率 |
| `float getQuestExpRate()` / `getQuestMesoRate()` | 任务奖励倍率 | 世界 exp/meso × questRate |
| `float getCardRate(int itemid)` | 怪物卡掉率加成 | MESO_UP_BY_ITEM / ITEM_UP_BY_ITEM buff 的 `getCardRate` 加成折成乘数 |
| `void setPlayerRates()` / `revertLastPlayerRates()` / `revertPlayerRates()` / `setWorldRates()` / `revertWorldRates()` / `resetPlayerRates()` | 倍率叠加/回退 | 乘除 GameConstants 的等级段加成或世界倍率；`applySavedRateOrElse` 优先读 ExtendValue 里 GM 设置的指定角色倍率（`CHARACTER_EXTEND` 类型 expRate/mesoRate/dropRate） |
| `void setCouponRates()` | 从 CASH 背包激活优惠券 | `setActiveCoupons`（统计持券数与倍率表）→ `activateCouponsEffects`（`use_stack_coupon_rates` 叠乘，否则取经验/掉落各最大）→ 逐券 `commitBuffCoupon` |
| `void updateCouponRates()` | 重新计算券效果 | 锁 effLock+chrLock+CASH 背包，先 revert 再 set |
| `void dispelBuffCoupons()` | 取消全部券 buff | 遍历生效 buff，来源是 RateCoupon 的 cancelEffect |
| `Set<Integer> getActiveCoupons()` | 当前生效券-id 集 | |

### 2.10 金币

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `int getMeso()` / `void setMeso(int)` | 原子读写 | |
| `void gainMeso(int gain)` ×3 → `gainMeso(int gain, boolean show, boolean enableActions, boolean inChat)` | 金币变动核心 | petLock 保护下防上溢/下溢（钳到 0/上限）；非 0 时 `updateSingleStat(MESO)`，show 时下发获得提示 |
| `synchronized boolean spendMesoTransactionally(int cost, IntPredicate persistence)` | 与业务事务绑定的扣费（BeiDou 新增） | 余额不足/持久化回调抛错或返回 false 都不扣内存；`persistence.test(balanceAfter)` 成功后才 `meso.set` 并下发；用于「扣钱+写库」原子化 |
| `int getMerchantMeso()` / `int getMerchantNetMeso()` | 雇佣商店余额/净值 | 净值按 fredstorage 时间戳每天扣 1%，最多 100 天 |
| `void setHasMerchant(boolean)` / `boolean hasMerchant()` | 有开张雇佣商店标记 | 直接 update DB |
| `void addMerchantMesos(int add)` | 商店入账 | 钳上限后 `setMerchantMeso` |
| `void setMerchantMeso(int set)` | 写商店余额 | update DB + 内存 |
| `synchronized void withdrawMerchantMesos()` | 提取商店金币 | 正余额分批 gainMeso（防角色溢出），负余额反向扣 |

### 2.11 职业进阶

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `synchronized void changeJob(Job newJob)` | 转职主流程 | 更新 job（带组队搜索暂停/恢复）；SP 赠送（4 转+2、SP 表职业+2，`use_enforce_job_sp_range` 走 `getChangedJobSp` 补齐到理论值）；AP 赠送（骑士团 7，其余 5/4）；非 GM 四背包各+4 格；按职业段掷 HP/MP；重算属性并 7 项 stat 下发；重建 MPC、静默组队更新；龙取消、公会/家族广播；`setMasteries` 发 4 转基础技能；`broadcastChangeJob`（全图重发 spawn + 延迟 777ms 转职特效）；Evan 系取消骑乘并 `createDragon`；可配置全服公告 |
| `void setMasteries(int jobId)` | 补发 4 转掌握类技能 | 按 jobId 查表（112/122/…/2218），未学的以 0 级/10 上限写入（客户端显示可加点） |
| `int getJobType()` / `boolean isCygnus()` / `boolean isAran()` / `boolean isBeginnerJob()` | 职业分类 | jobType=id/1000；骑士团=1；Aran 2000~2112；初心者 0/1000/2000 |
| `private int getChangedJobSp(Job)` / `getUsedSp(Job)` / `getJobLevelSp(int,Job,int)` / `getJobMaxSp(Job)` / `getJobRemainingSp(Job)` / `getSpGain(int,Job)` / `getSpGain(int,int,Job)` / `levelUpGainSp()` | SP 总量管控（private 组） | 「已用 SP + 剩余 SP」与「等级理论 SP（法师提前 2 级起算）」对齐，防 SP 溢出；升级 SP 走 `level_up_sp_gain` 且同样受上限钳制 |

### 2.12 技能与冷却

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void changeSkillLevel(Skill skill, byte newLevel, int newMasterlevel, long expiration)` | 修改技能等级 | ≥0 写入 `skills`（隐藏技能不下发包）；-1 删除并调 `characterService.removeSkill` |
| `Map<Skill,SkillEntry> getSkills()` / `getEditableSkills()` | 技能表只读/可写视图 | |
| `int getSkillLevel(int skill)` / `byte getSkillLevel(Skill skill)` | 技能等级 | 未学返回 0 |
| `int getMasterLevel(int)` ×2 | 技能掌握上限 | |
| `long getSkillExpiration(int)` ×2 | 技能到期时间 | 未学 -1 |
| `int getRemainingSp()` / `void updateRemainingSp(int)` | 当前职业书 SP | 委托父类按 skillbook |
| `void addCooldown(int skillId, long startTime, long length)` | 登记冷却 | effLock+chrLock 下写 `coolDowns` |
| `void giveCoolDowns(int skillid, long starttime, long length)` | 登录恢复冷却 | 5221999 特殊：length 即 battleshipHp |
| `void removeCooldown(int skillId)` / `void removeAllCooldownsExcept(int id, boolean packet)` | 移除冷却 | Except 版可逐个下发冷却清零包 |
| `boolean skillIsCooling(int skillId)` | 冷却中判断 | |
| `List<PlayerCoolDownValueHolder> getAllCooldowns()` | 冷却快照（跨频道迁移用） | |
| `synchronized void saveCooldowns()` | 冷却+疾病落库 | 冷却全删全写 cooldowns 表；疾病写 playerdiseases（含 MobSkillId） |

### 2.13 攻击频率反作弊（BeiDou 新增模块）

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `long getAttackInterval(int skillId, long now)` | 取距上次攻击间隔并更新时间戳 | `normalAttackTimes.compute` 原子更新；间隔 < `MIN_INTERVAL`(50ms) 视为网络抖动不更新时间戳透明放行；首次/时钟回退返回 `Long.MAX_VALUE` |
| `SkillWindowResult checkSkillWindow(int skillId, long interval)` | 滑动窗口判定攻击模式 | 窗口 10 次；avg≥250ms 判 PASS；<250 且 CV<0.3 判 `STABLE_HACK`（稳定高速），否则 `BURST`（网络暴发） |
| `AttackWindow getSkillWindow(int skillId)` | 取某技能窗口（只读） | 无则 null |
| `long getGlobalInterval(long now)` / `void updateGlobalTime(long now)` | 全局攻击间隔 | 只被正常主动技能更新 |

（内部类型 `SkillWindowResult`、`AttackWindow` 见 2.25。）

### 2.14 Buff / 效果引擎

> 数据结构：`effects`（BuffStat→当前生效 holder）+ `buffEffects`（buff源id→全部 statup holder）+ `buffExpires` + `buffEffectsCount`。`use_buff_most_significant` 开启时同 stat 取最优。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void registerEffect(StatEffect effect, long starttime, long expirationtime, boolean isSilent)` | 注册 buff 效果 | 先按效果类型挂任务：龙之血 4s 掉血、狂战士 `checkBerserk`、黑骑士之眼治疗/增益双任务、RECOVERY 周期回复、HpR/MpR 额外回复任务、地图椅子 `startChairTask`；然后三锁下登记 holder：统计 buffEffectsCount，`use_buff_most_significant` 时逐 statup 比较现值择优 deploy，并 `propagateBuffEffectUpdates` 重算整组关联 buff 下发顺序；isSilent 不登记过期表 |
| `boolean cancelEffect(StatEffect effect, boolean overwrite, long startTime)` | 取消效果（返回是否取消到） | prtLock+effLock 下走 `cancelEffect(...,firstCancel=true)`；魔法门取消且无其它门源时 `Door.attemptRemoveDoor` |
| `private boolean cancelEffect(StatEffect, boolean, long, boolean)` / `cancelEffectInternal` / `deregisterBuffStats` / `dropBuffStats` / `extractCurrentBuffStats` / `extractLeastRelevantStatEffectsIfFull` | 取消链 | 非 overwrite 抽走该源全部 statup；singleton statup 特判；buff stat 满员（`max_monitored_buff_stats`）时抽最弱；deregister 里同步取消 RECOVERY 任务、召唤兽/木偶（广播移除+黑骑士任务取消）、龙之血任务、HpR/MpR 任务；最后 `updateLocalStats`+`updateEffects` |
| `void cancelEffect(int itemId)` | 按道具取消 | `ii.getItemEffect(itemId)` 后 cancelEffect |
| `void cancelEffectFromBuffStat(BuffStat stat)` | 按 stat 取消 | 取 effects 中 holder 后 cancelEffect |
| `void cancelBuffStats(BuffStat stat)` | 只取消某个 stat 的所有 buff | 遍历 buffEffects 中含该 stat 的源，逐一 extract+drop，最后 `cancelPlayerBuffs` |
| `void cancelAllBuffs(boolean softcancel)` | 全取消 | soft：直接清容器（SUMMON/PUPPET/COMBO 先 cancel）；hard：逐 effect cancelEffect |
| `void updateActiveEffects()` | 依据 isActive 重算生效集 | 地图/条件变化后调用；更新过的 stat 从 effects 移除再 `updateEffects` |
| `private void updateEffects(Set<BuffStat>)` / `propagateBuffEffectUpdates` / `propagatePriorityBuffEffectUpdates` / `cancelInactiveBuffStats` / `sortEffectsList` + `topologicalSort*` 三个 | buff 传播引擎（private 组） | 对每个被移除/接管的 stat 重新 `fetchBestEffectFromItemEffectHolder` 选最优；按 statup 值排序 + 拓扑排序决定下发顺序（客户端对 statup 顺序敏感）；WATK/MATK 按是否法师过滤；花香三类优先源（ROSE/FREESIA/LAVENDER SCENT）强制覆盖；战舰骑乘补发 HP 包 |
| `Long getBuffedStarttime(BuffStat)` / `Integer getBuffedValue(BuffStat)` / `int getBuffSource(BuffStat)` / `StatEffect getBuffEffect(BuffStat)` / `StatEffect getStatForBuff(BuffStat)` | 读当前生效 buff | 双锁下读 effects |
| `void setBuffedValue(BuffStat effect, int value)` | 改生效值 | 连击/能量条刷新用 |
| `boolean hasActiveBuff(int sourceid)` / `hasBuffFromSourceid(int sourceid)` / `isBuffFrom(BuffStat, Skill)` | 来源判断 | |
| `List<PlayerBuffValueHolder> getAllBuffs()` | buff 快照（跨频道） | 按 srcid 去重，带已经过时间 |
| `void silentGiveBuffs(List<Pair<Long, PlayerBuffValueHolder>> buffs)` | 登录/换线静默恢复 | 逐个 `effect.silentApplyBuff` |
| `void debugListAllBuffs()` | 调试输出 buff 三张表 | |
| `boolean registerChairBuff()` / `boolean unregisterChairBuff()` | 地图椅子 buff（额外回血） | 需 `use_chair_extra_heal` 且学过本系 MAP_CHAIR；applyTo/cancelEffect |
| `int getChair()` / `void sitChair(int itemId)` | 椅子状态/坐椅 | ≥1000000 道具椅（广播 showChair）；0~999999 地图椅（注册椅子 buff 并广播）；<0 起立 `unsitChairInternal`（钓鱼椅注销、广播取消） |
| `void handleEnergyChargeGain()` | 能量条增长 | 每次+102 至 10000 满；满后切 15000「爆发态」并按持续时间倒计时归零，广播海盗 buff |
| `void handleOrbconsume()` | 战士 COMBO 球清零 | setBuffedValue(1) 并广播 |
| `void checkBerserk(boolean isHidden)` | 黑骑士狂战士状态机 | 取消旧任务；HP% < 技能 X 值时 berserk=true，注册 5s/3s 周期任务周期性向自身/GM 广播状态 |
| `void dispel()` | 驱散自身技能类 buff | HOLY_SHIELD 可配免驱；COMBO_ABILITY 例外保留 |
| `void dispelSkill(int skillid)` | 按技能驱散（0=驱散召唤类） | skillid=0 时驱散 1004（表情）与 `dispelSkills` 白名单（黑骑士之眼/召唤兽/木偶/影子） |
| `boolean isRidingBattleship()` / `void announceBattleshipHp()` / `void decreaseBattleshipHp(int)` / `void resetBattleshipHp()` | 海盗战舰 | MONSTER_RIDING=BATTLE_SHIP 判定；HP 归零进入冷却并取消骑乘；重置公式 `400×技能等级 + 200×(等级−120)` |

### 2.15 周期任务（buff/冷却/道具过期）

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void buffExpireTask()` / `void cancelBuffExpireTask()` | buff 过期扫描（1.5s） | 到期源 cancelEffect |
| `void skillCooldownTask()` / `void cancelSkillCooldownTask()` | 冷却到期扫描（1.5s） | 到期 removeCooldown + 下发冷却 0 |
| `void expirationTask()` / `void cancelExpirationTask()` | 道具/技能过期扫描（60s） | 过期技能 changeSkillLevel(-1)；带 LOCK 的过期道具转为永久；一般道具到期移除并发 `itemExpired`，支持 `getReplaceOnExpire` 补偿物；宠物到期先 unEquipPet，可过期宠物 `Pet.deleteFromDb` 清孤儿数据；删除券后 `updateCouponRates` |
| `void diseaseExpireTask()` / `void cancelDiseaseExpireTask()` | 疾病过期扫描（1.5s） | 到期 dispelDebuff |
| `void questExpirationTask()` / `void cancelQuestExpirationTask()` / `private void runQuestExpireTask()` / `private void registerQuestExpire(Quest, long)` | 任务限时（10s 扫描，空表自停） | |
| `void questTimeLimit(Quest quest, int seconds)` | 新限时任 | 注册过期 + 下发客户端时限钟 |
| `void questTimeLimit2(Quest quest, long expires)` | 按绝对到期时间恢复 | 剩余≤0 立即过期 |
| `void forfeitExpirableQuests()` | 放弃全部限时任（下线用） | |
| `void reloadQuestExpirations()` | 登录恢复限时任 | 遍历 STARTED 且有 expiration 的任务走 questTimeLimit2 |
| `private void startChairTask()/stopChairTask()/getChairTaskIntervalRate()/updateChairHealStats()` | 椅子回血任务（private 组） | BeiDou 重算公式：以 21s 回满为目标二分出单次回复量（钳 interval 420~5000ms），满池自动停任务 |
| `private void startExtraTask(byte,byte,short)/startExtraTaskInternal/stopExtraTask()` | HpR/MpR 回复任务 | buff 源消失自停 |

### 2.16 疾病（Disease/Debuff）

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `boolean hasDisease(Disease dis)` / `int getDiseasesSize()` | 疾病判断/计数 | 同时最多 2 个 |
| `Map<Disease, Pair<Long, MobSkill>> getAllDiseases()` | 快照（剩余毫秒+技能） | |
| `void silentApplyDiseases(Map<Disease, Pair<Long, MobSkill>>)` | 登录静默恢复 | |
| `void announceDiseases()` | 向全图广播自己的疾病 | 地图切换后延续可视性；SLOW 走专用包 |
| `void collectDiseases()` | 收集全图他人疾病（自己进场） | |
| `void giveDebuff(Disease disease, MobSkill skill)` | 怪物施加疾病 | 未患且 <2 个；非 SEDUCE/STUN 可被 HOLY_SHIELD 挡；登记过期表；SEDUCE 强制起立；给自己+全图广播（SLOW 专用包） |
| `void dispelDebuff(Disease debuff)` | 解单个疾病 | cancelDebuff 双包 + 容器移除 |
| `void dispelDebuffs()` | 解常规五病 | CURSE/DARKNESS/POISON/SEAL/WEAKEN/SLOW（ZOMBIFY 不可解） |
| `void purgeDebuffs()` | 全解 | 再加 SEDUCE/ZOMBIFY/CONFUSE |
| `void cancelAllDebuffs()` | 清容器（不发包） | 下线用 |

### 2.17 任务系统

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `byte getQuestStatus(int quest)` | 任务状态-id | 未登记返回 0（NOT_STARTED） |
| `QuestStatus getQuest(int quest)` / `getQuest(Quest quest)` | 取状态（无则建 NOT_STARTED 并登记） | |
| `QuestStatus getQuestNAdd(Quest)` / `getQuestNoAdd(Quest)` | 建/不建变体 | |
| `List<QuestStatus> getStartedQuests()` / `getCompletedQuests()` | 已开始/已完成列表 | |
| `void updateQuestStatus(QuestStatus qs)` | 任务状态推进总入口 | STARTED：UPDATE+INFO 包（含 infoNumber 副任务）；COMPLETED：非同日重复任务发任务点（`awardQuestPoint`），completed 计数+1，COMPLETE 包；NOT_STARTED：UPDATE |
| `void setQuestProgress(int id, int infoNumber, String progress)` | 写任务进度字符串 | infoNumber 匹配时写副任务，否则写主任务；随后 UPDATE 包 |
| `void raiseQuestMobCount(int id)` | 击杀计数（含三组假 mob 合并） | 绿蘑菇/僵尸蘑菇/树妖王各合并到 QUEST 版 mob；遍历任务 `qs.progress(id)` 成功则 UPDATE（含副任务） |
| `void announceUpdateQuest(DelayedQuestUpdate, Object...)` / `flushDelayedUpdateQuests()` | 任务包下发（NPC 对话延迟化） | 正在 NPC/quest 脚本对话中先入 `npcUpdateQuests` 队列，脚本结束时统一 flush，避免打断对话 |
| `void awardQuestPoint(int awardedPoints)` | 任务点 → 人气 | 按 `quest_point_requirement` 进位 |
| `boolean needQuestItem(int questid, int itemid)` | 任务物品是否还需要 | 状态 0 查起始需求、状态 1 查完成需求、其它 false |
| `boolean gotPartyQuestItem(String)` / `void removePartyQuestItem(String)` / `void setPartyQuestItemObtained(String)` | PQ 字母收集（dataString） | |
| `void enteredScript(String script, int mapid)` / `boolean hasEntered(String)` ×2 / `void resetEnteredScript()` ×3 | OnFirstUserEnter 脚本去重 | |

### 2.18 好友 / 组队 / 公会 / 家族 / 婚姻

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void deleteBuddy(int otherCid)` | 删好友 | 对方在线可见时经 world 通知其频道；本地移除并下发新列表；弹出下一条待处理请求 |
| `void setBuddyCapacity(int capacity)` | 好友容量 | 改 BuddyList 并下发容量包 |
| `Party getParty()` / `void setParty(Party p)` / `int getPartyId()` | 组队引用 | setParty(null) 时顺带清 mpc 与 doorSlot |
| `PartyCharacter getMPC()` / `void setMPC(PartyCharacter)` | 组队侧玩家镜像 | 懒创建 |
| `List<Character> getPartyMembersOnline()` / `getPartyMembersOnSameMap()` | 在线/同图队友 | 按 map.hashCode 比较同图 |
| `boolean isPartyMember(Character/int)` / `isPartyLeader()` | 队员/队长判断 | |
| `boolean leaveParty()` | 退队 | 队长则先 `assignNewLeader`，`Party.leaveParty` |
| `void partyOperationUpdate(Party party, List<Character> exPartyMembers)` | 组队变更后掉落归属与门刷新 | 遍历最近访问地图 `updatePlayerItemDropsToParty`；新队员补发可见掉落；`updatePartyTownDoors`（城镇门销毁/重建，离队者单独处理自己的门） |
| `void silentPartyUpdate()` / `private void silentPartyUpdateInternal(Party)` | 静默更新队伍侧镜像 | SILENT_UPDATE 世界广播 |
| `void updatePartyMemberHP()` / `private void updatePartyMemberHPInternal()` | 向同图队友广播自己 HP | |
| `void receivePartyMemberHP()` | 进图时接收队友 HP | 特意不加 prtLock，注释说明避免跨角色锁序死锁 |
| `void updatePartySearchAvailability(boolean)` / `boolean toggleRecvPartySearchInvite()` / `boolean isRecvPartySearchInviteEnabled()` / `void closePartySearchInteractions()` | 组队招募开关 | attach/detach PartySearchCoordinator |
| `Guild getGuild()` / `Alliance getAlliance()` | 公会/联盟对象 | 经 Server 单例解析，异常吞掉返回 null |
| `GuildCharacter getMGC()` / `void setMGC(GuildCharacter)` | 公会侧镜像 | |
| `private void guildUpdate()` | 等级/职业变更推送公会 | `memberLevelJobUpdate` + 联盟包 |
| `void genericGuildMessage(int code)` | 公会通用错误码包 | |
| `void disbandGuild()` | 解散公会 | 仅 rank=1；`Server.disbandGuild` |
| `void deleteGuild(int guildId)` | 删公会记录 | characterService.deleteGuild |
| `void increaseGuildCapacity()` | 扩容（扣金币） | `Guild.getIncreaseGuildCost` 计价，余额不足红字提示 |
| `void saveGuildStatus()` | 公会状态落库 | guildid/rank/allianceRank |
| `boolean isGuildLeader()` | 会长或副会长 | `guildId>0 && guildRank<3` |
| `Family getFamily()` / `void setFamilyEntry(FamilyEntry)` | 家族引用 | set 时同步 familyId |
| `void setFamilyBuff(boolean type, float exp, float drop)` / `void startFamilyBuffTimer(int delay)` / `void cancelFamilyBuffTimer()` | 家族 2x buff | 定时结束发取消包并复位倍率 |
| `boolean isMarried()` / `boolean hasJustMarried()` | 婚姻判断 | 已婚=戒指+partnerId；刚刚结婚=婚礼 EIM 的 groomId/brideId 之一且在教堂祭坛图 |
| `Ring getMarriageRing()` / `Ring getRingById(int id)` / `List<Ring> getCrushRings()` / `getFriendshipRings()` / `void addPlayerRing(Ring)` | 戒指集合 | addPlayerRing 按 itemid 分类：婚戒 / >1112012 友情戒 / 其余情侣戒 |
| `int getRelationshipId()` | 婚姻关系-id | world.getRelationshipId |
| `boolean haveWeddingRing()` | 戴着四种婚戒之一 | |
| `void notifyMapTransferToPartner(int mapid)` | 换图通知配偶 | WeddingPackets.OnNotifyWeddingPartnerTransfer |
| `void broadcastMarriageMessage()` | 结婚公告 | 公会 + 家族广播 |
| `Marriage getMarriageInstance()` | 婚礼 EIM | 强转 |
| `Set<NewYearCardRecord> getNewYearRecords()` / `getReceivedNewYearRecords()` / `getNewYearRecord(int)` / `addNewYearRecord` / `removeNewYearRecord` | 新年贺卡 | |
| `void removeIncomingInvites()` | 清所有未决邀请（换图/下线） | InviteCoordinator.removePlayerIncomingInvites |

### 2.19 宠物与坐骑

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void addPet(Pet pet)` | 召唤宠物入槽 | petLock 下找 3 槽空位 |
| `void removePet(Pet pet, boolean shift_left)` | 移除宠物 | shift_left 时后续槽前移 |
| `Pet[] getPets()` / `Pet getPet(int index)` / `byte getPetIndex(int petId)` / `getPetIndex(Pet)` / `int getNoPets()` | 宠物槽访问 | 均持 petLock；index 无效返回 null/-1 |
| `void shiftPetsRight()` | 槽位右移补位 | 2 号槽空时才执行 |
| `void unEquipPet(Pet pet, boolean shift_left[, boolean hunger])` | 收回宠物 | 置未召唤并 saveToDb；注销饥饿任务；广播 showPet；`commitExcludedItems` 重发过滤表；petStatUpdate 包 |
| `void unEquipAllPets()` | 全收 | |
| `void runFullnessSchedule(int petSlot)` | 饱食度周期衰减 | −饥饿值；≤5 时强制饱食 15 并收回提示 |
| `void resetExcluded(int petId)` / `void addExcluded(int petId, int x)` / `Set<Integer> getExcludedForPet(int petId)` | 宠物拾取过滤（旧接口） | excluded: petId→Set<itemId> |
| `void loadPetExcludedItems(int petId)`（BeiDou 新增） | 召唤时从 DB 加载过滤配置 | `inventoryService.getPetIgnoreByPetId` 后内存整体替换，保证与库一致 |
| `void updatePetExcludedItems(int petId, Set<Integer>)`（BeiDou 新增） | 客户端提交过滤时的增量落库 | 计算差集 add/remove，避免保存时全量删写 |
| `void deletePetExcludedData(int petId)`（BeiDou 新增） | 宠物销毁同步清理 | DB 删 pets/petignores + 内存移除 |
| `Map<Integer, Set<Integer>> getExcluded()` / `Set<Integer> getExcludedItems()` / `void commitExcludedItems()` / `void exportExcludedItems(Client c)` | 过滤表读写与下发 | commit 先清 excludedItems 再逐宠物 loadExceptionList |
| `int getPetEquipItemId(byte petIndex)` / `boolean hasPetNameTag(byte)` / `hasPetChatballoon(byte)` / `isEquippedMesoMagnet(byte)` / `isEquippedItemPouch(byte)` / `isEquippedPetItemIgnore(byte)` | 宠物装备槽检查 | 按 `ItemConstants.PET_EQUIP_SLOTS` 各槽位查 EQUIPPED |
| `Mount mount(int id, int skillid)` | 更新坐骑物品/技能 | 只改 mapleMount 两字段并返回 |
| `boolean runTirednessSchedule()` | 坐骑疲劳周期 | +1 并广播；>99 钳 99、驱散骑乘技能并提示（返回 false 停任务） |

### 2.20 怪物控制、召唤兽与地图对象

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void controlMonster(Monster)` / `void stopControllingMonster(Monster)` | 登记怪物控制权 | cpnLock（tryLock 容忍失败）维护 `controlled` |
| `int getNumControlledMonsters()` / `Collection<Monster> getControlledMonsters()` | 控制数/快照 | |
| `void releaseControlledMonsters()` | 放弃全部控制 | 逐个 `aggroRedirectController` 重分配 |
| `void addSummon(int id, Summon summon)` | 登记召唤兽 | 木偶额外 `map.addPlayerPuppet` |
| `Collection<Summon> getSummonsValues()` / `Summon getSummonByKey(int)` / `void clearSummons()` / `boolean isSummonsEmpty()` / `boolean containsSummon(Summon)` | 召唤兽表访问 | |
| `void addVisibleMapObject(MapObject)` / `removeVisibleMapObject(MapObject)` / `MapObject[] getVisibleMapObjects()` / `boolean isMapObjectVisible(MapObject)` | 可见对象集 | ConcurrentHashMap 背书的 Set |
| `void setPlayerAggro(int mobHash)` / `void resetPlayerAggro()` | Boss 血条占用（借 serverMessage 通道） | aggro 记录 hash+时间；reset 时恢复服务器消息 |
| `int getObjectId()` / `MapObjectType getType()` / `void sendDestroyData(Client)` / `void sendSpawnData(Client)` / `void setObjectId(int)` / `String toString()` | MapObject 协议实现 | objectId=角色-id；type=PLAYER；spawn 时隐身仅 GM 可见并给 GM 广播 DARKSIGHT 标记 |

### 2.21 传送石（Trock）与杂项小状态

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `List<Integer> getTrockMaps()` / `getVipTrockMaps()` | 普通/VIP 传送石地图表 | |
| `int getTrockSize()` / `getVipTrockSize()` | 已用格数 | 首个 NONE 位置即大小 |
| `void deleteFromTrocks(int map)` / `deleteFromVipTrocks(int map)` | 删除记忆点 | 删除后补 NONE 保持长度 |
| `void addTrockMap()` / `addVipTrockMap()` | 记忆当前地图 | 找第一个空格写入 |
| `boolean isTrockMap(int id)` / `isVipTrockMap(int id)` | 已记忆判断 | |
| `void changeCI(int type)` / `changePage(int)` / `changeTab(int)` / `changeType(int)` | 商城浏览位置 | |
| `void equipChanged()` | 装备变更广播 | 广播外形更新、置 equipchanged 脏标记、`updateLocalStats`、信使更新 |
| `void changeFaceExpression(int emote)` | 表情限速广播 | 1.5s 内仅一次 |
| `int addDojoPointsByMap(int mapId)` | 道场积分 | <17000 分时 1+层数/6，非组队道场再+1 |
| `void addMesosTraded(int gain)` | 交易金币流水 | |
| `void decreaseReports()` | 举报次数递减 | 初始 10 |
| `boolean attemptCatchFish(int baitLevel)` | 钓鱼判定 | 需开钓鱼系统、钓鱼区图、Y>0、钓鱼椅、world 注册成功 |
| `int getMiniGamePoints(MiniGameResult, boolean omok)` / `void setMiniGamePoints(Character, int, boolean)` | 五子棋/翻牌胜负平计数 | |
| `void setRPS(RockPaperScissor)` / `void closeRPS()` | 猜拳会话 | dispose 后置 null |
| `int getCP()` / `setCP(int)` / `int getTotalCP()` / `setTotalCP(int)` / `void gainCP(int)` / `resetCP()` / `void gainFestivalPoints(int)` | 怪物嘉年华 CP | gainCP 联动 MCPQ 队伍 CP 与本人/队伍 CPUpdate 包；reset 清 monsterCarnival |
| `void updateAriantScore([int dropQty])` / `void gainAriantPoints(int)` | 阿里安竞技场比分 | 按「精灵宝石」持有数更新；掉落时补记 lostShards |
| `void setCpqTimer(ScheduledFuture<?>)` / `void clearCpqTimer()` | MCPQ 计时器 | |
| `void createDragon()` | 生成龙（Evan） | `new Dragon(this)` |
| `void setTeam(int)` / `long getLastSnowballAttack()` / `void setLastSnowballAttack(long)` | 活动队伍/雪球攻击冷却 | |
| `void startMapEffect(String msg, int itemId[, int duration])` | 地图横幅 | 默认 30s 后发销毁包 |
| `void showUnderLeveledInfo(Monster)` / `void showMapOwnershipInfo(Character)` | 打低级怪/占地提示 | 1 分钟限速 |
| `int getRelationshipId()`（见 2.18） | | |

### 2.22 存档与加载（DB 持久化）

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `boolean insertNewChar(CharacterFactoryRecipe recipe)` | 建角落库 | 按配方设属性/SP/物品/技能；`rescueGaga` 事件初始化；单事务（READ_UNCOMMITTED）：INSERT characters（取自增 id）→ keymap → quickslot（账号级 UPSERT，未变更跳过）→ `ItemFactory.INVENTORY.saveItems` → skills；失败回滚 |
| `void saveCharToDB()` | 存档入口（自动保存调度） | `use_autosave` 时注册到 `CharacterSaveService`（世界级保存服务排队），否则直接 `saveCharToDB(true)` |
| `synchronized void saveCharToDB(boolean notAutosave)` | **存档核心（UPDATE 版，实际生效路径）** | 未登录时注销保存任务直接返回；先 `Server.updateCharacterEntry`；单事务：56 字段 UPDATE characters（地图落 ForcedReturn/死亡回城规则，spawnpoint 特判 Crimsonwood）；`monsterBook.saveCards`；宠物 saveToDb；keymap 全删全写；quickslot UPSERT；技能宏；`ItemFactory.saveItems`；skills REPLACE；savedlocations/trocklocations(普通+VIP)/buddies(pending=0)/area_info/eventstats；任务三表全删全写（queststatus/questprogress/medalmaps，逐条取生成键）；家族声望（本人+两级离线长辈）；CashShop.save；用过的 storage.saveToDB。异常回滚并 error 日志 |
| `static Character loadCharFromDB(int cid, Client client, boolean channelServer)` | 读档入口 | 委托 `characterService.loadCharFromDB`；异常记日志返回 null |
| `static Character fromCharactersDO(CharactersDO, Client)`（BeiDou 重写） | **完整读档**（DO→内存） | 基础字段逐一拷贝；sp 逗号串拆 10 槽；逐背包 `inventoryService.getInventoryList` 还原物品（宠物召唤状态恢复并 `loadPetExcludedItems`；带 ringid 的装备加载 Ring 并 equip）；`commitExcludedItems`；SANDBOX 位检测；婚姻关系校验（无 partner 清 marriageItemId）；新年卡；trock 15 行按 vip 分桶补 NONE；账号名/角色槽/语言；area_info；eventstats（RescueGaga）；CashShop；AutobanManager；linked 角色（同账号最高级）；Mount（按 -18 槽）；quickslot（账号级 Long→bytes） |
| `static CharactersDO toCharactersDO(Character)`（BeiDou 新增，未完成） | 内存→DO（部分字段） | 双锁下拷属性/HP/MP/SP；地图与出生点规则同 save；**标注 todo 未完成**，不是实际存档路径 |
| `static Character loadCharacterEntryFromDB(ResultSet rs, List<Item> equipped)` | 轻量角色条目（排名/列表用） | 只填基础字段+已装备栏 |
| `Character generateCharacterEntry()` | 从完整角色裁出轻量条目 | 供角色列表展示 |
| `static boolean deleteCharFromDB(Character player, int senderAccId)` | 删角 | `characterService.deleteCharFromDB` 后 `Server.deleteCharacterEntry` 清缓存（防角色槽满不能重建） |
| `static boolean canCreateChar(String name)` | 建角名校验 | 屏蔽词表 + `existName` + 正则 `[a-zA-Z0-9\u4e00-\u9fa5]{2,12}`（支持中文） |
| `static boolean existName(String name)` | 名字占用 | characterService.findByName + 改名请求队列 |
| `static int getIdByName(String name)` / `static String getNameById(int id)` / `static int getAccountIdByName(String name)` | 名-id 互查（JDBC） | 失败 -1/null |
| `void updateMacros(int position, SkillMacro)` / `void sendMacros()` | 技能宏写/发 | 发包恒发（修切换角色客户端 bug） |
| `void changeKeybinding(int key, KeyBinding)` / `void changeQuickslotKeymapping(byte[])` / `void sendKeymap()` / `void sendQuickmap()` | 键位/快捷栏 | type=0 视为删除；quickslot 无配置时发默认 |

### 2.23 交互清理、消息与广播

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void closePlayerInteractions()` | 总清理（换图/下线/进商城） | 依次 closeNpcShop/closeTrade/closePlayerShop/closeMiniGame/closeRPS/closeHiredMerchant/closePlayerMessenger + client.closePlayerScriptInteractions + resetPlayerAggro |
| `void closeNpcShop()` / `closeTrade()` / `closePlayerShop()` / `closeMiniGame(boolean)` / `closeHiredMerchant(boolean)` / `closePlayerMessenger()` | 各子系统关闭 | 商店：店主回收余货并 closeShop，访客 removeVisitor；雇佣商店区分封禁关闭/未发布店主关闭/普通；信使 leaveMessenger |
| `void dropMessage([int type,] String message)` / `void message(String)` / `void yellowMessage(String)` | 玩家消息 | type 语义见注释（0 蓝字/1 弹窗/5 红字/6 黄字）；黄条走 sendYellowTip |
| `void showHint(String msg[, int length])` | 顶部提示 | 委托 client.announceHint |
| `void broadcastAcquaintances(int type, String)` / `broadcastAcquaintances(Packet)` | 熟人广播（好友+家族+公会+自己） | |
| `void broadcastStance([int newStance])` | 广播站姿/位移 | movePlayer 包（IDLE_MOVEMENT 截断版） |
| `void updateSingleStat(Stat stat, int newval)` | 单项属性包 | updatePlayerStats 单元素 |
| `void sendPacket(Packet packet)` | 下发封包 | client 判空调 sendPacket |
| `void enableActions()` | 解除客户端假死 | enableActions 包 |
| `AbstractPlayerInteraction getAbstractPlayerInteraction()` | 脚本交互桥 | new 一个基于当前 client 的交互对象 |
| `void reapplyLocalStats()` / `List<Pair<Stat,Integer>> recalcLocalStats()` / `private void updateLocalStats()` | 本地属性重算 | reapply：基础值+装备缓存+HYPERBODY+枫叶战士+能量条+弓系专家+WATK/MATK buff+精灵祝福+投掷物 ATK，HP/MP 上限钳 30000，法师 magic 钳 2000；recalc 额外按 `use_fixed_ratio_hpmp_update` 做 HP/MP 等比换算（transientHp/Mp 浮点过渡）；updateLocalStats 再叠加 enforceMaxHpMp、发包与组队 HP 通知 |
| `void updateAriantScore`（见 2.21） | | |

### 2.24 移动/传送距离反作弊上下文（BeiDou 新增）

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void markTeleportLikeMove()` / `synchronized void markTeleportLikeMove(Point beforePos, Point afterPos)` | 标记一次瞬移位移 | 双坐标版在位移 ≥40px（距离平方 1600）时建立上下文：记录前后坐标、地图-id、1.2s 过期、最多保护 2 次攻击校验 |
| `synchronized void markRegularMove(Point, Point)` | 记录普通移动（350ms 窗口/1 次校验/≥20px） | |
| `long getLastTeleportLikeMoveTime()` | 最近瞬移时刻（单调纳秒） | |
| `synchronized Point getTeleportBeforePositionForDistanceCheck()` / `getMovementBeforePositionForDistanceCheck()` | 取校验用「移动前坐标」 | 上下文失效（超时/跨图/次数耗尽）自动清理并返回 null |
| `void setPetLootTeleportBeforePos(Point)` / `Point getPetLootTeleportBeforePos()` | 宠物拾取传送补偿坐标 | 内传送门触发时记录，1.5s 有效防旧位置捡包误判 |
| `synchronized void consumeTeleportDistanceCheckContext()` / `consumeMovementDistanceCheckContext()` | 消费一次保护次数 | 归零清上下文 |
| `synchronized void clearTeleportDistanceContext()` | 显式清空（换图调用） | 同时清传送+移动上下文与时间戳 |

### 2.25 内部类型

| 类型 | 说明 |
|---|---|
| `private static class BuffStatValueHolder` | buff 记录：`effect/startTime/value/bestApplied`（bestApplied 标记被取消后需重新选优） |
| `public static class CooldownValueHolder` | 冷却记录：`skillId/startTime/length` |
| `public enum SkillWindowResult` | 攻击窗口判定：`PASS / STABLE_HACK / BURST` |
| `static final class AttackWindow` | 10 格环形缓冲：`push/isFull/avg/stddev` 全 synchronized，60s 无写入自动重置；常量 `WINDOW_SIZE=10、STABLE_CV=0.3、MIN_INTERVAL=50、NORMAL_AVG=250` |

### 2.26 生命周期收尾

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void empty(boolean remove)` | 清定时器与引用（登出） | 取消全部 15 个 ScheduledFuture；remove=true 时清 PQ/events/mpc/mgc/party/family 引用，并注册 5 分钟延迟任务置空 client/map/listener、dispose 全部背包（防 Netty handler 迟到包 NPE 与背包持有者内存泄漏） |

---

## 3. Client（客户端会话）

**类声明**：`public class Client extends ChannelInboundHandlerAdapter`（1646 行）

**概述**：一条 Netty 连接对应一个 Client，同时就是该连接的 inbound handler。管理账号登录态（loggedin 状态机 + DB 落库）、PIN/PIC 二级验证、封禁检查（IP/HWID/MAC）、多开协调、频道/世界上下文、脚本引擎缓存与断线清理。角色未登录时 `player == null`。

**关键字段**：

| 字段 | 说明 |
|---|---|
| `type`（LOGIN/CHANNEL）、`sessionId`、`packetProcessor` | 会话类型、会话-id、封包分发器 |
| `ioChannel`、`remoteAddress`、`hwid`、`macs` | Netty 通道、对端 IP、硬件指纹、MAC 集 |
| `player`、`channel=1`、`world`、`accId=-4`、`accountName`、`gmlevel` | 角色与账号上下文 |
| `loggedIn`、`serverTransition`、`inTransition`（volatile） | 登录状态机；LOGIN_NOTLOGGEDIN=0 / LOGIN_SERVER_TRANSITION=1 / LOGIN_LOGGEDIN=2 |
| `birthday`、`gender`、`pin/pinattempt`、`pic/picattempt`、`csattempt`、`loginattempt` | 账号资料与各验证尝试计数 |
| `characterSlots=3`、`votePoints/voteTime`、`tempBanCalendar`、`visibleWorlds`、`lang` | 槽位/投票/封禁/语言 |
| `lastPong`、`lastPacket`、`lastNpcClick` | 心跳与限速时间戳 |
| `actionsSemaphore(7)`、`lock`、`encoderLock`、`announcerLock` | 三把公平锁 + 信号量防死锁/瓶颈 |
| `engines: Map<String,ScriptEngine>` | 脚本引擎缓存（断线置 null） |
| `disconnecting`、`sysRescue`（static @Getter） | 断线一次性标记；系统救援器（卡地图玩家） |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `Client(Type, long, String, PacketProcessor, int, int)`（构造） | 初始化会话元数据 | 类型/会话-id/远端地址/分发器/世界/频道 |
| `static Client createLoginClient(...)` / `createChannelClient(...)` / `createMock()` | 工厂方法 | Mock 用 null/-123 供离线测试 |
| `void channelActive(ChannelHandlerContext ctx)` | 连接建立 | 服务器不在线直接 close；记录 remoteAddress 与 ioChannel |
| `void channelRead(ChannelHandlerContext ctx, Object msg)` | **上行封包分发核心** | 仅接受 InPacket；读 opcode；`use_debug_show_rcvd_packet` 时 debug 日志；`handler.validateState` 通过后 `ThreadLocalUtil.setCurrentClient(this)` → 监控号日志 → `handlePacket`；异常统一 warn（带玩家/地图上下文）并 `enableActions()` 解假死；finally 清 ThreadLocal；刷新 lastPacket |
| `void userEventTriggered(ChannelHandlerContext, Object)` | 空闲事件 | IdleStateEvent 转 `checkIfIdle` |
| `void exceptionCaught(ChannelHandlerContext, Throwable)` | 异常处理 | 玩家不在世界（卡图）时经 `sysRescue.setMapChange` 救援并 warn；非法包头关会话；IOException 走 closeMapleSession |
| `void channelInactive(ChannelHandlerContext)` | 连接断开 | `closeMapleSession` |
| `private void closeMapleSession()` | 会话关闭编排 | 按类型走 Login/普通 closeSession；非过渡态执行 disconnect(false,false)（异常仅 warn「账号卡住」）；finally closeSession |
| `void updateLastPacket()` / `long getLastPacket()` | 最后封包时间 | |
| `void closeSession()` / `void disconnectSession()` | 关闭/断开 Netty 通道 | |
| `Hwid getHwid()` / `void setHwid(Hwid)` / `String getRemoteAddress()` / `boolean isInTransition()` | 会话属性 | |
| `EventManager getEventManager(String event)` | 取事件管理器 | 频道 EventSM |
| `Character getPlayer()` / `void setPlayer(Character)` | 角色绑定 | set 时顺带 `new SystemRescue()` |
| `AbstractPlayerInteraction getAbstractPlayerInteraction()` | 脚本交互桥 | |
| `void sendCharList(int server)` | 发角色列表 | PacketCreator.getCharList |
| `List<Character> loadCharacters(int serverId)` / `List<String> loadCharacterNames(int worldId)` | 账号角色加载 | 私有 `loadCharactersInternal` 查 characters 表（accountid+world），逐个 `Character.loadCharFromDB` |
| `boolean isLoggedIn()` | 登录标志 | |
| `boolean hasBannedIP()` | IP 封禁检查 | `remoteAddress LIKE ip%` 前缀匹配 ipbans |
| `int getVoteTime()` / `void resetVoteTime()` / `boolean hasVotedAlready()` | 投票时间 | 查 bit_votingrecords；24h（86400s）内算已投 |
| `boolean hasBannedHWID()` / `hasBannedMac()` | HWID/MAC 封禁检查 | MAC 动态拼 IN(...) 占位符查 macbans |
| `private void loadHWIDIfNescessary()` / `loadMacsIfNescessary()` | 懒加载 HWID/MAC | 从 accounts 表补齐 |
| `void banHWID()` / `void banMacs()` | 封禁硬件 | MAC 先过 macfilters 正则白名单 |
| `int finishLogin()` | 完成账号登录（encoderLock） | 状态 >0 视为重复登录返回 7；否则置 LOGIN_LOGGEDIN 返回 0 |
| `void setPin(String)` / `String getPin()` / `boolean checkPin(String)` | PIN 设置/校验 | set 落库；check 需 `enable_pin` 且未绕过；>5 次关会话；成功注册 LoginBypass 并清计数 |
| `void setPic(String)` / `String getPic()` / `boolean checkPic(String)` | PIC 同上 | `enable_pic`/canBypassPic |
| `int login(String login, String pwd, Hwid hwid)` | **账号登录核心** | 尝试 >4 次关会话返 6；查 accounts（banned/pin/pic/gender/slots/tos/language）；封禁返 3；已登录返 7；debug+no_password 直通 0；BCrypt 校验通过按 tos 返 0/23；旧 SHA-1/SHA-512/明文按 `bcrypt_migration` 返 0/-10/23/-23（引导迁移）；密码错返 4；账号不存在 accId=-3；loginok∈{0,4} 时经 `SessionCoordinator.attemptLoginSession` 多开仲裁映射 17/13/10/16/8 |
| `Calendar getTempBanCalendarFromDB()` / `getTempBanCalendar()` / `boolean hasBeenBanned()` | 临时封禁 | DB 时间等于 `DefaultDates.getTempban()` 视为未封 |
| `static long dottedQuadToLong(String)` | IP 字符串转 long | 4 段 256 进制叠加 |
| `void updateHwid(Hwid)` / `void updateMacs(String)` | 硬件信息回写 DB | |
| `void setAccID(int)` / `int getAccID()` | 账号-id | |
| `void updateLoginState(int newState)` | 登录态落库 + 内存同步 | LOGIN_LOGGEDIN 时 `SessionCoordinator.updateOnlineClient`；NOTLOGGEDIN 清 accId 与标志 |
| `int getLoginState()` | 读登录态（含自愈） | 查 DB loggedin；TRANSITION 超 30s 视为僵尸自动复位 NOTLOGGEDIN（恢复 accId）；读到 LOGGEDIN 时同步 loggedIn=true |
| `boolean checkBirthDate(Calendar date)` | 生日校验 | 年月日全等 |
| `private void removePartyPlayer(World)` | 退组队 | LOG_ONOFF 上线状态更新；队长离线时按等级选同图继任者 CHANGE_LEADER |
| `private void removePlayer(World, boolean serverTransition)` | 下线玩家清理 | 玩家侧：setDisconnectedFromChannelWorld、通知配偶、清邀请、取消 buff、关闭交互；非换线时：退组/EIM playerDisconnected/MCPQ/阿里安离场；**地图移除独立 try 块**（BeiDou 修复：即使前面清理抛异常也必须把玩家从地图摘除并重分配怪物 controller，防「幽灵玩家」卡死怪物）；道场空房释放、危险图注销掉血 |
| `final void disconnect(boolean shutdown, boolean cashshop)` | 断线入口（异步） | `canDisconnect` 一次性 CAS 后交 ThreadManager 执行 `disconnectInternal` |
| `final void forceDisconnect()` / `void timeoutDisconnect()` | 强断/超时断 | 前者同步执行 |
| `private void disconnectInternal(boolean, boolean)` | **断线核心（每实例一次）** | 玩家在线时：取消魔法门、`updateOnlineTime`、removePlayer；非关服非 -1 频道：非商城且非换线时退信使/弃限时任/公会下线/好友 loggedOff；finally：非换线时 removePlayer、saveCooldowns、cancelAllDebuffs、**saveCharToDB(true)**、logOff、（`instant_name_change` 时 doPendingNameChange）、clear()；换线仅 removePlayer+保存；最后关会话，非过渡态复位登录态并清引用（engines=null 防 NPE） |
| `private void clear()` | 引用清除 | player.empty(true)、注销登录态、置空账号名/MAC/HWID/生日/引擎/角色 |
| `void setCharacterOnSessionTransitionState(int cid)` | 进入换线过渡 | 状态=SERVER_TRANSITION、inTransition=true、登记过渡角色-id |
| `int getChannel()` / `void setChannel(int)` / `Channel getChannelServer()` / `getChannelServer(byte)` / `World getWorldServer()` / `int getWorld()` / `void setWorld(int)` | 频道/世界上下文 | |
| `boolean deleteCharacter(int cid, int senderAccId)` | 删角（带退队修复） | 读档后若在某队：临时 setPlayer→setParty→leaveParty→置回 null；再 `Character.deleteCharFromDB` |
| `String getAccountName()` / `void setAccountName(String)` | 账号名 | |
| `void pongReceived()` / `void checkIfIdle(IdleStateEvent)` | 心跳 | 发 ping 后 15s 内未 pong 则 `closeMapleSession`（BeiDou 改为走正常移除流程防内存残留） |
| `Set<String> getMacs()` / `int getGMLevel()` / `void setGMLevel(int)` | MAC/GM 等级 | |
| `void setScriptEngine(String, ScriptEngine)` / `getScriptEngine(String)` / `removeScriptEngine(String)` | 脚本引擎缓存 | |
| `NPCConversationManager getCM()` / `QuestActionManager getQM()` | 当前 NPC/任务脚本会话 | |
| `boolean acceptToS()` | 接受服务条款 | 已接受仍重复接受则返回 true（触发断线）；否则置 tos=1 |
| `void checkChar(int accid)` | 同账号重复登录踢出 | `use_character_account_check` 开启时全世界扫同账号在线角色 forceDisconnect |
| `int getVotePoints()` / `void addVotePoints(int)` / `void useVotePoints(int)` | 投票点 | use 不足直接忽略并 log（MapleLeafLogger） |
| `void lockClient()` / `unlockClient()` / `boolean tryacquireClient()` / `void releaseClient()` / `boolean tryacquireEncoder()` / `void unlockEncoder()` | 动作/编码器锁协议 | 先 tryAcquire 信号量再拿锁，释放逆序；防多线程并发操作同一客户端 |
| `short getAvailableCharacterSlots()` / `getAvailableCharacterWorldSlots([int world])` / `short getCharacterSlots()` / `void setCharacterSlots(byte)` / `boolean canGainCharacterSlot()` / `synchronized boolean gainCharacterSlot()` | 角色槽管理 | 总槽/世界槽=容量−已建数；上限 15；扩容落库 |
| `byte getGReason()` | 封禁原因码 | accounts.greason |
| `byte getGender()` / `void setGender(byte)` | 性别（落库） | |
| `private void announceDisableServerMessage()` / `void announceServerMessage()` | 服务器滚动条开关 | Boss 血条借道 serverMessage 时禁用 |
| `synchronized void announceBossHpBar(Monster, int mobHash, Packet)` | Boss 血条广播（限速） | 目标不变只刷新时间；目标变化 5s 内不重发（防客户端线程抖动） |
| `void sendPacket(Packet packet)` | **下行封包唯一出口** | announcerLock 下 writeAndFlush |
| `void announceHint(String msg, int length)` | 提示 + enableActions | |
| `void changeChannel(int channel)` | **换频道全流程** | 封禁/死亡/迁移限制/迷你地牢/目标频道禁用检查（各自 enableActions 退出）；关闭交互；buff/疾病存入 PlayerBuffStorage 供对侧恢复；取消 buff/任务/冷却定时器；EQUIPPED 背包 `checked(false)` 校验；从地图与频道移除、清放逐数据、saveCharToDB；`change_channel_force_return` 可选强制落 ForcedReturn 地图；置过渡态后发 ChannelChange 包 |
| `long getSessionId()` | 会话-id | |
| `boolean canRequestCharlist()` / `boolean canClickNPC()` / `void setClickedNPC()` / `void removeClickedNPC()` | NPC 点击/列表请求限速 | 500ms/877ms 窗口 |
| `int getVisibleWorlds()` / `void requestedServerlist(int worlds)` | 服务器列表状态 | |
| `void closePlayerScriptInteractions()` | 关闭 NPC/任务脚本 | dispose 两个 ScriptManager |
| `boolean attemptCsCoupon()` / `void resetCsCoupon()` / `void enableCSActions()` | 商城券尝试次数（>2 拒绝） | |
| `boolean canBypassPin()` / `canBypassPic()` | 登录绕过（已验证过） | LoginBypassCoordinator |
| `int getLanguage()` / `void setLanguage(int)` | 账号语言 | |
| `void enableActions()` | 解假死包 | |
| `private static class CharNameAndId` | 内部值对象（name/id） | 与 client 包同名类区分 |

---

## 4. AbstractCharacterObject（角色属性基类）

**类声明**：`public abstract class AbstractCharacterObject extends AbstractAnimatedMapObject`（766 行，HeavenMS 遗留）

**概述**：把「四维属性 + HP/MP 池 + AP/SP」统一到读写锁模型，并提供 `changeStatPool` 属性变更引擎：所有属性变更被打包成 4×16bit 的 long 池，一次性应用、一次性生成 `statUpdates`、统一走监听器回调（重算本地属性/广播）。Character 与（将来可能的）其它玩家型对象共用。

**关键字段**：

| 字段 | 说明 |
|---|---|
| `map`（@Setter @Getter） | 所在地图 |
| `attrStr/attrDex/attrLuk/attrInt`、`hp/maxHp/mp/maxMp` | 基础属性（statRlock/statWlock 保护） |
| `hpMpApUsed`、`remainingAp`（@Setter）、`remainingSp[10]` | AP/SP（按技能书分桶） |
| `clientMaxHp/clientMaxMp`（transient，@Getter） | 客户端上限（≤30000） |
| `localMaxHp=50/localMaxMp=5`（transient） | 本地（buff 后）上限 |
| `transientHp/transientMp` | HP/MP 等比换算的浮点过渡值（NEGATIVE_INFINITY 表示无过渡） |
| `listener` | AbstractCharacterListener 回调 |
| `statUpdates: Map<Stat,Integer>` | 本次变更待下发集合 |
| `effLock`（公平）、`statRlock/statWlock`（公平读写锁） | 并发控制 |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `protected AbstractCharacterObject()`（构造） | 初始化锁与 SP 数组 | `remainingSp` 全 0 |
| `protected void setListener(AbstractCharacterListener)` | 注入监听器 | |
| `int getStr()` / `getDex()` / `getInt()` / `getLuk()` / `getRemainingAp()` / `getHp()` / `getMp()` / `getMaxHp()` / `getMaxMp()` / `getHpMpApUsed()` | 读锁下读属性 | |
| `protected int getRemainingSp(int jobid)` | 按职业取对应书 SP | `GameConstants.getSkillBook` |
| `int[] getRemainingSps()` | SP 全量拷贝 | 防外部改内部数组 |
| `boolean isAlive()` | hp>0 | |
| `int getCurrentMaxHp()` / `getCurrentMaxMp()` | 本地（buff 后）上限 | 直接返回 localMax* |
| `private void dispatchHpChanged/onHpMpPoolUpdated/onStatUpdate/onAnnounceStatPoolUpdate()` | 监听器分发 | |
| `protected void setHp(int)` / `setMp(int)` | 落位钳制写 | 钳 [0, localMax]；变化时清 transient 浮点；setHp 触发 onHpChanged |
| `protected void setMaxHp(int)` / `setMaxMp(int)` | 写上限 | 上限抬升清 transient；clientMax 钳 30000 |
| `void setRemainingSp(int remainingSp, int skillbook)` | 写单书 SP | |
| `private static long clampStat(int,int,int)` / `calcStatPoolNode(Integer,int)` / `calcStatPoolLong(Integer,Integer,Integer,Integer)` | 4×16bit 属性池打包 | null→Short.MIN_VALUE 哨兵（表示「不变」），值钳 [-32767,32767] |
| `private void changeStatPool(Long hpMpPool, Long strDexIntLuk, Long newSp, int newAp, boolean silent)` | **属性变更引擎核心** | effLock+statWlock；解析两个 long：HP/MP 池按哨兵选择性更新（MAXHP<50 钳 50、MAXMP<5 钳 5），四维 <4 拒绝；SP 池写指定书；每次写入都登记 statUpdates；pool 变化→onHpMpPoolUpdate、四维变化→onStatUpdate、非 silent→onAnnounceStatPoolUpdate（下发 updatePlayerStats） |
| `void healHpMp()` | 回满 | updateHpMp(30000) |
| `void updateHpMp(int x)` / `updateHpMp(int,int)` / `changeHpMp(int,int,boolean)` | HP/MP 双改 | |
| `private void changeHpMpPool(Integer hp, Integer mp, Integer maxhp, Integer maxmp, boolean silent)` | HP/MP 池打包进引擎 | |
| `void updateHp(int)` / `updateMaxHp(int)` / `updateHpMaxHp(int,int)` / `updateMp(int)` / `updateMaxMp(int)` / `updateMpMaxMp(int,int)` / `updateMaxHpMaxMp(int,int)` | 单/组合便捷入口 | 均以 null 哨兵走 changeHpMpPool |
| `protected void enforceMaxHpMp()` | 超上限钳回 | hp/mp 超 localMax 时 changeHpMp |
| `int safeAddHP(int delta)` | 不会致死的加血 | 加完 ≤0 时调整 delta 保 1 HP，返回实际值 |
| `void addHP(int)` / `addMP(int)` / `addMPHP(int,int)` / `addMaxHP(int)` / `addMaxMP(int)` / `protected void addMaxMPMaxHP(int,int,boolean)` | 增量修改 | 全部持双锁走 update* |
| `void setStr(int)` / `setDex(int)` / `setInt(int)` / `setLuk(int)` | 裸写（读档用） | 不走引擎、不发包 |
| `boolean assignStr(int)` / `assignDex(int)` / `assignInt(int)` / `assignLuk(int)` | 单维加点 | 转发四维联合方法 |
| `boolean assignHP(int deltaHP, int deltaAp)` / `assignMP(int deltaMP, int deltaAp)` | 手动加 HP/MP 上限 | AP 不足/已用为负/达 30000 拒绝；成功扣 AP 并记 hpMpApUsed |
| `boolean assignStrDexIntLuk(int,int,int,int)` | 四维联合加点 | 总消耗 >remainingAp 拒绝；每维结果必须 ∈[4, `max_ap`]（GameConfig）；`updateStrDexIntLuk` 入引擎 |
| `void updateStrDexIntLuk(int x)`（四值同设） / `protected void updateStrDexIntLuk(int,int,int,int,int)` / `private void changeStrDexIntLuk(...)` / `changeStrDexIntLukSp(...)` / `protected void updateStrDexIntLukSp(int×7)` | 四维（+SP）变更入口 | 打包进 changeStatPool |
| `void changeRemainingAp(int x, boolean silent)` | 直接改剩余 AP | 连带四维重打包（AP 与四维同包下发） |
| `void gainAp(int deltaAp, boolean silent)` | AP 增减 | 下限 0 |
| `protected void setRemainingSp(int[] sps)` | 整表覆盖（读档） | arraycopy |
| `protected void updateRemainingSp(int, int)` / `changeRemainingSp(int, int, boolean)` | SP 变更 | SP 池打包（AP 传哨兵不动） |
| `void gainSp(int deltaSp, int skillbook, boolean silent)` | SP 增减 | 下限 0 |

---

## 5. 监听器

### 5.1 AbstractCharacterListener（接口）

**概述**：HeavenMS 遗留的 4 方法接口，属性引擎（`AbstractCharacterObject`）向宿主角色回调的出口。

| 方法签名 | 作用 |
|---|---|
| `void onHpChanged(int oldHp)` | HP 变化（含死亡判定入口） |
| `void onHpMpPoolUpdate()` | HP/MP 池上限变化（重算本地属性并钳当前值） |
| `void onStatUpdate()` | 四维属性变化（重算本地属性） |
| `void onAnnounceStatPoolUpdate()` | 下发 statUpdates 集合 |

### 5.2 CharacterListener（implements AbstractCharacterListener）

**概述**：接口在 Character 上的实现，构造时持有宿主 `Character`。

**关键字段**：`character`（final）。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void onHpChanged(int oldHp)` | HP 变化 | `character.hpChangeAction(oldHp)`（死亡/狂战士判定，注册到地图延迟任务） |
| `void onHpMpPoolUpdate()` | 池上限变化 | 取 `recalcLocalStats()` 返回的更新对写入 `character.statUpdates`；hp/mp 超新上限时钳回并登记 |
| `void onStatUpdate()` | 四维变化 | 仅 `recalcLocalStats()` |
| `void onAnnounceStatPoolUpdate()` | 下发 | 把 statUpdates 组 `Pair` 列表以 `updatePlayerStats(..., true, this)` 发出 |

---

## 6. 技能体系

### 6.1 Skill

**概述**：一个技能定义：多级 `StatEffect` 列表 + 元素属性 + 动画时长。由 SkillFactory 装配，运行期只读。

**关键字段**：`id`（final）、`effects: List<StatEffect>`（按等级序）、`element: Element`、`animationTime`、`job = id/10000`（final）、`action`（是否有攻击动作）。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `Skill(int id)`（构造） | 由技能-id 推职业 | |
| `int getId()` | 技能-id | |
| `StatEffect getEffect(int level)` | 取某级效果 | `effects.get(level-1)`（调用方保证范围） |
| `int getMaxLevel()` | 最大等级 | effects.size() |
| `boolean isFourthJob()` | 是否 4 转 | Evan 2212 例外为 false；五个 Evan 特殊技（22170001 等）例外为 true；否则 `job%10==2` |
| `void setElement(Element)` / `Element getElement()` | 元素属性 | |
| `int getAnimationTime()` / `void setAnimationTime(int)` / `void incAnimationTime(int)` | 动画延迟合计（ms） | effect 各帧 delay 累加 |
| `boolean isBeginnerSkill()` | 初心者技能 | `id % 10000000 < 10000` |
| `void setAction(boolean)` / `boolean getAction()` | 动作标记 | |
| `void addLevelEffect(StatEffect effect)` | 装配用追加一级 | |

### 6.2 SkillFactory

**概述**：技能静态工厂。启动时一次性从 `WZFiles.SKILL` 装载全部技能到 `volatile Map`（整体替换保证可见性）。

**关键字段**：`skills`（volatile HashMap）、`datasource`（Skill.wz DataProvider）。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `static Skill getSkill(int id)` | 取技能 | map 直查 |
| `static void loadAllSkills()` | 全量装载 | 遍历 wz 根下文件名 ≤8 字符的职业目录 → `skill` 节点 → 每个子节点名即技能-id；全部装入临时 map 后整体替换 `skills` |
| `private static Skill loadFromData(int id, Data data)` | 单技能装配 | 元素取 `elemAttr` 首字符；`skillType==2` 判 buff；skillType 缺失时用启发式：有 effect 无 hit/ball、或 action 为 alert2 判 buff；随后两张巨型白名单（冲刺/治疗/隐身等强制非 buff；各类 booster/charge/guard 强制 buff，覆盖 v83 全技能表）；逐级 `StatEffect.loadSkillEffectFromData`；effect 各帧 delay 累加为 animationTime |
| `static String getSkillName(int skillid)` | 技能名（String.wz） | 4 位-id 补前导 0 拼路径；找 `name` 节点 |

---

## 7. 好友

### 7.1 BuddyList

**概述**：好友容器：可见好友表（LinkedHashMap，cid→entry）+ 待处理请求队列（Deque）+ 容量。所有对 `buddies` 的操作在 `synchronized(buddies)` 下进行。

**关键字段**：`buddies`、`capacity`、`pendingRequests`。内部枚举 `BuddyOperation{ADDED,DELETED}`、`BuddyAddResult{BUDDYLIST_FULL, ALREADY_ON_LIST, OK}`。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `BuddyList(int capacity)`（构造） | | |
| `boolean contains(int characterId)` / `boolean containsVisible(int characterId)` | 是否在列/是否可见 | |
| `int getCapacity()` / `void setCapacity(int)` | 容量 | |
| `BuddylistEntry get(int characterId)` / `get(String characterName)` | 按-id/名取 | 名字匹配忽略大小写 |
| `void put(BuddylistEntry entry)` / `void remove(int characterId)` | 增删 | |
| `Collection<BuddylistEntry> getBuddies()` | 只读视图 | unmodifiable |
| `boolean isFull()` | 满 | size≥capacity |
| `int[] getBuddyIds()` | id 数组快照 | |
| `void broadcast(Packet packet, PlayerStorage pstorage)` | 向在线好友广播 | 仅 `isLoggedInWorld()` 的好友 |
| `void loadFromDb(int characterId)` | 读档 | JOIN characters 取好友名；pending=1 进待处理队列、否则入表（channel=-1 离线）；**读完删除 pending 行**（请求弹出即消费） |
| `CharacterNameAndId pollPendingRequest()` | 弹一条待处理请求 | pollLast |
| `void addBuddyRequest(Client c, int cidFrom, String nameFrom, int channelFrom)` | 收到好友申请（BeiDou 增强） | **先落库**：给被加方 DELETE+INSERT 一条 pending=1（幂等，防下线/重启丢请求，与离线加好友对齐）；内存 put 不可见 entry；队列空则立即弹窗，否则入队 |

### 7.2 BuddylistEntry

**概述**：单个好友条目（名字/分组/cid/频道/可见性）。equals/hashCode 仅按 cid。

**关键字段**：`name`（final）、`group`、`cid`（final）、`channel`（-1=离线）、`visible`。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `BuddylistEntry(String name, String group, int characterId, int channel, boolean visible)`（构造） | | |
| `int getChannel()` / `void setChannel(int)` | 所在频道 | |
| `boolean isOnline()` | 在线判断 | `channel >= 0` |
| `String getName()` / `String getGroup()` / `void changeGroup(String)` / `int getCharacterId()` | 属性 | |
| `void setVisible(boolean)` / `boolean isVisible()` | 可见（删除后置 false 保留显示） | |
| `int hashCode()` / `boolean equals(Object)` | 以 cid 为键 | |

### 7.3 CharacterNameAndId

**概述**：id+name 二元组（好友请求、角色列表等通用载体）。

**关键字段**：`id`、`name`（均 final）。

| 方法签名 | 作用 |
|---|---|
| `CharacterNameAndId(int id, String name)`（构造） | |
| `int getId()` / `String getName()` | 只读访问 |

---

## 8. 家族

### 8.1 Family

**概述**：一个家族（ seniors/juniors 树的根容器 ）：成员表（ConcurrentHashMap）、族长、家训、总代数。id 分配用静态 `AtomicInteger`，跨世界查重。

**关键字段**：`id/world`（final）、`members`、`leader`、`name`（=族长名）、`preceptsMessage`、`totalGenerations`、静态 `familyIDCounter`。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `Family(int id, int world)`（构造） | id=-1 时自动分配 | 自增直到 `idInUse` 为 false（全世界查 family 表） |
| `private static boolean idInUse(int id)` | id 占用检查 | 遍历所有 World 的 getFamily |
| `int getID()` / `int getWorld()` | 标识 | |
| `void setLeader(FamilyEntry)` / `FamilyEntry getLeader()` | 族长 | set 时同步家族名 |
| `int getTotalMembers()` / `int getTotalGenerations()` / `void setTotalGenerations(int)` | 规模统计 | |
| `String getName()` | 家族名 | |
| `void setMessage(String message, boolean save)` / `String getMessage()` | 家训 | save 时 UPDATE family_character.precepts（按族长 cid） |
| `void addEntry(FamilyEntry)` | 加成员 | |
| `void removeEntryBranch(FamilyEntry root)` / `void addEntryTree(FamilyEntry root)` | 递归摘除/挂入整棵子树 | |
| `FamilyEntry getEntryByID(int cid)` | 查成员 | |
| `void broadcast(Packet)` / `broadcast(Packet, int ignoreID)` | 家族广播 | 在线成员逐个 sendPacket，可忽略一人 |
| `void Familybuff(int duration)` | 家族 2x buff | 全员 familyBuff 包 + `setFamilyBuff(true,2,2)` + 到期定时器 |
| `void broadcastFamilyInfoUpdate()` | 刷新全员家族面板 | getFamilyInfo 包 |
| `void resetDailyReps()` | 每日声望清零 | todaysRep/repsToSenior 归零、权益次数重置 |
| `void saveAllMembersRep()` | 全员声望事务保存 | 手动事务；失败回滚并 error；成功后逐个 `savedSuccessfully` 清脏标记 |

### 8.2 FamilyEntry

**概述**：家族树节点（一名成员）：上下级指针、声望四值、11 格权益使用计数、离线缓存（名/级/职业）。join/fork 负责整棵子树在家族间的迁移（内存 + 事务化 DB）。

**关键字段**：`characterID`（final）、`family`/`character`/`senior`（volatile）、`juniors[2]`（final 数组，每人最多 2 下级）、`entitlements[11]`、`reputation/totalReputation/todaysRep/repsToSenior`、`totalJuniors/totalSeniors`、`generation`、`repChanged`（脏标记）、离线缓存 `charName/level/job`。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `FamilyEntry(Family, int characterID, String charName, int level, Job)`（构造） | | |
| `Character getChr()` / `void setCharacter(Character)` | 绑定/解绑在线角色 | 绑定时反向 `character.setFamilyEntry(this)`；传 null 前缓存名字/等级/职业（离线展示） |
| `private void cacheOffline(Character)` | 离线缓存 | |
| `synchronized void join(FamilyEntry senior)` | 带整棵子树并入目标家族 | 无上级或参数空直接返回；setSenior、`addSeniorCount` 全子树世代数修正、新家族族长 `doFullCount()` 重算；旧家族清家训、从世界注册表移除；DB：事务内 `updateDBChangeFamily` + 递归 `updateNewFamilyDB` 改 juniors，失败回滚 |
| `synchronized void fork()` | 分家（自立门户） | 新建 Family(-1, world) 并注册世界；从旧上级摘除（`addJuniorCount(-n)`、removeJunior、旧族 doFullCount）；repsToSenior 清零并置脏；DB 同 join |
| `private synchronized boolean updateNewFamilyDB(Connection)` | 递归更新子树 familyid | family_character + characters 两表 |
| `private static boolean updateFamilyEntryDB(Connection, int cid, int familyid)` | 改 family_character.familyid | |
| `private synchronized void addSeniorCount(int, Family)` | 子树全体世代数平移 | 递归 juniors |
| `private synchronized void addJuniorCount(int)` | 沿祖先链累加下级计数 | |
| `Family getFamily()` / `int getChrId()` | 引用 | |
| `String getName()` / `int getLevel()` / `Job getJob()` | 在线实时/离线缓存二态取值 | |
| `int getReputation()` / `getTodaysRep()` / `void setReputation(int)` / `setTodaysRep(int)` / `int getRepsToSenior()` / `void setRepsToSenior(int)` / `int getTotalReputation()` / `void setTotalReputation(int)` | 声望读写 | 变化即置 `repChanged` |
| `void gainReputation(int gain, boolean countTowardsTotal)` | 获得声望 | 私有重载带来源名；todaysRep 同加；正向且计总时入 totalReputation；在线则 sendGainRep 包 |
| `void giveReputationToSenior(int gain, boolean includeSuperSenior)` | 孝敬长辈 | 上级等级低于自己时正收益减半（负不减）；计入自己的 repsToSenior；可再向祖辈传一份 |
| `FamilyEntry getSenior()` / `synchronized boolean setSenior(FamilyEntry, boolean save)` | 上级设置 | 同引用 false；新上级先 `addJunior` 成功才生效（save 时落库并清 reptosenior），世代链 addSeniorCount(1)；传 null 时从旧上级 removeJunior |
| `private static boolean updateDBChangeFamily(int cid, int familyid, int seniorid)` ×2 重载 | 家族/上级变更落库 | UPDATE family_character（reptosenior=0）+ characters.familyid |
| `private static boolean updateCharacterFamilyDB(Connection, int, int, boolean)` | characters 表 familyid | |
| `List<FamilyEntry> getJuniors()` | 下级只读视图 | Arrays.asList 包装 |
| `FamilyEntry getOtherJunior(FamilyEntry)` | 另一个下级 | |
| `int getJuniorCount()` | 下级数（弱一致） | |
| `synchronized boolean addJunior(FamilyEntry)` | 添下级 | 找空位；addJuniorCount(1)、family.addEntry、刷新「杰出市民」勋章资格 |
| `synchronized boolean isJunior(FamilyEntry)` | 是否直系下级 | 结果关键，须加锁 |
| `synchronized boolean removeJunior(FamilyEntry)` | 摘下级 | 同步刷新勋章资格 |
| `int getTotalSeniors()` / `setTotalSeniors(int)` / `int getTotalJuniors()` / `setTotalJuniors(int)` | 祖/后裔计数 | |
| `void announceToSenior(Packet, boolean includeSuperSenior)` / `void updateSeniorFamilyInfo(boolean)` | 向长辈发包/刷新面板 | 可含祖辈 |
| `synchronized void doFullCount()` | 族长触发全树重算 | `traverseAndUpdateCounts(0)` 递归设 seniors/juniors 计数与 generation，家族总代数=最大深度+1 |
| `private Pair<Integer,Integer> traverseAndUpdateCounts(int seniors)` | 递归计数 | 返回（最大深度, 后裔总数） |
| `boolean useEntitlement(FamilyEntitlement)` | 消费权益 | 已用拒绝；INSERT family_entitlement；计数++ |
| `boolean refundEntitlement(FamilyEntitlement)` | 退回权益 | DELETE 行、计数清零 |
| `boolean isEntitlementUsed(FamilyEntitlement)` / `int getEntitlementUsageCount(FamilyEntitlement)` / `void setEntitlementUsed(int id)` / `void resetEntitlementUsages()` | 权益查询/恢复/每日重置 | |
| `boolean saveReputation()` ×2 重载 | 声望落库 | 未变直接 true；UPDATE family_character 四值 |
| `void savedSuccessfully()` | 清脏标记 | |

### 8.3 FamilyEntitlement（枚举）

**概述**：家族权益目录（11 项），名称与描述走 i18n（`FamilyEntitlement.messageN`）。每项携带每日使用上限与声望价格。

**枚举项**：FAMILY_REUINION(300)、SUMMON_FAMILY(500)、SELF_DROP_1_5(700)、SELF_EXP_1_5(800)、FAMILY_BONDING(1000)、SELF_DROP_2(1200)、SELF_EXP_2(1500)、SELF_DROP_2_30MIN(2000)、SELF_EXP_2_30MIN(2500)、PARTY_DROP_2_30MIN(4000)、PARTY_EXP_2_30MIN(5000)。

| 方法签名 | 作用 |
|---|---|
| `FamilyEntitlement(int usageLimit, int repCost, String name, String description)`（构造） | |
| `int getUsageLimit()` / `int getRepCost()` / `String getName()` / `String getDescription()` | 属性访问 |

---

## 9. QuestStatus（任务状态）

**概述**：单条任务在角色身上的运行时状态：状态机、按 mob-id 的进度（3 位 0 填充字符串）、勋章地图进度、完成/过期时间、自定义数据。容器 `progress`/`medalProgress` 保持插入序。

**关键字段**：`questID`（final short）、`status`、`progress: Map<Integer,String>`、`medalProgress: List<Integer>`、`npc`、`completionTime/expirationTime`、`forfeited/completed`、`customData`。内部枚举 `Status{UNDEFINED(-1), NOT_STARTED(0), STARTED(1), COMPLETED(2)}`（含 `getById`）。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `QuestStatus(Quest quest, Status status)` ×2 重载（可带 npc） | 构造 | completionTime=now；STARTED 时 `registerMobs` 预填击杀进度 000 |
| `Quest getQuest()` | 反查任务定义 | `Quest.getInstance(questID)` |
| `short getQuestID()` / `Status getStatus()` / `final void setStatus(Status)` | 状态-id/状态机 | |
| `int getNpc()` / `final void setNpc(int)` | 关联 NPC | |
| `private void registerMobs()` | 预登记相关怪 | 每个相关 mob 进度 "000" |
| `boolean addMedalMap(int mapid)` | 勋章地图去重追加 | 已有返回 false |
| `int getMedalProgress()` / `List<Integer> getMedalMaps()` | 勋章进度 | |
| `boolean progress(int id)` | 击杀 +1 | 未登记 false；达到 `getMobAmountNeeded` 上限 false；否则 +1 并左补 0 到 3 位 |
| `void setProgress(int id, String pr)` / `boolean madeProgress()` / `String getProgress(int id)` / `void resetProgress(int id)` / `void resetAllProgress()` / `Map<Integer,String> getProgress()` | 进度读写 | 缺省返回 ""；reset 写 "000"；map 只读 |
| `short getInfoNumber()` / `String getInfoEx(int index)` / `List<String> getInfoEx()` | 任务 infoNumber/InfoEx 委托 | 按当前状态查 Quest 定义 |
| `long getCompletionTime()` / `void setCompletionTime(long)` / `long getExpirationTime()` / `void setExpirationTime(long)` | 完成/过期时间 | |
| `int getForfeited()` / `int getCompleted()` / `void setForfeited(int)` / `void setCompleted(int)` | 放弃/完成次数 | 只允许调大，调小抛 IllegalArgumentException |
| `final void setCustomData(String)` / `final String getCustomData()` | 脚本自定义数据 | |
| `String getProgressData()` | 进度串行化 | 全部进度值顺序拼接（存库用） |

---

## 10. 其它游戏对象

### 10.1 Mount

**概述**：坐骑运行时（物品-id、技能-id、疲劳、经验、等级、激活态）。挂在 Character.mapleMount。

**关键字段**：`itemid/skillid/tiredness/exp/level/owner/active`。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `Mount(Character owner, int id, int skillid)`（构造） | 初始疲劳 0、1 级 | |
| `int getItemId()` / `int getSkillId()` | 物品/技能-id | |
| `int getId()` | 坐骑类型序号（封包用） | itemid<1903000 时 `itemid-1901999`（Hog=1、Silver Mane=2…），否则恒 5 |
| `int getTiredness()` / `int getExp()` / `int getLevel()` | 状态 | |
| `void setTiredness(int)` | 疲劳（下限 0） | |
| `int incrementAndGetTiredness()` | 疲劳+1 | |
| `void setExp(int)` / `void setLevel(int)` / `void setItemId(int)` / `void setSkillId(int)` | 写状态 | |
| `void setActive(boolean)` / `boolean isActive()` | 骑乘激活 | |
| `void empty()` | 释放 | 从世界注销坐骑饥饿任务，断开 owner 引用 |

### 10.2 MonsterBook

**概述**：怪物图鉴（卡片收集）：卡表（cardid→等级 1~5）、特殊/普通卡计数、图鉴等级。`ReentrantLock` 保护；读档走 Spring `MonsterBookService`，存档走 JDBC 批量 UPSERT。

**关键字段**：`specialCard/normalCard/bookLevel`、`cards: LinkedHashMap`、`lock`、静态 `monsterBookService`。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `MonsterBook(int cid)`（构造） | 读档 | `loadCards(cid)` |
| `Set<Entry<Integer,Integer>> getCardSet()` | 卡表快照 | |
| `void addCard(Client c, int cardid)` | 捡卡升级 | 广播他人卡片特效；已有卡 <5 级时 +1；新卡入库计数（cardid/1000≥2388 为特殊卡）；只有新卡（首次）触发 `calculateLevel`；满级发 addCard(true) 包 |
| `private void calculateLevel()` | 图鉴等级 | 收集量对「1+Σ level×10」阈值递推 |
| `int getBookLevel()` / `Map<Integer,Integer> getCards()` / `int getTotalCards()` / `int getNormalCard()` / `int getSpecialCard()` | 查询 | |
| `void loadCards(int chrId)` | 读档 | service 取 MonsterbookDO 列表分桶计数；结束 calculateLevel |
| `void saveCards(Connection con, int chrId)` | 存档 | `INSERT ... ON DUPLICATE KEY UPDATE level` 批量 |
| `static int[] getCardTierSize()` | 各层级卡总数（静态） | `GROUP BY floor(cardid/1000)` 滚动结果集转数组 |

### 10.3 Ring

**概述**：戒指（情侣/友情/结婚）：自身 ringId 与伴侣 ringId 配对。直接 JDBC 操作 rings / inventoryequipment 表；id 由 `CashIdGenerator` 发放并可回收。

**关键字段**：`ringId/ringId2/partnerId/itemId/partnerName`（final）、`equipped`。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `Ring(int id, int id2, int partnerId, int itemid, String partnername)`（构造） | | |
| `static Ring loadFromDb(int ringId)` | 按-id 查戒指 | SELECT rings，无则 null |
| `static void removeRing(Ring ring)` | 删除配对戒指 | 批量 DELETE 两行 rings；`CashIdGenerator.freeCashId` 回收两个-id；inventoryequipment.ringid 置 -1 |
| `static Pair<Integer,Integer> createRing(int itemid, Character partner1, Character partner2)` | 创建一对戒指 | 任一方空返回 -3/-2；生成两个 CashId 互相配对 INSERT 两行；SQL 失败 -1 |
| `int getRingId()` / `int getPartnerRingId()` / `int getPartnerChrId()` / `int getItemId()` / `String getPartnerName()` | 属性 | |
| `boolean equipped()` / `void equip()` / `void unequip()` | 佩戴态 | |
| `boolean equals(Object)` / `int hashCode()` / `int compareTo(Ring)` | 按 ringId 比较/排序 | |

### 10.4 SkillMacro

**概述**：一条技能宏（5 个槽位之一）：绑定 3 个技能 + 名称 + 是否喊话 + 槽位。

**关键字段**：`skill1/skill2/skill3`、`name/shout/position`（final）。

| 方法签名 | 作用 |
|---|---|
| `SkillMacro(int skill1, int skill2, int skill3, String name, int shout, int position)`（构造） | |
| `int getSkill1()/getSkill2()/getSkill3()` / `void setSkill1/2/3(int)` | 三技能位读写 |
| `String getName()` / `int getShout()` / `int getPosition()` | 名称/喊话/槽位 |

---

## 11. 枚举与值对象

### 11.1 Job（枚举）

**概述**：v83 全职业表（初心者 → 战法弓飞海 → 骑士团 → 战神/Evan 十段），id+显示名（i18n `job.name.<id>`，Lombok `@Getter`）。

**关键字段**：`id`、`name`；静态 `maxId = 22`（EVAN/100）。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `static int getMax()` | 职业大类数上限 | 22 |
| `static Job getById(int id)` | 按-id 取 | 未命中返回 BEGINNER |
| `static Job getBy5ByteEncoding(int encoded)` | 创建角封包位段解码 | 2/4/8/16/32→五大职业，1024~32768→骑士团五系 |
| `boolean isA(Job basejob)` | 职业归属（含骑士团/战神同宗） | 同十位分支且 id≥base，或 base 为分支根时按百位比较 |
| `int getJobNiche()` | 职业大类号 | `(id/100)%10`（0初心 1战 2法 3弓 4飞 5海） |
| `static Job getJobStyleInternal(int jobid, byte opt)` | 武器流派归一（战士/法/弓弩/飞/拳枪） | 骑士团/战神/Evan 映射回五大系；弓手按十位细分弩；海盗按 opt 0x80 分拳师/枪手 |

### 11.2 Stat（枚举）

**概述**：角色属性封包位掩码（updatePlayerStats 用），值即客户端协议位。

**枚举项**：SKIN 0x1、FACE 0x2、HAIR 0x4、LEVEL 0x10、JOB 0x20、STR 0x40、DEX 0x80、INT 0x100、LUK 0x200、HP 0x400、MAXHP 0x800、MP 0x1000、MAXMP 0x2000、AVAILABLEAP 0x4000、AVAILABLESP 0x8000、EXP 0x10000、FAME 0x20000、MESO 0x40000、PET 0x180008、GACHAEXP 0x200000。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `int getValue()` | 位掩码值 | |
| `static Stat getByValue(int)` | 反查 | 未命中 null |
| `static Stat getBy5ByteEncoding(int)` | 加点封包位段解码 | 64/128/256/512 → STR/DEX/INT/LUK |
| `static Stat getByString(String)` | 按名反查 | name() 相等 |

### 11.3 BuffStat（枚举）

**概述**：buff 位掩码（long），客户端 giveBuff/cancelBuff 协议核心。前段为正确掩码；`isFirst=true` 段为「客户端首包掩码不正确」的特殊 buff（SLOW/ELEMENTAL_RESET/ARAN 系/速度激发等），需放在第一个掩码包发送。含 MORPH、WATK/MATK/WDEF/MDEF、SUMMON、MONSTER_RIDING、COMBO、HOLY_SYMBOL 等约 80 项。

**关键字段**：`i`（long 掩码）、`isFirst`。

| 方法签名 | 作用 |
|---|---|
| `BuffStat(long i, boolean isFirst)` / `BuffStat(long i)`（构造） | 默认 isFirst=false |
| `long getValue()` | 掩码值 |
| `boolean isFirst()` | 是否需要首包发送 |
| `String toString()` | 枚举名 |

### 11.4 Disease（枚举）

**概述**：异常状态位掩码（long，部分为 46~48 位宽），可关联怪物技能 `MobSkillType`（SEDUCE/STUN/POISON 等）。

**枚举项**：NULL 0x0、SLOW 0x1、SEDUCE 0x80、FISHABLE 0x100、ZOMBIFY 0x4000、CONFUSE 0x80000、STUN/POISON/SEAL/DARKNESS/WEAKEN/CURSE（0x2000…0x8000 开头的 long 高位段）。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `long getValue()` | 掩码 | |
| `boolean isFirst()` | 恒 false（预留） | |
| `MobSkillType getMobSkillType()` | 关联怪物技能 | 未关联为 null |
| `static Disease ordinal(int ord)` | 序号安全取值 | 越界返回 NULL |
| `static Disease getRandom()` | CPQ 随机疾病 | 从 `GameConstants.CPQ_DISEASES` 随机 |
| `static Disease getBySkill(MobSkillType)` | 怪物技能反查 | stream findAny |

### 11.5 DiseaseValueHolder

**概述**：疾病计时值对象（开始时间 + 时长），public 字段直读。

| 方法签名 | 作用 |
|---|---|
| `DiseaseValueHolder(long start, long length)`（构造） | 写 `startTime/length` 两个 public 字段 |

### 11.6 SkinColor（枚举）

**概述**：皮肤色-id 表（NORMAL 0、DARK 1、BLACK 2、PALE 3、BLUE 4、GREEN 5、WHITE 9、PINK 10）。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `int getId()` | 色号 | |
| `static SkinColor getById(int id)` | 反查 | 未命中 null |

### 11.7 DefaultDates

**概述**：符号性默认日期常量（2005-05-11 = MapleGlobal 开服日），作 DB 中「空日期」哨兵。工具类，私有构造。

| 方法签名 | 作用 |
|---|---|
| `static LocalDate getBirthday()` | 返回 2005-05-11 |
| `static LocalDateTime getTempban()` | 返回 2005-05-11T00:00（未封禁哨兵，Client.getTempBanCalendarFromDB 用） |

---

## 附：与其它模块的衔接点

- **网络层**：`Client.channelRead` 是全部 RecvOpcode 的入口（详见详细设计文档 06）；Character 的所有 `sendPacket` 最终走 `Client.sendPacket`。
- **存档双版本**：角色保存实际生效的是 `Character.saveCharToDB(boolean)`（UPDATE 版）；`CharacterService.insertSelective`/`toCharactersDO` 不是实际写入路径（CLAUDE.md 已明确标注）。
- **换频道**：`Client.changeChannel` 把 buff/疾病写入 `PlayerBuffStorage`，对侧 `Character.loadCharFromDB` + `silentGiveBuffs/silentApplyDiseases` 恢复。
- **Spring 桥接**：Character/Client/MonsterBook/Family 均以静态字段持有 service（`ServerManager.getApplicationContext().getBean`），遗留对象不注册为 Spring bean。
