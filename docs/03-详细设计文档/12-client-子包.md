# 12-client 子包详细设计

- 模块路径：`gms-server/src/main/java/org/gms/client/` 下 7 个子包：`autoban`、`command`（仅根包框架类）、`creator`（含 `novice`/`veteran` 子包）、`inventory`（含 `manipulator` 子包）、`keybind`、`processor`（含 `action`/`npc`/`stat` 子包）、`status`
- 类数量：41（autoban 2、command 根 2、creator 12、inventory 13、keybind 2、processor 8、status 2）
- 依赖模块：
  - `org.gms.client` 根包：`Character`、`Client`、`Job`、`Skill`/`SkillFactory`、`Stat`、`SkinColor`、`BuffStat`（详见《11-client 根包》）
  - `org.gms.server.*`：`ItemInformationProvider`（WZ 物品信息）、`Storage`、`Trade`、`DueyPackage`、`MakerItemFactory.MakerItemCreateEntry`、`StatEffect`、`ThreadManager`、`maps.MapleMap`、`maps.HiredMerchant`、`movement.*`
  - `org.gms.net.server.*`：`Server`（单例）、`channel.Channel`、`world.World`
  - `org.gms.net.packet.*`：`InPacket`/`OutPacket`
  - `org.gms.provider.*`：`Data`/`DataTool`/`DataProvider(Factory)`/`WZFiles`
  - `org.gms.config.GameConfig`（热更配置）、`org.gms.dao.entity.AutobanConfigDO`、`org.gms.model.pojo.NewYearCardRecord`
  - `org.gms.constants.*`：`inventory.ItemConstants`、`id.ItemId`/`MapId`、`game.ExpTable`/`GameConstants`、`skills.*`（各职业技能 ID 常量）
  - `org.gms.util.*`：`PacketCreator`、`DatabaseConnection`、`Pair`、`Randomizer`、`I18nUtil`、`CashIdGenerator`
  - Spring 胶水层：`org.gms.manager.ServerManager`（取 ApplicationContext）、`org.gms.service.CommandService`/`HpMpAlertService`/`NoteService`
- 篇界说明：`org/gms/client` 根包的 `Character`、`Client` 等由《11-client 根包》负责；`client/command/commands/gm0~gm6` 下 177 个具体命令类由《13-commands 命令集》负责，本文只覆盖 `command` 根下的 2 个框架类。
- 通用约定：
  - 线程模型分三层：`Client.tryacquireClient()/lockClient()`（会话级互斥，命令/处理器入口使用）、`Inventory.lockInventory()`（单背包互斥）、`ItemFactory` 内 400 把分段锁（按 `id % 400` 哈希，消除同库保存瓶颈）。
  - 面向玩家/GM 的文案走 `I18nUtil.getMessage/getLogMessage`（资源文件 `i18n/message_*`、`i18n/log_*`）；少量遗留 OdinMS 代码仍保留英文硬编码。
  - 运营参数全部走 `GameConfig`（`game_config` 表，支持热更），如 `use_auto_ban`、`use_starting_ap_4`、`use_equipment_level_up` 等。

---

## 1. autoban 子包（自动封禁）

### 1.1 AutobanFactory（枚举）

**概述**：自动封禁「违规类型」枚举，定义 20 种作弊检测类型（MOB_COUNT、GENERAL、FIX_DAMAGE、DAMAGE_HACK、DISTANCE_HACK、PORTAL_DISTANCE、PACKET_EDIT、ACC_HACK、CREATION_GENERATOR、HIGH_HP_HEALING、FAST_HP_HEALING、FAST_MP_HEALING、GACHA_EXP、TUBI、SHORT_ITEM_VAC、ITEM_VAC、PET_ITEM_VAC、PET_SHORT_ITEM_VAC、FAST_ITEM_PICKUP、FAST_ATTACK、MPCON、ATTACK_INTERVAL），每个类型携带触发阈值（points）与积分衰减窗口（expiretime）。BeiDou 扩展了数据库配置缓存（`autoban_config` 表 → `AutobanConfigDO`），可在线覆盖每个类型的阈值/窗口/禁用状态。源码路径：`client/autoban/AutobanFactory.java`。

**关键字段**：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `name` | `String` | 类型显示名，取自 i18n `autoban.name.<ENUM>`，同时作为配置缓存 key（`this.name()` 枚举名才是 key，见 getEffectivePoints） |
| `points` | `int` | 代码内置触发阈值（累计到达即封号）；无参构造默认 1 |
| `expiretime` | `long` | 积分衰减窗口（毫秒），超期后积分减半；-1 表示永不衰减 |
| `CONFIG_CACHE` | `static Map<String, AutobanConfigDO>` | 数据库配置缓存，key 为枚举名 |
| `ignoredChrIds` | `static Set<Integer>` | 忽略告警的角色 ID 集合（GM 可用命令切换） |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `AutobanFactory(String name)` / `(String name, int points)` / `(String name, int points, long expire)` | 枚举构造 | 三级重载；单参默认 points=1、expire=-1 |
| `int getMaximum()` | 取内置触发阈值 | 直接返回 `points` |
| `long getExpire()` | 取内置衰减窗口 | 直接返回 `expiretime` |
| `static void initConfig(Map<String, AutobanConfigDO> configs)` | 初始化配置缓存 | 清空后整体 putAll，并打印加载数量日志（启动时由 Spring 侧调用） |
| `static void updateConfig(String type, AutobanConfigDO config)` | 热更单个配置 | `config == null` 时移除该 key，否则覆盖（运营后台改配置时调用） |
| `static AutobanConfigDO getConfig(String type)` | 查询配置 | 直接 `CONFIG_CACHE.get(type)` |
| `int getEffectivePoints()` | 取生效的触发阈值 | 优先取数据库配置 `config.getPoints()`（非 null 时），否则回退内置 `points` |
| `long getEffectiveExpiretime()` | 取生效的衰减窗口 | 优先取 `config.getExpireTime()`，否则回退内置 `expiretime` |
| `boolean isDisabled()` | 该类型是否被禁用 | 配置存在且 `disabled == TRUE` 时为 true（addPoint 直接短路） |
| `void addPoint(AutobanManager ban, String reason)` | 给目标管理器记 1 点 | 委托 `ban.addPoint(this, reason)` |
| `void alert(Character chr, String reason)` | 向全服 GM 广播作弊告警 | 需 `use_auto_ban` 开启；被忽略角色（isIgnored）跳过；用 `Server.broadcastGMMessage` + `sendYellowTip` 通告 "角色名 caused 枚举名 reason"；另在 `use_auto_ban_log` 开启时打 info 日志 |
| `void autoban(Character chr, String value)` | 立即自动封禁 | 需 `use_auto_ban` 开启；调 `chr.autoBan("Autobanned for (类型: 值)")` |
| `static boolean toggleIgnored(int chrId)` | 切换角色的告警忽略状态 | 在 `ignoredChrIds` 中存在则移除并返回 false，否则加入并返回 true（返回新状态） |
| `static boolean isIgnored(int chrId)`（private） | 判断是否被忽略 | `ignoredChrIds.contains` |
| `static Collection<Integer> getIgnoredChrIds()` | 取忽略列表 | 返回集合引用，供 ignore/ignored 命令展示 |

### 1.2 AutobanManager

**概述**：每个 `Character` 持有一个的自动封禁积分管理器，按 `AutobanFactory` 类型累计违规积分、检测 miss 无敌（godmode）与时间戳刷包（spam），到达阈值后调 `Character.autoBan` 封号。源码路径：`client/autoban/AutobanManager.java`。

**关键字段**：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `chr` | `Character` | 宿主角色 |
| `points` | `Map<AutobanFactory, Integer>` | 各类型当前积分 |
| `lastTime` | `Map<AutobanFactory, Long>` | 各类型最近一次记分时间（用于衰减） |
| `misses` / `lastmisses` / `samemisscount` | `int` | miss 无敌检测：本次 miss 计数 / 上轮计数 / 连续相同轮数 |
| `spam` | `long[20]` | 通用动作节流时间戳（按 type 下标） |
| `timestamp` / `timestampcounter` | `int[20]` / `byte[20]` | 相同时间戳包重复计数（刷包检测） |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `AutobanManager(Character chr)` | 构造 | 仅保存角色引用 |
| `void addPoint(AutobanFactory fac, String reason)` | 记 1 点违规积分 | 需 `use_auto_ban`：GM 或已封号角色直接返回；`fac.isDisabled()` 返回；若距上次记分超过 `getEffectiveExpiretime()` 则积分减半（不完全清零）；有衰减窗口时刷新 lastTime；积分 +1，达到 `getEffectivePoints()` 阈值调 `chr.autoBan(reason)`。`use_auto_ban_log` 开启时逐点打 info 日志 |
| `void addMiss()` | miss 计数 +1 | 攻击 miss 时由伤害计算调用，`misses++` |
| `void resetMisses()` | 轮次结束结算 miss 无敌检测 | `lastmisses == misses && misses > 6` 则 `samemisscount++`；`samemisscount > 4` 判定 miss godmode，`chr.sendPolice(...)` 踢出；否则若 `samemisscount > 0` 更新 lastmisses；最后 `misses = 0` |
| `void spam(int type)` | 记录动作时间戳（服务器钟） | `spam[type] = Server.getInstance().getCurrentTime()` |
| `void spam(int type, int timestamp)` | 记录动作时间戳（客户端钟） | 直接写 `spam[type] = timestamp` |
| `long getLastSpam(int type)` | 查询上次动作时间 | 返回 `spam[type]`（调用方自行做间隔比较） |
| `void setTimestamp(int type, int time, int times)` | 相同时间戳重复包检测并踢线 | type 语义：1 宠物食品、2 背包合并、3 背包排序、4 技能释放、5 捕捉道具、6 丢物、7 聊天、8 HP 恢复、9 MP 恢复；当前时间戳与上次相同则 `timestampcounter[type]++`，达到 `times` 次且 `use_auto_ban` 开启时 `disconnect(false,false)` 并打日志；时间戳变化则重置计数 |

---

## 2. command 子包根（命令框架）

### 2.1 Command（抽象类）

**概述**：所有游戏内聊天命令的抽象基类（`@Data`），177 个具体命令（gm0~gm6）均继承它。命令通过 `CommandsExecutor` 注册表按语法名分发。源码路径：`client/command/Command.java`。

**关键字段**：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `rank` | `int` | 命令所需 GM 等级（0=普通玩家，1~6 递增；注册时由 `CommandsExecutor.addCommand` 写入） |
| `description` | `String` | 命令描述，供 help 命令按等级分组展示 |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `abstract void execute(Client client, String[] params)` | 执行命令 | 子类实现；params 为已小写化、按空格切分的参数数组（无参数时为空数组） |
| `protected String joinStringFrom(String[] arr, int start)` | 拼接剩余参数为整句 | 从 `start` 起用空格连接到末尾（供需要保留原句的命令，如公告、留言） |
| `int getRank()` / `void setRank(int)` / `String getDescription()` / `void setDescription(String)` | Lombok `@Data` 生成 | rank/description 的存取器 |

### 2.2 CommandsExecutor

**概述**：命令系统单例执行器：判断聊天内容是否命令、解析命令名与参数、做监狱/等级校验后从注册表取出 `Command` 执行。BeiDou 改造点：原 `registerLv0~Lv6Commands()` 硬编码注册流程已停用（调用被注释），改为委托 Spring 侧 `CommandService.loadCommands(registeredCommands, commandsNameDesc)` 完成注册（命令定义外置、支持后台管理）。源码路径：`client/command/CommandsExecutor.java`。

**关键字段**：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `instance` | `static CommandsExecutor` | 饿汉单例（`@Getter`） |
| `USER_HEADING` / `GM_HEADING` | `static char` | 命令前导符 `'@'`（玩家）/ `'!'`（GM） |
| `registeredCommands` | `HashMap<String, Command>` | 命令名 → 命令实例（实例复用，避免每次执行重建） |
| `commandsNameDesc` | `List<Pair<List<String>, List<String>>>` | 按 GM 等级分组的（命令名列表, 描述列表），供帮助命令展示 |
| `levelCommandsCursor` | `Pair<List<String>, List<String>>` | 当前正在注册的等级分组游标（遗留代码使用） |
| `commandService` | `static CommandService` | 类加载时经 `ServerManager.getApplicationContext()` 获取 |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static boolean isCommand(Client client, String content)` | 判断聊天串是否命令 | GM 可用 `@` 或 `!` 开头；普通玩家仅 `@` |
| `void loadCommandsExecutor()` | 加载命令注册表 | 直接调 `commandService.loadCommands(registeredCommands, commandsNameDesc)`（原 `registerLv0~6Commands` 调用链被注释停用，方法体保留） |
| `void handle(Client client, String message)` | 命令处理入口 | `client.tryacquireClient()` 抢会话锁成功才执行 `handleInternal`（finally 释放）；抢锁失败提示客户端正忙（i18n `CommandsExecutor.handle.message1`） |
| `void handleInternal(Client client, String message)`（private） | 实际解析与执行 | ①监狱地图（`MapId.JAIL`）且非 GM 拒绝；②普通玩家 `@` 命令且 `deterred_player_command` 开启时拒绝；③按首个空格切成 [命令名, 参数串]（缺参数补空串）；④`setLastCommandMessage` 保留参数原文（避免整句被小写化）；⑤命令名转小写查 `registeredCommands`，未找到提示（message2）；⑥`player.gmLevel() < command.getRank()` 提示权限不足（message3）；⑦参数串小写化再按空格 split 成数组；⑧`command.execute(client, params)` 并按 i18n 模板打执行日志（玩家名 + 命令类简单名） |
| `void addCommandInfo(String name, Class<? extends Command> commandClass)`（private） | 登记命令名与描述到当前等级分组 | 反射 `getDeclaredConstructor().newInstance()` 取 description，加入 `levelCommandsCursor` 两侧列表 |
| `void addCommand(String[] syntaxs, Class<? extends Command>)` / `addCommand(String syntax, Class<? extends Command>)` / `addCommand(String[] surtaxes, int rank, Class<? extends Command>)`（private） | 批量/单语法注册重载 | 统一转发到 `addCommand(syntax, 0, clazz)`（rank 默认 0）或带 rank 版本 |
| `void addCommand(String syntax, int rank, Class<? extends Command> commandClass)`（private） | 注册单个命令语法 | 语法名小写重复时 warn 跳过；反射创建命令单例、`setRank(rank)`、放入 `registeredCommands`（实例复用防每次调用重建）；异常仅 warn 不中断 |
| `void registerLv0Commands()` ~ `registerLv6Commands()`（private，7 个） | 各 GM 等级命令注册（遗留） | 每级新建 `levelCommandsCursor` 分组，调用 `addCommand(语法, 等级, 命令类)`：Lv0 玩家命令（help/droplimit/time/credits/uptime/gacha/dispose/changel/equiplv/showrates/rates/online/gm/reportbug/points/joinevent/leaveevent/ranks/str/dex/int/luk/enableauth/toggleexp/mylawn）、Lv1（bosshp/mobhp/whatdropsfrom/whodrops/buffme/goto）、Lv2（recharge/whereami/hide/unhide/sp/ap/empowerme/buffmap/buff/bomb/dc/cleardrops/clearslot/clearsavelocs/warp/warphere·summon/warpto·reach·follow/gmshop/heal/item/drop/level/levelpro/setslot/setstat/maxstat/maxskill/resetskill/search/jail/unjail/job/unbug/id/gachalist/loot/mobskill/warpmap/warparea）、Lv3（debuff/fly/spawn/mutemap/checkdmg/inmap/reloadevents/reloaddrops/reloadportals/reloadmap/reloadshops/hpmp/maxhpmp/music/monitor/monitors/ignore/ignored/pos/togglecoupon/togglewhitechat/fame/givenx/givevp/givems/giverp/expeds/kill/seed/maxenergy/killall/notice/rip/openportal/closeportal/pe/startevent/endevent/startmapevent/stopmapevent/online2/ban/unban/healmap/healperson/hurt/killmap/night/npc/face/hair/startquest/completequest/resetquest/timer/timermap/timerall）、Lv4（servermessage/proitem/seteqstat/exprate/mesorate/droprate/bossdroprate/questrate/travelrate/fishrate/itemvac/forcevac/zakum/horntail/pinkbean/pap/pianus/cake/playernpc/playernpcremove/pnpc/pnpcremove/pmob/pmobremove/warptolife）、Lv5（debug/set/showpackets/showmovelife/showsessions/iplist）、Lv6（setgmlevel/warpworld/saveall/dcall/mapplayers/getacc/shutdown/clearquestcache/clearquest/supplyratecoupon/spawnallpnpcs/eraseallpnpcs/addchannel/addworld/removechannel/removeworld/devtest）。当前由 `CommandService` 承担同等注册职责，这些方法保留作参照 |

---

## 3. creator 子包（角色创建）

包结构：根 4 类（`CharacterFactory`、`CharacterFactoryRecipe`、`MakeCharInfo`、`MakeCharInfoValidator`）+ `novice` 3 类（新手职业创建器）+ `veteran` 5 类（30 级老手职业创建器，对应「Maple Life」道具直升玩法）。

### 3.1 CharacterFactory（抽象类）

**概述**：角色创建流程模板基类。子类（novice/veteran 各 Creator）用静态方法组装 `CharacterFactoryRecipe`（出生配装配方）后调用本类 `createNewCharacter` 完成通用建号流程：槽位/名字校验 → 组装角色外观与装备 → MakeCharInfo 合法性校验（防封包伪造外观）→ 入库 → 通知客户端与 GM。源码路径：`client/creator/CharacterFactory.java`。

**关键字段**：仅 `log`（Logger）。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `protected synchronized int createNewCharacter(Client c, String name, int face, int hair, int skin, int gender, CharacterFactoryRecipe recipe)` | 通用建号（静态同步） | ①槽位检查：`collective_chr_slot` 开启时用全服共享槽 `c.getAvailableCharacterSlots() <= 0`，否则按世界 `getAvailableCharacterWorldSlots() <= 0`，返回 **-3**；②`Character.canCreateChar(name)` 名字非法返回 **-1**；③`Character.getDefault(c)` 建默认角色并写入 world/skin/gender/name/hair/face 及 recipe 的 level/job/map；④配装：top→装备位 -5、bottom→-6、shoes→-7、weapon→-11（weapon 用 `copy()` 防共享实例），经 `ItemInformationProvider.getEquipById` 生成并 `addItemFromDB` 放入 EQUIPPED 背包；⑤`MakeCharInfoValidator.isNewCharacterValid` 不通过视为封包编辑，warn 并返回 **-2**；⑥`newCharacter.insertNewChar(recipe)`（连同 recipe 的属性/技能/道具落库）失败返回 **-2**；⑦成功：`PacketCreator.addNewCharEntry` 通知客户端、`Server.createCharacterEntry` 登记在线名单、`broadcastGMMessage` 黄字提示新角色（i18n `CharacterFactory.message1`）、日志记录账号与角色名，返回 **0** |

### 3.2 CharacterFactoryRecipe

**概述**：角色创建「配方」值对象：新角色的职业/等级/出生地图/四件套装备、四维属性、HP/MP、剩余 AP/SP、初始金币、初始技能与初始道具清单。由各 Creator 填充，最终被 `Character.insertNewChar` 消费。源码路径：`client/creator/CharacterFactoryRecipe.java`。

**关键字段**：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `job` / `level` / `map` | `Job` / `int` / `int` | 职业、等级、出生地图（构造器传入，final） |
| `top` / `bottom` / `shoes` / `weapon` | `int` | 四件套装备 itemId（final；0 表示不穿） |
| `str`/`dex`/`int_`/`luk` | `int` | 四维，默认 4；`maxHp`=50、`maxMp`=5、`ap`/`sp`=0、`meso`=0 |
| `skills` | `List<Pair<Skill, Integer>>` | 初始技能等级表 |
| `itemsWithType` | `List<Pair<Item, InventoryType>>` | 初始道具（含装备）清单 |
| `runningTypePosition` | `Map<InventoryType, AtomicInteger>` | 各背包自动分配的起始格位计数器 |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `CharacterFactoryRecipe(Job job, int level, int map, int top, int bottom, int shoes, int weapon)` | 构造 | 保存七要素；按配置初始化四维/AP：`use_starting_ap_4` 开启保持 4/4/4/4；否则 `use_auto_assign_starters_ap` 开启时 str=12、dex=5（GMS 风格自动分配），不开则 `ap=9`（留给玩家手动分配） |
| `void setStr(int)` / `setDex(int)` / `setInt(int)` / `setLuk(int)` / `setMaxHp(int)` / `setMaxMp(int)` / `setRemainingAp(int)` / `setRemainingSp(int)` / `setMeso(int)` | 覆写默认属性 | 直接赋值（veteran 创建器用） |
| `void addStartingSkillLevel(Skill skill, int level)` | 登记初始技能 | `skills.add(new Pair<>(skill, level))` |
| `void addStartingEquipment(Item eqpItem)` | 登记初始装备（背包内，非身穿） | 以 `InventoryType.EQUIP` 加入 itemsWithType |
| `void addStartingItem(int itemid, int quantity, InventoryType itemType)` | 登记初始普通道具 | 按 `runningTypePosition` 为该背包自动取下一个格位（0 起自增），构造 `Item(itemid, pos, quantity)` 入清单 |
| `Job getJob()` / `int getLevel()` / `int getMap()` / `int getTop()` / `int getBottom()` / `int getShoes()` / `int getWeapon()` / `int getStr()` / `int getDex()` / `int getInt()` / `int getLuk()` / `int getMaxHp()` / `int getMaxMp()` / `int getRemainingAp()` / `int getRemainingSp()` / `int getMeso()` | 取配方要素 | 直接返回字段 |
| `List<Pair<Skill, Integer>> getStartingSkillLevel()` | 取初始技能表 | 返回内部列表引用 |
| `List<Pair<Item, InventoryType>> getStartingItems()` | 取初始道具清单 | 返回内部列表引用 |

### 3.3 MakeCharInfo

**概述**：角色创建外观合法性数据集：从 `MakeCharInfo.img` 节点解析出的合法脸型/发型/发色/皮肤/上衣/裤裙/鞋/武器 ID 白名单，用于校验客户端建号请求未伪造外观（防封包编辑）。源码路径：`client/creator/MakeCharInfo.java`。

**关键字段**：8 个 `Set<Integer>`——`charFaces`/`charHairs`/`charHairColors`/`charSkins`/`charTops`/`charBottoms`/`charShoes`/`charWeapons`；节点名常量 `"0"`~`"7"` 分别对应上述八类。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `MakeCharInfo(Data charInfoData)` | 构造并解析 WZ 节点 | 遍历子节点按名字（0=脸、1=发、2=发色、3=皮肤、4=上衣、5=裤、6=鞋、7=武器）收集 ID 入对应 Set；未知节点名打 error 日志 |
| `boolean verifyFaceId(int id)` / `verifySkinId(int)` / `verifyTopId(int)` / `verifyBottomId(int)` / `verifyShoeId(int)` / `verifyWeaponId(int)` | 单项白名单校验 | 对应 Set 的 `contains` |
| `boolean verifyHairId(int id)` | 发型校验（含染色偏移） | 若 `id % 10 != 0`（带染色编号），用去尾的基础发型 `id - id % 10` 查白名单；否则直接查 |
| `boolean verifyHairColorId(int id)` | 发色校验 | 用个位 `id % 10` 查发色 Set |
| `boolean verifyCharacter(Character character)` | 整角色校验 | 脸/发型/发色/皮肤逐项校验；仅当职业为 BEGINNER/NOBLESSE/LEGEND（客户端会上传装备数据的职业；Maple Life 道具建号时装备全由服务端决定、客户端不发包，故跳过装备校验）时再校验 EQUIPPED 背包 -5/-6/-7/-11 四格装备 ID |

### 3.4 MakeCharInfoValidator

**概述**：`MakeCharInfo` 的静态门面：启动时从 `ETC` WZ 的 `MakeCharInfo.img` 装载男/女 × 普通/骑士团（Premium）/战神（Orient）共 6 份白名单，按新角色职业+性别路由校验。源码路径：`client/creator/MakeCharInfoValidator.java`。

**关键字段**：`charFemale`/`charMale`/`orientCharFemale`/`orientCharMale`/`premiumCharFemale`/`premiumCharMale` 共 6 个 `static MakeCharInfo`（static 块从 `Info/CharFemale`、`Info/CharMale`、`OrientCharFemale`、`OrientCharMale`、`PremiumCharFemale`、`PremiumCharMale` 路径装载）。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static MakeCharInfo getMakeCharInfo(Character character)`（private） | 按职业+性别选白名单 | switch：BEGINNER/WARRIOR/MAGICIAN/BOWMAN/THIEF/PIRATE → Char 男/女；NOBLESSE → Premium 男/女；LEGEND → Orient 男/女；其他职业返回 null |
| `static boolean isNewCharacterValid(Character character)` | 新角色外观总校验 | 取不到白名单（非法职业）返回 false；否则 `makeCharInfo.verifyCharacter(character)` |

### 3.5 BeginnerCreator（novice）

**概述**：新手（冒险家 BEGINNER，1 级）创建器。源码路径：`client/creator/novice/BeginnerCreator.java`。

**关键字段**：无实例字段（全静态）；依赖 `MapId.MUSHROOM_TOWN`、`MapId.BEIDOU_BEGINNER`、`ItemId.BEGINNERS_GUIDE`。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static CharacterFactoryRecipe createRecipe(Job job, int level, int map, int top, int bottom, int shoes, int weapon)`（private） | 组装配方 | 基础配方 + `BEGINNERS_GUIDE`×1 放 ETC 背包 |
| `static void giveItem(CharacterFactoryRecipe recipe, int itemid, int quantity, InventoryType itemType)`（private） | 加初始道具 | 转发 `recipe.addStartingItem` |
| `static int createCharacter(Client c, String name, int face, int hair, int skin, int top, int bottom, int shoes, int weapon, int gender)` | 创建新手角色 | 出生地图：`use_beidou_beginner_map` 开启用北斗新手村 `BEIDOU_BEGINNER`，否则蘑菇镇 `MUSHROOM_TOWN`；以 `Job.BEGINNER`、1 级调 `createNewCharacter` |

### 3.6 NoblesseCreator（novice）

**概述**：骑士团新手（NOBLESSE，1 级）创建器，结构与 BeginnerCreator 一致。源码路径：`client/creator/novice/NoblesseCreator.java`。

**关键字段**：依赖 `MapId.STARTING_MAP_NOBLESSE`、`ItemId.NOBLESSE_GUIDE`。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static CharacterFactoryRecipe createRecipe(...)`（private，同 3.5 签名） | 组装配方 | 基础配方 + `NOBLESSE_GUIDE`×1 入 ETC |
| `static void giveItem(...)`（private） | 加初始道具 | 转发 addStartingItem |
| `static int createCharacter(Client c, String name, int face, int hair, int skin, int top, int bottom, int shoes, int weapon, int gender)` | 创建骑士团新手 | `Job.NOBLESSE`、1 级、出生地 `STARTING_MAP_NOBLESSE` |

### 3.7 LegendCreator（novice）

**概述**：战神（LEGEND/Aran，1 级）创建器，结构与 BeginnerCreator 一致。源码路径：`client/creator/novice/LegendCreator.java`。

**关键字段**：依赖 `MapId.ARAN_TUTORIAL_START`、`ItemId.LEGENDS_GUIDE`。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static CharacterFactoryRecipe createRecipe(...)`（private） | 组装配方 | 基础配方 + `LEGENDS_GUIDE`×1 入 ETC |
| `static void giveItem(...)`（private） | 加初始道具 | 转发 addStartingItem |
| `static int createCharacter(Client c, String name, int face, int hair, int skin, int top, int bottom, int shoes, int weapon, int gender)` | 创建战神 | `Job.LEGEND`、1 级、出生地 `ARAN_TUTORIAL_START` |

### 3.8 WarriorCreator（veteran）

**概述**：30 级战士直升创建器（Maple Life 道具玩法）：出生 Perion，STR 35、AP 123、SP 61、HP 905（+洗血加成）、MP 208、10 万金币，附送备用武器三件与药水。`improveSp` 参数把「洗血卷」投入的 SP 换算为 HP 增益技能。源码路径：`client/creator/veteran/WarriorCreator.java`。

**关键字段**：`equips`（男女上衣/裤/鞋 5 元数组，按 gender 与 2+gender 取）、`weapons`（GLADIUS 主武器 + MITHRIL_POLE_ARM/MAUL/FIREMANS_AXE 备用）、`startingHpMp = {905, 208}`、`hpGain = {0,72,...,660}`（按 improveSp 档位的额外 HP）。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static CharacterFactoryRecipe createRecipe(Job job, int level, int map, int top, int bottom, int shoes, int weapon, int gender, int improveSp)`（private） | 组装战士配方 | str=35、AP=123、SP=61、maxHp=905+hpGain[improveSp]、maxMp=208、meso=10 万；女性（gender==1）补 DARK_ENGRIT 衣服；备用武器 weapons[1..] 全部入装备背包；improveSp>0 时先扣 SP（improveSp+5），`IMPROVED_HPREC` 5 点、余量给 `IMPROVED_MAXHP`；WHITE/BLUE_POTION 各 100、RELAXER×1 |
| `static void giveEquipment(CharacterFactoryRecipe recipe, ItemInformationProvider ii, int equipid)`（private） | 装备入配方 | `ii.getEquipById` 后 `addStartingEquipment` |
| `static void giveItem(...)`（private） | 道具入配方 | 转发 addStartingItem |
| `static int createCharacter(Client c, String name, int face, int hair, int skin, int gender, int improveSp)` | 创建 30 级战士 | `Job.WARRIOR`、30 级、`MapId.PERION`，按 gender 选三件套 |

### 3.9 MagicianCreator（veteran）

**概述**：30 级魔法师直升创建器：出生 Ellinia，INT 20、AP 138、SP 67、HP 405、MP 729（+洗魔加成）、10 万金币；improveSp 换算 MP 增益技能。源码路径：`client/creator/veteran/MagicianCreator.java`。

**关键字段**：`equips`（女性上衣/裙、鞋；男性位为 0 由 `BLUE_WIZARD_ROBE` 替代）、`weapons`（MITHRIL_WAND + CIRCLE_WINDED_STAFF）、`startingHpMp = {405, 729}`、`mpGain = {0,40,...,370}`。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static CharacterFactoryRecipe createRecipe(...)`（private，签名同 3.8） | 组装魔法师配方 | int=20、AP=138、SP=67、maxMp=729+mpGain[improveSp]；男性补 BLUE_WIZARD_ROBE；备用法杖入包；improveSp>0 扣 SP 后 `Magician.IMPROVED_MP_RECOVERY` 5 点、余量 `IMPROVED_MAX_MP_INCREASE`；ORANGE_POTION/ManaElixir 各 100、RELAXER×1 |
| `static void giveEquipment(...)` / `giveItem(...)`（private） | 入配方 | 同 3.8 |
| `static int createCharacter(Client c, String name, int face, int hair, int skin, int gender, int improveSp)` | 创建 30 级魔法师 | `Job.MAGICIAN`、30 级、`MapId.ELLINIA` |

### 3.10 BowmanCreator（veteran）

**概述**：30 级弓箭手直升创建器：出生 Henesys，DEX 25、AP 133、SP 61、HP 797、MP 404、10 万金币。无 improveSp 换算（洗血技能仅战士/魔法师系）。源码路径：`client/creator/veteran/BowmanCreator.java`。

**关键字段**：`equips`（男女绿色猎手衣/裤、鞋）、`weapons`（RYDEN 弩 + MOUNTAIN_CROSSBOW 备用）、`startingHpMp = {797, 404}`。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static CharacterFactoryRecipe createRecipe(Job job, int level, int map, int top, int bottom, int shoes, int weapon)`（private） | 组装弓手配方 | dex=25、AP=133、SP=61、HP/MP=797/404、meso=10 万；备用弩入包；WHITE/BLUE_POTION 各 100、RELAXER×1 |
| `static void giveEquipment(...)` / `giveItem(...)`（private） | 入配方 | 同 3.8 |
| `static int createCharacter(Client c, String name, int face, int hair, int skin, int gender, int improveSp)` | 创建 30 级弓箭手 | `Job.BOWMAN`、30 级、`MapId.HENESYS`（improveSp 参数被忽略） |

### 3.11 ThiefCreator（veteran）

**概述**：30 级飞侠直升创建器：出生 Kerning City，DEX 25、AP 133、SP 61、HP 794、MP 407、10 万金币，附 500 飞镖。源码路径：`client/creator/veteran/ThiefCreator.java`。

**关键字段**：`equips`（男女夜行衣/裤、青铜链靴）、`weapons`（STEEL_GUARDS 主手 + REEF_CLAW 备用拳套）、`startingHpMp = {794, 407}`。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static CharacterFactoryRecipe createRecipe(...)`（private，签名同 3.10） | 组装飞侠配方 | dex=25、AP=133、SP=61、HP/MP=794/407、meso=10 万；备用拳套入包；SUBI_THROWING_STARS×500 入 USE；WHITE/BLUE_POTION 各 100、RELAXER×1 |
| `static void giveEquipment(...)` / `giveItem(...)`（private） | 入配方 | 同 3.8 |
| `static int createCharacter(Client c, String name, int face, int hair, int skin, int gender, int improveSp)` | 创建 30 级飞侠 | `Job.THIEF`、30 级、`MapId.KERNING_CITY` |

### 3.12 PirateCreator（veteran）

**概述**：30 级海盗直升创建器：出生 Nautilus Harbor，DEX 20、AP 138、SP 61、HP 846、MP 503、10 万金币，附 800 子弹。源码路径：`client/creator/veteran/PirateCreator.java`。

**关键字段**：`equips`（仅鞋 BROWN_PAULIE_BOOTS，衣裤位为 0）、`weapons`（PRIME_HANDS 拳套 + COLD_MIND 备用枪）、`startingHpMp = {846, 503}`。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static CharacterFactoryRecipe createRecipe(...)`（private，签名同 3.10） | 组装海盗配方 | dex=20、AP=138、SP=61、HP/MP=846/503、meso=10 万；补 BROWN_POLLARD 头带；备用枪入包；BULLET×800 入 USE；WHITE/BLUE_POTION 各 100、RELAXER×1 |
| `static void giveEquipment(...)` / `giveItem(...)`（private） | 入配方 | 同 3.8 |
| `static int createCharacter(Client c, String name, int face, int hair, int skin, int gender, int improveSp)` | 创建 30 级海盗 | `Job.PIRATE`、30 级、`MapId.NAUTILUS_HARBOR` |

---

## 4. inventory 子包（背包与物品）

包结构：根 11 类 + `manipulator` 2 类。继承体系：`Item` ← `Equip`、`Item` ← `Pet`；`Inventory` ← `InventoryProof`。

### 4.1 Item

**概述**：普通物品（堆叠类/消耗品/宠物蛋等）基类，实现 `Comparable<Item>`。管理 itemId、格位、数量、所有者铭刻、flag 位、过期时间、cashId（商城物品唯一号）、宠物绑定与物品日志。`getItemType()` 约定：1=装备、2=普通、3=宠物。源码路径：`client/inventory/Item.java`。

**关键字段**：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `runningCashId` | `static AtomicInteger` | cashId 发号器，起点 777000000（宠物与戒指共用） |
| `id` | `final int` | itemId |
| `cashId` / `sn` | `int` | 商城物品唯一号（懒分配）/ 序列号 |
| `position` / `quantity` | `short` | 背包格位（负数为装备位）/ 数量 |
| `petid` / `pet` | `int` / `Pet` | 绑定宠物实例（petid=-1 表示非宠物） |
| `owner` / `giftFrom` | `String` | 铭刻主人 / 赠送者 |
| `itemLog` | `List<String>`（protected） | 物品流转日志 |
| `flag` | `short` | 属性位（UNTRADEABLE、KARMA、SANDBOX、ACCOUNT_SHARING 等，见 `ItemConstants`） |
| `expiration` | `long` | 过期时间戳，-1 永久 |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `Item(int id, short position, short quantity)` | 构造（非宠物） | 初始化 itemLog、flag=0 |
| `Item(int id, short position, short quantity, int petid)` | 构造（可绑定宠物） | `petid > -1` 时 `Pet.loadFromDb` 加载；加载失败（无记录）则降级 petid=-1，防止脏数据 |
| `Item copy()` | 浅拷贝物品 | 复制 flag/owner/expiration/itemLog（新列表）；注意不复制 cashId/sn |
| `void setPosition(short position)` | 设置格位 | 同步更新绑定宠物的格位 |
| `void setQuantity(short quantity)` | 设置数量 | 直接赋值（Equip 覆写为仅允许 0/1） |
| `int getItemId()` / `short getPosition()` / `short getQuantity()` / `String getOwner()` / `void setOwner(String)` / `int getPetId()` / `Pet getPet()` / `int getSN()` / `void setSN(int)` / `String getGiftFrom()` / `void setGiftFrom(String)` / `long getExpiration()` | 属性存取器 | 直接读写字段 |
| `int getCashId()` | 取/分配 cashId | `cashId == 0` 时从 `runningCashId` 原子自增分配后返回 |
| `InventoryType getInventoryType()` | 归属背包类型 | `ItemConstants.getInventoryType(id)`（按 itemId 区间） |
| `byte getItemType()` | 物品大类 | petid > -1 → 3（宠物）；否则 2（普通） |
| `int compareTo(Item other)` | 按 itemId 排序 | id 升序比较（相等为 0） |
| `String toString()` | 调试输出 | `"Item: {id} quantity: {quantity}"` |
| `List<String> getItemLog()` | 取物品日志 | 返回不可修改视图 |
| `short getFlag()` / `void setFlag(short b)` | flag 存取 | setFlag 时若 `ii.isAccountRestricted(id)` 自动补 `ACCOUNT_SHARING` 位（账号绑定物品服务端强制标记） |
| `void setExpiration(long expire)` | 设置过期时间 | 非永久物品按传入值；永久物品：宠物→`Long.MAX_VALUE`，其他→-1 |
| `boolean isUntradeable()` | 是否不可交易 | flag 含 UNTRADEABLE，或 WZ 标记 drop-restricted 且未打 Karma（可交易化）标记 |

### 4.2 Equip（继承 Item）

**概述**：装备类物品：21 项属性（四维/HP/MP/物魔攻防/命中/回避/手技/速度/跳跃/金锤）、升级槽位、装备等级与经验（装备成长系统）、戒指 ID、穿戴状态。包含完整的装备升级（gainLevel）与经验获取（gainItemExp）算法，BeiDou 在此基础上扩展了升级槽/金锤子概率配置与 i18n 提示。源码路径：`client/inventory/Equip.java`。

**内部枚举**：`ScrollResult`（FAIL=0/SUCCESS=1/CURSE=2，卷轴结果，本类仅定义供卷轴逻辑使用，含 `getValue()`）；`StatUpgrade`（16 个升级属性常量 incDEX/incSTR/incINT/incLUK/incMHP/incMMP/incPAD/incMAD/incPDD/incMDD/incEVA/incACC/incSpeed/incJump/incVicious/incSlot，各含数值编码）。

**关键字段**：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `upgradeSlots` | `byte` | 剩余砸卷（升级）次数 |
| `level` / `itemLevel` | `byte` | 卷轴强化等级 / 装备自身成长等级（`@Getter`） |
| `str`/`dex`/`_int`/`luk`/`hp`/`mp`/`watk`/`matk`/`wdef`/`mdef`/`acc`/`avoid`/`hands`/`speed`/`jump`/`vicious` | `short` | 装备属性（vicious=金锤子已用次数） |
| `itemExp` | `float` | 装备经验 |
| `ringid` | `int` | 戒指 ID（-1 非戒指） |
| `wear` | `boolean` | 是否处于穿戴状态 |
| `isUpgradeable` / `isElemental` | `boolean` | 本次升级是否有属性提升 / 是否成长型装备（timeless/reverse 等，`ii.getEquipLevel(id,false) > 1`） |

**方法表**（get/set 存取器合并列出）：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `Equip(int id, short position)` / `Equip(int id, short position, int slots)` | 构造 | quantity 固定 1；itemLevel=1、itemExp=0；按 WZ 判定 isElemental |
| `Item copy()` | 深拷贝装备 | 复制全部属性/等级/经验/升级槽/itemLog/owner/quantity/expiration/giftFrom |
| `byte getItemType()`（override） | 大类=装备 | 恒返回 1 |
| `short getFlag()` / `void setFlag(short)`（override） | flag 存取 | 直接读写（不走 Item 的 ACCOUNT_SHARING 补位逻辑） |
| `byte getUpgradeSlots()` / `void setUpgradeSlots(byte)` / `void setUpgradeSlots(int)` | 升级槽存取 | 两个 setter（byte/int 重载） |
| `short getStr()/getDex()/getInt()/getLuk()/getHp()/getMp()/getWatk()/getMatk()/getWdef()/getMdef()/getAcc()/getAvoid()/getHands()/getSpeed()/getJump()/getVicious()` 与对应 `setXxx(short)`（vicious 另有 `setVicious(int)`） | 21 项属性存取器 | 直接读写 |
| `byte getLevel()` / `void setLevel(byte)` | 卷轴强化等级存取 | 直接读写 |
| `int getItemExp()` / `void setItemExp(int)` / `byte getItemLevel()`（继承 @Getter）/ `void setItemLevel(byte)` | 成长经验/等级存取 | getItemExp 取整返回 |
| `void setQuantity(short quantity)`（override） | 数量仅允许 0/1 | 越界抛 RuntimeException（装备不可堆叠） |
| `int getRingId()` / `void setRingId(int)` / `boolean isWearing()` / `void wear(boolean)` | 戒指与穿戴状态 | 直接读写 |
| `static int getStatModifier(boolean isAttribute)`（private） | 升级点换算系数 | `use_equipment_level_up_power` 开启时属性点=2/其他=4，否则 4/16（越小越容易出加成） |
| `static int randomizeStatUpgrade(int top)`（private） | 随机升级点数 | 以 `max_equipment_level_up_stat_up` 封顶；按三角分布（poolCount=limit*(limit+1)/2+limit）随机，偏向小值 |
| `static boolean isPhysicalWeapon(int itemid)`（private） | 是否物理系武器 | 取模板 watk >= matk 判定 |
| `boolean isNotWeaponAffinity(StatUpgrade name)`（private） | 加成是否与武器系别不符 | 武器的 incPAD 在魔法武器上（或 incMAD 在物理武器上）为「不符」，期望收益减半（÷2.7） |
| `void getUnitStatUpgrade(List<Pair<StatUpgrade,Integer>> stats, StatUpgrade name, int curStat, boolean isAttribute)`（private） | 尝试为单项属性生成随机升级值 | 置 isUpgradeable=true；上限=1+curStat/(系数×亲和惩罚)，随机得 0 则跳过 |
| `static void getUnitSlotUpgrade(List<Pair<StatUpgrade,Integer>> stats, StatUpgrade name)`（private，默认 10%） | 概率附加升级槽/金锤子项 | 转发 0.1 概率版本 |
| `static void getUnitSlotUpgrade(List<Pair<StatUpgrade,Integer>> stats, StatUpgrade name, double chance)`（private） | 按概率附加 1 点 | `Math.random() <= chance` 时 `stats.add((name,1))` |
| `void UpgradeSlotProcessing(List<Pair<StatUpgrade,Integer>> stats, int equipLevel)`（private） | 升级时砸卷槽/金锤子判定 | `use_equipment_level_up_slots` 开启→10% 出 incSlot；`use_equipment_level_up_vicious` 开启且 vicious>0→按 `use_equipment_level_up_vicious_levelrange_chance`（JSON 二维数组 [minLevel,maxLevel,chance]）命中的档位概率出 incVicious（解析失败回退默认 {0,255,0.1}） |
| `void improveDefaultStats(List<Pair<StatUpgrade,Integer>> stats)`（private） | 生成默认随机属性升级表 | 对 14 项非零属性依次 getUnitStatUpgrade（四维按属性系数、其余按其他系数） |
| `Map<StatUpgrade, Short> getStats()` | 导出当前非零属性 | 14 项非零属性 → `StatUpgrade→值` 映射 |
| `Pair<String, Pair<Boolean,Boolean>> gainStats(List<Pair<StatUpgrade,Integer>> stats)` | 应用升级结果并生成提示 | incVicious→扣金锤子次数；incSlot→加升级槽；其余经 `handleStatUpgrade` 封顶（`max_equipment_stat`）后累加，正增量拼提示串（i18n `Equip.gainStats.<属性>` + "+值"）；返回（提示串,（是否加槽, 是否减金锤）） |
| `int handleStatUpgrade(StatUpgrade type, int value, int maxStat)`（private） | 单属性封顶累加 | 实际增量=min(value, maxStat-当前值)，>0 才 setCurrentStat |
| `int getCurrentStat(StatUpgrade type)` / `void setCurrentStat(StatUpgrade type, int value)`（private） | StatUpgrade ↔ 字段映射 | switch 14 项属性读写对应 short 字段 |
| `String getStatMessage(StatUpgrade type, int value)`（private） | 属性提示文案 | key = `Equip.gainStats.` + 枚举名去 inc 前缀（如 DEX），拼 "+值" |
| `void gainLevel(Client c)`（private） | 装备升 1 级 | 成长型装备优先用 WZ `getItemLevelupStats` 配方；无配方时 `improveDefaultStats` 随机；若仍可升级但空结果则循环重掷直到非空；itemLevel++；拼升级提示（i18n lvupStr/Vicious/UPGSLOT）；`equipChanged` 重算面板、showHint、dropMessage(6)、`showEquipmentLevelUp` + 地图广播特效(15)、`forceUpdateItem` 刷新 |
| `static double normalizedMasteryExp(int reqLevel)`（private） | 怪物经验→装备经验换算基准 | 分段曲线：<5→42；≥78→10413.648·e^(0.03275L)；≥38→4985.818·e^(0.02007L)；≥18→248.219·e^(0.11093L)；其余 1334.564·ln(L)-1731.976；下限 15 |
| `synchronized void gainItemExp(Client c, int gain)` | 装备获得经验并可能升级 | 不可升级（WZ）直接返回；最大等级=min(30, max(WZ 满级, `use_equipment_level_up`))，到顶返回；经验增量=gain×元素系数(0.85/0.6)×精通系数(`equip_exp_rate`×1 级怪物经验/normalizedMasteryExp)；`use_debug_show_eqp_exp` 打调试；累积到 `ExpTable.getEquipExpNeededForLevel` 触发 `gainLevel`（`use_equipment_level_up_continuous` 控制是否连升），最后 `forceUpdateItem` |
| `boolean reachedMaxLevel()`（private） | 是否已到成长上限 | 成长型看 WZ 满级；最终看 `use_equipment_level_up` 配置 |
| `String showEquipFeatures(Client c)` | 装备成长信息串 | 不可升级返回空；否则拼 `'名字' -> LV: x  EXP: y / z`（满级显示 MAX LEVEL），带 Maple 富文本色彩码 |

### 4.3 Inventory（实现 Iterable\<Item\>）

**概述**：单个背包容器：`LinkedHashMap<格位, Item>` + 公平锁。职责涵盖格位管理（取空位/扩缩容）、按 ID/名字/现金号查找、数量统计、物品移动合并（move/swap）、增删槽（含点卷倍率券联动），以及一组静态「能否放下」预检（checkSpot/checkSpots/checkSpotsAndOwnership），是交易/商店/仓库防刷背包的核心。源码路径：`client/inventory/Inventory.java`。

**关键字段**：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `inventory` | `Map<Short, Item>`（protected） | 格位 → 物品（LinkedHashMap 保序） |
| `type` | `InventoryType`（protected，final） | 背包类型 |
| `lock` | `Lock`（protected） | 公平 ReentrantLock，所有读写路径加锁 |
| `owner` | `Character`（protected） | 所属角色（点卷倍率刷新回调用；dispose 置 null） |
| `slotLimit` | `byte`（protected） | 格子上限 |
| `checked` | `boolean`（protected） | 排序标记（背包整理用） |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `Inventory(Character mc, InventoryType type, byte slotLimit)` | 构造 | 初始化容器与公平锁 |
| `boolean isExtendableInventory()` | 背包是否可扩容 | UNDEFINED/EQUIPPED/CASH 之外可扩 |
| `boolean isEquipInventory()` | 是否装备类背包 | EQUIP 或 EQUIPPED |
| `byte getSlotLimit()` / `void setSlotLimit(int newLimit)` | 格子上限存取 | set：缩容时把 position > newLimit 的物品逐个 removeSlot，再落新上限（加锁） |
| `Collection<Item> list()` | 快照全部物品 | 加锁拷贝为 ArrayList（防御性复制） |
| `Item findById(int itemId)` | 按 itemId 找首个物品 | 遍历 list() 比对 |
| `Item findByName(String name)` | 按物品名找 | `ii.getName` 取名比对（忽略大小写）；无名打 `[CRITICAL]` error |
| `int countById(int itemId)` | 统计某 ID 总数量 | 遍历累加 quantity |
| `int countNotOwnedById(int itemId)` | 统计无铭刻的某 ID 数量 | owner 为空串才计入（任务/合成常用） |
| `int freeSlotCountById(int itemId, int required)` | 收纳 required 个还需占几个已有格 | 非 rechargeable 按「已有格可吸收数量」计数，rechargeable 每格只算 1；能完全吸收返回已用格数，否则 -1 |
| `List<Item> listById(int itemId)` | 同 ID 物品列表（按格位升序） | 过滤 + 排序 |
| `List<Item> linkedListById(int itemId)` | 同上（LinkedList 版） | 供 PetAutopot 等需要从头消费的场景 |
| `short addItem(Item item)` | 新格入包 | `addSlot` 取空位，失败返回 -1；成功回写 item.position |
| `void addItemFromDB(Item item)` | 按既有格位恢复（DB/装备） | 非 EQUIPPED 背包遇到负格位直接丢弃 |
| `void move(short sSlot, short dSlot, short slotMax)` | 移动/合并/交换两格 | 加锁：目标空→平移；同 ID 且非 rechargeable 且同 owner → EQUIP/CASH 背包直接交换，否则超出 slotMax 拆分（源格留余量）、未超则并入目标并删源格；不同物品 → `swap` |
| `void swap(Item source, Item target)`（private） | 交换两物品格位 | 双删双放，互换 position |
| `Item getItem(short slot)` | 取指定格物品 | 加锁 get |
| `void removeItem(short slot)` / `void removeItem(short slot, short quantity, boolean allowZero)` | 扣减数量并可能删格 | quantity 扣到负数按 0 截断；数量归零且不允许 0 时 removeSlot |
| `short addSlot(Item item)`（protected） | 底层放格 | 加锁 `getNextFreeSlot` 后 put；若为点卷倍率券（`isRateCoupon`）异步 `ThreadManager.newTask(owner.updateCouponRates)`（防死锁） |
| `void addSlotFromDB(short slot, Item item)`（protected） | 底层按格放（DB 恢复） | 加锁 put；同样联动倍率券刷新 |
| `void removeSlot(short slot)` | 删除格位 | 加锁 remove；倍率券被移除同样异步刷新倍率 |
| `boolean isFull()` / `boolean isFull(int margin)` / `boolean isFullAfterSomeItems(int margin, int used)` | 满仓判定 | size>=limit；size+margin>=limit；size+margin>=limit-used（预留 used 格场景） |
| `short getNextFreeSlot()` | 找 1~slotLimit 首个空格 | 满仓返回 -1 |
| `short getNumFreeSlot()` | 统计空格数 | 满仓直接 0 |
| `static boolean checkItemRestricted(List<Pair<Item,InventoryType>> items)`（private） | 唯一物品（pickup-restricted）数量校验 | 任一受限物品 quantity > 1 即 false（不可堆叠唯一物） |
| `static boolean checkSpot(Character chr, Item item)` / `static boolean checkSpot(Character chr, List<Item> items)` | 预检能否放下（单件/列表） | 包装为 Pair 列表后调 `checkSpotsAndOwnership(chr, list)`（雇佣商店取回物品时校验堆叠） |
| `static boolean checkSpots(Character chr, List<Pair<Item,InventoryType>> items)` / `static boolean checkSpots(Character chr, List<Pair<Item,InventoryType>> items, boolean useProofInv)` | 预检（不区分 owner） | 构造全 0 的已用格数组转调 4 参版本；useProofInv=true 时用 CANHOLD 影子背包 |
| `static boolean checkSpots(Character chr, List<Pair<Item,InventoryType>> items, List<Integer> typesSlotsUsed, boolean useProofInv)` | 预检核心 | 先 `checkItemRestricted`；按 itemId 聚合数量（装备/可充电物不聚合堆叠）；逐项调 `InventoryManipulator.checkSpaceProgressively`，返回值 bit0=有无空间、高位=新的已用格数，滚动更新 typesSlotsUsed；任一项放不下即 false |
| `static boolean checkSpotsAndOwnership(Character chr, List<Pair<Item,InventoryType>> items)` / `... (…, boolean useProofInv)` / `... (…, List<Integer> typesSlotsUsed, boolean useProofInv)` | 预检（区分 owner 铭刻） | 与 checkSpots 同构，但聚合 key 用 `hashKey(itemId, owner)`（itemId 左移 32 位 + owner 的 FNV-1a 32 位哈希），同 ID 不同铭刻不互相堆叠 |
| `static long fnvHash32(String k)`（private） / `static Long hashKey(Integer itemId, String owner)`（private） | 聚合 key 计算 | FNV-1a 32 位（负数映射到 long 正区间）；`(itemId << 32) + hash(owner)` |
| `InventoryType getType()` | 背包类型 | 直接返回 |
| `Iterator<Item> iterator()` | for-each 支持 | 基于不可变快照 list() |
| `Item findByCashId(int cashId)` | 按现金号找物品 | 宠物用 petId、戒指用 ringId、其余用 cashId 比对 |
| `boolean checked()` / `void checked(boolean yes)` | 排序标记存取 | 加锁读写 |
| `void lockInventory()` / `void unlockInventory()` | 外部持锁接口 | 暴露内部公平锁（Manipulator/处理器组合操作用） |
| `void dispose()` | 释放引用 | owner 置 null（角色下线清理） |

### 4.4 InventoryProof（继承 Inventory）

**概述**：CANHOLD 类型「影子背包」：只有格位形状、无副作用，用于交易等场景先克隆玩家背包、模拟增删物品来验证「拿走 A 后能否放下 B」。覆写三个槽操作去掉点卷倍率刷新副作用。源码路径：`client/inventory/InventoryProof.java`。

**关键字段**：无新增（复用父类；构造时 type=CANHOLD、slotLimit=0）。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `InventoryProof(Character mc)` | 构造 | `super(mc, InventoryType.CANHOLD, (byte) 0)` |
| `void cloneContents(Inventory inv)` | 克隆目标背包内容 | 双锁（先 inv 后 this）；清空自身、同步 slotLimit；把每个物品克隆为普通 `Item`（仅 itemId/position/quantity）放入 |
| `void flushContents()` | 清空影子背包 | 加锁 clear |
| `short addSlot(Item item)`（override，protected） | 放格（无副作用） | 加锁取空位 put；不触发倍率券刷新 |
| `void addSlotFromDB(short slot, Item item)`（override，protected） | 按格放（无副作用） | 加锁 put |
| `void removeSlot(short slot)`（override） | 删格（无副作用） | 加锁 remove |

### 4.5 InventoryType（枚举）

**概述**：背包类型枚举：UNDEFINED(0)、EQUIP(1)、USE(2)、SETUP(3)、ETC(4)、CASH(5)、CANHOLD(6，校验用影子类型)、EQUIPPED(-1，身上装备)。`@Getter` 提供 type/name。源码路径：`client/inventory/InventoryType.java`。

**关键字段**：`type`（byte 协议编码）、`name`（i18n 显示名 `InventoryType.<枚举>`）。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `InventoryType(int type, String name)` | 枚举构造 | 存协议码与显示名 |
| `short getBitfieldEncoding()` | 位域编码 | `2 << type`（客户端背包掩码包用；EQUIPPED 的 -1 会得到异常值，实际只对正序类型使用） |
| `static InventoryType getByType(byte type)` | 协议码→枚举 | 遍历 values 匹配，未命中 UNDEFINED |
| `static InventoryType getByWZName(String name)` | WZ 分类名→枚举 | Install→SETUP、Consume→USE、Etc→ETC、Cash→CASH、Pet→CASH（宠物归现金背包）、其余 UNDEFINED |
| `boolean canChangeSlotMax()` | 该背包物品数量上限是否可改（叠加上限设置） | 仅 USE/ETC（CASH 如需支持需改此处） |
| `boolean isEquip()` | 是否装备类 | EQUIP 或 EQUIPPED |

### 4.6 ItemFactory（枚举）

**概述**：物品持久化工厂（每个枚举值对应 `inventoryitems.type` 一类存储场景）：INVENTORY(1, 角色)、STORAGE(2, 仓库，按账号)、CASH_EXPLORER/CASH_CYGNUS/CASH_ARAN/CASH_OVERALL(3/4/5/7, 购物袋，按账号)、MERCHANT(6, 雇佣商人)、MARRIAGE_GIFTS(8, 结婚礼物)、DUEY(9, 快递)。负责 `inventoryitems`/`inventoryequipment`/`inventorymerchant` 三表的整读整写，带 400 把分段锁与事务保护。源码路径：`client/inventory/ItemFactory.java`。

**关键字段**：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `value` | `final int` | type 协议值（DB 列值） |
| `account` | `final boolean` | true=按 `accountid` 归档（仓库/购物袋），false=按 `characterid` |
| `locks` | `static Lock[400]` | 分段公平锁数组，`id % 400` 取锁，消除全库保存瓶颈 |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `int getValue()` | 取 type 值 | 直接返回 |
| `List<Pair<Item, InventoryType>> loadItems(int id, boolean login)` | 按角色/账号 id 装载物品 | id 语义：仓库为 storageId、其余为角色/账号 id；login=true 只装载 EQUIPPED（登录先穿装备）；MERCHANT 走 `loadItemsMerchant`（带 bundles 数量），其余走 `loadItemsCommon` |
| `void saveItems(List<Pair<Item,InventoryType>> items, int id, Connection con)` | 保存（无 bundles） | 转发两参 bundles=null 版本 |
| `void saveItems(List<Pair<Item,InventoryType>> items, List<Short> bundlesList, int id, Connection con)` | 整体保存 | 非 MERCHANT 走 `saveItemsCommon`，MERCHANT 走 `saveItemsMerchant`（需 bundles 列表，与 items 一一对应） |
| `static Equip loadEquipFromResultSet(ResultSet rs)`（private） | 行→Equip | 还原 owner/quantity/16 项属性/flag/升级槽/level/itemExp/itemLevel/expiration/giftFrom/ringid |
| `static List<Pair<Item,Integer>> loadEquippedItems(int id, boolean isAccount, boolean login)` | 跨角色装载身上装备 | characters 表 RIGHT JOIN inventoryitems+inventoryequipment；按 accountid 或 characterid 过滤，login 时仅 EQUIPPED；返回（Equip, characterid）列表（供登录时他处使用） |
| `List<Pair<Item,InventoryType>> loadItemsCommon(int id, boolean login)`（private） | 通用装载 | `inventoryitems LEFT JOIN inventoryequipment`，type=? 且 accountid/characterid=?；EQUIP/EQUIPPED 建 Equip，其余建 Item（petid 判 wasNull 归 -1），恢复 owner/expiration/giftFrom/flag |
| `void saveItemsCommon(List<Pair<Item,InventoryType>> items, int id, Connection con)`（private） | 通用整存 | 分段锁；事务策略：仅当调用方 autoCommit=true 时自己 setAutoCommit(false)+commit/rollback（`Character.saveCharToDB` 已开事务则不接管，防「DELETE 已提交而 INSERT 失败」丢物品）；先 DELETE 旧 items+equipment，再逐条 INSERT inventoryitems（RETURN_GENERATED_KEYS），装备续 INSERT inventoryequipment（23 列） |
| `List<Pair<Item,InventoryType>> loadItemsMerchant(int id, boolean login)`（private） | 雇佣商人装载 | 同 Common 查询，另查 `inventorymerchant.bundles`；数量 = bundles × quantity（装备不乘）；bundles=0 的非装备物品跳过（已售出） |
| `void saveItemsMerchant(List<Pair<Item,InventoryType>> items, List<Short> bundlesList, int id, Connection con)`（private） | 雇佣商人整存 | 分段锁 + 同款事务策略；DELETE inventorymerchant + DELETE items/equipment；逐条 INSERT items → INSERT inventorymerchant(inventoryitemid, characterid, bundles) → 装备续 INSERT equipment |

### 4.7 ModifyInventory

**概述**：背包变更通知项：`PacketCreator.modifyInventory` 的最小单元，mode 0=新增、1=数量/属性更新、2=移动、3=删除；持有物品快照（copy）与旧格位。源码路径：`client/inventory/ModifyInventory.java`。

**关键字段**：`mode`（int 模式码）、`item`（Item，构造时 copy 的快照）、`oldPos`（short 旧格位）。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `ModifyInventory(int mode, Item item)` | 构造（无旧格位） | 快照 item |
| `ModifyInventory(int mode, Item item, short oldPos)` | 构造（含旧格位） | 快照 item + 保存 oldPos（mode 2 移动用） |
| `int getMode()` / `short getPosition()` / `short getOldPosition()` / `short getQuantity()` / `Item getItem()` | 取快照字段 | 直接返回（getInventoryType 经 item.getInventoryType().getType() 取协议码） |
| `int getInventoryType()` | 取背包类型码 | 快照物品的 InventoryType 协议值 |
| `void clear()` | 释放快照引用 | item 置 null（发包后回收） |

### 4.8 Pet（继承 Item）

**概述**：宠物：名字/亲密度(tameness)/等级/饱食度(fullness)成长体系、召唤状态与地图位置（fh/pos/stance）、宠物属性位（ OWNER_SPEED 主人加速）；直接读写 `pets` 表（loadFromDb/saveToDb/createPet/deleteFromDb）。源码路径：`client/inventory/Pet.java`。

**内部枚举**：`PetAttribute`——`OWNER_SPEED(0x01)`（佩戴时给主人加移速），含 `getValue()`。

**关键字段**：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `name` / `uniqueid` | `String` / `int` | 宠物名 / 唯一 petId（即 cashId） |
| `tameness` / `level` / `fullness` | `int` / `byte` / `int` | 亲密度（上限 30000）/ 等级（上限 30）/ 饱食度（上限 100，初始 100） |
| `Fh` / `pos` / `stance` | `int` / `Point` / `int` | 地图落脚点 / 坐标 / 朝向姿态 |
| `summoned` | `boolean` | 是否已召唤 |
| `petAttribute` | `int` | 属性位掩码 |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `Pet(int id, short position, int uniqueid)`（private） | 构造 | quantity=1、pos=(0,0) |
| `static Pet loadFromDb(int itemid, short position, int petid)` | 从 `pets` 表装载 | 查 name/level/closeness/fullness/summoned/flag；closeness 封顶 30000、level 封顶 30、fullness 封顶 100；SQLException 打栈返回 null |
| `static void deleteFromDb(Character owner, int petid)` | 删除宠物记录 | `owner.deletePetExcludedData(petid)` 清内存中的拾取过滤缓存（DB 中 petignores 靠外键级联删）；`CashIdGenerator.freeCashId(petid)` 回收号段 |
| `void saveToDb()` | 保存宠物状态 | UPDATE pets SET name/level/closeness/fullness/summoned/flag WHERE petid=? |
| `static int createPet(int itemid)` | 创建宠物（初始态） | `CashIdGenerator.generateCashId()` 生成 petid，INSERT（name=WZ 名，level=1，closeness=0，fullness=100）；失败 -1 |
| `static int createPet(int itemid, byte level, int tameness, int fullness)` | 创建宠物（指定成长态） | 同上，可指定 level/tameness/fullness |
| `String getName()` / `void setName(String)` / `int getUniqueId()` / `void setUniqueId(int)` / `int getTameness()` / `void setTameness(int)` / `byte getLevel()` / `void setLevel(byte)` / `int getFullness()` / `void setFullness(int)` / `int getFh()` / `void setFh(int)` / `Point getPos()` / `void setPos(Point)` / `int getStance()` / `void setStance(int)` / `boolean isSummoned()` / `void setSummoned(boolean)` / `int getPetAttribute()` | 成长与位置存取器 | 直接读写 |
| `void gainTamenessFullness(Character owner, int incTameness, int incFullness, int type)` | 喂食/指令入口 | 转发 forceEnjoy=false 版本 |
| `void gainTamenessFullness(Character owner, int incTameness, int incFullness, int type, boolean forceEnjoy)` | 喂食与好感成长核心 | 饱食度<100、或 incFullness==0（指令互动）、或 forceEnjoy（商城道具）时「有效」：fullness 增（封顶 100）；tameness 增（封顶 30000）并按 `ExpTable.getTamenessNeededForLevel` 连升等级（发升级包 showOwnPetLevelUp/showPetLevelUp）；无效喂食（满腹喂食）反而 tameness-1 并可能掉级；广播 `petFoodResponse`（enjoyed 标志+聊天泡泡）；saveToDb 后 forceUpdateItem 刷新 CASH 背包格 |
| `void addPetAttribute(Character owner, PetAttribute flag)` | 加属性位 | `petAttribute |= flag` → saveToDb → forceUpdateItem |
| `void removePetAttribute(Character owner, PetAttribute flag)` | 去属性位 | `petAttribute &= ~flag`（用 `0xFFFFFFFF ^ value` 实现）→ saveToDb → forceUpdateItem |
| `Pair<Integer, Boolean> canConsume(int itemId)` | 能否食用某道具 | 委托 `ii.canPetConsume(宠物itemId, 道具itemId)` |
| `void updatePosition(List<LifeMovementFragment> movement)` | 按移动包更新宠物位置 | 遍历移动片段：AbsoluteLifeMovement 更新 pos；LifeMovement 更新 stance |

### 4.9 PetCommand

**概述**：宠物指令数据（WZ `Pet/<petid>.img/interact/<skillId>`）：某宠物对某指令的成功概率与亲密度增量。源码路径：`client/inventory/PetCommand.java`。

**关键字段**：`petId`、`skillId`、`prob`（int 成功概率）、`inc`（int 亲密度增量）。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `PetCommand(int petId, int skillId, int prob, int inc)` | 构造 | 保存四元组 |
| `int getPetId()` / `int getSkillId()` / `int getProbability()` / `int getIncrease()` | 取字段 | 直接返回 |

### 4.10 PetDataFactory

**概述**：宠物 WZ 数据缓存工厂：解析并缓存 `Item/Pet/<petid>.img` 的指令（interact）与饥饿值（info/hungry），双检锁懒加载。源码路径：`client/inventory/PetDataFactory.java`。

**关键字段**：`dataRoot`（ITEM WZ DataProvider）、`petCommands`（`Map<String, PetCommand>`，key=`petId+""+skillId`）、`petHunger`（`Map<Integer, Integer>`）。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static PetCommand getPetCommand(int petId, int skillId)` | 取指令数据（缓存） | 无锁查缓存 → miss 进 synchronized 再查 → 仍无则读 WZ `interact/<skillId>/prob|inc`（无数据按 0）建 PetCommand 入缓存 |
| `static int getHunger(int petId)` | 取饥饿消耗（缓存） | 双检锁模式读 `info/hungry`，默认 1（每次饥饿 tick 扣的饱食度） |

### 4.11 WeaponType（枚举）

**概述**：武器类型 × 最大伤害系数表（武器公式常数，供伤害计算模块取用）：NOT_A_WEAPON(0)、单双手剑/锤/斧（挥/刺分系数）、弓/弩/拳套/短刀/枪/指节/长枪/矛/法杖/魔杖等 20 项。源码路径：`client/inventory/WeaponType.java`。

**关键字段**：`damageMultiplier`（double 最大伤害倍率，如 SWORD2H 4.6、POLE_ARM_SWING 5.0）。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `WeaponType(double maxDamageMultiplier)` | 枚举构造 | 存系数 |
| `double getMaxDamageMultiplier()` | 取最大伤害系数 | 直接返回 |

### 4.12 manipulator/InventoryManipulator

**概述**：背包操作工具类（全静态）：入包（addById/addFromDrop，含堆叠合并、沙盒物品标记）、空间预检（checkSpace/checkSpaceProgressively）、扣减（removeFromSlot/removeById）、移动合并（move）、穿脱装备（equip/unequip，含套装冲突处理）、丢弃（drop，含消失类物品判定）。是绝大多数 Handler 的落包通道。源码路径：`client/inventory/manipulator/InventoryManipulator.java`。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static boolean addById(Client c, int itemId, short quantity)` / `addById(Client c, int itemId, short quantity, long expiration)` / `addById(Client c, int itemId, short quantity, String owner, int petid)` / `addById(Client c, int itemId, short quantity, String owner, int petid, long expiration)` | 按 ID 入包（四级重载） | 补默认参后转发全参版本 |
| `static boolean addById(Client c, int itemId, short quantity, String owner, int petid, short flag, long expiration)` | 按 ID 入包（全参） | 取目标背包并 `lockInventory`，调 addByIdInternal |
| `static boolean addByIdInternal(...)`（private） | 入包实现 | 非装备：先遍历同 ID 已有格补满 slotMax（需 owner 兼容且 flag 相同），差额开新格（新 Item 带 petid/flag/expiration），满包发 inventoryFull+showInventoryFull 并 false；rechargeable 或带宠物的物品不堆叠直接开新格；装备：quantity 必须 1（否则抛异常），`ii.getEquipById` 建装备后入包；SANDBOX 标记物品置 `chr.setHasSandboxItem`；每步发 modifyInventory(0/1) |
| `static boolean addFromDrop(Client c, Item item)` / `addFromDrop(Client c, Item item, boolean show)` / `addFromDrop(Client c, Item item, boolean show, int petId)` | 拾取入包（三级重载） | 锁背包后调 addFromDropInternal；show 控制是否发获得提示 |
| `static boolean addFromDropInternal(...)`（private） | 拾取实现 | 先查唯一物（pickup-restricted 且已持有→showItemUnavailable false）；堆叠逻辑同 addById（owner/flag 全等才并）；放不下时回滚 item 数量（供地上物品保留）；装备 quantity>1 拒收；成功且 show 发 getShowItemGain |
| `static boolean haveItemWithId(Inventory inv, int itemid)`（private） | 背包内是否有某 ID | `inv.findById != null` |
| `static boolean checkSpace(Client c, int itemid, int quantity, String owner)` | 一次性空间预检 | 唯一物已持有（背包或已装备）false；非装备按已有格可吸收量折算需求格数（rechargeable 恒 1 格）判 isFull；装备判 isFull |
| `static int checkSpaceProgressively(Client c, int itemid, int quantity, String owner, int usedSlots, boolean useProofInv)` | 增量空间预检 | 返回值编码：bit0=是否放得下、其余位=新的累计已用格数（供 checkSpots 系列滚动累计）；useProofInv 时对 CANHOLD 影子背包判定；假设装备 slotMax==1 |
| `static void removeFromSlot(Client c, InventoryType type, short slot, short quantity, boolean fromDrop)` / `removeFromSlot(..., boolean consume)` | 从指定格扣减 | consume 且 rechargeable 时允许数量到 0 保留空格（子弹壳）；EQUIPPED 格：锁背包→`chr.unequippedItem`（重算面板）→removeItem→广播；宠物物品：先对已召唤宠物 `unEquipPet` 再删；CANHOLD 背包不发包；最后 announceModifyInventory |
| `static void announceModifyInventory(Client c, Item item, boolean fromDrop, boolean allowZero)`（private） | 发扣减结果包 | 数量归零且非 allowZero → mode 3（删除），否则 mode 1（更新） |
| `static void removeById(Client c, InventoryType type, int itemId, int quantity, boolean fromDrop, boolean consume)` | 按 ID/现金号扣够数量 | EQUIPPED 按负格位 0~-128 遍历，其余 0~slotLimit；itemId 匹配或 cashId 匹配（商城物品回收）；逐格扣至够数；仍不足且非 CANHOLD 抛 `[Hack]` RuntimeException |
| `static void move(Client c, InventoryType type, short src, short dst)` | 玩家拖动物品 | 负格位/越界拒绝；`inv.move(src,dst,slotMax)` 后按合并/交换/删除组 ModifyInventory(mode 1/2/3) 发包；`use_debug` 且 GM 时 dropMessage 显示物品 ID（i18n `InventoryManipulator.handlePacket.message1`） |
| `static void equip(Client c, short src, short dst)` | 穿装备（EQUIP→EQUIPPED） | ①`use_equipment_gender_limit` 开启时校验装备性别（不符弹窗+i18n 日志拒绝）；②`ii.canWearEquipment`（等级/属性需求）与坐骑阵营（冒险家/骑士团互斥）校验；③「装备后绑定」物品打 UNTRADEABLE；④套装冲突预处理：穿连体裤(-6)先脱上身(-5)、穿上身为连体时先脱下身、双手武器(-10/-11)互斥互脱；⑤坐骑(-18)更新 MapleMount 外观；⑥EQUIPPED 目标格有装备则先卸回 EQUIP；戒指 `chr.getRingById(...).equip()`、`chr.equippedItem` 重算面板、buff 武器切换取消 BOOSTER；宠物名牌位换装广播宠物改名包；发 mode 2 移动包 + `equipChanged` |
| `static void unequip(Client c, short src, short dst)` | 脱装备（EQUIPPED→EQUIP） | dst 为负或源空拒绝；目标格占用时交换；戒指 `unequip()`、`unequippedItem` 重算；宠物名牌位同样广播改名；发 mode 2 包 + `equipChanged` |
| `static boolean isDisappearingItemDrop(Item it)`（private） | 丢弃后是否「消失」（不落地） | drop-restricted、现金物品（`use_enforce_unmerchable_cash`）/宠物（`use_enforce_unmerchable_pet`）、`use_erase_untradeable_drop` 下的不可交易物、结婚戒指 → true（用 disappearingItemDrop 静默销毁） |
| `static void drop(Client c, InventoryType type, short src, short quantity)` | 丢物落地 | src<0 视为脱身上装备；GM 低于 `minimum_gm_level_to_drop` 禁止；交易中/小游戏/空格拒绝；已召唤宠物先收回；部分丢弃：复制出目标数量、源格扣减、新年贺卡 ETC 特判（联动删除卡片记录），可消失物走 disappearingItemDrop 否则 spawnItemDrop；整格丢弃：EQUIPPED 需先 unequippedItem；最后联动清物品特效/黑板/阿里安积分 |
| `static boolean isDroppedItemRestricted(Item it)`（private） | 不可交易物落地即毁判定 | `use_erase_untradeable_drop` && `it.isUntradeable()` |
| `static boolean isSandboxItem(Item it)` | 是否沙盒物品（测试/副本专用） | flag 含 `ItemConstants.SANDBOX` |

### 4.13 manipulator/KarmaManipulator

**概述**：命运（Karma）剪刀标记操作：装备用 `KARMA_EQP`、消耗品用 `KARMA_USE` 位；带 Karma 标记的「绑定不可交易物」变为可交易，使用后（取出/交易）标记翻转为 UNTRADEABLE（一次性）。源码路径：`client/inventory/manipulator/KarmaManipulator.java`。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static short getKarmaFlag(Item item)`（private） | 取该物品对应的 Karma 位 | `item.getItemType() == 1`（装备）→ KARMA_EQP，否则 KARMA_USE |
| `static boolean hasKarmaFlag(Item item)` | 是否已剪（可交易） | flag 与 Karma 位按位与全等 |
| `static void toggleKarmaFlagToUntradeable(Item item)` | 使用后翻转为不可交易 | 当前有 Karma 位时：XOR 去掉 Karma、OR 上 UNTRADEABLE（仓库取出/寄售时调用） |
| `static void setKarmaFlag(Item item)` | 打上 Karma 位（剪成功） | OR Karma 位并清除 UNTRADEABLE 位（`&= 0xFFFFFFFF ^ UNTRADEABLE`） |

---

## 5. keybind 子包（按键绑定）

### 5.1 KeyBinding

**概述**：单个功能键绑定（不可变值对象）：`type`=按键类型、`action`=绑定动作码（技能/道具/菜单等），随角色存取（`keymap` 表）。源码路径：`client/keybind/KeyBinding.java`。

**关键字段**：`type`（final int）、`action`（final int）。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `KeyBinding(int type, int action)` | 构造 | 保存二元组 |
| `int getType()` / `int getAction()` | 取字段 | 直接返回 |

### 5.2 QuickslotBinding

**概述**：快捷键栏（8 格，v83 新手引导解锁的底部快捷栏）绑定：默认值 `{0x2A,0x52,0x47,0x49,0x1D,0x53,0x4F,0x51}`；负责向客户端出站包编码——与默认一致时只写 1 字节 false（客户端自动套默认），否则逐键按 int 写出（防 error 38 崩溃）。源码路径：`client/keybind/QuickslotBinding.java`。

**关键字段**：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `QUICKSLOT_SIZE` | `static final int` | 恒 8 |
| `DEFAULT_QUICKSLOTS` | `static final byte[8]` | 客户端默认键位 |
| `m_aQuickslotKeyMapped` | `final byte[]` | 玩家实际键位（构造时 clone 防外部修改） |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `QuickslotBinding(byte[] aKeys)` | 构造 | 长度必须为 8，否则 `IllegalArgumentException`；clone 保存 |
| `void encode(OutPacket p)` | 编码进出站包 | 等于默认 → `writeBool(false)` 收工；否则 `writeBool(true)` 后逐键 `writeInt`（Nexon 协议按 int 编码，虽值域 ≤0xFF） |
| `byte[] GetKeybindings()` | 取原始键位数组 | 返回内部引用（命名沿袭 C# 风格遗留） |

---

## 6. processor 子包（包处理器）

包结构：`action` 3 类（宠物/装备制造动作）、`npc` 3 类（仓库/快递/雇佣商人 NPC 交互）、`stat` 2 类（AP/SP 分配）。均为从对应 Handler 抽离的纯静态（FredrickProcessor 除外，持有 Spring 注入的 NoteService）处理逻辑，入口统一先抢 `Client` 会话锁。

### 6.1 stat/AssignAPProcessor

**概述**：属性点（AP）分配处理器：自动分配（APAutoAssignAction，Ronan 智能分配器，按职业策略+装备属性推算四维）、手动分配（APAssignAction，编码 64/128/256/512/2048/8192 → STR/DEX/INT/LUK/HP/MP）、洗点重置（APResetAction），含各职业 HP/MP 成长与洗血/洗魔数值表。源码路径：`client/processor/stat/AssignAPProcessor.java`。

**关键字段**（static 配置缓存，`reloadConfig()` 从 GameConfig 重读）：`useServerAutoAssigner`、`useAutoAssignSecondaryCap`（副属性封顶回流主属性）、`useEnforceHpmpSwap`（洗血蓝只能互换）、`useFixedRatioHpmpUpdate`（固定比例血蓝更新）、`useRandomizeHpmpGain`（血蓝随机成长）、`maxAp`（单属性上限）。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static void reloadConfig()` | 重读配置 | 6 个配置项从 GameConfig 装入静态字段（多处调用点刷新，支持热更） |
| `static void APAutoAssignAction(InPacket inPacket, Client c)` | AP 自动分配 | 剩余 AP<1 返回；`c.lockClient()`；**服务端分配器**（useServerAutoAssigner）：读 opt 字节（海盗职业分支），统计 EQUIPPED 全部装备四维与前两高装备值（eqpStr/eqpDex/eqpLuk），按职业 switch 分配：魔法师主 INT 副 LUK（副 CAP 165，需求=level+3-有效LUK）、弓手主 DEX 副 STR（CAP 125）、弩手/枪手（CAP 120）、飞侠主 LUK 副 DEX（CAP 160，DEX 补到 2×level 再到 level+40，STR 达阈值 max(13, 0.4×level) 后补到 50 再到 20+level/2）、默认（战士等）主 STR 副 DEX（CAP 300，按等级段 80/96 档位目标曲线）；副属性超 CAP 时溢出回流主属性；三级属性依次 `gainStatByType`，余量再走主→副→三→第四属性（非魔法师第四属性 INT、魔法师 STR）；`chr.assignStrDexIntLuk` 落库，发弹窗公告分配结果。**非服务端分配器**：校验包长（<16 判 PACKET_EDIT 踢线），读两组（type,value）手动加成（value 越界拒绝），`Stat.getBy5ByteEncoding` 解码后 gainStatByType |
| `static int getNthHighestStat(List<Short> statList, short rank)`（private） | 取降序第 rank 高的装备属性 | 越界返回 0 |
| `static int gainStatByType(Stat type, int[] statGain, int gain, int[] statUpdate)`（private） | 单属性加成（封顶） | gain≤0 返回 0；按 STR/DEX/INT/LUK 更新 statGain/statUpdate，超过 maxAp 截断；返回溢出量（未能分配的点） |
| `static Stat getQuaternaryStat(Job stance)`（private） | 第四兜底属性 | 非魔法师→INT，魔法师→STR |
| `static boolean APResetAction(Client c, int APFrom, int APTo)` | 洗点（能力值重置卷） | `c.lockClient()`；按 APFrom 编码扣 1 点：STR/DEX/INT/LUK 需当前值≥5 且 `assignXxx(-1)` 成功；HP 需 `useEnforceHpmpSwap` 下只能洗向 MP（8192），`hpMpApUsed ≥ 1`，且 maxHp ≥ level×14+148（下限保护），扣 `takeHp(job)`；MP 对称（只能洗向 HP，各职业下限：枪骑士 4L+156、斗士/战神 4L+56、二转飞侠 14L-4、其余 14L+148），扣 `takeMp(job)`；扣成功后 `addStat(player, APTo, true)` 加到目标；非法编码发空 updatePlayerStats 返回 false |
| `static void APAssignAction(Client c, int num)` | 手动加 1 点 AP | 锁客户端后 `addStat(player, num, false)` |
| `static boolean addStat(Character chr, int apTo, boolean usedAPReset)`（private） | 目标属性 +1 点 | 64/128/256/512 调 assignStr/Dex/Int/Luk(1)；2048/8192 调 `calcHpChange/calcMpChange` 后 assignHP/assignMP(值,1)，usedAPReset 时 dropMessage 展示 `[重置卷轴] 最大HP/MP +x`；非法编码发空属性包 false |
| `static int calcHpChange(Character player, boolean usedAPReset)`（private） | HP 成长计算 | 按职业取（base,reset,min,max）参数：战士/圣骑 (20,20,18,22)带 `Warrior.IMPROVED_MAXHP`/`DawnWarrior.MAX_HP_INCREASE` 技能加成、战神 (28,20,26,30)、法系 (6,6,5,9)、飞侠/弓手 (16,16,14,18)、海盗 (18,18,16,20)带 `Brawler.IMPROVE_MAX_HP`/`ThunderBreaker.IMPROVE_MAX_HP`、默认 (10,8,8,12)；非重置且带技能时加技能 effect 的 Y 值；重置加 reset 值；`useRandomizeHpmpGain` 且重置时用随机区间 |
| `static int calcMpChange(Character player, boolean usedAPReset)`（private） | MP 成长计算 | 同构：战士系 (3,2,2,4,INT 系数 0.1)、法系 (18,18,12,16,0.05)带 `Magician.IMPROVED_MAX_MP_INCREASE`/`BlazeWizard.INCREASING_MAX_MP`、弓/飞 (10,10,6,8)、海盗 (14,14,7,9)、默认 (6,6,4,6,0.5)；随机模式下额外加 `INT×系数` |
| `static int takeHp(Job job)`（private） | 洗 HP 时扣减量 | 战士系 54 / 法系 10 / 飞侠 20 / 弓手 20 / 海盗 42 / 默认 12 |
| `static int takeMp(Job job)`（private） | 洗 MP 时扣减量 | 战士系 4 / 法系 31 / 弓手 12 / 飞侠 12 / 海盗 16 / 默认 8 |

### 6.2 stat/AssignSPProcessor

**概述**：技能点（SP）分配处理器：合法性校验（canSPAssign，防跨职业技能/任务技能/GM 技能封包）与升级技能（SPAssignAction，含新手技能共享上限与战影技能联动）。源码路径：`client/processor/stat/AssignSPProcessor.java`。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static boolean canSPAssign(Client c, int skillid)` | SP 分配合法性 | 战神隐藏技能（HIDDEN_FULL/OVER_DOUBLE/TRIPLE）直接拒绝；非 PQ 地图用 PQ 技能、非 GM 用 GM 技能、技能不在本职业技能树（`GameConstants.isInJobTree`）→ `AutobanFactory.PACKET_EDIT.alert` + warn 日志 + `disconnect(true,false)`；合法返回 true |
| `static void SPAssignAction(Client c, int skillid)` | 技能 +1 级 | `c.lockClient()`；先 canSPAssign；取对应技能书（`getSkillBook(skillid/10000)`）的剩余 SP；新手技能（ jobId×10000000+1000~1002）剩余额度=`min(level-1, 6) - 三技能已投入总和`（升级不扣 SP）；四转技能上限用 masterLevel 否则 maxLevel；满足则 `gainSp(-1,...)`（新手技能只发包不扣点）；战神 FULL_SWING/OVER_SWING 升级时同步联动升级对应 HIDDEN_DOUBLE/TRIPLE 隐藏技能（equal 等级/masterLevel/过期时间） |

### 6.3 action/SpawnPetProcessor

**概述**：宠物召唤/收回处理器（SpawnPetHandler 的主体）：双击 CASH 背包宠物道具时召唤；龙蛋/机器人蛋孵化进阶；多宠物（技能 8）位次管理。源码路径：`client/processor/action/SpawnPetProcessor.java`。

**关键字段**：`dataRoot`（ITEM WZ，读进化目标 `info/evol1`）。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static void processSpawnPet(Client c, byte slot, boolean lead)` | 召唤/收回宠物 | `tryacquireClient` 全程互斥；取 CASH 背包 slot 的 Pet（空返回）；**龙蛋/Robo 蛋**：已有进化形态（petid+1）则提示不可重复孵化；否则读 `evol1` 生成新宠物（继承原过期时间），删蛋加新宠物后返回；**已召唤** → `unEquipPet(pet, true)` 收回；**召唤**：无多宠技能（技能 8 等级 0）且 0 号位有宠物先收回 0 号；lead 时 `shiftPetsRight` 腾出首位；初始化位置（角色坐标 y-12）、fh（findBelow）、stance=0、summoned=true、`saveToDb`；`addPet` 入角色宠物列表；`loadPetExcludedItems` 补载该宠物的拾取过滤配置；广播 showPet、发 petStatUpdate/enableActions；`commitExcludedItems` 同步过滤包；`registerPetHunger` 注册饥饿定时任务 |

### 6.4 action/PetAutopotProcessor

**概述**：宠物自动药水处理器：宠物饰品触发时按警戒线一口气喝药到目标血/蓝（支持一瓶不够跨格连喝），兼容三种模式：服务端个人警戒线（HpMpAlertService）、全服强制比例（原 HMS 方案）、单瓶模式。内部类 `AutopotAction` 封装动作。源码路径：`client/processor/action/PetAutopotProcessor.java`。

**内部类 AutopotAction 关键字段**：`c`/`slot`/`itemId`、`toUse`（当前瓶游标）、`toUseList`（同 ID 药品格队列）、`hasHpGain/hasMpGain`、`maxHp/maxMp/curHp/curMp`、`incHp/incMp`。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static void runAutopotAction(Client c, short slot, int itemid)` | 入口 | 构造 `AutopotAction(c, slot, itemid)` 并 `run()` |
| `boolean cursorOnNextAvailablePot(Character chr)`（内部类 private） | 游标移到下一可用药品格 | 懒加载 USE 背包 `linkedListById(itemId)` 队列，从头弹出数量>0 的格作为新游标；无药返回 false |
| `void run()`（内部类 public） | 执行自动喝药 | 角色死亡仅 enableActions；读当前 max/cur HPMP；锁 USE 背包；**满血蓝直接返回**（锁内判定，防排队包越限）；校验 slot 物品 ID；当前瓶空则游标后移；`ii.getItemEffect` 取药效，算 incHp/incMp（比例药按 maxHp×hpRate 向上取整）；算目标瓶数 qtyCount：`use_server_auto_pot`→按 `HpMpAlertService.getHpAlertPer/getMpAlertPer(chrId)` 个人警戒线；`use_compulsory_auto_pot`→按 `pet_auto_hp_ratio/pet_auto_mp_ratio`；否则 1 瓶；循环 `removeFromSlot` 喝药（数量=min(目标, 当前瓶数量)），本地滚动 curHp/curMp，瓶尽且未达标继续取下一瓶；解锁后按喝掉的瓶数逐次 `stat.applyTo(chr)`（真实结算），最后 enableActions |

### 6.5 action/MakerProcessor

**概述**：装备制造（Maker/炼金术）处理器：type 3=怪物结晶转换（强化余料→结晶）、type 4=装备分解、其他=常规制造（装备可带刺激剂与宝石，附魔产物属性随机）。全程 `tryacquireClient` 互斥，含多重防刷校验。源码路径：`client/processor/action/MakerProcessor.java`。

**关键字段**：`ii`（static ItemInformationProvider）。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static void makerAction(InPacket p, Client c)` | 制造总入口 | 读 type/toCreate；type 3：`getMakerCrystalFromLeftover` 换算目标结晶，无配方的道具提示不可转换，配方由 `generateLeftoverCrystalEntry` 生成；type 4：读背包位 pos，校验装备在格且 ID 匹配，`generateDisassemblyInfo` 取分解回收物，生成分解配方；其余（制造）：装备类读刺激剂字节与宝石列表（数量封顶 `getMakerReagentSlots`），背包没有的宝石剔除，`removeOddMakerReagents` 清洗非法/重复宝石；`getCreateStatus` 前置校验后按状态码提示（-1 非法配方/1 缺材料/2 缺金币/3 等级不足/4 技能不足/5 背包满）；状态 0 执行：分解删装备、制造扣材料/刺激剂/宝石/金币；带强化时 `addBoostedMakerItem` 生成成品（否则 setCS 上下文直接 gainItem）；按 type 发 makerResultCrystal/makerResultDesynth/makerResult 结果包 + showMakerEffect 本人与地图特效；任务 6033（结晶制造）进度联动 |
| `static boolean removeOddMakerReagents(int toCreate, Map<Integer,Short> reagentids)`（private） | 宝石合法性清洗 | 非武器（`use_maker_permissive_atk_up` 可放宽）用了攻击系宝石（id/100 < 42502）直接 false；同类宝石只留 ID 最大（效果最好）一枚；数量统一压为 1 |
| `static int getMakerReagentSlots(int itemId)`（private） | 装备宝石槽数 | 按需求等级：<78 → 1 槽、78~107 → 2 槽、≥108 → 3 槽（查 WZ 失败 0） |
| `static Pair<Integer, List<Pair<Integer,Integer>>> generateDisassemblyInfo(int itemId)`（private） | 分解信息 | `getMakerDisassembledFee`（手续费）+ `getMakerDisassembledItems`（回收物清单）；无数据 null |
| `static int getMakerSkillLevel(Character chr)` | 取制造技能等级 | 技能 ID = `(job.getId()/1000)*10000000 + 1007` 的当前等级 |
| `static short getCreateStatus(Client c, MakerItemCreateEntry recipe)`（private） | 制造前置校验 | 顺序返回：-1 配方非法 → 1 材料不足（`hasItems`）→ 2 金币不足 → 3 等级不足 → 4 制造技能不足 → 5 空间不足（`canHoldAllAfterRemoving` 模拟先扣后加）→ 0 通过 |
| `static boolean hasItems(Client c, MakerItemCreateEntry recipe)`（private） | 材料齐备 | 逐项 `countById ≥ 需求量` |
| `static boolean addBoostedMakerItem(Client c, int itemid, int stimulantid, Map<Integer,Short> reagentids)`（private） | 生成强化装备成品 | 刺激剂：90% 成功率（失败返回 false，装备消失）；建装备模板；饰品升级槽≤0 时补 3 槽；`use_enhanced_crafting`（精工）：+1 升级槽并附加一次混沌卷效果（GM 完美卷除外）；宝石加成：固定属性（MaxHP/MaxMP 映射为 MHP/MMP）累加后 `improveEquipStats`；randStat/randOption 走 `scrollOptionEquipWithChaos` 随机；带刺激剂再 `randomizeUpgradeStats` 重掷属性；`addFromDrop` 入包 |

### 6.6 npc/StorageProcessor

**概述**：仓库 NPC（银行）交互处理器：取出（mode 4）、存入（5）、整理（6）、金币存取（7）、关闭（8），含等级门槛、GM 限制、费用、防复制加锁与唯一物校验。源码路径：`client/processor/npc/StorageProcessor.java`。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static void storageAction(InPacket p, Client c)` | 仓库操作总入口 | 等级<15 弹窗拒绝（15 级解锁）；`tryacquireClient` 后按 mode 分发（见下）；各分支内嵌 GM 限制校验与费用扣除 |
| `static boolean hasGMRestrictions(Character character)`（private） | GM 是否被限制用仓 | `isGM() && gmLevel < minimum_gm_level_to_use_storage` |
| （mode 4 取出，storageAction 内分支） | 从仓库取物到背包 | slot 越界（<0 或 >仓库格数）→ PACKET_EDIT 告警+踢线；GM 限制弹窗拒绝；唯一物已持有 → 错误码 0x0C；取出手续费不足 → 0x0B；`checkSpace` 不过 → 0x0A；`storage.takeOut` 成功后：Karma 翻转为不可交易、`addFromDrop` 入包、扣手续费、`sendTakenOut` 刷新界面 |
| （mode 5 存入，storageAction 内分支） | 从背包存入仓库 | slot 越界（<1 或 >背包格数，玩家背包从 1 起）→ PACKET_EDIT 踢线；GM 限制拒绝；数量<1 无视；仓库满 → 0x11；存入费不足 → 0x0B；锁背包校验格内物品（ID 匹配、数量足够、rechargeable 存整格）、结婚戒指/信物拒绝；`removeFromSlot` 后 `item.copy()`（防引用错位）；扣费、Karma 翻转、`storage.store` 入仓、`sendStored` 刷新 |
| （mode 6 整理，storageAction 内分支） | 仓库排序 | `use_storage_item_sort` 开启才执行 `storage.arrangeItems(c)`；发 enableActions |
| （mode 7 金币，storageAction 内分支） | 仓库存取金币 | meso>0 取出/meso<0 存入（溢出保护：取出使玩家金币溢出时钳到 `MAX_VALUE - playerMesos`，存入使仓库为负时钳到 `MIN_VALUE + storageMesos`，钳后仍不满足则拒绝）；`storage.setMeso` + `chr.gainMeso` 双向划转、`sendMeso` 刷新 |
| （mode 8 关闭，storageAction 内分支） | 关闭仓库 | `storage.close()` |

### 6.7 npc/DueyProcessor

**概述**：快递员 Duey（包裹邮寄）处理器：寄送（dueySendItem，含手续费/快速票/同账号限制/在线通知）、领取（dueyClaimPackage，synchronized 防多人抢同一包裹）、删除（dueyRemovePackage）、对话打开（dueySendTalk）、系统侧发放（dueyCreatePackage）与过期清理（runDueyExpireSchedule，30 天）。内部枚举 `Actions` 定义 24 个 C2S/S2C 操作码。源码路径：`client/processor/npc/DueyProcessor.java`。

**内部枚举 Actions**：TOSERVER_RECV_ITEM(0x00)/SEND_ITEM(0x02)/CLAIM_PACKAGE(0x04)/REMOVE_PACKAGE(0x05)/CLOSE_DUEY(0x07)、TOCLIENT_OPEN_DUEY(0x08)/SEND_ENABLE_ACTIONS(0x09)/NOT_ENOUGH_MESOS(0x0A)/INCORRECT_REQUEST(0x0B)/NAME_DOES_NOT_EXIST(0x0C)/SAMEACC_ERROR(0x0D)/RECEIVER_STORAGE_FULL(0x0E)/RECEIVER_UNABLE_TO_RECV(0x0F)/RECEIVER_STORAGE_WITH_UNIQUE(0x10)/MESO_LIMIT(0x11)/SUCCESSFULLY_SENT(0x12)、TOCLIENT_RECV_UNKNOWN_ERROR(0x13)/ENABLE_ACTIONS(0x14)/NO_FREE_SLOTS(0x15)/RECEIVER_WITH_UNIQUE(0x16)/SUCCESSFUL_MSG(0x17)/PACKAGE_MSG(0x1B)，含 `getCode()`。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static Pair<Integer,Integer> getAccountCharacterIdFromCNAME(String name)`（private） | 收件人名→（accountId, characterId） | 查 characters 表；查无（-1,-1） |
| `static void showDueyNotification(Client c, Character player)`（private） | 给在线收件人弹到货通知 | 查 dueypackages 中该收件人 Checked=1 的包裹，有则置 Checked=0 并发 `sendDueyParcelReceived`（区分物品/金币包裹） |
| `static void deletePackageFromInventoryDB(Connection con, int packageId)`（private） | 清空包裹物品记录 | `ItemFactory.DUEY.saveItems(空列表, packageId, con)`（整存空表即删除） |
| `static void removePackageFromDB(int packageId)`（private） | 删除包裹 | DELETE dueypackages + 联动清物品表 |
| `static DueyPackage getPackageFromDB(ResultSet rs)`（private） | 行→包裹对象 | `ItemFactory.DUEY.loadItems` 取物品（每包裹仅一件）；组装 sender/mesos/sentTime(quick 标志)/message/receiverId |
| `static List<DueyPackage> loadPackages(Character chr)`（private） | 装载收件人全部包裹 | 查 dueypackages WHERE ReceiverId=? 逐行组装 |
| `static int createPackage(int mesos, String message, String sender, int toCid, boolean quick)`（private） | 建包裹记录 | INSERT dueypackages（Checked=1 待通知）；返回生成的 PackageId，失败 -1 |
| `static boolean insertPackageItem(int packageId, Item item)`（private） | 包裹附物 | `ItemFactory.DUEY.saveItems(单件, packageId)` |
| `static int addPackageItemFromInventory(int packageId, Client c, byte invTypeId, short itemPos, short amount)`（private） | 从背包取物入包裹 | 锁背包校验数量；不可交易/不可寄售 → -1；数量不足 → -2；rechargeable 整格取；取出后 Karma 翻转、`insertPackageItem`；返回 0 成功 / 1 入库失败 |
| `static void dueySendItem(Client c, byte invTypeId, short itemPos, short amount, int sendMesos, String sendMessage, String recipient, boolean quick)` | 寄包裹 | `tryacquireClient`；GM 低于 `minimum_gm_level_to_use_duey` 拒绝；**sendMesos<0 → PACKET_EDIT 踢线**（修复负数金币刷钱）；留言>100 字符 → 踢线；手续费=Trade.getFee(sendMesos)，普通件+5000，快速件须持 QUICK_DELIVERY_TICKET（无票踢线）；总费用溢出/空包裹 → 踢线；金币不足 → 0x0A；收件人不存在 → 0x0C；同账号互寄 → 0x0D；快速件扣票；createPackage → 扣总费用 → 附物（0 成功 0x12 / 正数 0x09 / 负数 0x0B）；收件人在线且未离开世界则即时到货通知 |
| `static void dueyRemovePackage(Client c, int packageid, boolean playerRemove)` | 删包裹 | 互斥锁内 `removePackageFromDB` + 发 `removeItemFromDuey`（区分玩家手动删/领取后系统删） |
| `static synchronized void dueyClaimPackage(Client c, int packageId)` | 领取包裹 | **方法级 synchronized**：防复制外挂多人并发领同一包裹；查包裹（无 → 0x13）；**收件人非本人 → PACKET_EDIT 告警**（改包）；仍在派送中（isDeliveringTime）→ 0x13；有物品时：金币持有上限校验、`checkSpace`（唯一物冲突 → 0x16，空间不足 → 0x15）、`addFromDrop` 入包；`gainMeso` 入账；`dueyRemovePackage` 收尾 |
| `static void dueySendTalk(Client c, boolean quickDelivery)` | 打开快递对话 | 互斥；NPC 冷却（`block_npc_race_condition`）内拒绝并刷新冷却时间；quick 分支发 0x1A 空面板，否则发 0x8 + 全部包裹列表 |
| `static void dueyCreatePackage(Item item, int mesos, String sender, int recipientCid)` | 系统侧直接发包裹（无手续费/无物品校验） | createPackage + insertPackageItem（任务奖励/管理员发放用） |
| `static void runDueyExpireSchedule()` | 过期清理定时任务 | 删除 30 天前的包裹（先逐个 removePackageFromDB 清物品，再批量 DELETE 主表） |

### 6.8 npc/FredrickProcessor

**概述**：雇佣商人管理员 Fredrick 处理器：取回关店物品与净收入（fredrickRetrieveItems，含开店中禁止取回的防复制保护）、过期清理与提醒调度（runFredrickSchedule：关店超 100 天销毁物品，第 2/5/10/15/30/60/90 天发提醒便笺）。**本包唯一非静态处理器**，构造注入 Spring `NoteService` 发便笺。源码路径：`client/processor/npc/FredrickProcessor.java`。

**关键字段**：`dailyReminders = {2,5,10,15,30,60,90,MAX_VALUE}`（提醒阶梯）；`noteService`（NoteService）。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `FredrickProcessor(NoteService noteService)` | 构造 | 保存便笺服务（Spring 侧以 bean 方式创建） |
| `static byte canRetrieveFromFredrick(Character chr, List<Pair<Item,InventoryType>> items)`（private） | 取回前置校验 | `Inventory.checkSpotsAndOwnership` 放不下：唯一物冲突 → 0x22，普通放不下 → 0x20；净收入>0 且金币将溢出 → 0x1F；净支出（负数）且现金不足 → 0x21；可取回 → 0x0 |
| `static int timestampElapsedDays(Timestamp then, long timeNow)` | 计算已过天数 | 毫秒差 ÷ 86400000 |
| `static String fredrickReminderMessage(int daynotes)`（private） | 提醒文案 | daynotes<4 用温和版（"店铺已关闭 N 天，请取回"），否则最后通牒版（"在我们撤走之前…"）——中文硬编码（遗留） |
| `static void removeFredrickLog(int cid)` / `static void removeFredrickLog(Connection con, int cid)`（private 重载） | 删 fredstorage 记录 | DELETE WHERE cid=?（无参版自开连接） |
| `static void insertFredrickLog(int cid)` | 记录开始计时 | 先清旧记录再 INSERT（cid, daynotes=0, 当前时间）——店铺关闭/掉线时调用 |
| `static void removeFredrickReminders(List<Pair<Integer,Integer>> expiredCids)`（private） | 清过期者的历史便笺 | 按 cid → 角色名，DELETE notes WHERE from='FREDRICK' AND to=角色名 |
| `void runFredrickSchedule()` | 每日调度 | 扫 fredstorage JOIN characters：超 100 天 → 过期处理（批量删 type=MERCHANT 物品、MerchantMesos 清零（在线角色同步 setMerchantMeso(0)）、删便笺、删记录）；未过期且超过当前提醒档位天数 → daynotes 前进到对应档，若玩家 7 天内活跃（或已是最后一档）则 `noteService.sendNormal(提醒文案, "FREDRICK", 角色名)` 发便笺并更新 daynotes |
| `static boolean deleteFredrickItems(int cid)`（private） | 删雇佣商人库存 | DELETE inventoryitems WHERE type=MERCHANT(6) AND characterid=? |
| `void fredrickRetrieveItems(Client c)` | 取回物品与金币 | `tryacquireClient`；**防复制**：角色仍标记开店或世界上存在其营业中的 HiredMerchant（hasMerchant / isOwner）→ 弹窗拒绝（开店时物品同时在内存与 DB，先取回即双份）；`ItemFactory.MERCHANT.loadItems` 载物品；`canRetrieveFromFredrick` 非 0 → 发对应错误码返回；`withdrawMerchantMesos` 结算净收入；`deleteFredrickItems` 清 DB、`merchant.clearItems()` 清内存；逐件 `addFromDrop` 入包并打 debug 日志；成功发 0x1E、`removeFredrickLog` 结束计时 |

---

## 7. status 子包（怪物状态）

### 7.1 MonsterStatus（枚举）

**概述**：怪物异常/增益状态位枚举（30 项）：属性削弱（WATK/WDEF/MATK/MDEF/ACC/AVOID/SPEED）、控制（STUN/FREEZE/POISON/SEAL/DOOM/INERTMOB/BLIND/SEAL_SKILL）、特殊标记（NEUTRALISE、PHANTOM_IMPRINT、SHOWDOWN、HARD_SKIN、NINJA_AMBUSH、ELEMENTAL_ATTRIBUTE、VENOMOUS_WEAPON、SHADOW_WEB）、增益（WEAPON/MAGIC_ATTACK_UP、WEAPON/MAGIC_DEFENSE_UP）与免疫/反弹（WEAPON_IMMUNITY、MAGIC_IMMUNITY、WEAPON_REFLECT、MAGIC_REFLECT）。源码路径：`client/status/MonsterStatus.java`。

**关键字段**：`i`（int 位码）、`first`（boolean，true 表示该状态应排在状态包首位——NEUTRALISE、PHANTOM_IMPRINT、WEAPON_REFLECT、MAGIC_REFLECT 标记为 first；注意 NEUTRALISE 与 WDEF、PHANTOM_IMPRINT 与 MATK 共用位码但互斥使用）。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `MonsterStatus(int i)` / `MonsterStatus(int i, boolean first)` | 枚举构造 | 存位码与首位标记 |
| `boolean isFirst()` | 是否需排首位 | 返回 first（发包顺序用） |
| `int getValue()` | 取状态位码 | 返回 i（供按位 OR 聚合状态掩码） |

### 7.2 MonsterStatusEffect

**概述**：怪物身上的一个状态效果实例：`Map<MonsterStatus, Integer>` 状态值表（ConcurrentHashMap 线程安全）+ 来源技能（玩家 Skill 或怪物 MobSkill）。由怪物技能（如毒雾、冰冻）与玩家技能（如诅咒、压制）共同写入，供伤害计算与状态到期回收。源码路径：`client/status/MonsterStatusEffect.java`。

**关键字段**：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `stati` | `Map<MonsterStatus, Integer>` | 状态 → 效果值（如 WATK → -30） |
| `skill` | `Skill` | 来源玩家技能（可空） |
| `mobskill` | `MobSkill` | 来源怪物技能（可空） |
| `monsterSkill` | `boolean` | true=怪物施放（影响解除逻辑：怪物技能可被 Holy Shield 类效果清除） |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `MonsterStatusEffect(Map<MonsterStatus,Integer> stati, Skill skillId, MobSkill mobskill, boolean monsterSkill)` | 构造 | 入参 Map 拷入 ConcurrentHashMap（与外部解耦） |
| `Map<MonsterStatus, Integer> getStati()` | 取状态表 | 返回内部并发 Map 引用 |
| `Integer setValue(MonsterStatus status, Integer newVal)` | 设置/覆盖单个状态值 | `stati.put`，返回旧值 |
| `Skill getSkill()` / `MobSkill getMobSkill()` | 取来源技能 | 直接返回 |
| `boolean isMonsterSkill()` | 是否怪物技能来源 | 直接返回 monsterSkill |
| `void removeActiveStatus(MonsterStatus stat)` | 移除单个激活状态 | `stati.remove(stat)`（状态到期/被净化时调用） |
