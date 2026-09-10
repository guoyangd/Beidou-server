# 07 - net/server 核心详细设计

## 模块概览

| 项目 | 内容 |
|---|---|
| 模块路径 | `gms-server/src/main/java/org/gms/net/server/` |
| 类数量 | 约 90 个类/接口/枚举（不含 channel/handlers 子包，该部分由其他文档负责） |
| 依赖模块 | `org.gms.client`（Character/Client/SkillFactory）、`org.gms.net`（LoginServer/ChannelServer/PacketProcessor/Packet）、`org.gms.server`（TimerManager/ThreadManager/Storage/Quest/CashShop）、`org.gms.service`（Spring Service 层）、`org.gms.dao`（MyBatis-Flex）、`org.gms.util`（PacketCreator/Pair/DatabaseConnection）、`org.gms.config.GameConfig`、`org.gms.property.ServiceProperty`、`org.gms.manager.ServerManager` |

子包结构：

| 子包 | 类数 | 职责 |
|---|---|---|
| （根包） | 6 | Server 单例、PlayerStorage、跨频道 Buff 暂存与值对象 |
| `channel`（根） | 2 | Channel 频道服务器门面、CharacterIdChannelPair |
| `world` | 6 | World 世界服务器、Party/PartyCharacter/PartyOperation、Messenger/MessengerCharacter |
| `guild` | 6 | Guild/GuildCharacter/GuildSummary/GuildResponse/GuildPackets/Alliance |
| `coordinator` | 25 | session（登录会话/多开检测 10 类）、login（2 类）、matchchecker（6 类）、partysearch（4 类）、world（3 类） |
| `handlers`（根） | 3 | 登录/游戏共用的小型 handler（登录 handler 子包 `handlers/login` 属登录流程文档范畴，此处仅覆盖根目录 3 类） |
| `services` | 15 | BaseService/BaseScheduler/Service/ServicesManager/ServiceType/SchedulerListener + type 枚举 2 + 具体服务实现 8 |
| `task` | 27 | 定时任务（TimerManager 注册的 Runnable） |

线程/并发模型总述：Server 使用公平 ReentrantReadWriteLock 保护 worlds 与登录视图；World 为各子系统（宠物/坐骑/商店/婚姻/组队等）维护独立锁；Channel 用单一公平 ReentrantLock 保护婚礼/道场状态、用 RW 锁保护雇佣商店表；coordinator 大量使用 ConcurrentHashMap/Semaphore/分段锁。跨频道调用普遍采用"锁内取快照、锁外执行"的模式避免死锁。

---

## 1. 根包类

### 1.1 `Server`（单例，全服门面）

`gms-server/src/main/java/org/gms/net/server/Server.java`

**概述**：整个游戏服务器的顶层单例（懒加载 `getInstance()`），负责世界/频道的生命周期管理、全服数据缓存（家族、联盟、排名、优惠卡、新年卡）、启动流程 `init()`、停服流程 `shutdownInternal`、账号-角色登录视图，以及定时任务注册。构造时创建两对公平读写锁（world 锁 wldRLock/wldWLock、login 锁 lgnRLock/lgnWLock）。

**关键字段**：

| 字段 | 作用 |
|---|---|
| `instance` | 单例引用 |
| `channelDependencies` | 频道依赖注入容器（NoteService + FredrickProcessor） |
| `loginServer` | Netty 登录服务器 |
| `channels: List<Map<Integer,String>>` | 每个世界的 频道号→IP:端口 表 |
| `worlds: List<World>` | 世界列表（读锁查询，写锁增删） |
| `subnetInfo: Properties` | 子网配置（Lombok @Getter） |
| `accountChars / accountCharacterCount / worldChars` | 账号→角色集合、账号角色数、角色→世界映射（登录视图，login 锁保护） |
| `transitioningChars: Map<String,Integer>` | 远程 IP→正在转频道的角色 id（跨服切换 IP 校验） |
| `guilds: Map<Integer,Guild>` / `alliances: Map<Integer,Alliance>` | 家族/联盟内存缓存（synchronized(guilds)/(alliances)） |
| `inLoginState: Map<Client,Long>` | 登录态客户端→过期时间（srvLock，10 分钟超时） |
| `buffStorage` | 跨频道 Buff 暂存 |
| `newyears` | 新年卡缓存 |
| `processDiseaseAnnouncePlayers / registeredDiseaseAnnouncePlayers` | 疾病广播双缓冲队列（disLock） |
| `playerRanking` | 各世界玩家排名（世界名,等级 列表） |
| `couponRates / activeCoupons（静态）` | 倍率卡倍率表与当前激活列表 |
| `activeFly（静态）` | 允许飞行的账号集合 |
| `currentTime:AtomicLong / serverCurrentTime` | 服务器"心跳时钟"（按 update_interval 递增，非真实时间） |
| `shuttingDown: volatile boolean` | 停服重入保护（volatile 外层判断，避免 Spring 关闭钩子在 System.exit 时卡锁导致端口不释放） |
| `online: boolean`（@Getter/@Setter） | 服务器是否对外可用 |
| 各静态 Spring Bean（npcService、characterService 等 14 个） | 通过 ServerManager 上下文获取 |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `static Server getInstance()` | 获取单例 | 懒加载：instance 为 null 时 new |
| `int getCurrentTimestamp()` | 相对启动时间戳 | currentTime - uptime |
| `long getCurrentTime()` | 获取服务器逻辑时间 | 返回 serverCurrentTime（按 tick 递增的延迟时间） |
| `void updateCurrentTime()` | 推进逻辑时钟 | currentTime.addAndGet(update_interval) |
| `long forceUpdateCurrentTime()` | 强制对齐真实时钟 | 用 System.currentTimeMillis() 同时写两个字段并返回 |
| `List<Pair<Integer,String>> worldRecommendedList()` | 世界推荐列表 | 直接返回引用 |
| `void setNewYearCard(NewYearCardRecord)` / `NewYearCardRecord getNewYearCard(int)` / `removeNewYearCard(int)` | 新年卡缓存读写 | HashMap put/get/remove |
| `void setAvailableDeveloperRoom()` / `boolean canEnterDeveloperRoom()` | 开发者房间开关 | volatile boolean 置位/读取 |
| `private void loadPlayerNpcMapStepFromDb()` | 加载 PlayerNPC 地图数据 | npcService.getPlayerNpcFields 遍历并写入各 World 的 setPlayerNpcMapData |
| `World getWorld(int id)` | 按编号取世界 | 读锁内 worlds.get(id)，越界返回 null |
| `List<World> getWorlds()` | 世界只读列表 | 读锁 + unmodifiableList |
| `int getWorldsSize()` | 世界数量 | 读锁 |
| `Channel getChannel(int world, int channel)` | 取指定频道 | getWorld(world).getChannel(channel)，NPE 返回 null |
| `List<Channel> getChannelsFromWorld(int)` / `getAllChannels()` | 频道列表 | 遍历世界聚合；空安全 |
| `Set<Integer> getOpenChannels(int world)` | 开放频道号集合 | 读锁读 channels.get(world).keySet |
| `private String getIP(int world, int channel)` | 频道 IP:port | 读锁读 channels 表 |
| `String[] getInetSocket(Client, int world, int channel)` | 为客户端解析频道接入地址 | 依据客户端来源 IP（IpAddresses.isLocal/isLan）替换为 localhost/lanHost 配置，返回 [host, port] |
| `int addChannel(int worldid)` | 动态加频道 | 读锁校验世界存在、未超 max_channel_size；new Channel 并 world.addChannel，成功后写锁登记 IP；返回新频道号或 -2/-3 |
| `int addWorld()` | 动态加世界 | initWorld() 成功后重载排名并为所有已登录账号加载新世界角色视图 |
| `private int initWorld()` | 创建世界 | 读锁取序号并校验 max_world_size；读 GameConfig 各倍率/flag/消息；构造 World 与 channel_size 个 Channel；写锁内二次确认 `world.getId()==worlds.size()` 后注册（防并发部署死锁），失败则 world.shutdown() |
| `boolean removeChannel(int worldid)` | 移除末位频道 | world.removeChannel() 成功后写锁从 channels 表移除 |
| `boolean removeWorld()` | 移除末位世界 | 校验 canUninstall 后 w.shutdown()，写锁移除 worlds/channels/推荐列表并重载排名 |
| `private void resetServerWorlds()` | 清空世界数据 | 写锁 clear 三个集合（停服用） |
| `private static long getTimeLeftForNextHour()` | 距下个整点毫秒 | Calendar 计算 |
| `static long getTimeLeftForNextDay()` | 距明日零点毫秒 | Calendar 计算 |
| `Map<Integer,Integer> getCouponRates()` / `List<Integer> getActiveCoupons()` | 优惠卡数据 | 静态 map；activeCoupons 用 synchronized |
| `void commitActiveCoupons()` | 应用激活优惠卡 | 遍历全部世界在线角色 chr.updateCouponRates() |
| `void toggleCoupon(Integer couponId)` | 手动开关优惠卡 | 校验 isRateCoupon 后在 synchronized(activeCoupons) 内取反并 commit |
| `void updateActiveCoupons()` | 按星期/小时刷新激活卡 | 以 weekdayMask/hour 查询 nxCouponService.selectActiveCouponIds |
| `void runAnnouncePlayerDiseasesSchedule()` | 疾病广播调度 | disLock 下交换双缓冲队列；对 process 队列逐个调用 player.announceDiseases()+collectDiseases()；再把 registered 队列搬入 process（保证至少一个完整 tick 延迟） |
| `void registerAnnouncePlayerDiseases(Client)` | 注册疾病广播 | disLock 加入 registered 队列 |
| `List<Pair<String,Integer>> getWorldPlayerRanking(int worldid)` | 世界排名 | 读锁；全服排名模式取 world 0 |
| `void reloadWorldsPlayerRanking()` | 重载排名 | characterService.getWorldsRankPlayers 后写锁重建 playerRanking |
| `void init()` | **服务器启动主流程** | ①registerChannelDependencies；②虚拟线程并行加载 SkillFactory/CashItemFactory/Quest/SkillbookInformationProvider（future.get 等待）；③设默认时区；④重置登录状态/雇佣商店、清空过期 NX 码、装载优惠卡倍率、启动新年卡待发、CashIdGenerator 装载、执行改名与转区、PlayerNPC 排名数据、自动封禁配置、预跑 BossLogTask/ExtendValueTask；⑤启动 ThreadManager 与 initializeTimelyTasks；⑥循环 initWorld 并重载排名/PlayerNPC 数据/家族；⑦initLoginServer 启动登录端口；⑧生成 opcode 名并加载 GM 命令；⑨所有频道 reloadEventScriptManager；⑩online=true 并输出耗时 |
| `private void registerChannelDependencies()` | 注册频道依赖 | 组装 ChannelDependencies(noteService, fredrickProcessor) 并注册到 PacketProcessor |
| `private LoginServer initLoginServer(int port)` | 启动登录服务器 | new LoginServer(port).start() |
| `private void initializeTimelyTasks()` | 注册全服定时任务 | TimerManager 注册 purge(5min)、CharacterDiseaseTask、CouponTask、RankingCommandTask(5min)、RankingLoginTask、LoginCoordinatorTask、EventRecallCoordinatorTask、LoginStorageTask(2min)、DueyFredrickTask、InvitationTask(30s)、RespawnTask、OnlineTimeTask(5s)、BossLogTask/ExtendValueTask（每日，对齐零点） |
| `Alliance getAlliance(int)` / `void addAlliance(int, Alliance)` | 联盟缓存 | synchronized(alliances) |
| `boolean removeAllianceFromMemory(int)` | 移除联盟缓存 | 移除后遍历 guilds，将属该联盟的家族 allianceId 置 0 并重置联盟rank；返回是否全部同步成功 |
| `void allianceMessage(int id, Packet, int exception, int guildex)` | 联盟广播 | 遍历联盟下家族（跳过 guildex），guild.broadcast(packet, exception) |
| `boolean addGuildToAlliance(int aId, int guildId, int guildMasterId)` | 家族入盟 | synchronized(alliance)：alliance.addGuildAndSave 成功后 setAllianceIdInMemory + 重置成员联盟 rank |
| `boolean removeGuildFromAlliance(int aId, int guildId)` | 家族退盟 | 校验 canRemoveGuild → removeGuildAndSave → 家族内存 allianceId 置 0 |
| `int createGuild(int leaderId, String name)` | 创建家族 | 委托静态 Guild.createGuild |
| `Guild getGuildByName(String)` / `getGuild(int)` | 家族查询 | synchronized(guilds)；前者忽略大小写遍历 |
| `Guild getGuild(int id, int world)` / `getGuild(int id, int world, Character mc)` | 取家族（懒加载） | 缓存未命中且 id≠0 时 new Guild(id, world)（id==-1 视为失败）；mc 非空时回填 MGC 并 setOnline；放入缓存 |
| `void setGuildMemberOnline(Character, boolean, int)` | 更新成员在线状态 | getGuild 后 g.setOnline |
| `int addGuildMember(GuildCharacter, Character)` | 加成员 | 委托 g.addGuildMember |
| `boolean setGuildAllianceIdInMemory(int, int)` / `void resetAllianceGuildPlayersRankInMemory(int)` | 家族联盟内存更新 | 委托 Guild 对应方法 |
| `void leaveGuild(GuildCharacter)` | 成员退族 | g.leaveGuild |
| `void guildChat(int gid, String name, int cid, String msg)` | 家族聊天 | g.guildChat |
| `void changeRank(int gid, int cid, int newRank)` | 改家族 rank | g.changeRank |
| `void expelMember(GuildCharacter, String, int)` | 开除成员 | g.expelMember(…, channelDependencies.noteService()) |
| `void setGuildNotice(int, String)` / `memberLevelJobUpdate(GuildCharacter)` / `changeRankTitle(int, String[])` / `setGuildEmblem(int, short, byte, short, byte)` | 家族公告/成员信息/头衔/徽章 | 均为缓存命中后委托 Guild |
| `boolean disbandGuild(int gid)` | 解散家族 | 注释明确：不持全局 guilds 锁执行 guild.disbandGuild()（含 DB 与跨频道广播，避免阻塞所有登录线程），成功后再锁内移除缓存 |
| `boolean increaseGuildCapacity(int)` / `void gainGP(int, int)` | 扩容/加 GP | 委托 Guild |
| `void guildMessage(int gid, Packet)` / `guildMessage(int, Packet, int)` | 家族发包 | g.broadcast(packet, exception) |
| `PlayerBuffStorage getPlayerBuffStorage()` | 取 Buff 暂存 | 返回字段 |
| `void deleteGuildCharacter(Character)` / `deleteGuildCharacter(GuildCharacter)` | 删号时清理家族 | 先 setGuildMemberOnline(false)，rank>1 走 leaveGuild 否则 disbandGuild |
| `void reloadGuildCharacters(int world)` | 重载世界家族状态 | 遍历世界角色，有家族者 setGuildMemberOnline + memberLevelJobUpdate，最后 reloadGuildSummary |
| `void broadcastMessage(int world, Packet)` / `broadcastGMMessage(int world, Packet)` | 世界广播 | 遍历该世界所有频道 ch.broadcastPacket/broadcastGMPacket |
| `boolean isGmOnline(int world)` | 是否有 GM 在线 | 遍历世界各频道玩家存储 |
| `void changeFly(Integer accountid, boolean)` / `boolean canFly(Integer)` | 飞行许可 | 静态 activeFly 增删/查询 |
| `int getCharacterWorld(Integer chrid)` | 角色所在世界 | login 读锁查 worldChars，缺省 -1 |
| `boolean haveCharacterEntry(Integer accountid, Integer chrid)` | 账号是否拥有角色 | login 读锁 |
| `short getAccountCharacterCount(Integer)` | 账号角色总数 | login 读锁 |
| `short getAccountWorldCharacterCount(Integer, Integer)` | 账号在某世界的角色数 | login 读锁遍历 accountChars 计数 |
| `private Set<Integer> getAccountCharacterEntries(Integer)` | 账号角色 id 集合拷贝 | login 读锁 |
| `void updateCharacterEntry(Character)` | 更新角色登录视图 | 未在线直接返回；否则 generateCharacterEntry 后 login 写锁内注册到对应 World 的 accountChars 视图 |
| `void createCharacterEntry(Character)` | 新建角色视图 | login 写锁：计数+1、accountChars/worldChars 登记、注册 World 视图 |
| `void deleteCharacterEntry(Integer, Integer)` | 删除角色视图 | login 写锁：计数-1、移除两张索引并从 World 视图注销 |
| `void transferWorldCharacterEntry(Character, Integer toWorld)` | 转区视图迁移 | 在设置新 worldid 之前调用：先从旧 World 注销、更新 worldChars、注册新 World |
| `SortedMap<Integer,List<Character>> loadAccountCharlist(int accountId, int visibleWorlds)` | 登录界面角色列表 | 截取前 visibleWorlds 个世界，读锁收集各世界非空角色列表（首登时初始化空集合） |
| `private static Pair<Short,List<List<Character>>> loadAccountCharactersViewFromDb(int accId, int wlen)` | 从 DB 加载账号角色视图 | ItemFactory.loadEquippedItems 预载装备；SELECT * FROM characters WHERE accountid 按 world,id 排序逐行 loadCharacterEntryFromDB |
| `void loadAllAccountsCharactersView()` | 加载全部账号视图 | 遍历 accounts 表，首登账号逐个 loadAccountCharactersView |
| `private boolean isFirstAccountLogin(Integer)` | 是否首次登录 | login 读锁判断 accountChars 是否含 key |
| `void loadAccountCharacters(Client)` | 登录时加载账号角色 | 非首登：聚合账号涉及世界，取全角色视图最大 gmLevel 设给 Client；首登：走 loadAccountCharactersView |
| `private int loadAccountCharactersView(Integer accId, int gmLevel, int fromWorldid)` | 装载视图（返回最大 gmLevel） | DB 加载后 login 写锁内逐世界 w.loadAccountCharactersView，登记 accountChars/worldChars/计数 |
| `void loadAccountStorages(Client)` | 加载账号仓库 | login 写锁收集账号涉及世界，各世界 loadAccountStorage |
| `private static String getRemoteHost(Client)` | 会话远端标识 | SessionCoordinator.getSessionRemoteHost |
| `void setCharacteridInTransition(Client, int)` | 登记转频道角色 | login 写锁 transitioningChars.put(remoteIp, charId) |
| `boolean validateCharacteridInTransition(Client, int)` | 校验并消费转频道 | use_ip_validation 关闭直接 true；读出并 remove，比对 charId |
| `Integer freeCharacteridInTransition(Client)` | 强制释放转频道记录 | 配置关闭返回 null；否则 remove 返回 |
| `boolean hasCharacteridInTransition(Client)` | 是否存在转频道记录 | login 读锁 containsKey |
| `void registerLoginState(Client)` / `unregisterLoginState(Client)` | 登录态登记/注销 | srvLock 写 inLoginState（10 分钟过期） |
| `private void disconnectIdlesOnLoginState()` | 踢出登录态超时客户端 | srvLock 内筛选过期项，锁外分别 disconnect / SessionCoordinator.closeSession（避免死锁） |
| `private void disconnectIdlesOnLoginTask()` | 注册上述清理任务 | TimerManager 每 5 分钟执行 |
| `final Runnable shutdown(boolean restart)` | 停服任务工厂 | 返回 () -> shutdownInternal(restart) |
| `void shutdownInternal(boolean restart)` | 停服入口（重入保护） | volatile shuttingDown 在 synchronized 外先判断（防止 System.exit 时 Spring 钩子卡锁）；synchronized 内二次判断后 doShutdownInternal，finally 重置标志使重启后可再次停服 |
| `private synchronized void doShutdownInternal(boolean)` | 实际停服 | 逐世界 shutdown → 保存 HpMpAlert → 等待所有频道 finishedShutdown（1s 轮询）→ resetServerWorlds → 停 ThreadManager/TimerManager/loginServer → online=false；restart 时置空单例并重新 getInstance().init() |
| `boolean isNextTime()` | 随机日程触发 | 以 1+rand(4) 天为周期；首次调用仅初始化 nextTime 返回 false |
| `synchronized void shutdownWithMsgAndInternal(ServerShutdownDTO)` | 带公告的定时停服 | 按 minutes 计算倒计时；依 DTO 开关对全服角色发 startMapEffect（火红玫瑰）、世界滚动消息、蓝色 GM 聊天；TimerManager 延时调度 shutdown(false) |

### 1.2 `PlayerStorage`

**概述**：线程安全的"角色存储"，按 id 与小写名字双索引在线角色；被 World、Channel 共用（各自持有实例）。

**关键字段**：`storage: Map<Integer,Character>`（LinkedHashMap）、`nameStorage: Map<String,Character>`、公平读写锁对。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void addPlayer(Character)` | 添加角色 | 写锁同时写两张索引 |
| `Character removePlayer(int)` | 按 id 移除 | 写锁移除 id 索引并联动移除名字索引，返回角色 |
| `Character getCharacterByName(String)` | 按名查 | 读锁，name 转 lowercase |
| `Character getCharacterById(int)` | 按 id 查 | 读锁 |
| `Collection<Character> getAllCharacters()` | 全量快照 | 读锁返回新 ArrayList |
| `final void disconnectAll()` | 断开全部 | 读锁取快照后锁外逐个 client.forceDisconnect()，最后写锁 clear |
| `int getSize()` | 在线数 | 读锁 |

### 1.3 `PlayerBuffStorage`

**概述**：角色跨频道/跨服务器切换时的 Buff 与疾病（debuff）暂存，一次性取回（get 即 remove）。

**关键字段**：`id`（随机数，用于 equals/hashCode）、公平 ReentrantLock、`buffs: Map<Integer,List<PlayerBuffValueHolder>>`、`diseases: Map<Integer,Map<Disease,Pair<Long,MobSkill>>>`。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void addBuffsToStorage(int chrid, List<PlayerBuffValueHolder>)` | 存 Buff 列表 | 锁内 put（覆盖旧值） |
| `List<PlayerBuffValueHolder> getBuffsFromStorage(int chrid)` | 取 Buff | 锁内 remove 取回 |
| `void addDiseasesToStorage(int, Map<Disease,Pair<Long,MobSkill>>)` / `getDiseasesFromStorage(int)` | 疾病存取 | 同上模式 |
| `int hashCode()` / `boolean equals(Object)` | 以随机 id 判等 | 供调试/标识 |

### 1.4 `PlayerBuffValueHolder`

**概述**：Buff 值对象。公有字段 `usedTime`（施放时间）、`effect`（StatEffect）；构造器 `PlayerBuffValueHolder(int usedTime, StatEffect effect)` 赋值。

### 1.5 `PlayerCoolDownValueHolder`

**概述**：技能冷却值对象。公有字段 `skillId / startTime / length`；构造器 `(int skillId, long startTime, long length)`。

### 1.6 `PlayerDiseaseValueHolder`

**概述**：疾病值对象（来源 Celino）。公有字段 `disease / startTime / length`；构造器 `(Disease disease, long startTime, long length)`。

---

## 2. channel 根包

### 2.1 `Channel`（final，频道门面）

`gms-server/src/main/java/org/gms/net/server/channel/Channel.java`

**概述**：单个游戏频道的门面：持有 Netty ChannelServer（端口 = 7575 + (channel-1) + world*100，IP 为 WAN 主机）、PlayerStorage、MapManager、EventScriptManager、ServicesManager，并管理道场（Dojo）、婚礼预约、远征、迷你地下城、雇佣商店、怪物嘉年华房间、地图所有权等频道级状态。

**关键字段**：

| 字段 | 作用 |
|---|---|
| `port / ip / world / channel` | 频道网络标识 |
| `players: PlayerStorage` | 本频道在线角色 |
| `channelServer` | Netty ChannelServer 实例 |
| `serverMessage` | 本频道滚动公告 |
| `mapManager` | 地图工厂 |
| `eventSM` | 事件脚本管理器 |
| `services: ServicesManager(ChannelServices.OVERALL)` | 频道级调度服务 |
| `hiredMerchants: Map<Integer,HiredMerchant>` | 雇佣商店（merch 读写锁保护） |
| `storedVars` | 脚本用变量表 |
| `playersAway` | 在商城/MTS 的离场角色 id |
| `expeditions / expedType` | 各类型远征 |
| `dungeons` | 迷你地下城 |
| `ownedMaps`（WeakHashMap 同步 Set） | 需检查所有权的地图 |
| `usedDojo / dojoStage[20] / dojoFinishTime[20] / dojoTask[20] / dojoParty` | 道场槽位（位图）、进度、计时任务、组队映射 |
| `chapelReservationQueue / cathedralReservationQueue` 及 `ongoingChapel*/ongoingCathedral*` | 婚礼小教堂/大教堂预约队列与进行中婚礼（lock 保护） |
| `ongoingStartTime` | 当前婚礼轮起始时间 |
| `usedMC` | 占用的怪物嘉年华房间 |
| `finishedShutdown` | 停服完成标志 |

**方法表**（全量公有方法）：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `Channel(int world, int channel, long startTime)` | 构造 | 计算 port/ip、初始化 MapManager、启动 ChannelServer；Server 已在线则加载全部事件脚本，否则仅加载 0_EXAMPLE（加快启动）；初始化道场数组与 ServicesManager |
| `private ChannelServer initServer(int port, int world, int channel)` | 启动 Netty 服务 | new ChannelServer(...).start() |
| `synchronized void reloadEventScriptManager()` | 重载事件脚本 | 已停服直接返回；cancel 旧 ESM 后按 getEvents() 重建 |
| `synchronized void shutdown()` | 频道停服 | finishedShutdown 防重入；closeAllMerchants → disconnectAwayPlayers → players.disconnectAll → eventSM.dispose → mapManager.dispose → closeChannelSchedules → channelServer.stop |
| `private void closeChannelServices()` | 关停调度服务 | services.shutdown() |
| `private void closeChannelSchedules()` | 取消道场任务 | lock 内逐个 cancel dojoTask，再关服务 |
| `private void closeAllMerchants()` | 关闭全部雇佣商店 | 写锁取快照清空后，锁外逐店 forceClose；每店独立 try-catch 防止单店异常致其余店物品丢失 |
| `MapManager getMapFactory()` | 取地图工厂 | 返回字段 |
| `BaseService getServiceAccess(ChannelServices sv)` | 取调度服务 | services.getAccess(sv).getService() |
| `int getWorld()` / `World getWorldServer()` | 世界号/世界对象 | 后者委托 Server.getInstance().getWorld(world) |
| `void addPlayer(Character)` | 角色进入频道 | players.addPlayer 并推送当前 serverMessage |
| `String getServerMessage()` | 读公告 | 返回字段 |
| `PlayerStorage getPlayerStorage()` | 取角色存储 | 返回字段 |
| `boolean removePlayer(Character)` | 移除角色 | players.removePlayer(id) != null |
| `int getChannelCapacity()` | 频道负载（0-800） | ceil(在线数/channel_capacity * 800) |
| `void broadcastPacket(Packet)` | 全频道广播 | 遍历在线角色 sendPacket |
| `final int getId()` | 频道号 | 返回 channel |
| `String getIP()` | 频道地址 | 返回 ip |
| `Event getEvent()` / `void setEvent(Event)` | 当前 GM 活动 | getter/setter |
| `EventScriptManager getEventSM()` | 事件脚本管理器 | 返回字段 |
| `void broadcastGMPacket(Packet)` | GM 广播 | 仅 isGM 角色发包 |
| `List<Character> getPartyMembers(Party)` | 本频道的队伍成员 | 过滤 partychar.getChannel()==getId() 且玩家存储可查 |
| `void insertPlayerAway(int chrId)` / `removePlayerAway(int)` | 标记/解除商城离场 | playersAway 增删 |
| `boolean canUninstall()` | 频道可否卸载 | 在线数为 0 且无离场角色 |
| `private void disconnectAwayPlayers()` | 踢离场角色 | 遍历 playersAway，从世界存储找到角色后 forceDisconnect |
| `Map<Integer,HiredMerchant> getHiredMerchants()` | 商店只读表 | merch 读锁 + unmodifiableMap |
| `boolean addHiredMerchant(int chrid, HiredMerchant)` | 注册商店 | 写锁，已存在返回 false |
| `boolean removeHiredMerchant(int chrid, HiredMerchant expected)` | 注销商店 | 写锁 remove(key, expected) 条件移除 |
| `int[] multiBuddyFind(int charIdFrom, int[] characterIds)` | 好友可见性批量查询 | 遍历 characterIds，在线且 buddylist.containsVisible(charIdFrom) 的收集为数组 |
| `boolean addExpedition(Expedition)` | 注册远征 | synchronized(expeditions)；同类型已存在返回 false；成功则 exped.beginRegistration() |
| `void removeExpedition(Expedition)` / `Expedition getExpedition(ExpeditionType)` / `List<Expedition> getExpeditions()` | 远征管理 | synchronized(expeditions) |
| `boolean isConnected(String name)` | 玩家是否在本频道 | getPlayerStorage().getCharacterByName != null |
| `boolean isActive()` | 事件是否激活 | eventSM != null && eventSM.isActive() |
| `boolean finishedShutdown()` | 停服完成标志 | 返回字段 |
| `void setServerMessage(String)` | 设置公告 | 更新字段、全频道广播 serverMessage、重置世界"公告禁用"表 |
| `private static String[] getEvents()` | 枚举事件脚本 | 扫描 scripts/event 与 scripts-语言/event 目录（语言目录补充本地化事件、同名去重），仅收 *.js |
| `private static void addEvents(Path, List<String>)` | 目录扫描辅助 | 目录不存在跳过；DirectoryStream 过滤 .js，去重加入 |
| `int getStoredVar(int key)` / `void setStoredVar(int, int)` | 频道脚本变量 | HashMap 读写，缺省 0 |
| `int lookupPartyDojo(Party)` | 查队伍道场槽 | dojoParty.get(party.hashCode())，缺省 -1 |
| `int ingressDojo(boolean isPartyDojo, int fromStage)` | 进入道场（单人重载） | 委托三参版本 |
| `int ingressDojo(boolean isPartyDojo, Party, int fromStage)` | 进入道场 | lock 内：按 usedDojo 位图（单人跳过前 5 位、上限 15，组队上限 5）找空槽；计算 slotMapid（DOJO_PARTY/SOLO_BASE + 100*(stage+1) + slot）；组队已在道场返回 -2；置位 usedDojo、resetDojo、startDojoSchedule；无空位返回 -1 |
| `private void freeDojoSlot(int slot, Party)` | 释放道场槽 | lock 内清位图；移除 dojoParty 记录（含"无队伍却占槽"的兜底清理） |
| `private static int getDojoSlot(int dojoMapId)` | 由地图 id 算槽位 | (mapid%100) + (92502 前缀 ? 5 : 0) |
| `void resetDojoMap(int fromMapId)` | 重置道场地图物件 | 依 stage≤36 重置 5/2 张地图的 resetMapObjects |
| `void resetDojo(int dojoMapId)` / `private void resetDojo(int, int thisStg)` | 重置槽进度 | dojoStage[slot] = thisStg |
| `void freeDojoSectionIfEmpty(int dojoMapId)` | 空则释放道场段 | 向后扫 5 张图，全空才 freeDojoSlot |
| `private void startDojoSchedule(int dojoMapId)` | 道场限时任务 | 时钟 = (stage>36?15:(stage/6)+5) 分钟；取消旧任务后 schedule：到点把该段全部在图角色传送 DOJO_EXIT 并 freeDojoSlot；额外 3 秒展示 TIMES UP；记录 dojoFinishTime |
| `void dismissDojoSchedule(int, Party)` | 主动结束道场 | 取消槽任务并释放槽位 |
| `boolean setDojoProgress(int dojoMapId)` | 推进最高进度 | dojoStage 只增不减，成功返回 true |
| `long getDojoFinishTime(int)` | 槽截止时间 | dojoFinishTime[slot] |
| `boolean addMiniDungeon(int dungeonid)` | 注册迷你地下城 | lock；不存在则按 MiniDungeonInfo 创建（时限取地图配置） |
| `MiniDungeon getMiniDungeon(int)` / `void removeMiniDungeon(int)` | 地下城查询/移除 | lock |
| `Pair<Boolean,Pair<Integer,Set<Integer>>> getNextWeddingReservation(boolean cathedral)` | 取下一场婚礼 | lock 内从对应队列出队；向 World 取新人 id 对与宾客表；全世界公告婚礼开始；返回 (premium, (weddingId, guests))，空队返回 null |
| `boolean isWeddingReserved(Integer)` | 婚礼是否已预约 | 世界队列中或为当前 ongoing |
| `int getWeddingReservationStatus(Integer, boolean)` | 预约位置 | 进行中=0、队列第 i 位=i+1、无=-1 |
| `int pushWeddingReservation(Integer, boolean cathedral, boolean premium, Integer groomId, Integer brideId)` | 提交预约 | 已预约返回 -1；先登记 World 队列，再按 wedding_reservation_delay 用 null 占位填充等待时间，返回队列长度 |
| `boolean isOngoingWeddingGuest(boolean, int)` | 是否进行中婚礼宾客 | 查 ongoingXxxGuests |
| `Integer getOngoingWedding(boolean)` / `boolean getOngoingWeddingType(boolean)` | 进行中婚礼 id/类型 | lock 读 ongoing 字段 |
| `void closeOngoingWedding(boolean)` | 关闭进行中婚礼 | lock 清空对应 ongoing 三元组 |
| `void setOngoingWedding(boolean cathedral, Boolean premium, Integer weddingId, Set<Integer> guests)` | 开启婚礼 | lock 写 ongoing；记录起始时间；schedule 一个 wedding_reservation_timeout 分钟后 closeOngoingWedding 的保底任务 |
| `synchronized boolean acceptOngoingWedding(boolean)` | 新人到场确认 | 取消保底超时任务（无任务返回 false） |
| `private static String getTimeLeft(long)` | 剩余时间格式化 | 时/分/秒 级联拼接 |
| `long getWeddingTicketExpireTime(int resSlot)` | 婚票过期时间 | ongoingStartTime + 相对时间 |
| `static long getRelativeWeddingTicketExpireTime(int resSlot)` | 相对婚票时长 | resSlot * wedding_reservation_interval 分钟 |
| `String getWeddingReservationTimeLeft(Integer)` | 预约等待描述 | 先查大教堂再查小教堂；进行中显示 RIGHT NOW，否则显示剩余时间 |
| `Pair<Integer,Integer> getWeddingCoupleForGuest(int guestId, boolean)` | 宾客查询新人 | 是进行中婚礼宾客时向 World 查 relationshipCouple |
| `void dropMessage(int type, String)` | 频道消息 | 遍历在线角色 dropMessage |
| `void registerOwnedMap(MapleMap)` / `unregisterOwnedMap(MapleMap)` | 登记所有权地图 | ownedMaps 增删 |
| `void runCheckOwnedMapsSchedule()` | 所有权检查调度 | 快照后逐图 checkMapOwnerActivity() |
| `private static int getMonsterCarnivalRoom(boolean cpq1, int field)` | 嘉年华房间号 | (cpq1?0:100)+field |
| `void initMonsterCarnival(boolean, int)` / `finishMonsterCarnival(boolean, int)` / `boolean canInitMonsterCarnival(boolean, int)` | 嘉年华房间占用管理 | usedMC 增删/查询 |
| `void debugMarriageStatus()` | 婚礼状态调试输出 | 打印世界与本频道两侧婚礼队列/任务/宾客 |

### 2.2 `CharacterIdChannelPair`

**概述**：值对象，承载"角色 id + 所在频道号"，用于跨频道好友查询（World.multiBuddyFind）。提供空构造器与 `(int charid, int channel)` 构造器，getter `getCharacterId()/getChannel()`。

---

## 3. world 包

### 3.1 `World`

`gms-server/src/main/java/org/gms/net/server/world/World.java`

**概述**：世界（服务器大区）门面。持有频道列表与各倍率（exp/drop/bossDrop/meso/quest/travel/fishing），并集中管理世界级子系统：账号角色视图与仓库、家族队列与摘要、婚姻队列与宾客、组队（partyChars/parties）、好友、聊天、信使（Messenger）、猫头鹰搜索/现金商城热卖统计、宠物/坐骑饥饿调度、玩家商店/雇佣商店、限时地图对象、HP 递减、公告禁用、PlayerNPC 数据、亲密关系（marriages 表）、钓鱼、组队搜索调度等。构造器中向 TimerManager 注册 12+ 个世界级定时任务。

**关键字段**（节选）：

| 字段 | 作用 |
|---|---|
| `id / flag / expRate…fishingRate` | 世界属性（Lombok @Getter，部分 @Setter） |
| `eventmsg` | 事件消息 |
| `channels + chnRLock/chnWLock` | 频道列表（公平读写锁） |
| `pnpcStep / pnpcPodium` | PlayerNPC 地图数据 |
| `messengers / runningMessengerId` | 信使表与自增 id |
| `families` | 家族系统 |
| `relationships / relationshipCouples` | 玩家→婚姻id、婚姻id→(夫,妻) |
| `gsStore` | GuildSummary 缓存 |
| `players: PlayerStorage` | 世界在线角色 |
| `services: ServicesManager(WorldServices.SAVE_CHARACTER)` | 世界级调度服务 |
| `matchChecker / partySearch` | 匹配确认与组队搜索协调器 |
| `accountChars: Map<Integer,SortedMap<Integer,Character>>` + `accountCharsLock` | 账号角色视图（须在 Server 的 lgnWLock 之后加锁） |
| `accountStorages` | 账号仓库 |
| `queuedGuilds / queuedMarriages / marriageGuests` | 家族创建队列、婚姻队列与宾客（ConcurrentHashMap） |
| `partyChars / parties / runningPartyId(初值 1000000001，避免与 charid 冲突) / partyLock` | 组队管理（id 从 1000000001 自增） |
| `owlSearched / cashItemBought(9 页)` + suggest 读写锁 | 猫头鹰搜索与商城购买统计 |
| `activePets/activeMounts/activePlayerShops/activeMerchants` + 各自锁 | 调度注册表 |
| `registeredTimedMapObjects` | 限时地图对象（Runnable→过期时间） |
| `fishingAttempters / playerHpDec` | WeakHashMap 同步包装 |
| 各 ScheduledFuture（pets/srvMessages/mounts/merchant/timedMapObjects/characters/marriages/mapOwnership/fishing/partySearch/timeout/hpDec） | 定时任务句柄 |

**方法表**（全量公有方法，私有择要）：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `World(int world, int flag, String eventmsg, float expRate, float dropRate, float bossDropRate, float mesoRate, float questRate, float travelRate, float fishingRate)` | 构造 | 初始化 id/倍率、锁、partyid/messengerid 起点；向 TimerManager 注册：PetFullnessTask(1min)、ServerMessageTask(10s)、MountTirednessTask(1min)、HiredMerchantTask(10min)、TimedMapObjectTask(1min)、CharacterAutosaverTask(1h, fixedDelay)、WeddingReservationTask(wedding_reservation_interval)、MapOwnershipTask(20s)、FishingTask(10s)、PartySearchTask(10s)、TimeoutTask(10s)、CharacterHpDecreaseTask(map_damage_overtime_interval)；启用家族系统时注册 FamilyDailyResetTask(每日对齐零点) 并预重置 entitlement |
| `int getChannelsSize()` / `List<Channel> getChannels()` / `Channel getChannel(int)` | 频道查询 | chn 读锁；getChannel 下标 channel-1，越界 null |
| `boolean addChannel(Channel)` | 加频道 | 写锁；仅接受 id==size+1 的连续编号 |
| `int removeChannel()` | 移除末位频道 | 先读锁取末位，canUninstall 校验后写锁移除并 ch.shutdown() |
| `boolean canUninstall()` | 世界可否卸载 | 无在线玩家且所有频道 canUninstall |
| `void setFlag(byte)` / `getEventMessage()` / `setEventMessage(String)` | 属性存取 | 直接字段操作 |
| `void setExpRate(float)` / `setDropRate(float)` / `setMesoRate(float)` | 动态倍率 | 三段式：全在线角色 revertWorldRates → 改字段 → setWorldRates |
| `int getTransportationTime(int travelTime)` | 交通工具耗时 | travelTime * travelRate 取整（支持小数倍率） |
| `void loadAccountCharactersView(Integer, List<Character>)` | 装载账号角色视图 | 转 TreeMap 后 accountCharsLock 写入 |
| `void registerAccountCharacterView(Integer, Character)` / `unregisterAccountCharacterView(Integer, Integer)` / `clearAccountCharacterView(Integer)` | 视图增删 | accountCharsLock |
| `void loadAccountStorage(Integer)` | 懒加载账号仓库 | 未注册则 Storage.loadOrCreateFromDB 并登记 |
| `private void registerAccountStorage(Integer)` / `void unregisterAccountStorage(Integer)` / `Storage getAccountStorage(Integer)` | 仓库管理 | accountCharsLock 保护 accountStorages |
| `private static List<Entry<…>> getSortedAccountCharacterView(Map)` | 按账号 id 排序 | List.sort |
| `List<Character> loadAndGetAllCharactersView()` | 强制加载全视图 | 触发 Server.loadAllAccountsCharactersView 后 getAllCharactersView |
| `List<Character> getAllCharactersView()` | 全部角色视图 | 拷贝 accountChars 后按账号/角色 id 排序聚合 |
| `List<Character> getAccountCharactersView(int accountId)` | 账号视图 | 无记录时初始化空 TreeMap 并返回 null |
| `PlayerStorage getPlayerStorage()` | 角色存储 | 返回字段 |
| `MatchCheckerCoordinator getMatchCheckerCoordinator()` / `PartySearchCoordinator getPartySearchCoordinator()` | 协调器访问 | 返回字段 |
| `void addPlayer(Character)` | 角色进入世界 | players.addPlayer |
| `void removePlayer(Character)` | 角色离开世界 | 先从其 Channel 移除（失败则全频道查找），再从 players 移除 |
| `void addFamily(int, Family)` / `removeFamily(int)` / `Family getFamily(int)` / `Collection<Family> getFamilies()` | 家族系统管理 | synchronized(families) |
| `Guild getGuild(GuildCharacter)` | 取家族 | 委托 Server.getGuild 并维护 gsStore 摘要缓存 |
| `boolean isWorldCapacityFull()` | 世界是否满载 | 状态==2 |
| `int getWorldCapacityStatus()` | 负载状态 0/1/2 | 频道数*channel_capacity 为上限，≥80% 为 1，满为 2 |
| `GuildSummary getGuildSummary(int gid, int wid)` / `updateGuildSummary(int, GuildSummary)` / `reloadGuildSummary()` | 家族摘要缓存 | 未命中从 Server 加载并缓存；reload 遍历刷新 |
| `void setGuildAndRank(List<Integer> cids, int guildid, int rank, int exception)` | 批量设置家族/rank | 循环单参版本（exception 用于重生） |
| `void setOfflineGuildStatus(int guildid, int guildrank, int cid)` | 离线角色家族状态 | 直接 UPDATE characters |
| `void setGuildAndRank(int cid, int guildid, int rank)` | 在线角色家族/rank | guildid==-1&&rank==-1 时仅触发广播重生；否则更新 MGC（换族时 allianceRank 重置 5）并 saveGuildStatus；换族时向全图广播 guildNameChanged/guildMarkChanged |
| `void changeEmblem(int gid, List<Integer>, GuildSummary)` | 换家族徽章 | 更新摘要、向受影响玩家发 emblemChange、setGuildAndRank(-1,-1) 触发重生 |
| `void sendPacket(List<Integer> targetIds, Packet, int exception)` | 定向发包 | 遍历在线角色 sendPacket |
| `boolean isGuildQueued(int)` / `putGuildQueued(int)` / `removeGuildQueued(int)` | 家族创建队列 | queuedGuilds 增删查 |
| `boolean isMarriageQueued(int)` / `getMarriageQueuedLocation(int)` / `getMarriageQueuedCouple(int)` / `putMarriageQueued(int, boolean cathedral, boolean premium, int groomId, int brideId)` / `Pair<Boolean,Set<Integer>> removeMarriageQueued(int)` | 婚姻队列管理 | queuedMarriages 存 (位置类型, 新人)；put 同时初始化宾客集合；remove 返回 (premium, guests) |
| `boolean addMarriageGuest(int, int)` | 添加宾客 | 已在列表返回 false |
| `Pair<Integer,Integer> getWeddingCoupleForGuest(int guestId, Boolean cathedral)` | 为宾客定位婚礼 | 先查各频道进行中婚礼；再扫婚姻宾客表；多候选时按频道预约位次取最早者 |
| `void debugMarriageStatus()` | 婚姻调试 | log.debug 队列与宾客 |
| `private void registerCharacterParty(Integer, Integer)` / `unregisterCharacterParty(Integer)` / `Integer getCharacterPartyid(Integer)` | 角色→队伍映射 | partyLock 读写 partyChars |
| `Party createParty(PartyCharacter)` | 创建队伍 | 自增 partyid、new Party、partyLock 内登记 parties/partyChars、addMember |
| `Party getParty(int)` | 查队伍 | partyLock |
| `private Party disbandParty(int)` | 解散登记 | partyLock 移除 |
| `private void updateCharacterParty(Party, PartyOperation, PartyCharacter, Collection)` | 维护角色映射 | JOIN 登记、LEAVE/EXPEL 注销、DISBAND 批量注销 |
| `private void updateParty(Party, PartyOperation, PartyCharacter)` | 广播队伍变更 | 更新映射后向全成员 setParty/setMPC + updateParty 包；LEAVE/EXPEL 额外通知离队者并清空其队伍 |
| `void updateParty(int partyid, PartyOperation, PartyCharacter)` | 队伍操作入口 | JOIN/EXPEL/LEAVE/DISBAND/SILENT_UPDATE/LOG_ONOFF 直接委托 Party；CHANGE_LEADER 处理事件领队（eim.changedLeader）与迷你地下城关闭（换图时 mmd.close()）后 setLeader；最后统一广播 |
| `void removeMapPartyMembers(int partyid)` | 移除地图队伍关联 | 遍历成员 map.removeParty |
| `int find(String name)` / `int find(int id)` | 查角色所在频道 | 玩家存储查找，-1 表示不在线 |
| `void partyChat(Party, String, String namefrom)` | 队伍聊天 | 向除发送者外成员发 multiChat(type=1) |
| `void buddyChat(int[] recipientIds, int cidFrom, String, String)` | 好友聊天 | 仅接收者好友列表对发送者可见时发 multiChat(type=0) |
| `CharacterIdChannelPair[] multiBuddyFind(int charIdFrom, int[] characterIds)` | 跨频道好友定位 | 各频道 multiBuddyFind 聚合为 id/频道对 |
| `Messenger getMessenger(int)` | 查信使 | messengers.get |
| `void leaveMessenger(int, MessengerCharacter)` | 离开信使 | 移除成员并广播 removeMessengerPlayer |
| `void messengerInvite(String sender, int messengerid, String target, int fromchannel)` | 信使邀请 | 目标在线且无信使时经 InviteCoordinator.createInvite(MESSENGER) 发包；否则提示发送者 |
| `void addMessengerPlayer(Messenger, String namefrom, int fromchannel, int position)` | 广播新成员 | 双向通知成员列表（joinMessenger/addMessengerPlayer） |
| `void removeMessengerPlayer(Messenger, int position)` | 广播移除成员 | 全员 removeMessengerPlayer 包 |
| `void messengerChat(Messenger, String, String)` | 信使聊天 | 除发送者外全员 messengerChat 包 |
| `void declineChat(String sender, Character player)` | 拒绝信使邀请 | answerInvite(MESSENGER,…,false) 结果为 DENIED 时通知发送者 |
| `void updateMessenger(int messengerid, String, int)` / `updateMessenger(Messenger, String, int, int)` | 更新成员频道信息 | 全员 updateMessengerPlayer 包 |
| `void silentLeaveMessenger(int, MessengerCharacter)` | 静默回归（离线重连） | messenger.addMember 恢复 |
| `void joinMessenger(int, MessengerCharacter, String from, int fromchannel)` | 加入信使 | addMember + addMessengerPlayer 广播 |
| `void silentJoinMessenger(int, MessengerCharacter, int position)` | 静默加入 | 仅 addMember |
| `Messenger createMessenger(MessengerCharacter)` | 创建信使 | 自增 id、new Messenger、登记 |
| `boolean isConnected(String charName)` | 角色在线判断 | 玩家存储按名查 |
| `BuddyAddResult requestBuddyAdd(String addName, int channelFrom, int cidFrom, String nameFrom)` | 好友申请 | 目标列表满返回 BUDDYLIST_FULL；未包含则 addBuddyRequest；已可见返回 ALREADY_ON_LIST |
| `void buddyChanged(int cid, int cidFrom, String, int channel, BuddyOperation)` | 好友状态变更 | ADDED/DELETED 更新好友条目并广播频道变化 |
| `void loggedOff(String, int, int, int[])` / `loggedOn(...)` | 好友上下线广播 | 委托 updateBuddies |
| `private void updateBuddies(int, int, int[], boolean offline)` | 上下线通知实现 | 遍历好友，可见者更新 channel 并发包 |
| `private static Integer getPetKey(Character, byte petSlot)` | 宠物注册键 | (chrId<<2)+petSlot（假设最多 3 宠） |
| `void addOwlItemSearch(Integer itemid)` | 猫头鹰搜索计数 | suggest 写锁 merge +1 |
| `List<Pair<Integer,Integer>> getOwlSearchedItems()` | 搜索热榜 | use_enforce_item_suggestion 时返回空；否则读锁转列表 |
| `void addCashItemBought(Integer snid)` | 商城购买计数 | 按 snid/10000000 分页 merge |
| `private List<List<Pair<Integer,Integer>>> getBoughtCashItems()` | 购买统计 | 同上空保护；读锁导出 9 页 |
| `private List<Integer> getMostSellerOnTab(List<Pair<Integer,Integer>>)` | 页内 Top5 | PriorityQueue 降序取前 5 |
| `List<List<Integer>> getMostSellerCashItems()` | 商城热卖榜 | 每页不足 5 用全服榜或 GameConstants.CASH_DATA 填充 |
| `void registerPetHunger(Character, byte petSlot)` | 注册宠物饥饿 | GM/全局免饿配置跳过；按距上次调度时长预扣计数（>55s 则 -2 否则 -1）写入 activePets |
| `void unregisterPetHunger(Character, byte)` | 注销宠物饥饿 | 移除键 |
| `void runPetSchedule()` | 宠物调度（1min） | 快照 activePets；计数+1 达 pet_exhaust_count 时 chr.runFullnessSchedule(slot) 并清零 |
| `void registerMountHunger(Character)` / `unregisterMountHunger(Character)` / `runMountSchedule()` | 坐骑疲劳 | 同宠物模式，达 mount_exhaust_count 调 chr.runTirednessSchedule() |
| `void registerPlayerShop(PlayerShop)` / `unregisterPlayerShop(PlayerShop)` / `List<PlayerShop> getActivePlayerShops()` / `PlayerShop getPlayerShop(int ownerid)` | 玩家商店注册表 | activePlayerShopsLock |
| `boolean registerHiredMerchant(HiredMerchant)` | 注册雇佣商店 | 已存在返回 false；距上次调度>5min 计 1 否则 0（近似在店时长） |
| `boolean unregisterHiredMerchant(HiredMerchant)` | 注销商店 | 引用相同才移除 |
| `boolean isHiredMerchantRegistered(HiredMerchant)` | 是否注册中 | 引用判等 |
| `void runHiredMerchantSchedule()` | 商店调度（10min） | 被封或超 144 tick（≈24h）的商店收集后，锁外逐个 forceClose（注释：收店需客户端/物品锁，不能持注册表锁等待） |
| `List<HiredMerchant> getActiveMerchants()` / `HiredMerchant getHiredMerchant(int ownerid)` | 商店查询 | 读锁过滤 isOpen / 按 owner |
| `void registerTimedMapObject(Runnable, long duration)` | 注册限时对象 | 记录过期时间 |
| `void runTimedMapObjectSchedule()` | 限时对象调度 | 到期项锁内移除、锁外 run() |
| `void addPlayerHpDecrease(Character)` / `removePlayerHpDecrease(Character)` | 毒图 HP 递减登记 | WeakHashMap putIfAbsent/remove |
| `void runPlayerHpDecreaseSchedule()` | HP 递减调度 | 非 away 角色计数取模 map_damage_overtime_count，为 0 时 chr.doHurtHp() |
| `void resetDisabledServerMessages()` | 清空公告禁用表 | srvMessagesLock |
| `boolean registerDisabledServerMessage(int chrid)` | 禁用公告（打 BOSS 血条） | 返回是否已禁用 |
| `boolean unregisterDisabledServerMessage(int)` | 解除禁用 | remove != null |
| `void runDisabledServerMessagesSchedule()` | 公告禁用调度（10s） | 计数≥4（约 35s）后移除并重新推送当前 serverMessage |
| `void setPlayerNpcMapStep(int mapid, int step)` / `setPlayerNpcMapPodiumData(int, int)` / `setPlayerNpcMapData(int, int, int)` | 更新 PlayerNPC 数据 | 均委托私有四参 setPlayerNpcMapData（不落库变体） |
| `private static void executePlayerNpcMapDataUpdate(boolean isPodium, Map, int value, int worldId, int mapId)` | 持久化 PlayerNPC 数据 | 已有记录 updateByQuery（world+map 条件），否则 insert |
| `private void setPlayerNpcMapData(int mapId, int step, int podium, boolean silent)` | 内存+DB 更新 | silent=false 时先落库再更新内存 map |
| `int getPlayerNpcMapStep(int)` / `getPlayerNpcMapPodiumData(int)` / `void resetPlayerNpcMapData()` | 查询/清空 | 缺省 0 / 1 |
| `void setServerMessage(String)` | 世界公告 | 广播到全部频道 |
| `void broadcastPacket(Packet)` | 世界广播 | 遍历 players 全员发包 |
| `List<Pair<PlayerShopItem,AbstractMapObject>> getAvailableItemBundles(int itemid)` | 全服商品搜索（猫头鹰） | 聚合雇佣商店与玩家商店在售商品，按价格升序（截取前 200） |
| `private void pushRelationshipCouple(Pair)` | 缓存婚姻关系 | 写 relationshipCouples 与双向 relationships |
| `Pair<Integer,Integer> getRelationshipCouple(int relationshipId)` | 婚姻id→新人 | 未命中查 DB 后缓存 |
| `int getRelationshipId(int playerId)` | 玩家→婚姻id | 同上（按夫/妻查）；无返回 -1 |
| `private static Pair<…> getRelationshipCoupleFromDb(int id, boolean usingMarriageId)` | DB 查询 marriages | 两种条件查询 |
| `int createRelationship(int groomId, int brideId)` | 结婚登记 | INSERT marriages 取自增 id 并缓存 |
| `private static int addRelationshipToDb(int, int)` | DB 插入 | RETURN_GENERATED_KEYS |
| `void deleteRelationship(int playerId, int partnerId)` | 离婚 | 删 DB 行并清缓存 |
| `private static void deleteRelationshipFromDb(int)` | DB 删除 | DELETE marriages |
| `void dropMessage(int type, String)` | 世界消息 | 全员 dropMessage |
| `boolean registerFisherPlayer(Character, int baitLevel)` | 注册钓鱼者 | 已注册返回 false |
| `int unregisterFisherPlayer(Character)` | 注销钓鱼 | 返回鱼饵等级（无则 0） |
| `void runCheckFishingSchedule()` | 钓鱼调度（10s） | 取 Fishing.fetchFishingLikelihood 概率，逐人 unregister 后 doFishing |
| `void runPartySearchUpdateSchedule()` | 组队搜索调度 | partySearch.updatePartySearchStorage() + runPartySearch() |
| `BaseService getServiceAccess(WorldServices sv)` | 世界调度服务 | services.getAccess |
| `private void closeWorldServices()` / `clearWorldData()` | 关停清理 | services.shutdown；clearWorldData 快照 parties 后关闭服务 |
| `final void shutdown()` | 世界停服 | 逐频道 shutdown；取消全部 12 个 ScheduledFuture；players.disconnectAll 并置空；clearWorldData；日志输出 |

### 3.2 `Party`

**概述**：队伍领域对象：成员列表、历史顺序（用于队伍门槽位）、队伍门（Door）、PQ 资格成员快照、敌方队伍（CPQ）。所有成员操作由公平 ReentrantLock 保护。另含四个静态入口封装建队/入队/离队/踢人完整流程。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `Party(int id, PartyCharacter chrfor)` | 构造 | leaderId=创建者 id |
| `boolean containsMembers(PartyCharacter)` | 成员判断 | lock + List.contains（按名字 equals） |
| `void addMember(PartyCharacter)` | 加成员 | 记录 histMembers 顺序号后加入 |
| `void removeMember(PartyCharacter)` | 移除成员 | 联动移除历史记录 |
| `void setLeader(PartyCharacter)` | 设队长 | leaderId 赋值 |
| `void updateMember(PartyCharacter)` | 更新成员快照 | 按 id 替换列表项 |
| `PartyCharacter getMemberById(int)` | 按 id 查成员 | 遍历 |
| `Collection<PartyCharacter> getMembers()` / `List<PartyCharacter> getPartyMembers()` | 成员快照 | lock 内新建 LinkedList |
| `List<PartyCharacter> getPartyMembersOnline()` | 在线成员 | 过滤 isOnline |
| `Collection<PartyCharacter> getEligibleMembers()` / `void setEligibleMembers(List)` | PQ 资格成员 | pqMembers 快照读写 |
| `PartyCharacter getLeader()` | 取队长对象 | 按 leaderId 在成员中查找 |
| `List<Integer> getMembersSortedByHistory()` | 按入队顺序排序的 id | histMembers 按序号排序 |
| `byte getPartyDoor(int cid)` | 成员的队伍门槽位 | 在历史序列中的下标 |
| `void addDoor(Integer owner, Door)` / `removeDoor(Integer)` / `Map<Integer,Door> getDoors()` | 队伍门管理 | lock 保护 doors |
| `void assignNewLeader(Client c)` | 自动选新队长 | 选等级最高的非队长成员后 world.updateParty(CHANGE_LEADER) |
| `int hashCode()` / `boolean equals(Object)` | 以 id 判等 | — |
| `PartyCharacter getMemberByPos(int pos)` | 按位置取成员 | 遍历计数 |
| `static boolean createParty(Character player, boolean silentCheck)` | 创建队伍入口 | 等级<10 且未开新手组队则拒绝；Ariant 竞技场中拒绝；经 world.createParty 建队并回发 partyCreated 包 |
| `static boolean joinParty(Character player, int partyid, boolean silentCheck)` | 加入队伍入口 | 队伍存在且 <6 人；updateParty(JOIN) 并同步 HP；否则提示已满/已解散/已有队 |
| `static void leaveParty(Party party, Client c)` | 离队入口 | 队长离队→DISBAND（先 removeMapPartyMembers、CPQ leftParty、eim.disbandParty）；成员离队→LEAVE（地图移除、CPQ/事件通知）；若正进行家族创建确认则 dismissMatchConfirmation |
| `static void expelFromParty(Party party, Client c, int expelCid)` | 踢人入口 | 仅队长；对被踢者做地图/CPQ/事件清理后 updateParty(EXPEL) |

### 3.3 `PartyCharacter`

**概述**：队伍成员值对象（Character 的轻量快照，名字判等）。字段：`name/final`、`id/level/channel/world/jobid/mapid/online/job/character`。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `PartyCharacter(Character maplechar)` | 由角色构造 | 拷贝名字/等级/频道/世界/职业/地图，online=true |
| `PartyCharacter()` | 空构造 | name="" |
| `Character getPlayer()` | 取关联角色 | 返回引用 |
| `Job getJob()` / `int getLevel()` / `int getChannel()` / `void setChannel(int)` | 基本属性 | getter/setter |
| `boolean isLeader()` | 是否队长 | getPlayer().isPartyLeader() |
| `boolean isOnline()` / `void setOnline(boolean)` | 在线状态 | 置 offline 时清空 character 引用（防内存驻留） |
| `int getMapId()` / `void setMapId(int)` / `String getName()` / `int getId()` / `int getJobId()` / `int getGuildId()` / `int getWorld()` | 属性 | getGuildId 直接透传 character |
| `int hashCode()` / `boolean equals(Object)` | 以 name 判等 | — |

### 3.4 `PartyOperation`（枚举）

**概述**：队伍操作类型：`JOIN / LEAVE / EXPEL / DISBAND / SILENT_UPDATE / LOG_ONOFF / CHANGE_LEADER`。

### 3.5 `Messenger`

**概述**：信使（好友群聊窗口）对象，最多 3 个位置槽（`pos[3]` 布尔占用表）。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `Messenger(int id, MessengerCharacter chrfor)` | 构造 | 初始化槽位表并 addMember 创建者 |
| `int getId()` | 信使 id | — |
| `Collection<MessengerCharacter> getMembers()` | 成员只读视图 | unmodifiableList |
| `void addMember(MessengerCharacter, int position)` | 加成员 | 设置成员 position 并占槽 |
| `void removeMember(MessengerCharacter)` | 移除成员 | 释放槽位并移除 |
| `int getLowestPosition()` | 最低空闲槽 | 扫描 pos，满返回 -1 |
| `int getPositionByName(String)` | 按名查槽位 | 遍历成员，无返回 -1 |

### 3.6 `MessengerCharacter`

**概述**：信使成员值对象。字段：`name/final`、`id`、`position`、`channel/final`、`online/final(true)`。构造器 `(Character maplechar, int position)` 拷贝名字/频道/id。方法：`getId()/getChannel()/getName()/isOnline()/getPosition()/setPosition(int)`；hashCode/equals 以 name 判等。

---

## 4. guild 包

### 4.1 `Guild`

**概述**：家族领域对象，含成员列表（membersLock 公平锁保护）、头衔/徽章/GP/公告/容量等属性、按频道分组的广播通知表（notifications，bDirty 脏标记），以及邀请/建族等静态流程。构造器从 guilds 与 characters 表装载。内部枚举 `BCOp { NONE, DISBAND, EMBLEMCHANGE }` 标识广播附带动作。

**关键字段**：`members/membersLock`、`rankTitles[5]`、`name/notice`、`id/gp/logo/logoColor/leader/capacity/logoBG/logoBGColor/signature/allianceId`、`world/final`、`notifications`（频道→在线成员 id）、`bDirty`。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `Guild(int guildid, int world)` | 构造（DB 装载） | 查 guilds 表填充属性（无记录 id=-1）；查 characters 表按 guildrank 序装载成员 GuildCharacter |
| `private void buildNotifications()` | 重建频道通知表 | bDirty 为假直接返回；按 Server.getOpenChannels 重建/清空 notifications；遍历在线成员按频道登记；置 bDirty=false |
| `boolean writeToDB(boolean bDisband)` | 持久化 | bDisband 走 disbandOnDb；否则 UPDATE guilds 全字段（GP/logo/5 头衔/capacity/notice） |
| `private boolean disbandOnDb()` | 解散事务 | 事务内：FOR UPDATE 校验 allianceId==0 → 成员 guildid/rank 复位 → DELETE guilds；失败回滚并恢复 autoCommit |
| `int getId()` / `int getLeaderId()` / `int setLeaderId(int)` / `int getGP()` / logo 系列 getter/setter / `String getNotice()` / `String getName()` / `List<GuildCharacter> getMembers()` / `int getCapacity()` / `int getSignature()` | 属性存取 | getMembers 返回拷贝 |
| `void broadcastNameChanged()` | 广播家族名变更 | 对每个在线成员所在地图发 guildNameChanged |
| `void broadcastEmblemChanged()` | 广播徽章变更 | 同上发 guildMarkChanged |
| `void broadcastInfoChanged()` | 广播家族面板 | 在线成员发 showGuildInfo |
| `void broadcast(Packet)` / `broadcast(Packet, int exception)` | 家族广播 | 委托三参版本 |
| `void broadcast(Packet, int exceptionId, BCOp bcop)` | 带动作广播 | membersLock 内取通知表快照；锁外逐频道执行：DISBAND→world.setGuildAndRank(targets,0,5)、EMBLEMCHANGE→world.changeEmblem、否则 world.sendPacket（快照+锁外，避免锁序反转） |
| `void guildMessage(Packet)` | 全员发包 | 遍历成员在各频道玩家存储查找后发送 |
| `void dropMessage(String)` / `dropMessage(int, String)` | 家族消息 | 遍历有 Character 引用的成员 |
| `void broadcastMessage(Packet)` | 发包 | Server.getInstance().guildMessage(id, packet) |
| `final void setOnline(int cid, boolean online, int channel)` | 成员上下线 | 更新成员 channel/online；状态变化时广播 guildMemberOnline；置 bDirty |
| `void guildChat(String name, int cid, String message)` | 家族聊天 | broadcast(multiChat type=2) |
| `String getRankTitle(int rank)` | 头衔查询 | rankTitles[rank-1] |
| `static int createGuild(int leaderId, String name)` | 创建家族（DB） | 重名返回 0；INSERT guilds → 回查 guildid → UPDATE characters 设置族长 guildid |
| `int addGuildMember(GuildCharacter, Character)` | 加成员 | 容量满返回 0；按 rank/名字插入有序位置；broadcast newGuildMember |
| `void leaveGuild(GuildCharacter)` | 成员退族 | broadcast memberLeft(expelled=false) 后移除 |
| `void expelMember(GuildCharacter initiator, String name, int cid, NoteService)` | 开除成员 | 发起者 rank 必须更小；在线走 setGuildAndRank(0,5)，离线发 Note 并 setOfflineGuildStatus |
| `void changeRank(int cid, int newRank)` / `changeRank(GuildCharacter, int)` | 改 rank | 在线/离线分别走 world.setGuildAndRank / setOfflineGuildStatus；广播 changeRank |
| `void setGuildNotice(String)` | 设置公告 | 写 DB + broadcast guildNotice |
| `void memberLevelJobUpdate(GuildCharacter)` | 成员等级/职业更新 | 按 equals 匹配成员并更新，广播 |
| `boolean equals(Object)` / `int hashCode()` | 与 GuildCharacter 混合比较 | 按 id+name（历史遗留） |
| `void changeRankTitle(String[] ranks)` | 改 5 个头衔 | arraycopy → 广播 → writeToDB |
| `boolean disbandGuild()` | 解散家族 | 有联盟走 Alliance.disbandGuild(this, world)，否则 writeToDB(true)；成功后广播 guildDisband + BCOp.DISBAND（清除成员状态） |
| `void setGuildEmblem(short bg, byte bgcolor, short logo, byte logocolor)` | 设置徽章 | 更新字段写 DB，广播 BCOp.EMBLEMCHANGE |
| `GuildCharacter getMGC(int cid)` | 按 id 取成员 | 遍历 |
| `boolean increaseCapacity()` | 扩容 +5 | 上限 99+；写 DB 并广播 guildCapacityChange |
| `void gainGP(int)` / `void removeGP(int)` | GP 增减 | 更新写 DB 并 guildMessage updateGP/getGPMessage |
| `static GuildResponse sendInvitation(Client c, String targetName)` | 发送入族邀请 | 目标不在频道→NOT_IN_CHANNEL；已有族→ALREADY_IN_GUILD；经 InviteCoordinator(GUILD) 发 guildInvite；占用中→MANAGING_INVITE；成功返回 null |
| `static boolean answerInvitation(int targetId, String targetName, int guildId, boolean answer)` | 应答邀请 | answerInvite 结果 ACCEPTED 返回 true；DENIED/NOT_FOUND 向发送者发对应包 |
| `static Set<Character> getEligiblePlayersForGuild(Character guildLeader)` | 同图可入族玩家 | 无队伍无族且不在匹配确认中的玩家集合 |
| `static void displayGuildRanks(Client c, int npcid)` | NPC 展示家族排行 | GP 降序前 50 发 showGuildRanks |
| `int getAllianceId()` / `void setAllianceId(int aid)` | 联盟 id | set 版本更新内存并 UPDATE guilds |
| `void setAllianceIdInMemory(int)` | 仅内存更新 | 字段赋值 |
| `void resetAllianceGuildPlayersRankInMemory()` | 成员联盟 rank 复位 | 在线成员 allianceRank=5 |
| `static int getIncreaseGuildCost(int size)` | 扩族费用 | base + 档位费；>30 人时钳制在 [500万, max] |

### 4.2 `GuildCharacter`

**概述**：家族成员值对象（含对 Character 的可选引用）。字段：`character/level/id(final)/world/channel/jobid/guildrank/guildid/allianceRank/online/name(final)`。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `GuildCharacter(Character chr)` | 由在线角色构造 | 全量拷贝且 online=true |
| `GuildCharacter(Character, int _id, int _lv, String _name, int _channel, int _world, int _job, int _rank, int _gid, boolean _on, int _allianceRank)` | 全参构造 | DB 装载用；仅 _on 时记录 channel/world |
| `void setCharacter(Character)` / `Character getCharacter()` | 角色引用 | — |
| `int getLevel()` / `void setLevel(int)` | 等级 | — |
| `int getId()` | 角色 id | — |
| `int getChannel()` / `void setChannel(int)` | 频道 | — |
| `int getWorld()` | 世界 | — |
| `int getJobId()` / `void setJobId(int)` | 职业 | — |
| `int getGuildId()` / `void setGuildId(int gid)` | 家族 id | set 时联动 character.setGuildId 并刷新"优秀市民"勋章资格 |
| `int getGuildRank()` / `void setOfflineGuildRank(int)` / `void setGuildRank(int)` | 家族 rank | setGuildRank 联动 character |
| `int getAllianceRank()` / `void setOfflineAllianceRank(int)` / `void setAllianceRank(int)` | 联盟 rank | setAllianceRank 联动 character |
| `boolean isOnline()` / `void setOnline(boolean)` | 在线状态 | — |
| `String getName()` | 名字 | — |
| `int hashCode()` / `boolean equals(Object)` | id+name 判等 | — |

### 4.3 `GuildSummary`

**概述**：家族摘要值对象（避免持有完整 Guild）。构造器 `GuildSummary(Guild g)` 拷贝 name/logoBG/logoBGColor/logo/logoColor/allianceId；提供对应 6 个 getter。

### 4.4 `GuildResponse`（枚举）

**概述**：家族操作错误码：`NOT_IN_CHANNEL(0x2a)/ALREADY_IN_GUILD(0x28)/NOT_IN_GUILD(0x2d)/NOT_FOUND_INVITE(0x2e)/MANAGING_INVITE(0x36)/DENIED_INVITE(0x37)`。唯一方法 `Packet getPacket(String targetName)`：value≥0x36（带目标名类）走 `GuildPackets.responseGuildMessage`，否则 `genericGuildMessage`。

### 4.5 `GuildPackets`

**概述**：家族/联盟/BBS 协议包工厂（全静态）。核心方法：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `static Packet showGuildInfo(Character chr)` | 家族面板包 | 0x1A；空角色/无族写 bInGuild=0；否则写 id/名/5 头衔/成员（两轮：id 列表+详细信息）/容量/徽章/公告/GP/allianceId |
| `static Packet guildMemberOnline(int guildId, int chrId, boolean)` | 成员上下线 | 0x3d |
| `static Packet guildInvite(int guildId, String charName)` | 入族邀请 | 0x05 |
| `static Packet createGuildMessage(String masterName, String guildName)` | 建族确认弹窗 | 0x3 |
| `static Packet genericGuildMessage(byte code)` | 通用错误码消息 | 直接写 code（28/31/33/35/36/38/41/42/45/48/52/56/57 等） |
| `static Packet responseGuildMessage(byte code, String targetName)` | 带目标名错误 | 53/54/55 |
| `static Packet newGuildMember(GuildCharacter)` | 新成员通知 | 0x27，13 字节定长名 |
| `static Packet memberLeft(GuildCharacter, boolean bExpelled)` | 离族/被踢 | 0x2c/0x2f |
| `static Packet changeRank(GuildCharacter)` | rank 变更 | 0x40 |
| `static Packet guildNotice(int, String)` | 公告 | 0x44 |
| `static Packet guildMemberLevelJobUpdate(GuildCharacter)` | 等级/职业更新 | 0x3C |
| `static Packet rankTitleChange(int, String[])` | 头衔变更 | 0x3E |
| `static Packet guildDisband(int)` | 解散 | 0x32 |
| `static Packet guildQuestWaitingNotice(byte channel, int waitingPos)` | 家族任务排队 | 0x4C |
| `static Packet guildEmblemChange(int, short, byte, short, byte)` | 徽章变更 | 0x42 |
| `static Packet guildCapacityChange(int, int)` | 容量变更 | 0x3A |
| `static void addThread(OutPacket, ResultSet)` | BBS 帖子条目 | 写 localthreadid/postercid/name/时间/icon/回复数 |
| `static Packet BBSThreadList(ResultSet, int start)` | BBS 帖子列表 | 0x06；处理置顶公告、分页（每页 10） |
| `static Packet showThread(int, ResultSet, ResultSet)` | BBS 帖子详情 | 0x07；校验回复数与结果集一致否则抛异常 |
| `static Packet showGuildRanks(int npcid, ResultSet)` | 家族排行榜 | 0x49；GP 降序前 50 |
| `static Packet showPlayerRanks(int npcid, List<Pair<String,Integer>>)` | 玩家排行榜 | 0x49 |
| `static Packet updateGP(int, int)` | GP 更新 | 0x48 |
| `static void getGuildInfo(OutPacket, Guild)` | 家族信息写入复用 | 供联盟包内嵌 |
| `static Packet getAllianceInfo(Alliance)` / `updateAllianceInfo(Alliance, int world)` | 联盟信息/更新 | ALLIANCE_OPERATION 0x0C/0x0F |
| `static Packet getGuildAlliances(Alliance, int worldId)` | 联盟成员族列表 | 0x0D |
| `static Packet addGuildToAlliance(Alliance, int newGuild, Client)` | 家族入盟 | 0x12 |
| `static Packet allianceMemberOnline(Character, boolean)` | 联盟成员上下线 | 0x0E |
| `static Packet allianceNotice(int, String)` | 联盟公告 | 0x1C |
| `static Packet changeAllianceRankTitle(int, String[])` | 联盟头衔 | 0x1A |
| `static Packet updateAllianceJobLevel(Character)` | 联盟成员等级/职业 | 0x18 |
| `static Packet removeGuildFromAlliance(Alliance, int, int)` | 家族退盟 | 0x10 |
| `static Packet disbandAlliance(int)` | 联盟解散 | 0x1D |
| `static Packet allianceInvite(int allianceid, Character)` | 联盟邀请 | 0x03 |
| `static Packet GuildBoss_HealerMove(short nY)` / `GuildBoss_PulleyStateChange(byte)` | 家族 BOSS 机关 | 专用 opcode |
| `static Packet guildNameChanged(int chrid, String)` / `guildMarkChanged(int, Guild)` | 名/徽章更新广播 | GUILD_NAME_CHANGED / GUILD_MARK_CHANGED |
| `static Packet sendShowInfo / sendInvitation / sendChangeGuild / sendChangeLeader / sendChangeRank(…)` | 联盟 NPC 交互系列 | 0x02/0x05/0x07/0x08/0x09 |

### 4.6 `Alliance`

**概述**：家族联盟领域对象。字段：`guilds:List<Integer>`（synchronized 保护）、`allianceId/capacity/name/notice/rankTitles[5]`，方法级 synchronized。所有 DB 变更均使用事务 + FOR UPDATE 乐观校验 + rollback/restoreAutoCommit 辅助。内部枚举 `GuildRemovalResult { SUCCESS, LEADER_GUILD, FAILED }`。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `Alliance(String name, int id)` | 构造 | 默认 5 级头衔 Master/Jr.Master/Member×3 |
| `static boolean canBeUsedAllianceName(String)` | 名称可用性 | 不含空格、≤12 字、DB 无重名 |
| `private static List<Character> getPartyGuildMasters(Party)` | 队伍中的两名族长 | 过滤 guildRank==1 且与队长同图；把队长族长排到首位 |
| `static synchronized Alliance createAlliance(Party, String name, int creationCost)` | 创建联盟 | 需恰好 2 名同图族长；payer.spendMesoTransactionally 包裹 createAllianceOnDb 事务；成功后注册 Server 缓存、设置两族 allianceId/成员联盟 rank、广播联盟信息 |
| `private static int createAllianceOnDb(...)` | 建盟事务 | INSERT alliance（防重名 NOT EXISTS）→ FOR UPDATE 校验两族可入盟 → 清旧 allianceguilds → 更新 guilds.allianceId → 插入 allianceguilds → 批量设置 characters.allianceRank → 扣费 commit |
| `static Alliance loadAlliance(int id)` | DB 装载联盟 | 读 alliance 表属性 + allianceguilds 联表 guilds 校验装载族列表（超出容量告警） |
| `synchronized boolean updateRankTitles(String[])` | 更新头衔 | 仅拼装有变化字段的 UPDATE；成功后克隆更新内存 |
| `synchronized boolean updateNotice(String)` | 更新公告 | 无变化短路；UPDATE 后同步内存 |
| `synchronized boolean changeLeader(Character current, Character newLeader)` | 换盟主 | 校验当前盟主(联盟rank1+族长)与新领袖(族长+联盟rank2)；事务内 FOR UPDATE 锁 alliance、校验唯一盟主、两人 rank 互换；成功后同步内存 MGC |
| `static boolean isAllianceMissing(int)` | 联盟是否存在 | SELECT 1 LIMIT 1 |
| `synchronized boolean addGuildAndSave(int guildId, int guildMasterId)` | 家族入盟（入口） | 容量/去重校验后 saveGuildJoin，成功加入内存列表 |
| `synchronized boolean removeGuildAndSave(int guildId)` | 家族退盟（入口） | 存在性校验后 saveGuildRemoval，成功移出内存 |
| `private boolean saveGuildJoin(int, int)` | 入盟事务 | FOR UPDATE 锁容量并比对 → 计数校验未满 → 更新 guilds.allianceId（校验族长）→ 清/插 allianceguilds → 成员 allianceRank（族长 2 其余 5） |
| `private boolean saveGuildRemoval(int)` | 退盟事务 | 锁 alliance → guilds.allianceId 复位 → 删 allianceguilds → 成员 allianceRank=5 |
| `static boolean disbandGuild(Guild disbandingGuild, int worldId)` | 解散联盟内某家族 | 区分解散者是否盟族：盟族→解散整个联盟（复位所有族、删 alliance）；否则仅退盟；均事务执行并广播对应包/更新内存 |
| `static boolean disbandAlliance(int allianceId)` | 解散整个联盟 | 事务复位全部成员 rank、清 guilds.allianceId、删 allianceguilds/alliance；广播 disbandAlliance 并 Server.removeAllianceFromMemory |
| `static GuildRemovalResult removeGuildFromAlliance(int allianceId, int guildId, int worldId)` | 家族被移出联盟 | 盟族不可被移除（LEADER_GUILD）；Server.removeGuildFromAlliance 后广播退盟/列表/公告包并全盟提示 |
| `void updateAlliancePackets(Character)` | 推送联盟信息 | 广播 updateAllianceInfo + allianceNotice |
| `boolean removeGuild(int gid)` / `boolean addGuild(int gid)` / `private int getGuildIndex(int)` | 内存族列表操作 | synchronized(guilds) |
| `String getRankTitle(int)` / `List<Integer> getGuilds()` / `String getAllianceNotice()` / `getNotice()` | 属性查询 | getGuilds 过滤 -1 |
| `synchronized boolean purchaseCapacity(Character buyer, int cost, int maxCapacity)` | 购买扩容 | 校验买家盟主身份/未达上限/本族在盟；spendMesoTransactionally 包裹 saveCapacityPurchase（条件 UPDATE capacity）成功后内存 capacity+1 |
| `private boolean saveCapacityPurchase(int buyerId, int balanceAfter, int expected, int newCap, int maxCap)` | 扩容事务 | `UPDATE alliance SET capacity=? WHERE id=? AND capacity=? AND capacity<?` 乐观锁 + 扣费 |
| `private boolean addLoadedGuild(int)` | 装载期加族 | 不查容量 |
| `private synchronized void setCapacity(int)` / `synchronized int getCapacity()` | 容量存取 | — |
| `private static void updateCharacterMeso(Connection, int characterId, int balanceAfter)` | 事务内扣费 | UPDATE characters SET meso |
| `private static void rollback(Connection, Exception)` / `restoreAutoCommit(Connection, boolean, String)` | 事务辅助 | 回滚时收集 suppressed；恢复 autoCommit |
| `int getId()` / `String getName()` | 标识 | — |
| `GuildCharacter getLeader()` | 查盟主 | 遍历成员族找 allianceRank==1 的族长 MGC |
| `private int getLeaderGuildId()` | 盟族 id | 内存查不到则 findLeaderGuildId 查 DB |
| `synchronized boolean canRemoveGuild(int guildId)` | 可否移除 | 盟主族不可移除 |
| `private static int findLeaderGuildId(Connection, int allianceId)` | DB 查唯一盟族 | 多盟主抛异常 |
| `void dropMessage(String)` / `dropMessage(int, String)` | 联盟消息 | 逐族 Guild.dropMessage |
| `void broadcastMessage(Packet)` | 联盟广播 | Server.allianceMessage |
| `static void sendInvitation(Client c, String targetGuildName, int allianceId)` | 联盟邀请 | 目标族不存在/已入盟/族长不在线分别提示；经 InviteCoordinator(ALLIANCE) 发 allianceInvite |
| `static boolean answerInvitation(int targetId, String targetGuildName, int allianceId, boolean answer)` | 应答联盟邀请 | answerInvite(ALLIANCE)；拒绝/过期向发送者提示 |

---

## 5. coordinator 包

### 5.1 session 子包（10 类）

#### `SessionCoordinator`（单例）

**概述**：会话协调器，负责登录/游戏会话生命周期与防多开（AntiMulticlient）。内部枚举 `AntiMulticlientResult { SUCCESS, REMOTE_LOGGEDIN, REMOTE_REACHED_LIMIT, REMOTE_PROCESSING, REMOTE_NO_MATCH, MANY_ACCOUNT_ATTEMPTS, COORDINATOR_ERROR }`。

**关键字段**：`sessionInit:SessionInitialization`、`loginStorage:LoginStorage`、`onlineClients:Map<IntegeraccountId,Client>`、`onlineRemoteHwids:Set<Hwid>`、`loginRemoteHosts:ConcurrentHashMap<String,Client>`、`hostHwidCache:HostHwidCache`。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `static SessionCoordinator getInstance()` | 单例 | 饿汉 |
| `private static boolean attemptAccountAccess(int accountId, Hwid, boolean routineCheck)` | HWID 账号关联校验 | SessionDAO.getHwidRelevance；命中（后缀匹配）且非例行检查时更新 relevance+expiry；未命中但关联 HWID 数 < max_allowed_account_hwid 也放行 |
| `static String getSessionRemoteHost(Client)` | 会话远端标识 | 远端 IP（+ "-" + hwid，若有） |
| `void updateOnlineClient(Client)` | 登记在线客户端 | 先 forceDisconnect 同账号旧会话再 put |
| `private void disconnectClientIfOnline(int accountId)` | 踢同账号会话 | onlineClients 命中即 forceDisconnect |
| `boolean canStartLoginSession(Client)` | 能否开始登录会话 | 未开 deterred_multi_client 直接 true；sessionInit.initialize 失败按结果处理；HWID 已在线或 host 已在登录则拒绝；登记 loginRemoteHosts，finally finalize |
| `void closeLoginSession(Client)` | 关闭登录会话 | 清 loginRemoteHosts；摘除在线 HWID；仅当在线 client 的 sessionId 一致时才移除 onlineClients（不影响游戏会话） |
| `private void clearLoginRemoteHost(Client)` | 清登录 host 记录 | 同时按裸 IP 与 IP-hwid 两键移除 |
| `AntiMulticlientResult attemptLoginSession(Client, Hwid, int accountId, boolean routineCheck)` | 登录会话校验 | 依次：loginStorage.registerLogin（防爆破）→ attemptAccountAccess（HWID 限额）→ onlineRemoteHwids 查重；通过则 client.setHwid 并登记 HWID |
| `AntiMulticlientResult attemptGameSession(Client, int accountId, Hwid)` | 游戏会话校验 | client 无 HWID→NO_MATCH；HWID 与登录时不一致→NO_MATCH；查重后重新登记 HWID、写 hostHwidCache（remoteHost 与裸 IP 两键）、associateHwidAccountIfAbsent |
| `private static void associateHwidAccountIfAbsent(Hwid, int accountId)` | 首次 HWID-账号关联 | 已含相同 HWID 跳过；未超 max_allowed_account_hwid 时 registerAccountAccess（relevance=0 过期策略） |
| `private static Client fetchInTransitionSessionClient(Client)` | 构造转频道伪客户端 | 无 HWID 时用 gameSessionHwid + Server.freeCharacteridInTransition 还原账号（用于 closeSession 兜底） |
| `void closeSession(Client, Boolean immediately)` | 关闭会话 | client 为 null 时走兜底；摘除 HWID；游戏会话直接移除 onlineClients，登录会话仅 sessionId 一致时移除；immediately=true 时 client.closeSession() |
| `Hwid pickLoginSessionHwid(Client)` | 取出登录 HWID | hostHwidCache.removeEntryAndGetItsHwid(裸 IP)（同网多玩家兼容） |
| `Hwid getGameSessionHwid(Client)` | 查游戏 HWID | hostHwidCache.getEntryHwid(remoteHost) |
| `void clearExpiredHwidHistory()` | 清过期 HWID 缓存 | 委托 HostHwidCache.clearExpired（LoginCoordinatorTask 周期调用） |
| `void runUpdateLoginHistory()` | 清过期登录尝试 | loginStorage.clearExpiredAttempts |
| `void printSessionTrace()` / `printSessionTrace(Client c)` | 调试输出 | 前者打日志；后者把会话清单通过 NPC 对话框展示 |

#### `SessionInitialization`

**概述**：基于远端 host 的会话初始化互斥：100 把分段 ReentrantLock + `remoteHostsInInitState` 集合，防止同 IP 并发初始化。`initialize(String)`：tryLock 重试至多 2 次（间隔 1777ms），获锁后若已初始化返回 `ALREADY_INITIALIZED`，否则登记并返回 SUCCESS；`finalize(String)`：加锁移除登记。失败返回 TIMED_OUT/ERROR。

#### `InitializationResult`（包级枚举）

**概述**：`SUCCESS / ALREADY_INITIALIZED / TIMED_OUT / ERROR`，各自映射一个 `AntiMulticlientResult`，`getAntiMulticlientResult()` 取映射。

#### `Hwid`（record）

**概述**：机器标识值对象 `record Hwid(String hwid)`。静态 `fromHostString(String)`：校验格式 `12位MAC_8位HWID`（正则 `[0-9A-F]{12}_[0-9A-F]{8}`），取下划线后半段构造。

#### `HwidRelevance`（record）

**概述**：`record HwidRelevance(String hwid, int relevance)`；`getIncrementedRelevance()` 在未达 Byte.MAX_VALUE 时 +1。

#### `HwidAssociationExpiry`

**概述**：计算 HWID-账号关联过期时间。`getHwidAccountExpiry(int relevance)`：当前时间 + 时长；`hwidExpirationUpdate(relevance)`：按度数（每 5 点升 1 度）给 2h/1d/7d/70d 基础 + 度数相关的亚度数小时；`getHwidExpirationDegree(int)`：贪心扣减 5*degree 求度数。

#### `HostHwid`（包级 record）

**概述**：`record HostHwid(Hwid hwid, Instant expiry)`；`createWithDefaultExpiry(Hwid)` 以当前服务器时间 +7 天构造。

#### `HostHwidCache`（包级）

**概述**：host→HostHwid 缓存（ConcurrentHashMap）。方法：`clearExpired()`（清过期项）、`addEntry(String, Hwid)`、`getEntry(String)`、`removeEntryAndGetItsHwid(String)`、`getEntryHwid(String)`。

#### `IpAddresses`

**概述**：IP 分类工具。`isLocalAddress(String)`：127. 前缀；`isLanAddress(String)`：匹配 10./192.168./172.16-31 正则列表。

#### `SessionDAO`

**概述**：hwidaccounts 表静态 DAO。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `static void deleteExpiredHwidAccounts()` | 清过期关联 | DELETE WHERE expiresat < CURRENT_TIMESTAMP |
| `static List<Hwid> getHwidsForAccount(Connection, int)` | 账号 HWID 列表 | SELECT hwid |
| `static void registerAccountAccess(Connection, int, Hwid, Instant)` | 登记关联 | INSERT（hwid 非空校验） |
| `static List<HwidRelevance> getHwidRelevance(Connection, int)` | 查关联+关联度 | SELECT * |
| `static void updateAccountAccess(Connection, Hwid, int, Instant, int)` | 更新关联度/过期 | UPDATE … WHERE hwid LIKE |

### 5.2 login 子包（2 类）

#### `LoginBypassCoordinator`（单例）

**概述**：PIN/PIC 免重复输入缓存（key: (Hwid, accountId) → (是否含 pic, 过期时间)）。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `static getInstance()` | 单例 | 饿汉 |
| `boolean canLoginBypass(Hwid, int accId, boolean pic)` | 可否免验 | 查表；pic 需要登记过 pic=true；NPE 返回 false |
| `void registerLoginBypassEntry(Hwid, int accId, boolean pic)` | 登记 | 过期分钟数取 bypass_pic/pin_expiration；compute 原子合并旧值（pic 或运算 + 取最大过期，防并发丢失 pic 状态） |
| `void unregisterLoginBypassEntry(Hwid, int accId)` | 注销 | 修复过的 key 类型（Pair<Hwid,Integer>），保证能真正删除 |
| `void runUpdateLoginBypass()` | 周期维护 | 收集在线账号；在线者过期时间顺延至 ≥now+2min；离线且过期者移除 |

#### `LoginStorage`

**概述**：账号登录尝试频控（key accountId → 尝试时间列表）。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `boolean registerLogin(int accountId)` | 记录一次尝试 | 尝试数超过 max_account_login_attempt 时全量刷新时间为新过期并返回 false（拒绝） |
| `void clearExpiredAttempts()` | 清过期 | 移除过期 Instant；列表空则移除账号键 |

### 5.3 matchchecker 子包（6 类）

#### `MatchCheckerCoordinator`

**概述**：多人确认流程协调器（建族确认、CPQ 挑战）。每个参与角色 cid→MatchCheckingElement；用 `semaphorePool:Semaphore(7)` 限流 + `pooledCids` 池化防并发操作同一玩家。内部类 `MatchCheckingEntry`（单角色已确认状态）、`MatchCheckingElement`（一次确认：leaderCid/world/matchType/listener/confirmingMembers/confirmCount/active/message/beginTime，dispatch 系列回调 listener）。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `int getMatchConfirmationLeaderid(int cid)` | 查确认发起者 | 无返回 -1 |
| `MatchCheckerType getMatchConfirmationType(int cid)` | 查确认类型 | 无返回 null |
| `boolean isMatchConfirmationActive(int cid)` | 确认是否激活 | element.active |
| `boolean createMatchConfirmation(MatchCheckerType, int world, int leaderCid, Set<Integer> players, String message)` | 发起确认 | 信号量+池化；isMatchingAvailable（13 分钟冷却：超时的旧 element 拒绝新匹配）校验后创建 element、登记全员、自动接受队长；成功后 dispatchMatchCreated 返回 true |
| `boolean answerMatchConfirmation(int cid, boolean accept)` | 应答 | 信号量+池化+element synchronized；accept 全员接受完毕→dispatchMatchResult(true)；deny 立即结束；已失活直接清理；结果 dispatchMatchResult(accept) |
| `boolean dismissMatchConfirmation(int cid)` | 取消确认 | 同上加锁模式；dispatchMatchDismissed |
| 私有：`poolMatchPlayer(s)/unpoolMatchPlayer(s)/isMatchingAvailable/reenablePlayerMatching/createMatchConfirmationInternal/disposeMatchElement/acceptMatchElement/denyMatchElement/dismissMatchElement` | 内部支撑 | 池化增删、元素登记/销毁（dispose 自旋等待全部玩家入池后移除条目）、接受/拒绝/解散分派 |

#### `AbstractMatchCheckerListener`（接口）

**概述**：匹配事件回调：`onMatchCreated(Character leader, Set<Character> nonLeaderMatchPlayers, String message)`、`onMatchAccepted(int leaderid, Set<Character>, String)`、`onMatchDeclined(int leaderid, Set<Character>, String)`、`onMatchDismissed(int leaderid, Set<Character>, String)`。

#### `MatchCheckerListenerFactory`

**概述**：持有枚举 `MatchCheckerType { GUILD_CREATION, CPQ_CHALLENGE }`，每个枚举值绑定一个 listener 实例，`getListener()` 获取。

#### `MatchCheckerListenerRecipe`（接口）

**概述**：listener 配方接口，唯一方法 `AbstractMatchCheckerListener getListener()`。

#### `MatchCheckerCPQChallenge`

**概述**：CPQ 挑战 listener 配方。`loadListener()` 静态装载。`getListener()` 返回实现：onMatchCreated 向对方队长 NPC 脚本发起 cpqchallenge/cpqchallenge2 并向挑战者确认；onMatchAccepted 由对方 cm.startCPQ/startCPQ2 开房并互设 enemy party；onMatchDeclined 提示被拒；onMatchDismissed 空实现。

#### `MatchCheckerGuildCreation`

**概述**：建族确认 listener 配方。`getListener()` 返回实现：onMatchCreated 向非队长成员发 createGuildMessage 确认弹窗；onMatchAccepted 扣 create_guild_cost、Guild.createGuild、族长 rank1、其余按是否同队给 cofounder(rank2)/rank5，全员 showGuildInfo+saveGuildStatus，最后广播名/徽章变更；onMatchDeclined 队长离队并全员提示 0x26；onMatchDismissed 依队长是否离队给出不同文案并全员离队提示。

### 5.4 partysearch 子包（4 类）

#### `PartySearchCoordinator`

**概述**：组队搜索（K析：队长挂搜索令牌，系统周期性从按职业/等级索引的候选池中挑选玩家发邀请）。

**关键字段**：`storage:Map<Job,PartySearchStorage>`（按 14+ 个搜索职业分类）、`upcomers:Map<Job,PartySearchEchelon>`（新登记者缓冲）、`leaderQueue`（队长队列，RW 锁）、`searchLeaders/searchSettings:Map<cid,…>`、`timeoutLeaders`（长期等待回收池）、静态 `mapNeighbors`（WZ MapNeighbors.img 邻接表）、静态 `jobTable`（搜索职业映射）。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `static boolean isInVicinity(int callerMapid, int calleeMapid)` | 两图是否邻近 | 邻接表命中或按地图号段比较（≥90 段按百万位） |
| `void attachPlayer(Character)` | 玩家登记可被搜索 | 加入其职业的 echelon |
| `void detachPlayer(Character)` | 玩家注销 | 先从 echelon 移除，失败再从 storage 移除 |
| `void updatePartySearchStorage()` | 刷新候选池 | 各职业 echelon.exportEchelon → storage.updateStorage |
| `void registerPartyLeader(Character, int minLevel, int maxLevel, int jobs)` | 队长发起搜索 | 构造 LeaderSearchMetadata（按位解码职业选择），入 searchLeaders 与队首队列 |
| `void unregisterPartyLeader(Character)` | 队长停止搜索 | 从活动表移除；否则视为长期等待者移除 |
| `void runPartySearch()` | 周期执行搜索 | fetchQueuedLeaders 取前 100；逐队长 searchPlayer（打乱职业序、按等级二分检索）→ sendPartyInviteFromSearch（InviteCoordinator PARTY + 禁止重复邀请表）；未命中者 reentryCount 递增重回队列 25 位，超 party_search_reentry_limit 进入 timeoutLeaders（每 77 轮 reinstate 重新入队）；命中者队伍未满重新排队，满则结束并提示 |
| 私有：`fetchNeighbouringMaps/instantiateJobTable/getPartySearchJob/fetchPlayer/addQueueLeader/removeQueueLeader/searchPlayer/sendPartyInviteFromSearch/fetchQueuedLeaders/registerLongTermPartyLeaders/unregisterLongTermPartyLeader/reinstateLongTermPartyLeaders` 及内部类 `LeaderSearchMetadata`（minLevel/maxLevel/searchedJobs/reentryCount，decodeSearchedJobs 按位解码） | 支撑方法 | — |

#### `PartySearchStorage`

**概述**：按职业分类的候选玩家存储（等级升序列表 + IntervalBuilder 空区间剪枝）。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `List<PartySearchCharacter> getStorageList()` | 快照 | 读锁拷贝 |
| `private Map<Integer,Character> fetchRemainingPlayers()` | 仍在队列的玩家 | 过滤 isQueued |
| `void updateStorage(Collection<Character> echelon)` | 合并刷新 | 旧队列+新登记者重建 PartySearchCharacter 列表并按等级排序后整体替换；清空区间 |
| `private static int bsearchStorage(List, int level)` | 二分定位等级 | 返回最右不大于 level 的下标 |
| `Character callPlayer(int callerCid, int callerMapid, int minLevel, int maxLevel)` | 挑选一名候选 | 空区间短路；从 maxLevel 向下扫描（低于 minLevel 停止），调用 psc.callPlayer 校验邻近/在线/无队/未屏蔽；失败登记空区间 |
| `void detachPlayer(Character)` | 移除候选 | 查找后写锁移除 |

#### `PartySearchEchelon`

**概述**：新登记候选的缓冲层（id→WeakReference<Character>，RW 锁）。`exportEchelon()`：写锁内导出存活引用并清空；`attachPlayer(Character)` 读锁 put；`detachPlayer(Character)` 读锁 remove 返回是否命中。

#### `PartySearchCharacter`

**概述**：候选玩家包装（WeakReference + level + queued 标记）。`callPlayer(int leaderid, int callerMapid)`：引用存活、与召唤者邻近、未屏蔽该队长、在线且无队时置 queued=false 并返回角色；`getPlayer()/getLevel()/isQueued()/toString()`。

### 5.5 world 子包（3 类）

#### `InviteCoordinator`（全静态）

**概述**：统一邀请管理（家族/联盟/组队/信使/交易/家庭）。枚举 `InviteType { FAMILY, FAMILY_SUMMON, MESSENGER, TRADE, PARTY, GUILD, ALLIANCE }`，每个枚举实例自带 invites/inviteFrom/inviteTimeouts/inviteParams 四张并发表；内部类 `InviteResult(result, from, params)`；枚举 `InviteResultType { ACCEPTED, DENIED, NOT_FOUND }`。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `static boolean createInvite(InviteType, Character from, Object referenceFrom, int targetCid, Object... params)` | 创建邀请 | putIfAbsent 防重复；登记 from/timeout=0/params |
| `static boolean hasInvite(InviteType, int targetCid)` | 是否有待处理邀请 | containsKey |
| `static InviteResult answerInvite(InviteType, int targetCid, Object referenceFrom, boolean answer)` | 应答 | reference 匹配才 removeRequest；from 离线置 null；返回 ACCEPTED/DENIED/NOT_FOUND |
| `static void removeInvite(InviteType, int targetCid)` | 移除邀请 | removeRequest |
| `static void removePlayerIncomingInvites(int cid)` | 清玩家全部邀请 | 遍历所有 InviteType |
| `static void runTimeoutSchedule()` | 超时调度 | 计数 >5（约 3 分钟）移除，否则 +1 |

#### `EventRecallCoordinator`（单例）

**概述**：事件实例召回（掉线后回事件）。`recallEventInstance(int characterId)`：移除并仅在 eim 未销毁/未清场时返回；`storeEventInstance(int, EventInstanceManager)`：use_enable_recall_event 开启且可召回时缓存；`manageEventInstances()`：周期清理不可召回项。私有 `isRecallableEvent(eim)`。

#### `MonsterAggroCoordinator`

**概述**：怪物仇恨管理（按怪物维护玩家伤害条目，滑动平均衰减，用于仇恨领袖选举）。内部类 `PlayerAggroEntry`（cid/averageDamage/currentDamageInstances/accumulatedDamage/expireStreak/updateStreak/toNextUpdate/entryRank）。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void stopAggroCoordinator()` | 停止监控 | idleLock 取消 aggroMonitor，记录 lastStopTime |
| `void startAggroCoordinator()` | 启动监控 | 注册 mob_status_aggro_interval 定时（runAggroUpdate + runSortLeadingCharactersAggro）；按停机时长补偿性 runAggroUpdate(delta) |
| `private static void updateEntryExpiration(PlayerAggroEntry)` | 计算下次衰减步长 | 120s / interval / 2^(expireStreak+instances) |
| `private static void insertEntryDamage(PlayerAggroEntry, int damage)` | 记录伤害 | 同步块内滑动平均更新 accumulatedDamage，重置连续过期 |
| `private static boolean expiredAfterUpdateEntryDamage(PlayerAggroEntry, int deltaTime)` | 衰减推进 | 实例数递减到 0 时判定仇恨过期 |
| `void addAggroDamage(Monster mob, int cid, int damage)` | 上报伤害 | 怪物死亡忽略；tryLock 懒建条目表；双检锁建玩家条目；damage<1 直接返回 |
| `private void runAggroUpdate(int deltaTime)` | 衰减调度 | 快照后逐怪处理过期条目；全部过期且非 BOSS 时 aggroResetAggro；按 rank/线性两种方式清理排序列表 |
| `private static void insertionSortAggroList(List<PlayerAggroEntry>)` | 按累计伤害插入排序 | 准有序数据用插入排序并回写 entryRank |
| `boolean isLeadingCharacterAggro(Monster mob, Character player)` | 玩家能否当选仇恨领袖 | 优先傀儡判定；取前 5 名，命中本人且更靠前者 inactive（updateStreak 达阈值或已死）才返回 true |
| `void runSortLeadingCharactersAggro()` | 排序调度 | 快照后逐怪插入排序 |
| `void removeAggroEntries(Monster mob)` | 移除怪物条目 | lock 内双表移除 |
| `void addPuppetAggro(Character)` / `removePuppetAggro(Integer)` / `List<Integer> getPuppetAggroList()` | 傀儡仇恨登记 | mapPuppetEntries 同步块 |
| `void dispose()` | 销毁 | 停调度并清空两表 |

---

## 6. handlers 根目录（3 类）

> 说明：`net/server/handlers/login` 子包（20 个登录 handler）由登录服务器文档覆盖；本节仅覆盖根目录 3 类。

### 6.1 `CustomPacketHandler`

**概述**：GM 自定义原始包回显 handler（implements PacketHandler）。`handlePacket(InPacket, Client)`：有剩余字节且 GM 等级 ≥4 时把剩余字节原样包装（PacketCreator.customPacket）回发；`validateState(Client)` 恒 true。

### 6.2 `KeepAliveHandler`

**概述**：心跳包 handler。`handlePacket(InPacket, Client)`：仅调用 `c.pongReceived()` 刷新存活时间；`validateState` 恒 true。

### 6.3 `LoginRequiringNoOpHandler`（final，单例）

**概述**：需要登录态的空操作 handler。`getInstance()` 返回静态实例；`handlePacket` 为空实现；`validateState(Client)` 返回 `c.isLoggedIn()`。

---

## 7. services 包（15 类）

### 7.1 `BaseService`（抽象）

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `protected static int getChannelSchedulerIndex(int mapid)` | 地图→频道分段锁索引 | mapid / (1000000000 / channel_locks) |
| `abstract void dispose()` | 释放资源 | 子类实现 |

### 7.2 `BaseScheduler`（抽象）

**概述**：通用延时条目调度器：条目（key→(removalAction, 过期时间)）由 TimerManager 周期任务驱动（mob_status_monitor_proc 间隔），空闲达 mob_status_monitor_idle 拍后自动取消定时任务（有条目注册时再启动）。支持外部锁（extLocks，先于 schedulerLock 加锁）与移除监听器。

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `protected BaseScheduler()` / `BaseScheduler(List<Lock> extLocks)` | 构造 | 后者登记外部锁（慎用） |
| `protected void addListener(SchedulerListener)` | 注册监听器 | — |
| `private void lockScheduler()` / `unlockScheduler()` | 组合加/解锁 | 先外锁后 schedulerLock（逆序解锁） |
| `private void runBaseSchedule()` | 周期执行 | 空闲计数+自动停机；否则快照后锁外执行到期 action、锁内移除、dispatchRemovedEntries(toRemove, true) |
| `protected void registerEntry(Object key, Runnable removalAction, long duration)` | 注册条目 | 唤醒调度任务（为 null 时注册），记录过期时间 |
| `protected void interruptEntry(Object key)` / `interruptEntry(Object key, boolean executeBeforeStop)` | 提前终结条目 | 移除条目，executeBeforeStop=true 时执行 removalAction；dispatch(fromUpdate=false) |
| `private void dispatchRemovedEntries(List<Object>, boolean)` | 派发移除事件 | 遍历 listener（快照数组） |
| `public void dispose()` | 销毁 | 取消任务、清 listener 与条目、清外部锁 |

### 7.3 `SchedulerListener`（接口）

**概述**：`void removedScheduledEntries(List<Object> entries, boolean update)` —— 条目被移除（到期 update=true / 被打断 false）时回调。

### 7.4 `Service<T extends BaseService>`

**概述**：服务包装/延迟句柄。`Service(Class<T> s)`：反射调用无参构造实例化；`T getService()`：类型化取回；`void dispose()`：调用 service.dispose 并置空。

### 7.5 `ServicesManager`

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `ServicesManager(ServiceType serviceBundle)` | 构造 | 按 enumValues() 为每个枚举 createService() 存入 ordinal 数组 |
| `Service getAccess(ServiceType s)` | 取服务 | services[ordinal] |
| `void shutdown()` | 全部释放 | 逐个 dispose 后置空数组 |

### 7.6 `ServiceType<T extends Enum<?>>`（接口）

**概述**：服务类型契约：`Service createService()`、`int ordinal()`、`T[] enumValues()`。

### 7.7 `WorldServices`（枚举，type 子包）

**概述**：世界级服务清单：`SAVE_CHARACTER(CharacterSaveService.class)`；实现 `createService()`（new Service(s)）与 `enumValues()`。

### 7.8 `ChannelServices`（枚举，type 子包）

**概述**：频道级服务清单：`MOB_STATUS / MOB_ANIMATION / MOB_CLEAR_SKILL / MOB_MIST / EVENT / OVERALL` 对应 6 个实现类；同样实现 createService/enumValues。

### 7.9 `CharacterSaveService`（task/world）

**概述**：角色存盘排队服务，内部类 `CharacterSaveScheduler extends BaseScheduler`。`registerSaveCharacter(int characterId, Runnable runAction)`：registerEntry(cid, action, 0)（立即到期，下拍执行）；`unregisterSaveCharacter(int)`：interruptEntry(cid, false)（不打断即不保存）；`dispose()`：销毁调度器。

### 7.10 `EventService`（task/channel）

**概述**：事件延时动作服务，按 channel_locks 分段。`registerEventAction(int mapid, Runnable, long delay)`：按地图路由到对应 EventScheduler.registerDelayedAction（registerEntry(action, action, delay)）；`dispose()` 逐段销毁。

### 7.11 `MobClearSkillService`（task/channel）

**概述**：怪物技能清除延时服务，分段模式同上。`registerMobClearSkillAction(int mapid, Runnable, long delay)`。

### 7.12 `MobMistService`（task/channel）

**概述**：怪物迷雾消散延时服务。`registerMobMistCancelAction(int mapid, Runnable, long delay)`。

### 7.13 `OverallService`（task/channel）

**概述**：频道通用延时服务（内部类 OverallScheduler 为 public）。`registerOverallAction(int mapid, Runnable, long delay)`：延时注册；`forceRunOverallAction(int mapid, Runnable)`：interruptEntry 立即执行。

### 7.14 `MobAnimationService`（task/channel）

**概述**：怪物动画状态互斥服务：同一 mobHash 在动画期间不能重复进入。`registerMobOnAnimationEffect(int mapid, int mobHash, long delay)` → MobAnimationScheduler.registerAnimationMode：animationLock 内 onAnimationMobs 已含则 false，否则 registerEntry(mobHash, 空动作, delay) 并加入集合；通过 SchedulerListener 在条目移除时同步清集合。

### 7.15 `MobStatusService`（task/channel）

**概述**：怪物状态效果管理（支持周期 overtime 动作）。`registerMobStatus(int mapid, MonsterStatusEffect mse, Runnable cancelAction, long duration)` 及五参重载（附加 overtimeAction/overtimeDelay）；`interruptMobStatus(int mapid, mse)`：interruptEntry 立即执行取消动作。内部 MobStatusScheduler 维护 `registeredMobStatusOvertime`（mse→MobStatusOvertimeEntry，按 delay/monitor_proc 计算执行次数上限，update 到点收集执行），监听器负责移除与周期触发。

---

## 8. task 包（27 个定时任务）

通用说明：均实现 `Runnable`，由 `TimerManager` 注册（全服任务见 `Server.initializeTimelyTasks`，世界任务见 `World` 构造器）。多数世界任务继承：

### 8.1 `BaseTask`（抽象）

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `BaseTask(World world)` | 绑定世界 | 存 wserv |
| `void run()` | 空实现 | 子类覆写 |

### 8.2 全服任务（Server 注册）

| 类名 | 注册周期 | run() 关键逻辑 |
|---|---|---|
| `CharacterDiseaseTask` | update_interval | Server.updateCurrentTime() 推进逻辑时钟 + runAnnouncePlayerDiseasesSchedule()（疾病广播双缓冲） |
| `CouponTask` | 1h（对齐整点） | Server.updateActiveCoupons()（按星期/小时刷新）+ commitActiveCoupons()（对在线角色应用倍率）；异常捕获记日志 |
| `RankingCommandTask` | 5min | Server.reloadWorldsPlayerRanking() |
| `RankingLoginTask` | 1h | 事务内：可选 resetMoveRank（rankMove/jobRankMove 清零）；逐世界 updateRanking(-1)（总榜）+ 每职业 updateRanking(job)；按 level/exp/lastExpGainTime/fame/meso 排序写回 rank/rankMove；失败回滚；lastUpdate 用于判断老账号 rankMove 继承 |
| `LoginCoordinatorTask` | 1h | SessionCoordinator.clearExpiredHwidHistory() |
| `EventRecallCoordinatorTask` | 1h | EventRecallCoordinator.manageEventInstances() |
| `LoginStorageTask` | 2min | SessionCoordinator.runUpdateLoginHistory()（登录尝试过期清理）+ LoginBypassCoordinator.runUpdateLoginBypass()（PIN/PIC 免验续期） |
| `DueyFredrickTask` | 1h | 构造持有 FredrickProcessor；fredrickProcessor.runFredrickSchedule()（雇佣商人结算）+ DueyProcessor.runDueyExpireSchedule()（快递过期） |
| `InvitationTask` | 30s | InviteCoordinator.runTimeoutSchedule()（邀请 3 分钟超时） |
| `RespawnTask` | respawn_interval | 遍历全部频道，有玩家的频道执行 mapManager.updateMaps()（刷怪） |
| `OnlineTimeTask` | 5s | AtomicBoolean 防重入；Server 未在线跳过；全频道角色在线时长 +5s；跨天清零；首次（-1）从账号 ExtendValue(ONLINE_TIME) 初始化 |
| `BossLogTask` | 1 天（对齐零点） | ExpeditionBossLog.resetBossLogTable() |
| `ExtendValueTask` | 1 天 | 遍历 ExtendType.getCleanMap()，通过 ExtendValueMapper.clean(key, value) 清理过期扩展值；逐项 try-catch |

补充细节：
- `RankingLoginTask` 私有方法 `resetMoveRank(boolean job)`（UPDATE characters 重置移动标志）与 `updateRanking(int job, int world)`（组装按职业过滤的 SELECT，逐行计算新 rank 与 rankMove 并 UPDATE，单事务批量提交）。
- `OnlineTimeTask` 私有 `getInitialOnlineTime(Character)` 从账号扩展值读取初始在线分钟数（异常按 0）。

### 8.3 世界任务（World 构造器注册，均 extends BaseTask 除特别注明）

| 类名 | 注册周期 | run() 关键逻辑 |
|---|---|---|
| `PetFullnessTask` | 1min | wserv.runPetSchedule()（宠物饱食度递减） |
| `ServerMessageTask` | 10s | wserv.runDisabledServerMessagesSchedule()（BOSS 血条期间暂停公告，约 35s 恢复） |
| `MountTirednessTask` | 1min | wserv.runMountSchedule()（坐骑疲劳） |
| `HiredMerchantTask` | 10min | wserv.runHiredMerchantSchedule()（超时/封禁商店强制收店） |
| `TimedMapObjectTask` | 1min | wserv.runTimedMapObjectSchedule()（限时地图对象到期触发） |
| `CharacterAutosaverTask` | 1h（fixedDelay） | use_autosave 开关；静态 AtomicBoolean 不可重入保护（上轮未结束跳过，防多轮并发抢角色锁/打满连接池）；遍历世界在线角色 saveCharToDB(false)；HpMpAlertService.saveAll()；isNextTime() 时输出加密串；finally 记录耗时并复位标志 |
| `WeddingReservationTask` | wedding_reservation_interval | 遍历世界各频道：getNextWeddingReservation(true/false)（大教堂/小教堂）→ setOngoingWedding（无预约时清空 ongoing） |
| `MapOwnershipTask` | 20s | 遍历频道 ch.runCheckOwnedMapsSchedule()（地图所有权活跃检查） |
| `FishingTask` | 10s | wserv.runCheckFishingSchedule()（按概率结算钓鱼） |
| `PartySearchTask` | 10s | wserv.runPartySearchUpdateSchedule()（刷新候选池并执行组队搜索） |
| `TimeoutTask` | 10s | 遍历世界在线角色，距 LastPacket 超过 timeout_duration（默认 1h）则 client.timeoutDisconnect()（清理异常连接） |
| `CharacterHpDecreaseTask` | map_damage_overtime_interval | wserv.runPlayerHpDecreaseSchedule()（毒图周期掉血） |
| `FamilyDailyResetTask`（非 BaseTask，自持 world 字段） | 1 天（对齐零点） | resetEntitlementUsage(world)（SQL 重置 family_character 当日 rep、清理过期 family_entitlement）+ 遍历 family.resetDailyReps()；isNextTime() 输出加密串。静态 resetEntitlementUsage 以"次日 0 点"为界执行 UPDATE/DELETE |

---

## 9. 关键设计要点小结

1. **锁策略**：Server 用两对公平读写锁分离"世界结构"与"登录视图"；World 各子系统独立锁并遵循锁序注释（如 accountCharsLock 须在 lgnWLock 之后）；Guild.broadcast、Server.disbandGuild、World.runHiredMerchantSchedule 均为"锁内快照、锁外执行"的代表，注释中明确记录了历史死锁修复。
2. **服务器时钟**：`Server.updateCurrentTime()` 按配置间隔推进逻辑时钟，疾病广播、宠物/坐骑调度、BaseScheduler 等均使用该时钟，保证调度与游戏 tick 对齐。
3. **停服链路**：`Server.shutdownInternal`（volatile 重入保护）→ 各 World.shutdown → Channel.shutdown（关商店→踢玩家→释放脚本/地图→停 Netty）→ 关线程池与登录服；restart 路径重建单例并重新 init()。
4. **防多开体系**：SessionInitialization（同 IP 互斥）+ Hwid 关联（DB 事务 + relevance 过期梯度）+ LoginStorage（尝试频控）+ LoginBypassCoordinator（PIN/PIC 免验缓存），全部由 task 包的 4 个清理任务周期维护。
5. **调度服务框架**：services 包以 BaseScheduler（空闲自停机）+ Service（反射包装）+ ServicesManager（枚举驱动）+ type 枚举清单构成插件式延时任务框架，频道服务按地图分段降低锁竞争。
