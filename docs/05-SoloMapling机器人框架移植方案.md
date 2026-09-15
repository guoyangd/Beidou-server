# 05 · SoloMapling 机器人框架移植方案（含影响评估与测试应对）

> 目标：将 SoloMapling 的"人造玩家"（Bot）框架移植进 Beidou-server，使单机世界呈现约 2,400 个拟真 Bot。
> 本文档给出：资产清单 → 分步实施方案 → **每步的影响评估与测试应对** → 总体测试策略 → 风险登记册 → 回滚方案。

## ✅ 实施进度（2026-09-15 更新，分支 feature/bot-port）

| 步骤 | 状态 | 备注 |
|---|---|---|
| S0 分支 / S1 依赖 / S2 机械移植 / S4 Flyway 种子 | **完成** | 263 类 + 12 gm4 命令 + 4 基座新文件 + 934 数据文件；种子 `V1.11.9__bot_framework_seed.sql`（模板 CID=10000） |
| S3-B 基座 API 拓宽 | **完成** | 全部为增量式（新增方法/重载/可见性），`mvn compile` 零错误 |
| S2 单测验证 | **完成** | 27/27 通过（含 GCMove 导航图烘焙、世界图扫描 >1000 图——已验证与 Beidou WZ 数据兼容） |
| S3-C Bot 守卫 | **完成** | Monster（怪控排除×2 + 经验路由）、MapleMap（addPlayer 事件钩子 + 广播/可见性 Bot 排除）、Trade（完成路径守卫 + 回调 + 队列清理）、GeneralChat/Messenger/PartyOperation/PlayerInteraction/MovePlayer 五个 Handler 钩子、Client（Boss 血条早退）、TimeoutTask（跳过 Bot）、NPC（版本过滤）、Shop（赌场筹码） |
| S5 启动接线 | **完成** | `ServerManager.run()` 尾部挂 `initHeadlessBotClient` + 延迟填充（`game_config` 键 `bot_spawn_on_startup`，默认 **false**，种子见 `V1.11.10__bot_config.sql`）；预加载挂入 `Server.init` 虚拟线程段；gm4 注册 10 个 Bot 命令（!bot/!move/!env 等） |
| S6-S9 冒烟/灰度 | **完成** | 本地 docker 实跑通过：9 波全部完成、1185 Bot 初始化 12.1s、0 YamlException；管理后台 8686 正常（gms-ui 已构建入 fat jar） |
| 运行时修复 | **完成** | 幽灵清理误删 Bot（setEnteredChannelWorldWithoutPartyWorld）、满员拒绝登录（getRealPlayerCount 排除 Bot）、组队功能保留（canRecvPartySearchInvite 保持 true） |
| 数量调整 | **完成** | 硬编码削减：Wave1-7→50%、Wave8→30%、Wave9→50%（约 2400 → 1185）；FM 抽房 50%、TownPresence 减半 |
| Bot 人名 | **完成** | 252 个金庸人物名池 + 天干后缀防重名（郭靖乙/丙…） |
| R10 对话汉化 | **完成（20/20）** | 全部 20 个对话包中文翻译，累计约 12800 句；分 7 批提交（4fab0b7…02c9b03）；含部署包 `beidou-bot-deploy.tar.gz` |

**有意跳过的 Δbots 项**（保持 Beidou 原味，运行 Bot 不依赖）：Character 的信使持久化实验（closePlayerMessenger 注释/disconnectUser）、`server_queue` 表清理（Beidou 无此表）、DatabaseConnection 池参数（Beidou 用 Druid）、RPS 猜拳重设计 + RPSActionHandler、ItemInformationProvider 的 GM 卷轴/水晶等级玩法改动、PlayerInteraction 的 findMarketPortal 一行改动。

**顺带修正的 Beidou 侧问题**：Warrior 一转技能 ID（1000000 系→1001000 系，v83 正确值，Bot 攻击档案依赖）；`MapleMap.getPortals()` 被注释而恢复。

**Beidou 侧加固**（无 Spring 环境的单元测试友好，生产行为不变）：`WZFiles`/`GameConfig`/`Server` 静态初始化在上下文未启动时安全降级。


---

## 0. 版本基准（本方案最关键的事实）

```
Cosmic 上游 fec53bc77（2026-02-03）
   ├── SoloMapling main@47b61b538 = fec53bc77 + Δbots（机器人框架 + 基座改动）
   └── Beidou-server（org.gms，Spring 化 + 汉化 + 自有修复；对照审计版本 1321f6120）
```

**三方基准关系已经建立**：SoloMapling 恰好从 `fec53bc77` 分叉（已用 `git merge-base` 验证），而 Beidou 仓库自带的《Cosmic对比与缺陷核查报告.md》正是对照 `fec53bc77` 写的。因此：

> **Δbots（SoloMapling 对基座的全部改动）= `git diff fec53bc77..47b61b538`，共 59 个文件、+5606/-158 行**（不含 soloMapling/ 新包）。
> 移植的本质 = 把 Δbots 以"三方合并"的方式套到 Beidou 的 `org.gms` 改名版基座上。

生成补丁的命令（移植期间反复使用）：

```bash
cd SoloMapling
git diff fec53bc77..47b61b538 -- src/main/java/<某个基座文件>   # 单文件补丁
git diff fec53bc77..47b61b538 -- src/main/java ':(exclude)src/main/java/soloMapling'  # 全量基座补丁
```

## 1. 移植范围

**纳入**（In scope）：
- `soloMapling/` 新包：263 个 Java 文件（ArtificialPlayer 195、FreeMarket 20、itemPool 19、server 19、Environment 5、Casino 2、MapVFX 1、根 2）
- 基座改动 Δbots：59 个文件（清单见 §2.2，按四类分级）
- 资源：41 个 YAML（对话包/装备池/城镇配置）+ 879 个移动录制数据（`movementDataPackets/` 的 .bin/.csv）
- 数据库种子：`fmbot` 账号 + 模板角色 CID=2（改写为 Flyway 迁移）
- 依赖：`com.esotericsoftware:yamlbeans:1.17`、`org.jgrapht:jgrapht-core/io:1.5.2`

**不纳入**（Out of scope）：
- SoloMapling 对 `config.yaml` 的非机器人改动（重生间隔调快等，可后续按需在 `game_config` 单独调）
- `Casino/WzXmlPatcher` 对 WZ XML 的回写补丁（阶段二再评估，涉及改写 Beidou 的 wz 文件）
- Bot 对话汉化（产品决策，可后续单独做）

**移植三原则**：
1. **隔离**：Bot 框架统一放 `org.gms.soloMapling` 包，不与 Beidou 既有代码交叉。
2. **开关默认关**：所有启动接线由 `bot.enabled` / `bot.spawn_on_startup` 控制，默认 false——未开启时行为与移植前完全一致。
3. **每步一个可 revert 的 commit**：任何一步出问题都能独立回退。

## 2. 资产清单

### 2.1 soloMapling/ 新包（263 文件，纯增量）

| 子包 | 文件数 | 职责 | 移植批次 |
|---|---|---|---|
| ArtificialPlayer（核心） | 195 | BotSM 状态机、20 种 Bot、BotGeneration、GCMoveSystem、BotGrindSystem、BotAttackSystem、BotTradeSystem、消息/装扮/闲聊 | 阶段一 |
| server/（基础设施） | 19 | BotTickService、EventBus、ExecutorServiceManager、MethodScheduler | 阶段一 |
| Environment/ | 5 | 9 波世界填充编排 | 阶段一 |
| itemPool/ | 19 | 装备元数据缓存、物品池、升级模拟（Bot 装扮依赖） | 阶段一 |
| FreeMarket/ | 20 | Bot 经济：假市场、商店生成、讨价还价 | 阶段二 |
| Casino/ + MapVFX/ | 3 | 赌场筹码配置、反应堆特效 | 阶段二 |
| 根（BotLogger 等） | 2 | 日志/调试工具 | 阶段一 |

### 2.2 基座改动 Δbots（59 文件）——按改动性质分四类

**A 类 · 纯新增文件（14 个，直接改名搬运）**

| 文件（SoloMapling 路径） | → Beidou 落点 | 行数 |
|---|---|---|
| `client/BotClient.java` | `org.gms.client.BotClient` | 82 |
| `server/maps/Rope.java` | `org.gms.server.maps.Rope` | 14 |
| `constants/game/CharacterStance.java` | `org.gms.constants.game.CharacterStance` | 78 |
| `client/inventory/BodyPart.java` | `org.gms.client.inventory.BodyPart` | 109 |
| `client/command/commands/gm4/*`（12 个命令类） | `org.gms.client.command.commands.gm4` | 合计约 4,100 |

**B 类 · API 拓宽（低风险：加方法/改可见性/加重载，不改既有行为）**

| 文件 | 改动量 | 内容摘要 |
|---|---|---|
| `net/server/channel/handlers/AbstractMovementPacketHandler.java` | +116 | 新增 `updatePositionBot`（Bot 移动解析，与玩家共用解析逻辑） |
| `server/maps/Foothold.java` `FootholdTree.java` `GenericPortal.java` `MapFactory.java` | 小 | 访问器/构造暴露，供导航图烘焙读取 |
| `net/packet/InPacket.java` `ByteBufInPacket.java` | 小 | 读取扩展（移动录制回放用） |
| `tools/PacketCreator.java` → `org.gms.util.PacketCreator` | +25 | 个别 Bot 用包构建重载 |
| `server/StatEffect.java` `client/Job.java` `SkinColor.java` `constants/skills/Warrior.java` `constants/inventory/ItemConstants.java` `EquipType.java` | 小 | 装扮/攻击档案所需的小改 |
| `server/life/NPC.java` `server/ItemInformationProvider.java` | 小 | NPC 外观版本、物品信息暴露 |

**C 类 · Bot 守卫（中风险：在真人代码路径上插入 `BotHelpers.isBot()` 分支）**

| 文件 | 改动量 | 守卫语义（isBot=false 时必须与原行为逐字节一致） |
|---|---|---|
| `server/life/Monster.java` | ~20 | ① 怪物控制权不分配给 Bot（Bot 无客户端不会回 MoveMonster，会冻怪）② Bot 经验路由（不禁用装备经验等） |
| `client/Character.java` | +343 | 大头是 **+245 行追加的 Bot 方法段**（第 9245 行区域）；另有 save/disconnect 路径的 isBot 守卫若干处 |
| `server/Trade.java` | +100 | Bot 参与交易：isBot 分支 + `private→public`（isLocked/getMeso）+ `setMesoBot` |
| `net/server/channel/handlers/`（GeneralChat / Messenger / PartyOperation / PlayerInteraction / RPSAction / PlayerLoggedin） | 各小 | 聊天/信使/组队/互动对 Bot 的适配 |
| `net/server/task/TimeoutTask.java` `server/Shop.java` `server/maps/MapItem.java` | 各小 | 超时/商店/掉落物对 Bot 的跳过逻辑 |
| `net/server/channel/Channel.java` `tools/DatabaseConnection.java` | 小 | 注册/连接侧小改（Beidou 已 Spring 化，按语义重写而非套补丁） |

**D 类 · 集成钩子（关键路径，共 5 处）**

| # | 钩子 | SoloMapling 位置 | Beidou 落点 | 作用 |
|---|---|---|---|---|
| 1 | 启动接线 | `net/server/Server.java:962-964`（init 尾部：initHeadlessBotClient + 延迟 1s 环境填充） | `org.gms.net.server.Server.init()` 尾部（`Server.java:678` 起）或 `ServerManager.run()` 末尾 | Bot 世界开机自启 |
| 2 | 预加载挂载 | `Server.java:890-891`（EquipMetadataCache / DesirableEquipList） | Beidou `Server.init()` 的虚拟线程预加载段（`Server.java:687-698`） | 装扮元数据就绪 |
| 3 | LOD 唤醒 | `server/maps/MapleMap.java:2427-2429`（addPlayer 内 publish MAP_ENTERED） | `org.gms.server.maps.MapleMap.addPlayer()`（`MapleMap.java:2406`） | 玩家进图秒醒 Bot |
| 4 | 移动录制 | `net/server/channel/handlers/MovePlayerHandler.java:37` | `org.gms...handlers.MovePlayerHandler` | 可选：录制新移动路径 |
| 5 | GM 命令注册 | `client/command/CommandsExecutor.java`（gm4 套件注册） | `org.gms.client.command.CommandsExecutor` | `!bot` `!move` `!env` 等运维命令 |

> D 类是"冲突面"最集中的地方，但每个都只有几行——因为 SoloMapling 与 Beidou 的 `Server.init()` / `addPlayer()` 在这些位置结构仍高度同源。

### 2.3 第三方依赖（已用 import 扫描确认）

| 依赖 | 用途 | Beidou 现状 | 处置 |
|---|---|---|---|
| `yamlbeans`（14 处引用） | 读 41 个 Bot YAML | **无**（有 snakeyaml） | 直接加依赖（零传递依赖、MIT 许可、改动最小）；后期可选迁 snakeyaml |
| `org.jgrapht`（core/io） | 导航图/世界图寻路 | 无 | 加依赖 |
| `slf4j` / `netty-buffer` / `org.w3c.dom` | — | 已有 | 无需处理 |

> 好消息：**soloMapling/ 框架代码零处引用 `YamlConfig`**——配置耦合只剩 Server.init 里一处 `SPAWN_BOTS_ON_STARTUP` 判断。

## 3. 阶段与步骤总览

```mermaid
flowchart LR
    subgraph P1["阶段一 · 核心可用（S0-S9）"]
        S0["S0 基线"] --> S1["S1 依赖"] --> S2["S2 机械移植"] --> S3["S3 基座合并"] --> S4["S4 种子数据"] --> S5["S5 启动接线"] --> S6["S6 单Bot冒烟"] --> S7["S7 移动引擎"] --> S8["S8 训练战斗"] --> S9["S9 灰度扩容"]
    end
    subgraph P2["阶段二 · 完整体验（S10-S11）"]
        S9 --> S10["S10 经济族Bot"] --> S11["S11 压测定版"]
    end
```

每步统一执行模板：**做什么 → 影响评估 → 测试应对 → 回滚**。守门规则：**上一步测试不全绿，不进下一步**。

---

## 4. 步骤详表（影响评估 + 测试应对）

### S0 · 基线准备

**做什么**：Beidou 建独立分支 `feature/bot-port`；跑通 `mvn test` 与本地启动；录制一份"移植前基线"（见测试应对）。

**影响评估**：零——纯分支操作，不碰任何文件。

**测试应对**（建立基线，供后续每步对比）：
- 自动化：`mvn test` 结果存档。
- 手测脚本化清单（后续每步复跑）：①注册/登录/选角/进图 ②打怪 5 只+升级 ③聊天/组队/NPC 对话/买药 ④下线重登数据完整（等级/背包/位置）⑤重启服务器数据完整。
- 记录启动耗时、堆内存、线程数快照。

**回滚**：删除分支即可。

### S1 · 依赖引入

**做什么**：Beidou `gms-server/pom.xml` 增加 `yamlbeans:1.17`、`jgrapht-core/io:1.5.2`。

**影响评估**：仅 classpath 增量，无代码改动。许可合规：yamlbeans（MIT）、JGraphT（EPL-2.0/LGPL-2.1 双许可）与 Beidou 的 AGPL 均兼容。副作用仅 fat jar 体积 +约 1MB。

**测试应对**：编译 + 全量 `mvn test`；启动一次确认无类冲突（SLF4J/Netty 版本与 Spring Boot 依赖管理的仲裁不受影响——这两个库无传递依赖）。

**回滚**：revert pom 一个 commit。

### S2 · 机械移植（新包 + 新文件 + 资源）

**做什么**：
1. `soloMapling/` 整包复制为 `org.gms.soloMapling`，批量改 import（`client.→org.gms.client.`、`net.server.→org.gms.net.server.`、`server.→org.gms.server.`、`tools.→org.gms.util.`、`constants/provider→org.gms.*`）。
2. 搬运 A 类 4 个基座新文件（BotClient/Rope/CharacterStance/BodyPart）到 §2.2 指定落点。
3. 资源拷贝：41 个 YAML 与 `movementDataPackets/`（879 个文件）进 `gms-server/src/main/resources`（保持相对路径）。
4. **移植 7 个既有单测**（`BotTickServiceTest`、`GCMoveBakeTest`、`GCWorldGraphTest`、`CoarseExecutorTest`、`TierDwellTest`、`BotSpotClaimsTest`、`SpotEstimateTest`）并适配包名——它们是后续所有步骤的守门资产。

**影响评估**：纯增量代码，无任何既有行为激活（BotClient 编译进基座但无人实例化）。风险点是机械改名的遗漏（静态导入如 `updatePositionBot`、`EquipType.getEquipTypeById` 容易漏）。

**测试应对**：
- 编译零错误是第一道门。
- 7 个移植单测全绿（surefire 需 `-Dwz-path` 指向 Beidou 的 wz 目录；注意 Beidou 的多语言 WZ 解析优先 `wz-zh-CN/`——`Map.wz` 不在其覆盖列表，单测要显式验证烘焙结果与基线一致）。
- 启动冒烟：行为与 S0 基线逐项一致（此时 Bot 完全未接线）。

**回滚**：删除新增目录/文件。

### S3 · 基座改动三方合并（全方案风险最高的一步，分三批）

**做什么**：按 §2.2 的 B→C→D 顺序，对每个文件执行：`git diff fec53bc77..47b61b538 -- <file>` 得补丁 → 对照 Beidou 同名文件（org.gms 改名版）人工套用 → 冲突按语义解决。

**批次与影响评估**：

| 批次 | 内容 | 影响评估 |
|---|---|---|
| 批次1（B 类 API 拓宽） | 加方法/重载/可见性 | **理论零行为影响**（只增不改）。风险=漏合或 Beidou 侧方法签名漂移导致编译错（编译器兜底） |
| 批次2（C 类 Bot 守卫） | 真人代码路径插入 `isBot()` 分支 | **核心风险**：isBot=false 路径必须与原逻辑等价。逐个守卫 review；重点文件 Monster（怪控分配）、Character（save/disconnect 守卫）、Trade |
| 批次3（D 类 5 个钩子） | 启动/进图/录制/命令注册 | 影响启动序列与 addPlayer 热路径（每次真人进图多一次 EventBus 发布，纳秒级）。此时 Bot 仍未启用，钩子是"空转"状态 |

**已知冲突热点及处置**：
1. `Character.java`（+343 行）：Beidou 把 `loadCharFromDB` 委托给 Spring `CharacterService`——SoloMapling 的改动**不含**该方法本体，冲突有限；save/disconnect 区的守卫按 Beidou 的 `CharacterSaveService` 路径语义重放（Bot 的 `isLoggedin()=false` + BotClient 空操作双重保险在 Beidou 同样成立，已核实两侧守卫模型一致）。
2. `Server.java`：Beidou 的 init 已 JDBC→Service 化，尾部追加钩子即可，不与既有内容交叠。
3. `DatabaseConnection.java`（±4 行）：Beidou 已改为 Spring DataSource——**不套补丁**，按语义确认 Bot 无 DB 连接需求即可（Bot 全内存）。
4. `PlayerShop.java`（+237 行，阶段二才需要）：Beidou 修过 hired-merchant 竞态，两边改了同一区域——**推迟到 S10 人工合并**，本步跳过。

**测试应对**：
- 每批合并后：①全量 `mvn test` ②S0 手测基线五项全过 ③`git diff` 自查（守卫点的 else 分支与 fec53bc77 原文逐行对照）。
- 批次2 加针对性验证：多真人同图打怪（验证怪控分配只落在真人）；真人交易全流程（验证 Trade 守卫不改变原行为）。
- 批次3 后：启动日志含 BotClient 初始化痕迹（或开关关闭时无痕迹——取决于 S5 是否已并入；建议 S3、S5 之间保持开关默认关）。

**回滚**：按批 revert（每批一组 commit）。

### S4 · 种子数据（Flyway 迁移）

**做什么**：将 SoloMapling 的 `db/data/162-fmbot-data.sql`（fmbot 账号 + CID=2 模板角色，语义为 INSERT IGNORE）改写为 Beidou 的 `V1.x.x__bot_framework_seed.sql`。

**影响评估与注意点**：
1. **模板 CID 占用冲突——源侧已解决**：原实现硬编码 CID=2，落在真实角色自增范围内（Beidou `V1.0.67__admin.sql` 的 admin 角色占用 id=1，运行过的库上 id=2 很可能已分给真实玩家，INSERT IGNORE 会静默跳过种子、导致 Bot 克隆到错误角色）。**SoloMapling 源码已完成修改**：新增常量 `SoloMaplingConstants.GameConstants.BOT_TEMPLATE_CID = 10000`（高于真实玩家预期上限 1000、低于内存 Bot ID 基数 20000），`BotGeneration.java` 两处引用改用常量，并新增种子变更集 `db/data/163-fmbot-template-cid.sql`（162 保持原样以稳定既有库的 Liquibase checksum）。**移植时此修改随 diff 自然继承**：Beidou 侧 Flyway 种子直接按 CID=10000 插入即可。
2. **列清单差异**：Beidou `characters` 表比 Cosmic 多 `reborns`、`PQPoints`、`lastLogoutTime`、`lastExpGainTime` 等列——INSERT 需按 Beidou 表结构补默认值；id 相关列已是 BIGINT（V1.11.7/8），模板数据不受影响。
3. 模板必须保持"素角色"：`gm=0`、`level=1`、无装备（Bot 克隆后另行装扮）——SoloMapling 原注释已强调，迁移时保留该约束注释。

**测试应对**：
- 启动自动迁移成功（Flyway 版本表新增记录）。
- SQL 断言：`SELECT id,name,gm,level FROM characters WHERE id=<模板CID>` 返回 fmbot 模板且 gm=0；`accounts` 有 fmbot 账号。
- 幂等性：重复启动不报错、不产生重复行。

**回滚**：删除两行种子数据（数据级回滚，无需回滚 schema）。

### S5 · 启动接线与配置开关

**做什么**：
1. `Server.init()` 尾部（或 `ServerManager.run()` 末尾，推荐后者——语义上"Spring 完全就绪后"）追加：`BotClientHandler.initHeadlessBotClient()` + 读开关决定是否延迟 1s 触发 `EnvironmentManager.environmentLoadStartup()`。
2. `EquipMetadataCache::initialize`、`DesirableEquipList::load` 挂入 initExecutor 预加载段（与 SkillFactory 并行）。
3. 开关落位：`bot.enabled` / `bot.spawn_on_startup` 进 `game_config`（config 表 type=server），默认 false。

**影响评估**：
- 开关关（默认）：与基线行为一致，仅多两个开关读取。
- 开关开：启动序列新增预加载任务（并行，秒级）+ 延迟 1s 的填充。冷启动耗时增加约 10-15s（按 SoloMapling 实测 9 波约 10s 折算 Beidou 硬件）。
- 选择 `ServerManager.run()` 落点的额外收益：`loadCharFromDB` 已走 Spring `CharacterService`，此时上下文必然就绪，规避时序风险。

**测试应对**：
- 开关关：启动 → 与 S0 基线对比（启动耗时/日志/手测五项）。
- 开关开（先用 50 Bot 小规模，见 S9 参数化）：日志出现 `initHeadlessBotClient` 与波次输出；启动后无异常刷屏。
- 反复重启 3 次：无状态残留（Bot 不落库，每次重建）。

**回滚**：开关置 false（配置级，秒级生效，无需回代码）。

### S6 · 单 Bot 冒烟（第一个"看得见"的里程碑）

**做什么**：移植的 GM 命令（`ArtificialPlayerCommand`，即 `!bot` 套件）手动生成 1 个 Bot。

**影响评估**：运行时首次真实激活 Bot 代码路径。风险= S2/S3 遗漏的语义差异在此集中暴露。

**测试应对**（逐项断言，全部通过才算过）：
1. **存在性**：`PlayerStorage.getCharacterByName(botIGN)` 可查到；真人客户端肉眼可见 Bot 站在地图上。
2. **包隔离**：服务器无异常（BotClient.sendPacket 空操作生效）；向 Bot 发私聊，服务端不崩溃。
3. **怪控隔离**：Bot 与真人同图刷怪，怪物仇恨/控制权只落真人（验证 C 类守卫）。
4. **不落库**：重启服务器 → `SELECT count(*) FROM characters WHERE name LIKE '<BotIGN模式>'` = 0。
5. **不占会话**：`SessionCoordinator` 无 Bot 会话记录；DB 连接池占用数与基线相同。
6. **移除**：`!bot remove` 后地图广播消失、PlayerStorage 清除。

**回滚**：`!bot remove`（运行时移除）或重启（全量消失）。

### S7 · GCMove 移动引擎验证

**做什么**：验证物理移动与导航图烘焙在 Beidou 的 WZ 数据上正确工作。

**影响评估**：导航图烘焙有首次进入地图的 CPU/内存开销（每图一次，之后缓存）。风险=WZ 数据差异导致烘焙异常或寻路错误。

**测试应对**：
- 自动化：S2 已移植的 4 个 GCMove 单测（Bake/WorldGraph/CoarseExecutor/TierDwell）全绿。
- 手测矩阵（`!move` 命令驱动）：平地图直线 / 上绳子 / 上梯子 / 下落 / 传送门跨图 `travel`——各验证 Bot 位置逐步变化且最终到达。
- 回归：真人移动不受影响（`updatePositionBot` 与玩家路径分离，但同文件——编译期已保证，运行期抽查录制的移动数据可回放）。
- WZ 多语言专项：确认烘焙读的是同一份 `Map.wz`（`wz-zh-CN` 不含 Map 覆盖，理论无影响——用单测输出图数量对比 SoloMapling 侧结果）。

**回滚**：不使用 `!move` 即无影响；引擎问题不阻塞 S8（S8 可先在录制回放模式跑）。

### S8 · TrainingBot 与战斗系统验证

**做什么**：生成少量训练 Bot，验证 LOD 双模式战斗。

**影响评估**：首次激活 `Monster.damage` 直调路径与抽象经验路径。已知 Beidou P0（负伤害漏洞，审计报告）**不在 Bot 路径上**（Bot 不走客户端输入的 `applyAttack`），但本步会高频触碰 `Monster` 锁与 `MapleMap.killMonster`——是并发压力的首次预演。

**测试应对**（核心断言）：
1. **观测模式**：真人站在 Bot 训练图 → 客户端看到 Bot 出招动画+伤害数字（真包）；怪物掉血/死亡/掉落与真人逻辑一致；Bot 经验增长（`!bot` 调试命令查看）。
2. **未观测模式**：真人远离 → 抓包/日志验证零攻击包；抽象经验按约 30 击杀/分累计。
3. **LOD 切换**：真人反复进出该图 10 次 → 两种模式正确来回切换，无卡死/无刷屏。
4. **看门狗**：构造卡死场景（如把 Bot 困在无怪区）→ 30s 自愈传送 / 60s 撤离生效。
5. **真人回归**：与 Bot 同图的真人打怪经验/掉落完全正常（Monster 守卫的终极验证）。

**回滚**：`!bot remove` 或关闭开关重启。

### S9 · 环境填充灰度（规模递增）

**做什么**：开启 `spawn_on_startup`，分档扩容：**50 → 300 → 1,000 → 2,390（全量 wave8 配置）**。扩容参数通过 EnvironmentManager 的波次配置裁剪（临时改常量或加参数）。

**影响评估**：全系统第一次承受 Bot 满载。影响面=线程数（虚拟线程+固定池）、堆内存（每 Bot 一个 Character 对象）、启动耗时、`PlayerStorage`/`MapleMap` 锁竞争。审计报告提及的 Beidou 既有并发缺陷（SessionCoordinator 非并发 Map 等）可能在此暴露——**Bot 不注册会话所以不直接触碰该 Map，但间接路径需观察**。

**测试应对**：
- 每档观察 24h（至少 4h）再升档，监控指标：
  - BotTickService tick 延迟 P99（目标 <500ms）与 governor 拉长比例（目标 <10% 时间处于节流）
  - 堆内存/Full GC 频率（对比 S0 基线增幅 <50%）
  - 线程总数、CPU 稳态
  - DB 连接池占用（断言：与基线相同——Bot 不占连接）
  - 死锁检测：`jstack` 定期采样
- 真人体验回归：每档下真人登录/打怪/移动延迟无感知劣化。
- 填充成功率断言：日志波次计数 = 配置数（如 2,390）；填充总耗时 <30s。
- 通过标准：全量档稳定运行 24h 无死锁、无内存泄漏趋势、无错误日志刷屏。

**回滚**：开关置 false 重启（清空全部 Bot，秒级恢复基线负载）。

### S10 · 阶段二：经济族 Bot（FM/交易/赌场/OPQ）

**做什么**：分四个独立小步移植激活：①FreeMarket 商人（含 `PlayerShop` +237 行的人工合并——与 Beidou 的 hired-merchant 竞态修复同区域，逐 hunk 语义合并+回归 Beidou 原修复）②BotTradeSM 交易（用 `TradeBotTestCommand` 驱动全状态机）③Blackjack 赌场④OPQ 副本编排。

**影响评估**：经济 Bot 会触碰 Beidou 修复过的敏感区（商人竞态、MTS/Duey 复制链——审计 P0）。**建议顺序上先修复审计报告中的经济类 P0 再激活对应 Bot 族**，否则 Bot 高频交易会放大可被真人利用的漏洞。

**测试应对**：
- 每族独立开关（波次配置裁剪），逐族启用逐族验证。
- FM：真人可浏览/购买 Bot 商店商品；服务器重启后商店重建。
- 交易：与 Bot 完成一次完整买卖（WAITING_RESPONSE→…→COMPLETED），中途取消走 DECLINE 路径，超时走 TIMED_OUT（可用调试命令快进）。
- 回归重点：**真人↔真人交易**在 PlayerShop/Trade 合并后完全正常（这是本步最重要的回归项）。
- 赌场/OPQ：功能手测 + 重启重建。

**回滚**：族级波次开关关闭重启。

### S11 · 压测定版

**做什么**：真人客户端（2-5 个）+ 全量 Bot 混合压测 48h；据结果定版容量参数（Bot 总量、governor 阈值、各档 tick 周期），写入 `game_config` 文档化。

**影响评估**：产出物是配置，不再改代码。

**测试应对**：即本步本身。定版标准=真人操作 P95 延迟对比基线增幅 <10%；CPU 稳态 <70%；无死锁/泄漏。

**回滚**：恢复 `game_config` 备份值。

---

## 5. 测试总体策略

### 5.1 测试金字塔

| 层 | 内容 | 时机 |
|---|---|---|
| 单元测试 | S2 移植的 7 个既有测试 + 建议新增：`BotHelpersTest`（isBot 判定）、`BotGenerationTest`（模板克隆字段）、导航图烘焙快照测试（固定若干图的边数/节点数基线） | 每次 commit |
| 集成冒烟 | 开关开/关双模式启动 + 手测五项基线 | 每步守门 |
| 专项断言 | S6-S8 的逐项清单（不落库/怪控隔离/LOD/包隔离） | 对应步骤 |
| 灰度与压测 | S9 规模阶梯 + S11 混合压测 | 阶段收尾 |

### 5.2 Beidou 原功能回归清单（每步复跑）

登录注册 → 选角进图 → 打怪升级 → 聊天/组队/信使 → NPC 对话/任务 → 背包/商店/仓库 → 交易 → 下线重登 → 服务器重启数据完整 → REST API（8686）在线查询正常。

### 5.3 监控与告警（S5 起常开）

| 指标 | 采集 | 告警阈值建议 |
|---|---|---|
| BotTickService tick 延迟 | BotLogger 周期输出 | P99 > 500ms |
| governor 节流时间占比 | BotTickService 统计 | > 10% |
| 堆内存 / Full GC | JVM 标准手段 | 基线 +50% |
| 活跃 Bot 数 vs 配置数 | CharacterStorage | 偏差 > 5%（泄漏/丢失） |
| DB 连接池活跃数 | Druid 监控 | 与基线差 > 2（Bot 不应占连接） |
| 真人包处理延迟 | PacketLogger 抽样 | 基线 +10% |

### 5.4 总体验收标准（DoD）

1. 开关关闭时：代码 diff 审查确认行为与移植前一致 + 全部回归通过。
2. 开关开启时：2,390 训练 Bot + 城镇氛围 Bot 稳定运行 24h；真人全程无感知劣化。
3. Bot 全生命周期验证：可见、可互动（交易/组队）、战斗真实、不落库、重启重建。
4. 所有单测与专项断言通过并纳入 CI。

## 6. 风险登记册

| # | 风险 | 概率 | 影响 | 应对（前文已内置） |
|---|---|---|---|---|
| R1 | 基座三方合并冲突/语义漂移（两年版本差） | 中 | 高 | fec53bc77 三方基准 + 逐批合并逐批回归（S3）；审计文档辅助 |
| R2 | 模板 CID 与存量角色冲突 | 已消除 | — | 源侧已改：`BOT_TEMPLATE_CID = 10000`（`SoloMaplingConstants`）+ 种子变更集 163；移植随 diff 继承，Beidou Flyway 按 10000 插入 |
| R3 | Beidou 既有 P0 漏洞被 Bot 放大（负伤害/商人竞态/复制链） | 中 | 高 | S10 前先修 P0；Bot 不走客户端输入路径（已核实） |
| R4 | Spring 生命周期时序（CharacterService 依赖上下文） | 低 | 中 | 钩子挂 `ServerManager.run()`（S5） |
| R5 | WZ 多语言解析差异影响烘焙 | 低 | 中 | S7 专项对比测试（Map.wz 无 zh-CN 覆盖） |
| R6 | 满载性能回退（内存/锁竞争） | 中 | 中 | S9 阶梯灰度 + governor 自节流 + 24h 观察门 |
| R7 | PlayerShop 双向修改冲突（阶段二） | 中高 | 中 | S10 人工逐 hunk 合并 + 真人交易回归 |
| R8 | 机械改名遗漏（静态导入等） | 中 | 低 | 编译器兜底 + 单测守门 |
| R9 | yamlbeans 依赖引入的长期维护 | 低 | 低 | 可后期统一迁 snakeyaml（接口面小） |
| R10 | Bot 英文对话影响中文服体验 | 确定 | 低（产品） | 阶段二后可选汉化 20 个 YAML |

## 7. 回滚总方案（分层）

| 层级 | 手段 | 生效 | 损失 |
|---|---|---|---|
| L1 运行时 | `!bot remove` / 族级开关 | 秒级 | 无 |
| L2 配置 | `bot.spawn_on_startup=false` 重启 | 分钟级 | 无（Bot 不落库，天然无残留） |
| L3 数据 | 删除 fmbot 种子两行 | 分钟级 | 无 |
| L4 代码 | revert 对应步骤 commit（每步独立） | 分钟级 | 无（全程不改 schema） |

> 整个移植**不修改任何表结构**，Bot 状态全内存——L2 是"一键清场"级回滚，这是本方案安全性的根基。

## 8. 工作量与里程碑

| 阶段 | 步骤 | 预估（熟悉两库的开发者） | 里程碑判据 |
|---|---|---|---|
| 阶段一 | S0-S5（移植与接线） | 3-4 天 | 开关开=BotClient 初始化，开关关=与基线一致 |
| 阶段一 | S6-S8（功能验证） | 3-5 天 | 单 Bot 全断言通过；训练 Bot LOD 双模式通过 |
| 阶段一 | S9（灰度扩容） | 3-4 天（含等待观察） | 2,390 Bot 稳定 24h |
| **阶段一合计** | | **约 2-3 周** | |
| 阶段二 | S10-S11 | 1.5-2.5 周 | 四族经济 Bot + 压测定版 |

## 9. 附：移植操作速查

```bash
# 1) 生成某个基座文件的三方合并参考补丁
cd /home/enochguo/Project/java/SoloMapling
git diff fec53bc77..47b61b538 -- src/main/java/server/life/Monster.java

# 2) 全量基座改动清单（59 文件）
git diff --name-status fec53bc77..47b61b538 -- src/main/java ':(exclude)src/main/java/soloMapling'

# 3) Beidou 侧对应文件（org.gms 改名版）
#    /home/enochguo/Project/java/Beidou-server/gms-server/src/main/java/org/gms/server/life/Monster.java

# 4) 参考审计：docs/Cosmic对比与缺陷核查报告.md（Beidou 侧自带）
```

架构背景资料：SoloMapling 侧的三份设计文档（`SoloMapling/docs/architecture/01~03`）含 26 张 Mermaid 图，描述了 Bot 框架的全部机制，移植时可直接作为实现参照。
