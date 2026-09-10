# 13 聊天命令（client/command/commands）详细设计

## 0. 模块概览

| 项目 | 内容 |
| --- | --- |
| 模块路径 | `gms-server/src/main/java/org/gms/client/command/`（命令实现在子包 `commands/gm0` ~ `commands/gm6`） |
| 类数量 | 命令类 175 个 + 框架类 2 个（`Command`、`CommandsExecutor`）= 177 个 Java 文件 |
| 注册指令数 | 179 条（含别名；与 `command_info` 表初始化数据一致） |
| 入口调用方 | `GeneralChatHandler`（聊天包）→ `CommandsExecutor.handle`；`Server.init` → `loadCommandsExecutor()` 注册 |
| 依赖模块 | 见下 |

依赖模块：

- `org.gms.client.*`：`Character`、`Client`、`Skill/SkillFactory`、`BuffStat`、`Job`、`Stat`、`Disease`、`inventory.*`（Item/Equip/Pet/InventoryType/InventoryManipulator）、`autoban.AutobanFactory`
- `org.gms.net.server.*`：`Server`、`World`、`Channel`、`PlayerStorage`、`coordinator.login.LoginBypassCoordinator`、`coordinator.session.SessionCoordinator`、`net.packet.logging.MonitoredChrLogger`
- `org.gms.server.*`：`life.*`（Monster/MobSkill/MobSkillFactory/NPC/PlayerNPC/LifeFactory/MonsterInformationProvider）、`maps.*`（MapleMap/Portal/MapFactory/SavedLocationType/FieldLimit/MiniDungeonInfo/MapItem）、`quest.Quest`、`expeditions.Expedition`、`events.gm.Event`、`gachapon.Gachapon`、`ShopFactory`、`ItemInformationProvider`、`TimerManager`、`ThreadManager`
- `org.gms.scripting.*`：`NPCScriptManager`、`QuestScriptManager`、`portal.PortalScriptManager`、`AbstractScriptManager`
- `org.gms.provider.*`：`DataProviderFactory`、`WZFiles`、`Data/DataTool`（WZ String 数据检索）
- `org.gms.net.*`：`PacketProcessor`、`PacketHandler`、`packet.ByteBufInPacket`（`!pe` 模拟客户端发包）
- `org.gms.config.GameConfig`（`item_limit_on_map`、`max_ap`、`block_generate_cash_item` 等配置及热更新）、`org.gms.constants.*`（GameConstants/ItemConstants/MapId/MobId/NpcId/ItemId/ServerConstants/NpcChat）
- `org.gms.util.*`：`PacketCreator`（出包）、`DatabaseConnection`（部分命令直连 JDBC）、`I18nUtil`、`StringUtil`、`Pair`、`Randomizer`、`HexTool`
- Spring/MyBatis 层：`ServerManager.getApplicationContext()` 反查 `GachaponService`、`HpMpAlertService`；注册链路为 `CommandService` + `CommandInfoMapper`（`command_info` 表）；REST 管理入口 `CommandController`（`/command/v1/*`）

---

## 1. 命令体系说明

### 1.1 权限等级模型

命令按 GM 权限分为 7 级，与子包目录一一对应：

| 等级 | 目录 | 定位（按所含命令归纳） |
| --- | --- | --- |
| gm0 | `commands/gm0` | 普通玩家自助指令（`@` 前缀）：查倍率/排行/在线、加活动、分配 AP、认领地图等 |
| gm1 | `commands/gm1` | 信息查询增强：Boss/怪血量、掉落查询、GM 自助 buff、预设地图传送 |
| gm2 | `commands/gm2` | 标准 GM：角色养成（等级/职业/技能/属性）、物品生成、单玩家传送/踢线/监禁 |
| gm3 | `commands/gm3` | 高级 GM：地图与玩家管控（禁言/封禁/击杀/治疗）、任务/事件/计时器、重载脚本 |
| gm4 | `commands/gm4` | 运营管理：全服倍率与公告、Boss 召唤、永久 NPC/怪物（`plife` 表）、强制捡取 |
| gm5 | `commands/gm5` | 调试诊断：debug 信息、收包/移动日志开关、会话/IP 追踪 |
| gm6 | `commands/gm6` | 服主级：GM 等级授予、转区、全服存档/踢线/关服、动态增删大区与频道 |

执行时校验 `client.getPlayer().gmLevel() < command.getRank()` 则拒绝，即 gm6 最高、玩家 gm0 可执行全部 rank ≤ 0 的指令。

### 1.2 指令前缀与触发

- 玩家指令前缀 `@`；GM 额外可用 `!`（`CommandsExecutor.isCommand`：GM 两者皆可，非 GM 仅 `@`）。
- 聊天包进入 `GeneralChatHandler` 后先做频率与长度防刷检查，命中指令前缀即转 `CommandsExecutor.getInstance().handle(c, s)`。

### 1.3 注册机制（数据库驱动）

`CommandsExecutor` 内置的硬编码注册方法（`registerLv0Commands` ~ `registerLv6Commands`）**已被注释弃用**，实际注册链路：

1. `Server.init()` 末尾调用 `CommandsExecutor.getInstance().loadCommandsExecutor()`；
2. 委托 `CommandService.loadCommands(...)` 读取 `command_info` 表全量记录（建表与 179 条初始数据见 Flyway 迁移 `V1.5.1__create_command_info.sql`）；
3. 按 `level` 分组、跳过 `enabled = 0` 的行，反射实例化 `org.gms.client.command.commands.gm{default_level}.{clazz}`，并把实例 `rank` 设为该行的 `level`（当前生效等级，允许与 `default_level` 不同）；命令实例为**注册期单例**（每条 syntax 反射 new 一次后复用）；
4. 同名 syntax 重复注册记录 warn 并跳过；同时按等级维护 `commandsNameDesc`（名字/描述二元列表），供帮助系统展示。

字段约束：`syntax` 指令名、`level` 当前等级（0–6，可改）、`enabled` 启用开关、`clazz` 类名、`default_level` 默认等级（即类所在包，不可改）。

热更新：REST `CommandController`（`/command/v1/getCommandListFromDB` 分页查询、`/command/v1/updateCommand` 仅允许改 `level`/`enabled` 并同步内存注册表 `updateRegisteredCommands`、`/command/v1/reloadEventsByGMCommand|reloadPortalsByGMCommand|reloadMapsByGMCommand` 复用对应重载命令逻辑），供 gms-ui 后台在线调整指令开关与等级。

### 1.4 分发流程（`CommandsExecutor.handle` → `handleInternal`）

1. `client.tryacquireClient()` 客户端锁防并发，未抢到提示"上一条指令未执行完成"；
2. 监狱地图（`MapId.JAIL`）内非 GM 禁用一切指令；
3. 配置 `deterred_player_command` 为真时拒绝非 GM 的 `@` 指令；
4. 消息去掉前缀后按空格切两段：首段转小写为命令名，其余整段先存入 `player.setLastCommandMessage()`（**保留原文大小写**，供公告、歌名、搜索词等取含空格原文），再按空格小写化为参数数组 `params`（**参数整体小写**，需要原文的一律改用 `getLastCommandMessage()`）；
5. 查 `registeredCommands`，未注册提示不存在；`gmLevel() < rank` 提示权限不足；
6. `command.execute(client, params)`，随后 log.info 记录执行者与命令类名。

框架类 `Command`（抽象基类：`rank`/`description`/`execute`/`joinStringFrom`）与 `CommandsExecutor`（单例、注册表、分发器）的完整类设计见本系列**第 12 篇（框架类）**文档。

### 1.5 通用编码约定

- 所有面向玩家的提示文案与日志走 `I18nUtil.getMessage/getLogMessage`（`i18n/message_{zh_CN,en_US}.properties`），命令描述（`setDescription`）同样取自 i18n 的 `<类名>.message1`；部分命令参数支持中英文关键字（如 `clearslot 装备`、`search 物品`、`timer remove/移除`、`fly on/开启`、`givenx 点券/抵用券/信用点`）。
- 多数"指定玩家"参数支持**角色名或数字角色 ID** 二选一（先按名查 `PlayerStorage`，失败且为数字再按 ID 查）。
- 涉及账号/会话状态的命令（`enableauth`、`toggleexp`、`mylawn`、`whodrops` 等）自带 `tryacquireClient` 客户端锁。
- 生成类命令对 `gmLevel() < 3` 的使用者自动给产物打 `UNTRADEABLE`/`ACCOUNT_SHARING`（`item`/`drop` 再加 `SANDBOX` 并置 owner 为 `TRIAL-MODE`），即"试用模式"标记。

---

## 2. gm0 —— 玩家指令（25 类 / 26 条指令）

| 命令（别名） | 类 | 作用 | 参数 | 关键逻辑要点 |
| --- | --- | --- | --- | --- |
| `help`（`commands`） | HelpCommand | 展示可用的 gm 指令 | 无 | 打开 NPC `STEWARD` 的 `commands` 脚本对话，按等级列出指令与描述 |
| `droplimit` | DropLimitCommand | 查看当前地图掉落数量/上限 | 无 | 掉落数低于上限 75% 蓝色、否则红色提示；上限取配置 `item_limit_on_map` |
| `time` | TimeCommand | 展示服务器当前时间 | 无 | `HH:mm:ss`，服务器默认时区 |
| `credits` | StaffCommand | 展示服务端贡献人员 | 无 | 打开 NPC `HERACLE` 的 `credits` 脚本 |
| `uptime` | UptimeCommand | 展示服务器运行时长 | 无 | `System.currentTimeMillis() - Server.uptime` 折算天/时/分/秒 |
| `gacha <城镇名>` | GachaCommand | 查询扭蛋机奖励 | 城镇名（原文） | 与 `Gachapon.GachaponType` 名单匹配；未命中列出全部可用名；命中后经 `GachaponService.getRewardsByNpcId` 查库，NPC 对话展示物品清单 |
| `dispose` | DisposeCommand | 解卡 NPC/任务对话 | 无 | dispose NPC 与 Quest 脚本管理器 + `enableActions` + `removeClickedNPC` |
| `changel <语言>` | ChangeLanguageCommand | 切换客户端语言 | 1 个 int | `client.setLanguage(...)`；缺参提示用法 |
| `equiplv` | EquipLvCommand | 展示装备等级 | 无 | `showAllEquipFeatures()` |
| `showrates` | ShowRatesCommand | 展示大区+玩家倍率明细 | 无 | 按经验/金币/掉落/Boss 掉落分组，逐行显示世界倍率、玩家基础倍率、倍率卡与最终倍率；`use_quest_rate` 开启时附任务倍率，末尾附传送倍率 |
| `rates` | RatesCommand | 展示玩家实际倍率 | 无 | 含 `EXP_BUFF` 翻倍、家族经验/掉落加成、怪物经验率（>1 时）、任务倍率（按配置）；不展示全局传送倍率 |
| `online` | OnlineCommand | 列本世界各频道在线玩家 | 无 | 逐频道列出非 GM 玩家 ID/名字/所在地图名 |
| `gm <消息>` | GmCommand | 呼叫 GM | 消息 ≥3 字符 | 向全 GM 广播黄条+蓝底公告并记日志，回复随机小贴士；过短直接拒绝 |
| `reportbug <内容>` | ReportBugCommand | 上报漏洞 | 任意文本 | 与 `gm` 同构：广播 GM+日志+回执 |
| `points [rp\|vp]` | ReadPointsCommand | 查询奖励点/投票点 | 0~1 个 | `rp`=角色 rewardPoints、`vp`=客户端 votePoints；其他值回退两者都显示 |
| `joinevent` | JoinEventCommand | 加入当前频道 GM 活动 | 无 | 校验非 `CANNOTMIGRATE` 图、活动存在、limit>0；椰子/雪球图按 limit 奇偶分队；保存 `EVENT` 回程点后传入活动图并 limit-1 |
| `leaveevent` | LeaveEventCommand | 离开活动返回原地 | 无 | 读 `EVENT` 存档点；重置 OLA/健身状态；活动 limit+1；无存档点提示 |
| `ranks` | RanksCommand | 展示世界玩家排行 | 无 | `Server.getWorldPlayerRanking` → `GuildPackets.showPlayerRanks` |
| `str [数量]` | StatStrCommand | AP 分配到力量 | 0~1 个 int | 缺省取 min(剩余AP, `max_ap`-当前值)；`assignStr` 失败提示上限；下述 dex/int/luk 同构 |
| `dex [数量]` | StatDexCommand | AP 分配到敏捷 | 0~1 个 int | 同 `str`，`assignDex` |
| `int [数量]` | StatIntCommand | AP 分配到智力 | 0~1 个 int | 同 `str`，`assignInt` |
| `luk [数量]` | StatLukCommand | AP 分配到运气 | 0~1 个 int | 同 `str`，`assignLuk` |
| `enableauth` | EnableAuthCommand | 重置 PIC/PIN 验证冷却 | 无 | `LoginBypassCoordinator.unregisterLoginBypassEntry(hwdid, accId)`；客户端锁保护 |
| `toggleexp` | ToggleExpCommand | 开关经验获取 | 无 | `toggleExpGain()`；客户端锁保护 |
| `mylawn` | MapOwnerClaimCommand | 认领/归还地图归属 | 无 | 需 `use_map_ownership_system`；须不在活动副本且图内无 Boss；已拥有时执行为解除（归还），否则 `claimOwnership` |

## 3. gm1 —— 信息查询与自助传送（6 类 / 6 条指令）

| 命令 | 类 | 作用 | 参数 | 关键逻辑要点 |
| --- | --- | --- | --- | --- |
| `bosshp` | BossHpCommand | 展示本图 Boss 血量 | 无 | 逐 Boss 输出名称/ID/百分比与 100 格文本血条 `[...\|\|..]` |
| `mobhp` | MobHpCommand | 展示本图怪物血量 | 无 | 输出全部存活怪名称/ID/当前 HP/最大 HP |
| `whatdropsfrom <怪名>` | WhatDropsFromCommand | 查怪物掉落 | 怪物名（原文） | 名字匹配取前 3 个怪，遍历 `retrieveDrop` 按 1000000/chance ÷（普通用 dropRate、Boss 用 bossDropRate）计算 1/x 概率，NPC 对话展示 |
| `whodrops <物名>` | WhoDropsCommand | 查物品来源 | 物品名（原文） | 物品名匹配前 3 个，JDBC 直查 `drop_data WHERE itemid=? LIMIT 50` 反查 dropper 名；客户端锁保护 |
| `buffme` | BuffMeCommand | 给自己上 GM 常用 buff | 无 | 满级施放 4101004（haste）/2311003/1301007/2301004/1005 并回满血蓝 |
| `goto [地名]` | GotoCommand | 传送到预设地图 | 0~1 个地名 | 无参时 NPC 对话列出清单（玩家仅 `GOTO_TOWNS`，GM 追加 `GOTO_AREAS`）；有参校验存活、非活动/迷你副本/受限图（GM 豁免）后随机出生点传送 |

## 4. gm2 —— 标准 GM 指令（39 类 / 42 条指令）

### 4.1 角色养成与属性

| 命令 | 类 | 作用 | 参数 | 关键逻辑要点 |
| --- | --- | --- | --- | --- |
| `sp <SP> [玩家]` | SpCommand | 设置剩余 SP | 1~2 个 int | 夹取 [0, `max_ap`]；两参时对世界内指定玩家生效 |
| `ap <AP> [玩家]` | ApCommand | 设置剩余 AP | 1~2 个 int | 同 `sp`，`changeRemainingAp` |
| `level <等级>` | LevelCommand | 直接改等级 | 1 个 int | 清空当前经验→`setLevel(min(目标, 职业上限)-1)`→重算玩家/世界倍率→执行一次 `levelUp` |
| `levelpro <等级>` | LevelProCommand | 逐级升到目标 | 1 个 int | 循环 `levelUp(false)` 直到 min(目标, 职业上限)，走真实升级流程 |
| `setstat <值>` | SetStatCommand | 四维统一设值 | 1 个 int | 夹取 [4, 32767]，`updateStrDexIntLuk` |
| `maxstat` | MaxStatCommand | 一键满状态 | 无 | 255 级+四维 32767+人气 13337+HP/MP 30000 |
| `maxskill` | MaxSkillCommand | 技能全满 | 无 | 遍历 String/Skill.img 全技能设满级；Aran/Legend 与其他职业的互斥新手技（5001005 / 21001001）设 -1 隐藏 |
| `resetskill` | ResetSkillCommand | 重置全部技能 | 无 | 同 `maxskill` 遍历但全部清 0，互斥技能同样设 -1 |
| `job <职业ID> [玩家]` | JobCommand | 转职 | 1~2 个 | 合法区间 [0, 2200)；`changeJob`+`equipChanged` |
| `setslot <格数>` | SetSlotCommand | 设置背包格数 | 1 个 int | 向下取整到 4 的倍数，四类背包按差值 `gainSlots` |

### 4.2 物品

| 命令 | 类 | 作用 | 参数 | 关键逻辑要点 |
| --- | --- | --- | --- | --- |
| `item <物品ID> [数量\|天数]` | ItemCommand | 生成物品进背包 | 1~2 个 | 宠物第二参为有效天数（创建 petid+过期时间，数量固定 1）；`block_generate_cash_item` 开启时拒绝点券物；gm<3 产物打 `UNTRADEABLE`+`ACCOUNT_SHARING` |
| `drop <物品ID> [数量\|天数]` | ItemDropCommand | 生成物品掉在地上 | 1~2 个 | 与 `item` 同构（宠物同样按天数）；装备走 `getEquipById`；gm<3 再加 `SANDBOX` 且 owner="TRIAL-MODE"，正常时 owner=GM 名 |
| `recharge` | RechargeCommand | 补满可充值消耗品 | 无 | USE 栏飞镖/箭矢/子弹/堆叠消耗品全部补到 `slotMax` |
| `gmshop` | GmShopCommand | 打开 GM 商店 | 无 | `ShopFactory.getShop(1337)` |
| `loot` | LootCommand | 拾取属于自己的掉落 | 无 | 全图筛选 owner 为自己或自己队伍的 MapItem 逐个 `pickupItem` |
| `clearslot <栏名>` | ClearSlotCommand | 清空指定背包栏 | 1 个 | 栏名支持英文/中文：all/全部、equip/装备、use/消耗、setup/设置、etc/其他、cash/现金 |
| `gachalist` | GachaListCommand | 展示扭蛋机奖励 | 无 | 打开 NPC 9900001 的 `gachaponInfo` 脚本 |

### 4.3 传送族

| 命令 | 类 | 作用 | 参数 | 关键逻辑要点 |
| --- | --- | --- | --- | --- |
| `warp <地图ID> [传送点]` | WarpCommand | 传送自己 | 1~2 个 | 非 GM 需存活且不在活动/迷你副本/`CANNOTMIGRATE` 图；传送点参数先按名、再按数字索引，缺省随机出生点 |
| `warphere`（`summon`）`<玩家>` | SummonCommand | 把玩家召到自己身边 | 1 个 | 跨频道先对目标 `changeChannel`，轮询最多 7×1777ms 等其重新上线，再 `forceChangeMap` 到离自己最近的传送点 |
| `warpto`（`reach`、`follow`）`<玩家>` | ReachCommand | 传送到玩家身边 | 1 个 | 目标在其他频道仅提示；同频道 `forceChangeMap` 到离目标最近传送点 |
| `warpmap <地图ID>` | WarpMapCommand | 全图玩家传送 | 1 个 int | 本图所有玩家逐个 `saveLocationOnWarp`+`changeMap` |
| `warparea <地图ID>` | WarpAreaCommand | 附近玩家传送 | 1 个 int | 仅传送与自己距离平方 ≤50000 的玩家，其余同 `warpmap` |
| `jail <玩家> [分钟=5]` | JailCommand | 关进监狱 | 1~2 个 | 累加监禁时长后送 `MapId.JAIL`（保存 `JAIL` 回程点）；GM 不可被关 |
| `unjail <玩家>` | UnJailCommand | 从监狱释放 | 1 个 | 仅当剩余监禁时间 >0 时清除计时 |
| `clearsavelocs [玩家]` | ClearSavedLocationsCommand | 清空全部存档点 | 0~1 个 | 遍历所有 `SavedLocationType` 逐个 clear |

### 4.4 Buff / 战斗 / 玩家管理

| 命令 | 类 | 作用 | 参数 | 关键逻辑要点 |
| --- | --- | --- | --- | --- |
| `hide` | HideCommand | 隐身 | 无 | 施放 GM 技能 9101004 满级效果 |
| `unhide` | UnHideCommand | 取消隐身 | 无 | 同样施放 9101004（该 buff 为开关型，重复施放即解除） |
| `empowerme` | EmpowerMeCommand | 一次性上全套增益 | 无 | 17 个技能满级连放（`buffme` 组合+魔击无效、暗影、挑衅等） |
| `buffmap` | BuffMapCommand | 全图 GM buff | 无 | 9101001/9101002/9101003/9101008/1005 满级 `applyTo(player, true)` 全图生效 |
| `buff <技能ID>` | BuffCommand | 施放指定 buff | 1 个 int | 该技能满级效果 `applyTo` 自己 |
| `heal` | HealCommand | 自己血蓝回满 | 无 | `player.healHpMp()`（全图/指定玩家版本 `healmap`/`healperson` 在 gm3） |
| `bomb [玩家]` | BombCommand | 召唤自爆炸弹怪 | 0~1 个 | 无参炸自己脚下，有参（名/ID）炸目标脚下（`MobId.ARPQ_BOMB`），并广播 GM 提示 |
| `dc <玩家>` | DcCommand | 强制玩家下线 | 1 个 | 世界→频道→当前图三级查找；对 gmLevel 高于自己的目标改为断开自己（防越权）；地图兜底路径 `disconnect(true)+removePlayer` |
| `cleardrops` | ClearDropsCommand | 清空本图掉落 | 无 | `map.clearDrops(player)` |
| `unbug` | UnBugCommand | 解卡动作 | 无 | 全图广播 `enableActions` |
| `whereami` | WhereaMiCommand | 展示当前地图信息 | 无 | 列地图 ID 及图内玩家/玩家 NPC/NPC/存活怪（名称-ID-ObjectId） |
| `mobskill <技能ID> <等级>` | MobSkillCommand | 给全图怪上技能 | 2 个 int | `MobSkillFactory` 构造 MobSkill 后对本图所有怪 `applyEffect` |
| `id <类型> <关键词>` | IdCommand | 手册查 ID | ≥2 个 | 类型 map/etc/npc/use/weapon 对应 `handbook/*.txt`，`ThreadManager` 异步加载并按类型缓存，命中最多 100 条 NPC 对话展示 |
| `search <类型> <关键词>` | SearchCommand | 全局搜索 | ≥2 个 | 类型 item/npc/mob(monster)/skill/map/quest，支持中文"物品/怪物/技能/地图/任务"；item 走 `getAllItems`（结果截断 32654 字符），map 按 streetName/mapName 双匹配，quest 走 `Quest.getMatchedQuests`；末尾附检索耗时 |

## 5. gm3 —— 高级 GM 指令（57 类 / 57 条指令）

### 5.1 地图与怪物

| 命令 | 类 | 作用 | 参数 | 关键逻辑要点 |
| --- | --- | --- | --- | --- |
| `spawn <怪ID> [数量]` | SpawnCommand | 召唤怪物 | 1~2 个 int | 脚下生成；多只时逐个以 `new Monster(id, stats)` 克隆生成 |
| `npc <NPCID>` | NpcCommand | 临时生成 NPC | 1 个 int | 脚下生成（设置 rx0/rx1/fh），广播 `spawnNPC`；不落库，重载后消失 |
| `killall` | KillAllCommand | 击杀全图怪物 | 无 | 跳过友好怪与暗黑龙残骸段（`MobId.DEAD_HORNTAIL_MIN~HORNTAIL`），`damageMonster(Integer.MAX_VALUE)`，统计并汇报数量 |
| `seed` | SeedCommand | 掉落全部妙月种子 | 无 | 仅月妙 PQ 图（`HENESYS_PQ`）可用；6 个固定坐标按序掉 6 色种子，间隔 100ms |
| `mutemap` | MuteMapCommand | 切换本图禁言 | 无 | `map.setMuted` 开关翻转 |
| `night` | NightCommand | 本图切夜晚 | 无 | `broadcastNightEffect` |
| `music [曲名]` | MusicCommand | 本图切背景音乐 | 0~1 个 | 曲名走 `getLastCommandMessage` 保留原文，须在 `GameConstants.GAME_SONGS` 内；无参/未命中时 NPC 对话列曲单 |
| `pos` | PosCommand | 显示坐标 | 无 | 输出 x/y 与脚下 foothold ID |
| `openportal <名>` / `closeportal <名>` | OpenPortalCommand / ClosePortalCommand | 开/关本图传送点 | 1 个传送点名 | `map.getPortal(name).setPortalState(true/false)` |
| `reloadmap` | ReloadMapCommand | 重载当前地图 | 无 | `MapFactory.resetMap` 重建实例并回传图内全部玩家（非发起者提示），随后 `respawn` |
| `debuff <类型>` | DebuffCommand | 给附近玩家上异常 | 1 个 | 类型 slow/seduce/zombify/confuse/stun/poison/seal/darkness/weaken/curse（名字走 i18n，中文参数可用）；对范围 777777.7 内除自己外玩家 `giveDebuff` |

### 5.2 玩家管控与封禁

| 命令 | 类 | 作用 | 参数 | 关键逻辑要点 |
| --- | --- | --- | --- | --- |
| `kill <玩家>` | KillCommand | 击杀玩家 | 1 个 | `updateHpMp(0)` 并广播 GM 通知 |
| `hurt <玩家>` | HurtCommand | 打到 1 HP | 1 个 | `updateHp(1)` |
| `killmap` | KillMapCommand | 击杀本图全员 | 无 | 全图玩家 `updateHp(0)` |
| `healmap` | HealMapCommand | 全图回满 | 无 | 图内所有玩家 `healHpMp` |
| `healperson <玩家>` | HealPersonCommand | 指定玩家回满 | 1 个 | 目标 `healHpMp` |
| `hpmp <值> [玩家]` | HpMpCommand | 设置 HP/MP | 1~2 个 int | 将 HP 与 MP 同时设为该值 |
| `maxhpmp <增量> [玩家]` | MaxHpMpCommand | 抬升最大 HP/MP | 1~2 个 int | 在 `clientMax` 基础上补足既有 extraHp/extraMp 后同步上调双上限，至少 +1 |
| `fame <人气> [玩家]` | FameCommand | 设置人气 | 1~2 个 | `setFame`+单属性刷新 |
| `face <脸ID> [玩家]` | FaceCommand | 改脸型 | 1~2 个 | 校验 `isFace` 且物品名存在；`setFace`+刷新+`equipChanged` |
| `hair <发型ID> [玩家]` | HairCommand | 改发型 | 1~2 个 | 同 `face`，校验 `isHair` |
| `ban <角色> <原因>` | BanCommand | 封禁玩家 | ≥2 个 | 在线目标：写 `ipbans` 表、`banMacs`、`Character.ban`，5 秒后断开并全服公告；离线走 `Character.ban(ign, ...)`；成功回 GM 特效包 |
| `unban <角色>` | UnBanCommand | 解封 | 1 个 | `accounts.banned=-1` 并删除 `ipbans`/`macbans` 记录（JDBC 直写） |
| `monitor <玩家>` | MonitorCommand | 开关封包监控 | 1 个 | `MonitoredChrLogger.toggleMonitored`，并广播 GM 通知 |
| `monitors` | MonitorsCommand | 列被监控角色 | 无 | 遍历 `getMonitoredChrIds` 反查角色名 |
| `ignore <玩家>` | IgnoreCommand | 开关自动封禁白名单 | 1 个 | `AutobanFactory.toggleIgnored`，广播 GM 通知 |
| `ignored` | IgnoredCommand | 列白名单角色 | 无 | 遍历 `getIgnoredChrIds` 反查角色名 |
| `checkdmg <玩家>` | CheckDmgCommand | 查看玩家状态/伤害 | 1 个 | 输出四维、总 WATK/总 MAGIC、buff WATK/MATK、祝福技能等级、计算的最大基础伤害 |
| `inmap` | InMapCommand | 列本图玩家名 | 无 | 拼接图内所有角色名输出 |
| `fly <on\|off\|开启>` | FlyCommand | 账号级飞行开关 | 1 个 | `Server.changeFly(accId, ...)`，对该账号全部角色生效 |

### 5.3 点券与积分发放

| 命令 | 类 | 作用 | 参数 | 关键逻辑要点 |
| --- | --- | --- | --- | --- |
| `givenx [类型] [玩家] <数量>` | GiveNxCommand | 发放点券类货币 | 1~3 个 | 类型 `nx`/点券=1、`mp`/抵用券=2、`np`/信用点=4；仅数量时给自己发 1 类；`CashShop.gainCash` |
| `givevp [玩家] <数量>` | GiveVpCommand | 发放投票点 | 1~2 个 int | `client.addVotePoints`；两参时按名（不支持 ID）查目标 |
| `givems [玩家] <数量\|max\|min>` | GiveMesosCommand | 发放金币 | 1~2 个 | int 夹取，支持 `max`/`min` 描述词；`gainMeso(..., true)` |
| `giverp [玩家] <数量>` | GiveRpCommand | 发放奖励点 | 1~2 个 int | 直接累加 `rewardPoints` |

### 5.4 活动与广播

| 命令 | 类 | 作用 | 参数 | 关键逻辑要点 |
| --- | --- | --- | --- | --- |
| `startevent [人数=50]` | StartEventCommand | 开启频道活动 | 0~1 个 int | 以当前图为活动图创建 `Event` 挂到频道，全服蓝条+公告 |
| `endevent` | EndEventCommand | 结束频道活动 | 无 | `channelServer.setEvent(null)`（配合 `joinevent`/`leaveevent`） |
| `startmapevent` | StartMapEventCommand | 开启地图官方活动 | 无 | `map.startEvent(player)`，仅活动地图有效 |
| `stopmapevent` | StopMapEventCommand | 停止地图官方活动 | 无 | `map.setEventStarted(false)` |
| `notice <文本>` | NoticeCommand | 全服公告 | 任意文本 | 世界广播 `serverNotice(6)`，文本取 `getLastCommandMessage` |
| `rip <文本>` | RipCommand | 全服"讣告"广播 | 任意文本 | `joinStringFrom(params,1)` 拼前缀后世界广播 |
| `expeds` | ExpedsCommand | 列进行中的远征 | 无 | 逐频道列出远征类型/招募状态/人数/队长与成员名单 |
| `online2` | OnlineTwoCommand | 统计在线 | 无 | 各频道人数+名单（<50 人时列名），hint 显示总数 |

### 5.5 任务、计时器与调试

| 命令 | 类 | 作用 | 参数 | 关键逻辑要点 |
| --- | --- | --- | --- | --- |
| `startquest <任务ID>` | QuestStartCommand | 强制开始任务 | 1 个 int | 仅状态 0 时；若任务有 NPC 需求则以该 NPC 强制开始 |
| `completequest <任务ID>` | QuestCompleteCommand | 强制完成任务 | 1 个 int | 仅状态 1（进行中）时；同上按完成侧 NPC 需求 |
| `resetquest <任务ID>` | QuestResetCommand | 重置任务 | 1 个 int | 仅状态 ≠0 时 `quest.reset(player)` |
| `timer <玩家> <秒\|remove\|移除>` | TimerCommand | 给玩家发时钟 | 2 个 | `getClock(秒)`/`removeClock` 包 |
| `timermap <秒\|remove\|移除>` | TimerMapCommand | 全图时钟 | 1 个 | 对本图所有玩家发时钟包 |
| `timerall <秒\|remove\|移除>` | TimerAllCommand | 全世界时钟 | 1 个 | 对本世界所有在线角色发时钟包 |
| `maxenergy` | MaxEnergyCommand | 道场能量加满 | 无 | `setDojoEnergy(10000)` 并发 `getEnergy` 包 |
| `pe` | PeCommand | 模拟客户端发包 | 无 | 读工作目录 `pe.txt` 的 `pe=hex` 串，按 opcode 取 `PacketProcessor` handler 校验状态后直接 `handlePacket`（包编辑测试）；异常仅 warn 日志 |
| `togglecoupon <物品ID>` | ToggleCouponCommand | 开关倍率卡 | 1 个 int | `Server.toggleCoupon`（服务器级启用/停用该倍率卡物品） |
| `togglewhitechat` | ChatCommand | 切换 GM 白字聊天 | 无 | `toggleWhiteChat` 并回显当前状态 |

### 5.6 脚本/数据重载族

| 命令 | 类 | 作用 | 参数 | 关键逻辑要点 |
| --- | --- | --- | --- | --- |
| `reloadevents` | ReloadEventsCommand | 重载活动脚本 | 无 | 全部频道 `reloadEventScriptManager`；REST `reloadEventsByGMCommand` 复用此逻辑 |
| `reloaddrops` | ReloadDropsCommand | 重载掉落数据 | 无 | `MonsterInformationProvider.clearDrops` 清缓存 |
| `reloadportals` | ReloadPortalsCommand | 重载传送点脚本 | 无 | `PortalScriptManager.reloadPortalScripts`；REST 同名接口复用 |
| `reloadshops` | ReloadShopsCommand | 重载商店 | 无 | `ShopFactory.reloadShops` |

## 6. gm4 —— 运营管理指令（25 类 / 25 条指令）

### 6.1 全服倍率与公告

| 命令 | 类 | 作用 | 参数 | 关键逻辑要点 |
| --- | --- | --- | --- | --- |
| `servermessage <文本>` | ServerMessageCommand | 设置世界滚动公告 | 任意文本 | `world.setServerMessage(lastCommandMessage)` 保留原文 |
| `exprate <倍率>` | ExpRateCommand | 世界经验倍率 | 1 个 float | 夹取 ≥1，`world.setExpRate` 后世界广播 |
| `mesorate <倍率>` | MesoRateCommand | 世界金币倍率 | 1 个 float | 同 `exprate` 模式 |
| `droprate <倍率>` | DropRateCommand | 世界掉落倍率 | 1 个 float | 同上 |
| `bossdroprate <倍率>` | BossDropRateCommand | 世界 Boss 掉落倍率 | 1 个 float | 同上 |
| `questrate <倍率>` | QuestRateCommand | 世界任务倍率 | 1 个 float | 同上 |
| `travelrate <倍率>` | TravelRateCommand | 世界传送倍率 | 1 个 float | 同上（船/出租车等用时） |
| `fishrate <倍率>` | FishingRateCommand | 世界钓鱼倍率 | 1 个 int | 同上（int 型） |

### 6.2 物品与捡取

| 命令 | 类 | 作用 | 参数 | 关键逻辑要点 |
| --- | --- | --- | --- | --- |
| `proitem <装备ID> <属性> [速度跳跃]` | ProItemCommand | 生成自定义属性装备 | 2~3 个 | `getEquipById` 后 14 项属性（四维/watk/matk/acc/avoid/wdef/mdef/hp/mp 及速度跳跃）统一设值，打 `UNTRADEABLE` 后入包；仅装备类有效 |
| `seteqstat <属性> [速度跳跃]` | SetEqStatCommand | 背包装备全改属性 | 1~2 个 int | 遍历装备栏所有装备同 `proitem` 设值并 `UNTRADEABLE`、逐个 `forceUpdateItem` |
| `itemvac` | ItemVacCommand | 捡取全图掉落 | 无 | 无视归属直接 `pickupItem` 全部 MapItem |
| `forcevac` | ForceVacCommand | 强制真空捡取 | 无 | 逐件加锁：跳过已拾取；金币直加、即拾即用消耗、NX 卡按面值加点券、宠物重新 `createPet` 入包、其余 `addFromDrop` |

### 6.3 Boss 召唤族

| 命令 | 类 | 作用 | 参数 | 关键逻辑要点 |
| --- | --- | --- | --- | --- |
| `zakum` | ZakumCommand | 召唤扎昆 | 无 | 本体以 fake 怪生成 + 8 只手臂逐一真生成 |
| `horntail` | HorntailCommand | 召唤暗黑龙 | 无 | `map.spawnHorntailOnGroundBelow`（封装多部位生成） |
| `pinkbean` | PinkbeanCommand | 召唤品克缤 | 无 | 单体生成 |
| `pap` | PapCommand | 召唤帕普拉图斯 | 无 | 生成 `PAPULATUS_CLOCK` |
| `pianus` | PianusCommand | 召唤皮亚奴斯 | 无 | 生成 `PIANUS_R` |
| `cake [HP]` | CakeCommand | 召唤巨型蛋糕 Boss | 0~1 个 | 可自定义初始 HP：≤0 或超 int 上限取 `Integer.MAX_VALUE` |

### 6.4 永久生命体与玩家 NPC（写 `plife` 表）

| 命令 | 类 | 作用 | 参数 | 关键逻辑要点 |
| --- | --- | --- | --- | --- |
| `pnpc <NPCID>` | PnpcCommand | 生成永久 NPC | 1 个 int | 图内已含该 NPC 或名称为 MISSINGNO 时拒绝；坐标取脚下 `getGroundBelow`，INSERT `plife`(type=n, mobtime=-1) 后在本世界**所有频道**生成并广播 |
| `pnpcremove [NPCID]` | PnpcRemoveCommand | 移除永久 NPC | 0~1 个 int | 指定 ID 删对应 `plife` 行；缺省按当前位置 ±50 坐标范围删；随后全频道 `destroyNPC`，汇报删除数 |
| `pmob <怪ID> [mobTime]` | PmobCommand | 生成永久怪物 | 1~2 个 int | INSERT `plife`(type=m, mobtime 可指定，默认 -1) 并在本世界各频道 `addMonsterSpawn`+`addAllMonsterSpawn` |
| `pmobremove [怪ID]` | PmobRemoveCommand | 移除永久怪物 | 0~1 个 int | 同 `pnpcremove` 的选择逻辑，删行后全频道移除刷新点 |
| `playernpc <角色名>` | PlayerNpcCommand | 生成玩家 NPC | 1 个 | 以该角色外形在当前位置 `spawnPlayerNPC`（快照型，非 plife） |
| `playernpcremove <角色名>` | PlayerNpcRemoveCommand | 移除玩家 NPC | 1 个 | `removePlayerNPC` |
| `warptolife <lifeID>` | WarpToLifeCommand | 传送到生物所在地图 | 1 个 int | `MapFactory.getMapByLifeId` 按 `plife` 反查地图后传送（描述复用 `GotoNPCCommand.message1`：不含玩家） |

## 7. gm5 —— 调试诊断指令（6 类 / 6 条指令）

| 命令 | 类 | 作用 | 参数 | 关键逻辑要点 |
| --- | --- | --- | --- | --- |
| `debug <类型>` | DebugCommand | 打印调试信息 | 1 个 | 类型：monster（怪+控制器/仇恨）、packet（空实现）、portal、spawnpoint、pos、map、mobsp、event、areas、reactors、servercoupons(coupons)、playercoupons、timer（线程池统计）、marriage、buff；`type`/`help` 列出全部类型 |
| `set <值...>` | SetCommand | 写入调试变量 | 任意个 int | 依序填 `ServerConstants.DEBUG_VALUES[i]`，供后续测试读取 |
| `showpackets` | ShowPacketsCommand | 收包日志开关 | 无 | 翻转 GameConfig `server.Debug.use_debug_show_rcvd_packet`（`GameConfig.update` 热写 DB） |
| `showmovelife` | ShowMoveLifeCommand | 生物移动日志开关 | 无 | 翻转 `use_debug_show_life_move`，同上热更 |
| `showsessions` | ShowSessionsCommand | 打印会话追踪 | 无 | `SessionCoordinator.printSessionTrace(c)` |
| `iplist` | IpListCommand | 列所有在线玩家 IP | 无 | 按世界分组列出角色名-远程地址，NPC 22000 对话框展示 |

## 8. gm6 —— 服主级指令（17 类 / 17 条指令）

| 命令 | 类 | 作用 | 参数 | 关键逻辑要点 |
| --- | --- | --- | --- | --- |
| `setgmlevel <玩家> <等级>` | SetGmLevelCommand | 设置 GM 等级 | 2 个 | 同时改角色与客户端 gmLevel 并通知对方 |
| `warpworld <世界ID>` | WarpWorldCommand | 转区 | 1 个 byte | 从当前世界/地图移除→`setWorld`→`saveCharToDB`（防双实例）→发频道切换包；目标世界不存在仅提示 |
| `saveall` | SaveAllCommand | 全服存档 | 无 | 遍历全部世界全角色 `saveCharToDB`，另调 `HpMpAlertService.saveAll()`，广播 GM 通知 |
| `dcall` | DCAllCommand | 全服下线 | 无 | 断开全部非 GM 在线玩家 |
| `mapplayers` | MapPlayersCommand | 列同图玩家 | 无 | 遍历**所有世界**找同地图角色，输出 名字: HP/MaxHP |
| `getacc <角色>` | GetAccCommand | 查角色账号名 | 1 个 | 输出 角色名 ↔ 账号名 |
| `shutdown <分钟\|now>` | ShutdownCommand | 定时/立即关服 | 1 个 | `now`=1ms 立即；>1 分钟先向全服玩家广播倒计时，`TimerManager.schedule(Server.shutdown(false))` |
| `clearquestcache` | ClearQuestCacheCommand | 重载全部任务 | 无 | `Quest.clearCache()` |
| `clearquest <任务ID>` | ClearQuestCommand | 重载指定任务 | 1 个 int | `Quest.clearCache(questId)` 精确清缓存 |
| `supplyratecoupon <enabled\|开启>` | SupplyRateCouponCommand | 倍率卡使用开关 | 1 个 | 热写 `server.Game Mechanics.use_supply_rate_coupons`；注意现实现为"或"比较，任何参数当前都会写入 true（疑似应为"且"，调整前需核对源码） |
| `spawnallpnpcs` | SpawnAllPNpcsCommand | 全图生成玩家 NPC | 无 | `PlayerNPC.multicastSpawnPlayerNPC(mapId, world)` |
| `eraseallpnpcs` | EraseAllPNpcsCommand | 移除所有玩家 NPC | 无 | `PlayerNPC.removeAllPlayerNPC()` |
| `addchannel <世界ID>` | ServerAddChannelCommand | 给大区加频道 | 1 个 int | `ThreadManager` 异步执行 `Server.addChannel`；按返回码（≥0 新频道号，-1/-2/-3）区分成功/失败原因提示 |
| `addworld` | ServerAddWorldCommand | 添加新大区 | 无 | 异步 `Server.addWorld`，失败按返回码提示 |
| `removechannel <世界ID>` | ServerRemoveChannelCommand | 移除大区末频道 | 1 个 int | 异步 `Server.removeChannel`，成功汇报剩余频道数 |
| `removeworld` | ServerRemoveWorldCommand | 移除最后大区 | 无 | 仅剩 1 个大区时拒绝；异步 `Server.removeWorld` |
| `devtest` | DevtestCommand | 运行开发测试脚本 | 无 | 内嵌 `DevtestScriptManager` 加载 `devtest.js` 并 `invokeFunction("run", player)`；异常仅记日志 |

---

## 9. 命令总数统计

### 9.1 按等级统计（与 `V1.5.1__create_command_info.sql` 初始数据一致）

| 权限等级 | 目录 | 命令类数 | 注册指令数（含别名） | 别名明细 |
| --- | --- | --- | --- | --- |
| gm0 玩家 | `commands/gm0` | 25 | 26 | `commands` → HelpCommand |
| gm1 | `commands/gm1` | 6 | 6 | — |
| gm2 | `commands/gm2` | 39 | 42 | `summon` → SummonCommand（主名 `warphere`）；`reach`、`follow` → ReachCommand（主名 `warpto`） |
| gm3 | `commands/gm3` | 57 | 57 | — |
| gm4 | `commands/gm4` | 25 | 25 | — |
| gm5 | `commands/gm5` | 6 | 6 | — |
| gm6 | `commands/gm6` | 17 | 17 | — |
| **合计** | `commands/*` | **175** | **179** | — |

另：模块根包框架类 2 个（`Command`、`CommandsExecutor`），模块内 Java 文件合计 **177** 个。

### 9.2 补充说明

- 注册指令数 > 命令类数仅出现在 gm0（1 个别名）与 gm2（3 个别名），其余等级一一对应。
- `command_info.level` 可经后台（`/command/v1/updateCommand`）在线调整，因此**运行期某条指令的实际所需等级可能与本章分组不同**；本章按 `default_level`（类所在包）归类。
- 等级调整/停用后无需重启：`CommandService.updateRegisteredCommands` 会同步内存注册表与 `commandsNameDesc` 帮助列表。
