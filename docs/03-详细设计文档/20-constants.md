# 20-常量层（constants）详细设计

- 模块路径：`gms-server/src/main/java/org/gms/constants/`，含子包 `api`（2 类）、`game`（6 类）、`id`（4 类）、`inventory`（4 类）、`net`（2 类）、`skills`（53 类）、`string`（5 类）
- 类数量：76
- 依赖模块：
  - 本项目：`org.gms.client.*`（`Job`/`Disease`/`Character`）、`org.gms.config.GameConfig`（多个判定读取热更配置）、`org.gms.provider.*`（GameConstants 启动期扫 WZ）、`org.gms.net.opcodes.*`（`SendOpcode`/`RecvOpcode`/`Opcode`）、`org.gms.net.packet.OutPacket`、`org.gms.server.CashShop.CashItemFactory`、`org.gms.server.maps.FieldLimit`/`MapleMap`、`org.gms.server.quest.Quest`、`org.gms.util.*`（`Pair`/`I18nUtil`）、`org.gms.manager.ServerManager` + `org.gms.property.ServiceProperty`（CharsetConstants）
  - JDK：`java.text.NumberFormat`、`java.nio.charset.Charset`、`java.util.Base64` 等
- 总体说明：常量层是全服魔法数字/字符串的唯一权威来源。`GameConstants`（游戏规则判定）与 `ServerConstants`（版本与服务器级常量）最重要，逐常量/逐方法说明；`skills` 包 53 个类是纯技能 ID 常量表，按职业体系归类概述；`id`/`inventory`/`string`/`api`/`net` 逐类给出概要+关键常量/方法表。

---

# 一、game 包（6 类）

## GameConstants

游戏规则与判定的集大成者：职业树/转职规则、经验倍率成长表、名人堂（Hall of Fame）、goto 命令表、BGM 清单、默认键位、怪物血量表、各类「地图/技能/物品属于哪个系统」的判定函数（源码路径：`constants/game/GameConstants.java`）。

### 关键常量

| 常量 | 说明 |
| --- | --- |
| `WORLD_NAMES` | 21 个世界名（Scania、Bera……），世界创建/推荐世界时使用 |
| `stats` | WZ 装备扩展属性名数组（tuc/reqLevel/.../charmEXP） |
| `DEFAULT_BUDDY_GROUP = "Default Group"` | 好友默认分组名——BeiDou 客户端 C++ 字面量未本地化，服务端内存与 `buddies` 表必须引用此常量，否则 `changeGroup` 静默失败（加好友异常的根因） |
| `CASH_DATA` | 5 个现金数据物品 ID |
| `DROP_RATE_GAIN` / `MESO_RATE_GAIN` / `EXP_RATE_GAIN` | Ronan 倍率成长系统：按槽位取的掉落（线性）/金币（三角数）/经验（斐波那契）额外倍率 |
| `jobUpgradeBlob = {1,20,60,110,190}` | 各转职等级分界（0-4 转） |
| `jobUpgradeSpUp = {0,1,2,3,6}` | 洗点时按职业分支返还的 SP 增量 |
| `CPQ_DISEASES` | CPQ 可用的 8 种异常状态 |
| `MAX_FIELD_MOB_DAMAGE` | 地图障碍物伤害上限 = 启动时扫 `Map.wz/Obj` 全部 `mobdamage` 最大值 × 2 |
| `GOTO_TOWNS` / `GOTO_AREAS` | 玩家/GM `goto` 命令的别名 → 地图 ID 映射（36 城镇 / 19 区域） |
| `GAME_SONGS` | 170 首 BGM 路径（点唱机/换 BGM 命令白名单） |
| `DEFAULT_KEY/TYPE/ACTION`、`CUSTOM_KEY/TYPE/ACTION` | 官方默认键位与 HeavenMS 自定义键位三件套 |
| `mobHpVal` | 1-200 级怪物参考 HP 表（怪物生成兜底） |
| `MAX_CLEAN_PACK_SIZE = 101` | 干净封包最大尺寸 |
| `goldrewards` | 金枫叶抽奖奖池（itemId、权重的交替数组，共 47 项） |

### 方法表

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static int getPlayerBonusDropRate/MesoRate/ExpRate(int slot)` | 取玩家槽位的额外倍率 | 直接查三张 GAIN 表 |
| `public static int[] getCustomKey(boolean)` / `getCustomType(boolean)` / `getCustomAction(boolean)` | 取新角色键位方案 | true 走 CUSTOM，否则 DEFAULT |
| `public static String getJobName(int jobid)` | 职业 ID → 显示名（带缓存） | Job 枚举名去数字、首字母大写；未知返回 "" |
| `public static int getJobUpgradeLevelRange(int jobbranch)` | 各转职等级上限 | 查 jobUpgradeBlob |
| `public static int getChangeJobSpUpgrade(int jobbranch)` | 转职 SP 补偿 | 查 jobUpgradeSpUp |
| `public static boolean isHallOfFameMap(int)` / `isPodiumHallOfFameMap(int)` | 是否名人堂地图（含讲台） | 比对五大职业殿堂/骑士团殿堂三厅/战神殿堂常量 |
| `public static byte getHallOfFameBranch(Job, int mapid)` | 名人 NPC 的职业分支号 | 五大系 10-14、骑士团五职业 15-19、战神 20、龙神 21、初心系 22-24、其他 25；非殿堂地图按大区返回 `26 + 4×(mapid/1e8)`（每大区 400 个名人位） |
| `public static int getOverallJobRankByScriptId(int)` / `boolean canPnpcBranchUseScriptId(byte, int)` | 名人 NPC 脚本 ID 与排名/分支互算 | scriptId 百位即分支；≥26 的大区分支每 400 一档 |
| `public static int getHallOfFameMapid(Job)` | 按职业取应放置的殿堂 | 骑士团→KNIGHTS_CHAMBER、战神→PALACE；初心冒险家借用骑士团二厅 |
| `public static int getJobBranch(Job)` | 职业分支号（0-6+） | `id%1000==0`→0（初心）；`%100==0`→1（一转）；否则 `2 + id%10` |
| `public static int getJobMaxLevel(Job)` | 各分支等级上限 | 0→10、1→30、2→70、3→120；四转骑士团 120 其余 200 |
| `public static int getSkillBook(int job)` | 龙神技能书号 | 2210-2218 → 1-9，其余 0 |
| `public static boolean isAranSkills(int skill)` | 是否战神连击系技能 | 比对 Aran 类 12 个常量（双/三/满/超 swing、连击吸收等） |
| `public static boolean isHiddenSkills(int skill)` | 是否战神隐藏技能 | 4 个 HIDDEN_*_DOUBLE/TRIPLE |
| `public static boolean isCygnus(int job)` / `isAran(int job)` | 骑士团/战神判定 | `job/1000==1` / `job==2000 或 2100-2112` |
| `private static boolean isInBranchJobTree(int, int, int)` / `hasDivergedBranchJobTree(...)` | 职业树包含/分叉判定辅助 | 按 10^branchType 位比较 |
| `public static boolean isInJobTree(int skillId, int jobId)` | 技能是否属于该职业的技能树 | 以 `skillId/10000` 为技能职业，按 1/10/100/1000 位逐层判定，须 `技能职业 ≤ 当前职业` |
| `public static boolean isPqSkill(int skill)` | 是否 PQ 专属技能 | 20000014-18、10000013、20001013 及 `%1e7 ∈ [1009,1011] 或 ==1020` |
| `public static boolean bannedBindSkills(int skill)` | 禁止绑定快捷键的技能 | Aran 系或 PQ 系 |
| `public static boolean isGMSkills(int skill)` | GM 技能判定 | 9001000-9101008、8001000-8001001 |
| `public static boolean isFreeMarketRoom(int mapid)` | 自由市场房间 | `mapid/1000000==910 且 > FM_ENTRANCE` |
| `public static boolean isMerchantLocked(MapleMap)` | 禁用雇佣商店的地图 | FieldLimit.CANNOTMIGRATE 或 FM 入口图 |
| `public static boolean isDojoBossArea(int mapid)` | 道场 Boss 层 | 是道场且 `((mapid/100)%100)%6 > 0` |
| `public static boolean isAriantColiseumLobby/Arena(int mapid)` | Ariant 竞技场候场/竞技图 | 分支 980010 且尾号 0/1 |
| `public static boolean isPqSkillMap(int mapid)` | 允许用 PQ 技能的图 | 道场或奈特金字塔 |
| `public static boolean isFinisherSkill(int skillId)` | 英雄终结技 | 1111003-1111006 区间及骑士团 11111002/11111003 |
| `public static boolean isMedalQuest(short questid)` | 是否勋章任务 | `Quest.getMedalRequirement() != -1` |
| `public static boolean hasSPTable(Job)` | 是否用分表 SP（龙神） | EVAN~EVAN10 十个分支 |
| `public static int getMonsterHP(int level)` | 按等级取参考 HP | 越界返回 `Integer.MAX_VALUE` |
| `public static String ordinal(int i)` | 英文序数（1st/2nd/3rd） | 11-13 特判 th |
| `public synchronized static String numberWithCommas(int i)` | 千分位格式化 | `NumberFormat(UK)` |
| `public synchronized static Number parseNumber(String value)` | 解析 WZ 数值字符串 | 按 `use_unit_price_with_comma` 选 FRANCE/UK Locale（逗号小数兼容）；失败返回 0.0f |
| `private static int getMaxObstacleMobDamageFromWz()` | 启动期扫全 WZ 地图障碍物最大伤害 | 遍历 `Map.wz/Obj` 全部三层节点读 `s1/mobdamage` 取最大（类加载时执行一次） |
| `public static Pair<byte[], byte[]> getEnc()` | 取两段 Base64 装饰串（资源站点地址类混淆数据） | 硬编码字节经 Base64 解码返回 |
| `public static int selectRandomReward(int[] rewards)` | 按 `[itemId, weight, ...]` 交替数组加权抽取 | 权重展开成列表后等概率取（金枫叶奖池用） |

## ServerConstants（net 包，此处一并详述）

服务器级核心常量：协议版本、北斗版本号、屏蔽名与全服广播模板（源码路径：`constants/net/ServerConstants.java`）。

| 常量 | 类型 | 说明 |
| --- | --- | --- |
| `VERSION = 83` | `short` | GMS 协议版本号——登录握手（`getHello`）、opcode 表选择的依据，改动即客户端不兼容 |
| `DEBUG_VALUES` | `int[10]` | 封包调试用的 10 个可写槽 |
| `BLOCKED_NAMES` | `String[]` | 建角屏蔽名（admin/gm/歧视与脏词等 50+） |
| `LEVEL_200` | `String` | 满级全服广播模板（`%s` 角色名 ×2、`%d` 等级） |
| `BEI_DOU_VERSION = "1.12"` | `String` | 北斗发行版本号（启动横幅/Swagger 展示） |
| `BEI_DOU_BUILD_TIME` | `String` | 构建时间戳（随 CI 写入） |

## CommodityFlag

商城商品（Commodity）字段编码枚举：每个枚举值 = flag 位掩码 + 协议写入顺序号 + 中文说明 + `BiConsumer<OutPacket, Number>` 写入器——`PacketCreator.writeModifiedCashItem` 反射匹配 DO 字段后按本表顺序编码（源码路径：`constants/game/CommodityFlag.java`）。

| 枚举值（代表） | flag / sort | 写入逻辑 |
| --- | --- | --- |
| `SN(0,0)` / `FLAG(0,1)` | 固有部分 | writeInt（FLAG 为各字段位掩码之和，由编码器汇总） |
| `ITEM_ID(1<<0,2)`、`COUNT(1<<1,3,writeShort)`、`PRICE(1<<2,5)`、`BONUS(1<<3,6)`、`PRIORITY(1<<4,4)`、`PERIOD(1<<5,7)`、`MAPLE_POINT(1<<6,8)`、`MESO(1<<7,9)`、`FOR_PREMIUM_USER(1<<8,10)`、`COMMODITY_GENDER(1<<9,11)`、`ON_SALE(1<<10,12)`、`CLASS(1<<11,13)`、`LIMIT(1<<12,14)`、`PB_CASH/PB_POINT/PB_GIFT(1<<13..15,15-17)` | 自定义部分 | 各自宽度写入 |
| `PACKAGE_SN(1<<16,18)` | 礼包 SN | 从 `CashItemFactory.getPackage` 取礼包内物品数 + 各 SN 写入 |
| `REQ_POP/REQ_LEVEL/TERM_START/.../MILEAGE_RATE/ALL` | sort=-1 | v83 不支持，编码时被 `getAvailableSortedValues` 过滤 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static List<CommodityFlag> getAvailableSortedValues()` | 取 v83 可用且按协议顺序排列的字段 | 过滤 sort==-1 或 desc 为 Unknown83 的项，按 sort 升序 |

## DelayedQuestUpdate

任务延迟更新类型枚举：`UPDATE`（进度更新）/ `FORFEIT`（放弃）/ `COMPLETE`（完成）/ `INFO`（infoNumber 更新），配合 `Character.announceUpdateQuest` 批量刷新任务面板（源码路径：`constants/game/DelayedQuestUpdate.java`）。纯枚举，无方法。

## ExpTable

升级经验表：内置角色（1-200 级）、装备、宠物、骑宠四张整数经验表（源码路径：`constants/game/ExpTable.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static int getExpNeededForLevel(int level)` | 角色升到 level+1 所需经验 | level>200 固定 20 亿（满级墙） |
| `public static int getEquipExpNeededForLevel(int level)` | 装备升级经验 | equip 表（1-30 级，终值 10000） |
| `public static int getTamenessNeededForLevel(int level)` | 宠物升级亲密度 | pet 表（末位 INT_MAX） |
| `public static int getMountExpNeededForLevel(int level)` | 骑宠升级经验 | mount 表（30 项） |
| `public static int getMountMaxLevel()` | 骑宠最高等级 | mount.length |

## NextLevelType

BeiDou 链式对话类型枚举：每个值携带对应的 NPCConversationManager 方法名字符串（`sendNextLevel`、`sendLastLevel`、`sendLastNextLevel`、`sendOkLevel`、`sendSelectLevel`、`sendNextSelectLevel`、`getInputNumberLevel`、`getInputTextLevel`、`sendAcceptDeclineLevel`、`sendYesNoLevel`），`NextLevelContext` 记录后由 `NPCScriptManager.nextLevel` 分发（源码路径：`constants/game/NextLevelType.java`）。

## NpcChat

NPC 对话文本工具常量：仅 `NEW_LINE = "\r\n"`（对话框换行符），私有构造防实例化（源码路径：`constants/game/NpcChat.java`）。

---

# 二、net 包（2 类）

## ServerConstants

见上文「game 包」中的详述（版本号 83、北斗版本、屏蔽名等）。

## OpcodeConstants

opcode 名称索引：启动时把 `SendOpcode`/`RecvOpcode` 枚举导出为「值 → 名称」Map，供封包日志/调试输出可读化（源码路径：`constants/net/OpcodeConstants.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `sendOpcodeNames` / `recvOpcodeNames` | `Map<Integer, String>` | 出站/入站 opcode 值 → 枚举名 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static void generateOpcodeNames()` | 生成索引 | 按 `ServerConstants.VERSION` 分派：83 → init(Send/Recv)；其他版本抛 RuntimeException（不支援的版本） |
| `public static void init(Opcode[] sendValues, Opcode[] recvValues)` | 填充两张 Map | 逐枚举 `put(getValue(), getName())` |

---

# 三、api 包（2 类）

## ApiConstant

REST API 版本常量：`V1 = "v1"`、`LATEST = V1`。Controller 的 `@RequestMapping`/`@Tag` 统一引用；新增不兼容版本时加常量并把 `LATEST` 指过去（源码路径：`constants/api/ApiConstant.java`）。

## InformationType

REST 查询信息类型枚举（十类）：`CASH/CONSUME/EQP/ETC/INS/MAP/MOB/NPC/PET/SKILL`，每项携带小写 type 字符串；用于管理端按类型查询 WZ 资源信息（源码路径：`constants/api/InformationType.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static InformationType ofType(String type)` | 字符串转枚举 | 遍历比对 type，未匹配返回 null |

---

# 四、id 包（4 类）

## ItemId

物品 ID 常量全集（约 200 项 + 12 个判定方法），按业务分组：杂项/药水/椅子/飞镖子弹/新手装/卷轴/婚恋/商城/坐骑/捕捉道具等（源码路径：`constants/id/ItemId.java`）。

### 代表常量（分组）

| 分组 | 常量（=值） |
| --- | --- |
| 杂项 | `PENDANT_OF_THE_SPIRIT=1122017`、`HEART_SHAPED_CHOCOLATE=5110000`、`FISHING_CHAIR=3011000`、`GOLDEN_MAPLE_LEAF=4000313`、`PERFECT_PITCH=4310000` |
| 药水 | `WHITE_POTION=2000002`、`MANA_ELIXIR=2000006`、`ALL_CURE_POTION=2050004` |
| 椅子 | `RELAXER=3010000` ~ `FISHING_CHAIR=3011000` |
| 飞镖/子弹 | `SUBI_THROWING_STARS=2070000`、`BALANCED_FURY=2070018`、`BULLET=2330000` |
| 特殊卷轴 | `CHAOS_SCROll_60=2049100`、`WHITE_SCROLL=2340000`、`CLEAN_SLATE_1/3/5/20=204900x`、`VEGAS_SPELL_10/60=561000x` |
| 婚恋 | 订婚戒盒/戒 4031357-4031364、婚戒 `WEDDING_RING_MOONSTONE=1112803` 等 4 枚、婚礼门票 525100x、预约收据 4031375/4031376/4031480/4031481、请柬 4031377/4031395 |
| 商城 | `WHEEL_OF_FORTUNE=5510000`、`CASH_SHOP_SURPRISE=5222000`、经验/掉落券 5211xxx/5360xxx、`AP_RESET=5050000`、`NAME_CHANGE=5400000`、`WORLD_TRANSFER=5401000`、`VICIOUS_HAMMER=5570000` |
| 宠物装备 | `MESO_MAGNET=1812000`、`ITEM_POUCH=1812001`、`ITEM_IGNORE=1812007` |
| 坐骑 | `HOG=1902000`、银鬃/红龙、骑士团坐骑 1902005-1902007、鞍 1912000/1912005、`BATTLESHIP=1932000` |
| 永久宠物 | 粉豆/基诺/白虎/小雪人（5000060、5000100-5000102） |
| HPQ | 六色月妙种子 4001095-4001100 |

### 判定方法

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `isExpIncrease(int)` | 经验提升道具 | 2022450-2022452 |
| `isRateCoupon(int)` | 倍率券 | `/1000 == 5211 或 5360` |
| `isMonsterCard(int)` | 怪物卡 | `/10000 == 238` |
| `isPyramidBuff / isDojoBuff(int)` | 金字塔/道场 Buff 药 | 区间判定 2022585-88、2022616-17 / 2022359-2022421 |
| `isChair(int)` | 椅子 | RELAXER~FISHING_CHAIR 闭区间 |
| `allThrowingStarIds()` / `allBulletIds()` | 全部飞镖/子弹 ID 数组 | IntStream 区间 |
| `isPartyAllCure(int)` | 组队全治愈药 | 道场 2022433 或嘉年华 2022163 |
| `isPet(int)` | 宠物 | `/1000 == 5000` |
| `getPermaPets()` | 永久宠物 ID 数组 | 4 项 |
| `isWeddingToken(int)` / `isWeddingRing(int)` | 求婚信物/婚戒 | 4031357-4031364 区间 / 4 枚婚戒枚举 |
| `isNxCard(int)` / `isCashPackage(int)` | NX 卡/现金礼包 | 4031865/4031866 / `/10000==910` |
| `isFaceExpression(int)` | 表情道具 | 5160000-5160014 |
| `getOwlItems()` | 猫头鹰热搜 10 件套 | 工作手套/镖/粉披风/卷轴等 |
| `isExplorerMount(int)` / `isCygnusMount(int)` | 冒险家/骑士团坐骑 | 区间+鞍 |
| `getGender(int itemId)` | 装备性别要求（0 男/1 女/2 通用） | 非装备（`/1e6!=1`）返回 2；装备取 `/1000%10` |

## MapId

地图 ID 常量全集（约 130 项 + 9 个判定方法）：特殊图、全部主城、航线、战神/骑士团新手剧情、活动图、道场、迷你副本、Boss 图、婚礼图、名人堂等（源码路径：`constants/id/MapId.java`）。

### 代表常量（分组）

| 分组 | 常量（=值） |
| --- | --- |
| 特殊 | `NONE=999999999`、`GM_MAP=180000000`、`JAIL=300000012`、`DEVELOPERS_HQ=777777777`、`BEIDOU_BEGINNER=4`（BeiDou 新手出生图）、`MUSHROOM_TOWN=10000` |
| 主城 | 各岛 30+ 城镇（`HENESYS=100000000`、`ORBIS=200000000`、`LEAFRE=240000000`、`MUSHROOM_SHRINE=800000000` 等） |
| 航线 | Lith↔Rien 200090060/70、Ellinia↔Ereve 200090030/31、Ereve↔Orbis 200090020/21 |
| 战神剧情 | `ARAN_TUTO_1~4=91409001x`、`ARAN_POLEARM=914090100`、`ARAN_MAHA=914090200` |
| 骑士团剧情 | `CYGNUS_INTRO_LEAD/...=91304010x`（六段） |
| 活动 | 椰子 109080000、OX 109020001、体力 109040000、上台阶 109030x0x、雪球 109060000、寻宝 109010000 |
| 道场 | `DOJO_SOLO_BASE=925020000`、`DOJO_PARTY_BASE=925030000`、`DOJO_EXIT=925020002` |
| 婚礼 | `AMORIA=680000000`、教堂/礼拜堂祭坛 680000110/680000210、照相 680000300、出口 680000500 |
| 名人堂 | 五大职业殿堂（102000004 等）、骑士团四厅 1300001xx、战神殿堂 140010110 |

### 判定方法

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `isMapleIsland(int)` | 枫叶岛（新手岛） | 0-2000001 |
| `isGodlyStatMap(int)` | 战神开局神属性三图 | 914000200/10/20 |
| `isCygnusIntro(int)` | 骑士团剧情图 | 913040000-913040006 |
| `isPhysicalFitness(int)` / `isOlaOla(int)` | 体力/上台阶活动图区间 | 109040000-109040004 / 109030001-109030403 |
| `isSelfLootableOnly(int)` | 仅本人可拾取图 | 圣诞树 15 张 + GPQ 喷泉 3 张 |
| `isDojo(int)` / `isPartyDojo(int)` | 道场（全体/组队区） | 925020000-925033804 / 925030100 起 |
| `isBossRush(int)` | Boss 冲刺 | 970030100-970042711 |
| `isNettsPyramid(int)` | 奈特金字塔 | 926010100-926023500 |
| `isFishingArea(int)` | 钓鱼区 | 通往港口之路/海滩码头/和平号三图 |

## MobId

怪物 ID 常量（约 60 项 + 2 判定）：任务怪、Boss（扎昆/暗黑龙王/皮蛋/鱼王/帕普拉图斯/塔查狮蝎）、可捕捉怪、友方怪、道场 Boss（源码路径：`constants/id/MobId.java`）。

| 分组 | 常量（代表） |
| --- | --- |
| 任务 | `GREEN_MUSHROOM_QUEST=9101000` 等三组普通/暴走/任务版本 |
| Boss | 扎昆本体 8800000-8800002 + 八臂 8800003-8800010；暗黑龙王头/手/翼/腿/尾 8810000-8810009、尸体 8810010-8810017、真身 8810018；皮蛋 8820001；帕普拉图斯时钟 8500001；鱼王 8510000；塔查/狮蝎雕像与三阶段 9420541-9420549 |
| 捕捉 | `PHEROMONE_PERFUME=2270000` 对应的 `TAMABLE_HOG=9300101`、鬼魂、Ariant 蝎子、驯鹿等 |
| 友方 | `MOON_BUNNY=9300061`（HPQ）、罗密欧/朱丽叶、看门猪、巨大雪人六档 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static boolean isZakumArm(int mobId)` | 扎昆手臂 | 8800003-8800010 |
| `public static boolean isDeadHorntailPart(int mobId)` | 暗黑龙王尸体部件 | 8810010-8810017 |
| `public static boolean isDojoBoss(int mobId)` | 道场 Boss | 9300184-9300215 |

## NpcId

NPC ID 常量（约 25 项）：管理 NPC、扭蛋 NPC 连号区间、功能 NPC（源码路径：`constants/id/NpcId.java`）。

| 常量（=值） | 说明 |
| --- | --- |
| `CUSTOM_DEV=9977777` | 自定义开发 NPC |
| `MAPLE_ADMINISTRATOR=9010000` | 管理员 NPC（forceStartQuest 默认发起者） |
| `DIMENSIONAL_MIRROR=9010022` | 次元之镜 |
| `DUEY=9010009` / `FREDRICK=9030000` / `RPS_ADMIN=9000019` | 快递 / 雇佣商人 / 猜拳 |
| `GACHAPON_HENESYS=9100100` … `GACHAPON_NAUTILUS=9100117` | 12 个扭蛋 NPC 连号；`GACHAPON_MIN/MAX` 给出区间 |
| `PLAYER_NPC_BASE=9900000`、`BEI_DOU_NPC_BASE=9900001` | 名人 NPC / 北斗自定义 NPC 起始号 |

---

# 五、inventory 包（4 类）

## ItemConstants

物品分类与规则判定工具（约 40 个静态方法 + flag 位常量）：按 itemId 前缀判定物品类别、宠物装备槽位表、新建角色外观白名单（源码路径：`constants/inventory/ItemConstants.java`）。

### 关键常量

| 常量 | 说明 |
| --- | --- |
| `LOCK=0x01 / SPIKES=0x02 / KARMA_USE=0x02 / COLD=0x04 / UNTRADEABLE=0x08 / KARMA_EQP=0x10 / SANDBOX=0x40 / PET_COME=0x80 / ACCOUNT_SHARING=0x100 / MERGE_UNTRADEABLE=0x200` | 物品 flag 位（锁定/防滑/业报/防寒/不可交易/宠物召唤等） |
| `permanentItemids` | 永久物品集合（初始化装入 4 只永久宠物） |
| `Pet0Equip..Pet2ItemIgnore`（18 个 short） | 三个宠物位的装备/名牌/气泡/吸金/拾取/过滤槽位号（-114~-148） |
| `PET_EQUIP_SLOTS` / `PETS_NAME_TAG` | 上述槽位按宠物索引组装的不可变表 |

### 方法表（按用途分组）

| 分组 | 方法签名 | 关键逻辑 |
| --- | --- | --- |
| flag | `getFlagByInt(int type)` | 128→PET_COME、256→ACCOUNT_SHARING，其余 0 |
| 弹药 | `isThrowingStar/isBullet/isRechargeable(int)` | `/10000==207` / `==233` / 两者并集 |
| 消耗 | `isPotion/isFood/isConsumable(int)` | `/1000==2000`；2022/2010/2020；并集 |
| 箭矢 | `isArrowForBow/isArrowForCrossBow/isArrow(int)` | `/1000==2060` / `==2061` |
| 宠物 | `isPet(int)`、`isExpirablePet(int)`、`isPermanentItem(int)` | `/1000==5000`；配置 `use_erase_pet_on_expiration` 或蜗牛宠；查 permanentItemids |
| 贺年卡 | `isNewYearCardEtc/Use(int)` | `/10000==430` / `==216` |
| 装备类 | `isAccessory`（1110000-1140000）、`isTaming`（1902/1912）、`isOverall`（105）、`isWeapon`（1302000-1493000）、`isEquipment`（<2000000 且非 0）、`isMedal`（1140000-1143000）、`isFishingChair` | 区间/前缀判定 |
| 卷轴 | `isCleanSlate`（2049000-03）、`isModifierScroll`（防滑/防寒）、`isFlagModifier(scroll, flag)`（卷轴与 flag 匹配）、`isChaosScroll`（2049100-03）、`isTownScroll`（2030000-2030099） | 区间+特判 |
| 商城 | `isRateCoupon`、`isExpCoupon`、`isHiredMerchant`（503）、`isPlayerShop`（514）、`isCashStore`（503/514）、`isMapleLife`（543 且非 5430000）、`isNxCard` | 前缀判定 |
| 组队物品 | `isPartyItem(int)` | 2022430-33 / 2022160-63 |
| 背包归属 | `getInventoryType(int)` | `itemId/1000000 ∈ [1,5]` → EQUIP..CASH，带 `inventoryTypeCache` 缓存，未定义返回 UNDEFINED |
| 制造 | `isMakerReagent(int)` | `/10000==425` |
| 外观 | `isFace`（2/5）、`isHair`（3/4/6） | 前缀判定 |
| 建角白名单 | `isNewCharDefaultFace(job,gender,faceId)`、`isNewCharDefaultHair(gender,hairId)`、`isNewCharDefaultHairColor(int)`、`isNewCharDefaultSkinColor(int)`、`isNewCharDefaultTop/Bottom/Shoes/Weapon(...)` | 逐 ID 枚举比对（建角防作弊用）；`notValidHairColor`（>7 或 <0） |
| 宠物槽 | `isValidPetIndex(byte)` | 0-2 |

## EquipSlot

装备槽位枚举：WZ 的 islot 字符串 → 负数槽位号映射（`HAT("Cp",-1)` … `BELT("Be",-50)`），戒指占 -12/-13/-15/-16 四槽，`PET_EQUIP` 兜底（源码路径：`constants/inventory/EquipSlot.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public boolean isAllowed(int slot, boolean cash)` | 槽位是否属于本装备位 | allowed 数组比对；cash 物品槽位再减 100（现金装备镜像槽） |
| `public static EquipSlot getFromTextSlot(String slot)` | WZ islot 文本 → 枚举 | 遍历名匹配，未命中返回 PET_EQUIP |

## EquipType

装备类型枚举（34 项）：饰品/帽/披风/上衣/连身/裤/鞋/手套/盾/戒指/坐骑及 17 类武器（剑/斧/锤/短刀/杖/棒/双头系列/枪/矛/弓/弩/拳爪/枪械），值 = itemId 的万位（武器为千位）（源码路径：`constants/inventory/EquipType.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public int getValue()` | 取类型数值 | — |
| `public static EquipType getEquipTypeById(int itemid)` | 物品 → 装备类型 | `itemid/100000 ∈ {13,14}`（武器）按 `/1000` 查表，否则 `/10000`；未命中 UNDEFINED |

## PetEquipSlot

宠物装备槽位 record：`(equip, nameTag, chatBalloon, mesoMagnet, itemPouch, itemIgnore)` 六个 short 槽位的一组打包，配合 `ItemConstants.PET_EQUIP_SLOTS` 使用（源码路径：`constants/inventory/PetEquipSlot.java`）。纯数据记录，无方法。

---

# 六、string 包（5 类）

## CategoryType

商城/物品分类枚举（8 项）：`EVENT=1、EQUIP=2、USE=3、SET=4、ETC=5、PET=6、PACKAGE=7、MAIN=8`，名称经 `I18nUtil.getMessage("CategoryType.XXX")` 取多语言文案（源码路径：`constants/string/CategoryType.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static CategoryType ofId(int id)` | ID → 枚举 | 遍历比对，未命中 null |
| `public static String toName(int id)` | ID → 分类名 | ofId 后取 name，null 返回 "" |

## CharsetConstants

客户端语言-字符集映射：核心是私有枚举 `Language`（`LANGUAGE_US(2,"US-ASCII","en-US")`、`LANGUAGE_CN(3,"GBK","zh-CN")`、`LANGUAGE_PT_BR/THAI/KOREAN(-1,...)`），类加载时从 `ServiceProperty` 读服务器语言缓存（源码路径：`constants/string/CharsetConstants.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static Charset getCharset(int language)` | 语言代号 → 字符集 | `Language.fromLang(language).getCharset()`（中文走 GBK 写包） |
| `public static Locale getLanguageLocale(int language)` | 语言代号 → Locale | `Locale.forLanguageTag` |
| `public static boolean isZhCN()` | 服务器是否中文 | 缓存的 SERVICE_LANGUAGE == LANGUAGE_CN |
| `private static Language loadServiceLanguage()` | 启动时装载服务器语言 | zh-CN → CN，否则 US |
| `Language.fromLang(int)`（枚举方法） | 代号 → 语言 | 无匹配回退服务器语言（PT/TH/KR 代号 -1 不可达，仅占位） |

## ExtendKey

扩展值键名枚举（BeiDou）：目前仅 `ONLINE_TIME("每日在线时间")`——`getOnlineTime`/每日在线统计写入扩展表时使用的键（源码路径：`constants/string/ExtendKey.java`）。

## ExtendType

扩展值表类型枚举（BeiDou）：`ACCOUNT_EXTEND("11")`、`ACCOUNT_EXTEND_DAILY("12")`、`ACCOUNT_EXTEND_WEEKLY("13")`、`CHARACTER_EXTEND("21")`、`CHARACTER_EXTEND_DAILY("22")`、`CHARACTER_EXTEND_WEEKLY("23")`、`UNSUPPORTED("99")`，即 `extend_value.extend_type` 字段的取值域（源码路径：`constants/string/ExtendType.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static ExtendType getExtendType(String type)` | 字符串 → 枚举 | 遍历比对，未命中 UNSUPPORTED |
| `public static Map<String, Date> getCleanMap()` | 每日/每周类型的重置基准时间 | 今日 0 点 + 本周一 0 点四个 `Date`（供定时清零任务比对） |
| `public static boolean isAccount(String)` / `isCharacter(String)` | 账号级/角色级判定 | 11/12/13、21/22/23 |

## LanguageConstants

CPQ（怪物嘉年华）多语言文案表：13 组 `String[4]`（葡萄牙语/西班牙语/英语/中文索引 0-3），如 `CPQBlue/CPQRed`（蓝队/红队）、`CPQPickRoom`、`CPQEntryLobby`、`CPQLeaderNotFound` 等，静态块按语言填充（源码路径：`constants/string/LanguageConstants.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static String getMessage(Character chr, String[] message)` | 按玩家客户端语言取文案 | `message[chr.getClient().getLanguage()]` |

---

# 七、skills 包（53 类）

53 个类全部为「职业 → 技能 ID」纯常量表（`public static final int`，无方法），供 `PacketCreator`、各 Handler、脚本等以语义名引用技能 ID。技能 ID 的万位即职业 ID（如 5121009 = 5121 海盗四转 009 号技能）。按职业体系归类如下：

## 7.1 冒险家·战士系（10 类）

| 类 | 职业（转职段） | 代表常量 |
| --- | --- | --- |
| `Warrior` | 战士（1 转，1000） | `POWER_STRIKE=1000004`、`SLASH_BLAST=1000005`、`IRON_BODY` |
| `Fighter` | 剑客（2 转，1100） | `RAGE=1101006`、`POWER_GUARD`、剑/斧精通与终极 |
| `Crusader` | 斗士（3 转，1110） | `COMBO=1111002`、`SWORD/AXE_PANIC`、`SWORD/AXE_COMA`、`SHOUT` |
| `Hero` | 英雄（4 转，1120） | `BRANDISH=1121008`、`ADVANCED_COMBO=1120003`、`ENRAGE`、`HEROS_WILL` |
| `Page` | 准骑士（2 转，1200） | `THREATEN`、锤/剑精通与终极、`POWER_GUARD` |
| `WhiteKnight` | 骑士（3 转，1210） | 火/冰/雷六种属性充能（`SWORD_FIRE_CHARGE=1211003` 等）、`CHARGE_BLOW`、`MAGIC_CRASH` |
| `Paladin` | 圣骑士（4 转，1220） | `BLAST=1221009`、`HEAVENS_HAMMER`、`ADVANCED_CHARGE`、`SWORD/BW_HOLY_CHARGE` |
| `Spearman` | 枪战士（2 转，1300） | `HYPER_BODY=1301007`、枪/矛精通与终极、`IRON_WILL` |
| `DragonKnight` | 龙骑士（3 转，1310） | `SPEAR/POLE_ARM_CRUSHER`、`DRAGON_ROAR=1311006`、`SACRIFICE`、`DRAGON_BLOOD` |
| `DarkKnight` | 黑骑士（4 转，1320） | `BERSERK=1320006`、`BEHOLDER=1321007` 及光环/诅咒（1320008/09）、`RUSH`、`ACHILLES` |

## 7.2 冒险家·法师系（10 类）

| 类 | 职业 | 代表常量 |
| --- | --- | --- |
| `Magician` | 魔法师（2000） | `MAGIC_GUARD=2001002`、`MAGIC_CLAW`、`ENERGY_BOLT` |
| `FPWizard` / `FPMage` / `FPArchMage` | 火毒一路（2100/2110/2120） | `FIRE_ARROW`、`POISON_BREATH`；`POISON_MIST=2111003`、`EXPLOSION`、`ELEMENT_COMPOSITION`；`METEOR_SHOWER=2121007`、`PARALYZE`、`FIRE_DEMON`、`ELQUINES` |
| `ILWizard` / `ILMage` / `ILArchMage` | 冰雷一路（2200/2210/2220） | `COLD_BEAM`、`THUNDERBOLT`；`ICE_STRIKE=2211002`、`THUNDER_SPEAR`；`BLIZZARD=2221007`、`CHAIN_LIGHTNING`、`IFRIT`、`ICE_DEMON` |
| `Cleric` / `Priest` / `Bishop` | 牧师一路（2300/2310/2320） | `HEAL=2301002`、`BLESS`；`HOLY_SYMBOL=2311003`、`DISPEL`、`MYSTIC_DOOR`、`SUMMON_DRAGON`；`GENESIS=2321008`、`RESURRECTION`、`BAHAMUT`、`ANGEL_RAY` |

## 7.3 冒险家·弓手系（7 类）

| 类 | 职业 | 代表常量 |
| --- | --- | --- |
| `Archer` | 弓手（3000） | `CRITICAL_SHOT`、`DOUBLE_SHOT`、`ARROW_BLOW`、`FOCUS` |
| `Hunter` / `Ranger` / `Bowmaster` | 猎人/游侠/神射手（3100/3110/3120） | `ARROW_BOMB`、`SOUL_ARROW`；`STRAFE=3111006`、`ARROW_RAIN`、`PUPPET`、`INFERNO`；`HURRICANE=3121004`、`SHARP_EYES=3121002`、`BOW_EXPERT`、`PHOENIX` |
| `Crossbowman` / `Sniper` / `Marksman` | 弩手/狙击手/神弩手（3200/3210/3220） | `IRON_ARROW`；`BLIZZARD=3211003`（技能）、`ARROW_ERUPTION`；`SNIPE=3221007`、`PIERCING_ARROW`、`MARKSMAN_BOOST`、`FROST_PREY` |

## 7.4 冒险家·飞侠系（6 类）

| 类 | 职业 | 代表常量 |
| --- | --- | --- |
| `Rogue` | 飞侠（4000） | `LUCKY_SEVEN=4001344`、`DOUBLE_STAB=4001334`、`DARK_SIGHT=4001003` |
| `Assassin` / `Hermit` / `NightLord` | 刺客一路（4100/4110/4120） | `CLAW_MASTERY`、`CRITICAL_THROW`；`FLASH_JUMP=4111006`、`SHADOW_PARTNER=4111002`、`AVENGER`；`TRIPLE_THROW=4121007`、`SHADOW_STARS`、`VENOMOUS_STAR`、`NINJA_STORM` |
| `Bandit` / `ChiefBandit` / `Shadower` | 侠盗一路（4200/4210/4220） | `SAVAGE_BLOW=4201005`、`STEAL`；`MESO_EXPLOSION=4211006`、`MESO_GUARD`、`ASSAULTER`、`BAND_OF_THIEVES`；`BOOMERANG_STEP=4221007`、`SMOKE_SCREEN`、`ASSASSINATE`、`VENOMOUS_STAB` |

## 7.5 冒险家·海盗系（7 类）

| 类 | 职业 | 代表常量 |
| --- | --- | --- |
| `Pirate` | 海盗（5000） | `FLASH_FIST`、`SOMERSAULT_KICK`、`DOUBLE_SHOT`、`DASH=5001005` |
| `Brawler` | 拳手（2 转，5100） | `KNUCKLER_MASTERY`、`CORKSCREW_BLOW`、`DOUBLE_UPPERCUT`、`OAK_BARREL` |
| `Marauder` | 斗士（3 转，5110） | `ENERGY_CHARGE=5110001`、`ENERGY_BLAST/DRAIN`、`TRANSFORMATION=5111005`、`SHOCKWAVE`、`STUN_MASTERY` |
| `Buccaneer` | 船长（4 转，5120） | `SPEED_INFUSION=5121009`、`DEMOLITION`、`BARRAGE`、`SUPER_TRANSFORMATION`、`TIME_LEAP`、`DRAGON_STRIKE` |
| `Gunslinger` / `Outlaw` / `Corsair` | 火枪手三转（5200/5210/5220） | `GUN_MASTERY`、`RECOIL_SHOT`；`GAVIOTA`、`FLAME_THROWER`、`ICE_SPLITTER`、`HOMING_BEACON`；`BATTLE_SHIP=5221006`、`BATTLESHIP_CANNON/TORPEDO`、`RAPID_FIRE`、`BULLSEYE` |

## 7.6 骑士团（Cygnus，6 类）

| 类 | 职业 | 代表常量 |
| --- | --- | --- |
| `Noblesse` | 圣魂骑士团初心（10001000 段） | `THREE_SNAILS`、`ECHO_OF_HERO`、`BLESSING_OF_THE_FAIRY=10000012` 及坐骑系列 |
| `DawnWarrior` | 魂骑士（11xxx） | `SOUL_BLADE`、`COMBO=11111001`、`BRANDISH`、`SOUL_DRIVER`、`SOUL_CHARGE` |
| `BlazeWizard` | 炎术师（12xxx） | `FLAME`、`FIRE_PILLAR`、`FIRE_STRIKE`、`METEOR_SHOWER=12111003`、`IFRIT`、`FLAME_GEAR` |
| `WindArcher` | 风行者（13xxx） | `STORM_BREAK`、`WIND_WALK=13101006`、`ARROW_RAIN`、`HURRICANE=13111002`、`WIND_PIERCING/SHOT` |
| `NightWalker` | 夜行者（14xxx） | `VAMPIRE=14101006`、`POISON_BOMB`、`FLASH_JUMP`、`TRIPLE_THROW=14110005`、`VENOM` |
| `ThunderBreaker` | 雷霆使者（15xxx） | `SHARK_WAVE=15111007`、`SPEED_INFUSION=15111005`、`LIGHTNING_CHARGE`、`SPARK`、`TRANSFORMATION` |

## 7.7 战神/龙神/传奇（3 类）

| 类 | 职业 | 代表常量 |
| --- | --- | --- |
| `Legend` | 传奇初心（20001000 段） | 基础生活技 + `TUTORIAL_SKILL1-5=20000014-18`（新手教程连击/暴击，`isPqSkill` 区间一部分） + 坐骑 |
| `Aran` | 战神（21xxx，按 1-4 转分 2100/2110/2111/2112） | 连击体系：`DOUBLE_SWING=21000002`、`TRIPLE_SWING`、`FULL_SWING=21110002`、`OVER_SWING=21120002`、`COMBO_ABILITY/DRAIN/SMASH/FENRIR/TEMPEST/BARRIER`、隐藏技能 `HIDDEN_FULL_DOUBLE=21110007` 等 4 个、`FREEZE_STANDING` |
| `Evan` | 龙神（20xxx/22xxx，10 段成长） | 专属坐骑式技能树：`MAGIC_MISSILE`、`FIRE_CIRCLE`、`LIGHTNING_BOLT`、`ICE_BREATH`、`FIRE_BREATH`、`DARK_FOG=22181002`、`BLAZE`、`SOUL_STONE`、`MAPLE_WARRIOR=22171000` 等 45 个常量（数量最多） |

## 7.8 GM 类（2 类）与初心通用（1 类）

| 类 | 说明 | 代表常量 |
| --- | --- | --- |
| `Beginner` | 冒险家初心（1000 段通用技能） | `THREE_SNAILS=1001`、`RECOVERY`、`NIMBLE_FEET`、`MONSTER_RIDER=1004`、`ECHO_OF_HERO=1005`、`SPACESHIP/SPACE_DASH`、雪人/扫帚/蝙蝠坐骑 |
| `GM` | GM 技能（9001/9101 段） | `HIDE=9001004`、`GM_ROAR1/2`、`GM_TELEPORT1/2`、`RESURRECTION=9001005`、`HYPER_BODY=9001008`、`HASTE=9101000`、`BLESS` |
| `SuperGM` | 高级 GM 技能 | `HEAL_PLUS_DISPEL=9101000`、`SUPER_DRAGON_ROAR=9001001`、`HIDE=9101004`、`HOLY_SYMBOL`、`RESURRECTION` |

### skills 包类数合计

战士系 10 + 法师系 10 + 弓手系 7 + 飞侠系 7 + 海盗系 7 + 骑士团 6 + 战神/龙神/传奇 3 + 通用/GM 3 = **53 类**，与目录实际文件数一致。
