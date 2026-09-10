# 08 游戏频道包处理器（handlers）详细设计 —— A 篇（AbstractDealDamage ~ GuildOperation）

## 0. 模块概览

| 项目 | 内容 |
| --- | --- |
| 模块路径 | `gms-server/src/main/java/org/gms/net/server/channel/handlers/` |
| 类总数 | 149 个（按文件名字母序） |
| 本篇覆盖 | 前 50 个：`AbstractDealDamageHandler` ~ `GuildOperationHandler`（字母序前 1/3） |
| 剩余范围 | `GuildOperationHandler` 之后的 99 个由《09-handlers-B.md》（中间 1/3）与《10-handlers-C.md》（末尾 1/3）负责 |

所有处理器均继承 `org.gms.net.AbstractPacketHandler`，核心入口统一为：

```java
public void handlePacket(InPacket p, Client c)
```

`InPacket` 为服务端解包后的读包游标（`readByte/readShort/readInt/readLong/readString/skip`），`Client` 为玩家客户端会话（持有 `getPlayer()`、`getWorldServer()`、`getChannelServer()` 等）。处理器由 opcode 注册表（见《06-net-encryption-netty-opcodes-packet.md》）分发。

依赖模块：

- `org.gms.client.*`：`Character`、`Client`、`Skill/SkillFactory`、`BuffStat`、`Family/FamilyEntry/FamilyEntitlement`、`BuddyList`、`CashShop`、`inventory.*`
- `org.gms.server.*`：`StatEffect`、`TimerManager`、`life.*`（Monster/MobSkill）、`maps.*`（MapleMap/Portal/Summon/DoorObject）、`guild.*`（Guild/Alliance/GuildPackets）、`ChatLogger`、`CashShop.CashItemFactory`
- `org.gms.net.server.*`：`Server`、`PlayerBuffValueHolder`、`coordinator.world.InviteCoordinator`、`coordinator.matchchecker.*`
- `org.gms.client.processor.*`：`stat.AssignAPProcessor/AssignSPProcessor`、`npc.DueyProcessor/FredrickProcessor`（薄处理器转发的业务下沉层）
- `org.gms.client.autoban.AutobanFactory`：外挂检测计分/告警（`alert`、`addPoint`、`autoban`）
- `org.gms.util.*`：`PacketCreator`（出包）、`DatabaseConnection`（JDBC）、`I18nUtil`、`Randomizer`
- MyBatis/Spring 层：`ServerManager.getApplicationContext()`（`CashOperationHandler` 取 `CharacterService`/`NoteService`）

通用设计约定：

1. **客户端锁**：涉及库存/点券等易被并发利用的操作统一用 `c.tryacquireClient()` / `c.releaseClient()` 串行化（`CancelChair`、`CashOperation`、`CouponCode`、`FaceExpression` 等）。
2. **状态兜底**：几乎所有分支的失败路径都回发 `PacketCreator.enableActions()` 或 `enableCSActions()`，避免客户端卡在"不可操作"状态。
3. **外挂检测**：伤害/距离/攻击间隔/人气/聊天长度等异常走 `AutobanFactory.*`，`addPoint` 累计封禁、`alert` 仅告警。

---

## 1. AbstractDealDamageHandler（抽象基类）

**继承**：`AbstractPacketHandler`。所有攻击类处理器（近战 `CloseRangeDamageHandler`、远程 `RangedAttackHandler`、魔法 `MagicDamageHandler` 等）的公共父类，负责攻击包解析、伤害合法性校验与伤害结算。

### 1.1 内嵌类

- `public static class AttackInfo`：一次攻击的解码结果。字段：`numAttacked`（目标数）、`numDamage`（每目标伤害段数）、`numAttackedAndDamage`（二者 nibble 打包）、`skill/skilllevel/stance/direction/rangedirection/charge/display`、`allDamage: Map<oid, List<Integer>>`（目标 OID→伤害列表，值为负数表示暴击编码）、`ranged/magic`、`speed`、`position`。
  - `public StatEffect getAttackEffect(Character chr, Skill theSkill)`：取技能效果；技能等级为 0 且在 PQ 地图用 PQ 技能时按 1 级处理；`display > 80` 且技能无动作时触发 `AutobanFactory.FAST_ATTACK`。
- `private static final class DistanceCheckSample`：距离校验最终采用的坐标样本（checkPos、distanceSq、是否来自瞬移/位移补偿上下文）。

### 1.2 公有/受保护方法全表

| 方法 | 可见性 | 说明 |
| --- | --- | --- |
| `protected void applyAttack(AttackInfo attack, Character player, int attackCount)` | protected | 伤害结算主流程（见下） |
| `protected AttackInfo parseDamage(InPacket p, Character chr, boolean ranged, boolean magic)` | protected | 攻击包解码 + 伤害上限估算（见下） |
| `handlePacket` | — | 抽象层未实现，由子类完成（子类先做职业/buff 前置检查，再调 `parseDamage` → 广播攻击动作 → `applyAttack`） |

主要私有静态辅助：`decodeDamage`（负数暴击解码）、`damageMonsterWithSkill`（按动画时间延时结算，用于圣灵之锤/群攻技）、`isWithinAttackBox`/`intersectsAnyAttackBox`/`getMonsterBounds`/`getWorldBbox`/`calculateDistanceSq`/`chooseBestDistanceCheckSample`/`shouldUseBoundingBox`（技能攻击框 vs 怪物 bbox 的几何距离判定与位移补偿）、`shouldSkipDistanceHackCheck`/`isFullScreenDistanceExempt`/`isNonSpatialAttackSkill`/`isPiercingProjectileWithoutAttackBox`/`hasReliableDistanceGeometry`（距离检测豁免）、`detectionAttackInterval`（攻击间隔三层检测）、`buildBboxInfo`（异常日志）。

### 1.3 `applyAttack` 关键流程

1. 地图所有权限制（`map.isOwnershipRestricted`）与封号检查，直接 return。
2. 若带技能：取 `SkillFactory.getSkill` 与 `attackEffect`；MP 不足计 `MPCON` 分；按技能特例处理 buff 施放（Poison Bomb 定点 applyTo、终极攻击/隐藏技能放宽 mobCount）；`numAttacked > mobCount` 时 `MOB_COUNT` autoban。
3. 金钱爆炸（Meso Explosion）：对 `allDamage` 中的地上的金币 MapItem 加锁校验，按 100ms 间隔调度 `map.pickItemDrop` 移除。
4. 遍历每个目标怪物：
   - **距离检测**：先按技能攻击框（`isWithinAttackBox`，综合 direction/stance/当前朝向三种朝向与 现位置/瞬移前/位移前 三种坐标）判定合法命中；未命中框且几何来源可靠（技能有 lt/rb 或怪物有 bbox）时，用 `chooseBestDistanceCheckSample` 选最短距离样本，超过按 远程/魔法/Aran/技能 特例加成后的 `distanceToDetect` 阈值则记录最差样本。全屏技能（创世/暴风雪/陨石）、Energy Charge、Avenger 等豁免。
   - 魔法/武器免疫将伤害置 1；武道馆 Boss 的蜗牛投掷术限伤 30% 最大 HP。
   - 累计单怪伤害 `totDamageToOneMonster` 并 `monster.aggroMonsterDamage` 产生仇恨。
   - **技能附带效果**（按技能 ID 分支）：Pickpocket 掉金币、Energy Drain/Vampire 吸血、Steal 偷道具、Fire/Ice Demon 设置临时属性弱点、Homing Beacon 锁定、Flame Thrower 施毒；Aran 雪电荷、Hamstring/Slow/Blind 减速减命中、骑士系属性充填临时弱点、Combo Drain 回血、夜盗/暗影双刀 Venoms 叠毒、弓手 Mortal Blow 处决；固定伤害技能（`getFixDamage`）校验 + 终极蜗牛壳消耗逻辑（`use_ultra_three_snails`）；技能自带 `MonsterStati` 概率上状态；圣灵之锤/群攻技按百分比/延时结算。
   - `map.damageMonster` 正式扣血；怪物带武器/魔法反弹（`PHYSICAL_AND_MAGIC_COUNTER`）时按 MobSkill 反伤。
5. 消费瞬移/位移补偿上下文；若存在最差距离违规样本，`AutobanFactory.DISTANCE_HACK.addPoint` 并记录含 bbox 详情的告警日志。
6. 整体 try/catch 打印异常（吞掉，防止攻击线程中断）。

### 1.4 `parseDamage` 关键流程

1. 读包头：`numAttackedAndDamage`（高低 nibble 拆分）、`skill`；调 `detectionAttackInterval` 做攻击间隔检测（SKIP_SET 持续施法技能、PASSIVE_SET 被动触发技全跳过；小于 `Character.MIN_INTERVAL` 视为网络抖动跳过；per-skill 滑动窗口 STABLE_HACK 计分/BURST 告警；全局间隔跨技能计分）。
2. 蓄力技（龙息/终极无限/手雷/毒弹等）读 `charge`；读 `display/direction/stance`。
3. 金钱爆炸特殊分支：支持纯金币包与"怪物+尾部金币"两种格式，直接产出 `allDamage`（金币 OID→null）。
4. 远程分支读 `speed/rangedirection` 及暴风箭雨等特例的额外 4 字节。
5. **计算理论伤害上限 `calcDmgMax`**：魔法公式 / 幸运七 / 龙吼 / 毒星公式 / 通用 `calculateMaxBaseDamage`；魔法系按元素放大增幅、`matk` 修正、Heal 专用公式与 `speed=7`；暗器分身按 `moneyCon*10*1.5`；普通技能按 `damage%`。
6. 组合（COMBO）buff：普通/进阶组合伤害加成，终结技按剩余球数 1.2~2.5 倍；能量满 15000 再乘；所有 buff 的 `damage-100` 增益累乘；Aran 新手图 +80000。
7. 暴击资格（弓/贼/风灵/夜光/Aran3+/拳系或 Sharp Eyes）；Sharp Eyes 按当前等级 `y` 修正上限；影子分身后半段 50%；Snipe 服务端重掷为 195000~200000；竹雨上限 30% 最强道馆 Boss HP。
8. 逐目标读 OID+14 字节：骑士充填按怪物弱点属性乘 1.05~1.2+；技能元素弱点 ×1.5；Shadow Web 按怪物 HP 比例、Body Pressure 按百分比 HP 取上限；逐段读伤害并校验：超过 `maxWithCrit*1.5` 告警 `DAMAGE_HACK.alert`，超过 5 倍 `addPoint`；暴击伤害编码为 `-Integer.MAX_VALUE + damage - 1`；`numDamage` 超过技能最大段数（分身翻倍）计分。
9. Poison Bomb 尾部读取落点 `position`，返回 `AttackInfo`。

### 1.5 核心协作类

`SkillFactory`/`StatEffect`（技能与效果）、`Monster`/`MonsterStats`/`MonsterStatusEffect`（目标与状态）、`MapleMap`（结算/广播）、`AutobanFactory`（检测）、`TimerManager`（延时结算/掉币）、`PacketCreator`（广播）。

---

## 2. AbstractMovementPacketHandler（抽象基类）

**继承**：`AbstractPacketHandler`。玩家/宠物/召唤/龙移动包的公共解析基类。

### 2.1 公有/受保护方法全表

| 方法 | 可见性 | 说明 |
| --- | --- | --- |
| `protected List<LifeMovementFragment> parseMovement(InPacket p) throws EmptyMovementException` | protected | 把移动包解析为移动片段列表（转发广播用） |
| `protected void updatePosition(InPacket p, AnimatedMapObject target, int yOffset) throws EmptyMovementException` | protected | 边解析边把服务端位置/姿态同步到 `target`（yOffset 用于宠物等贴地偏移） |

私有静态辅助：`handleTeleportMove`（3/4 瞬移：同步坐标并对玩家调 `markTeleportLikeMove` 记录前后坐标，供攻击距离双坐标校验）、`handleDashLikeMove`（7/8/9 突进类：Assaulter/Assassinate/Rush 同步坐标）、`handleChairMove`（11 椅子：只同步姿态）、`handleJumpDownMove`（15 下跳）、`snapshotPosition`、`readPositionWithOffset`、`estimateRelativeMovePosition`（相对位移推算落点）、`applyPositionAndStance`、`recordRegularMove`（玩家调用 `markRegularMove` 记录位移前坐标）。

### 2.2 `parseMovement` 指令分发表

| command | 语义 | 产出 |
| --- | --- | --- |
| 0/5/17 | 普通移动/漂浮 | `AbsoluteLifeMovement`（x,y,wobble,fh,state,duration） |
| 1/2/6/12/13/16/18/19/20/22 | 跳跃/击退/二段跳/弹簧/Aran Combat Step 等 | `RelativeLifeMovement`（相对位移） |
| 3/4/7/8/9/11 | 瞬移消失/出现、Assaulter、Assassinate、Rush、椅子 | `TeleportMovement` |
| 14 | 下跳（旧格式） | 跳过 9 字节 |
| 10 | 换装 | `ChangeEquip` |
| 15 | 下跳 | `JumpDownMovement`（含 originFh） |
| 21 | Aran 特殊 | 跳过 3 字节 |
| 其他 | 未知 | warn 日志并抛 `EmptyMovementException` |

`numCommands < 1` 或结果为空同样抛 `EmptyMovementException`（由上层吞掉该包）。

### 2.3 `updatePosition` 关键流程

1. 读指令数，逐条按上表分派。
2. 绝对移动(0/5/17)：`target.setPosition(新坐标+yOffset)`、`setStance`，并 `recordRegularMove`。
3. 相对移动组：仅当 target 是 `Character` 时按 delta 推进位置（宠物/召唤不做物理模拟），更新姿态并记录。
4. 瞬移(3/4)、突进(7/8/9)：读绝对坐标直接同步；瞬移额外登记前后坐标。
5. 椅子(11)：跳过坐标，只同步 stance。

**核心协作类**：`org.gms.server.movement.*`（各移动片段实现）、`AnimatedMapObject`、`Character`（位移上下文）、`EmptyMovementException`。

---

## 3. AcceptFamilyHandler

**包**：家族邀请的接受/拒绝应答。`use_family_system` 关闭时直接返回。

**handlePacket 流程**：

1. 读 `inviterId`、（跳过 inviterName）、`accept`；从世界玩家存储取邀请人 `inviter`，不存在则只回 `sendFamilyMessage(0,0)`。
2. `InviteCoordinator.answerInvite(FAMILY, ...)`，`NOT_FOUND` 则返回。
3. 接受时按三种情况：
   - 邀请人有家族、自己无家族：新建 `FamilyEntry` 并 `setSenior`，失败给邀请人发 message(1)；成功则 `family.addEntry` + `insertNewFamilyRecord` 落库。
   - 邀请人有家族、自己也有家族（且自己是族长）：两家族代数和 ≤ `family_max_generations` 时 `targetEntry.join` 吸并，否则双方收 message(76)。
   - 邀请人无家族：创建新 `Family`（id=-1 自动分配），注册到 `WorldServer.addFamily`，建邀请人 entry 设为族长；自己无家族则同时加入并落库两条记录，有家族则吸并自己的家族。
4. 成功后：家族广播 `sendFamilyJoinResponse(true)`、给本人 `getSeniorMessage` + `getFamilyInfo`，`updateSeniorFamilyInfo(true)`。拒绝则通知邀请人 `sendFamilyJoinResponse(false)`。
5. 私有 `insertNewFamilyRecord(cid, familyId, seniorId, updateChar)`：直连 JDBC 写 `family_character`，必要时同步 `characters.familyid`。

**协作类**：`Family`/`FamilyEntry`、`InviteCoordinator`、`WorldServer.getPlayerStorage`、`DatabaseConnection`。

---

## 4. AdminChatHandler

**包**：GM 管理员公告（`/alert*`、`/notice*`、`/slide*` 系列命令触发）。

**handlePacket 流程**：

1. 非 GM 直接返回。
2. 读 `mode`、`message`、公告类型 byte，构造 `PacketCreator.serverNotice(type, message)`。
3. mode=0 全世界广播（`WorldServer.broadcastPacket`），mode=1 当前频道（`ChannelServer.broadcastPacket`），mode=2 当前地图（`map.broadcastMessage`）；三种情况均写 `ChatLogger.log`（"Alert All/Ch/Map"）。

**协作类**：`ChatLogger`、`WorldServer/ChannelServer/MapleMap` 广播通道。

---

## 5. AdminCommandHandler

**包**：GM 客户端内置管理指令（右键菜单/快捷指令）。非 GM 直接返回；按 `mode` 分派：

| mode | 功能 | 流程要点 |
| --- | --- | --- |
| 0x00 | 召唤包怪 | `ItemInformationProvider.getSummonMobs(itemId)` 按概率在玩家脚下 `spawnMonsterOnGroundBelow` |
| 0x01 | /d 清道具 | 遍历指定背包槽 `InventoryManipulator.removeFromSlot`（注意：循环内 return，只清第 1 格） |
| 0x02 | 设置经验 | `player.setExp(p.readInt())` |
| 0x03 | /ban 提示 | 仅黄字提示改用 `!ban` |
| 0x04 | /block 封禁 | 读名字/原因类型/天数/描述；在线则 `ban`（-1 永久）或 `block+sendPolice`；离线走 `Character.ban(victim,...)`；回 `getGMEffect` |
| 0x10 | /h 隐身 | `player.hide(byte==1)` |
| 0x11 | 进图附带指令 | 子模式 0=/u 列出当前地图玩家名；12=/uclip 忽略 |
| 0x12 | 传送他人 | 按名字+地图 ID `changeMap` |
| 0x15 | 杀怪 | 按怪物 ID+数量 `killMonster`（无越界检查） |
| 0x16 | 重置任务 | `Quest.getInstance(short).reset(player)` |
| 0x17 | 召唤怪 | 按 mobId×quantity 召唤 |
| 0x18 | 查怪物 HP | 遍历本图怪物打印匹配 ID 的 HP |
| 0x1E | 警告 | 给目标发 `serverNotice(1)`，回 `getGMEffect(0x1E, 成功?)` |
| 0x24 | 遗物排行 | 空实现 |
| 0x77 | 测试 | 按剩余长度读 int/short 打 debug 日志 |
| default | — | log.info 记录新出现的 GM 包 |

**协作类**：`ItemInformationProvider`、`LifeFactory`、`InventoryManipulator`、`Quest`、`ChannelServer.getPlayerStorage/getMapFactory`。

---

## 6. AdminLogHandler

**包**：客户端管理端日志上报；服务端实现为空方法（注释 "harhar"），仅保留 opcode 占位。

---

## 7. AllianceOperationHandler

**包**：联盟（Guild Alliance）UI 操作。

**handlePacket 前置校验**：

1. 无公会 → enableActions 返回。
2. 有联盟对象时：mode=4（建盟）不可能出现则提示并返回；玩家联盟等级 > 2 或不在本联盟公会 → 返回。
3. 无联盟对象时：仅允许 mode=4（接受建盟邀请）。

**mode 分派**：

| mode | 功能 | 流程要点 |
| --- | --- | --- |
| 0x01 | 显示联盟成员信息 | `Server.allianceMessage(sendShowInfo)` |
| 0x02 | 退盟 | 仅会长（guildRank==1）；`Alliance.removeGuildFromAlliance`，失败提示 |
| 0x03 | 邀请公会入盟 | 读公会名；联盟满员（`getCapacity`）提示，否则 `Alliance.sendInvitation` |
| 0x04 | 接受邀请 | 校验无联盟且是会长；`answerInvitation(true)`；容量检查；`Server.addGuildToAlliance`；会长联盟等级设 2；广播 addGuild/updateAllianceInfo/allianceNotice 三包；公会群发欢迎消息 |
| 0x06 | 踢公会出盟 | 仅盟主（allianceRank==1）；`removeGuildFromAlliance` + 广播移除/更新/公告 + 被踢公会收 `disbandAlliance` |
| 0x07 | 转让盟主 | 仅盟主；目标须联盟等级 2；`alliance.changeLeader`，广播 `getGuildAlliances` + 公告 |
| 0x08 | 改联盟等级头衔 | 读 5 个头衔；`alliance.updateRankTitles` + 广播 `changeAllianceRankTitle` |
| 0x09 | 升/降玩家联盟等级 | 读 cid+方向；私有 `changePlayerAllianceRank`：newRank 夹在 [3,5]，`setAllianceRank`+`saveGuildStatus`+广播+公告 |
| 0x0A | 改联盟公告 | `updateNotice` + 广播 `allianceNotice` + `dropMessage(5)` |
| default | — | 提示不支持的操作 |

**协作类**：`Alliance`/`Guild`/`GuildCharacter`/`GuildPackets`、`Server.getInstance()` 联盟消息广播、`I18nUtil`。

---

## 8. AranComboHandler

**包**：Aran 连击（COMBO_ABILITY）计数更新。

**流程**：仅当 Aran 职业（`GameConstants.isAran`）且技能等级 > 0（或新手 2000）时处理：距上次连击超过 3 秒则清零；`combo++`；每逢 10 的整数倍且 `combo/10 ≤ 技能等级`（2000 新手除外）时 `COMBO_ABILITY.getEffect(combo/10).applyComboBuff`；最后 `setCombo/setLastCombo`。

**协作类**：`SkillFactory`、`StatEffect.applyComboBuff`。

---

## 9. AutoAggroHandler

**包**：怪物对玩家自动仇恨触发。

**流程**：GM 隐身（`isHidden`）直接返回；读怪物 OID，`map.getMonsterByOid` 找到则 `monster.aggroAutoAggroUpdate(player)` 登记仇恨目标。

---

## 10. AutoAssignHandler

**包**：能力值自动分配（Auto Assign 按钮）。

**流程**：直接委托 `AssignAPProcessor.APAutoAssignAction(p, c)`（属性点自动分配的业务与校验全部在 processor 层实现）。本类为薄转发。

---

## 11. BBSOperationHandler

**包**：公会 BBS 公告板（Guild Board）操作。无公会直接返回。

**mode 分派**：

| mode | 功能 | 说明 |
| --- | --- | --- |
| 0 | 新建/编辑主题 | `bEdit` 决定编辑（读 localthreadid）；`bNotice` 置顶公告；标题截 25 字、正文截 600 字；icon 0x64~0x6a 需持有对应道具（5290000+），否则 0~3；分别走 `newBBSThread/editBBSThread` |
| 1 | 删主题 | `deleteBBSThread(c, localthreadid)` |
| 2 | 列主题 | `listBBSThreads(c, start*10)` 分页查 `bbs_threads` |
| 3 | 看主题 | `displayThread(c, localthreadid)` |
| 4 | 回复 | 正文截 25 字，`newBBSReply`：查 threadid → 插 `bbs_replies` → 回复数+1 → 刷新显示 |
| 5 | 删回复 | `deleteBBSReply(c, replyid)`（第二个 int 为 replyid） |

**公有静态方法**：`deleteBBSThread`（本人或公会 rank≤2 才可删，级联删 replies）、`deleteBBSReply`（同权限，replycount-1）、`displayThread(client, threadid[, bIsThreadIdLocal])`（查主题+回复，`GuildPackets.showThread` 输出）。

**协作类**：`GuildPackets.BBSThreadList/showThread`、`DatabaseConnection`（表 `bbs_threads`/`bbs_replies`）。

---

## 12. BeholderHandler

**包**：黑骑的小黑（Beholder）召唤技能动作。

**流程**：遍历玩家 summons 找 OID 匹配的 `Summon`；命中则读技能 ID：`AURA_OF_BEHOLDER` 跳 2 字节、`HEX_OF_BEHOLDER` 跳 1 字节（buff 实际结算在服务端定时器侧）；召唤不存在则 `clearSummons()` 清理残留。

---

## 13. BuddylistModifyHandler

**包**：好友列表增删改。

**mode 分派**：

- **mode=1 添加**：读名字+分组；分组 >16 字或名字长度非法直接返回（防破解）。已在列表且同组 → 提示已存在；列表满 → 提示；否则查目标（在线优先 `PlayerStorage`，离线私有 `getCharacterIdAndNameFromDatabase` 查 `characters` 表含 buddyCapacity）：
  - 目标在线：`world.requestBuddyAdd` 得 `BuddyAddResult`（目标满/已在列表/可发请求）；`ALREADY_ON_LIST` 时 `notifyRemoteChannel` 通知对方频道直接互相加为可见好友。
  - 目标离线：JDBC 查 `buddies` 判断对方满/已存在；不满足则在 `buddies` 插 `pending=1` 记录。
  - 本方 `buddylist.put` 新 entry 并回 `updateBuddylist`。
  - 已在列表但不同组 → 仅 `changeGroup`。
- **mode=2 接受**：读 otherCid；列表未满时：查对方名字（在线/DB），随后 JDBC 三步落库——DELETE+INSERT 本方 `pending=0` 记录、UPDATE 对方记录 `pending=0`（幂等）；`buddylist.put` + `updateBuddylist` + `notifyRemoteChannel`；最后 `nextPendingRequest` 弹出下一条待处理请求并回 `requestBuddylistAdd`。
- **mode=3 删除**：`player.deleteBuddy(otherCid)`。

内嵌私有类 `CharacterIdNameBuddyCapacity extends CharacterNameAndId`（附带好友容量）。**协作类**：`BuddyList/BuddylistEntry/BuddyAddResult/BuddyOperation`、`World.requestBuddyAdd/buddyChanged/find`、`DatabaseConnection`。

---

## 14. CancelBuffHandler

**包**：客户端主动取消持续施法/引导类 buff。

**流程**：读 sourceid；对 BIG_BANG（三系）、HURRICANE（弓/风灵）、PIERCING_ARROW、RAPID_FIRE、FIRE/ICE_BREATH 等持续施法技，广播 `PacketCreator.skillCancel` 让他人停止特效；其余技能走默认 `player.cancelEffect(effect, false, -1)` 真正撤销 buff。

---

## 15. CancelChairHandler

**包**：离开地图座椅（地图 chair）。

**流程**：读座位 id；`id >= map.getSeats()` 直接返回（防越界）；持客户端锁执行 `mc.sitChair(id)`（传座位号即坐下/取消逻辑，由 Character 内部按当前状态翻转）。

---

## 16. CancelDebuffHandler

**包**：客户端请求取消异常状态。实现体为全注释的历史代码，当前为空操作（注释 "TIP: BAD STUFF LOL!"，服务端不允许客户端自行清除 debuff）。

---

## 17. CancelItemEffectHandler

**包**：取消药水/道具持续时间效果。

**流程**：读 `itemId = -p.readInt()`（编码取负）；`ItemInformationProvider.noCancelMouse(itemId)`（不可主动取消的道具，如鼠标保护类）则返回；否则 `player.cancelEffect(itemId)` 按 itemid 撤销对应效果。

---

## 18. CashOperationHandler

**包**：现金商城（Cash Shop）内所有交易操作。**构造器注入 `NoteService`**（Spring 管理）。

**handlePacket 总流程**：商城未打开 → enableActions；`tryacquireClient` 获取锁后按 `action` 分派，`finally releaseClient`；获锁失败回 enableActions。

| action | 功能 | 流程要点 |
| --- | --- | --- |
| 0x03/0x1E | 购买道具/礼包 | 读货币类型 useNX+SN；`canBuy`（在售且余额够，日志记录购买）；0x03 再校验：现金装扮需等级 ≥16、倍率券需 `use_supply_rate_coupons`、MapleLife 需 ≥30；礼包走 `CashItemFactory.getPackage` 逐件入库；容量检查 `ensureCashInventoryCapacity`；扣款 `cs.gainCash` 入现金仓库，回 `showBoughtCashItem/Package` + `showCash` |
| 0x04 | 送礼 | 校验生日 `checkBirthday`、收件人存在（`CharacterService.findByName`）、非同账号、留言 ≤73 字；扣 NX_PREPAID，`cs.gift` 入对方礼物箱，回 `showGiftSucceed`；`noteService.sendNormal` 发纸条并在线即时 `show` |
| 0x05 | 改心愿单 | 清空后读 10 个 SN，在售才加入，回 `showWishList(true)` |
| 0x06 | 扩背包格 | mode=0 直购 4000 点加 4 格；mode=1 买道具（itemId-9110000)/1000 定类型）加 8 格；`canGainSlots` 预检后扣款 `gainSlots` |
| 0x07 | 扩仓库格 | 同上，目标改为账号仓库 `chr.getStorage()`，成功后 `setUsedStorage` |
| 0x08 | 扩角色栏 | 买 SN 道具，`c.canGainCharacterSlot`（上限 12 额外栏）预检，`c.gainCharacterSlot()` |
| 0x0D | 现金仓库→背包 | 按 cashId 取 item，`inventory.addItem` 成功后移出仓库；装备带 RingId 时 `Ring.loadFromDb + addPlayerRing` 佩戴戒指 |
| 0x0E | 背包→现金仓库 | 校验 invType 1~5（非法断线）、非装备中宠物、非婚戒/婚约道具；容量检查后 `cs.addToInventory + mi.removeSlot` |
| 0x1D | 情侣戒（Crush Ring） | 校验生日；对方须同频道在线；`Ring.createRing` 建一对戒指，一枚入自己现金仓库、一枚 `cs.gift` 给对方；`sendWithFame` 纸条；加入 `crushRings` |
| 0x20 | 1 枫币购买区 | SN 前 8 位须为 8（防 1 meso exploit）、在售、价格 >0；扣枫币 `InventoryManipulator.addById`，回 `showBoughtQuestItem` |
| 0x23 | 友情戒（Friendship Ring） | 同 0x1D，加入 `friendshipRings`，付款按 `cs.gainCash(payment, -price)` |
| 0x2E | 改名卡 | SN=50600000 且 `allow_cash_shop_name_change`；新名合法（`canCreateChar`）、等级 ≥10、30 天内无封禁；`registerNameChange` 成功后购买道具入仓 |
| 0x31 | 转世界卡 | SN=50600001 且 `allow_cash_shop_world_transfer`；`checkWorldTransferEligibility`、目标世界合法、非当前世界、目标世界角色栏位够；`registerWorldTransfer` 成功后入仓 |
| 其他 | — | log.warn 未处理 action |

**公有静态方法**：`checkBirthday(Client, idate)`——把 yyyyMMdd 整数转 Calendar 后 `c.checkBirthDate`。私有静态：`ensureCashInventoryCapacity`（容量不足时提示上限并 enableCSActions）、`canBuy(chr, item, cash)`（在售+价格≤余额，购买日志）。

**协作类**：`CashShop`/`CashItemFactory`/`ModifiedCashItemDO`、`InventoryManipulator`、`Ring`、`CharacterService`/`NoteService`（Spring）、`PacketCreator` 全套商城回包。

---

## 19. CashShopSurpriseHandler

**包**：开启现金商城惊喜盒（Cash Shop Surprise）。

**流程**：商城须打开；读 64 位 cashId；`cs.openCashShopSurprise(cashId)` 返回 `Optional<CashShopSurpriseResult>`（消耗盒+奖励道具，概率与替换逻辑在 CashShop 内）；空则回 `onCashItemGachaponOpenFailed`，成功回 `onCashGachaponOpenSuccess`（含消耗数量、奖励道具与数量）。

---

## 20. ChangeChannelHandler

**包**：切换频道。

**流程**：读目标频道（0 基 +1）+4 字节；登记 autoban 时间戳槽 6；目标==当前频道 → `GENERAL.alert` 并断线（异常行为）；处于商城/小游戏/玩家商店/交易中 → enableActions 返回；否则 `c.changeChannel(channel)`（内部走迁移断开+重连流程）。

---

## 21. ChangeMapHandler

**包**：玩家踩光圈（portal）切图/复活回城，是地图切换主入口。

**handlePacket 流程**：

1. 正在切图（`isChangingMaps`）或封号：warn 日志（含最近访问地图列表，私有 `getFormattedMapListLogMessage` 把 mapId 转中文名）+ enableActions 返回。
2. 有交易则 `Trade.cancelTrade(UNSUCCESSFUL_ANOTHER_MAP)`。
3. **`p.available()==0` → 从商城返回**：走 `enterFromCashShop(c)`——商城未开断线；取频道 socket，`cashShop.open(false)`，`setSessionTransitionState` 后回 `getChannelChange` 引导客户端重连游戏频道。
4. 商城仍打开却发普通切图 → 断线（状态非法）。
5. 解析：fromDying byte、`targetMapId`、`portalName`（→ `Portal`）、wheel byte；GM 追逐模式（尾随 2 个 int 坐标）设 `setChasing+setPosition`。
6. `targetMapId != -1` 分支：
   - **已死亡**：持有命运之轮（WHEEL_OF_FORTUNE）且 wheel=1 → 扣道具、HP 恢复 50%、原地最近出生点复活；否则事件脚本 `eventInstance.revivePlayer` 决定是否走默认 `chr.respawn(map.getReturnMapId())` 回城复活。
   - **存活非 GM**：仅放开教学/新手链路白名单（divi==0 → 10000；骑士团 20100 → 锂矿石；913040000 段埃雷夫；9140900 段 Aran 教学；1020x 段勇士部落影片；98004x 段魔女塔），命中才 `changeMap`。
   - GM 直接任意 `getWarpMap(targetMapId)`。
7. 目标 portal 关闭（`getPortalStatus` false）→ `blockedMessage(1)` 返回。
8. 离开健身/跑步运动会终点图时重置 `Fitness/Ola` 次数。
9. 距离校验：玩家与 portal 距离平方 >400000（约 630px）拒绝；通过则 `portal.enterPortal(c)` 执行脚本传送；portal 为 null 回 enableActions。

**协作类**：`Portal.enterPortal`（脚本）、`MapleMap`、`EventInstance.revivePlayer`、`InventoryManipulator`、`Server.getInetSocket`。

---

## 22. ChangeMapSpecialHandler

**包**：脚本型传送门（startwp）触发的特殊切图（如任务脚本门）。

**流程**：读 byte、`startwp` 脚本名、short；取 `Portal`，portal 不存在 / 传送冷却中（`portalDelay`）/ 脚本名在黑名单（`getBlockedPortals`）/ 切图中 / 封号 → enableActions 返回；有交易先取消；`portal.enterPortal(c)`。

---

## 23. CharInfoRequestHandler

**包**：查看其他玩家角色信息面板。

**流程**：跳 4 字节，读 cid；`map.getMapObject(cid)` 命中 `Character` 时：非本人先 `player.exportExcludedItems(c)`（同步宠物/戒指等排除显示项），回 `PacketCreator.charInfo(player)`。

---

## 24. ClickGuideHandler

**包**：点击界面左下角"向导"按钮（新手帮助）。

**流程**：职业为 `NOBLESSE`（骑士团新手）开 NPC `MIMO` 对话，否则开 `LILIN`（`NPCScriptManager.start`）。

---

## 25. CloseChalkboardHandler

**包**：关闭粉笔板（小黑板）留言。

**流程**：`setChalkboard(null)` 清空文本，全图广播 `useChalkboard(player, true)` 通知他人黑板已关闭。

---

## 26. CloseRangeDamageHandler（extends AbstractDealDamageHandler）

**包**：近战攻击（CLOSE_ATTACK）。

**handlePacket 流程**（在基类解析/结算之外的职业前置逻辑）：

1. `parseDamage(p, chr, false, false)`；变身（MORPH）为不可攻击变体时直接断线（客户端不可能发出）。
2. 网字金字塔图 + 法老之怒（skill%10000000==1020，私有 `isRageOfPharaoh`）：须在 `Pyramid` PQ 且 `pyramid.useSkill()` 通过；竹雨（%10000000==1009，`isBambooRain`）道馆能量 <10000 拒绝。
3. 道馆攻击命中加能量（`dojo_energy_atk`）并回 `getEnergy`。
4. 广播 `closeRangeAttack`（含 stance/目标伤害表/speed 等）。
5. **组合与能量**：终结技消耗球（`handleOrbconsume`）；普通命中给十字军/魂骑加 COMBO 球（进阶组合概率 +2，刷新 buff 剩余时长并广播 giveBuff/giveForeignBuff）；Marauder/ThunderBreaker 命中回复能量（`handleEnergyChargeGain`×目标数）。
6. 龙骑士牺牲（SACRIFICE）按伤害 X% 自伤 `safeAddHP`；普通充填（1211002）未触发进阶充填概率时取消 WK_CHARGE。
7. 终结技无球直接 return；竹雨攻击后清空道馆能量并提示。
8. 有 CD 技能：冷却中 return，否则 `skillCooldown` + `addCooldown`（金字塔技能按 1 级取效果）。
9. 攻击打破隐身：暗影神偷/夜行者 DARKSIGHT、风灵 WIND_WALK 攻击后立即取消。
10. `applyAttack(attack, chr, attackCount)`（attackCount=技能攻击段数，无技能为 1）。

---

## 27. CoconutHandler

**包**：椰子小游戏（Coconut Event）击打椰子。

**流程**：读椰子 id；`map.getCoconut()` 取活动与 `Coconuts`（顺序注意：先 get 再判 event==null，存在先解引用后判空的瑕疵）；不可打/冷却中（`getHitTime`）返回；击打数 >2 后 40% 概率掉落：1% 且剩余 stop 次数 >0 时触发"卡住"事件（`stopCoconut`），否则 5% 且有炸弹次数时爆弹（`bombCoconut`），再否则正常掉落（`fallCoconut`）并按队伍加分（`addMapleScore/addStoryScore`）+ 广播比分 `coconutScore`；未达阈值只 `nut.hit()` 广播普通击打效果 `hitCoconut(…,1)`。

---

## 28. CouponCodeHandler

**包**：兑换商城优惠码（卡号）。

**私有静态方法**：

- `getNXCodeItems(chr, con, codeid)`：查 `nxcode_items` 汇总 type<5 的点数（0 枫币/1 点卡/2 预付/3 折算/4 nxCredit）与 type≥5 的道具；非法 itemid 降级为 4000000×1 并 warn；背包装不下（`canHold` 失败）返回 null。
- `getNXCodeResult(chr, code)`：`c.attemptCsCoupon` 节流（否则 -5）；查 `nxcode`：不存在 -1、已被领 -2、过期 -3、背包不够 -4；成功则 UPDATE `retriever=角色名` 占用，返回 `(0, 明细列表)`；末尾 `c.resetCsCoupon()`。
- `parseCouponResult(res)`：错误码 → 客户端提示码 0xB0/0xB3/0xB2/0xBB（默认 0xB1 成功）。

**handlePacket 流程**：跳 2 字节读 code（大写化）；持客户端锁：失败回 `showCashShopMessage(parseCouponResult)`；成功先预检现金仓库容量（`canAddToInventory`），随后逐条发放：type 0 加枫币、1/2/4 加对应点券、3 折算 5000:1 双货币、其余按道具——现金道具经 `CashShop.generateCouponItem` 入现金仓库，普通道具 `InventoryManipulator.addById`；cashItems 超过 255 截断；点券类回 `showBoughtQuestItem(0)`，否则回 `showCouponRedeemedItems`；末尾 `enableCSActions`。

---

## 29. DamageSummonHandler

**包**：召唤物（如 Puppet）被怪物扣血。

**流程**：读召唤 OID、跳 1 字节、damage、怪物 ID；地图对象命中 `Summon` 时 `summon.addHP(-damage)`；HP≤0 则 `cancelEffectFromBuffStat(PUPPET)` 撤销 puppet buff；以召唤位置为中心广播 `damageSummon`（不含本人，由客户端自发）。

---

## 30. DenyAllianceRequestHandler

**包**：拒绝联盟邀请。

**流程**：跳 byte，读邀请人名与公会名；找到在线邀请人且其有联盟时 `Alliance.answerInvitation(自己cid, guildName, allianceId, false)` 登记拒绝（内部会通知盟主）。

---

## 31. DenyGuildRequestHandler

**包**：拒绝公会邀请。

**流程**：跳 byte，读邀请人名；在线则 `Guild.answerInvitation(cid, name, inviterGuildId, false)`。

---

## 32. DenyPartyRequestHandler

**包**：拒绝组队邀请。

**流程**：跳 byte，读"名字 PS: xxx"串按 `"PS: "` 切分取真实名；在线则 `InviteCoordinator.answerInvite(PARTY, ...)`，结果为 `DENIED` 时：恢复自己组队搜索可用性（`updatePartySearchAvailability`）、给邀请人回 `partyStatusMessage(23, 名字)`。

---

## 33. DistributeAPHandler

**包**：手动分配能力点（AP+按钮）。

**流程**：读 int（时间戳，丢弃）、`num`（加点点数），委托 `AssignAPProcessor.APAssignAction(c, num)`。

---

## 34. DistributeSPHandler

**包**：手动分配技能点（SP+按钮）。

**流程**：读 int（丢弃）、`skillid`，委托 `AssignSPProcessor.SPAssignAction(c, skillid)`。

---

## 35. DoorHandler

**包**：进入魔法师传送门（Mystic Door）。

**流程**：读门主人 id 与方向 byte（镇↔目标双向）；切图中/封号 → enableActions；遍历本图 MapObjects 找 `DoorObject` 且 `getOwnerId()==ownerid` → `door.warp(chr)` 传送并 return；找不到回 `blockedMessage(6)` + enableActions。

---

## 36. DueyHandler

**包**：快递员 Duey（道具快递）。`use_duey` 关闭时 enableActions。

**流程**：读 operation byte 分派到 `DueyProcessor`：

- `TOSERVER_RECV_ITEM`：`dueySendTalk(c, false)` 打开包裹列表。
- `TOSERVER_SEND_ITEM`：读背包类型/位置/数量/附言枫币/收件人/是否快递/留言，`dueySendItem(...)`。
- `TOSERVER_REMOVE_PACKAGE`：读 packageid，`dueyRemovePackage(c, id, true)`。
- `TOSERVER_CLAIM_PACKAGE`：读 packageid，`dueyClaimPackage(c, id)`（该 case 出现两次，第二个实际为打开对话的重复分支）。

---

## 37. EnterCashShopHandler

**包**：进入现金商城。

**流程**：

1. `cannotEnterCashShop()`（冷却/状态限制）→ enableActions；注册活动中/迷你地牢内禁止进入。
2. 商城已打开直接 return；防御性修复：三种点券余额为负时清零回正。
3. 频道侧下线准备：关闭交互与组队搜索、注销椅子 buff、buff/异常状态存入 `PlayerBuffStorage`、`setAwayFromChannelWorld`、通知伴侣、清邀请、取消全部 buff/debuff 与各类到期任务。
4. 放弃限时任务（`forfeitExpirableQuests`）。
5. 回包序列：`openCashShop(c,false)` → `showCashInventory` → `showGifts(loadGifts)` → `showWishList` → `showCash`。
6. 从频道与地图移除玩家，`cashShop.open(true)`，`saveCharToDB` 落库。

---

## 38. EnterMTSHandler

**包**：进入 MTS 交易市场（也是 Cash Shop 后端复用）。

**流程**：

1. `use_mts` 关闭 → 私有 `openCenterScript(c)` 打开中转 NPC 脚本；活动中/迷你地牢/`FieldLimit.CANNOTMIGRATE`/死亡/等级 <10 均拒绝。
2. 与 EnterCashShop 相同的频道下线清理序列（buff 存储迁移、取消任务计时、`saveCharToDB`）。
3. `openCashShop(c, true)`、`cashShop.open(true)`、`enableCSActions`。
4. 初始化 MTS 界面数据：`MTSWantedListingOver(0,0)`、`showMTSCash`；JDBC 直查 `mts_items`（第 2 页 LIMIT 16,16 的在售列表，装备逐属性重建 `Equip`，售价加 100+10% 手续费）、`COUNT(*)` 算页数，回 `sendMTS(items,1,0,0,pages)`。
5. 私有 `getNotYetSold(cid)`（自己在售未售出）与 `getTransfer(cid)`（transfer=1 待提取），分别回 `notYetSoldInv/transferInventory`。

---

## 39. FaceExpressionHandler

**包**：改变默认脸型表情（7 号以上为道具表情）。

**流程**：读 emote；emote>7 时映射道具 `5159992+emote`，须 `ItemId.isFaceExpression` 且背包持有，否则丢弃；emote<1 丢弃；持客户端锁且 `isLoggedInWorld()` 时 `chr.changeFaceExpression(emote)`。

---

## 40. FamilyAddHandler

**包**：发起家族（学院）组队邀请。`use_family_system` 关闭返回。

**流程**：读目标名；依次校验并回对应 `sendFamilyMessage` 码：不在线 65 / 自己 66(仅 enableActions) / 不在同图或对方是更高 GM 且隐身 69 / 等级 ≤10 77 / 等级差 >20 72 / 同一家族 enableActions / 对方已有待处理邀请 73 / 两家族代数和超 `family_max_generations` 76；通过则 `InviteCoordinator.createInvite(FAMILY,...)` + 给对方发 `sendFamilyInvite` + 提示已发送。

---

## 41. FamilyPreceptsHandler

**包**：修改家族家训。

**流程**：无家族返回；仅族长（`family.getLeader().getChr()==player`）可改；读家训，>200 字返回；`family.setMessage(newPrecepts, true)` 落库；回 `getFamilyInfo` 刷新面板。

---

## 42. FamilySeparateHandler

**包**：家族脱离/分家（ junior 脱离 senior，或 senior 开除 junior）。`use_family_system` 关闭返回。

**流程**：

1. 无家族返回；包内有 id（0x95 之外的子操作）→ 读 entryId 得 `forkOn`，须是自己的 junior（`isJunior`），isSenior=true；无 id → forkOn=自己（脱离上级），isSenior=false。
2. 上级 `senior==null` 返回。
3. **费用**：枫币 `2500*等级差 + 等级差²`，不足回 message(80/81, cost)；扣款。
4. **声望罚**：私有 `separateRepCost(junior)` = `((level/20)+10)*level*2`；senior 扣全额、senior 的上级再扣一半。
5. 通知上级、`forkOn.fork()` 执行分离，回 `getFamilyInfo` + `updateSeniorFamilyInfo(true)` + message(1)。

---

## 43. FamilySummonResponseHandler

**包**：对"家族召唤"邀请的应答。`use_family_system` 关闭返回。

**流程**：读（丢弃）家族名与 accept；`InviteCoordinator.answerInvite(FAMILY_SUMMON,...)`，NOT_FOUND 返回；接受且召唤人仍在原地图（params[0]）→ `changeMap(map, portal 0)` 传送过去；拒绝或召唤人已换图 → `refundEntitlement(SUMMON_FAMILY)` 退还次数并全额返还声望，给召唤人发 familyInfo 与拒绝提示。

---

## 44. FamilyUseHandler

**包**：消耗声望使用家族特权（FamilyEntitlement）。`use_family_system` 关闭返回。

**handlePacket 流程**：

1. 读特权序号（`FamilyEntitlement.values()[int]`）与声望消耗；声望不足或当日已用 → 直接 return（客户端本应拦截）。
2. **传送类**（FAMILY_REUINION / SUMMON_FAMILY）读目标名；目标在线、非本人、同家族才有效：
   - REUINION（我传过去）：目标图非 CANNOTVIPROCK、自己图非 CANNOTMIGRATE、非强制回城图（枫叶岛除外）、无活动 → `changeMap`；否则 message(75)。
   - SUMMON_FAMILY（拉对方来）：镜像的 FieldLimit 检查；对方已有待答邀请 → message(74)；否则 `createInvite(FAMILY_SUMMON, ..., 自己当前地图)` + 给对方 `sendFamilySummonRequest`。
3. **Buff 类**：已有家族 buff 时提示"你已经有BUFF"并返回，否则按枚举分派：
   - `PARTY_EXP_2_30MIN` / `PARTY_DROP_2_30MIN` → `applyPartyBuff`：有队伍时仅扣使用者一次次数，对同家族（"同学"）且在线的队友逐个 `familyBuff` 包 + `setFamilyBuff` + `startFamilyBuffTimer`；不同家族/无家族队友不享受且不扣次数。
   - `SELF_*`（EXP/DROP ×1.5/×2，15/30 分钟）→ `applySelfBuff`：扣次数后给自己 buff 与计时器。
   - `FAMILY_BONDING` → 全家族 30 分钟组队 buff（`family.Familybuff(30)`）。
4. 私有 `useEntitlement(entry, entitlement)`：`entry.useEntitlement` 成功则扣声望、回 familyInfo；注释说明仅 buff 类限一日一次，传送类自 2019 年起不限。

---

## 45. FieldDamageMobHandler

**包**：利用地图机关/障碍物伤害怪物（如雪原推冰块）。

**流程**：读怪物 OID 与伤害值；本图无激活的环境对象（`map.getEnvironment().isEmpty()`）→ warn 并拒绝（防外挂凭空机关攻击）；伤害 <0 或 > `GameConstants.MAX_FIELD_MOB_DAMAGE` → warn 拒绝；命中怪物则广播 `damageMonster` 并 `map.damageMonster(chr, mob, dmg)` 结算（含掉落/经验）。

---

## 46. FredrickHandler

**包**：雇佣商人仓库管理员 Fredrick（领取商店收益）。**构造器注入 `FredrickProcessor`**（Spring）。

**流程**：读 operation：0x19（历史遗留，空）；0x1A → `fredrickProcessor.fredrickRetrieveItems(c)` 取回寄卖剩余道具与枫币；0x1C 退出（空）；default 忽略。

---

## 47. GeneralChatHandler

**包**：普通聊天（含玩家命令入口）。

**流程**：

1. 读消息；200ms 内重复发言（spam 槽 7）→ enableActions 丢弃。
2. 非 GM 且长度 >127 → `PACKET_EDIT.alert` + warn + `c.disconnect(true,false)`。
3. 首字符命令：`CommandsExecutor.isCommand` → `handle(c, s)` 执行玩家命令并结束。
4. 非 `/` 开头才广播：读 `show`（气泡显示方式）；地图被禁言且非 GM → 提示返回；隐身 GM 走 `broadcastGMMessage`（仅 GM 可见）并记 "GM General" 日志，普通玩家 `broadcastMessage(getChatText(...))` + `ChatLogger.log("General")`；最后 `spam(7)` 登记。

---

## 48. GiveFameHandler

**包**：送人气（人气 +/-）。

**流程**：读目标 cid（地图对象）与 mode（0/1 → `famechange = 2*mode-1 = ±1`）；目标无效/是自己/自己等级 <15 → 静默返回；famechange 非 ±1 → `PACKET_EDIT.alert` + 断线；24 小时内已送过 → status=3，本月已送过同一目标 → status=4，回 `giveFameErrorResponse(status)`；合法时 `target.gainFame(±1, player, mode)`（内部广播人气变化），成功且非 GM 则 `hasGivenFame(target)` 登记时间与月度名单，失败（目标刚下线等）提示消息。

---

## 49. GrenadeEffectHandler

**包**：投掷类技能的手雷抛物线特效（手雷/毒弹）。

**流程**：读落点 x/y、按键时长 keyDown、skillId；仅 `NightWalker.POISON_BOMB` 与 `Gunslinger.GRENADE` 支持：技能等级 >0 时以落点为参照广播 `throwGrenade`（伤害结算在后续攻击包）；其他技能 id 记 warn 日志。

---

## 50. GuildOperationHandler

**包**：公会操作总入口（建会/邀请/加入/退出/踢人/改衔/徽章/公告/建会确认）。

**私有方法**：`isGuildNameAcceptable(name)`——长度 3~12 且只含中文/字母/数字/下划线（正则 `[^\u4e00-\u9fa5a-zA-Z0-9_]`）。

**type 分派**：

| type | 功能 | 流程要点 |
| --- | --- | --- |
| 0x00 | 显示公会信息 | 空实现（由登录/其他包触发） |
| 0x02 | 创建公会 | 已有会提示；枫币 < `create_guild_cost` 提示；名字合法性检查；`Guild.getEligiblePlayersForGuild`（同图同队等条件）人数 < `create_guild_min_partners` 时按地图人数给出两种提示；先 `Party.createParty(mc, true)` 建队；最后 `MatchCheckerCoordinator.createMatchConfirmation(GUILD_CREATION, ...)` 发起全体成员确认流程 |
| 0x05 | 邀请入会 | guildRank ≤2 才可；`Guild.sendInvitation(c, name)` 返回 `GuildResponse` 时回执给邀请人 |
| 0x06 | 接受邀请 | 已有会 / cid 非本人 → warn hack；`Guild.answerInvitation(true)`；设 MGC guildId/等级 5/联盟等级 5；`Server.addGuildMember`（失败回退）；回 `showGuildInfo`；有联盟则 `updateAlliancePackets`；`saveGuildStatus` 落库；地图广播 `guildNameChanged/guildMarkChanged` |
| 0x07 | 退会 | cid+名字三重校验；回 `updateGP(0)`；`Server.leaveGuild`；回 `showGuildInfo(null)`；联盟更新；MGC 重置并广播空名 |
| 0x08 | 踢人 | rank ≤2；`Server.expelMember(mgc, name, cid)` + 联盟更新 |
| 0x0d | 改等级头衔 | 仅会长；读 5 个头衔 `Server.changeRankTitle` |
| 0x0e | 改成员等级 | rank ≤2 且（newRank≤2 时须会长）；newRank 夹 (1,5]；`Server.changeRank` |
| 0x0f | 改徽章 | 仅会长且在公会 HQ 图；枫币 ≥ `change_emblem_cost`；读 bg/bgcolor/logo/logocolor → `Server.setGuildEmblem`；有联盟广播 `getGuildAlliances`；扣枫币；`broadcastNameChanged/broadcastEmblemChanged` |
| 0x10 | 改公告 | rank ≤2；长度 ≤100；`Server.setGuildNotice` |
| 0x1E | 建会确认应答 | 已在队 → `dismissMatchConfirmation` 撤销；否则取确认流程 leaderid，读 result：同意且流程活跃时先 `Party.joinParty`（GMS "组队建会"机制），再 `answerMatchConfirmation` |
| default | — | warn 未知 GUILD_OPERATION 包 |

**协作类**：`Guild`/`Alliance`/`GuildPackets`/`GuildResponse`、`Server.getInstance()`、`Party`、`MatchCheckerCoordinator`（建会多端确认）、`GameConfig`（费用/最少人数）、`I18nUtil`。

---

## 附录：本篇覆盖的 50 个 Handler 清单

| # | 类名 | # | 类名 |
| --- | --- | --- | --- |
| 1 | AbstractDealDamageHandler（抽象） | 26 | CloseRangeDamageHandler |
| 2 | AbstractMovementPacketHandler（抽象） | 27 | CoconutHandler |
| 3 | AcceptFamilyHandler | 28 | CouponCodeHandler |
| 4 | AdminChatHandler | 29 | DamageSummonHandler |
| 5 | AdminCommandHandler | 30 | DenyAllianceRequestHandler |
| 6 | AdminLogHandler | 31 | DenyGuildRequestHandler |
| 7 | AllianceOperationHandler | 32 | DenyPartyRequestHandler |
| 8 | AranComboHandler | 33 | DistributeAPHandler |
| 9 | AutoAggroHandler | 34 | DistributeSPHandler |
| 10 | AutoAssignHandler | 35 | DoorHandler |
| 11 | BBSOperationHandler | 36 | DueyHandler |
| 12 | BeholderHandler | 37 | EnterCashShopHandler |
| 13 | BuddylistModifyHandler | 38 | EnterMTSHandler |
| 14 | CancelBuffHandler | 39 | FaceExpressionHandler |
| 15 | CancelChairHandler | 40 | FamilyAddHandler |
| 16 | CancelDebuffHandler | 41 | FamilyPreceptsHandler |
| 17 | CancelItemEffectHandler | 42 | FamilySeparateHandler |
| 18 | CashOperationHandler | 43 | FamilySummonResponseHandler |
| 19 | CashShopSurpriseHandler | 44 | FamilyUseHandler |
| 20 | ChangeChannelHandler | 45 | FieldDamageMobHandler |
| 21 | ChangeMapHandler | 46 | FredrickHandler |
| 22 | ChangeMapSpecialHandler | 47 | GeneralChatHandler |
| 23 | CharInfoRequestHandler | 48 | GiveFameHandler |
| 24 | ClickGuideHandler | 49 | GrenadeEffectHandler |
| 25 | CloseChalkboardHandler | 50 | GuildOperationHandler |
