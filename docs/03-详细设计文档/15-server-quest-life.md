# 15-server-quest 与 server-life 详细设计

- 模块路径：
  - `gms-server/src/main/java/org/gms/server/quest/`（含子包 `actions/`、`requirements/`、`medal/`）
  - `gms-server/src/main/java/org/gms/server/life/`（含子包 `positioner/`）
- 类数量：66（quest 41 = 根包 3 + actions 13 + requirements 21 + medal 4；life 25 = 根包 23 + positioner 2）
- 依赖模块：
  - WZ 数据：`org.gms.provider.*`（`DataProviderFactory`/`DataTool`）、`WZFiles.QUEST`/`MOB`/`SKILL`/`STRING`/`NPC`/`UI`
  - 客户端模型：`org.gms.client.*`（`Character`、`QuestStatus`、`Job`、`Skill`/`SkillFactory`、`Disease`、`BuffStat`、`Pet`、`status.MonsterStatus(MonsterStatusEffect)`、`inventory.*`）
  - 地图/对象：`org.gms.server.maps.*`（`AbstractAnimatedMapObject`、`MapleMap`、`Mist`、`Summon`、`MapObjectType`）
  - 频道服务：`org.gms.net.server.services.task.channel.*`（`OverallService`、`MobStatusService`、`MobAnimationService`、`MobClearSkillService`）、`MonsterAggroCoordinator`
  - 数据库：`DatabaseConnection`（drop_data、playernpcs 等表）、MyBatis-Flex DO/Mapper（`PlayernpcsDO`、`NpcService`）
  - 配置：`GameConfig`（`quest_point_repeatable_interval`、`tot_mob_quest_requirement`、`use_quest_rate`、`exp_split_*`、`playernpc_*` 等）
  - 工具：`PacketCreator`、`Randomizer`、`NumberTool`、`StringUtil`、`I18nUtil`、`TimerManager`、`LootManager`、`ItemInformationProvider`
  - 掉落/战利品：`org.gms.server.loot.LootManager`
- 说明：`quest` 包实现任务状态机（WZ 驱动的条件检查 + 奖励发放，策略模式）；`life` 包实现地图生命体（怪物 `Monster`/NPC/玩家 NPC）及其属性工厂、怪物技能、掉落提供者、重生点与玩家 NPC 自动摆位。

---

# 第一部分 org.gms.server.quest（41 类）

## Quest

任务对象核心类：每个任务 ID 对应一个 `Quest` 实例（进程内缓存），从 WZ `QuestInfo.img`/`Check.img`/`Act.img` 解析出元信息、启动/完成两阶段的需求（`AbstractQuestRequirement`）与动作（`AbstractQuestAction`），并提供任务状态机操作（开始/完成/放弃/重置/过期）（源码路径：`src/main/java/org/gms/server/quest/Quest.java`）。

**关键字段**

| 字段 | 说明 |
| --- | --- |
| `static Map<Integer, Quest> quests` | 全部任务缓存（volatile，`loadAllQuests` 整体替换） |
| `static Map<Integer, Integer> infoNumberQuests` | infoNumber → 真实任务 ID 的反查表（volatile） |
| `static Map<Short, Integer> medals` | 任务 ID → 勋章道具 ID（`viewMedalItem`） |
| `static Set<Short> exploitableQuests` | 已知可刷任务黑名单（2338/3637/3714/21752） |
| `id` / `timeLimit` / `timeLimit2` | 任务 ID、两种限时（秒） |
| `startReqs` / `completeReqs` | `EnumMap<QuestRequirementType, AbstractQuestRequirement>`，接取/完成条件 |
| `startActs` / `completeActs` | `EnumMap<QuestActionType, AbstractQuestAction>`，接取/完成奖励 |
| `relevantMobs` | 与任务相关的怪物 ID 列表（由 MOB 条件解析时收集） |
| `autoStart` / `autoPreComplete` / `autoComplete` / `repeatable` | 自动接取/自动（预）完成标记；`repeatable` 由存在 INTERVAL 条件推导 |

**方法表**

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `private Quest(int id)` | 构造并解析 WZ | ① `Check.img/<id>` 不存在直接返回（infoEx 型任务）；② 从 `QuestInfo.img` 读 name/parent/timeLimit(2)/autoStart/autoPreComplete/autoComplete/viewMedalItem（有勋章则登记 medals）；③ 解析 `Check.img` 节点 "0"（接取条件）与 "1"（完成条件），逐子节点按 WZ 名映射 `QuestRequirementType` 后经 `getRequirement` 构造（INTERVAL 置 `repeatable=true`，MOB 时把子节点怪物 ID 收进 `relevantMobs`）；④ 解析 `Act.img` 节点 "0"/"1" 为启动/完成动作 |
| `public boolean isAutoComplete()` | 是否可自动完成 | `autoPreComplete \|\| autoComplete` |
| `public boolean isAutoStart()` | 是否自动接取 | 返回 `autoStart` |
| `public static Quest getInstance(int id)` | 取/建任务实例 | `quests` 缓存 miss 时 `new Quest(id)` 放入（无锁，竞态下最多重复解析一次） |
| `public static Quest getInstanceFromInfoNumber(int infoNumber)` | 由 infoNumber 反查任务 | `infoNumberQuests` 命中则取其真实 ID，否则直接以 infoNumber 当任务 ID |
| `public boolean isSameDayRepeatable()` | 是否当日可重复 | 非可重复返回 false；否则取 startReqs 的 `IntervalRequirement`，判断 interval < `GameConfig quest_point_repeatable_interval`（小时→毫秒） |
| `public boolean canStartQuestByStatus(Character chr)` | 按当前状态判断能否接取 | 未开始，或（已完成且 repeatable）才可接 |
| `public boolean canQuestByInfoProgress(Character chr)` | 校验 infoEx 进度 | 遍历当前 `QuestStatus.getInfoEx()`，逐项与 `getAbstractPlayerInteraction().getQuestProgress(infoNumber, i)` 比对（infoNumber 缺省取任务 ID），全部一致才通过 |
| `public boolean canStart(Character chr, int npcid)` | 能否接取 | 状态检查 → 遍历 startReqs 全部 `check` → `canQuestByInfoProgress` |
| `public boolean canComplete(Character chr, Integer npcid)` | 能否完成 | 必须 STARTED → 遍历 completeReqs 全部 `check` → `canQuestByInfoProgress` |
| `public void start(Character chr, int npc)` | 接取任务 | autoStart 或 `canStart` 通过时：先对全部 startActs 逐一 `check`（任一失败即中止），再全部 `run`，最后 `forceStart` |
| `public void complete(Character chr, int npc)` | 完成任务（无选项） | 委托三参重载，selection=null |
| `public void complete(Character chr, int npc, Integer selection)` | 完成任务 | autoPreComplete 或 `canComplete` 通过时：completeActs 全部 `check` → `forceComplete` → 逐个 `run(chr, selection)`；若无 NEXTQUEST 动作则向客户端 `announceUpdateQuest(INFO, ...)` 刷新任务面板 |
| `public void reset(Character chr)` | 重置任务状态 | 新建 NOT_STARTED 的 `QuestStatus` 并 `updateQuestStatus` |
| `public boolean forfeit(Character chr)` | 放弃任务 | 非 STARTED 返回 false；有 timeLimit 时发包移除倒计时；新建 NOT_STARTED 状态并继承 forfeited+1 |
| `public boolean forceStart(Character chr, int npc)` | 强制置为进行中 | ① 新 STARTED 状态并拷贝旧进度；② BeiDou 定制：TOT 任务（id/100==35）且配置 `tot_mob_quest_requirement>0` 时，把进度槽 8200000~8200012 预置为 `999-requirement`（补零 3 位）；③ 继承 forfeited/completed；④ timeLimit/timeLimit2>0 时设置到期时间并 `questTimeLimit(2)` 开启倒计时；⑤ `updateQuestStatus` |
| `public boolean forceComplete(Character chr, int npc)` | 强制完成 | 有 timeLimit 先移除倒计时；新 COMPLETED 状态继承放弃/完成记录、记录完成时间；发包 `showSpecialEffect(9)` 并向全图广播任务完成特效 |
| `public short getId()` | 取任务 ID | 返回 `id`（short） |
| `public List<Integer> getRelevantMobs()` | 取相关怪物列表 | 返回 `relevantMobs` |
| `public int getStartItemAmountNeeded(int itemid)` | 接取所需物品数 | startReqs 无 ITEM 条件返回 `Integer.MIN_VALUE`；否则委托 `ItemRequirement.getItemAmountNeeded(itemid, false)` |
| `public int getCompleteItemAmountNeeded(int itemid)` | 完成所需物品数 | 同上但查 completeReqs，缺省 `Integer.MAX_VALUE` |
| `public int getMobAmountNeeded(int mid)` | 完成所需击杀数 | completeReqs 无 MOB 条件返回 0；否则 `MobRequirement.getRequiredMobCount` |
| `public short getInfoNumber(Status qs)` | 取 infoNumber | 非进行中查 startReqs、进行中查 completeReqs 的 INFO_NUMBER 条件；无则 0 |
| `public String getInfoEx(Status qs, int index)` / `public List<String> getInfoEx(Status qs)` | 取 infoEx 期望值 | 按状态选 start/completeReqs 的 `InfoExRequirement.getInfo()`；异常返回空串/空列表 |
| `public int getTimeLimit()` | 取限时 | 返回 `timeLimit` |
| `public static void clearCache(int quest)` / `public static void clearCache()` | 清缓存 | 移除单个/全部任务缓存 |
| `private AbstractQuestRequirement getRequirement(QuestRequirementType type, Data data)` | 条件工厂 | 按 type switch 到 21 个 Requirement 实现类；NORMAL_AUTO_START/START/END 与未定义类型返回 null（跳过） |
| `private AbstractQuestAction getAction(QuestActionType type, Data data)` | 动作工厂 | 按 type switch 到 13 个 Action 实现类；未定义返回 null |
| `public boolean restoreLostItem(Character chr, int itemid)` | 找回任务物品 | 仅 STARTED 状态：取 startActs 的 `ItemAction.restoreLostItem` |
| `public int getMedalRequirement()` | 取关联勋章 ID | `medals.get(id)`，无则 -1 |
| `public int getNpcRequirement(boolean checkEnd)` | 取指定 NPC 条件 | 查对应阶段 NPC 条件的 `NpcRequirement.get()`，无则 -1 |
| `public boolean hasScriptRequirement(boolean checkEnd)` | 是否脚本任务 | 查对应阶段 SCRIPT 条件的 `ScriptRequirement.get()` |
| `public boolean hasNextQuestAction()` | 是否有后续任务动作 | completeActs 含 NEXTQUEST |
| `public String getName()` / `public String getParentName()` | 取任务名/父分类名 | 直接返回字段 |
| `public static boolean isExploitableQuest(short questid)` | 是否黑名单任务 | `exploitableQuests.contains` |
| `public static List<Quest> getMatchedQuests(String search)` | 按名称模糊搜索 | 遍历缓存，name 或 parent 包含（忽略大小写）即命中 |
| `public static void loadAllQuests()` | 启动时全量加载 | 遍历 `QuestInfo.img` 全部子节点构建 Quest；同时把 STARTED/COMPLETED 两阶段的 infoNumber>0 登记进 `infoNumberQuests`，最后整体替换两个 volatile 缓存 |
| `public void expireQuest(Character chr)` | 任务到期处理 | `forfeit` 成功后发包 `questExpire` 通知客户端 |

---

## QuestActionType

任务动作类型枚举，WZ `Act.img` 节点名 → 动作枚举的映射（源码路径：`src/main/java/org/gms/server/quest/QuestActionType.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static QuestActionType getByWZName(String name)` | WZ 名转枚举 | `exp→EXP`、`money→MESO`、`item→ITEM`、`skill→SKILL`、`nextQuest→NEXTQUEST`、`pop→FAME`、`buffItemID→BUFF`、`petskill→PETSKILL`、`no→NO`、`yes→YES`、`npc→NPC`、`lvmin→MIN_LEVEL`、`normalAutoStart→NORMAL_AUTO_START`、`pettameness→PETTAMENESS`、`petspeed→PETSPEED`、`info→INFO`、`"0"→ZERO`，其余 UNDEFINED |

枚举常量：`UNDEFINED(-1)`、`EXP(0)`、`ITEM(1)`、`NEXTQUEST(2)`、`MESO(3)`、`QUEST(4)`、`SKILL(5)`、`FAME(6)`、`BUFF(7)`、`PETSKILL(8)`、`YES(9)`、`NO(10)`、`NPC(11)`、`MIN_LEVEL(12)`、`NORMAL_AUTO_START(13)`、`PETTAMENESS(14)`、`PETSPEED(15)`、`INFO(16)`、`ZERO(16)`（byte 型编号）。

## QuestRequirementType

任务条件类型枚举，WZ `Check.img` 节点名 → 条件枚举的映射（源码路径：`src/main/java/org/gms/server/quest/QuestRequirementType.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public byte getType()` | 取编号 | 返回 byte 型枚举编号 |
| `public static QuestRequirementType getByWZName(String name)` | WZ 名转枚举 | `job→JOB`、`quest→QUEST`、`item→ITEM`、`lvmin→MIN_LEVEL`、`lvmax→MAX_LEVEL`、`end→END_DATE`、`mob→MOB`、`npc→NPC`、`fieldEnter→FIELD_ENTER`、`interval→INTERVAL`、`startscript`/`endscript→SCRIPT`（同名映射）、`pet→PET`、`pettamenessmin→MIN_PET_TAMENESS`、`mbmin→MONSTER_BOOK`、`normalAutoStart→NORMAL_AUTO_START`、`infoNumber→INFO_NUMBER`、`infoex→INFO_EX`、`questComplete→COMPLETED_QUEST`、`start→START`、`daybyday→DAY_BY_DAY`、`money→MESO`、`buff→BUFF`、`exceptbuff→EXCEPT_BUFF`，其余 UNDEFINED |

枚举常量共 24 个（`UNDEFINED(-1)` ~ `EXCEPT_BUFF(23)`）。

---

## actions.AbstractQuestAction

任务动作抽象基类：定义 `processData`（解析 WZ）/`run`（执行）/`check`（执行前校验）契约，并承载 WZ 中位编码的职业解码工具（源码路径：`src/main/java/org/gms/server/quest/actions/AbstractQuestAction.java`）。

| 字段/方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `protected int questID` | 所属任务 ID | 构造时从 `Quest.getId()` 记录 |
| `public abstract void run(Character chr, Integer extSelection)` | 执行动作 | 子类实现 |
| `public abstract void processData(Data data)` | 解析 WZ 数据 | 子类实现（构造器内调用） |
| `public boolean check(Character chr, Integer extSelection)` | 执行前置校验 | 默认恒 true，子类可覆写（如 ItemAction/PetSkillAction） |
| `public QuestActionType getType()` | 取动作类型 | 返回构造传入的枚举 |
| `public static List<Integer> getJobBy5ByteEncoding(int encoded)` | 解 WZ 5 字节职业位编码 | 逐位测试 bit：0x1→0、0x2→100、0x4→200、0x8→300、0x10→400、0x20→500、0x400→1000、0x800→1100、0x1000→1200、0x2000→1300、0x4000→1400、0x8000→1500、0x20000→{2001,2200}、0x100000→{2000,2001}、0x200000→2100、0x400000→{2001,2200}、0x40000000→{3000,3200,3300,3500} |
| `public static List<Integer> getJobBySimpleEncoding(int encoded)` | 解简单职业位编码 | 0x1→200、0x2→300、0x4→400、0x8→500 |

## actions.BuffAction

任务 Buff 奖励：完成/接取时给角色施加指定道具的Buff效果（源码路径：`src/main/java/org/gms/server/quest/actions/BuffAction.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public BuffAction(Quest quest, Data data)` | 构造 | `super(BUFF, quest)` 后 `processData` |
| `public boolean check(Character chr, Integer extSelection)` | 前置校验 | 恒 true |
| `public void processData(Data data)` | 解析 | `itemEffect = DataTool.getInt(data)` |
| `public void run(Character chr, Integer extSelection)` | 执行 | `ItemInformationProvider.getInstance().getItemEffect(itemEffect).applyTo(chr)` |

## actions.ExpAction

任务经验奖励（源码路径：`src/main/java/org/gms/server/quest/actions/ExpAction.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析 | `exp = DataTool.getInt(data)` |
| `public void run(Character chr, Integer extSelection)` | 执行 | 委托静态 `runAction(chr, exp)` |
| `public static void runAction(Character chr, int gain)` | 给经验（供脚本复用） | 配置 `use_quest_rate` 关闭时按 `chr.getExpRate()`、开启时按 `chr.getQuestExpRate()` 加成，`NumberTool.floatToInt` 取整后 `chr.gainExp(..., true, true)` |

## actions.FameAction

任务人气奖励（源码路径：`src/main/java/org/gms/server/quest/actions/FameAction.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析 | `fame = DataTool.getInt(data)` |
| `public void run(Character chr, Integer extSelection)` | 执行 | `chr.gainFame(fame)` |

## actions.InfoAction

任务进度写入动作：把 WZ info 字符串写入任务进度（HeavenMS 扩展）（源码路径：`src/main/java/org/gms/server/quest/actions/InfoAction.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析 | `info = DataTool.getString(data, "")` |
| `public void run(Character chr, Integer extSelection)` | 执行 | `chr.getAbstractPlayerInteraction().setQuestProgress(questID, info)` |

## actions.ItemAction

任务物品奖励（最复杂的动作）：支持按性别/职业过滤、prop 权重随机、`prop=-1` 玩家选择（extSelection）、正负数量（负数收走物品）、period 限时物品，以及背包空间预检（源码路径：`src/main/java/org/gms/server/quest/actions/ItemAction.java`）。

**关键字段**：`List<ItemData> items`（内部类 `ItemData`：map 排序键/id/count/prop/job/gender/period）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析物品表 | 遍历子节点读 id/count（默认1）/period（默认0）/prop（可 null）/gender（默认2=通用）/job（默认-1）；按节点序号 map 升序排序 |
| `public void run(Character chr, Integer extSelection)` | 发放/收回物品 | ① 累计所有可获取（`canGetItem`）且 prop 非 -1 物品的 prop 总和 props，随机 `rndProps`；② 遍历物品：prop=-1 时仅选中 `extSelection` 对应序号；prop>0 时累计 accProps，超过 rndProps 才入选（一次只中一个随机项）；③ count<0 入 takeItem（收走），否则入 giveItem（发放）；④ 先收后给：收走时 EQUIP 背包不足可回退到已装备栏（EQUIPPED）；发放时 period>0 换算过期时间戳；两者均发包 `getShowItemGain` |
| `public boolean check(Character chr, Integer extSelection)` | 背包预检 | 分固定获得/随机池/选择池三组：负数量物品校验持有量（`freeSlotCountById`，装备可回退已装备栏）；随机池逐项 `InventoryManipulator.checkSpaceProgressively`（结果偶数=放不下）；选择池取 `selectList.get(extSelection)`；最后 `canHold`（`canHoldAllAfterRemoving` 同时考虑将收走物品腾出的空间）统一校验，失败时 `announceInventoryLimit` |
| `private void announceInventoryLimit(List<Integer> itemids, Character chr)` | 提示背包不足 | 若涉及唯一道具且已持有则提示 one-of-a-kind；否则 `I18nUtil.getMessage("ItemAction.Message1")` |
| `private boolean canHold(Character chr, List<Pair<Item, InventoryType>> gainList)` | 计算容量 | 拆分为待增/待减两组后 `canHoldAllAfterRemoving` |
| `private boolean canGetItem(ItemData item, Character chr)` | 性别/职业过滤 | gender≠2 且不匹配返回 false；job>0 时用 `getJobBy5ByteEncoding` 解码后按 `job/100` 分支比对角色职业 |
| `public boolean restoreLostItem(Character chr, int itemid)` | 找回丢失任务物品 | 仅限任务道具（`isQuestItem`）：找到对应 ItemData 后按 `count - chr.countItem` 补差额，放得下才补 |

## actions.MesoAction

任务金币奖励（源码路径：`src/main/java/org/gms/server/quest/actions/MesoAction.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析 | `mesos = DataTool.getInt(data)` |
| `public void run(Character chr, Integer extSelection)` | 执行 | 委托静态 `runAction` |
| `public static void runAction(Character chr, int gain)` | 给金币（供脚本复用） | 负数直接 `gainMeso`（扣款不吃倍率）；正数按 `use_quest_rate` 选 mesoRate 或 questMesoRate 加成 |

## actions.NextQuestAction

"下一任务"提示动作：完成后引导客户端亮起后续任务 NPC 头标（源码路径：`src/main/java/org/gms/server/quest/actions/NextQuestAction.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析 | `nextQuest = DataTool.getInt(data)` |
| `public void run(Character chr, Integer extSelection)` | 执行 | 取当前任务状态后发包 `updateQuestFinish(questID, npc, nextQuest)` |

## actions.PetSkillAction

宠物技能旗标奖励（源码路径：`src/main/java/org/gms/server/quest/actions/PetSkillAction.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析 | `flag = DataTool.getInt("petskill", data)` |
| `public boolean check(Character chr, Integer extSelection)` | 前置校验 | 任务状态须为 NOT_STARTED 且 forfeited>0（即放弃后重做），且 0 号宠物存在 |
| `public void run(Character chr, Integer extSelection)` | 执行 | `chr.getPet(0).setFlag(ItemConstants.getFlagByInt(flag))` |

## actions.PetSpeedAction

宠物"主人加速"属性奖励（HeavenMS 扩展；注意构造时注册的类型是 `PETTAMENESS`，`getType()` 返回值与 WZ 名 `petspeed` 对应的 `PETSPEED` 不一致，属遗留实现细节）（源码路径：`src/main/java/org/gms/server/quest/actions/PetSpeedAction.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析 | 空实现（无 WZ 字段） |
| `public void run(Character chr, Integer extSelection)` | 执行 | 取 0 号宠物（无则返回），`c.lockClient()` 下 `pet.addPetAttribute(player, PetAttribute.OWNER_SPEED)` |

## actions.PetTamenessAction

宠物亲密度奖励（源码路径：`src/main/java/org/gms/server/quest/actions/PetTamenessAction.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析 | `tameness = DataTool.getInt(data)` |
| `public void run(Character chr, Integer extSelection)` | 执行 | 0 号宠物存在时，持客户端锁调用 `pet.gainTamenessFullness(chr, tameness, 0, 0)` |

## actions.QuestAction

批量改写其它任务状态的动作（源码路径：`src/main/java/org/gms/server/quest/actions/QuestAction.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析 | 每个子节点读 `id`+`state` 存入 `Map<Integer, Integer> quests` |
| `public void run(Character chr, Integer extSelection)` | 执行 | 对每个条目 `chr.updateQuestStatus(new QuestStatus(Quest.getInstance(id), Status.getById(stat)))` |

## actions.SkillAction

任务技能奖励：给符合职业（或新手技能）的角色直接设置技能等级（源码路径：`src/main/java/org/gms/server/quest/actions/SkillAction.java`）。

**关键字段**：`Map<Integer, SkillData> skillData`（内部类含 id/level/masterLevel/jobs 列表，`jobsContains(Job)`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析 | 每个技能节点读 id、skillLevel（可缺省 0）、masterLevel、job 子节点列表 |
| `public void run(Character chr, Integer extSelection)` | 执行 | 对每个技能：`SkillFactory.getSkill` 取不到跳过；`shouldLearn = jobsContains(chr.getJob()) \|\| isBeginnerSkill()`；等级取 `max(任务值, 当前值)`、masterLevel 同理；满足则 `chr.changeSkillLevel(skill, level, masterLevel, -1)` |

---

## requirements.AbstractQuestRequirement

任务条件抽象基类（源码路径：`src/main/java/org/gms/server/quest/requirements/AbstractQuestRequirement.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public abstract boolean check(Character chr, Integer npcid)` | 校验玩家是否满足条件 | 子类实现 |
| `public abstract void processData(Data data)` | 解析 WZ 数据 | 子类实现（构造器内调用） |
| `public QuestRequirementType getType()` | 取条件类型 | 返回构造传入枚举 |

## requirements.BuffExceptRequirement

"不得持有某 Buff"条件（HeavenMS 扩展；注意其构造器误注册为 `QuestRequirementType.BUFF`，未用 `EXCEPT_BUFF`，属遗留实现细节）（源码路径：`src/main/java/org/gms/server/quest/requirements/BuffExceptRequirement.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析 | WZ 值为负数道具 buff ID：`buffId = -1 * parseInt(getString(data))` |
| `public boolean check(Character chr, Integer npcid)` | 校验 | `!chr.hasBuffFromSourceid(buffId)`（未持有才通过） |

## requirements.BuffRequirement

"必须持有某 Buff"条件（同样注册为 `QuestRequirementType.BUFF`）（源码路径：`src/main/java/org/gms/server/quest/requirements/BuffRequirement.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析 | 同 BuffExcept（道具 buff 取负） |
| `public boolean check(Character chr, Integer npcid)` | 校验 | `chr.hasBuffFromSourceid(buffId)` |

## requirements.CompletedQuestRequirement

"已完成任务数量达标"条件（源码路径：`src/main/java/org/gms/server/quest/requirements/CompletedQuestRequirement.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析 | `reqQuest = getInt(data)` |
| `public boolean check(Character chr, Integer npcid)` | 校验 | `chr.getCompletedQuests().size() >= reqQuest` |

## requirements.EndDateRequirement

任务开放截止时间条件。BeiDou 定制：结束时间早于 `2024-11-19 22:46:11`（1732027571809L）视为历史活动数据、条件恒真，避免老任务永久不可接（源码路径：`src/main/java/org/gms/server/quest/requirements/EndDateRequirement.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析 | `timeStr = getString(data)`（形如 yyyyMMddHH） |
| `public boolean check(Character chr, Integer npcid)` | 校验 | 按 yyyy/MM/dd/HH 切片解析为 Calendar；`endTime < 1732027571809L` 直接 true；否则 `endTime >= 当前时间` 才可接 |

## requirements.FieldEnterRequirement

"进入指定地图"条件（源码路径：`src/main/java/org/gms/server/quest/requirements/FieldEnterRequirement.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析 | 取子节点 "0" 的地图 ID（缺省 -1） |
| `public boolean check(Character chr, Integer npcid)` | 校验 | `mapId == chr.getMapId()` |

## requirements.InfoExRequirement

infoEx 期望进度条件：不参与 check，仅向 `Quest.canQuestByInfoProgress` 提供期望值列表（源码路径：`src/main/java/org/gms/server/quest/requirements/InfoExRequirement.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析 | 遍历子节点取 `value` 字符串存入 `infoExpected` |
| `public boolean check(Character chr, Integer npcid)` | 校验 | 恒 true（实际比对在 Quest 侧） |
| `public List<String> getInfo()` | 取期望值 | 返回 `infoExpected` |

## requirements.InfoNumberRequirement

infoNumber 条件：登记任务的 infoNumber 编号（多进度任务用）（源码路径：`src/main/java/org/gms/server/quest/requirements/InfoNumberRequirement.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析 | `infoNumber = (short) getIntConvert(data, 0)` |
| `public boolean check(Character chr, Integer npcid)` | 校验 | 恒 true |
| `public short getInfoNumber()` | 取编号 | 返回 `infoNumber` |

## requirements.IntervalRequirement

任务重复间隔条件（存在即表示任务可重复）：按上次完成时间 + interval 判定能否再接（源码路径：`src/main/java/org/gms/server/quest/requirements/IntervalRequirement.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public long getInterval()` | 取间隔毫秒 | WZ 分钟 × 60000 |
| `public void processData(Data data)` | 解析 | `interval = MINUTES.toMillis(getInt(data))` |
| `private static String getIntervalTimeLeft(Character chr, IntervalRequirement r)` | 剩余时间文案 | 按 剩余>0分钟/小时 组装 "x hours, y minutes, z seconds"（switch 贯穿拼接） |
| `public boolean check(Character chr, Integer npcid)` | 校验 | 未完成，或 `完成时间 <= now - interval` 即可再接；否则 `chr.message` 提示剩余时间并返回 false |

## requirements.ItemRequirement

持有物品条件：校验背包中物品数量，含装备类特殊处理（源码路径：`src/main/java/org/gms/server/quest/requirements/ItemRequirement.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析 | 每个子节点读 id/count 存 `Map<Integer, Integer> items` |
| `public boolean check(Character chr, Integer npcid)` | 校验 | 逐物品：UNDEFINED 类型背包直接 false；累加该类背包 `listById` 数量；EQUIP 且非勋章时——GM 可把已装备栏计入，普通玩家若数量不足且已装备可补足则提示 "Unequip the required ..." 返回 false；最终 `count < countNeeded` 或 `countNeeded<=0 && count>0`（禁持型条件）返回 false |
| `public int getItemAmountNeeded(int itemid, boolean complete)` | 查询所需数量 | 命中返回配置值；否则 complete ? MAX_VALUE : MIN_VALUE |

## requirements.JobRequirement

职业条件（源码路径：`src/main/java/org/gms/server/quest/requirements/JobRequirement.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析 | 子节点逐个加入 `jobs` 列表 |
| `public boolean check(Character chr, Integer npcid)` | 校验 | 角色职业在列表中，或角色是 GM，即通过 |

## requirements.MaxLevelRequirement

等级上限条件（源码路径：`src/main/java/org/gms/server/quest/requirements/MaxLevelRequirement.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` / `public boolean check(Character chr, Integer npcid)` | 解析/校验 | `maxLevel = getInt(data)`；`maxLevel >= chr.getLevel()` |

## requirements.MesoRequirement

金币条件（源码路径：`src/main/java/org/gms/server/quest/requirements/MesoRequirement.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` / `public boolean check(Character chr, Integer npcid)` | 解析/校验 | `meso = getInt(data)`；不足时 `dropMessage(5, "You don't have enough mesos...")` 并返回 false |

## requirements.MinLevelRequirement

最低等级条件（源码路径：`src/main/java/org/gms/server/quest/requirements/MinLevelRequirement.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` / `public boolean check(Character chr, Integer npcid)` | 解析/校验 | `minLevel = getInt(data)`；`chr.getLevel() >= minLevel` |

## requirements.MinTamenessRequirement

宠物最低亲密度条件：取所有出战宠物中的最高亲密度判定（源码路径：`src/main/java/org/gms/server/quest/requirements/MinTamenessRequirement.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` / `public boolean check(Character chr, Integer npcid)` | 解析/校验 | `minTameness = getInt(data)`；遍历 `chr.getPets()` 取最大 tameness 与阈值比较 |

## requirements.MobRequirement

击杀怪物条件：按任务进度槽（progress key = 怪物 ID）校验击杀数（源码路径：`src/main/java/org/gms/server/quest/requirements/MobRequirement.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析 | 每个怪物节点读 id/count 存 `Map<Integer, Integer> mobs` |
| `public boolean check(Character chr, Integer npcid)` | 校验 | 逐怪物把 `status.getProgress(mobID)` parseInt 为进度（解析失败记 warn 日志并 false），`progress < countReq` 即 false |
| `public int getRequiredMobCount(int mobid)` | 查询所需击杀数 | 命中返回 count，否则 0 |

## requirements.MonsterBookCountRequirement

怪物卡收集数量条件（源码路径：`src/main/java/org/gms/server/quest/requirements/MonsterBookCountRequirement.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` / `public boolean check(Character chr, Integer npcid)` | 解析/校验 | `reqCards = getInt(data)`；`chr.getMonsterBook().getTotalCards() >= reqCards` |

## requirements.NpcRequirement

指定 NPC 条件：仅当从该 NPC 触发时才可接/交（源码路径：`src/main/java/org/gms/server/quest/requirements/NpcRequirement.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` / `public boolean check(Character chr, Integer npcid)` | 解析/校验 | `reqNPC = getInt(data)`；`npcid != null && npcid == reqNPC` |
| `public int get()` | 取 NPC ID | 供 `Quest.getNpcRequirement` 查询 |

## requirements.PetRequirement

携带指定宠物条件（源码路径：`src/main/java/org/gms/server/quest/requirements/PetRequirement.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析 | 子节点读 id 存 `petIDs` |
| `public boolean check(Character chr, Integer npcid)` | 校验 | 任一出战宠物的 itemId 在 `petIDs` 中即通过（null 宠物跳过） |

## requirements.QuestRequirement

前置任务状态条件（源码路径：`src/main/java/org/gms/server/quest/requirements/QuestRequirement.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析 | 每个子节点读 id/state 存 `Map<Integer, Integer> quests` |
| `public boolean check(Character chr, Integer npcid)` | 校验 | 逐条目比对 `QuestStatus.Status.getById(stateReq)`；qs 为 null 且要求 NOT_STARTED 视为满足；其余不匹配即 false |

## requirements.ScriptRequirement

脚本任务标记：不参与 check，仅标记该阶段需走 `scripts/quest/*.js` 脚本（源码路径：`src/main/java/org/gms/server/quest/requirements/ScriptRequirement.java`；注意构造器同样误注册为 `QuestRequirementType.BUFF`，属遗留实现细节）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void processData(Data data)` | 解析 | `reqScript = !getString(data, "").isEmpty()` |
| `public boolean check(Character chr, Integer npcid)` | 校验 | 恒 true |
| `public boolean get()` | 是否脚本任务 | 供 `Quest.hasScriptRequirement` 查询 |

---

## medal.DynamicHairMedal

"百变发型"勋章（任务 29020）进度追踪：换发型 50 次达成（源码路径：`src/main/java/org/gms/server/quest/medal/DynamicHairMedal.java`）。

| 常量/方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `QUEST_ID = 29020` / `REQUIRED_CHANGES = 50` | 任务/次数阈值 | 常量 |
| `public static void onHairChanged(Character player, int oldHair, int newHair)` | 换发型回调 | 同基底（/10 相等）、任务未接或非 STARTED 时忽略；进度 <50 时写进度槽 0（`min(progress+1, 50)`） |
| `private static int getProgress(QuestStatus status)` | 读进度 | `parseInt(status.getProgress(0))`，异常按 0 |

## medal.OutstandingCitizenMedal

"杰出市民"勋章（29508）：已婚 + 有公会 + 有家族且至少 1 名后辈时，通过影子资格任务 29580 控制领取资格（源码路径：`src/main/java/org/gms/server/quest/medal/OutstandingCitizenMedal.java`）。

| 常量/方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `QUEST_ID = 29508` / `ELIGIBILITY_QUEST_ID = 29580` / `MEDAL_ID = 1142081` | 常量 | 主任务/资格任务/勋章道具 |
| `public static boolean isEligible(Character player)` | 资格判定 | `isMarried() && getGuildId()>0 && familyEntry!=null && juniorCount>=1` |
| `public static void refreshEligibility(Character player)` | 刷新资格任务状态 | 主任务非 STARTED 或不满足资格 → 资格任务非 NOT_STARTED 则 reset；主任务进行中且满足资格 → 资格任务非 STARTED 则 `forceStart(player, 9000040)` |
| `public static void clearEligibility(Character player)` | 清除资格 | 资格任务非 NOT_STARTED 即 reset（结婚/退会等事件回调用） |

## medal.SpecialChallengeMedal

特级挑战系列勋章（29500~29512）的进度追踪与领取条件判断：覆盖 maply 偶像/龙knights/pink bean/捐赠王/嘉年华双勋/宠物达人/挑战者/怪物博士等九个勋章任务（源码路径：`src/main/java/org/gms/server/quest/medal/SpecialChallengeMedal.java`）。

**关键字段（常量）**：9 组任务 ID ↔ 勋章道具 ID 映射（如 `MAPLE_IDOL_QUEST_ID=29500`/`MEDAL_ID=1142006`）；领取阈值（人气 1000、击杀 1、捐赠 1000 万、嘉年华 100 胜、50 场 70% 胜率、亲密度 1400、怪物卡 30）；Boss 本体 ID（暗黑龙王 `8810018`、Pink Bean `8820001`，不认召唤物）；进度槽位 `PROGRESS_KILLS=0`/`PROGRESS_WINS=1`/`PROGRESS_LOSSES=2`。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static int getProgress(Character player, int questId, int progressId)` | 读任务进度槽 | `parseInt(status.getProgress(progressId))`，异常按 0 |
| `public static int getMaxPetTameness(Character player)` | 最高宠物亲密度 | 遍历出战宠物取最大 tameness（不依赖 WZ 宠物 ID 列表） |
| `public static int getMonsterBookCards(Character player)` | 怪物卡总数 | `player.getMonsterBook().getTotalCards()` |
| `public static boolean hasCarnivalVictoryMedalProgress(Character player)` | 29505 百胜进度 | `PROGRESS_WINS >= 100` |
| `public static boolean hasCarnivalGeniusMedalProgress(Character player)` | 29506 天才进度 | 场次 `wins+losses >= 50` 且 `wins*100 >= matches*70` |
| `public static void onMonsterKilled(Character player, Monster monster)` | Boss 击杀回调 | 怪物 ID 命中龙王/Pink Bean 本体时给对应已接任务加击杀进度 |
| `public static void onMonsterCarnivalFinished(Character player, boolean cpq1, boolean won)` | 嘉年华结算回调 | cpq1 直接跳过（仅怪物嘉年华2）；胜场进 29505 的 WINS；29506 按胜负分别累计 WINS/LOSSES |
| `private static void addStartedQuestProgress(Character player, int questId, int progressId, int cap)` | 进度自增（核心） | 仅 STARTED 任务计；`progress >= cap` 封顶不再加；写回 `setProgress` 后 `announceUpdateQuest(UPDATE, ...)` 刷新客户端 |

## medal.VeteranHunterMedal

"十万猎人"勋章（29400）：击杀 10 万只符合等级条件的怪物。进度槽 key 使用虚拟怪物 ID `9999999`（源码路径：`src/main/java/org/gms/server/quest/medal/VeteranHunterMedal.java`）。

| 常量/方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `QUEST_ID = 29400` / `MEDAL_ID = 1142004` / `REQUIRED_KILLS = 100000` / `PROGRESS_MOB_ID = 9999999` | 常量 | 任务/勋章/阈值/进度槽 |
| `public static int getProgress(Character player)` | 读进度 | `parseInt(status.getProgress(9999999))`，异常按 0 |
| `public static boolean isComplete(Character player)` | 是否达成 | `getProgress >= 100000` |
| `public static void onMonsterKilled(Character player, Monster monster)` | 击杀回调 | 任务须 STARTED 且未达上限，且 `isEligibleKill`；进度 +1（封顶）后 `announceUpdateQuest(UPDATE, ...)` |
| `private static boolean isEligibleKill(Character player, Monster monster)` | 击杀资格 | 角色 ≥120 级时须怪物 ≥120 级；否则须怪物等级 > 角色等级（越级击杀） |

---

# 第二部分 org.gms.server.life（25 类）

## Monster

怪物运行时对象（继承 `AbstractLoadedLife`），游戏服最核心的大类之一：持有 HP/MP、控制者（aggro）、怪物状态（stati）、技能/攻击冷却、伤害记账（exp/掉落分配依据）、召唤关系，并对接事件实例、家族声望、任务击杀与勋章进度（源码路径：`src/main/java/org/gms/server/life/Monster.java`）。

**关键字段**

| 字段 | 说明 |
| --- | --- |
| `ostats: ChangeableStats` | 变身属性覆盖（v83 WZ 不支持，保留字段） |
| `stats: MonsterStats` | 基础属性（构造时 `copy()` 出独立副本） |
| `hp: AtomicInteger` / `maxHpPlusHeal: AtomicLong` | 当前 HP；含治疗量的"总承受伤害"分母（经验按伤害比例分配用） |
| `mp` | 当前 MP（技能/攻击消耗） |
| `controller: WeakReference<Character>` | 当前控制者客户端（负责驱动该怪 AI） |
| `controllerHasAggro` / `controllerKnowsAboutAggro` / `controllerHasPuppet` | 控制者仇恨三态 |
| `listeners: Collection<MonsterListener>` | 生命周期监听器（SpawnPoint/事件等注册） |
| `stati: EnumMap<MonsterStatus, MonsterStatusEffect>` / `alreadyBuffed` | 已生效怪物状态与历史 buff 记录 |
| `usedSkills: Set<MobSkillId>` / `usedAttacks: Set<Integer>` | 冷却中的技能/攻击 |
| `calledMobOids` / `callerMob: WeakReference<Monster>` / `parentMobOid` | 召唤关系（双向） |
| `takenDamage: HashMap<Integer, AtomicLong>` | 每角色累计伤害（经验/掉落归属） |
| `fake` | 假怪（如扎昆球，客户端不可交互但可见） |
| `dropsDisabled` | 禁用掉落（Boss Rush 场景） |
| `stolenItems: List<Integer>` | 已被偷走的道具（防止重复偷） |
| `monsterItemDrop: ScheduledFuture` | 友好怪定时掉落任务 |
| `externalLock` / `monsterLock`(公平) / `statiLock` / `animationLock` / `aggroUpdateLock` | 五把内部锁，分层控制并发 |

### 构造与通用

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public Monster(int id, MonsterStats stats)` | 主构造 | `super(id)` 后 `initWithStats` |
| `public Monster(Monster monster)` | 拷贝构造 | `super(monster)` 复制朝向/站位等，再 `initWithStats(monster.stats)`（stats 仍是独立副本） |
| `private void initWithStats(MonsterStats baseStats)` | 初始化 | 站位 5；`stats = baseStats.copy()`（反射逐字段拷贝）；hp/mp 取属性值；`maxHpPlusHeal = hp` |
| `public void lockMonster()` / `public void unlockMonster()` | 外部锁 | 供伤害/击杀等外层流程串行化（如 `MapleMap.killMonster`、`Quest` 之外的调用方） |

### 基础属性读写

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public int getHp()` / `public synchronized void addHp(int hp)` | HP 读写 | addHp 在已死（hp<=0）时忽略 |
| `public synchronized void setStartingHp(int hp)` | 重设起始 HP | 同时改 stats 与当前 hp（非静态 HP 池重构） |
| `public int getMaxHp()` / `getMp()` / `void setMp(int)` / `getMaxMp()` | 上限与 MP | setMp 负数归零 |
| `public int getExp()` / `getLevel()` / `getCP()` / `getTeam()` / `void setTeam(int)` | 经验/等级/CP/队伍 | 直接读 stats 或字段 |
| `public int getVenomMulti()` / `void setVenomMulti(int)` | 毒刃层数 | 毒伤叠加倍数 |
| `public MonsterStats getStats()` / `void setStats(MonsterStats)` | 属性对象 | — |
| `public boolean isBoss()` / `isMobile()` / `isFirstAttack()` / `isFake()` / `void setFake(boolean)` | 标志位 | fake 读写走 monsterLock |
| `public String getName()` / `public int getAnimationTime(String name)` | 名称/动作时长 | 委托 stats |
| `public int getBuffToGive()` / `getPADamage()` / `getDropPeriodTime()` / `getRemoveAfter()` | 杂项 | 委托 stats |
| `public void setBoss(boolean boss)` | 置 Boss | 写 stats（常用于脚本） |
| `public int getSpawnEffect()` / `void setSpawnEffect(int)` | 出生特效 | — |
| `public void setMap(MapleMap map)` / `public MapleMap getMap()` | 所属地图 | — |
| `public MonsterAggroCoordinator getMapAggroCoordinator()` | 取地图仇恨协调器 | `map.getAggroCoordinator()` |
| `public boolean isAlive()` | 存活判定 | `hp.get() > 0`（disposeMapObject 置 -1 后视为不可用） |

### 召唤/父子怪

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public int getParentMobOid()` / `public void setParentMobOid(int)` | 父怪 OID | 复活链标记 |
| `public int countAvailableMobSummons(int summonsSize, int skillLimit)` | 剩余可召唤数 | `min(本次数量, limit - 已召唤数)`（limit 是场上召唤物上限） |
| `public void addSummonedMob(Monster mob)` | 登记召唤物 | 惰性建 `calledMobOids`（同步 Set），子怪回挂 `callerMob` |
| `private void removeSummonedMob(int mobOid)` / `private void setSummonerMob(Monster mob)` | 内部维护 | — |
| `private void dispatchClearSummons()` | 死亡时清理 | 通知 caller 移除自己；清空 calledMobOids |
| `public void pushRemoveAfterAction(Runnable run)` / `public Runnable popRemoveAfterAction()` | 延迟移除动作 | 与 `removeAfter`（定时消失怪）配合 |

### HP 变动 / 伤害 / 治疗

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void setHpZero()` | 强制清零 HP | `applyAndGetHpDamage(Integer.MAX_VALUE, false)` |
| `public synchronized Integer applyAndGetHpDamage(int delta, boolean stayAlive)` | HP 增减原子核心 | 已死返回 null；delta≥0：stayAlive 时预留 1 点（毒不可直接毒死），真实伤害 = min(curHp, delta) 扣减并返回；delta<0（治疗）：超出 maxHp 部分截断 |
| `public synchronized void disposeMapObject()` | 脱离地图 | `hp.set(-1)` 使对象失效 |
| `public void damage(Character attacker, int damage, boolean stayAlive)` | 玩家攻击入口 | 持 externalLock：已死 false；damage>0 走 `applyDamage`，若致死则 lastHit=true 且处于 Netz 金字塔（Pyramid）时结算 cool/kill；damage<=0 且在金字塔记 miss |
| `private void applyDamage(Character from, int damage, boolean stayAlive, boolean fake)` | 伤害落地 | ① `applyAndGetHpDamage`（null 即已死）；② GM+debug 时发包显示任务/怪物 ID；③ 非 fake 时 `dispatchMonsterDamaged` + 事件实例 `eim.addDamage(from, trueDamage)`；④ 记入 `takenDamage`（每角色 AtomicLong）；⑤ `broadcastMobHpBar` |
| `public void applyFakeDamage(Character from, int damage, boolean stayAlive)` | 假伤害 | `applyDamage(..., fake=true)`（不计入记账，仅掉血表现） |
| `public void heal(int hp, int mp)` | 治疗 | `applyAndGetHpDamage(-hp)`；MP 补至上限；hp>0 时广播 `healMonster`；`maxHpPlusHeal` 累加真实治疗量；`dispatchMonsterHealed` |
| `public void broadcastMobHpBar(Character from)` | 广播血条 | Boss 血条：`setPlayerAggro` + 全图 `broadcastBossHpMessage`；普通怪：按剩余百分比发包（组队时发给同图队友） |
| `public boolean isAttackedBy(Character chr)` | 是否被某角色攻击过 | `takenDamage.containsKey` |
| `public int getHighestDamagerId()` | 伤害最高者 ID | 遍历 takenDamage 取最大（用于拾取归属） |
| `public boolean hasBossHPBar()` / `public Packet makeBossHPBarPacket()` | Boss 血条 | `isBoss() && tagColor>0`；组包 `showBossHP` |

### 经验分配

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `private static boolean isWhiteExpGain(Character chr, Map<Integer, Float> personalRatio, double sdevRatio)` | 白字经验判定 | 该角色伤害占比 ≥ 均值+标准差阈值 |
| `private static double calcExperienceStandDevThreshold(List<Float> entryExpRatio, int totalEntries)` | 计算阈值 | 求各"参与方"（单人或整队）占比的 均值+标准差 |
| `private void distributePlayerExperience(Character chr, float exp, float partyBonusMod, int totalPartyLevel, boolean highestPartyDamager, boolean whiteExpGain, boolean hasPartySharers)` | 单人份经验 | 个人份 = `exp_split_common_mod * 等级/队伍总等级` +（MVP `exp_split_mvp_mod`）再乘 exp；组队 bonus = partyBonusMod × 个人份；最终 `giveExpToCharacter` |
| `private void distributePartyExperience(Map<Character, Long> partyParticipation, float expPerDmg, Set<Character> underleveled, Map<Integer, Float> personalRatio, double sdevRatio)` | 队伍分配 | 以怪等级 ±`exp_split_level_interval` 建"吸经验区间"，再叠加每个参战者 ±`exp_split_leech_interval`；`use_enforce_mob_level_range` 开启时队内等级不在区间者进 underleveled（仅提示不给经验）；组队 bonus = 5%×人数（>1 人时）；MVP 为伤害最高者 |
| `private void distributeExperience(int killerId)` | 总分配入口 | 按 takenDamage 归组：同图同队进 partyExpDist、散人进 soloExpDist、离线/换图者仅计入参与方总数；expPerDmg = 怪经验 / `maxHpPlusHeal`（含治疗总量）；先发散人再发队伍；最后事件实例 `eim.monsterKilled(chr, this)`、underleveled 者显示越级提示 |
| `private float getStatusExpMultiplier(Character attacker, boolean hasPartySharers)` | 经验倍率 | 神圣之火（HolySymbol）：`use_full_holy_symbol` 全额否则单人仅 1/5；怪物 SHOWDOWN 状态加成 |
| `private static int expValueToInteger(double exp)` | 浮点转 int | 夹取到 int 范围后四舍五入（防 -1 经验） |
| `private void giveExpToCharacter(Character attacker, Float personalExp, Float partyExp, boolean white, boolean hasPartySharers)` | 落经验 | 依次乘：状态倍率、`expRate*mobExpRate`、EXP_INCREASE 加值、EXP_BUFF 翻倍、家族 buff `familyExp`；组队份另乘 `party_bonus_exp_rate`；`gainExp(个人, 组队, ...)`；`increaseEquipExp`；`raiseQuestMobCount(getId())`；并回调 `VeteranHunterMedal.onMonsterKilled` 与 `SpecialChallengeMedal.onMonsterKilled`（BeiDou：勋章进度挂在经验发放处） |
| `private void giveFamilyRep(FamilyEntry entry)` | 家族声望 | Boss/普怪按 `family_rep_per_boss_kill`/`family_rep_per_kill`，maxHp<=1 的垃圾怪不计 |

### 死亡与掉落

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public List<MonsterDropEntry> retrieveRelevantDrops()` | 计算掉落表 | 友好怪直接 `MonsterInformationProvider.retrieveEffectiveDrop`；普通怪收集 takenDamage 中仍在线的打手，交 `LootManager.retrieveRelevantDrops(mobId, lootChars)`（按打手掉落率/任务道具过滤） |
| `public Character killBy(final Character killer)` | 死亡结算 | ① `distributeExperience`；② `aggroRemoveController`；③ 有 revives（复活怪）时按 `die1` 动画时长延时重生：逐个 `LifeFactory.getMonster` 置原位、继承 parentMobOid 与 dropsDisabled；武陵通塔 clear 特效、timeMob 提示；暗黑龙头死亡且主怪已倒时连锁清尸；控制器移交；`eim.reviveMonster`；④ 返回拾取者：优先最高伤害者，否则 killer |
| `public void dropFromFriendlyMonster(long delay)` | 友好怪定时掉落 | `TimerManager.register` 周期任务：死亡即自取消；图上有人时触发 `eim.friendlyItemDrop` 与 `map.dropFromFriendlyMonster` |
| `private void dispatchRaiseQuestMobCount()` | 无击杀者时任务计数 | 给所有参战者 `raiseQuestMobCount`（如毒死/自爆） |
| `public void dispatchMonsterKilled(boolean hasKiller)` | 死亡事件分发 | `processMonsterKilled` + 事件实例：普通怪 `eim.monsterKilled(this, hasKiller)`、友好怪 `friendlyKilled` |
| `private synchronized void processMonsterKilled(boolean hasKiller)` | 死亡善后 | 无 killer 时补任务计数；`aggroClearDamages`；`dispatchClearSummons`；快照方式逐个回调 `listener.monsterKilled(die1 时长)`；最后清空 stati/alreadyBuffed/listeners |
| `private void dispatchMonsterDamaged(Character from, int trueDmg)` / `private void dispatchMonsterHealed(int trueHeal)` | 受击/治疗事件 | 快照遍历 listeners 回调 |
| `public void addListener(MonsterListener listener)` | 注册监听 | statiLock 保护下加入 |
| `public void dispose()` | 销毁 | 取消定时掉落任务；`map.dismissRemoveAfter(this)` |

### 怪物状态（stati/buff/debuff）

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `private Character getActiveController()` | 有效控制器 | 控制者须在线且仍在本图，否则 null |
| `private void broadcastMonsterStatusMessage(Packet packet)` | 状态包广播 | 全图广播 + 控制者不可见时单独补发 |
| `private int broadcastStatusEffect(MonsterStatusEffect status)` | 广播生效 | `applyMonsterStatus` 组包，返回技能动画时长 |
| `public boolean applyStatus(Character from, MonsterStatusEffect status, boolean poison, long duration)` | 施加状态（4 参） | 委托 5 参版本（venom=false） |
| `public boolean applyStatus(Character from, MonsterStatusEffect status, boolean poison, long duration, boolean venom)` | 施加状态核心 | ① 元素抗性门槛（IMMUNE/STRONG/NEUTRAL 拒绝）；② 特判火毒/冰雷合击按毒/冰抗、毒刃按毒抗（WEAK 反而拒绝）；③ poison 且 hp<=1 拒绝；④ Boss 只接受 SPEED+NINJA_AMBUSH+WATK 组合；⑤ 顶掉旧同类状态（interruptMobStatus）；⑥ poison：毒伤 = maxHp/(70-技能等级) 上限 Short.MAX，1 秒周期 DamageTask；venom：限夜光/影武/夜行者职业，毒伤按 luck×matk 区间 × 层数；Ninja Ambush（4121004/4221004）：伤害=(力量+运气)×3.7%技能伤害；⑦ 写入 stati/alreadyBuffed，交 `MobStatusService.registerMobStatus(mapid, status, cancelTask, duration+animationTime-100, overtimeAction, overtimeDelay)` |
| `public final void dispelSkill(final MobSkill skill)` | 驱散怪物自身技能 buff | 遍历 stati 找 `getMobSkill().getType()==skill.getType()` 的项逐个 `debuffMobStat` |
| `public void applyMonsterBuff(Map<MonsterStatus, Integer> stats, int x, long duration, MobSkill skill, List<Integer> reflection)` | 怪物自我增益（MobSkill 用） | 组 `MonsterStatusEffect`（vegan=true）广播 `applyMonsterStatus`（带反伤列表）；写 stati；注册取消任务（到期广播 cancelMonsterStatus 并移除） |
| `public void refreshMobPosition()` / `public void resetMobPosition(Point newPoint)` | 位置重置 | 移除控制器 → `setPosition` → 广播 moveMonster → `map.moveMonster` → 重找控制器 |
| `private void debuffMobStat(MonsterStatus stat)` | 移除单个状态 | statiLock 下移除并广播取消包 |
| `public void debuffMob(int skillid)` | 玩家驱散怪物 | 钱盾（ShadowMeso）驱双防、圣光（Dispel）驱四项、Crash 系按技能驱对应项；`use_anti_immunity_crash` 开启时 Crash 还能驱免疫（有反伤盾时不驱） |
| `public boolean isBuffed(MonsterStatus status)` | 是否有某状态 | statiLock 下 containsKey |
| `public Map<MonsterStatus, MonsterStatusEffect> getStati()` / `public MonsterStatusEffect getStati(MonsterStatus ms)` | 状态快照/单查 | 全量查询返回拷贝（防并发修改） |
| `public Collection<MonsterStatus> alreadyBuffedStats()` | 历史 buff | 返回只读视图 |
| `public void setTempEffectiveness(Element e, ElementalEffectiveness ee, long milli)` | 临时改元素抗性 | 当前非 WEAK 才覆盖；到期经 MobClearSkillService 恢复原值（火毒"降低火抗"类技能用） |
| `public ElementalEffectiveness getElementalEffectiveness(Element e)` | 查元素效果 | DOOM 状态下按 NORMAL（变蜗牛）；否则 monsterLock 下查 stats |
| `private ElementalEffectiveness getMonsterEffectiveness(Element e)` | 内部查询 | monsterLock 保护（applyStatus 抗性判断也用它） |

### 技能 / 攻击（怪物 AI 行为）

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public Set<MobSkillId> getSkills()` / `public boolean hasSkill(int skillId, int level)` | 技能表 | 委托 stats |
| `public boolean canUseSkill(MobSkill toUse, boolean apply)` | 能否释放技能 | null/被 SEAL_SKILL 拒绝；反伤类在已有 WEAPON/MAGIC_REFLECT 时拒绝；monsterLock 下校验 usedSkills 冷却与 MP；apply=true 时调 `usedSkill` 占用 |
| `private boolean isReflectSkill(MobSkill mobSkill)` | 是否反伤类 | PHYSICAL/MAGIC/PHYSICAL_AND_MAGIC_COUNTER |
| `private void usedSkill(MobSkill skill)` | 记录技能消耗 | 扣 MP、入 usedSkills；经 `MobClearSkillService` 在 cooltime 后 `clearSkill` |
| `private void clearSkill(MobSkillId msId)` | 冷却结束 | 移出 usedSkills |
| `public int canUseAttack(int attackPos, boolean isSkill)` | 能否普攻 | 查 `MonsterInformationProvider.getMobAttackInfo`（无此攻击返回 -1）；MP 不足 -1；通过则 `usedAttack` 扣 MP 并登记冷却（attackAfter），返回 1 |
| `private void usedAttack(int attackPos, int mpCon, int cooltime)` / `private void clearAttack(int attackPos)` | 攻击记账 | 同技能的冷却模型 |
| `public boolean hasAnySkill()` / `public MobSkillId getRandomSkill()` | 技能枚举 | 有技能/随机取一个（Set 随机流式 skip 实现） |
| `private boolean applyAnimationIfRoaming(int attackPos, MobSkill skill)` | 动画互斥（当前被注释未启用） | tryLock 后按攻击/技能动画时长注册 MobAnimationService |
| `private final class DamageTask implements Runnable` | 周期伤害任务（毒/网/伏击） | run：hp<=1 中断状态；伤害夹到 curHp-1（毒不死）；type 1/2（网/伏击）到达下限时中断；正伤害走 applyDamage(stayAlive=true)；type 1 广播 damageMonster、type 2 仅在截断时广播（伏击对施法者已显示） |

### 地图对象协议

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void sendSpawnData(Client client)` | 发送出生包 | 已死不发；fake 发 spawnFakeMonster，否则 spawnMonster；Boss 血条另发 |
| `public void sendDestroyData(Client client)` | 发送消失包 | 连发两包 killMonster（0/1 动画阶段） |
| `public MapObjectType getType()` | 对象类型 | `MapObjectType.MONSTER` |
| `public boolean isFacingLeft()` | 朝向 | 有 fixedStance（noFlip 怪）时按其奇偶判定，否则走父类 stance |

### 偷窃与杂项

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void addStolen(int itemId)` / `public List<Integer> getStolen()` | 偷窃记账 | 防止同一道具被重复偷（神偷术） |
| `public BanishInfo getBanish()` | 取放逐信息 | 委托 stats（BANISH 技能把玩家送回指定地图） |

### Override 属性（changeLevel/changeDifficulty）

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public final ChangeableStats getChangedStats()` / `public final int getMobMaxHp()` | 读覆盖属性 | ostats 优先，无则读 stats |
| `public final void setOverrideStats(OverrideMonsterStats ostats)` | 直接覆盖 HP/MP/EXP | 构建 ChangeableStats 并同步 hp/mp |
| `public final void changeLevel(int newLevel)` / `public final void changeLevel(int newLevel, boolean pqMob)` | 按等级重算属性 | `stats.isChangeable()` 才生效（WZ `changeable` 标记）；按新等级重算 HP/EXP/MP/攻防（pqMob ×1.5） |
| `private float getDifficultyRate(int difficulty)` | 难度系数 | 6/5/4/3/2 → 7.7/5.6/3.2/2.1/1.4 |
| `private void changeLevelByDifficulty(int difficulty, boolean pqMob)` / `public final void changeDifficulty(int difficulty, boolean pqMob)` | 按难度等级 | 等级 × 系数后走 changeLevel |

### 控制者 / 仇恨（aggro）体系

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public Character getController()` / `private void setController(Character)` | 控制者读写 | WeakReference 持有防泄漏 |
| `public boolean isControllerHasAggro()` / `isControllerKnowsAboutAggro()` | 仇恨状态 | fake 怪恒 false |
| `private void setControllerHasAggro(boolean)` / `setControllerKnowsAboutAggro(boolean)` / `setControllerHasPuppet(boolean)` | 内部写 | fake 怪不写 |
| `private boolean isPuppetInVicinity(Summon summon)` | 傀儡在附近 | 距离平方 < 177777 |
| `public boolean isCharacterPuppetInVicinity(Character chr)` | 角色傀儡覆盖本怪 | 取 PUPPET buff 的召唤兽判定；召唤兽已消失则顺手 `removePuppetAggro` |
| `public boolean isLeadingPuppetInVicinity()` | 当前控制器傀儡覆盖 | 控制器有效时委托上法 |
| `private Character getNextControllerCandidate()` | 选新控制器 | 遍历本图玩家（过滤隐藏与离线幽灵玩家）：优先傀儡附近者 > 活着且控怪数最少者 > 死亡玩家中控怪数最少者 |
| `public Pair<Character, Boolean> aggroRemoveController()` | 摘除控制器 | aggroUpdateLock 下清三态；给旧控制者发 stopControllingMonster（非 fake）并 `stopControllingMonster`；返回 (旧控制者, 是否有仇恨) 供重生移交 |
| `public void aggroSwitchController(Character newController, boolean immediateAggro)` | 切换控制器 | tryLock（防递归死锁）；同人不处理；先 remove；新控制者须在线且同图；置三态后 `aggroUpdatePuppetVisibility` + 发控制包 + `newController.controlMonster` |
| `public void aggroAddPuppet(Character player)` / `public void aggroRemovePuppet(Character player)` | 傀儡上下场 | 委托 MonsterAggroCoordinator 登记/移除，再 `aggroUpdatePuppetController` 刷新可见性 |
| `public void aggroUpdateController()` | 自动找控制器 | 现任有效且活着则不换；否则 `getNextControllerCandidate` 切换 |
| `private void aggroUpdatePuppetController(Character newController)` | 傀儡优先找控制器 | 现任傀儡覆盖则不换；否则在 puppetOwners 名单里找覆盖本怪的在线玩家；顺带清理离线傀儡；找不到时按需回落 `aggroUpdateController` |
| `public void aggroRedirectController()` | 强制重定向 | 先 remove 再 update（玩家进图/复活等） |
| `public Boolean aggroMoveLifeUpdate(Character player)` | 移动包鉴权 | 请求者是控制器则返回其 aggro（并置 knowsAboutAggro），否则 null（非控制器） |
| `public void aggroAutoAggroUpdate(Character player)` | 玩家近身自动仇恨 | 无控制器则接管（immediateAggro=true）；本人已是控制器则置 aggro；`use_auto_aggro_nearby` 关闭时补发控制包 |
| `public void aggroMonsterDamage(Character attacker, int damage)` | 受击仇恨 | `addAggroDamage` 记账；非控制器攻击时若 `isLeadingCharacterAggro`（DPS 最高）则切换控制器，否则仅刷新 aggro 与傀儡可见性 |
| `private static void aggroMonsterControl(Client c, Monster mob, boolean immediateAggro)` | 发控制包 | `PacketCreator.controlMonster` |
| `private void aggroRefreshPuppetVisibility(Character chrController, Summon puppet)` | 傀儡仇恨重定向补丁 | 给客户端重发：先 stop 全部傀儡覆盖的怪 → 移除并重发傀儡召唤 → 重发这些怪的控制包（让客户端把仇恨转移到傀儡） |
| `public void aggroUpdatePuppetVisibility()` | 傀儡可见性刷新 | `availablePuppetUpdate` 防重入；经 OverallService 延迟 `update_interval` 执行：控制器有傀儡覆盖则重定向；从覆盖变为不覆盖时重发控制包 |
| `public void aggroClearDamages()` | 清伤害记账 | `removeAggroEntries(this)`（死亡时） |
| `public void aggroResetAggro()` | 清仇恨 | aggroUpdateLock 下清 hasAggro/knowsAboutAggro |

---

## AbstractLoadedLife

地图生命体基类（继承 `AbstractAnimatedMapObject`）：NPC 与 Monster 的公共父类，承载 WZ life 节点的朝向/隐藏/站位/活动范围属性（源码路径：`src/main/java/org/gms/server/life/AbstractLoadedLife.java`）。

**关键字段**：`id`（生命体模板 ID）、`f`（朝向 0/1）、`hide`（是否隐藏 NPC）、`fh`/`start_fh`（当前/初始平台 ID）、`cy`、`rx0`/`rx1`（NPC 活动范围左右边界）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public AbstractLoadedLife(int id)` | 主构造 | — |
| `public AbstractLoadedLife(AbstractLoadedLife life)` | 拷贝构造 | 复制 id 与全部布局字段（start_fh 取 fh） |
| `public int getF()` / `void setF(int)` | 朝向 | — |
| `public boolean isHidden()` / `void setHide(boolean)` | 隐藏标记 | 隐藏 NPC 仅 GM 可见 |
| `public int getFh()` / `void setFh(int)` / `public int getStartFh()` | 平台 | — |
| `public int getCy()` / `void setCy(int)` / `getRx0()` / `setRx0(int)` / `getRx1()` / `setRx1(int)` | 布局坐标 | — |
| `public int getId()` | 模板 ID | — |

## NPC

地图 NPC 对象（继承 `AbstractLoadedLife`）：包装 `NPCStats`（名称）并对接商店系统与出生/销毁包（源码路径：`src/main/java/org/gms/server/life/NPC.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public NPC(int id, NPCStats stats)` | 构造 | — |
| `public boolean hasShop()` | 是否绑定商店 | `ShopFactory.getShopForNPC(getId()) != null` |
| `public void sendShop(Client c)` | 打开商店 | 取该 NPC 的商店并 `sendShop(c)` |
| `public void sendSpawnData(Client client)` | 出生包 | `spawnNPC` + `spawnNPCRequestController(true)` |
| `public void sendDestroyData(Client client)` | 消失包 | `removeNPCController` + `removeNPC` |
| `public MapObjectType getType()` | 类型 | `MapObjectType.NPC` |
| `public String getName()` | 名称 | 委托 stats |

## NPCStats

NPC 属性：仅含名称（源码路径：`src/main/java/org/gms/server/life/NPCStats.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public NPCStats(String name)` / `public String getName()` / `public void setName(String name)` | 名称读写 | — |

## Element

元素枚举（NEUTRAL/PHYSICAL/FIRE/ICE/LIGHTING/POISON/HOLY/DARKNESS），FIRE/ICE/HOLY 标记 `special`（源码路径：`src/main/java/org/gms/server/life/Element.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public boolean isSpecial()` | 特殊元素 | 构造标记（火/冰/圣） |
| `public static Element getFromChar(char c)` | WZ elemAttr 字符转枚举 | F/I/L/S/H/D/P，未知字符抛 IllegalArgumentException |
| `public int getValue()` | 编号 | 0~7 |

## ElementalEffectiveness

元素效果枚举：NORMAL（普通）/IMMUNE（免疫）/STRONG（抗性）/WEAK（弱点）/NEUTRAL（无属性）（源码路径：`src/main/java/org/gms/server/life/ElementalEffectiveness.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static ElementalEffectiveness getByNumber(int num)` | WZ 数字转枚举 | 1→IMMUNE、2→STRONG、3→WEAK、4→NEUTRAL，其余抛异常 |

## OverrideMonsterStats

怪物属性覆盖三元组（HP/MP/EXP），供脚本/PQ 直接改写怪物数值（源码路径：`src/main/java/org/gms/server/life/OverrideMonsterStats.java`）。公有字段 `hp/mp/exp`。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public OverrideMonsterStats()` | 默认构造 | hp=1, mp=0, exp=0 |
| `public OverrideMonsterStats(int hp, int mp, int exp, boolean change)` / `(int, int, int)` | 全参构造 | `change` 倍率逻辑已注释停用，直接赋值 |
| `getExp()/setOExp(int)`、`getHp()/setOHp(int)`、`getMp()/setOMp(int)` | 读写 | — |

## ChangeableStats

可变怪物属性（继承 `OverrideMonsterStats`，追加 watk/matk/wdef/mdef/level）：用于按新等级或覆盖值重算怪物强度（源码路径：`src/main/java/org/gms/server/life/ChangeableStats.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public ChangeableStats(MonsterStats stats, OverrideMonsterStats ostats)` | 用覆盖值构建 | hp/mp/exp 取 ostats，攻防等级取原 stats |
| `public ChangeableStats(MonsterStats stats, int newLevel, boolean pqMob)` | 按等级重算 | mod = 新等级/原等级；hpRatio = hp/exp；非 Boss 用 `GameConstants.getMonsterHP(newLevel)` 查表、Boss 按 mod 缩放；pqMob ×1.5；攻防按 mod 缩放且防御上限 Boss 30/普怪 20 |
| `public ChangeableStats(MonsterStats stats, float statModifier, boolean pqMob)` | 按倍率重算 | `newLevel = statModifier × 原等级` 后走上法 |

## MonsterStats

怪物静态属性包：由 `LifeFactory.getMonsterStats` 从 WZ 解析填充、每个 Monster 实例持有独立 `copy()`；BeiDou 扩展了移动类型/首帧尺寸/碰撞框（bbox）字段用于大体型距离判定（源码路径：`src/main/java/org/gms/server/life/MonsterStats.java`）。

**关键字段（节选）**：`exp/hp/mp/level/PADamage/PDDamage/MADamage/MDDamage`、`boss/undead/ffaLoot/isExplosiveReward/firstAttack/removeOnMiss/friendly`、`animationTimes`（动作名→总时长）、`resistance: Map<Element, ElementalEffectiveness>`、`revives`、`skills: Set<MobSkillId>`、`cool`（酷伤害 Pair）、`banish/loseItem/selfDestruction`、`fixedStance`（noFlip 朝向）、`movetype`（-1 未知/0 陆地/1 飞行）、`imgwidth/imgheight`、`bboxMinX..bboxMaxY + bboxValid`。

**方法表**（除注明外均为 getter/setter 对，关键逻辑列省略平凡项）：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void setAnimationTime(String name, int delay)` / `public int getAnimationTime(String name)` | 动作时长 | 缺省 500ms |
| `public boolean isMobile()` | 是否可移动 | 存在 move 或 fly 动作 |
| `public void setEffectiveness(Element e, ElementalEffectiveness ee)` / `public ElementalEffectiveness getEffectiveness(Element e)` | 元素抗性 | 缺省 NORMAL；`removeEffectiveness`（包私有）供临时抗性恢复 |
| `public Set<MobSkillId> getSkills()` / `public int getNoSkills()` / `public boolean hasSkill(int skillId, int level)` | 技能 | getSkills 返回不可变 Set |
| `public List<loseItem> loseItem()` / `public void addLoseItem(loseItem li)` | 死亡掉玩家物品 | 惰性建表 |
| `public selfDestruction selfDestruction()` / `void setSelfDestruction(...)` | 自爆配置 | — |
| `public int getMovetype()` / `void setMovetype(int)` | 移动类型 | -1/0/1，BeiDou 扩展 |
| `void setBbox(int minX, int minY, int maxX, int maxY)` / `boolean hasBbox()` / `getBboxMinX/MinY/MaxX/MaxY()` | 碰撞框 | 写入时置 bboxValid |
| `public int getBboxWidth()` / `public int getBboxHeight()` | 碰撞框宽高 | 无 bbox 时回退首帧图片宽高 |
| `public boolean isLargeSize()` | 大体型判定 | 宽或高 ≥160 或面积 ≥25000（决定是否启用碰撞框距离检测） |
| `public MonsterStats copy()` | 深拷贝 | 内部 `FieldCopyUtil.setFields` 反射逐字段复制（浅拷贝集合引用）；异常时打印堆栈并 sleep 10s 后返回半成品（遗留容错） |

其余 getter/setter：`setChange/isChangeable`、`exp/hp/mp/level`、`removeAfter`、`dropPeriod`、`boss`、`ffaLoot`、`revives`、`undead`、`name`、`tagColor/tagBgColor`、`firstAttack`、`buffToGive`、`banish`、`PADamage/CP`、`explosiveReward`、`removeOnMiss`、`cool`、`PDDamage/MADamage/MDDamage`、`friendly`、`fixedStance`、`imgwidth/imgheight`。

## LifeFactory

生命体工厂：从 Mob.wz/String.wz 解析怪物属性与 NPC 名称，缓存 `MonsterStats`；同时解析怪物攻击信息（MP 消耗/冷却/动画时长）与技能动画并登记到 `MonsterInformationProvider`。BeiDou 重写了怪物可视数据（link 链回溯 + UOL 解析 + 碰撞框合并）逻辑（源码路径：`src/main/java/org/gms/server/life/LifeFactory.java`）。

**关键字段**：`monsterStats: Map<Integer, MonsterStats>` 缓存、`hpbarBosses`（UI `MobGage` 血条 Boss 名单）、`npcNames` 缓存、WZ MOB/STRING provider。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static AbstractLoadedLife getLife(int id, String type)` | 按类型造生命体 | "n"→`getNPC`、"m"→`getMonster`，未知类型记 warn 返回 null |
| `public static Monster getMonster(int mid)` | 造怪物实例 | 缓存 miss 时调 `getMonsterStats(mid)` 解析并缓存，且 `setMonsterAttackInfo` 登记攻击信息；每次返回 `new Monster(mid, stats)`（stats 内部会 copy）；NPE 时记 SEVERE 日志返回 null |
| `private static Pair<MonsterStats, List<MobSkillInfoHolder>> getMonsterStats(int mid)` | 解析怪物属性（核心） | ① 读 `Mob.wz/<mid>.img` 的 info 节点，存在 `link` 时先递归取 link 怪的属性与攻击信息作底（revives 等不传播项除外——由默认值表达式区分）；② 逐字段读 maxHP/PADamage/PDDamage/MADamage/MDDamage/maxMP/exp/level/removeAfter/boss/explosiveReward/publicReward/undead/name/buff/getCP/removeOnMiss/damagedByMob(friendly)；③ coolDamage→`setCool`、loseItem→逐条 addLoseItem、selfDestruction、firstAttack（FLOAT 类型四舍五入）；④ dropItemPeriod ×10000（毫秒）；⑤ Boss 且在 hpbarBosses 名单才启用 hpTagColor/hpTagBgcolor；⑥ 遍历非 info 子节点累加 delay 得各动作总时长；⑦ revive 列表；⑧ `decodeElementalString` 解析 elemAttr；⑨ skill 节点：逐条 (skill,level) 经 `MobSkillType.from(...).orElseThrow()` 转 `MobSkillId`，且解析 `skill<N>` 动画时长登记 `mi.setMobSkillAnimationTime`；⑩ attack<N> 节点：动画时长/conMP/attackAfter 打包 MobAttackInfoHolder；⑪ ban 节点→BanishInfo；⑫ noFlip 怪按 stand/0 origin 推 fixedStance（origin.x<1→5 否则 4）；⑬ `resolveMonsterVisualData` 找最终视觉来源后 `applyMonsterVisualStats` 回填移动类型/尺寸/bbox |
| `private static void setMonsterAttackInfo(int mid, List<MobAttackInfoHolder> attackInfos)` | 登记攻击信息 | 非空时逐条写 `MonsterInformationProvider.setMobAttackInfo/setMobAttackAnimationTime` |
| `public static int getMonsterLevel(int mid)` | 快速取等级 | 有缓存用缓存，否则只读 WZ level；加载失败 -1 |
| `private static void decodeElementalString(MonsterStats stats, String elemAttr)` | 解析元素串 | 每两字符一组：字符→Element、数字→Effectiveness |
| `public static NPC getNPC(int nid)` | 造 NPC | 名称缓存 miss 时读 String.wz Npc.img（缺省 MISSINGNO） |
| `public static String getNPCName(int nid)` / `public static String getNPCDefaultTalk(int nid)` | NPC 名/默认台词 | 后者读 `d0` 字段，缺省 "(...)" |
| `private static Data resolveUol(Data data)` | 解析 UOL 引用 | UOL 节点按字符串路径 `getChildByPath` 跳转（WZ "呼叫转移"） |
| `private static boolean isBboxAction(String name)` | 参与碰撞框的动作 | stand/move/fly/jump/attack/hit 前缀 |
| `private static BoundingBox buildMonsterBoundingBox(int mid, String mobName, Data monsterData)` | 多动作帧合并包围盒 | 遍历动作（跳过 info/die），帧 UOL 解析后取 lt/rb；缺失时用 origin+宽高推算（-origin 到 width-origin）；合并所有帧的外接矩形 |
| `private static Data resolveMonsterVisualData(int mid, Data monsterData)` | 沿 link 找可视数据源 | visitedMobs 防环；当前节点有主可视帧或可解析 bbox 即用之，否则跳 link 怪；都没有则回退自身（避免 bbox 退化导致距离误判） |
| `private static boolean hasVisualBoundingBoxSource(Data)` / `private static Data resolvePrimaryVisualFrame(Data)` / `private static Data resolveVisualFrame(Data)` | 可视源判定 | 主可视帧优先 fly/0 其次 stand/0，须 CANVAS 类型 |
| `private static void applyMonsterVisualStats(int mid, MonsterStats stats, Data visualMonsterData)` | 回填视觉属性 | fly 优先 → movetype=1 + 首帧宽高，否则 stand → movetype=0；bbox 合并有效则 setBbox，否则回退主可视帧 origin 推算 |
| `private static Data getMonsterData(int mid)` | 读怪物 WZ 根 | 11 位左补零 `.img` |
| `private static Set<Integer> getHpBarBosses()` | 血条 Boss 名单 | UI `UIWindow.img/MobGage/Mob` 子节点名 |
| 内部类 `BanishInfo`（msg/map/portal + getter）、`loseItem`（id/chance/x）、`selfDestruction`（action/removeAfter/hp） | 值对象 | 公有静态内部类 |
| 内部类 `BoundingBox`（minX/minY/maxX/maxY/valid，`update(Point lt, Point rb)` 合并） | 包围盒累加器 | BeiDou 扩展 |

## MobSkill

怪物技能对象：按 `MobSkillType` 对玩家/怪物施加状态、疾病、反伤、召唤、放逐、毒雾等效果；由 `MobSkillFactory` 从 `Skill.wz/MobSkill.img` 构建（Builder 模式）（源码路径：`src/main/java/org/gms/server/life/MobSkill.java`）。

**关键字段**：`id: MobSkillId`（type+level）、`mpCon`、`spawnEffect`、`hp`、`x`、`y`、`count`、`duration`、`cooltime`、`prop`（触发概率 0~1）、`lt/rb`（作用矩形）、`limit`（召唤上限）、`toSummon`。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static class Builder`（mpCon/spawnEffect/hp/x/y/count/duration/cooltime/prop/lt/rb/limit/toSummon/build） | 构建器 | toSummon 包装为不可变 List |
| `public void applyDelayedEffect(Character player, Monster monster, boolean skill, int animationTime)` | 延迟生效 | 动画时长后经 OverallService 注册动作：怪物仍存活才 `applyEffect` |
| `public void applyEffect(Monster monster)` | 简化入口 | `applyEffect(null, monster, false, emptyList)` |
| `public void applyEffect(Character player, Monster monster, boolean skill, List<Character> banishPlayersOutput)` | 技能生效核心 | ① `makeChanceResult` 概率不过直接返回；② switch(type)：攻击/魔攻/防御/魔防（含 _M 群体与 PAD/MAD/PDR/MDR 变体）→ 组 MonsterStatus；HEAL_M → 治疗；SEAL/DARKNESS/WEAKNESS/STUN/CURSE/POISON/SLOW/SEDUCE/REVERSE_INPUT/UNDEAD → 疾病；DISPEL → 驱散玩家 buff；BANISH → 收集放逐玩家（输出参数）；AREA_POISON → 毒雾；物免/魔免互斥检查后施加；三系反伤 → WEAPON/MAGIC_REFLECT+IMMUNITY=10 且记录反伤值；ACC/EVA/SPEED/SEAL_SKILL → 状态；SUMMON → 召唤；③ 有状态走 `applyMonsterBuffs`、有疾病走 `applyDisease`（均区分 lt/rb+skill 的范围模式与单体模式） |
| `private void applyHealEffect(boolean skill, Monster monster)` | 治疗 | 范围模式给框内所有怪回血（x/1000 × 950~2000 随机），否则只回自身 |
| `private void applyDispelEffect(boolean skill, Monster monster, Character player)` | 驱散 | 范围模式驱框内所有玩家，否则驱攻击者 |
| `private void applyBanishEffect(boolean skill, Monster monster, Character player, List<Character> banishPlayersOutput)` | 放逐收集 | 范围收集框内玩家，否则收集攻击者（由调用方执行放逐） |
| `private void spawnMonsterMist(Monster monster)` | 毒雾 | lt/rb 平移成矩形生成 `Mist`，持续 x×100ms |
| `private void summonMonsters(Monster monster)` | 召唤 | 道场不限量；图上怪 <80；`countAvailableMobSummons` 限额；洗牌取前 N；Boss Rush 图禁掉落；对扎昆球/血色恶魔等特判出生坐标，并按地图（时间塔起源/皮亚努斯洞窟）夹取 x 坐标；LOW_DARKSTAR 用 spawnFakeMonster，其余 spawnMonsterWithEffect；登记 `addSummonedMob` |
| `private void applyMonsterBuffs(Map<MonsterStatus, Integer> stats, boolean skill, Monster monster, List<Integer> reflection)` | 怪物 buff | 范围给框内所有怪或单体 `applyMonsterBuff` |
| `private void applyDisease(Disease disease, boolean skill, Monster monster, Character player)` | 疾病 | 范围模式：框内无神圣之盾(HOLY_SHIELD)者中疾病；SEDUCE 限 count 人；单体直接给攻击者 |
| `private List<Character> getPlayersInRange(Monster monster)` / `private List<MapObject> getObjectsInRange(Monster, MapObjectType)` | 范围查询 | `calculateBoundingBox`（lt/rb 平移）后查图 |
| `public MobSkillId getId()` / `public MobSkillType getType()` / `getMpCon()` / `getHP()` / `getX()` / `getY()` / `getDuration()` / `getCoolTime()` | 取值 | — |
| `public boolean makeChanceResult()` | 概率判定 | `prop==1.0 \|\| Math.random() < prop` |

## MobSkillId

怪物技能标识 record：`(MobSkillType type, int level)`（源码路径：`src/main/java/org/gms/server/life/MobSkillId.java`）。

## MobSkillType

怪物技能类型枚举（WZ skill id 100~200）：增益类（100~115）、疾病类（120~129：SEAL/DARKNESS/WEAKNESS/STUN/CURSE/POISON/SLOW/DISPEL/SEDUCE/BANISH）、AREA_POISON(131)/REVERSE_INPUT(132)/UNDEAD(133)/STOP_POTION(134)/STOP_MOTION(135)/FEAR(136)、免疫反伤类（140~145）、属性增益类（150~157）、SUMMON(200)（源码路径：`src/main/java/org/gms/server/life/MobSkillType.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static Optional<MobSkillType> from(int id)` | ID 转枚举 | id 超出 [100,200] 返回 empty；流式匹配 getId |
| `public int getId()` | 取 WZ ID | — |

## MobSkillFactory

怪物技能工厂：从 `Skill.wz/MobSkill.img` 惰性加载 `MobSkill`，读写锁缓存（源码路径：`src/main/java/org/gms/server/life/MobSkillFactory.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static MobSkill getMobSkillOrThrow(MobSkillType type, int level)` | 取技能（不存在即抛） | `orElseThrow(IllegalArgumentException)`（LifeFactory 解析 skill 节点时用它暴露 WZ 数据错误） |
| `public static Optional<MobSkill> getMobSkill(MobSkillType type, int level)` | 取技能 | 读锁查缓存，miss 转 `loadMobSkill` |
| `private static Optional<MobSkill> loadMobSkill(MobSkillType type, int level)` | 解析并缓存 | 写锁双重检查；读 `<typeId>/level/<level>` 节点：mpCon、0..N 数字子节点为召唤列表、summonEffect、hp、x/y、count、time→duration、interval→cooltime（秒转毫秒）、prop/100、limit、lt/rb；Builder 构建（注意：mpCon 之外未设置 spawnEffect 字段，Builder.spawnEffect 未被调用）后入缓存 |
| `private static String createKey(MobSkillType type, int skillLevel)` | 缓存 key | `typeId + "" + level` |

## MobAttackInfo

怪物普攻附加效果值对象：是否致命一击（deadlyAttack）、烧 MP、疾病技能/等级、MP 消耗（源码路径：`src/main/java/org/gms/server/life/MobAttackInfo.java`）。

全为字段 + getter/setter（构造器 `MobAttackInfo(int mobId, int attackId)` 参数未使用）。

## MobAttackInfoFactory

怪物普攻附加效果工厂：从 `Mob.wz` attack 节点惰性解析 `MobAttackInfo`（源码路径：`src/main/java/org/gms/server/life/MobAttackInfoFactory.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static MobAttackInfo getMobAttackInfo(Monster mob, int attack)` | 取攻击信息 | key=`mobId+""+attack`；synchronized 双重检查；读怪 img（link 怪跟随）的 `attack<N>/info`：deadlyAttack 存在即 true、mpBurn、disease、level、conMP；无 attackData 返回 null（注意：此时也会 put null 占位） |

## MonsterDropEntry

单怪掉落条目值对象：`itemId/chance/Minimum/Maximum/questid` 公有 final 字段（源码路径：`src/main/java/org/gms/server/life/MonsterDropEntry.java`）。

## MonsterGlobalDropEntry

全局掉落条目值对象：在单怪条目基础上增加 `continentid`（大洲过滤，<0 全大陆生效）（源码路径：`src/main/java/org/gms/server/life/MonsterGlobalDropEntry.java`）。

## MonsterListener

怪物生命周期监听接口：`void monsterKilled(int aniTime)` / `void monsterDamaged(Character from, int trueDmg)` / `void monsterHealed(int trueHeal)`（源码路径：`src/main/java/org/gms/server/life/MonsterListener.java`）。SpawnPoint 用它感知死亡以安排下次重生。

## MonsterInformationProvider

怪物信息提供者（单例）：数据库驱动的掉落表缓存（单怪掉落/全局掉落/偷窃池/多重装备掉落）、攻击与技能动画时长登记、Boss 判定与名称查询（源码路径：`src/main/java/org/gms/server/life/MonsterInformationProvider.java`）。

**关键字段**：`dropCache: volatile DropCache`（drops/dropsChancePool/hasNoMultiEquipDrops/extraMultiEquipDrops 四个 ConcurrentMap，整体替换实现清缓存）、`globalDropStore`（快照 + 大洲惰性过滤）、`mobAttackAnimationTime/mobSkillAnimationTime/mobAttackInfo`、`mobBossCache/mobNameCache`。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static MonsterInformationProvider getInstance()` | 单例 | 饿汉实例 |
| `protected MonsterInformationProvider()` | 构造 | `reloadGlobalDrops` |
| `public final List<MonsterGlobalDropEntry> getRelevantGlobalDrops(int mapid)` | 取本图相关全局掉落 | 委托 GlobalDropStore（`mapId/100000000` 为大洲 ID；continentid<0 或相等即命中，按大洲缓存） |
| `private List<MonsterGlobalDropEntry> loadGlobalDrops()` / `private void reloadGlobalDrops()` | 加载全局掉落 | SQL `drop_data_global`（chance>0）；异常记 i18n 错误日志 |
| `public List<MonsterDropEntry> retrieveEffectiveDrop(int monsterId)` | 取生效掉落表 | 基础 drop 列表上叠加多重装备：`use_multiple_same_equip_drop` 开启且该怪有 Maximum>1 的装备条目时，按 [Minimum,Maximum] 随机复制额外条目（缓存于 extraMultiEquipDrops / hasNoMultiEquipDrops 负缓存） |
| `public final List<MonsterDropEntry> retrieveDrop(int monsterId)` | 取基础掉落 | `dropCache.drops.computeIfAbsent(loadMonsterDrops)`；SQL `drop_data`，异常返回空列表（loadMonsterDrops 内返回 null 时） |
| `private List<MonsterDropEntry> loadMonsterDrops(int monsterId)` | SQL 加载 | 按 dropperid 查 drop_data；异常记日志返回 null |
| `public final MonsterDropEntry retrieveRandomStealDrop(int monsterId)` | 随机可偷条目（神偷术） | 构建 chance 累积池 `dropsChancePool`（`allow_steal_quest_item` 关闭时排除任务/组队任务道具）；池空（无可偷）返回 null；`Math.random × 总权重` 后线性定位条目 |
| `public final void setMobAttackAnimationTime(int monsterId, int attackPos, int animationTime)` / `getMobAttackAnimationTime(...)` | 攻击动画时长 | Pair<monsterId, attackPos> 为 key，缺省 0 |
| `public final void setMobSkillAnimationTime(MobSkill skill, int animationTime)` / `getMobSkillAnimationTime(MobSkill)` | 技能动画时长 | 缺省 0 |
| `public final void setMobAttackInfo(int monsterId, int attackPos, int mpCon, int coolTime)` / `getMobAttackInfo(...)` | 攻击消耗/冷却 | key 打包 `(monsterId << 3) + attackPos`（attackPos 限 0~7，越界返回 null） |
| `public static ArrayList<Pair<Integer, String>> getMobsIDsFromName(String search)` | 按名搜怪 | 遍历 String.wz Mob.img 全部条目，名称包含（忽略大小写）即返回 |
| `public boolean isBoss(int id)` | Boss 判定 | 缓存 miss 时 `LifeFactory.getMonster(id).isBoss()`，异常按非 Boss 并记 warn |
| `public String getMobNameFromId(int id)` | 怪名查询 | 缓存 miss 时读 String.wz `Mob.img/<id>/name` |
| `public final synchronized void clearDrops()` | 清空掉落缓存 | 重载全局掉落 + 整体替换 DropCache |
| 内部类 `DropCache` / `GlobalDropStore` / `GlobalDropSnapshot` | 缓存结构 | DropCache 四表并发；GlobalDrop 快照 immutable + 大洲过滤惰性缓存 |

## SpawnPoint

怪物重生点：绑定一种怪 + 位置/朝向/平台，按 mobTime 控制重生节奏，通过 `MonsterListener` 感知死亡（源码路径：`src/main/java/org/gms/server/life/SpawnPoint.java`）。

**关键字段**：`monster`（模板 ID）、`mobTime`（重生间隔秒；<0 不重生；0 表示波次间隔 mobInterval）、`team`（CPQ 队伍）、`fh/f/pos`、`nextPossibleSpawn`、`mobInterval=5000ms`、`spawnedMonsters: AtomicInteger`、`immobile`、`denySpawn`。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public SpawnPoint(Monster monster, Point pos, boolean immobile, int mobTime, int mobInterval, int team)` | 构造 | 记录模板/位置/mobTime；`nextPossibleSpawn = 服务器当前时间` |
| `public int getSpawned()` | 当前存活数 | 计数器值 |
| `public void setDenySpawn(boolean val)` / `public boolean getDenySpawn()` | 禁止重生开关 | — |
| `public boolean shouldSpawn()` | 是否应重生 | denySpawn/mobTime<0/仍有存活 均不重生；到 `nextPossibleSpawn` 时间才 true |
| `public boolean shouldForceSpawn()` | 是否应强刷 | mobTime≥0 且场上无存活（地图重置场景） |
| `public Monster getMonster()` | 造怪 | `new Monster(LifeFactory.getMonster(id))` 拷贝构造；回填 pos/team/fh/f；计数 +1；注册 MonsterListener：死亡时 `nextPossibleSpawn = now + (mobTime>0 ? mobTime秒 : aniTime)` 并计数 -1；mobTime==0（即时波次）时 nextPossibleSpawn = now + mobInterval |
| `public int getMonsterId()` / `public Point getPosition()` / `public final int getF()` / `public final int getFh()` / `public int getMobTime()` / `public int getTeam()` | 取值 | — |

## PlayerNPCFactory

玩家 NPC 工厂：仅提供 scriptid 存在性校验（防止客户端崩溃）（源码路径：`src/main/java/org/gms/server/life/PlayerNPCFactory.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public synchronized static boolean isExistentScriptid(int scriptid)` | NPC.img 是否存在该 ID | `npcData.getData(scriptid + ".img") != null`；自定义玩家 NPC 区间（9901910+）须在定制 Npc.wz 中存在 |

## PlayerNPC

玩家 NPC（名人堂石像）：把玩家外观快照（发型/脸型/皮肤/装备）固化成地图上的 NPC 对象，含创建/删除/全图广播/排名体系（源码路径：`src/main/java/org/gms/server/life/PlayerNPC.java`）。

**关键字段**：`equips: Map<Short, Integer>`（装备位→道具）、`scriptId/face/hair/gender/job/skin/name/dir/FH/RX0/RX1/CY`（Lombok `@Getter`）、四个排名（world/overall/worldJob/overallJob）；静态：`availablePlayerNpcScriptIds`（各职业分支可用 scriptid 池）、`runningOverallRank/runningWorldRank/runningWorldJobRank`（发号器）、`npcService`（Spring `NpcService`，经 `ServerManager.getApplicationContext()` 反向获取）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public PlayerNPC(String name, int scriptId, int face, int hair, int gender, byte skin, Map<Short, Integer> equips, int dir, int FH, int RX0, int RX1, int CX, int CY, int oid)` | 全参构造 | job 固定 7777（开发者）；position=(CX,CY) |
| `public PlayerNPC(PlayernpcsDO npcDO, List<PlayernpcsEquipDO> equipDOList)` | DO 还原构造 | 全字段 Optional.ofNullable 兜底；overallJobRank 由 `GameConstants.getOverallJobRankByScriptId` 推导；装备列表转 equips |
| `public static void loadRunningRankData(int worlds)` | 启动时加载发号器 | 查全部 playernpcs 行：overall = 总数+1；各世界/世界职业排名取最大值+1 |
| `public int getWorldRank()` / `getOverallRank()` / `getWorldJobRank()` / `getOverallJobRank()` | 排名查询 | — |
| `public MapObjectType getType()` | 类型 | `MapObjectType.PLAYER_NPC` |
| `public void sendSpawnData(Client client)` | 出生包 | `spawnPlayerNPC` + `getPlayerNPC` |
| `public void sendDestroyData(Client client)` | 消失包 | `removeNPCController` + `removePlayerNPC` |
| `private static int getAndIncrementRunningWorldJobRanks(int world, int job)` | 世界职业排名发号 | computeIfAbsent(1) 后 getAndIncrement |
| `public static boolean canSpawnPlayerNpc(String name, int mapid)` | 是否可生成 | 同名同图不存在记录（查 playernpcs 表） |
| `public void updatePlayerNPCPosition(MapleMap map, Point newPos)` | 更新站位 | 回填 pos/RX0=+50/RX1=-50/CY/FH（findBelow）；SQL UPDATE playernpcs |
| `private static void fetchAvailableScriptIdsFromDb(byte branch, List<Integer> list)` | 拉取可用 scriptid | 分支长度：branch<26 为 100 否则 400；区间 `[PLAYER_NPC_BASE + branch*100, +len)`；查库排除已用；`PlayerNPCFactory.isExistentScriptid` 校验存在才入池（遇缺口即 break，最多 20 个）；倒序入 list（getNextScriptId 从尾部取） |
| `private static int getNextScriptId(byte branch)` | 取下一个 scriptid | 池空时先拉取；仍空返回 -1（无号可发） |
| `private static PlayerNPC createPlayerNPCInternal(MapleMap map, Point pos, Character chr)` | 创建落库 | ① 同名同图查重；② `GameConstants.getHallOfFameBranch(job, mapId)` 定分支；③ 取 scriptId；④ pos 为空时按地图类型选 `PlayerNPCPodium`/`PlayerNPCPositioner` 自动摆位；⑤ 组 `PlayernpcsDO`（外观/坐标/世界/排名发号/job 百位化）+ 已装备列表转 `PlayernpcsEquipDO`，交 `npcService.createPlayerNPC`（含 position 注入） |
| `private static List<Integer> removePlayerNPCInternal(MapleMap map, Character chr)` | 删除落库 | 按角色名（+可选 map）查 playernpcs；逐行删 playernpcs 与 playernpcs_equip；返回 [world, 受影响 map...] |
| `private static synchronized Pair<PlayerNPC, List<Integer>> processPlayerNPCInternal(MapleMap map, Point pos, Character chr, boolean create)` | 创建/删除分流 | synchronized 串行化 |
| `public static boolean spawnPlayerNPC(int mapid, Character chr)` / `(int mapid, Point pos, Character chr)` | 生成并广播 | 创建成功后对该世界全部频道地图 `addPlayerNPCMapObject` + 广播出生两包 |
| `private static PlayerNPC getPlayerNPCFromWorldMap(String name, int world, int map)` | 找同图同名石像 | 遍历世界 1 频道该图 PLAYER_NPC 对象，scriptId 须 < CUSTOM_DEV |
| `public static void removePlayerNPC(Character chr)` | 删除并广播 | 删库后对每个受影响地图全频道移除对象并广播消失 |
| `public static void multicastSpawnPlayerNPC(int mapid, int world)` | 给全服玩家生成石像 | 用 mock Client 逐个 `loadAndGetAllCharactersView` 的角色调 spawnPlayerNPC（初始化名人堂） |
| `public static void removeAllPlayerNPC()` | 清空全部 | 遍历 (world,map) 去重组合，全频道移除对象并广播；DELETE playernpcs / playernpcs_equip / playernpcs_field；`resetPlayerNpcMapData` |
| `public static void addPlayerNPCMapObject(MapleMap map)` | 地图加载时挂对象 | 查该图该世界的全部 PlayerNPC 逐个 `map.addPlayerNPCMapObject` |

## positioner.PlayerNPCPodium

领奖台（Podium）地图的玩家 NPC 自动摆位：三平台 × 可加密步长的矩阵排布，满员时全图重排（源码路径：`src/main/java/org/gms/server/life/positioner/PlayerNPCPodium.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `private static int getPlatformPosX(int platform)` / `getPlatformPosY(int platform)` | 平台基准坐标 | 平台 0/1/2 → x=-50/-170/70；y=-47（0 号）/40 |
| `private static Point calcNextPos(int rank, int step)` | 计算第 rank 个位置 | 平台 = rank/step，平台内序号 relativePos；x = 基准 + 100×序号/(step+1)，y = 平台 y（对 getGroundBelow 的 -7 偏移做 +7 抵消，见类注释） |
| `private static Point rearrangePlayerNpcs(MapleMap map, int newStep, List<PlayerNPC> pnpcs)` | 重排 | 按 calcNextPos 逐个 `updatePlayerNPCPosition`；返回末位+1 的空位 |
| `private static Point reorganizePlayerNpcs(MapleMap map, int newStep, List<MapObject> mmoList)` | 全图重排广播 | 按 scriptId 升序（生成历史序）；先全频道移除广播消失，重排后再全频道加回广播出生；返回新空位 |
| `private static int encodePodiumData(int podiumStep, int podiumCount)` | 编码状态 | `(count << 5) + step` 存于 WorldServer |
| `private static Point getNextPlayerNpcPosition(MapleMap map, int podiumData)` | 取下一位置（核心） | 满员（count ≥ 3×step）且 step 达 `playernpc_area_steps` 上限返回 null；满员未达上限则 step+1 触发全图重排；否则 count+1 直接算位 |
| `public static Point getNextPlayerNpcPosition(MapleMap map)` | 对外入口 | 读 `WorldServer.getPlayerNpcMapPodiumData`，经 `map.getGroundBelow` 落地 |

## positioner.PlayerNPCPositioner

普通地图的玩家 NPC 自动摆位：在地图区域网格扫描找互不重叠的落点，密度不足时逐级加密网格并可选全图重排（源码路径：`src/main/java/org/gms/server/life/positioner/PlayerNPCPositioner.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `private static boolean isPlayerNpcNearby(List<Point> otherPos, Point searchPos, int xLimit, int yLimit)` | 位置重叠检测 | 以两点为中心的 xLimit×yLimit 矩形相交即近 |
| `private static int calcDx(int newStep)` / `calcDy(int newStep)` | 网格步长 | dx=`playernpc_area_x/(step+1)`；dy=`area_y/2 + area_y/2^(step+1)`（step 越大越密） |
| `private static List<Point> rearrangePlayerNpcPositions(MapleMap map, int newStep, int pnpcsSize)` | 预排位置表 | 从 `playernpc_initial_x/y` 起按网格扫描 `map.getPointBelow`，排除重叠点，凑够 pnpcsSize 个即返回（供"重排预演"判断密度是否够） |
| `private static Point rearrangePlayerNpcs(MapleMap map, int newStep, List<PlayerNPC> pnpcs)` | 实际重排 | 同上网格扫描，依次给已有石像 `updatePlayerNPCPosition`，末位返回新空位 |
| `private static Point reorganizePlayerNpcs(MapleMap map, int newStep, List<MapObject> mmoList)` | 全图重排广播 | 同 Podium 版逻辑（按 scriptId 排序、全频道先删后加） |
| `private static Point getNextPlayerNpcPosition(MapleMap map, int initStep)` | 取下一位置（核心） | 收集现有石像位置；从 initStep 到 `playernpc_area_steps` 逐级：网格扫描找第一个不重叠落点，命中即返回（step 提升时写回 WorldServer）；每级失败后置 reorganize=true，且 `playernpc_organize_area` 开启时用预排表更新参照位置，命中后先走全图重排 |
| `public static Point getNextPlayerNpcPosition(MapleMap map)` | 对外入口 | 读 `WorldServer.getPlayerNpcMapStep(mapId)` 作为初始步长 |
