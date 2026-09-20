# BeiDou-Server 项目文档

本目录是 BeiDou-Server（MapleStory v83 GMS 协议私服，`gms-server` 服务端 + `gms-ui` 管理后台）的全量文档，共 **40 篇 / 约 1.9 万行**，全部基于对源码的逐文件阅读编写（含 69 张 Mermaid 图）。文档分三层，建议按「业务 → 架构 → 详设」顺序阅读。

> 项目背景与开发规范见根目录 [`CLAUDE.md`](../CLAUDE.md)。文档中的源码引用路径均相对仓库根（如 `gms-server/src/main/java/org/gms/...`）。

## 一、业务流程文档（01-业务流程文档/）

回答「这个项目做什么业务、每个业务由哪些代码负责」，每篇含业务规则、负责类清单与 Mermaid 流程图/时序图。

| 篇目 | 内容 |
|---|---|
| [00-业务总览](01-业务流程文档/00-业务总览.md) | 游戏整体业务域划分、玩家全链路总流程图、业务域↔代码包对应表 |
| [01-账号与登录](01-业务流程文档/01-账号与登录.md) | 登录认证（自动注册/BCrypt 迁移/封禁）、多开检测、选区选角、跨频道迁移 |
| [02-角色系统](01-业务流程文档/02-角色系统.md) | 角色创建、成长升级、技能施放、存档保存、改名/转区 |
| [03-物品与交易](01-业务流程文档/03-物品与交易.md) | 背包、卷轴强化、NPC 商店、玩家交易、雇佣商店、点券商城、转蛋、快递、MTS |
| [04-战斗与掉落](01-业务流程文档/04-战斗与掉落.md) | 伤害计算、怪物生死、掉落与拾取、玩家死亡、自动封禁检测 |
| [05-任务与NPC](01-业务流程文档/05-任务与NPC.md) | NPC 对话脚本链路、任务状态机、任务脚本、道具脚本、奖励发放 |
| [06-社交系统](01-业务流程文档/06-社交系统.md) | 好友、组队、家族/联盟、婚姻、聊天/便签、人气、BBS |
| [07-地图与副本](01-业务流程文档/07-地图与副本.md) | 地图与传送门、反应堆、副本活动（108 个 event 脚本）、远征队、小游戏 |
| [08-命令系统](01-业务流程文档/08-命令系统.md) | gm0~gm6 命令体系（175 个命令类）、数据库驱动注册、执行链 |
| [09-GM管理后台业务](01-业务流程文档/09-GM管理后台业务.md) | 15 个 Controller × 31 个 Service 的 REST 业务全景、gms-ui 各页面业务 |
| [10-运维与生命周期](01-业务流程文档/10-运维与生命周期.md) | 启停/重启、热重载配置、定时任务三层体系、封禁停业等运营规则 |

## 二、架构设计文档（02-架构设计文档/）

回答「整体架构如何设计、为什么这样设计」。

| 篇目 | 内容 |
|---|---|
| [01-总体架构](02-架构设计文档/01-总体架构.md) | 双引擎同进程（Spring Boot + Netty）、ServerManager 桥接、启动流程时序、模块依赖图 |
| [02-网络与协议架构](02-架构设计文档/02-网络与协议架构.md) | Netty pipeline、AES/Shanda 包加解密、opcode 体系、173 个 handler 的注册与 O(1) 分发 |
| [03-数据架构](02-架构设计文档/03-数据架构.md) | MyBatis-Flex + 遗留 JDBC 双轨持久层、100 个 Flyway 迁移、84 实体、saveCharToDB 双版本真相 |
| [04-脚本引擎架构](02-架构设计文档/04-脚本引擎架构.md) | GraalVM JS 接入、7 类脚本管理器与调用链、缓存与重载、wz/scripts 双语覆盖机制 |
| [05-配置与热重载架构](02-架构设计文档/05-配置与热重载架构.md) | application.yml → GameConfig → i18n 三层配置、世界倍率即时写回 |
| [06-安全架构](02-架构设计文档/06-安全架构.md) | JWT 鉴权链、限流与 IP 封禁、异常体系、游戏内自动封禁积分制 |
| [07-前端架构](02-架构设计文档/07-前端架构.md) | gms-ui 技术栈、API 信封约定、路由守卫、Pinia、dev/prod 部署模型 |

## 三、详细设计文档（03-详细设计文档/）

粒度到**每个类、每个公有方法**（签名/作用/关键逻辑），覆盖全部 1100+ Java 类与前端核心模块。统一格式：文档头「模块路径/类数量/依赖模块」，每类「概述 → 关键字段 → 方法表」。

| 篇目 | 覆盖范围 |
|---|---|
| [00-启动与Spring层](03-详细设计文档/00-启动与Spring层.md) | ServerApplication（自动建库）、ServerManager、config 5 类、property、aop 3 类、exception 8 类 |
| [01-controller](03-详细设计文档/01-controller.md) | 15 个 Controller、71 个 REST 端点全表 |
| [02-service](03-详细设计文档/02-service.md) | 31 个 Service（含角色级联删除、封禁收店、转蛋概率空间等关键链路） |
| [03-dao-entity](03-详细设计文档/03-dao-entity.md) | 84 个 DO 实体：实体↔表↔迁移脚本对照、字段表、逻辑外键关系 |
| [04-dao-mapper](03-详细设计文档/04-dao-mapper.md) | 84 个 Mapper：自定义 SQL 方法详解（仅 6 个 Mapper 含 9 个自定义方法） |
| [05-model-dto-pojo](03-详细设计文档/05-model-dto-pojo.md) | dto 39 个 + pojo 7 个，按业务域分组 |
| [06-net-encryption-netty-opcodes-packet](03-详细设计文档/06-net-encryption-netty-opcodes-packet.md) | net 包 36 类：加解密、Netty 装配、opcode、InPacket/OutPacket |
| [07-net-server核心](03-详细设计文档/07-net-server核心.md) | Server 单例、World、Channel、coordinator 25 类、guild 6 类、services 15 类、task 27 类等约 90 类 |
| [08-handlers-A](03-详细设计文档/08-handlers-A.md) | channel/handlers 149 个之 #1–50（两个抽象基类方法全表） |
| [09-handlers-B](03-详细设计文档/09-handlers-B.md) | channel/handlers 149 个之 #51–100（含 PlayerLoggedinHandler 40+ 步主流程） |
| [10-handlers-C](03-详细设计文档/10-handlers-C.md) | channel/handlers 149 个之 #101–149（Use* 系、婚礼、宠物等） |
| [11-client-character](03-详细设计文档/11-client-character.md) | client 根包 25 类：Character（约 350 个方法分 24 组）、Client（90+ 方法）等 |
| [12-client-子包](03-详细设计文档/12-client-子包.md) | client 子包 41 类：autoban、command 框架、creator、inventory、keybind、processor、status |
| [13-command](03-详细设计文档/13-command.md) | gm0~gm6 全部 175 个命令类、179 条指令 |
| [14-server-maps](03-详细设计文档/14-server-maps.md) | server/maps 45 类：MapleMap（19 个方法分组）、MapFactory、Portal、Reactor、HiredMerchant 等 |
| [15-server-quest-life](03-详细设计文档/15-server-quest-life.md) | quest 41 类（条件/动作策略体系）+ life 25 类（Monster 120+ 方法） |
| [16-server-其他](03-详细设计文档/16-server-其他.md) | server 其余 67 类：CashShop、Trade、Shop、Storage、StatEffect、gachapon、movement、events 等 |
| [17-scripting](03-详细设计文档/17-scripting.md) | scripting 21 类：AbstractPlayerInteraction 全 API、各脚本管理器、ConversationManager |
| [18-provider](03-详细设计文档/18-provider.md) | provider 16 类：Data 接口体系、双语 WZ 加载、DataTool |
| [19-util](03-详细设计文档/19-util.md) | util 23 类：PacketCreator（约 400 个封包方法分组）、BCrypt、JwtUtils 等 |
| [20-constants](03-详细设计文档/20-constants.md) | constants 76 类：GameConstants/ServerConstants 详述、53 个职业技能常量类按职业归类 |
| [21-gms-ui前端](03-详细设计文档/21-gms-ui前端.md) | gms-ui/src 全部 163 个源文件：api 17、views 15 页面、store、router、组件与工具 |

## 四、代码审查报告

- [04-代码审查报告](04-代码审查报告.md)：5 个专项（并发/事务/安全/架构/正确性）并行深审的汇总，约 80 项确认问题（8 项 P0），每条附 `文件:行号` 与修复方向，含已核实安全的排除项与三批次修复路线图。

## 五、组队任务与远征机器人化清单

- [05-组队任务机器人化清单](05-组队任务机器人化清单.md)：网络调查 + 代码对照的双轴盘点——v83 官方 15 个组队任务与 6 场 Boss 远征全部已实现（含 RnJPQ 命名误会澄清），仅 EPQ/PPQ 奖励表待补；机器人化按远征线/PQ 打怪线/PQ 谜题线给出工作量与路线图。
- [06-Phase0验收清单](06-Phase0验收清单.md)：机器人 v1/v2/Wave11 的游戏内验收清单（12 驻点逐项）+ `!debug botvis` 可见性分诊判读表 + 后台频道列验证。

## 阅读建议

- **想了解业务**：从 [00-业务总览](01-业务流程文档/00-业务总览.md) 入手，按需跳转对应业务篇。
- **想了解架构**：先读 [01-总体架构](02-架构设计文档/01-总体架构.md) 的双引擎设计，再按关注点（网络/数据/脚本/配置/安全/前端）深入。
- **想定位某段代码**：详设篇按包组织，先用业务篇定位负责类，再查对应详设篇的方法表。

## 文档约定

- 流程图均为 Mermaid，可在 GitHub / IDE（Markdown 预览）直接渲染。
- 遗留 OdinMS/Cosmic 代码与 BeiDou 定制点在文中均有区分标注；编写过程中发现的源码疑点（如 `SupplyRateCouponCommand` 的 `||` 判断、`RequireUtil` 误调用等）已在对应详设篇中如实注明「疑似缺陷，修改前需核对」。
