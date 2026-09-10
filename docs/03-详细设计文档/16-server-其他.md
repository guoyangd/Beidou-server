# 16-server-其他 详细设计

- 模块路径：
  - `gms-server/src/main/java/org/gms/server/`（根目录 22 个类）
  - `gms-server/src/main/java/org/gms/server/gachapon/`（15 个类）
  - `gms-server/src/main/java/org/gms/server/partyquest/`（7 个类）
  - `gms-server/src/main/java/org/gms/server/movement/`（9 个类）
  - `gms-server/src/main/java/org/gms/server/events/`（2 个类）与 `org/gms/server/events/gm/`（6 个类）
  - `gms-server/src/main/java/org/gms/server/expeditions/`（3 个类）
  - `gms-server/src/main/java/org/gms/server/loot/`（2 个类）
  - `gms-server/src/main/java/org/gms/server/minigame/`（1 个类）
- 类数量：67（根目录 22 + gachapon 15 + partyquest 7 + movement 9 + events 8 + expeditions 3 + loot 2 + minigame 1；`maps`、`quest`、`life` 三个子包由其他文档覆盖。若计入 `StorageInventory.java` 内的包级类 `PairedQuicksort` 及各类内部类/枚举则更多）
- 依赖模块：
  - 客户端侧：`org.gms.client.*`（`Character`、`Client`、`Skill`、`SkillFactory`、`Job`、`BuffStat`、`Disease`、`Mount`、inventory 全家桶、`AutobanFactory`）、`org.gms.net.server.*`（`Server`、`Channel`、`world.Party`、`PlayerStorage`、coordinator.world.`InviteCoordinator`）
  - 地图/怪物：`org.gms.server.maps.*`（`MapleMap`、`Door`、`Mist`、`Summon`、`Portal`、`FieldLimit` 等）、`org.gms.server.life.*`（`Monster`、`MobSkill`、`MobSkillFactory`、`LifeFactory`、`MonsterInformationProvider`、`MonsterDropEntry`）
  - 数据层：`provider/wz`（`DataProviderFactory`、`DataTool`、`WZFiles`）、`DatabaseConnection`（裸 JDBC）、`dao.entity`（`AccountsDO`、`ModifiedCashItemDO`、`WishlistsDO`）、`service`（`AccountService`、`CharacterService`、`CashShopService`，经 `ServerManager.getApplicationContext()` 反向取 Bean）
  - 配置/工具：`GameConfig`（热更运营参数）、`PacketCreator`、`I18nUtil`、`Randomizer`、`Pair`、`constants.*`（`ItemId`、`MapId`、`NpcId`、`MobId`、`ItemConstants`、`GameConstants`、技能常量类）、`TimerManager`
  - 并发：`ReentrantLock`、`AtomicBoolean`、`ScheduledFuture`、`ConcurrentHashMap`、`CopyOnWriteArrayList`、Java 21 虚拟线程（`ThreadManager`）

---

## 1. 根目录（22 个类）

### CashShop

玩家商城（点卷商店）会话对象：一个角色进入商城时创建，持有该账号的三种点卷余额、商城仓库物品、愿望单与礼物盒；内部静态类 `CashItemFactory` 承载全服共享的商品/礼包数据（源码路径：`server/CashShop.java`）。

**关键字段**

| 字段 | 说明 |
| --- | --- |
| `NX_CREDIT / MAPLE_POINT / NX_PREPAID` | 常量 1/2/4，三种货币类型编码 |
| `MAX_CASH_INVENTORY_SAFE` | 常量 1000，商城仓库安全上限 |
| `accountId / characterId` | 账号/角色 ID |
| `nxCredit / maplePoint / nxPrepaid` | 三种货币余额（内存值，`save` 时写回 `accounts` 表） |
| `factory` | `ItemFactory`（CASH_EXPLORER / CASH_CYGNUS / CASH_ARAN / CASH_OVERALL），决定商城物品落在哪张 DB 物品表 |
| `inventory / wishList / notes` | 商城仓库物品列表、愿望单 SN 列表、未读礼物数 |
| `lock` | `ReentrantLock`，保护 inventory 并发访问 |
| `accountService / characterService`（static） | 经 `ServerManager` 取到的 Spring Bean |

**方法表**

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `CashShop(int accountId, int characterId, int jobType)` | 构造商城会话 | ① `use_joint_cash_shop_inventory` 为真则用 CASH_OVERALL，否则按 jobType 0/1/2 选探险家/骑士团/战神工厂；② 从 `AccountsDO` 读三种点卷（null 补 0）；③ `factory.loadItems` 装载商城物品并 `trimToSafeInventoryLimit` 截断超限；④ 从 `CharacterService.getWishlistsByCharacter` 装载愿望单 |
| `static void loadAllCashItems()`（内部类 `CashItemFactory`） | 全服加载商品与礼包 | ① 解析 ETC `Commodity.img` 全部 SN → `ModifiedCashItemDO`（Period 为 0 时兜底 90 天；Limit 字段暂不解析）；② 解析 `CashPackage.img` 得到礼包 itemId → SN 列表；③ 两张 volatile Map 整体替换保证可见性；④ 调 `loadCashCategories` + `loadAllModifiedCashItems` |
| `static void loadAllModifiedCashItems()`（内部类） | 重载 DB 侧商品改档 | 清空 `modifiedCashItems` 后从 `CashShopService.loadAllModifiedCashItems` 重建 sn → DO 映射，支持运营热改价格/上架状态 |
| `private static void loadCashCategories()`（内部类） | 加载商城分类 | 从 `CashShopService.getAllCategoryList` 填充 `cashCategories`（注意：实现里误清的是 `modifiedCashItems`，属遗留疑点） |
| `static Optional<ModifiedCashItemDO> getRandomCashItem()`（内部类） | 随机一件在售商品 | 过滤 `isSelling` 且非礼包（`ItemId.isCashPackage`）后随机取一件，供「商城惊喜」抽奖 |
| `static ModifiedCashItemDO getItem(int sn)`（内部类） | 取合并后的商品 | 克隆 WZ 条目，再逐字段用 DB 改档（`modifiedCashItems`）覆盖——DB 字段为 null 时回退 WZ 值；SN 不存在返回 null |
| `static ModifiedCashItemDO getWzItem(int sn)`（内部类） | 取原始 WZ 商品 | 直接返回 `items.get(sn)`，不做 DB 合并 |
| `static List<Item> getPackage(int itemId)`（内部类） | 展开礼包 | 按 `packages` 的 SN 列表逐个 `getItem(sn).toItem()` |
| `static boolean isPackage(int itemId)`（内部类） | 是否礼包 | `packages.containsKey` |
| `int getCash(int type)` | 查余额 | 按 1/2/4 分别返回三种货币，其余返回 0 |
| `void gainCash(int type, int cash)` | 增减余额 | 按类型累加（可为负） |
| `void gainCash(int type, ModifiedCashItemDO buyItem, int world)` | 购买扣款 | 扣 `buyItem.price`；未开启 `use_enforce_item_suggestion` 时向 `World.addCashItemBought(sn)` 记录热销 |
| `boolean isOpened() / void open(boolean b)` | 商城是否已开启 | `opened` 标记（进入/退出商城时切换） |
| `List<Item> getInventory()` | 商城仓库快照 | 加锁返回不可变视图 |
| `Item findByCashId(int cashId)` | 按 CashId 找物品 | 优先比对 petId，其次装备的 ringId，最后 item.cashId |
| `boolean addToInventory(Item item)` | 入库 | 达到 1000 上限返回 false |
| `boolean canAddToInventory(int itemCount)` | 容量预检 | `size + itemCount <= 1000` |
| `int getInventoryLimit() / int getInventorySize()` | 上限/当前数量 | 上限恒 1000；size 加锁读取 |
| `void removeFromInventory(Item item)` | 出库 | 加锁 remove |
| `List<Integer> getWishList() / void clearWishList() / void addToWishList(int sn)` | 愿望单操作 | 直接操作内存列表，`save` 落库 |
| `void gift(int recipient, String from, String message, int sn)` | 送礼 | 委托 5 参重载（ringid = -1） |
| `void gift(int recipient, String from, String message, int sn, int ringid)` | 送礼（带戒指） | 裸 JDBC `INSERT INTO gifts` |
| `List<Pair<Item, String>> loadGifts()` | 领取礼物 | ① 按 `characterId` 查 `gifts` 表；② 礼包先展开并逐件入库（套装件数参与容量预检，放不下则整件跳过）；③ 装备回填 ringId，记录 `notes++`；④ 最后 `DELETE FROM gifts` 清空已领记录 |
| `int getAvailableNotes() / void decreaseNotes()` | 礼物提示计数 | 未读数减一 |
| `void save(Connection con) throws SQLException` | 持久化 | ① UPDATE `accounts` 三种点卷；② `factory.saveItems` 全量保存商城物品；③ DELETE + 逐条 INSERT `wishlists`（TODO 批量） |
| `Optional<CashShopSurpriseResult> openCashShopSurprise(long cashId)` | 开「商城惊喜」 | 全程持锁：校验物品为 CASH_SHOP_SURPRISE 且数量 > 0、仓库 < 100、能抽到随机商品；扣 1 个惊喜（归零则移出仓库），随机商品入库，返回 `record CashShopSurpriseResult(usedCashShopSurprise, reward)` |
| `static Item generateCouponItem(int itemId, short quantity)` | 生成兑换券物品 | 构造 sn=77777777、price=777 的 `ModifiedCashItemDO`；宠物有效 30 天，其余永久 |

---

### Trade

玩家 1v1 交易：每个参与交易的角色持有一个 `Trade` 实例（`Character.getTrade()`），两个实例互为 partner；提供邀请/入座/放物/确认/完成/取消全流程，含并发握手（按角色 ID 定序加锁）与金币税率（源码路径：`server/Trade.java`）。

**关键字段**

| 字段 | 说明 |
| --- | --- |
| `TradeResult`（enum） | 交易结果码：NO_RESPONSE(1)、PARTNER_CANCEL(2)、SUCCESSFUL(7)、UNSUCCESSFUL(8)、UNSUCCESSFUL_UNIQUE_ITEM_LIMIT(9)、UNSUCCESSFUL_ANOTHER_MAP(12)、UNSUCCESSFUL_DAMAGED_FILES(13) |
| `partner / items / exchangeItems` | 对端 Trade、自己放入的物品（≤9 件）、对端放入的物品 |
| `meso / exchangeMeso` | 自己/对端投入的金币 |
| `locked` | `AtomicBoolean`，双方均确认（交易锁定）标记 |
| `chr / number` | 持有者、窗口编号（发起方 0、受邀方 1，用于封包） |
| `fullTrade` | 双方均已进入交易窗口 |

**方法表**

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static int getFee(long meso)` | 金币交易税率 | 分档累进：≥1 亿 6%、≥2500 万 5%、≥1000 万 4%、≥500 万 3%、≥100 万 1.8%、≥10 万 0.8%，更低免税 |
| `private void lockTrade()` | 锁定己方并通知对端 | `locked=true` 后向 partner 发确认包 |
| `private void fetchExchangedItems()` | 快照对端资产 | `exchangeItems = partner.getItems()`、`exchangeMeso = partner.getMeso()` |
| `private void completeTrade()`（实例） | 单侧成交落地 | 清空自己放入的物品/金币；对端物品经 `KarmaManipulator.toggleKarmaFlagToUntradeable` 后 `InventoryManipulator.addFromDrop` 入包；金币 `gainMeso(exchangeMeso - fee)`，有税时以 NO_RESPONSE 结果码下发（客户端弹税率提示），否则 SUCCESSFUL |
| `private void cancel(byte result)` | 单侧取消退还 | 仅当**未双方锁定**时退还物品与金币；双方已锁定说明 completeTrade 已转移资产，此时仅记 error 日志（防二次转移/丢物）；最后发结果包 |
| `public void setMeso(int meso)` | 投入金币 | 已锁定抛异常；负数告警返回；金币不足静默忽略；成功后先 `gainMeso(-meso)` 扣角色金币，再向双方同步交易窗金币显示 |
| `public boolean addItem(Item item)` | 放入物品 | `synchronized(items)`：上限 9 件（`> 9` 判定）且格子位置不得重复 |
| `public void chat(String message)` | 交易窗口聊天 | 自己见 gm 视角、对端见普通视角 |
| `public Trade getPartner() / void setPartner(Trade)` | 对端管理 | setPartner 在已锁定时为 no-op |
| `public Character getChr() / List<Item> getItems() / int getExchangeMesos()` | 读取器 | getItems 返回拷贝（LinkedList） |
| `private boolean fitsMeso()` | 金币容纳检查 | `chr.canHoldMeso(exchangeMeso - getFee(exchangeMeso))` |
| `private boolean fitsInInventory()` | 背包容纳检查 | `Inventory.checkSpotsAndOwnership(chr, exchangeItems)` |
| `private boolean fitsUniquesInInventory()` | 唯一物品容纳检查 | `chr.canHoldUniques(exchangeItemids)`，用于区分「背包满」与「唯一物品超限」两种失败提示 |
| `private synchronized boolean checkTradeCompleteHandshake(boolean updateSelf)` | 定序加锁握手 | 以 `self.isLocked()` 判重入；先 `self.lockTrade()` 再返回 `other.isLocked()`；方法级 synchronized + 按 `chr.getId()` 大小选主（见 `checkCompleteHandshake`）避免双方并发确认竞态 |
| `private boolean checkCompleteHandshake()` | 握手入口 | 角色ID小的一方始终作为 self 加锁，保证全局一致的加锁顺序 |
| `private static void unlockAndCancel(Character chr, TradeResult result)` | 失败回滚 | 握手成功后若后续检查失败，**必须先复位双方 locked 再取消**，否则 `cancel()` 看到 bothLocked 会跳过退还造成物品永久丢失（源码注释明确强调） |
| `static void completeTrade(Character chr)` | 交易总成交入口 | ① 握手；② 双方 fetch；③ 两侧金币容纳检查（失败→解锁取消+提示）；④ 两侧背包容纳检查（唯一物品超限给专属结果码）；⑤ 低等级每日交易限额：`trade_limit_meso_under_level`（默认 15）级以下角色 `trade_limit_meso_max`（默认 100 万）封顶，`-1` 关闭，`addMesosTraded` 累计当日流水；⑥ `logTrade` 后双侧 `completeTrade`、双方 `setTrade(null)` |
| `private static void cancelTradeInternal(Character chr, byte selfResult, byte partnerResult)` | 取消落地 | 双侧 `cancel` 各带结果码、双方 `setTrade(null)`，并双向 `InviteCoordinator.answerInvite(..., false)` 清邀请 |
| `private static byte[] tradeResultsPair(byte result)` | 结果码配对 | PARTNER_CANCEL → 自己 NO_RESPONSE 对端原码；UNIQUE_ITEM_LIMIT → 对端降级 UNSUCCESSFUL；其余对称 |
| `private synchronized void tradeCancelHandshake(boolean updateSelf, byte result)` / `private void cancelHandshake(byte result)` | 取消握手 | 同成交握手，按角色 ID 选主后走 `cancelTradeInternal` |
| `static void cancelTrade(Character chr, TradeResult result)` | 取消入口 | 经握手分发 |
| `static void startTrade(Character chr)` | 初始化交易 | 无交易时 `new Trade((byte) 0, chr)` |
| `private static boolean hasTradeInviteBack(Character c1, Character c2)` | 是否互邀 | c2 的交易 partner 是否为 c1 |
| `static void inviteTrade(Character c1, Character c2)` | 发起邀请 | ① GM 与非 GM 混交易需 GM 等级 ≥ `minimum_gm_level_to_trade`（双向检查）；② `InviteCoordinator.hasInvite` 时提示（互邀另有提示）并返回；③ `fullTrade` 时提示占用；④ `createInvite` 成功且对方空闲→为对方建 `Trade((byte)1)` 并互设 partner、发 tradeInvite 包；对方忙→提示+取消+answerInvite(false)；createInvite 失败→提示+取消 |
| `static void visitTrade(Character c1, Character c2)` | 受邀入座 | `answerInvite(..., true)` 为 ACCEPTED 且双方 partner 互指时：发 partnerAdd + tradeStart 包，双方 `setFullTrade(true)`；否则提示并取消 |
| `static void declineTrade(Character chr)` | 拒绝邀请 | answerInvite 得 DENIED 时通知对方；对端 `cancel(PARTNER_CANCEL)`、自己 `cancel(NO_RESPONSE)`，双方清 trade |
| `boolean isFullTrade() / void setFullTrade(boolean)` | 双方入座标记 | 读写 |
| `private static void logTrade(Trade trade1, Trade trade2)` | 交易日志 | i18n 拼双方互给的金额与物品清单，异常仅记 error 不影响交易 |
| `private static String getFormattedItemLogMessage(List<Item> items)` | 物品清单格式化 | `数量x 名称 (id)` 逗号拼接 |

---

### Shop / ShopFactory / ShopItem

NPC 商店三件套：`ShopFactory` 按 shopId/npcId 双索引懒加载并缓存 `Shop`（含 null 负缓存），`Shop` 承载一家店的货架与买/卖/充值逻辑，`ShopItem` 是一条货架记录（源码路径：`server/Shop.java`、`server/ShopFactory.java`、`server/ShopItem.java`）。

**Shop 关键字段**：`id`（shopid）、`npcId`、`items`（货架）；静态 `rechargeableItems`（全部飞镖 + 子弹 + 火焰/冰霜胶囊 + 平衡 Fury，剔除不存在的 Devil Rain）；`tokenvalue = 1000000000`、`token = ItemId.GOLDEN_MAPLE_LEAF`（金枫叶抵扣体系）。

**Shop 方法表**

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `void sendShop(Client c)` | 打开商店 | `player.setShop(this)` 后下发 `getNPCShop` 包 |
| `void buy(Client c, short slot, int itemId, short quantity)` | 购买 | ① `findBySlot` 校验槽位与 itemId 一致；② 持有对应背包锁；③ `price > 0` 走金币：总价 `min(price*qty, MAX_INT)`，够钱且 `checkSpace` 后入包扣钱（可充值类按 `slotMax` 整组购买、只收单价）；④ `pitch > 0` 走完美音高（ETC 里 PERFECT_PITCH 数量足够）；⑤ 两者皆 0 走金枫叶抵扣：CASH 背包内金枫叶数 × 10 亿 + 现有金币 ≥ 总价时可买（宠物走 `Pet.createPet` 绑定 petid），差额以 `gainMeso(diff)` 结算；⑥ 结果包：0 成功 / 2 钱不够 / 3 没空间 |
| `private static boolean canSell(Item item, short quantity)` | 可卖校验 | 数量 0xFFFF 视为 1、负数拒绝；非充值类要求 `quantity <= iQuant`；充值类恒可卖 |
| `private static short getSellingQuantity(Item item, short quantity)` | 实卖数量 | 充值类（飞镖/子弹）整组出售 |
| `void sell(Client c, InventoryType type, short slot, short quantity)` | 出售 | `removeFromSlot` 后按 `ii.getPrice(itemId, quantity)` 回收金币；成功 0x8、失败 0x5 |
| `void recharge(Client c, short slot)` | 充值（USE 栏） | 仅可充值类且未满：价格 `ceil(unitPrice * (slotMax - qty))`，够钱则补满数量并扣钱；0x8 成功 / 0x2 钱不够 |
| `static Shop createFromDB(int id, boolean isShopId)` | 从 DB 建店 | ① 按 shopid 或 npcid 查 `shops`；② 查 `shopitems`（`ORDER BY position DESC`）：可充值类 `buyable=1`，其余 1000；③ 追加该店未上架的其余可充值物（price=0）供充值；SQL 异常仅打印栈 |
| `int getNpcId() / int getId()` | 读取器 | — |

**ShopFactory 方法表**

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static ShopFactory getInstance()` | 单例 | 饿汉静态实例 |
| `Shop getShop(int shopId)` | 按 shopId 取店 | 命中缓存（含 null 负缓存）直接返回，否则 `loadShop(id, true)` |
| `Shop getShopForNPC(int npcId)` | 按 NPC 取店 | 同上，`loadShop(id, false)` |
| `void reloadShops()` | 清缓存 | 清空两张 Map，下次访问重新建店（GM 指令热更新用） |

**ShopItem**：字段 `buyable`（short，可购数量）、`itemId`、`price`（金币价）、`pitch`（完美音高价）+ 四个 getter，纯数据类。

---

### Storage / StorageInventory

**Storage**：账号级+世界维度的仓库（银行箱）。构造私有，由 `loadOrCreateFromDB` 静态装载；所有操作在公平锁 `ReentrantLock(true)` 下进行（源码路径：`server/Storage.java`）。

**关键字段**：`id`（storageid）、`currentNpcid`（当前打开仓库的 NPC，用于取手续费）、`meso`、`slots`（初始 4，上限 48）、`items`、`typeItems`（分类视图缓存）；静态 `trunkGetCache / trunkPutCache` 缓存各 NPC 的存/取手续费。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static Storage loadOrCreateFromDB(int id, int world)` | 装载或创建 | 按 `accountid + world` 查 `storages`：命中则装载 `ItemFactory.STORAGE` 物品；未命中走 `create`（INSERT 4 槽 0 金币后重查）；SQL 异常包 RuntimeException（防部署 null 仓库） |
| `byte getSlots() / boolean canGainSlots(int slots)` | 槽位 | 扩槽后总数 ≤ 48 才允许 |
| `boolean gainSlots(int slots)` | 扩槽 | 锁内校验后累加 |
| `void saveToDB(Connection con)` | 持久化 | UPDATE `storages` 槽位数与金币；`ItemFactory.STORAGE.saveItems` 全量保存物品 |
| `Item getItem(byte slot)` | 按全局下标取物 | 锁内 `items.get(slot)` |
| `boolean store(Item item)` | 存入 | 已满拒绝（防无限插入）；插入后重建该类型的 `typeItems` 视图 |
| `boolean takeOut(Item item)` | 取出 | 移除后同样重建分类视图 |
| `List<Item> getItems()` | 全量快照 | 锁内返回不可变列表 |
| `byte getSlot(InventoryType type, byte slot)` | 分类下标→全局下标 | 遍历全量列表比对 `typeItems.get(type).get(slot)` 引用，未命中返回 -1 |
| `void sendStorage(Client c, int npcId)` | 打开仓库 | 等级 < 15 拒绝（提示「15级以后才可以使用仓库服务」）；锁内按 InventoryType 排序 items、为所有类型初始化 `typeItems`、记录 `currentNpcid`、下发 getStorage 包 |
| `void sendStored(Client c, InventoryType type) / void sendTakenOut(Client c, InventoryType type)` | 刷新存/取视图 | 锁内下发对应分类列表包 |
| `void arrangeItems(Client c)` | 整理仓库 | `StorageInventory` 合并同类→排序→回写 items、刷新 typeItems、下发 arrangeStorage 包 |
| `int getMeso() / void setMeso(int meso) / void sendMeso(Client c)` | 仓库金币 | setMeso 负数抛 RuntimeException |
| `int getStoreFee() / int getTakeOutFee()` | 存/取手续费 | 读 NPC wz `info/trunkPut`（默认 100）/`info/trunkGet`（默认 0），静态 Map 缓存 |
| `boolean isFull()` | 是否已满 | `items.size() >= slots` |
| `void close()` | 关闭仓库 | 清空 `typeItems`（下次打开重建） |

**StorageInventory**（含同文件包级类 `PairedQuicksort`）：仓库整理用的临时背包镜像，按「格子号→物品」LinkedHashMap 组织，提供同类合并与快排（源码路径：`server/StorageInventory.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `StorageInventory(Client c, List<Item> toSort)` | 构造镜像 | `slotLimit = toSort.size()`，逐个 `addItem` 分配连续格子 |
| `public void mergeItems()` | 合并同类 | 双循环：目标格未满且 itemId 相同时 `moveItem(src, dst)`（非充值、非拾取绑定、owner 相同才可叠；装备/点卷只交换位置；叠加溢出拆分余量）；随后把物品整体前移压缩空洞 |
| `public List<Item> sortItems()` | 排序 | 逐格取拷贝、清空格子，`PairedQuicksort` 主排序键按 `use_item_sort_by_name`（名称/物品ID）、次排序键为数量，返回新列表 |
| `private void move(short sSlot, short dSlot, short slotMax)` | 移动/合并/交换核心 | 目标空→直接移动；同 itemId 可叠→叠加（超过 slotMax 拆分）；否则 `swap` 交换 |

`PairedQuicksort`：手写快排，`PartitionByItemId / PartitionByName / PartitionByQuantity / PartitionByLevel` 四种分区，构造器 `(ArrayList<Item> A, int primarySort, int secondarySort)` 先整体按主键排，再按 itemId 相邻段边界用次键段内排序。

---

### ItemInformationProvider

物品信息中枢（单例）：封装对 Item.wz / Character.wz / String.wz / Etc.wz 的全部查询与 DB 辅助表（怪物卡、制造、掉落）访问，内部 40+ 张 `Map` 缓存按 itemId 惰性填充（源码路径：`server/ItemInformationProvider.java`，约 2400 行）。

**关键字段（缓存族）**：`slotMaxCache`（堆叠上限）、`itemEffects`（物品 StatEffect）、`equipStatsCache`（装备属性表）、`equipCache`、`equipLevelInfoCache / equipLevelReqCache / equipMaxLevelCache`、`scrollReqsCache`、`wholePriceCache / unitPriceCache`、`projectileWatkCache`、`nameDescCache / msgCache`、`getMesoCache`、`monsterBookID`、各类限制缓存（`accountItemRestrictionCache / dropRestrictionCache / pickupRestrictionCache / untradeableCache / onEquipUntradeableCache / karmaCache / consumeOnPickupCache / isQuestItemCache / isPartyQuestItemCache`）、`scriptedItemCache`、`triggerItemCache / createItem / mobItem / useDelay / mobHP / expCache / levelCache`、`rewardCache`、`replaceOnExpireCache`、`equipmentSlotCache`、`noCancelMouseCache`、制造系缓存（`mobCrystalMakerCache / statUpgradeMakerCache / makerItemCache / makerCatalystCache`）、`skillUpgradeCache / skillUpgradeInfoCache`、`cashPetFoodCache`、`questItemConsCache`、`itemCashInfoCache`、`itemNameCache`（仅作判空，实际从未填充）。内部类：`ScriptedItem`、`RewardItem`、`QuestConsItem`、`ItemCashInfo`。

**方法表（公有）**

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static ItemInformationProvider getInstance()` | 单例 | 饿汉 |
| `List<Pair<Integer, String>> getAllItems()` | 全物品名录 | 遍历 String.wz 六大类（Cash/Consume/Eqp/Etc/Ins/Pet）；判空 `itemNameCache` 但从不写入（每次重算，遗留行为） |
| `List<Pair<Integer, String>> getAllEtcItems()` | ETC 物品名录 | 同上仅 Etc.img |
| `boolean noCancelMouse(int itemId)` | 是否禁右键取消 | `info/noCancelMouse == 1`，缓存 |
| `List<Integer> getItemIdsInRange(int minId, int maxId, boolean ignoreCashItem)` | 区间内存在的物品 | 逐个 `getItemData != null`（可排除点卷） |
| `short getSlotMax(Client c, int itemId)` | 堆叠上限 | 基础值：wz `info/slotMax`，缺失时装备=1、其余=100；`item_slot_max` 配置 >0 且类型允许时覆盖；再加玩家技能加成（爪系/子弹掌握每级 +10，来自 `getExtraSlotMaxFromPlayer`，避免缓存玩家相关数据） |
| `int getMeso(int itemId)` | 物品自身价值 | `info/meso`，无则 -1 |
| `int getWholePrice(int itemId)` | 整价 | `info/price`；`getItemPriceData` 同时缓存整价与单价 |
| `double getUnitPrice(int itemId)` | 单价（充值类） | `info/unitPrice` 经 `getRoundedUnitPrice` 二分逼近小数（最多 5 次）取整到 2^-n 粒度 |
| `int getPrice(int itemId, int quantity)` | 出售总价 | 整价 × 数量；充值类为整价 + `ceil(qty * unitPrice)` |
| `Pair<Integer, String> getReplaceOnExpire(int itemId)` | 到期替换 | `info/replace/itemid` 与提示消息 |
| `Map<String, Integer> getEquipStats(int itemId)` | 装备属性全集 | `info` 下所有 `inc*` 键（去前缀）+ reqJob/reqLevel/reqDEX/reqSTR/reqINT/reqLUK/reqPOP/cash/tuc/cursed/success/fs |
| `Integer getEquipLevelReq(int itemId)` | 装备需求等级 | `info/reqLevel` |
| `List<Integer> getScrollReqs(int itemId)` | 卷轴适用装备 | `req` 子节点列表 |
| `WeaponType getWeaponType(int itemId)` | 武器类型 | `(itemId/10000)%100` 映射 30–49 的类型表 |
| `static boolean rollSuccessChance(double propPercent)` | 成功率掷骰 | `Math.random() >= (1-p)^scroll_chance_rolls`（多次不成功的联合概率） |
| `void scrollOptionEquipWithChaos(Equip nEquip, int range, boolean option)` | 混沌卷随机属性（可选池） | `option=false` 随机 STR/DEX/INT/LUK/ACC/AVOID，`true` 随机 WATK/WDEF/MATK/MDEF/SPD/JUMP/HP/MP；`use_enhanced_chaos_scroll` 时新值下限取当前值（不劣化），否则下限 0；上界 Short.MAX |
| `boolean canUseCleanSlate(Equip equip)` | 洁净卷可用 | `可用槽 + 已卷次数 < tuc + vicious` |
| `Item scrollEquipWithId(Item equip, int scrollId, boolean usingWhiteScroll, int vegaItemId, boolean isGM)` | 卷轴核心 | ① GM + `use_perfect_gm_scroll` 必成；② 有升级槽或是洁净卷才生效；③ Vega 卷调整成功率（10→30、60→90、混沌 60→100）；④ 成功分支按卷类型：尖刺/防寒上 flag、洁净 +1 槽、混沌走 `scrollEquipWithChaos`（`chaos_scroll_stat_range`），普通卷 `improveEquipStats`；非洁净卷扣 1 槽（修饰卷 `isModifierScroll` 不扣）且装备等级 +1；⑤ 失败分支：非完美卷/白卷/洁净/GM/修饰卷才扣槽，`cursed` 概率返回 null（装备销毁） |
| `static void improveEquipStats(Equip nEquip, Map<String, Integer> stats)` | 应用卷轴属性 | 按键名映射到装备字段（STR/DEX/INT/LUK/PAD/PDD/MAD/MDD/ACC/EVA/Speed/Jump/MHP/MMP），`getShortMaxIfOverflow` 封顶 |
| `Item getEquipById(int equipId)` | 生成装备实例 | 委托私有重载（ringId=-1）：按 equipStats 逐项赋值，tuc→升级槽，不可交易/尖刺（fs>0）打 flag，返回 `copy()` 防缓存泄漏 |
| `Equip randomizeStats(Equip equip)` | 掉落装备随机化 | `getRandStat`：基础值 ±min(10%基础, 上限)，主属性上限 5、防御 10 |
| `Equip randomizeUpgradeStats(Equip equip)` | 升级随机化 | 只增不减：主属性 +0..2、防御系 +0..5 |
| `StatEffect getItemEffect(int itemId)` | 物品效果 | `specEx`（无则 `spec`）→ `StatEffect.loadItemEffectFromData`，缓存 |
| `int[][] getSummonMobs(int itemId)` | 召唤怪物表 | `mob/x/id` + `mob/x/prob` |
| `int getWatkForProjectile(int itemId)` | 投射物攻击加成 | `info/incPAD` |
| `String getName(int itemId)` / `Pair<String, String> getNameDesc(int itemId)` | 名称/名称+描述 | `getStringData` 按 ID 段路由到 String.wz 各分类；无 name 返回 null |
| `String getMsg(int itemId)` | 使用提示 | String.wz `msg` |
| `boolean isUntradeableRestricted(int itemId)` | 不可交易 | `info/tradeBlock == 1` |
| `boolean isAccountRestricted(int itemId)` | 账号绑定 | `info/accountSharable == 1` |
| `boolean isLootRestricted(int itemId)` | 禁止掉落 | tradeBlock 或账号绑定 |
| `boolean isDropRestricted(int itemId)` | 禁止丢弃 | `isLootRestricted ∥ isQuestItem` |
| `boolean isPickupRestricted(int itemId)` | 禁止拾取 | `info/only == 1` |
| `Map<String, Integer> getSkillStats(int itemId, double playerJob)` | 技能书属性 | `getSkillStatsInternal` 取 inc*/masterLevel/reqSkillLevel/success，再在 `info/skill` 列表中找与玩家职业匹配的 skillid（无则 0） |
| `Pair<Integer, Boolean> canPetConsume(Integer petId, Integer itemId)` | 宠物粮适用性 | 解析 `spec` 下数字节点为可用宠物集合、`inc` 为亲密度；返回（亲密度, 是否适用） |
| `boolean isQuestItem(int itemId) / boolean isPartyQuestItem(int itemId)` | 任务物品 | `info/quest` / `info/pquest` 为 1 |
| `int getCardMobId(int id)` | 怪物卡→怪物 | 构造时从 `monstercarddata` 表装载的 `monsterBookID` |
| `boolean isUntradeableOnEquip(int itemId)` | 穿上后绑定 | `info/equipTradeBlock > 0` |
| `ScriptedItem getScriptedItemInfo(int itemId)` | 脚本物品 | 仅 243xxxx 段；`spec/npc`、`spec/script`、`spec/runOnPickup` |
| `boolean isKarmaAble(int itemId)` | 可用剪刀 | `info/tradeAvailable > 0` |
| `int getStateChangeItem(int itemId)` | 状态触发物 | `info/stateChangeItem` |
| `int getCreateItem(int itemId) / int getMobItem(int itemId) / int getUseDelay(int itemId) / int getMobHP(int itemId) / int getExpById(int itemId) / int getMaxLevelById(int itemId)` | 杂项单值 | 依次 `info/create`、`info/mob`、`info/useDelay`、`info/mobHP`、`spec/exp`、`info/maxLevel`（默认 256），各自缓存 |
| `Pair<Integer, List<RewardItem>> getItemReward(int itemId)` | 随机奖励包 | 解析 `reward` 子节点为 RewardItem（item/prob/count/Effect/worldMsg/period），返回总概率与列表 |
| `boolean isConsumeOnPickup(int itemId)` | 拾取即用 | `spec/consumeOnPickup` 或 `specEx/consumeOnPickup` 为 1 |
| `final boolean isTwoHanded(int itemId)` | 双手判定 | 按 WeaponType 枚举（双手剑/斧/锤/弓/弩/爪/枪/矛/拳/枪械） |
| `boolean isCash(int itemId)` | 点卷物品 | 百万段 == 5，或装备 stats `cash == 1` |
| `boolean isUpgradeable(int itemId)` | 可强化 | 新建装备有升级槽或任一属性 > 0 |
| `boolean isUnmerchable(int itemId)` | 不可雇佣商店 | `use_enforce_unmerchable_cash`+点卷 或 `use_enforce_unmerchable_pet`+宠物 |
| `Collection<Item> canWearEquipment(Character chr, Collection<Item> items)` | 登录批量穿装校验 | 已 `inv.checked()` 直接放行；GM 全穿；否则累计已穿装备加成后逐件过滤（等级/四维/人气不达标跳过），通过的 `wear(true)`；最后 `inv.checked(true)` 防重复检查 |
| `boolean canWearEquipment(Character chr, Equip equip, int dst)` | 单件穿装校验 | ① 婚戒在婚礼地图禁穿（双人特效会掉线）；② `islot` 与目标槽不匹配视为改包：`equip.wear(false)` + 全服 GM 黄字 + `AutobanFactory.PACKET_EDIT` 告警；③ GM 放行；④ 等级/四维（总属性）/人气不达标 `wear(false)` 拒绝 |
| `ArrayList<Pair<Integer, String>> getItemDataByName(String name)` / `static ArrayList<Pair<Integer, String>> getItemsIDsFromName(String search)` | 按名查物品 | 在 getAllItems 基础上大小写不敏感 contains |
| `int getEquipLevel(int itemId, boolean getMaxLevel)` | 装备可升级信息 | `getMaxLevel=true` 沿 `info/level/info` 逐级探测最大等级；false 只探测 1 级是否可升（>1 子节点） |
| `List<Pair<String, Integer>> getItemLevelupStats(int itemId, int level)` | 升级随机属性 | 每条 inc*Min/Max 以 90% 概率参与，值取 [Min, Max] 随机 |
| `Pair<String, Integer> getMakerReagentStatUpgrade(int itemId)` | 试剂属性加成 | 查 `makerreagentdata`（stat, value），含 null 负缓存 |
| `int getMakerCrystalFromLeftover(Integer leftoverId)` | 残渣→怪物结晶 | `drop_data` 找 dropper → `LifeFactory.getMonsterLevel` → `getCrystalForLevel` 按等级段映射基础/中级/高级结晶 |
| `MakerItemCreateEntry getMakerItemEntry(int toCreate)` | 制造配方 | 查 `makercreatedata`（需求等级/制造等级/费用/产出）+ `makerrecipedata`（材料），缓存并返回拷贝（防外部 addCost 污染缓存） |
| `int getMakerCrystalFromEquip(Integer equipId) / int getMakerStimulantFromEquip(Integer equipId)` | 装备→结晶 | 均按装备需求等级映射结晶 |
| `List<Pair<Integer, Integer>> getMakerDisassembledItems(Integer itemId)` | 分解产物 | `makerrecipedata` 中 426xxxx 结晶材料数量减半返还 |
| `int getMakerDisassembledFee(Integer itemId)` | 分解费用 | `req_meso × 13.636%`，向下取整到千 |
| `int getMakerStimulant(int itemId)` | 催化剂 | 遍历 ETC `ItemMake.img` 找到条目后读 `catalyst` |
| `Set<String> getWhoDrops(Integer itemId)` | 掉落来源 | `drop_data` LIMIT 50 → `MonsterInformationProvider.getMobNameFromId` |
| `List<Integer> usableMasteryBooks(Character player) / List<Integer> usableSkillBooks(Character player)` | 可用技能/精通书 | 2290000–2290139 / 2280000–2280019 逐个 `canUseSkillBook`（skillid 匹配职业、达到 reqSkillLevel、masterLevel 未达上限） |
| `final QuestConsItem getQuestConsumablesInfo(final int itemId)` | 任务兑换物信息 | 存在 `info/uiData` 才解析 exp/grade/questId 与 consumeItem 集合 |
| `final ItemCashInfo getItemCashInfo(int itemId)` | 点卷时限信息 | `info/addTime`、`info/maxDays` |

**内部类**：`ScriptedItem`（npc/script/runOnPickup 三字段 + getter）；`RewardItem`（公开字段 itemid/period/prob/quantity/effect/worldmsg）；`QuestConsItem`（questid/exp/grade/items + `getItemRequirement(int itemid)`）；`ItemCashInfo`（maxDays/addTime）。

---

### StatEffect

技能/物品效果统一载体：从 Skill.wz / Item.wz 的 level/spec 节点装载全部数值与 Buff/怪物状态映射，并负责把效果「应用」到角色（含组队扩散、怪物 debuff、召唤、魔法门、毒雾等特殊技能）（源码路径：`server/StatEffect.java`，约 1960 行）。

**关键字段**：攻击/防御/命中/回避/速度/跳跃（`watk…jump`）、`hp/mp`（定量）与 `hpR/mpR`（百分比）、dojo 增益 `mhpR/mmpR/mhpRRate/mmpRRate`、消耗 `mpCon/hpCon`、`duration`（物品以 ms、技能装载后 ×1000）、`target/barrier/mob`、`overTime/repeatEffect`、`sourceid`（正=技能、负用于物品 buff 标识）、`moveTo`、`cp/nuffSkill`（CPQ）、`cureDebuffs`、`skill`、`statups`（BuffStat 列表）、`monsterStatus`（怪物状态表）、`x/y/mobCount/moneyCon/cooldown/morphId/ghost/fatigue/berserk/booster`、`prop`（触发率）、`itemCon/itemConNo`、`damage/attackCount/fixdamage`、`lt/rb`（范围框）、`bulletCount/bulletConsume`、`mapProtection`、`cardStats`（怪物卡增益，内部类 `CardItemupStats`：itemCode/prob/areas/party）。

**方法表**

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static StatEffect loadSkillEffectFromData(Data source, int skillid, boolean overtime)` | 装载技能效果 | `loadFromData(source, skillid, true, overtime)` |
| `static StatEffect loadItemEffectFromData(Data source, int itemid)` | 装载物品效果 | `loadFromData(source, itemid, false, false)` |
| `private static StatEffect loadFromData(Data source, int sourceid, boolean skill, boolean overTime)` | 巨型解析器 | ① 基础数值：time/hp/hpR/mp/mpR/mpCon/hpCon/prop/cp/cure（poison/seal/darkness/weakness→WEAKEN+SLOW/curse）/nuffSkill/mobCount/cooltime/morph/ghost/incFatigue/repeatEffect/mobSkill/target/mob；② 非技能且 duration > -1 恒为 overTime，技能 duration ×1000；③ statups 组装：通用八维 + AURA/MAP_PROTECTION；物品细分金字塔 buff（berserk/booster）、 dojo/HP-MP 回复（HPREC/MPREC）、倍率券（COUPON_EXP1-4/DRP1-3）、怪物卡（MESO_UP_BY_ITEM/ITEM_UP_BY_ITEM/RESPECT_P_IMMUNE/RESPECT_M_IMMUNE/DEFENSE_ATT/DEFENSE_STATE/thaw→MAP_PROTECTION，并建 `cardStats`：con 节点 sMap/eMap 区间与 type=2 组队限定）、经验加成（EXP_INCREASE）、地图椅子（MAP_CHAIR）；技能侧 `use_ultra_nimble_feet` 强化初心者加速；④ 技能专属巨型 switch（百余 case）：把 sourceid 映射到 BuffStat（如 HYPER_BODY、COMBO、WK_CHARGE、SHARP_EYES(x<<8\|y)、DARKSIGHT、SUMMON、MAPLE_WARRIOR、ARAN_COMBO 等）与 MonsterStatus（STUN/FREEZE/POISON/SEAL/SHOWDOWN/TAUNT 等，冰冻系 duration ×2）；⑤ morph/ghost 追加；⑥ lt/rb 范围框（`use_max_range_echo_of_hero` 时英雄之吼扩为全图）；⑦ `use_ultra_recovery` 将 Recovery 的 x ×10 |
| `boolean isActive(Character applyto)` | 卡片增益是否生效 | 按 mapid 区域 + （party 限定时）同图队友数 > 1 |
| `int getCardRate(int mapid, int itemid)` | 卡片爆率加成 | itemCode 为 MAX_VALUE 全物品生效；<1000 按物品大类前缀匹配；否则精确匹配 |
| `void applyPassive(Character applyto, MapObject obj, int attack)` | 被动触发（MP 吸收） | 掷骰通过且目标是普通怪时按 `x%` 吸取怪物 MP（上限其当前 MP），并广播 buff 特效 |
| `boolean applyEchoOfHero(Character applyfrom)` | 英雄之吼 | 施加者自身 `applyTo` + 对同图其他玩家逐一施加（修复不加同图玩家的问题） |
| `boolean applyTo(Character chr)` / `applyTo(Character, boolean useMaxRange)` / `applyTo(Character, Point pos)` | 应用效果公开入口 | 统一转调私有全参版本（primary=true） |
| `private boolean applyTo(Character applyfrom, Character applyto, boolean primary, Point pos, boolean useMaxRange, int affectedPlayers)` | 应用核心 | ① GM HIDE 直接 `toggleHide(false)`；② 治疗类先 `applyBuff` 数同队受益人数（用于治疗量分摊）；③ `calcHPChange/calcMPChange` 计算 HP/MP 变化；④ primary 且 `itemConNo` 非零：校验并消耗道具；⑤ 非 primary 且是复活：满血 + 广播站姿；⑥ dispel→`dispelDebuffs`、cure-all（意志/白色圣水）→`purgeDebuffs`、Aran 连击重置；⑦ `applyHpMpChange` 失败（会致死）则中断；⑧ `moveTo != -1`：回城/防放逐卷（`getLastBanishData`）/指定地图（带地区合法性校验）传送；⑨ 影分身之镖：USE 栏按序找足量飞镖消耗 `bulletConsume`；⑩ overTime/召唤：先取消同类旧 buff（PUPPET/SUMMON）再 `applyBuffEffect`；⑪ primary 时 overTime 组队扩散 `applyBuff`、怪物 debuff `applyMonsterBuff`；⑫ 坐骑疲劳累加；⑬ 召唤物在 pos 生成（`spawnSummon` + addHP(x)，Beholder 再 +1）；⑭ 魔法门（FieldLimit.DOOR 校验、落脚点取 fh/地面、失败退还魔法石并提示原因）；⑮ 毒雾/烟幕（`calculateBoundingBox` 生成 Mist）；⑯ TimeLeap 清冷却；⑰ CPQ：`cp` 加分、`nuffSkill` 用 `CarnivalFactory.getSkill` 对敌方施加 debuff（targetsAll 全体否则随机一名）；⑱ `cureDebuffs` 逐项解除；⑲ 物品自带 mobSkill 时给自己或全图他人上 debuff |
| `private int applyBuff(Character applyfrom, boolean useMaxRange)` | 组队扩散 | 有范围框且（有队伍或 GM buff）时：useMaxRange 用全图矩形否则技能范围框，筛选存活（复活则筛死亡）同队角色逐一递归 `applyTo(primary=false)` 并广播特效，返回受影响人数（治疗分摊用） |
| `private void applyMonsterBuff(Character applyfrom)` | 怪物 debuff | 范围框内 MONSTER 逐个：dispel→`debuffMob`、Seal 对 Boss 跳过、其余掷骰后 `applyStatus(MonsterStatusEffect…)`，Crash 系再 debuffMob；最多 `mobCount` 只 |
| `boolean hasBoundingBox()` | 是否有范围框 | `lt != null && rb != null` |
| `Rectangle calculateBoundingBox(Point posFrom, boolean facingLeft)` | 计算实际范围矩形 | 朝左直接平移 lt/rb；朝右 X 轴镜像（修复 AoE 抖动 bug） |
| `int getBuffLocalDuration()` | 本地 buff 时长 | `use_buff_everlasting` 时返回 `Integer.MAX_VALUE`（永久） |
| `void silentApplyBuff(Character chr, long localStartTime)` | 登录静默恢复 | 炼金术修正时长后 `registerEffect(..., true)` 静默注册；召唤物按需重建（非固定型）；战舰公告 HP |
| `final void applyComboBuff(Character applyto, int combo)` | Aran 连击显示 | giveBuff（ARAN_COMBO, combo, 99999ms）+ `registerEffect`（LONG.MAX 过期） |
| `final void applyBeaconBuff(Character applyto, int objectid)` | 制导标记 | HOMING_BEACON 绑定怪物 objectid，换图不掉（修复自动标记 bug） |
| `void updateBuffEffect(Character target, List<Pair<BuffStat, Integer>> activeStats, long starttime)` | 断线重连刷新 buff | 计算剩余时长 > 0 才补发 giveBuff（海盗系走专用封包） |
| `private void applyBuffEffect(Character applyfrom, Character applyto, boolean primary)` | buff 落地 | ① 非骑乘/券/门/终极大/连击先 `cancelEffect` 旧效果；② 骑乘：从 EQUIPPED -18 槽取坐骑，战舰/飞船/雪吉/扫帚/巴洛格特殊 mountId，`applyto.mount` 并注册饥饿调度，duration/sourceid 互换编码；③ 技能变身按性别 +100；④ primary 经 `alchemistModifyVal` 延长并向全图播特效；⑤ 按技能类型选封包：Dash/Infusion 海盗 buff、WK 属性充能、暗影/风行者（Ds/Ww）、连击（COMBO 当前计数）、骑乘（含战舰 HP 重置）、影子伙伴、灵魂箭、Enrage（消耗能量珠）、Morph、Ariant 护盾；⑥ `registerEffect(this, starttime, starttime+duration)` 登记 + 广播 mbuff |
| `private int calcHPChange(Character applyfrom, boolean primary, int affectedPlayers)` | HP 变化量 | 物品类经炼金术加成；技能治疗按 `maxHp * hp% / affectedPlayers` 分摊；ZOMBIFY 减半/治疗反伤（变扣血且清 hpCon）；hpR 百分比；hpCon 消耗；Chakra 按 LUK 随机回复；GM 群疗直接满血 |
| `private int calcMPChange(Character applyfrom, boolean primary)` | MP 变化量 | 炼金术加成、mpR、mpCon × 魔法增幅（火毒/冰雷/炎术/龙魔各取对应技能 x%）、INFINITY 免魔、CONCENTRATE 按比例减免；GM 群疗满 MP |
| `private int alchemistModifyVal(Character chr, int val, boolean withX)` | 炼金术加成 | 非技能且职业为（夜）行者时按 ALCHEMIST 的 x%（时长）或 y%（药效）放大 |
| `private boolean isGmBuff() …（isMonsterBuff / isPartyBuff / isHeal / isResurrection / isTimeLeap / isDragonBlood / isBerserk / isRecovery / isMapChair / isDs / isWw / isCombo / isEnrage / isBeholder / isShadowPartner / isChakra / isCouponBuff / isAriantShield / isMysticDoor / isMonsterRiding / isMagicDoor / isPoison / isMorph / isMorphWithoutAttack / isMist / isSoulArrow / isShadowClaw / isCrash / isSeal / isDispel / isCureAllAbnormalStatus / isWkCharge / isDash / isSkillMorph / isInfusion / isCygnusFA / isHyperBody / isComboReset）` | 技能类型谓词族 | 均按 sourceid 白名单或 statups 内容判定；`isPartyBuff` 需有范围框且排除骑士团充能系；`isMorphWithoutAttack` 以 morphId<100 区分物品变身 |
| `public static boolean isMapChair(int sourceid)` / `static boolean isHpMpRecovery(int sourceid)` / `static boolean isAriantShield(int sourceid)` / `static boolean isHerosWill(int skillid)` | 静态谓词 | 供其他模块（物品使用、装载器）复用 |
| `private SummonMovementType getSummonMovementType()` | 召唤物移动类型 | 木偶/章鱼→STATIONARY；鹰/龙/凤凰等→CIRCLE_FOLLOW；精灵/黑暗/雷等→FOLLOW；其余 null（非召唤） |
| `boolean isSkill()` / `int getSourceId()` / `void setSourceId(int id)` / `int getBuffSourceId()` | 来源标识 | buff 来源技能取正、物品取负 sourceid |
| `boolean makeChanceResult()` | 触发率掷骰 | `prop == 1.0 ∥ random < prop` |
| `short getHp() … Map<MonsterStatus, Integer> getMonsterStati()`（约 30 个 getter） | 数值读取器 | getMp/getHpRate/getMpRate/getHpR/getMpR/getHpRRate/getMpRRate/getHpCon/getMpCon/getMatk/getWatk/getDuration/getStatups/sameSource（sourceid+skill 双判）/getX/getY/getDamage/getAttackCount/getMobCount/getFixDamage/getBulletCount/getBulletConsume/getMoneyCon/getCooldown 等 |

---

### TimerManager / TimerManagerMBean / ThreadManager

**TimerManager**：全服统一调度器（单例），封装 `ScheduledThreadPoolExecutor`（4 线程、命名 Worker、keepAlive 5 分钟、核心线程可超时、取消即移除），并注册 JMX MBean 供监控（源码路径：`server/TimerManager.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static TimerManager getInstance()` | 单例 | `@Getter` 静态字段；构造器注册 JMX `server:type=TimerManger`（拼写遗留） |
| `void start()` | 启动线程池 | 已在运行则直接返回；`scheduleAtFixedRate` 关停策略 false、`setRemoveOnCancelPolicy(true)` |
| `void stop()` | 立即停机 | `shutdownNow` |
| `Runnable purge()` | 清理任务 | 返回的 Runnable 先 `Server.getInstance().forceUpdateCurrentTime()` 再 `ses.purge()`（通常自周期执行） |
| `ScheduledFuture<?> register(Runnable r, long repeatTime, long delay)` / `register(Runnable r, long repeatTime)` | 固定频率周期任务 | 包 `TimerRunner` 后 `scheduleAtFixedRate`（无 delay 重载立即首跑） |
| `ScheduledFuture<?> registerWithFixedDelay(Runnable r, long repeatTime, long delay)` | 固定延迟周期任务 | `scheduleWithFixedDelay`：上一轮结束到下一轮开始间隔固定，适合存档类任务——卡顿后不追赶积压、避免连续多次 DB 写入（BeiDou 新增，中文注释说明） |
| `ScheduledFuture<?> update(ScheduledFuture<?> sf, Runnable r, long repeatTime)` | 重排任务 | 先 `stop(sf)` 再按固定频率重建 |
| `void stop(ScheduledFuture<?> sf)` | 取消任务 | 非空且未取消时 `cancel(false)` |
| `ScheduledFuture<?> schedule(Runnable r, long delay)` | 一次性延时任务 | `TimerRunner` 包装 |
| `ScheduledFuture<?> scheduleAtTimestamp(Runnable r, long timestamp)` | 定点任务 | delay = timestamp - 当前时间 |
| `long getActiveCount() / getCompletedTaskCount() / int getQueuedTasks() / long getTaskCount() / boolean isShutdown() / boolean isTerminated()` | JMX 监控 | 实现自 `TimerManagerMBean`，透传线程池状态 |

内部类 `TimerRunner`：包装 Runnable，`catch (Throwable)` 记 error 日志，防止单个任务异常杀死调度线程。

**TimerManagerMBean**：接口，声明上表 6 个监控方法，供 JMX 暴露。

**ThreadManager**：通用异步任务执行器（单例），`start()` 改用 **Java 21 虚拟线程** `Executors.newVirtualThreadPerTaskExecutor()`（虚拟线程不池化故无需拒绝策略，源码注释）；`newTask(Runnable)` 提交；`stop()` shutdown 后 `awaitTermination` 5 分钟。

---

### Marriage

婚礼副本会话：继承 `EventInstanceManager`，在其属性表之上实现新婚礼礼物（wishlist/礼物列表）的存取与 DB 持久化（源码路径：`server/Marriage.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `Marriage(EventManager em, String name)` | 构造 | 转调父类 |
| `boolean giftItemToSpouse(int cid)` | 礼物是否直接给配偶 | 属性 `wishlistSelection == 0` |
| `List<String> getWishlistItems(boolean groom)` | 取心愿单 | 读 `groomWishlist/brideWishlist` 属性按 `\r\n` 拆分，空返回空列表 |
| `void initializeGiftItems()` | 初始化礼物容器 | 向属性写入空的 `groomGiftlist/brideGiftlist` List |
| `List<Item> getGiftItems(Client c, boolean groom)` | 快照礼物列表 | 对底层 List `synchronized` 后拷贝返回 |
| `Item getGiftItem(Client c, boolean groom, int idx)` | 取单件礼物 | 越界返回 null |
| `void addGiftItem(boolean groom, Item item)` / `void removeGiftItem(boolean groom, Item item)` | 增删礼物 | 同步块内操作 |
| `Boolean isMarriageGroom(Character chr)` | 判定新郎/新娘 | 比对 `groomId/brideId` 属性；既非新郎也非新娘返回 null，解析失败同 |
| `static boolean claimGiftItems(Client c, Character chr)` | 领取 DB 中滞留礼物 | `Inventory.checkSpot` 放得下才：清空 `ItemFactory.MARRIAGE_GIFTS` 存储、逐件 `addFromDrop`；放不下返回 false（下次再领） |
| `static List<Item> loadGiftItemsFromDb(Client c, int cid)` | 装载礼物 | `MARRIAGE_GIFTS.loadItems` |
| `void saveGiftItemsToDb(Client c, boolean groom, int cid)` | 保存单侧礼物 | 转调静态重载 |
| `static void saveGiftItemsToDb(Client c, List<Item> giftItems, int cid)` | 保存礼物列表 | 带 InventoryType 配对后 `MARRIAGE_GIFTS.saveItems` |

---

### 其余根目录小类

**DueyPackage**（送货员包裹）：字段 sender/item/mesos/message/timestamp(Calendar)/packageId/receiverId；双构造器（带物品 / 纯金币包裹）。`sentTimeInMilliseconds()` 在寄出时间上加 1 个月（客户端月数组表示法）；`isDeliveringTime()` 判 `ts >= now`；`setSentTime(Timestamp, boolean quick)`：快速包裹且寄出不足 1 天时把时间回拨 1 天，保证立即可取（修复快速包裹无法立即领取）。其余为 getter/setter。

**MTSItemInfo**（MTS 挂售条目）：构造时按 `yyyy-MM-dd` 解析截止日期拆 year/month/day；`getTaxes()` 返回 `100 + price/10`；`getEndingDate()` 用 Calendar 拼回毫秒时间戳；`getItem/getPrice/getID/getSeller`。

**ChatLogger**：静态 `log(Client c, String chatType, String message)`，`use_enable_chat_log` 开启时以 `(type) name: message` 记 info。

**ExpLogger**（经验日志异步落库）：`LinkedBlockingQueue<ExpLogRecord>`（record 字段：worldExpRate/expCoupon/gainedExp/currentExp/expGainTime/charid）；`putExpLogRecord` 入队；静态块在 `use_exp_gain_log` 时启动单线程 MIN_PRIORITY 调度器，每 60 秒 `drainTo` 批量 INSERT `characterexplogs`；注册 ShutdownHook，`stopExpLogger` 关闭并等待 5 分钟后补跑一次落库。

**MapleLeafLogger**：静态 `log(Character player, boolean gotPrize, String operation)`——金枫叶兑换/消费审计一行日志。

**CommonInformation**（String.wz 硬查服务，单例）：`getStringInformation(InformationSearch condition)`——types 非空校验后按 `InformationType`（CASH/CONSUME/EQP/ETC/INS/MAP/MOB/NPC/PET/SKILL）路由 `searchXML`（Eqp/Map 需逐子目录展开，Map 用 `mapName/streetName`），`isMatch` 支持 filterType 0=ID+名称 / 1=仅 ID / 2=仅名称 × 全等/包含；因模糊匹配无法用 LRU 缓存，每次硬遍历 XML（源码注释）。

**MakerItemFactory**（制造系统工厂）：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static MakerItemCreateEntry getItemCreateEntry(int toCreate, int stimulantid, Map<Integer, Short> reagentids)` | 计算完整制造配方 | 基于 `ii.getMakerItemEntry` 基础配方；有催化剂加 `getMakerStimulantFee`，每种试剂按 `(key%10)+1` 级费用 × 数量累加；最后 `trimCost` 提交真实费用 |
| `static MakerItemCreateEntry generateLeftoverCrystalEntry(int fromLeftoverid, int crystalId)` | 残渣换结晶配方 | 100 个残渣 → 1 个结晶、零费用 |
| `static MakerItemCreateEntry generateDisassemblyCrystalEntry(int fromEquipid, int cost, List<Pair<Integer, Integer>> gains)` | 分解配方 | 装备 1 件 + 费用 → gains 列表 |
| `private static double getMakerStimulantFee(int itemid)` | 催化剂费用 | `use_maker_fee_heuristics` 开启时按装备类型取指数拟合公式 `a·e^(b·等级)`（帽子/长袍/鞋/手套/上衣/裤/盾/武器各有系数），否则固定 14000 |
| `private static double getMakerReagentFee(int itemid, int reagentLevel)` | 试剂费用 | 同上拟合公式 × reagentLevel，否则 `8000 × 等级` |

内部类 `MakerItemCreateEntry`：`cost`（double 累加）与 `reqCost`（`trimCost()` 向下取整到千后对外）；reqLevel/reqMakerLevel、reqItems/gainItems 列表；`isInvalid()` 以 `reqLevel < 0` 表示无效配方（查库未命中）。

**SkillbookInformationProvider**（技能书出处索引，静态工具）：`loadAllSkillbookInformation()` 汇聚三个来源——① 任务：Quest.wz `Act.img` 的 item/skill 节点（技能奖励以负 skillid 为键），`fetchQuestbook` 递归追溯 Check.img 的前置物品/任务识别「需要任务书」的条目（QUEST/QUEST_BOOK/QUEST_REWARD）；② 反应堆：`reactordrops` 表 2280000–2300000 区间 → REACTOR；③ 脚本：正则 `22(8|9)\d{4}` 扫描 `./scripts` 下 .js → SCRIPT；整体 volatile Map 替换。`getSkillbookAvailability(int itemId)` 未命中返回 UNAVAILABLE；`getTeachableSkills(Character chr)` 取负键中属于当前职业四转且 masterLevel==0 的技能（供 NPC 教学脚本使用，全类仅此一处调用点）。

**SystemRescue**（系统救援，`@Getter`）：城镇白名单 `MapIdList`（射手/密林/勇士/废弃/明珠）。`setMapChange(Character player)`：读 `system_rescue_maperror_changeid`（>0 启用；目标图不存在则从白名单随机、且排除当前图）；目标为自由市场入口时先处理限时/活动图强制返回与 `saveLocation("FREE_MARKET")`；直接 `changeMap` 后将「从 X 图救援至 Y 图」写入角色扩展值 `系统救援_卡地图_系统通知` 并记日志。`showMapChangeMessage(Character)`：上线时从扩展值取出消息以 5 频道红字投递并置空，随后弹窗补充提示；`dropMessage` 私有方法封装扩展值读取/清空（try-catch Throwable 防干扰登录流程）。

---

## 2. gachapon（15 个类）

### Gachapon / GachaponItems / GachaponItem

**Gachapon**（单例，源码路径：`server/gachapon/Gachapon.java`）：转蛋系统总控。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static Gachapon getInstance()` | 单例 | 饿汉 |
| `GachaponItem process(int npcId)` | 抽取一次转蛋 | `GachaponType.getByNpcId` → `getTier()` 按权重抽稀有度 → `getItem(tier)` 抽物品，封装为 `GachaponItem(tier, id)` |
| `static void log(Character player, int itemId, String map)` | 转蛋日志 | 解析物品名后 i18n 记 info（玩家/物品/地图） |

内部枚举 `GachaponType`：13 个条目（GLOBAL 权重全 -1 + 12 个城镇，权重 90/8/2 对应 Common/Uncommon/Rare），字段 npcId/gachapon/common/uncommon/rare。

| GachaponType 方法 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `private int getTier()` | 抽稀有度 | `1 + rand(总权重)`，落在尾部为 Rare(2)、中段 Uncommon(1)、头部 Common(0) |
| `public int[] getItems(int tier)` | 该城镇该档物品表 | 委托城镇 `GachaponItems.getItems` |
| `public int getItem(int tier)` | 抽最终物品 | 城镇池与 GLOBAL 池拼接后等概率抽取（GLOBAL 兜底池保证任何城镇都有基础产出） |
| `public static GachaponType getByNpcId(int npcId)` | NPC→类型 | 线性查找，未命中 null |
| `public static String[] getLootNames()` / `public static int[] getLootIds()` | 转蛋 NPC 名录/ID | 供 `!gacha` 查询指令使用（i18n 名称 + 10 个 NPC） |

内部类 `GachaponItem`：`getTier()/getId()` 两个 getter 的纯结果对象。

**GachaponItems**（抽象基类）：子类实现 `getCommonItems()/getUncommonItems()/getRareItems()` 三个抽象方法；构造器即时调用并缓存三个数组；`final int[] getItems(int tier)` 按 0/1/2 分发（非法 tier 返回 null）。

### 13 个城镇数据类

`Global`（全局兜底池：Common=矿石/矿物 4020000–4020008，Uncommon=特殊药水+卷轴 2049003，Rare=混沌卷 2049100、防护卷 2340000 及两把椅子）、`Henesys`、`Ellinia`、`Perion`、`KerningCity`、`Sleepywood`、`MushroomShrine`（Rare 含 `1102084, 3010019`）、`ShowaSpaMale`、`ShowaSpaFemale`（男女温泉共用类似池）、`Ludibrium`、`NewLeafCity`、`ElNath`（Rare 含 `2043803, 1102085`）、`NautilusHarbor`。全部继承 `GachaponItems`、无任何逻辑：Common 为按职业分组的大数组（战士/法师/弓手/飞侠/海盗装备），Uncommon/Rare 为少量高价值物品（多数城镇 Rare 为空数组，稀有产出实际由 GLOBAL 池补足）。

---

## 3. partyquest（7 个类）

### PartyQuest

组队任务基类：按队伍队长所在频道+地图筛选实际可参与的在线成员（源码路径：`server/partyquest/PartyQuest.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `PartyQuest(Party party)` | 构造 | 记录 channel/world；仅与队长同频道同地图的成员经 `PlayerStorage` 解析为 `Character` 纳入 participants |
| `Party getParty() / List<Character> getParticipants()` | 读取器 | — |
| `void removeParticipant(Character chr) throws Throwable` | 移除成员 | `synchronized(participants)` 移除并 `chr.setPartyQuest(null)` |
| `static int getExp(String PQ, int level)` | PQ 结算经验表 | 16 种阶段键（HenesysPQ 1250、KerningPQ 1st–Final 100–500、LudiMazePQ 2000、LudiPQ 1st–Last 100–800）统一 `base * level / 5`；未知键告警返回 0 |

### Pyramid

奈特斯金字塔（大乱斗前身的单人/组队副本），继承 `PartyQuest`（源码路径：`server/partyquest/Pyramid.java`）。

**关键字段**：`kill/miss/cool/exp` 计数、`map`（起始图）、`count`（客户端仪表值）、`skill`（可用技能次数）、`coolAdd(5+mode)/missSub(4+mode)/decrease(1–3)`（随难度 EASY..HELL 递增）、`gauge`（0–100 血条）、`rank/stage/buffcount`、`mode`、`timer/gaugeSchedule` 两个调度句柄；常量：法老守护者任务 29932 / 进度键 7760 / NPC 9000066 / 50000 击杀。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `Pyramid(Party party, PyramidMode mode, int mapid)` | 构造 | super 筛选成员并逐一 `setPartyQuest(this)`；按难度调 coolAdd/missSub/decrease |
| `void startGaugeSchedule()` | 启动血条衰减 | gauge=100 后每秒 `gauge -= decrease`，归零强制 `warp(NETTS_PYRAMID)` 退出 |
| `void kill() / void cool()` | 击杀/技能击杀计数 | 两者均调 `recordProtectorOfPharaohKill`；gauge 回升（cool 加成更多、封顶 100）并 `broadcastInfo`；触发 `checkBuffs` |
| `void miss()` | Miss 惩罚 | count/gauge 双扣 missSub |
| `private void recordProtectorOfPharaohKill()` | 法老守护者任务进度 | 对每个未完成任务（状态非 1 自动 forceStart、已完成跳过）且进度 < 50000 的成员累加进度 |
| `int timer()` | 阶段计时 | 取消旧计时；1 阶段 120s、其余 180s；到点进入下一阶段（≥5 直接退出图）；下发各计数与 5 分钟时钟；启动 gauge 调度 |
| `void warp(int mapid)` | 换图 | 目标是本副本下一阶段图（偏移 0–400 且 %100==0）时更新 stage 并续 `timer()`；否则（退出图）停 gauge 调度并解除成员 partyQuest 绑定 |
| `void leave(int mapid)` | 主动离开 | 停全部调度、成员解绑+传送 |
| `void broadcastInfo(String info, int amount)` | 广播计数 | `massacre_<info>` 能量包 + pyramidGauge 仪表包 |
| `boolean useSkill()` | 使用特殊技能 | skill > 0 才扣减并广播，返回成功与否 |
| `void checkBuffs()` | 阶梯奖励 | kill+cool 达 250/500/1000/1500/2000/2500/3000 时依次发放法老祝福 1–4 buff 与 skill 次数（buffcount 防重发） |
| `void sendScore(Character chr)` | 结算 | 按 stage==5 与否用不同阈值定 rank 0–4；exp = 档位基数 + mode 加成 + `kill*2 + cool*10`（rank 4 为 0）；只算一次（exp 缓存）；发 pyramidScore 包并 `gainExp` |

内部枚举 `PyramidMode`：EASY(0)/NORMAL(1)/HARD(2)/HELL(3) + `getMode()`。

### AriantColiseum

阿里安特竞技场（PVP 换装战）：基于 `Expedition` 组队进入，10 分钟限时，按「精神宝石」数量积分（源码路径：`server/partyquest/AriantColiseum.java`）。

**关键字段**：`exped/map`、`score/rewardTier`（Character→分值/奖励档）、`scoreDirty`、三个调度句柄（更新/结束/记分板）、`lostShards`、`eventClear`。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `AriantColiseum(MapleMap eventMap, Expedition expedition)` | 开局 | 结束报名；地图 `resetFully`；10 分钟主计时 + 9 分 50 秒记分板 + 500ms 分数刷新三个调度；成员换图、绑定 `setAriantColiseum(this)`、初始化计分与奖励档 |
| `int getAriantScore(Character) / void clearAriantScore(Character)` | 分数读写 | Map 操作，无记录按 0 |
| `void updateAriantScore(Character chr, int points)` | 更新分数 | 置 scoreDirty 供周期广播 |
| `private void broadcastAriantScoreUpdate()` | 脏标记广播 | 仅 scoreDirty 时向场上成员发排名包后复位 |
| `int getAriantRewardTier(Character) / void clearAriantRewardTier(Character)` | 奖励档读写 | — |
| `void addLostShards(int quantity)` | 掉落宝石计数 | 场上未拾取的宝石计入公平性判定 |
| `void leaveArena(Character chr) / void playerDisconnected(Character chr)` | 离场/掉线 | 已结算且仍在场内不处理；否则 `leaveArenaInternal`（synchronized）：探险队移除成员、剩余 < 2 人（结算后 < 1）则 dispose；回收成员身上 ARPQ_SPIRIT_JEWEL 并计分 |
| `private void showArenaResults()` | 展示结果 | `eventClear = true`；广播记分板、清怪、`distributeAriantPoints` |
| `private static boolean isUnfairMatch(...)` | 比赛公平性 | 冠军分 > 0 且「其余总分+落地产石 / 冠军分 < 0.8177…」且（总分 < 7 或第二名占比 < 0.5929）视为碾压局 |
| `void distributeAriantPoints()` | 分配奖励档 | 找出冠军/亚军；每人的档 = `floor(分数/10)`；碾压局强制冠军档降为 1（含掉落宝石补偿判定） |
| `private ExpeditionType getExpeditionType()` | 竞技场类型 | 按 3 张竞技场图映射 ARIANT/ARIANT1/ARIANT2 |
| `private void enterKingsRoom()` | 进入王之房间 | 从频道探险队注销、取消调度、全员传送 ARPQ_KINGS_ROOM |
| `private synchronized void dispose()` | 收尾 | `exped.dispose(false)`；全员解绑并回 ARPQ_LOBBY；5 分钟后延迟清空 score/exped/map（防正在使用的引用） |

### MonsterCarnival / MonsterCarnivalParty / CarnivalFactory / GuardianSpawnPoint

**MonsterCarnival**（CPQ 怪物嘉年华，红蓝两队对抗，源码路径：`server/partyquest/MonsterCarnival.java`）：

**关键字段**：`p1/p2`（两队 Party）、`map`（`getDisposableMap` 一次性副本图）、`timer/effectTimer/respawnTask`、`startTime`、`summonsR/summonsB`、`room`、`leader1/leader2`、`team1/team2`（在场哨兵引用）、红蓝 `CP/TotalCP/TimeupCP`、`cpq1`（CPQ1/CPQ2）。等级常量 `D=3 C=2 B=1 A=0`（奖励等级）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `MonsterCarnival(Party p1, Party p2, int mapid, boolean cpq1, int room)` | 开局 | 互设 enemy；取一次性地图；双方成员分队（红 0 蓝 1）、清 festival points、换图（紫图红门 2 蓝门 1）、识别队长；任一队无在线成员则全员报错返回；排三个调度：`map.getTimeDefault()` 秒后 timeUp、提前 10 秒 complete（避免重复发效果包）、按 `respawn_interval` 周期 respawn；向频道登记占用 |
| `public void playerDisconnected(int charid) / public void leftParty(int charid)` | 掉线/退队 | 定位所属队后向全场广播退出消息（红/蓝 i18n），`earlyFinish` 提前结束 |
| `public boolean canSummonR() / summonR() / canSummonB() / summonB()` | 召唤配额 | 已召量 < `map.getMaxMobs()` |
| `public boolean canGuardianR() / canGuardianB()` | 守护塔配额 | 统计图中名称前缀 0/1 的 Reactor 数 < `map.getMaxReactors()` |
| `protected void dispose(boolean warpout)` | 清理 | cpq1/cpq2 分别回 980000010/980030010；两队员 resetCP、team=-1、解绑、按需传送；取消三个调度；清 enemy；`map.dispose()` 置空；`cs.finishMonsterCarnival` 释放房间 |
| `public void exit()` / `protected void dispose()` | 退出入口 | dispose(false) |
| `private void finish(int winningTeam)` | 结算 | 胜队传 `map.getId()+2(+200)` 败队 `+3(+300)`（胜/败奖励房）；每人 `SpecialChallengeMedal.onMonsterCarnivalFinished` 记录特级挑战勋章胜负场（BeiDou 新增）、`gainFestivalPoints(队 TotalCP)`、解绑、清 debuff；最后 dispose |
| `private void timeUp()` | 到时判定 | 用 timeup 时点快照 CP：平局 `extendTime` 加时 3 分钟（重排两调度）；否则 finish |
| `public void complete()` | 到时前效果 | 快照 TotalCP 为 timeupCP；平局直接返回；清怪并向两队员发 win/lose 效果+音效；两队长跨频道抛异常断言 |
| `private void extendTime()` | 加时 | 全员提示、重置 startTime、发 3 分钟时钟、按 `map.getTimeExpand()` 重排调度 |
| `public long getTimeLeft() / int getTimeLeftSeconds()` | 剩余时间 | startTime（开局+10min）与当前差 |
| `public int getTotalCP(int team) / setTotalCP(int, int) / getCP(int team) / setCP(int, int)` | CP 读写 | team 0 红 1 蓝，未知队抛 RuntimeException |
| `Party getRed()/setRed(Party) / Party getBlue()/setBlue(Party) / Character getLeader1()/setLeader1 / getLeader2()/setLeader2` | 队伍/队长读写 | — |
| `Character getEnemyLeader(int team)` | 敌方队长 | 0→leader2、1→leader1 |
| `int getRoom() / MapleMap getEventMap() / ScheduledFuture<?> getTimer()` | 读取器 | — |

**MonsterCarnivalParty**（旧版 CPQ 队伍包装，`server/partyquest/MonsterCarnivalParty.java`）：构造 `(owner, members, team)` 为成员 `setMonsterCarnivalParty/setTeam`；`warpOut(int map)` 全员传送并解绑后清列表；`warpOut()` 依 winner 传 `980000003/980000004 + room*100` 胜/败房；`warp(MapleMap, portalid)`；`allInMap(MapleMap)` 全员同图判定；`removeMember(Character)` 踢出并回 980000010；`isWinner/setWinner`；`displayMatchResult()` 发胜负特效；`summon()/canSummon()`（初始 8 次召唤配额，递减）。

**CarnivalFactory**（CPQ 技能/守护塔工厂，单例，`server/partyquest/CarnivalFactory.java`）：构造时 `initialize` 解析 Skill.wz `MCSkill.img` 与 `MCGuardian.img` 为 `MCSkill` 记录，并按 target>1 拆分单/多目标技能池。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static CarnivalFactory getInstance()` | 单例 | — |
| `MCSkill getSkill(final int id)` | 取 CPQ 技能 | 命中且无 mobSkillType（纯 debuff 容器）时改为从对应目标池**随机替换**一个真技能；否则原样返回 |
| `MCSkill getGuardian(final int id)` | 取守护塔技能 | guardians 直查 |

内部 `record MCSkill(int cpLoss, MobSkillType mobSkillType, int level, boolean targetsAll)`：`getSkill()` 经 `MobSkillFactory.getMobSkillOrThrow` 转 MobSkill；`getDisease()` 映射 Disease。

**GuardianSpawnPoint**：守护塔刷新点（position/taken/team），构造即 taken=true，四个 getter/setter，纯数据类。

---

## 4. movement（9 个类）

客户端移动帧的服务端镜像，用于移动校验后原样/加工转发。接口两层：`LifeMovementFragment`（`serialize(OutPacket)` + `getPosition()`）← `LifeMovement`（追加 `getNewstate()/getDuration()/getType()`）；`AbstractLifeMovement` 实现全部接口成员并持 `position/duration/newstate/type` 四个 final 字段。

| 类 | 说明 |
| --- | --- |
| `AbsoluteLifeMovement` | 绝对坐标移动：追加 `pixelsPerSecond`（速度向量）与 `fh`（落脚平台）；`serialize` 顺序 type/pos/pps/fh/newstate/duration |
| `RelativeLifeMovement` | 相对位移：无附加字段；serialize type/pos/newstate/duration |
| `TeleportMovement` | 瞬移：继承 Absolute（duration 恒 0），serialize 省略 fh 与 duration（type/pos/pps/newstate） |
| `JumpDownMovement` | 跳下：追加 `pixelsPerSecond/fh/originFh`（源码注释确认第二个 fh 实为 originFh）；serialize 依次 type/pos/pps/fh/originFh/newstate/duration |
| `ChairMovement` | 椅子移动：追加 `fh`；serialize type/pos/fh/newstate/duration |
| `ChangeEquip` | 坐姿/起立切换：直接实现 Fragment（非 LifeMovement），持 `wui`；serialize 固定写 type=10 + wui；`getPosition()` 恒 `(0,0)` |
| `LifeMovementFragment` / `LifeMovement` / `AbstractLifeMovement` | 如上接口与基类 |

各具体类的公有方法仅为构造器、字段 getter/setter 与 `serialize`。

---

## 5. events（8 个类）

### Events / RescueGaga

`Events`：活动状态抽象基类，仅 `public abstract int getInfo()`（子类向客户端上报的活动进度值）。

`RescueGaga`（救援 Gaga 活动进度，`server/events/RescueGaga.java`）：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `int getCompleted()` / `void complete()` | 完成次数 | 计数读写 |
| `int getInfo()` | 上报进度 | 返回完成次数 |
| `void giveSkill(Character chr)` | 发放活动技能 | 按 jobType 取技能基址（探险家 1013、骑士团/战神 10001014）；完成 < 20 次发 3 个 1 级技能（20 天期限），≥ 20 次把首技能升 2 级且保留原到期时间 |

### gm 子包（6 个类）

**Event**（GM 活动基类）：字段 `mapid/limit`；`getMapId()/getLimit()/minusLimit()/addLimit()`，管理活动入场人数上限。

**Coconut / Coconuts**（打椰子团队赛，`server/events/gm/Coconut.java`）：

- `Coconut(MapleMap map)`：`super(1, 50)`（1 号活动图、50 人上限）。
- `startEvent()`：图 startEvent；生成 506 个 `Coconuts`；广播开场包、`setCoconutsHittable(true)`、5 分钟时钟；到点按 Maple/Story 两队比分出胜负（平局 `bonusTime()` 加时 2 分钟再判定），对胜/负队放 victory/lose 特效后 `warpOut()`。
- `warpOut()`：置不可击打，12 秒后胜队传 `EVENT_WINNER`、其余传 `EVENT_EXIT`，`map.setCoconut(null)` 解绑。
- 计分/物资：`getMapleScore/addMapleScore/getStoryScore/addStoryScore`；爆炸/坠落/停止的椰子余量 `getBombings/bombCoconut/getFalling/fallCoconut/getStopped/stopCoconut`（初始 80/401/20）。
- `getCoconut(int id)/getAllCoconuts()/setCoconutsHittable(boolean)`：椰子访问与批量开关。

`Coconuts`（单颗椰子）：`hit()` 击打一次（hits+1，750ms 冷却时间戳）；`getHits/resetHits/isHittable/setHittable/getHitTime`。

**Fitness**（Maple 体能测验，个人限时爬塔，`server/events/gm/Fitness.java`）：构造时预排 15 分钟超时（仍在体能图则强制送回 return map）；`startFitness()` 开活动、发 900 秒时钟、开启 join00 传送门并提示；`isTimerStarted/getTime/resetTimes（取消两个调度）/getTimeLeft`；`checkAndMessage()` 以 5s+29.5s 周期任务在 12 个时间窗点（14:30→0:10 递减）发送 i18n 化的官方提示语（源码内为英文字面量），离开体能图或 `chr.getFitness() == null` 时自动 reset。

**Ola**（Ola Ola 走迷宫，`server/events/gm/Ola.java`）：构造排 6 分钟超时（强制回城 + resetTimes）；`startOla()` 开活动、发 360 秒时钟、开 join00 门；`isTimerStarted/getTime/resetTimes/getTimeLeft` 同 Fitness（无消息任务，TODO 注释）。

**OxQuiz**（答题王，`server/events/gm/OxQuiz.java`）：构造随机起始 round（0–8）、question=1；`sendQuestion()`：广播题目包后 30 秒判定——按玩家坐标分区（x>-234 且 y>-26 为 O 区，反向为 X 区）对照 `OXQuiz.img` 的答案 `a`：答错的非 GM 传送回城，答对的 +200 经验；按 round 推进 question（各 round 末题跳 100 结束）；场上非 GM 人数 ≤ 2 时公告结束、开门、`map.setOx(null)/setOxQuiz(false)`，否则递归下一题；`private isCorrectAnswer(Character, int)` 与 `private static getOXAnswer(int imgdir, int id)`（读 ETC wz）。

**Snowball**（滚雪球团队赛，`server/events/gm/Snowball.java`）：构造 `(team, map)` 收集该队角色；`startEvent()`：发滚雪球状态包 + 10 分钟时钟，10 分钟后按两队雪球 `position` 判胜负（放 3/4 号结果包）并 `warpOut()`；`isHittable/setHittable/getPosition/getSnowmanHP/setSnowmanHP`；`hit(int what, int damage)`：what<2 时命中雪球（damage>0 扣 3 击蓄力，否则打雪人——雪人 HP 打穿后 10 秒复活至 7500 并 `message(5)`）；3 击蓄满推进 position+1（到 45/290/560 给对方队发 1/2/3 号消息），随后广播 hitSnowBall；`message(int)` 向本队发 snowballMessage；`warpOut()` 10 秒后按 winner 分流胜/退场图并 `map.setSnowball(team, null)`。

---

## 6. expeditions（3 个类）

### ExpeditionType

远征类型枚举：BALROG_EASY/BALROG_NORMAL/SCARGA/SHOWA/ZAKUM/HORNTAIL/CHAOS_ZAKUM/CHAOS_HORNTAIL/ARIANT(1/2)/PINKBEAN/CWKPQ，五元组（minSize, maxSize, minLevel, maxLevel, registrationMinutes），如 ZAKUM(6, 30, 50, 255, 5)、CWKPQ(6, 30, 90, 255, 5)。`getMinSize()` 在 `use_enable_solo_expeditions` 开启时返回 1（单人远征），其余 getter 直读。

### Expedition

远征队会话：报名期（registering）→ 开团（start）→ 进行/结算，支持成员管理、Boss 击杀日志与属性表（源码路径：`server/expeditions/Expedition.java`）。

**关键字段**：`leader/type/registering/startMap/bossLogs/schedule`、`members`（ConcurrentHashMap cid→name）、`banned`（CopyOnWriteArrayList）、`startTime`、`props`（Properties + 公平锁 pL）、`silent/minSize/maxSize`；静态 `EXPEDITION_BOSSES`（扎昆全身/暗黑龙全套/狮子老虎四阶段等 29 个 Boss MobId）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `Expedition(Character player, ExpeditionType met, boolean sil, int minPlayers, int maxPlayers)` | 构造 | 队长即首个成员；size 参数为 0 时回落到类型默认 |
| `int getMinSize() / int getMaxSize()` | 人数上下限 | — |
| `void beginRegistration()` | 开始报名 | registering=true；向队长发报名倒计时时钟；非静默时向报名图广播招募公告；`scheduleRegistrationEnd` |
| `private void scheduleRegistrationEnd()` | 报名超时 | 到 `registrationMinutes` 分钟仍未开团则从频道注销、广播流局、`dispose(false)` |
| `void dispose(boolean log)` | 收尾 | 广播移除时钟、取消调度；`log && !registering` 时输出远征日志 |
| `private void log()` | 远征日志 | 向全服 GM 广播摘要；拼接类型/时长/成员名单/Boss 击杀记录后 info 落日志 |
| `void finishRegistration() / void start()` | 结束报名/开团 | start：结束报名→`registerExpeditionAttempt` 为全员登记 Boss 次数→移除时钟→公告→广播 GM 开团消息 |
| `String addMember(Character player)` | 添加成员（NPC 对话版） | 未在报名/被 ban/满员/Boss 次数不足（`ExpeditionBossLog.attemptBoss(..., false)`）分别返回对应 i18n 拒绝语；成功发剩余报名时钟并广播 |
| `int addMemberInt(Character player)` | 添加成员（脚本版） | 同上但返回 0/1/2/3 状态码（不做 Boss 次数检查） |
| `private void registerExpeditionAttempt()` | 开团登记 Boss 次数 | 对每个在线成员 `attemptBoss(..., true)` 落库 |
| `public boolean removeMember(Character chr)` | 移除成员 | 移除成功才发移除时钟+公告 |
| `public void ban(Entry<Integer, String> chr)` | 踢并拉黑 | 入 banned、移出成员、公告；在线则通知；Ariant 系额外送回 ARPQ_LOBBY |
| `public void monsterKilled(Character chr, Monster mob)` | Boss 击杀记账 | 命中 EXPEDITION_BOSSES 时追加带时间戳与耗时的 bossLogs 条目 |
| `void setProperty(String key, String value) / String getProperty(String key)` | 属性表 | 公平锁保护的 Properties（脚本交互用） |
| `ExpeditionType getType()` | 类型 | — |
| `List<Character> getActiveMembers()` | 在线成员 | 经 `PlayerStorage` 过滤 `isLoggedInWorld()` 的成员（修复向离线成员发包） |
| `Map<Integer, String> getMembers() / List<Entry<Integer, String>> getMemberList()` | 成员表 | getMemberList 把队长置顶 |
| `final boolean isExpeditionTeamTogether()` | 全员同图 | 逐个比对 mapId |
| `final void warpExpeditionTeam(int warpFrom, int warpTo)` / `warpExpeditionTeam(int warpTo)` / `warpExpeditionTeamToMapSpawnPoint(int warpFrom, int warpTo, int toSp)` / `warpExpeditionTeamToMapSpawnPoint(int warpTo, int toSp)` | 团队传送 | 仅在线成员，可按来源图过滤、可指定出生点 |
| `final boolean addChannelExpedition(Channel ch) / final void removeChannelExpedition(Channel ch)` | 频道登记 | 委托 Channel 的 add/removeExpedition |
| `Character getLeader() / MapleMap getRecruitingMap()` | 读取器 | — |
| `boolean contains(Character player) / boolean isLeader(Character) / boolean isLeader(int playerid)` | 成员判定 | contains 含队长 |
| `boolean isRegistering() / boolean isInProgress() / long getStartTime() / List<String> getBossLogs()` | 状态读取 | isInProgress 为 `!registering` |

### ExpeditionBossLog

Boss 每日/每周次数限制的 DB 记账（`server/expeditions/ExpeditionBossLog.java`）。

**内部枚举 `BossLogEntry`**：ZAKUM(2 次)/HORNTAIL(2)/PINKBEAN(1)/SCARGA(1)/PAPULATUS(1)，字段 entries/minChannel/maxChannel/timeLength/week（周表/日表路由）；`getBossLogResetTimestamps(Calendar)` 生成各 Boss 的清理阈值；`getBossEntryByName(String)` 按名查枚举。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `static void resetBossLogTable()` | 周期清理入口 | 对周表（周四 0 点起点）与日表（当日 0 点起点）各执行一次 `resetBossLogTable` |
| `private static Calendar getCycleBoundary(boolean week)` | 计算周期起点 | 日：今天 0 点；周：最近一个已过的周四 0 点（本周四未到回退 7 天） |
| `private static void resetBossLogTable(boolean week, Calendar c)` | 删过期记录 | `DELETE FROM bosslog_weekly/bosslog_daily WHERE attempttime <= 周期起点 AND bosstype = ...`（保留当前周期） |
| `private static int countPlayerEntries(int cid, BossLogEntry boss)` | 数次数 | 按 week 选表 COUNT（SQL 异常返回 -1 视为不可进） |
| `private static void insertPlayerEntry(int cid, BossLogEntry boss)` | 落一次记录 | INSERT (characterid, bosstype) |
| `static boolean attemptBoss(int cid, int channel, Expedition exped, boolean log)` | 尝试进 Boss | `use_enable_daily_expeditions` 关闭恒放行；非 BossLogEntry 类型放行；频道不在 [minChannel, maxChannel] 或次数已满拒绝；`log=true` 时落库（开团时统一登记，报名检查只读） |

---

## 7. loot（2 个类）

**LootInventory**（角色背包快照，`server/loot/LootInventory.java`）：构造 `LootInventory(Character from)` 遍历四类背包把 `itemId → 总数量` 汇总进 Map（容量 50 初始）；`int hasItem(int itemid, int quantity)` 三态返回——0 无、1 有但不足、2 足量。

**LootManager**（掉落相关性过滤，`server/loot/LootManager.java`）：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `private static boolean isRelevantDrop(MonsterDropEntry dropEntry, List<Character> players, List<LootInventory> playersInv)` | 单条掉落是否相关 | 非任务物品恒相关；任务物品：从 `Quest` 取该物品在「未接/进行中」两阶段的需求量，按每个玩家任务状态选需求量，背包已有足量（状态 2）则该玩家不需要；任一玩家仍需要即相关。任务状态非 0 非 1 的玩家跳过（修复无数量任务物品判定） |
| `public static List<MonsterDropEntry> retrieveRelevantDrops(int monsterId, List<Character> players)` | 过滤入口 | `MonsterInformationProvider.retrieveEffectiveDrop` 取原始掉落，为每个玩家建 `LootInventory` 快照后逐条过滤，返回有效掉落表（供击杀者/队伍所有人都不再需要的任务物品不再掉落） |

---

## 8. minigame（1 个类）

### RockPaperScissor

石头剪刀布小游戏会话（NPC 互动，`server/minigame/RockPaperScissor.java`）。

**关键字段**：`round`（已连胜轮数，上限 10）、`ableAnswer`（当前能否出拳）、`win`（当前轮是否获胜）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `RockPaperScissor(final Client c, final byte mode)` | 开局 | 发 rpsMode(9+mode)；mode==0（付费模式）扣 1000 金币 |
| `final boolean answer(final Client c, final int answer)` | 出拳 | 可出拳且未胜且 answer∈[0,2] 时：服务端随机 response——平局仅展示可再出；玩家胜（0>2、1>0、2>1 的克制关系）标记 win 并锁定；负则锁定；不可出拳时直接 `reward` 并返回 false |
| `final boolean timeOut(final Client c)` | 超时 | 仍可作答时发超时包并锁定；否则结算返回 false |
| `final boolean nextRound(final Client c)` | 下一轮 | 胜且 round < 10 时 round+1、复位状态并发下一轮包；到 10 轮封顶后直接结算 |
| `final void reward(final Client c)` | 结算奖励 | 胜场非零发 `RPS_CERTIFICATE_BASE + round` 的奖励券；`setRPS(null)` 解绑会话 |
| `final void dispose(final Client c)` | 退出 | 先结算再发结束包 0x0D |
