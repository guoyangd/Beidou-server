# 10 · 通道处理器（handlers）详细设计 · C 篇

> 系列：《org/gms/net/server/channel/handlers 详细设计》共三篇。handlers 目录按文件名字母序共 **149 个** handler 类，三篇分工如下：
>
> | 篇目 | 文件 | 覆盖范围（字母序） | 类数 |
> |------|------|--------------------|------|
> | A 篇 | `08-handlers-A.md` | #1 `AbstractDealDamageHandler` ～ #50 `GuildOperationHandler` | 50 |
> | B 篇 | `09-handlers-B.md` | #51 `HealOvertimeHandler` ～ #100 `QuestActionHandler` | 50 |
> | **C 篇（本文）** | `10-handlers-C.md` | **#101 `QuickslotKeyMappedModifiedHandler` ～ #149 `WhisperHandler`** | **49** |

## 模块概览

| 项 | 说明 |
|----|------|
| 模块路径 | `gms-server/src/main/java/org/gms/net/server/channel/handlers` |
| 类数量 | 本篇覆盖 49 个（目录总计 149 个） |
| 注册入口 | `org.gms.net.PacketProcessor`（`registerHandler(RecvOpcode.XXX, new XxxHandler())`），本篇不含抽象基类，全部为具体 handler |
| 基类 | 绝大多数继承 `AbstractPacketHandler`；`RangedAttackHandler`、`SummonDamageHandler`、`TouchMonsterDamageHandler` 继承 `AbstractDealDamageHandler`（详见 A 篇 08） |
| 依赖模块 | `net`（`AbstractPacketHandler`/`InPacket`/`PacketProcessor`/`RecvOpcode`）、`client`（`Character`/`Client`/`Skill`/`StatEffect`/`inventory.*`/`processor.*`/`autoban.*`）、`server`（`ItemInformationProvider`/`life.*`/`maps.*`/`quest.*`/`minigame`）、`scripting`（`npc`/`item`/`quest`/`reactor`/`event`）、`config.GameConfig`（热更运营参数）、`service`（`NoteService`、`HpMpAlertService`，经 `PacketProcessor.ChannelDeps` 或 `ServerManager` 注入）、`util`（`PacketCreator`/`util.packets.WeddingPackets`/`DatabaseConnection`/`Randomizer`/`I18nUtil`/`Pair`）、`constants`（`id`/`skills`/`inventory`/`game`） |
| 通用约定 | 多数写操作型 handler 用 `c.tryacquireClient()/releaseClient()` 做客户端级串行化；背包修改用 `Inventory.lockInventory()/unlockInventory()`；道具消耗统一走 `InventoryManipulator`；技能/道具效果统一 `StatEffect.applyTo` |

> **关于 `AbstractDealDamageHandler`（A 篇内容，此处仅列本篇子类用到的两个继承方法）**
>
> | 方法（`protected`） | 作用 |
> |--------------------|------|
> | `AttackInfo parseDamage(InPacket p, Character chr, boolean ranged, boolean magic)` | 解析攻击包（技能 id、等级、命中数、逐怪伤害表 `allDamage`、姿态/朝向、速度等），内置距离/伤害外挂采样 |
> | `void applyAttack(AttackInfo attack, Character player, int attackCount)` | 结算攻击：扣怪血、分经验、触发死亡/掉落、应用怪物状态、Buffs 等 |

---

## QuickslotKeyMappedModifiedHandler

处理 `RecvOpcode.CHANGE_QUICKSLOT`（客户端保存扩展快捷键栏「Q 栏」配置）。

### handlePacket(InPacket, Client) 流程

1. 合法性校验：包剩余字节必须恰为 `QuickslotBinding.QUICKSLOT_SIZE(8) × 4` 字节，且 `c.getPlayer() != null`（已进入游戏），否则直接丢弃。
2. 循环读取 8 个 `int`，逐个截断为 `byte` 存入 `aQuickslotKeyMapped[8]`。
3. 调用 `c.getPlayer().changeQuickslotKeybinding(aQuickslotKeyMapped)` 持久化到角色键位。

### 核心协作类

- `org.gms.client.keybind.QuickslotBinding`（快捷键数据结构，`QUICKSLOT_SIZE = 8`）
- `Character.changeQuickslotKeybinding(byte[])`

---

## RaiseIncExpHandler

处理 `RecvOpcode.USE_ITEMUI`（使用「任务消耗型」道具 UI，如修炼任务中反复上交物品累积进度/经验）。

### handlePacket(InPacket, Client) 流程

1. 读取 `inventorytype`（byte）、`slot`（short）、`itemid`（int）。
2. `c.tryacquireClient()` 串行化；`ItemInformationProvider.getQuestConsumablesInfo(itemid)` 取 `QuestConsItem`（含 `questid`、可消耗物品映射 `items`、`exp`、`grade`），为空即返回。
3. `Quest.getInstanceFromInfoNumber(infoNumber)`；任务状态非 `STARTED` → 发 `enableActions` 返回（防越权刷进度）。
4. 锁对应类型背包：校验槽位物品 id 在 `consumables` 中且 `chr.haveItem(consId)`，然后 `InventoryManipulator.removeFromSlot` 扣除 1 个，解锁。
5. 计算新进度：`nextValue = min(单次贡献 + getQuestProgressInt(questid, infoNumber), consItem.exp × consItem.grade)`，`setQuestProgress` 写回；最后 `enableActions`。

### 核心协作类

- `ItemInformationProvider.QuestConsItem`、`Quest`/`QuestStatus`
- `AbstractPlayerInteraction.getQuestProgressInt/setQuestProgress`
- `InventoryManipulator`、`Inventory.lockInventory`

---

## RaiseUIStateHandler

处理 `RecvOpcode.OPEN_ITEMUI`（客户端打开任务道具 UI 窗口，如 IN74 类修炼界面）。

### handlePacket(InPacket, Client) 流程

1. 读取 `infoNumber`（short）；`tryacquireClient` 串行化。
2. `Quest.getInstanceFromInfoNumber` 取任务，读角色 `QuestStatus`。
3. `QuestScriptManager.getInstance().raiseOpen(c, infoNumber, npc)` 触发对应任务脚本回调。
4. 状态 `NOT_STARTED` → `quest.forceStart(chr, 22000)` 强制开始并把进度归零（`setQuestProgress(questId, infoNumber, 0)`）；
   状态 `STARTED` → `chr.announceUpdateQuest(DelayedQuestUpdate.UPDATE, mqs, infoNumber > 0)` 刷新客户端任务 UI。

### 核心协作类

- `QuestScriptManager.raiseOpen`、`Quest.forceStart`
- `constants.game.DelayedQuestUpdate`、`Character.announceUpdateQuest`

---

## RangedAttackHandler

处理 `RecvOpcode.RANGED_ATTACK`（远程攻击：弓/弩/标飞/枪手等）。继承 `AbstractDealDamageHandler`，是本篇最复杂的攻击入口之一。

### handlePacket(InPacket, Client) 流程

1. `parseDamage(p, chr, true, false)` 解析攻击数据。
2. 变身 Morph 检查：若当前 Morph 变身标记为「不可攻击」，客户端不可能发此包 → 直接 `disconnect`（防封包伪造）。
3. 道场命中加能量：`GameConfig.getServerInt("dojo_energy_atk")`，发包 `getEnergy("energy", ...)`。
4. **特判技能组**（不消耗子弹，直接广播 + 结算）：
   - `Buccaneer.ENERGY_ORB`/`ThunderBreaker.SPARK`/`Shadower.TAUNT`/`NightLord.TAUNT`：广播 `rangedAttack`，`applyAttack(attack, chr, 1)`。
   - `ThunderBreaker.SHARK_WAVE`（已学）：同上，且对每个被打目标 `handleEnergyChargeGain()`。
   - `Aran.COMBO_SMASH/FENRIR/TEMPEST`：要求 combo ≥ 30/100/200，达标则 `setCombo(0)` 并 `applyAttack` 1/2/4 段。
5. **通用路径**：
   1. 取已装备武器（`EQUIPPED` 槽位 -11），`ItemInformationProvider.getWeaponType` 非 `NOT_A_WEAPON` 才继续。
   2. 若带技能：`effect = attack.getAttackEffect(chr, null)`，`bulletCount = effect.getBulletCount()`，冷却中发 `skillCooldown` 预告；技能 `4111004`（Shadow Meso 钱镖）`bulletCount = 0` 并按 `moneyCon ± 随机一半`（封顶当前 meso）扣金币。
   3. `SHADOWPARTNER` 影分身 buff 存在 → `bulletCount *= 2`。
   4. 扫描 `USE` 背包找投射物：拳套（`MAGICAL_MITTEN` 魔法手套除外）配飞镖（`HWABI`/`BALANCED_FURY` 需等级 ≥70、`CRYSTAL_ILBI` ≥50）；枪配子弹（`BLAZE/GLAZE_CAPSULE` 需 ≥70 级，其余按 `id%10×20+9` 等级门槛）；弓配弓箭/弩配弩箭/魔法手套两者皆可，且数量 ≥ `bulletCount`（修复「最后一发箭不能用」）。**BeiDou 扩展**：`BALANCED_FURY` 余量 ≤10 时自动补充——`supplement = -slotMax`，后续把消耗量改为负数实现「补满」而非扣除。
   5. `SOULARROW`（灵魂之箭）/`SHADOW_CLAW` 或技能 `11101004/15111007/14101006` 免耗投射物，否则按 `effect.getBulletConsume()`（影分身×2；`supplement<0` 时改为补充量）从 `USE` 槽 `removeFromSlot` 扣除。
   6. 计算可视投射物 `visProjectile`：飞镖时若 CASH 背包有 `5021xxxx` 系列覆盖外观则用其显示；灵魂箭或免耗技能组置 0。
   7. 构造广播包：`Hurricane(3121004)/Pierce(3221001)/RapidFire(5221004)/KoC Hurricane(13111002)` 用 `attack.rangedirection`，其余用 `attack.stance`；`map.broadcastMessage(chr, packet, false, true)`。
   8. 冷却：`effect_.getCooldown() > 0` 且 `skillIsCooling` → 返回；否则 `addCooldown` 并发包。
   9. 攻击破隐：夜行者 `VANISH`+`DARKSIGHT`（非 9101004 来源）或风行者 `WIND_WALK` 在命中目标时取消对应 buff。
   10. `applyAttack(attack, chr, bulletCount)` 结算伤害。

### 核心协作类

- `AbstractDealDamageHandler.parseDamage/applyAttack`（伤害结算与反外挂）
- `ItemConstants`（箭/镖/子弹判定）、`ItemId`/`constants.skills.*`（技能常量）
- `InventoryManipulator.removeFromSlot`、`StatEffect`、`BuffStat`
- `GameConfig`（`dojo_energy_atk`）、`PacketCreator.rangedAttack/skillCooldown/getEnergy`

---

## ReactorHitHandler

处理 `RecvOpcode.DAMAGE_REACTOR`（玩家攻击/砸击反应堆）。

### handlePacket(InPacket, Client) 流程

1. 依次读取 `oid`（反应堆对象 id）、`charPos`（玩家相对站位）、`stance`（姿态）、跳过 4 字节、`skillid`。
2. `map.getReactorByOid(oid)` 定位反应堆，非空则 `reactor.hitReactor(true, charPos, stance, skillid, c)` 触发状态推进（内部完成脚本调度与广播）。

### 核心协作类

- `server.maps.Reactor.hitReactor`

---

## RemoteGachaponHandler

处理 `RecvOpcode.USE_REMOTE`（使用「远程扭蛋机」券，无需到 NPC 处直接抽奖）。

### handlePacket(InPacket, Client) 流程

1. 读取 `ticket`、`gacha`（扭蛋机编号 0–11）。
2. 三重防伪造：券 id 必须为 `ItemId.REMOTE_GACHAPON_TICKET`、`gacha ∈ [0,11]`、对应背包 `countById ≥ 1`；任一失败 → `AutobanFactory.GENERAL.alert` + 断开连接。
3. 映射 NPC：默认 `NpcId.GACHAPON_HENESYS + gacha`；8 → `GACHAPON_NLC`，9 → `GACHAPON_NAUTILUS`。
4. `NPCScriptManager.start(c, npcId, "gachaponRemote", null)` 启动远程扭蛋脚本。

### 核心协作类

- `AutobanFactory.GENERAL`、`NPCScriptManager.start`

---

## RemoteStoreHandler

处理 `RecvOpcode.REMOTE_STORE`（玩家请求远程管理自己已开的雇佣商店，如换线后取回）。

### handlePacket(InPacket, Client) 流程

1. `getMerchant(c)`：玩家 `hasMerchant()` 时从 `c.getWorldServer().getHiredMerchant(playerId)` 取商店实例，否则 null。
2. 存在且 `hm.isOwner(chr)`：
   - 商店在当前频道 → `hm.visitShop(chr)` 进入；
   - 否则发 `PacketCreator.remoteChannelChange(hm.getChannel() - 1)` 提示玩家切频道。
3. 无商店 → `dropMessage(1, "You don't have a Merchant open.")`；最终补发 `enableActions`。

### 核心协作类

- `server.maps.HiredMerchant`（`isOwner/visitShop/getChannel`）
- `World.getHiredMerchant`

---

## ReportHandler

处理 `RecvOpcode.REPORT`（玩家举报）。

### handlePacket(InPacket, Client) 流程

1. 读取 `type`（0=外挂举报 / 1=对话举报）、`victim`（被举报人名）、`reason`、`description`。
2. `type == 0`：`getPossibleReports() > 0` 且 meso > 299 → `decreaseReports()` + `gainMeso(-300)`（举报收费 300 金币）；次数不足回 `reportResponse(2)`，钱不够回 `reportResponse(4)`。随后向全服 GM 广播 `serverNotice(6, ...)`，`addReport` 落库。
3. `type == 1`：额外读取 `chatlog`（null 直接返回），其余同上，`addReport` 携带聊天记录。
4. 其他 type：广播 GM 提示「疑似改包」。
5. 私有 `addReport(reporterid, victimid, reason, description, chatlog)`：直接 JDBC `INSERT INTO reports`（`DatabaseConnection`），SQL 异常仅打印堆栈。

### 核心协作类

- `Character.getPossibleReports/decreaseReports/gainMeso`、`Character.getIdByName`
- `Server.broadcastGMMessage`、`DatabaseConnection`

---

## RingActionHandler

处理 `RecvOpcode.RING_ACTION`（婚恋系统全套动作：求婚/应答/退婚/请柬/愿望单）。构造器注入 `NoteService`（Spring 依赖，经 `PacketProcessor` 传入）。

### 公有静态方法（供其他模块复用，如 NPC 脚本/道具处理器）

| 方法 | 说明 |
|------|------|
| `void sendEngageProposal(Client c, String name, int itemid)` | 发起求婚：约 15 项校验（双方 ≥50 级、异性、同地图、双方未婚未订婚、无婚戒、持有有效订婚盒 `ENGAGEMENT_BOX_MIN..MAX`、双方 ETC 各有空位等），全过后 `source.setMarriageItemId(itemid)` 并给对方发 `onMarriageRequest` 弹窗；任何失败 `dropMessage(1, 原因)` + `OnMarriageResult(0)` |
| `void breakMarriageRing(Character chr, int wItemId)` | 对「婚戒（id/10==111280）」执行 `breakMarriage`、对「订婚凭证（ETC 403xxxx）」执行 `breakEngagement`，随后把该物品以消失动画丢在地上（`disappearingItemDrop`） |
| `void giveMarriageRings(Character player, Character partner, int marriageRingId)` | 结婚仪式完成后发婚戒：`Ring.createRing` 建库记录 → 生成两枚带 `ringId` 的 Equip 分别入包 → `setMarriageRing(Ring.loadFromDb)` → `broadcastMarriageMessage` → 刷新「优秀市民勋章」资格（`OutstandingCitizenMedal.refreshEligibility`） |

私有静态：`getEngagementBoxId`（订婚盒→空盒 id 映射）、`eraseEngagementOffline`/`breakEngagementOffline`（对离线方直接 SQL 重置 `marriageItemId/partnerId` 并使订婚盒过期）、`breakMarriage`（`World.deleteRelationship` + `Ring.removeRing` + 双方在线/离线两种清理路径 + 勋章资格刷新）、`breakEngagement`（清订婚关系并收回双方 ETC 盒子）、`resetRingId`（把身上/背包中婚戒的 ringId 置 -1）。

### handlePacket(InPacket, Client) 流程（按 mode 分派）

1. **mode 0 发起求婚**：`sendEngageProposal(c, readString(), readInt())`。
2. **mode 1 取消求婚**：若 `marriageItemId / 1000000 != 4`（不是 ETC 订婚盒）则重置为 -1。
3. **mode 2 应答求婚**：读 `accepted`、求婚者名、id。定位 `source`，校验（存在、id 匹配、`marriageItemId` 有效且持有、双方存活、双方未订婚）失败即 `enableActions`。接受：扣求婚者 USE 盒子 → `World.createRelationship` → 双方互设 `partnerId`、`marriageItemId = newBoxId / newBoxId + 1` → 双方 ETC 各加入对应空盒 → 双方收 `OnMarriageResult(marriageId, ...)` + `OnNotifyWeddingPartnerTransfer`。拒绝：给求婚者提示并重置其 `marriageItemId`。
4. **mode 3 解除婚约/离婚**：`breakMarriageRing(player, readInt())`。
5. **mode 5 邀请婚礼宾客**：读受邀人名、`marriageId`、请柬所在 ETC 槽位。校验持有 `INVITATION_CHAPEL/CATHEDRAL`；解析新郎/新娘/宾客 id；`wserv.getMarriageQueuedLocation(marriageId)` 确认预约存在 → `addMarriageGuest` 去重 → 频道 `getWeddingReservationStatus` → 取得门票过期时间。宾客在线且有位 → 直接发「收到的请柬」道具 + 提示；否则经 Duey 邮寄（`DueyProcessor.dueyCreatePackage`），离线再补 `noteService.sendNormal` 小纸条。最后 `gainItem(itemId, -1)` 消耗请柬。
6. **mode 6 打开请柬**：读 ETC 槽位与请柬 id（必须是「收到的请柬」两种之一且槽内物品一致）；`getWeddingCoupleForGuest` 取最近一场婚礼 → `sendWeddingInvitation(新郎名, 新娘名)`。
7. **mode 9 心愿单（Wishlist）**：在婚礼 EIM 中且是新人 → 读取 ≤10 行文本，写入 EIM 属性 `groomWishlist`/`brideWishlist`（仅首次为空时写入）。
8. 未知 mode 记 warn 日志；出口统一 `enableActions`。

### 核心协作类

- `client.Ring`（`createRing/removeRing/loadFromDb`）、`World`（`createRelationship/deleteRelationship/getMarriageQueuedLocation/addMarriageGuest/getWeddingCoupleForGuest`）
- `Channel.getWeddingReservationStatus/getWeddingTicketExpireTime`
- `InventoryManipulator`、`DueyProcessor`、`NoteService`、`WeddingPackets`、`OutstandingCitizenMedal`

---

## RPSActionHandler

处理 `RecvOpcode.RPS_ACTION`（NPC 猜拳小游戏「剪刀石头布」）。

### handlePacket(InPacket, Client) 流程

1. `tryacquireClient` 串行化；包为空或当前地图无 `NpcId.RPS_ADMIN` NPC → 若已有对局则 `rps.dispose(c)` 后返回。
2. 读 `mode`：
   - **0 开局 / 5 重试**：先 `rps.reward(c)` 结算上一局奖励；meso ≥ 1000 → `chr.setRPS(new RockPaperScissor(c, mode))` 扣费开局，否则 `rpsMesoError(-1)`。
   - **1 出拳**：`rps.answer(c, readByte())`，失败回 `rpsMode(0x0D)`。
   - **2 超时**：`rps.timeOut(c)`，失败同上。
   - **3 继续**：`rps.nextRound(c)`，失败同上。
   - **4 离开**：`rps.dispose(c)`（无对局回 `rpsMode(0x0D)`）。

### 核心协作类

- `server.minigame.RockPaperScissor`（对局状态机：`answer/timeOut/nextRound/dispose/reward`）

---

## ScriptedItemHandler

处理 `RecvOpcode.SCRIPTED_ITEM`（使用「脚本道具」，双击触发 item 脚本，如魔法箱/任务道具）。

### handlePacket(InPacket, Client) 流程

1. 跳过 4 字节 stamp；读 `itemSlot`（short）、`itemId`（int）。
2. `ItemInformationProvider.getScriptedItemInfo(itemId)` 取 `ScriptedItem`，null 返回。
3. 校验对应类型背包槽位物品存在且 id/数量一致。
4. `ItemScriptManager.getInstance().runItemScript(c, info)` 启动 `scripts/item/*.js`。

### 核心协作类

- `scripting.item.ItemScriptManager.runItemScript`、`ItemInformationProvider.ScriptedItem`

---

## ScrollHandler

处理 `RecvOpcode.USE_UPGRADE_SCROLL`（对装备使用卷轴砸卷，含白卷/祝福卷/匠人之魂）。

### handlePacket(InPacket, Client) 流程

1. `tryacquireClient` 串行化；读 stamp（int，弃）、`scrollSlot`、`equipSlot`、`ws` 标志（`(ws & 2) == 2` → 使用白色卷轴）。
2. 定位目标装备：默认 `EQUIPPED` 背包（槽位为负）；若已学传奇精神（技能 1003）且 `equipSlot >= 0` → 视为匠人之魂，从 `EQUIP` 背包取。
3. 预校验（不满足走 `announceCannotScroll`）：
   - 清洁卷轴（Clean Slate）需 `ii.canUseCleanSlate(toScroll)`；
   - 非修饰类卷轴需 `upgradeSlots >= 1`；
   - `ii.getScrollReqs(scrollId)` 非空时必须包含目标装备 id；
   - 白卷要在 `USE` 背包真实存在（否则降级为不用白卷）；
   - 非 Chaos/CleanSlate 卷需通过 `canScroll`（卷轴类别码 `scrollid/100 % 100 == itemid/10000 % 100`，饰品卷 20492 递归兼容戒指/腰带/龙魂石）。
4. `ii.scrollEquipWithId(toScroll, scrollId, whiteScroll, 0, chr.isGM())` 掷卷：
   - 返回 null → `CURSE`（装备被诅咒摧毁）；
   - 升级档位提升 / CleanSlate 恢复一格 / Flag 修饰类生效 → `SUCCESS`；
   - 否则 `FAIL`。
5. 锁 `USE` 背包：确认卷轴数量 ≥1（白卷同理），白卷（非 CleanSlate 场景）与卷轴各扣 1 个，解锁。
6. 结果处理：
   - `CURSE`：普通装备 → 从 `EQUIPPED`（先 `unequippedItem`）或 `EQUIP` 移除；**婚戒例外**（`ItemId.isWeddingRing`）→ 视为 `FAIL`，装备移除后原样加回，避免销毁婚戒数据。
   - 非 CURSE：构造 `ModifyInventory(3, 移除) + (0, 加回)` 刷新装备。
7. 发 `modifyInventory`、全图广播 `getScrollEffect(chrId, result, legendarySpirit, whiteScroll)`；已装备槽位（`equipSlot < 0`）且 SUCCESS/CURSE → `chr.equipChanged()` 重算属性。
8. 私有 `announceCannotScroll`：发 `getInventoryFull`；匠人之魂场景额外补发 `getScrollEffect(..., FAIL, true, false)` + 中文提示「砸卷失败不消耗卷轴」——**BeiDou 修复**：原版漏发 Inventory 封包导致客户端背包锁死假死，注释详细记录了排查结论。

### 核心协作类

- `ItemInformationProvider.scrollEquipWithId/getScrollReqs/canUseCleanSlate/rollSuccessChance`（概率掷点在 ii 内）
- `Equip.ScrollResult`、`ModifyInventory`、`InventoryManipulator`
- `ItemConstants`（isChaosScroll/isCleanSlate/isModifierScroll/isFlagModifier）

---

## SetHpMpAlertHandler

处理 `RecvOpcode.SET_HPMPALERT`（客户端上报 HP/MP 自动吃药警戒阈值挡位；服务端据此驱动服务端代喝药）。

### handlePacket(InPacket, Client) 流程

1. 从 Spring 容器取 `HpMpAlertService`（`ServerManager.getApplicationContext().getBean`）。
2. `chr == null` 直接返回。
3. 读 HP、MP 两个挡位字节，按无符号解析并限幅到 `[0, 19]`（20 挡，服务端换算比例上限 95%），异常值不影响自动吃药逻辑。
4. `setHpAlert(chr.getId(), hpStep)`、`setMpAlert(chr.getId(), mpStep)` 持久化阈值。

### 核心协作类

- `org.gms.service.HpMpAlertService`（Spring 服务）
- `org.gms.manager.ServerManager`（遗留代码反向获取 Spring bean 的桥）

---

## SkillBookHandler

处理 `RecvOpcode.USE_SKILL_BOOK`（使用技能书提升技能上限等级，如 4 转技能书）。

### handlePacket(InPacket, Client) 流程

1. 角色死亡 → `enableActions` 返回。
2. 读 stamp、`slot`、`itemId`；`tryacquireClient` 串行化。
3. `USE` 槽位物品 id 匹配；`ii.getSkillStats(itemId, job.getId())` 取技能书数据（`skillid/reqSkillLevel/masterLevel/success`），null 返回。
4. `skillid != 0` 且（当前技能等级 ≥ 前置等级或无前置）且当前 `masterLevel < 技能书 masterLevel`：
   1. 锁 `USE` 背包，复核槽内物品未被并发移动且数量 ≥1（修复堆叠技能书不可用的问题），扣 1 本，解锁。
   2. `canuse = true`；`ItemInformationProvider.rollSuccessChance(success)` 掷点，成功 → `changeSkillLevel` 把 master level 抬到 `max(书值, 当前值)`（`success = true`），失败仅 `success = false`。
5. 全图广播 `skillBookResult(player, skill, maxlevel, canuse, success)`（让周围玩家也看到结果动画）。

### 核心协作类

- `ItemInformationProvider.getSkillStats/rollSuccessChance`、`SkillFactory.getSkill`
- `Character.changeSkillLevel`、`InventoryManipulator`

---

## SkillEffectHandler

处理 `RecvOpcode.SKILL_EFFECT`（广播「持续施法/蓄力类」技能的起手特效，如聚气炮、飓风等）。

### handlePacket(InPacket, Client) 流程

1. 读取 `skillId`、`level`、`flags`、`speed`、`aids`。
2. 白名单校验（`EXPLOSION`、`BIG_BANG`×3、`HURRICANE`、`PIERCING_ARROW`、`CHAKRA`、`CORKSCREW_BLOW`×2、`GRENADE`、`RAPID_FIRE`、`POISON_BOMB`、`MONSTER_MAGNET`×3、`FIRE/ICE_BREATH`）：命中 → `map.broadcastMessage(chr, skillEffect(...), false)` 广播给他人；未命中 → warn 日志并返回（防任意技能特效刷屏）。

### 核心协作类

- `PacketCreator.skillEffect`、`constants.skills.*` 常量

---

## SkillMacroHandler

处理 `RecvOpcode.SKILL_MACRO`（保存技能宏设置）。

### handlePacket(InPacket, Client) 流程

1. 读宏数量 `num`，>5 直接丢弃。
2. 逐个读取：宏名（长度 >12 → `AutobanFactory.PACKET_EDIT.alert` + 断线）、`shout`、三个技能 id。
3. 组装 `SkillMacro(skill1, skill2, skill3, name, shout, i)` 并 `chr.updateMacros(i, macro)` 持久化。

### 核心协作类

- `client.SkillMacro`、`Character.updateMacros`、`AutobanFactory.PACKET_EDIT`

---

## SnowballHandler

处理 `RecvOpcode.SNOWBALL`（雪球赛活动：攻击雪球/雪人）。

### handlePacket(InPacket, Client) 流程

1. 取本队与对方队的 `Snowball` 对象；读动作 `what`。
2. 校验：任一雪球为 null 或本队雪人 HP 为 0 → 返回；距 `getLastSnowballAttack()` 不足 500ms → 返回（防刷）；`what % 2` 必须等于本队编号。
3. 记录攻击时间；计算伤害：`what < 2` 且对方雪人存活 → 10；`what == 2/3` → 3% 概率 45 否则 15。
4. `what ∈ [0,4]` → `snowball.hit(what, damage)` 推进雪球位置/雪人 HP。

### 核心协作类

- `server.events.gm.Snowball.hit/getSnowmanHP`、`Character.getTeam/getLastSnowballAttack`

---

## SpawnPetHandler

处理 `RecvOpcode.SPAWN_PET`（召唤/切换宠物）。

### handlePacket(InPacket, Client) 流程

1. 读 stamp（int，弃）、CASH 背包槽位 `slot`（byte）、1 字节（弃）、`lead`（是否主宠物）。
2. 全部逻辑委托 `SpawnPetProcessor.processSpawnPet(c, slot, lead)`（过期检查、饥饿度、buff 冲突等在处理器内）。

### 核心协作类

- `client.processor.action.SpawnPetProcessor.processSpawnPet`

---

## SpecialMoveHandler

处理 `RecvOpcode.SPECIAL_MOVE`（释放非攻击类主动技能：buff、瞬移、召唤、门等）。

### handlePacket(InPacket, Client) 流程

1. 读 stamp 并写入 autoban 时间戳采样（`setTimestamp(4, serverTimestamp, 28)`）；读 `skillid`、客户端声明的 `__skillLevel`。
2. 道场技能（`skillid % 10000000 ∈ {1010, 1011}`）：道场能量 < 10000 视为改包/延迟，直接返回；否则能量清零、发包并提示。
3. 服务器侧等级校验：`chr.getSkillLevel(skill) == 0` 或与客户端声明不符 → 返回。
4. 冷却：`effect.getCooldown() > 0` 且未在冷却 → 发 `skillCooldown` 并 `addCooldown`；`Corsair.BATTLE_SHIP` 例外（船体 HP 机制自管理）；勇士意志在 `use_fast_reuse_hero_will` 配置开启时冷却 ÷60。
5. 技能特判：
   - **怪物磁铁**（`Hero/Paladin/DarkKnight.MONSTER_MAGNET`）：逐个读取怪物 oid + 成功标记，广播 `catchMonster`；对非 Boss 清仇恨后 `aggroMonsterDamage(chr, 1)` + `aggroSwitchController(chr, true)` 把怪拉向自己；最后按方向广播 `showBuffEffect` 并 `enableActions` 返回。
   - **MP Recovery**（`Brawler.MP_RECOVERY`，生命分流）：`safeAddHP(-maxHp/X)` 扣血，回蓝 `-lose × Y/100`（**BeiDou 修复**：用 `double` 运算修掉整数截断导致 MP 不加的问题）。
   - **`SuperGM.HEAL_PLUS_DISPEL`**：跳 11 字节后广播 buff 特效。
   - `skillid % 10000000 == 1004`：额外读一个 short（宠物类技能字段）。
6. 若剩余 5 字节则读取施法落点 `pos`（`Point(x, y)`）。
7. 存活时统一生效：
   - `Priest.MYSTIC_DOOR`（时空门）：`tryacquireClient` 内检查 `chr.canDoor()`（5 秒节流）→ 先 `cancelMagicDoor` 再 `applyTo(chr, pos)`；冷却中给提示；出口补 `enableActions`。
   - `skillid % 10000000 == 1005`（Echo of Hero 英雄回响）→ `applyEchoOfHero(chr)`。
   - 其余 → `skill.getEffect(skillLevel).applyTo(chr, pos)`（buff 注册/MP 消耗/状态广播都在 `StatEffect` 内）。
8. 死亡 → 仅 `enableActions`。

### 核心协作类

- `SkillFactory.getSkill`、`StatEffect.applyTo/applyEchoOfHero/isHerosWill`
- `Monster.aggroClearDamages/aggroMonsterDamage/aggroSwitchController`
- `Character.addCooldown/skillIsCooling/canDoor/cancelMagicDoor/safeAddHP`
- `GameConfig`（`use_fast_reuse_hero_will`）、`Server.getInstance().getCurrentTimestamp`

---

## SpouseChatHandler

处理 `RecvOpcode.SPOUSE_CHAT`（夫妻频道私聊）。

### handlePacket(InPacket, Client) 流程

1. 读收件人名（服务端忽略，以 `partnerId` 为准）与消息文本。
2. `partnerId > 0`：配偶在线 → 双方各收 `OnCoupleMessage(自己名, msg, true)` 并 `ChatLogger.log(c, "Spouse", msg)`；离线 → 提示「配偶当前不在线」。
3. 未婚 → 提示「你没有配偶」。

### 核心协作类

- `World.getPlayerStorage().getCharacterById`、`server.ChatLogger`、`PacketCreator.OnCoupleMessage`

---

## StorageHandler

处理 `RecvOpcode.STORAGE`（仓库/银行全部操作：存取、整理、扩展等）。

### handlePacket(InPacket, Client) 流程

1. 整体委托 `StorageProcessor.storageAction(p, c)` 静态方法（存/取/移动/金币/升级格子等按子操作分派）。

### 核心协作类

- `client.processor.npc.StorageProcessor.storageAction`

---

## SummonDamageHandler

处理 `RecvOpcode.SUMMON_ATTACK`（召唤兽攻击结算，如海盗海鸥、弩召唤等）。继承 `AbstractDealDamageHandler`。含公共内部类 `SummonAttackEntry`（`getMonsterOid()`/`getDamage()`，构造器 `(int monsterOid, int damage)`）。

### handlePacket(InPacket, Client) 流程

1. 读召唤物 `oid`；角色死亡返回；在 `player.getSummonsValues()` 中按 oid 匹配召唤物，找不到返回。
2. 由 `summon.getSkill()/getSkillLevel()` 取 `StatEffect`；跳 4 字节，读 `direction`、`numAttacked`、跳 8 字节（双方坐标）。
3. 逐目标读取：怪物 oid + 跳 18 字节 + 伤害值，组装 `List<SummonAttackEntry>`。
4. 以召唤物坐标为中心广播 `summonAttack`；`map.isOwnershipRestricted(player)` 则到此为止。
5. 伤害上限校验：`magic = summonEffect.getWatk() == 0`；`calcMaxDamage`——魔法系 `calculateMaxBaseMagicDamage(max(总魔攻,14)) × 0.05 × 效果 Matk`；物理系 `calculateMaxBaseDamage(max(总物攻,14), 武器类型)` ×（基础伤害 ≥438 取 0.054 否则 0.077）× 效果 Watk。超限 → `AutobanFactory.DAMAGE_HACK.alert` + 日志并**钳制到上限**继续结算。
6. 伤害 >0 且技能带怪物状态 → `makeChanceResult()` 掷点后 `applyStatus`（`MonsterStatusEffect`，持续 4000ms，毒属性按 `isPoison`）。
7. `map.damageMonster(player, target, damage)` 结算；若为 `Outlaw.GAVIOTA`（海鸥掷雷后消散）→ `cancelEffect(summonEffect, false, -1)`。

### 核心协作类

- `server.maps.Summon`、`Monster.applyStatus`、`MapleMap.damageMonster/isOwnershipRestricted`
- `Character.calculateMaxBaseMagicDamage/calculateMaxBaseDamage`、`AutobanFactory.DAMAGE_HACK`

---

## TakeDamageHandler

处理 `RecvOpcode.TAKE_DAMAGE`（玩家受到伤害的完整结算，是防御体系核心）。未继承 `AbstractDealDamageHandler`（不产生己方攻击）。

### handlePacket(InPacket, Client) 流程

1. 预备：`banishPlayers` 列表（流放目标）；读 stamp（弃）、伤害来源 `damagefrom`、元素（弃）、`damage`。
2. **怪物来源解析**（`damagefrom != -3/-4`，-3/-4 为地图/诅咒类无实体伤害）：读 `monsteridfrom`、`oid`，`map.getMapObject(oid)` 转 `Monster`（id 不符置 null；`ClassCastException` 时记 warn 返回——切换地图瞬间的竞态）。
   - 怪物带 `NEUTRALISE`（身体压力压制）→ 玩家免伤返回。
   - **loseItem 掉物**（怪物 stat 配置 `loseItem()`，如月秒小偷）：有伤害且玩家无 `AURA` buff 时，按 `chance` 掷点数量从玩家背包扣物品（锁背包），在玩家左右 `25px` 队列散落掉落（`spawnItemDrop`）；`4031868`（阿里安竞技场分数道具）额外 `updateAriantScore`；随后 `removeMapObject(attacker)` 让小偷消失。
   - attacker 为 null 时：仅当 `damagefrom == 0` 且 `map.removeSelfDestructive(oid)`（自爆怪）成立才继续，否则返回。
   - 读 `direction`；若包还有 ≥2 字节：解析 `reflect/guardingData`，>0 时读反击八件套（PowerGuard oid、命中动作、双方坐标）；`guardingData > 1` 且攻击者非 Boss → 反击stance昏迷：按职业取 `1220006`（圣骑士 ACB）/`1120005`（英雄 ACB）技能，对怪施加 `STUN` 状态（时长取技能 duration，无效果时 2000ms 兜底）。
3. **怪物攻击信息结算**（`damagefrom != -1/-2` 且 attacker 非空）：
   - `MobAttackInfoFactory.getMobAttackInfo(attacker, damagefrom)`：
     - `isDeadlyAttack` → `mpattack = mp - 1`、`is_deadly = true`（致命攻击削 MP 到 1）；
     - 累加 `getMpBurn()`；
     - 疾病技能（`diseaseSkill/diseaseLevel`）在伤害 >0 时 `MobSkill.applyEffect(chr, attacker, false, banishPlayers)`（可能往 `banishPlayers` 塞人流放）；
     - 扣怪物 MP（`getMpCon`）。
   - **ManaReflection**（魔力反射，大法师 212/222/232）：buff 来源匹配 `jobid*10000+1002` 且掷点成功 → 反弹 `damage × X%`（封顶怪 maxHp/5），`map.damageMonster` + 广播 + 双端 buff 特效。
4. `damage == -1`（MISS/Fake）→ `fake = 4020002 + (job/10 - 40) × 100000`（幻影祖 Fake 技能 id 推导）。
5. Autoban 采样：伤害 >0 `resetMisses()`，否则 `addMiss()`。
6. 道场内致命攻击按 `use_deadly_dojo` 配置豁免（清零 damage/mpattack）。
7. **减伤与伤害分配**（`damage > 0` 且非隐身）：
   - `damagefrom == -1`（碰撞）时：`POWERGUARD` 反弹 `damage × 比例`（Boss 减半，封顶 maxHp/10），扣减本体伤害并给怪加仇恨；`BODY_PRESSURE`（Aran）对非 Boss 掷点施加 `NEUTRALISE`。
   - `COMBO_BARRIER` → `damage ×= X/1000`。
   - （-3/-4 之外）`Achilles`（战士四转减伤，按 job 推导技能）与 `Aran.HIGH_DEFENSE` 各自 `×= X/1000`。
   - 分配：`MAGIC_GUARD`（魔法盾）→ 按 MP 比例拆分 HP/MP 损失（MP 不足部分转 HP）；`MESOGUARD`（金币护盾）→ 伤害减半 + 按比例扣金币（金币不足则取消 buff）；其余 → 战船状态先 `decreaseBattleshipHp(damage)`，最后 `addMPHP(-damage, -mpattack)`。
8. 广播受击：非隐身 `map.broadcastMessage(damagePlayer(...))`；隐身只发 GM。
9. 道场受伤回能量（`dojo_energy_dmg` 配置）。
10. `banishPlayers` 逐个 `changeMapBanish(banish.map, portal, msg)`（怪物流放技能）。

### 核心协作类

- `MobAttackInfoFactory`/`MobAttackInfo`、`MobSkillFactory`/`MobSkill.applyEffect`
- `Monster.applyStatus/aggroMonsterDamage`、`LifeFactory.loseItem`
- `BuffStat`（POWERGUARD/MAGIC_GUARD/MESOGUARD/AURA/BODY_PRESSURE/COMBO_BARRIER/MANA_REFLECTION…）
- `MapleMap.spawnItemDrop/removeSelfDestructive/damageMonster/calcDropPos`
- `AutoBanManager.resetMisses/addMiss`、`GameConfig`、`MapId.isDojo`

---

## TouchingCashShopHandler

处理 `RecvOpcode.CHECK_CASH`（客户端进入/刷新点券界面时核对现金余额）。

### handlePacket(InPacket, Client) 流程

1. 直接回发 `PacketCreator.showCash(c.getPlayer())`（Nexon 现金/枫点余额）。

### 核心协作类

- `PacketCreator.showCash`

---

## TouchMonsterDamageHandler

处理 `RecvOpcode.TOUCH_MONSTER_ATTACK`（贴身触怪伤害：能量满载的空手/身体压力攻击）。继承 `AbstractDealDamageHandler`。

### handlePacket(InPacket, Client) 流程

1. 条件：`chr.getEnergyBar() == 15000`（能量已满）或持有 `BODY_PRESSURE` buff，才认可本次贴身伤害。
2. `applyAttack(parseDamage(p, chr, false, false), c.getPlayer(), 1)` 复用统一结算。

### 核心协作类

- `AbstractDealDamageHandler.parseDamage/applyAttack`、`BuffStat.BODY_PRESSURE`

---

## TouchReactorHandler

处理 `RecvOpcode.TOUCHING_REACTOR`（触碰型反应堆，走上/离开触发脚本）。

### handlePacket(InPacket, Client) 流程

1. 读 `oid`，`map.getReactorByOid(oid)`，null 返回。
2. 触碰标志非 0 → `ReactorScriptManager.getInstance().touch(c, reactor)`；为 0 → `untouch(c, reactor)`。

### 核心协作类

- `scripting.reactor.ReactorScriptManager.touch/untouch`

---

## TransferNameHandler

处理 `RecvOpcode.NAME_TRANSFER`（商城申请使用改名卡前的规则校验）。

### handlePacket(InPacket, Client) 流程

1. 读 cid（弃）与生日；`CashOperationHandler.checkBirthday` 校验失败 → `showCashShopMessage(0xC4)` + `enableActions`。
2. `GameConfig` 开关 `allow_cash_shop_name_change` 关闭 → `sendNameTransferRules(4)`。
3. 等级 <10 → 规则码 4；距最近临时封禁不足 30 天（`getTempBanCalendar + 30d > now` 判定仍在封禁窗口）→ 规则码 2。
4. JDBC 查 `namechanges` 表：存在 `completionTime == null` 的记录（有待处理申请）→ 规则码 1；距上次完成不足 `name_change_cooldown`（毫秒，GameConfig）→ 规则码 3。
5. 全部通过 → `sendNameTransferRules(0)`。

### 核心协作类

- `CashOperationHandler.checkBirthday`（静态复用）、`GameConfig`、`DatabaseConnection`

---

## TransferNameResultHandler

处理 `RecvOpcode.CHECK_CHAR_NAME`（改名界面实时校验新名字可用性）。

### handlePacket(InPacket, Client) 流程

1. 读 `name`；回发 `sendNameTransferCheck(name, Character.canCreateChar(name))`。

### 核心协作类

- `Character.canCreateChar`（静态：重名/非法字符/保留词校验）

---

## TransferWorldHandler

处理 `RecvOpcode.WORLD_TRANSFER`（申请跨世界转移前的规则校验），结构与 `TransferNameHandler` 对称。

### handlePacket(InPacket, Client) 流程

1. 生日校验（同上，失败 0xC4 + enableActions）。
2. `allow_cash_shop_world_transfer` 关闭或全服仅 1 个世界 → `sendWorldTransferRules(9, c)`。
3. `chr.checkWorldTransferEligibility()` 非 0 → 透传错误码。
4. JDBC 查 `worldtransfers`：待处理（completionTime null）→ 6；冷却 `world_transfer_cooldown` 未到 → 7。
5. 通过 → `sendWorldTransferRules(0, c)`。

### 核心协作类

- `Character.checkWorldTransferEligibility`、`Server.getInstance().getWorldsSize`、`GameConfig`、`DatabaseConnection`

---

## TrockAddMapHandler

处理 `RecvOpcode.TROCK_ADD_MAP`（地图传送石记忆点管理）。

### handlePacket(InPacket, Client) 流程

1. 读 `type` 与 `vip`（VIP 传送石）。
2. `type == 0`（删除）：读 `mapId`，按 vip 调 `chr.deleteFromVipTrocks/deleteFromTrocks`，回发 `trockRefreshMapList(chr, true, vip)` 刷新列表。
3. `type == 1`（登记当前地图）：当前地图无 `FieldLimit.CANNOTVIPROCK` 限制 → `chr.addVipTrockMap()/addTrockMap()` 并刷新列表；否则提示「不能保存此地图」。

### 核心协作类

- `Character.addTrockMap/deleteFromTrocks`（及 VIP 版）、`server.maps.FieldLimit.CANNOTVIPROCK`

---

## UseCashItemHandler

处理 `RecvOpcode.USE_CASH_ITEM`（使用 CASH 类道具，本篇最大的 handler，按 `itemId/10000` 的 itemType 巨型分派）。构造器注入 `NoteService`。

### handlePacket(InPacket, Client) 流程（总控）

1. **节流**：距 `player.getLastUsedCashItem()` < 3000ms → i18n 提示（`message9`）+ `enableActions` 返回；通过则刷新时间戳。
2. 读 `position`（short）、`itemId`；`itemType = itemId / 10000`。
3. 从 `CASH` 背包定位道具（槽位不符则 `findById` 兜底并回写 position）；无道具或数量 <1 → `enableActions` 返回。
4. 取勋章前缀（`EQUIPPED` -49 槽名字，用于喇叭显示 `<勋章> 名字 :`）。
5. 按 itemType 分派（成功路径各自 `remove(c, position, itemId)` 消耗道具）：

| itemType | 业务 | 关键流程 |
|----------|------|----------|
| 504 | 缩地石/VIP 传送石 | 非 VIP：目标地图须同大陆、无 CANNOTVIPROCK、无 forcedReturn（枫叶岛除外）→ `forceChangeMap` 随机出生点；VIP：按玩家名寻人（GM 需 gmLevel 不高于使用者才可达）传到其身边最近出生点。失败补发道具退款 |
| 505 | AP/SP 重置卷 | `itemId > AP_RESET` 为 SP 卷：`AssignSPProcessor.canSPAssign` 防漏洞校验后技能间搬 1 点 SP，被搬空技能联动清理宏（`updateMacros` + `sendMacros`）；否则 AP 卷走 `AssignAPProcessor.APResetAction` |
| 506 | 道具标签/封印锁/孵化器 | `5060000` 给已装备道具烙印主人名；`5060001/5061xxx` 加 `LOCK` flag 并按 7/30/90/365 天顺延过期（永久道具拒绝）；`5060002` 孵化器：`getIncubatedItem` 按内置奖池发奖并扣蛋 |
| 507 | 喇叭家族 | 子型号 `(itemId/1000)%10`：1 普通喇叭（需 ≥10 级）频道广播；2 超级喇叭全世界广播；5 Maple TV（解析 tvType/目标玩家/5 行文本，`MapleTVEffect.broadcastMapleTVIfNotActive` 占线检查，附带超喇叭则全世界广播）；6 道具喇叭（可携带一件道具展示）；7 三连喇叭（1–3 行） |
| 508 | 风筝 | 非自由市场地图 `spawnKite`（`Kite` 地图对象），市场内回 `sendCannotSpawnKite` |
| 509 | 小纸条 | `noteService.sendNormal(msg, 发件人, 收件人)` 成功后扣道具 + `SendNoteSuccessPacket` |
| 510 | 音乐盒 | 全图 `musicChange("Jukebox/Congratulation")` |
| 512 | 场景消息道具 | 有 `stateChangeItem` 则对全图角色应用道具效果；`startMapEffect`（`%s` 替换玩家名与消息） |
| 517 | 宠物取名 | 给 0 号位宠物 `setName/saveToDb`，`forceUpdateItem` + 广播 `changePetName` |
| 520 | 钱袋 | `gainMeso(ii.getMeso(itemId), true, false, true)` |
| 523 | 猫头鹰搜索器 | 读目标 itemid；`use_enforce_item_suggestion` 开启时 `addOwlItemSearch`；`setOwlSearch` + `getAvailableItemBundles` 取全服雇佣商店货源 → `owlOfMinerva` 包；有货才消耗 |
| 524 | 宠物食品 | 遍历 3 只宠物 `canConsume` 匹配 → `gainTamenessFullness`；全部不匹配 i18n 提示（`message10`） |
| 528 | 臭屁/花香 | `5281000`（**BeiDou 自研**）：在身前生成 10 秒绿色雾气（伪造 `StatEffect` sourceId=2111003 的 `Mist`）+ 全图角色表情强制 8（呕吐）；其余型号 `notEnabled` |
| 529 | 家族表情留言板 | `notEnabled`（未启用） |
| 530 | 变身石 | `ii.getItemEffect(itemId).applyTo(player)` |
| 533 | 特快专递券 | `DueyProcessor.dueySendTalk(c, true)` 打开 Duey 界面 |
| 537 | 黑板 | 自由市场禁用；`setChalkboard(文本)` + 广播 `useChalkboard`；**不消耗道具** |
| 539 | 头像喇叭 | 读 4 行文本 + whisper 标志 → `getAvatarMega` 全世界广播，10 秒后 `byeAvatarMega` 收尾（`TimerManager.schedule`） |
| 540 | 改名卡/换区卡（取消申请） | `cancelPendingNameChange()/cancelPendingWorldTransfer()` 并回显取消结果包，消耗道具 |
| 543 | 角色卡 | `MAPLE_LIFE_B` 需 `c.gainCharacterSlot()` 扩槽；读姓名/脸/发/发色/肤/性别/职业/improveSp，发色合法性校验后按职业分派 `Warrior/Magician/Bowman/Thief/PirateCreator.createCharacter`；成功（返回 0）给提示并消耗，-1 为重名错误，其余透传 `sendMapleLifeError` |
| 545 | 包裹商人妙妙 | 无已开商店时 `ShopFactory.getShop(1338).sendShop(c)` 并消耗 |
| 550 | 魔法沙漏（延长装备时限） | **BeiDou 完整实现**：仅限已装备槽（负值）、有时限的非现金装备（`useCashEquip=false`）；`ii.getItemCashInfo` 取 `addTime/maxDays`；三段 i18n 校验（`message11/12/13`）+ 超上限天数校验（`message14`）均回 `enableActions` 解锁防假死；通过后 `setExpiration(+addTime×1000ms)`、`forceUpdateItem`、消耗沙漏、成功提示（`message15`，含装备名与天数） |
| 552 | 宿命剪刀（解除不可交易） | 读背包类型+槽位；物品存在、未带 karma、`isKarmaAble` → `KarmaManipulator.setKarmaFlag` + `forceUpdateItem` + 消耗（其后还有一个不可达的同值 552 分支，死代码） |
| 557 | 金锤子 | 读装备槽（EQUIP 背包）；`getVicious() < 2` 且 CASH 背包持有 `VICIOUS_HAMMER` → vicious+1、`upgradeSlots+1`、`sendHammerData`、`forceUpdateItem`、消耗；失败路径补 `enableActions` 修复客户端假死 |
| 561 | 维嘉的咒术书 | 读标记（必须 1/2）与装备、卷轴槽位；`upgradeSlots ≥ 1`；itemId 非 5 系列置 0（防 PE）；`toggleBlockCashShop` 锁商城 → `scrollEquipWithId(toScroll, uitem, false, itemId, gm)` → `sendVegaScroll(0x41 成功/0x43 失败)`，扣卷轴与咒术书；`TimerManager` 3 秒后解锁商城、`modifyInventory` 刷新、广播 `getScrollEffect`、（已装备且成功）`equipChanged`、`enableActions` |
| 其他 | — | warn 日志「NEW CASH ITEM TYPE」+ `enableActions` |

### 私有辅助方法

| 方法 | 说明 |
|------|------|
| `remove(Client c, short position, int itemid)` | 锁 `CASH` 背包消耗 1 个道具；槽位物品校验失败时 `findById` 兜底再定位，防止并发移动后错删 |
| `getIncubatedItem(Client c, int id)` | 孵化器固定奖池（20 组 id/数量硬编码表），目标背包满返回 false |
| `notEnabled(Character player)` | 统一「该道具未启用」i18n 提示（`message0`）+ 解锁动作 |

### 核心协作类

- `ItemInformationProvider`（getItemCashInfo/getMeso/getStateChangeItem/getMsg/isCash/isKarmaAble 等）
- `NoteService`、`DueyProcessor`、`AssignAPProcessor`/`AssignSPProcessor`、`KarmaManipulator`
- `MapleTVEffect`/`Kite`/`Mist`/`ShopFactory`、`client.creator.veteran.*Creator`
- `TimerManager`、`GameConfig`、`I18nUtil`、`World.getAvailableItemBundles/addOwlItemSearch`

---

## UseCatchItemHandler

处理 `RecvOpcode.USE_CATCH_ITEM`（捕捉类道具：扑兽香水、精灵袋、透明精灵球等）。

### handlePacket(InPacket, Client) 流程

1. 读 stamp、`itemId`、目标怪物 oid；写 autoban 时间戳（`setTimestamp(5, ..., 4)`）。
2. 校验：背包内道具数量 >0、`map.getMonsterByOid(monsterid)` 存在。
3. 按 itemId 分派（通用模式：目标怪正确 → 广播 `catchMonster(oid, itemId, 1)` + `killMonster(mob, null, false)`（无掉落击杀）+ 扣捕捉道具 + `addById` 发奖励；血量/频率不满足 → `catchMessage(0)` 提示）：

| 道具 | 目标怪 | 附加条件 | 产出 |
|------|--------|----------|------|
| `PHEROMONE_PERFUME` | `TAMABLE_HOG` | — | `HOG`（野猪坐骑） |
| `POUCH` | `GHOST` | 1s 防刷（abm 10 号槽）、HP < 40% | `GHOST_SACK` |
| `ARPQ_ELEMENT_ROCK` | `ARPQ_SCORPION` | 0.8s 防刷、HP < 40%、`canHold`、50% 成功率（失败广播 `catchMonster(...,0)`） | `ARPQ_SPIRIT_JEWEL` + `updateAriantScore()` |
| `MAGIC_CANE` | `LOST_RUDOLPH` | HP < 40% | `TAMED_RUDOLPH` |
| `TRANSPARENT_MARBLE_1/2/3` | 道场 `KING_SLIME/FAUST/MUSHMOM` | HP < 30% | `MONSTER_MARBLE_1/2/3` |
| `EPQ_PURIFICATION_MARBLE` | `POISON_FLOWER` | HP < 40% | `EPQ_MONSTER_MARBLE` |
| `FISH_NET` | `P_JUNIOR` | 3s 防刷 | `FISH_NET_WITH_A_CATCH` |
| default | 由 WZ 数据驱动 | `ii.getCreateItem(itemId)`（产出）非 0、`getMobItem` 匹配怪 id、`getUseDelay` 防刷间隔、`getMobHP`（HP 百分比阈值） | `addById(itemGanho)` |

4. 各分支末尾统一 `enableActions`。

### 核心协作类

- `ItemInformationProvider.getCreateItem/getMobItem/getUseDelay/getMobHP`
- `AutoBanManager.getLastSpam/spam`（10 号防刷槽）、`Monster.getHp/getMaxHp`、`MapleMap.killMonster`

---

## UseChairHandler

处理 `RecvOpcode.USE_CHAIR`（坐下/起立椅子）。

### handlePacket(InPacket, Client) 流程

1. 读 `itemId`；校验 `ItemId.isChair(itemId)` 且 `SETUP` 背包确实持有该椅子（防未持有改包）。
2. `tryacquireClient` → `chr.sitChair(itemId)`（内含起立/换椅/地图广播逻辑）。

### 核心协作类

- `Character.sitChair`、`ItemId.isChair`

---

## UseDeathItemHandler

处理 `RecvOpcode.USE_DEATHITEM`（在「死亡界面」使用道具效果，如复活币特效）。

### handlePacket(InPacket, Client) 流程

1. 读 `itemId` → `chr.setItemEffect(itemId)` 记录角色道具特效。
2. 仅向自己发 `itemEffect(chrId, itemId)`（死亡状态不广播他人）。

### 核心协作类

- `Character.setItemEffect`、`PacketCreator.itemEffect`

---

## UseGachaExpHandler

处理 `RecvOpcode.USE_GACHA_EXP`（领取扭蛋累积经验 GachaEXP）。

### handlePacket(InPacket, Client) 流程

1. `tryacquireClient` 串行化。
2. `getGachaExp() <= 0` → `AutobanFactory.GACHA_EXP.autoban`（无存量领取直接封号记录）。
3. `chr.gainGachaExp()` 结转为经验；出口 `enableActions`。

### 核心协作类

- `Character.getGachaExp/gainGachaExp`、`AutobanFactory.GACHA_EXP`

---

## UseHammerHandler

处理 `RecvOpcode.USE_HAMMER`（打开金锤子使用界面）。

### handlePacket(InPacket, Client) 流程

1. 回发 `PacketCreator.sendHammerMessage()` 打开客户端锤子 UI（实际砸锤逻辑在 `UseCashItemHandler` itemType 557）。

### 核心协作类

- `PacketCreator.sendHammerMessage`

---

## UseItemEffectHandler

处理 `RecvOpcode.USE_ITEMEFFECT`（设置角色道具特效，含取消）。

### handlePacket(InPacket, Client) 流程

1. 读 `itemId`；`BUMMER_EFFECT/GOLDEN_CHICKEN_EFFECT` 从 `ETC` 背包查找，其余从 `CASH` 背包查找。
2. 道具不存在且数量 <1：若 `itemId != 0`（非取消操作）直接返回。
3. `chr.setItemEffect(itemId)`；向全图广播 `itemEffect(chrId, itemId)`（不含自己，客户端本地已表现）。

### 核心协作类

- `Character.setItemEffect`、`Inventory.findById`、`PacketCreator.itemEffect`

---

## UseItemHandler

处理 `RecvOpcode.USE_ITEM` 与 `RecvOpcode.USE_RETURN_SCROLL`（共用：使用消耗型道具/药水/回城卷）。

### handlePacket(InPacket, Client) 流程

1. 死亡 → `enableActions` 返回。
2. 读 stamp、`slot`、`itemId`；`USE` 槽位物品三重匹配（存在、数量 >0、id 一致）。
3. **特效药直通分支**（各自扣 1 个后返回）：
   - `ALL_CURE_POTION` → `dispelDebuffs()`；
   - `EYEDROP` → 解 `DARKNESS`；
   - `TONIC` → 解 `WEAKEN` + `SLOW`；
   - `HOLY_WATER` → 解 `SEAL` + `CURSE`。
4. **回城卷**（`ItemConstants.isTownScroll`）：先记录出发数据（当前地图、最近出生点、时间戳）；`ii.getItemEffect(...).applyTo(chr)` 生效后，若 `use_banishable_town_scroll` 开启 → `setBanishPlayerData(...)`（供反挂机/回流机制）；失败且 itemId 为 `2030009/2030010`（特殊回城卷）→ `serverNotice(1, i18n message2)`。
5. **防流放卷**（`isAntibanishScroll`）：`applyTo` 成功扣道具，失败提示（i18n `message1`）。
6. **通用消耗品**：先 `remove(c, slot)` 再 `applyTo(chr)`；`HAPPY_BIRTHDAY`（生日蛋糕）例外——效果作用于全图所有角色。
7. 私有 `remove(Client, short)`：`InventoryManipulator.removeFromSlot(USE, slot, 1)` + `enableActions`。

### 核心协作类

- `ItemInformationProvider.getItemEffect`、`StatEffect.applyTo`
- `Character.dispelDebuffs/dispelDebuff/setBanishPlayerData`、`Disease`
- `ItemConstants.isTownScroll/isAntibanishScroll`、`GameConfig.use_banishable_town_scroll`

---

## UseMapleLifeHandler

处理 `RecvOpcode.USE_MAPLELIFE`（角色卡流程第一步：输入名字校验）。

### handlePacket(InPacket, Client) 流程

1. 3 秒现金道具节流（`getLastUsedCashItem`），超频 → 提示 + `sendMapleLifeError(3)` + `enableActions`。
2. 读 `name`：`Character.canCreateChar(name)` → `sendMapleLifeCharacterInfo()`（进入外观设置）；否则 `sendMapleLifeNameError()`。
3. `enableActions`。

### 核心协作类

- `Character.canCreateChar`（静态）、`PacketCreator.sendMapleLife*`

---

## UseMountFoodHandler

处理 `RecvOpcode.USE_MOUNT_FOOD`（喂坐骑恢复疲劳度/升级）。

### handlePacket(InPacket, Client) 流程

1. 跳 4 字节；读 `pos`（short）、`itemid`；取 `chr.getMapleMount()`。
2. `tryacquireClient` + 锁 `USE` 背包：
   1. 槽位物品 id 匹配且坐骑存在；
   2. 恢复疲劳 `healedTiredness = min(当前疲劳, 30)`，`setTiredness` 扣减；
   3. 有恢复量时：`mount.setExp(+ ceil(恢复比例 × (2×等级 + 6)))`；未满级且经验达标 → `setLevel(level+1)`（`ExpTable.getMountExpNeededForLevel`），满级提示「坐骑已达到最高等级」（BeiDou 中文提示，硬编码）；
   4. `InventoryManipulator.removeById(USE, itemid, 1)`，解锁。
3. `mountLevelup != null`（确实吃到了）→ 全图广播 `updateMount(chrId, mount, mountLevelup)`。

### 核心协作类

- `client.Mount`（tiredness/exp/level）、`constants.game.ExpTable`
- `InventoryManipulator.removeById`、`PacketCreator.updateMount`

---

## UseOwlOfMinervaHandler

处理 `RecvOpcode.OWL_ACTION`（打开密涅瓦之猫头鹰热销榜界面）。

### handlePacket(InPacket, Client) 流程

1. 取 `c.getWorldServer().getOwlSearchedItems()`（全服搜索计数）。
2. 样本 <5 时用 WZ 内置默认榜 `ItemId.getOwlItems()`。
3. 否则按搜索次数降序（`PriorityQueue` + 降序比较器）取前 `min(size, 10)` 个 itemid。
4. 回发 `PacketCreator.getOwlOpen(owlLeaderboards)`。

### 核心协作类

- `World.getOwlSearchedItems`、`ItemId.getOwlItems`

---

## UseSolomonHandler

处理 `RecvOpcode.USE_SOLOMON_ITEM`（使用所罗密的诅咒/经验胶囊类道具兑换 GachaEXP）。

### handlePacket(InPacket, Client) 流程

1. 读 stamp、`slot`、`itemId`；`tryacquireClient` + 锁 `USE` 背包。
2. 校验：槽位有物品；id/数量匹配；`chr.getLevel() <= ii.getMaxLevelById(itemId)`（等级上限内可用）；`ii.getExpById(itemId) + 当前 GachaExp ≤ Integer.MAX_VALUE`（防溢出）。
3. `chr.addGachaExp((int) gachaexp)`；扣 1 个道具；解锁。
4. `enableActions`。

### 核心协作类

- `Character.addGachaExp/getGachaExp`、`ItemInformationProvider.getExpById/getMaxLevelById`

---

## UseSummonBagHandler

处理 `RecvOpcode.USE_SUMMON_BAG`（使用召唤袋放怪，如皮卡丘袋、魔王袋）。

### handlePacket(InPacket, Client) 流程

1. 死亡 → `enableActions` 返回。
2. 读 stamp、`slot`、`itemId`；`USE` 槽位物品匹配（存在、数量 >0、id 一致）。
3. `removeFromSlot` 扣 1 个。
4. `ii.getSummonMobs(itemId)` 取 `int[][] { {mobId, 概率%}, ... }`，逐项掷点（`Randomizer.nextInt(100) < 概率`）→ `map.spawnMonsterOnGroundBelow(LifeFactory.getMonster(mobId), 玩家坐标)`。
5. `enableActions`。

### 核心协作类

- `ItemInformationProvider.getSummonMobs`、`LifeFactory.getMonster`
- `MapleMap.spawnMonsterOnGroundBelow`、`Randomizer`

---

## UseTreasureChestHandler

处理 `RecvOpcode.USE_TREASUER_CHEST`（金/银宝箱开箱，需配套钥匙）。

### handlePacket(InPacket, Client) 流程

1. 读 `slot`（short）、`itemid`；`ETC` 槽位物品三重校验 + 角色存活，否则 `enableActions` 返回。
2. `4280000` 金宝箱 → 奖池 `GameConstants.goldrewards`、钥匙 `5490000`；`4280001` 银宝箱 → 同奖池、钥匙 `5490001`；其他 id 返回。
3. 奖励为 `2000004`（药水）数量 200、`2000005` 数量 100，其余为 1。
4. 前置：CASH 背包持有钥匙 >0，且 EQUIP/USE/SETUP/ETC 四栏均有空位（开箱动画需要完整栏位刷新），否则中文提示「栏位满了」/「请确认是否有钥匙」（BeiDou 中文化硬编码）。
5. 通过：扣宝箱（ETC 槽）+ 扣钥匙（CASH），发 `getShowItemGain(reward, amount, true)` 与 `UseTreasureBox(箱子id)` 开箱特效。

### 核心协作类

- `GameConstants.selectRandomReward/goldrewards`、`InventoryManipulator`

---

## UseWaterOfLifeHandler

处理 `RecvOpcode.WATER_OF_LIFE`（使用生命之水复活宠物）。

### handlePacket(InPacket, Client) 流程

1. 直接 `c.getAbstractPlayerInteraction().openNpc(NpcId.MAR_THE_FAIRY, "waterOfLife")` 打开精灵玛尔的专属脚本（复活得在 NPC 对话中确认宠物与道具）。

### 核心协作类

- `AbstractPlayerInteraction.openNpc`（指定脚本名重载）

---

## WeddingHandler

处理 `RecvOpcode.WEDDING_ACTION`（婚礼礼金/愿望单登记处操作）。

### handlePacket(InPacket, Client) 流程

1. `tryacquireClient` 串行化；读 `mode`。
2. **mode 6 送结婚礼物**：读 `slot/itemid/quantity`；须在婚礼 `Marriage` 实例中：
   - `marriage.giftItemToSpouse(chr.getId())` 判断礼物归属（新郎/新娘愿望单）；
   - 礼物数受 `GameConfig.getServerInt("wedding_gift_limit")` 限制，超限回 `onWeddingGiftResult(0xC)`；
   - 不能送给自己（cid 比对）、配偶须在线；
   - 锁对应背包：物品存在、非不可交易、id/数量匹配 → `item.copy()` 出礼物件、`marriage.addGiftItem`、从送礼者背包扣除、`KarmaManipulator.toggleKarmaFlagToUntradeable(newItem)`（礼物转为不可交易）、计数 +1、回 `onWeddingGiftResult(0xB, 愿望单, 新礼物列表)`；不可交易物品回 `0xE`；
   - 成功后按 `use_enforce_merchant_save` 配置可选立即 `saveCharToDB`，并 `marriage.saveGiftItemsToDb(...)` 落库。
3. **mode 7 取回礼物**：读背包类型（弃）与礼物位 `itemPos`；
   - 在婚礼实例中：`isMarriageGroom` 定位愿望单 → `getGiftItem` 取出 → `Inventory.checkSpot` 有空位 → `removeGiftItem` + `saveGiftItemsToDb` + `addFromDrop` 入包 + `onWeddingGiftResult(0xF)`；无空位/已领取（item null）→ 提示 + `0xE`。
   - 不在实例（婚后领未取礼物）：`getUnclaimedMarriageGifts()` 拿列表同流程处理。
4. **mode 8**：仅 `enableActions`；其他 mode 记 warn。

### 核心协作类

- `server.Marriage`（礼物 CRUD/愿望单/落库）、`KarmaManipulator`
- `GameConfig`（`wedding_gift_limit`、`use_enforce_merchant_save`）、`WeddingPackets.onWeddingGiftResult`

---

## WeddingTalkHandler

处理 `RecvOpcode.WEDDING_TALK`（婚礼仪式中与主祭 NPC 对话推进流程）。

### handlePacket(InPacket, Client) 流程

1. 读 `action`。
2. `action == 1` 且玩家**不是**当前婚礼新人（对不上 EIM 的 `groomId/brideId`）→ `OnWeddingProgress(false, 0, 0, (byte) 2)`（旁观者视角）。
3. 新人或非 action==1 → `OnWeddingProgress(true, 0, 0, (byte) 3)`。
4. `enableActions`。

### 核心协作类

- `EventInstanceManager.getIntProperty`（`groomId/brideId`）、`WeddingPackets.OnWeddingProgress`

---

## WeddingTalkMoreHandler

处理 `RecvOpcode.WEDDING_TALK_MORE`（非新人宾客在婚礼中「献上祝福」）。

### handlePacket(InPacket, Client) 流程

1. 取玩家 EIM；在婚礼图中且非新人 → `eim.gridInsert(c.getPlayer(), 1)`（宾客祝福计数入 EIM）+ 神父约翰祝福文案。
2. 回 `OnWeddingProgress(true, 0, 0, (byte) 3)` + `enableActions`。

### 核心协作类

- `EventInstanceManager.gridInsert`、`WeddingPackets.OnWeddingProgress`

---

## WhisperHandler

处理 `RecvOpcode.WHISPER`（私聊、查找玩家位置/好友定位）。

### 公有常量

| 常量 | 值 | 含义 |
|------|----|------|
| `RT_ITC` | 0x00 | 在道具/交易界面（Item Transfer Center） |
| `RT_SAME_CHANNEL` | 0x01 | 同频道（携带 mapId） |
| `RT_CASH_SHOP` | 0x02 | 在商城 |
| `RT_DIFFERENT_CHANNEL` | 0x03 | 跨频道（携带 channel-1） |

### handlePacket(InPacket, Client) 流程

1. 读 `request`（组合标志 `WhisperFlag.LOCATION/WHISPER/LOCATION_FRIEND | REQUEST`）与目标名；`getCharacterByName` 找不到 → `getWhisperResult(name, false)`（未找到回执）。
2. `LOCATION|REQUEST` / `LOCATION_FRIEND|REQUEST` → `handleFind(user, target, flag)`：
   - `user.gmLevel() >= target.gmLevel()` 才可见：目标在商城 → `RT_CASH_SHOP`；同频道 → `RT_SAME_CHANNEL` + mapId；否则 `RT_DIFFERENT_CHANNEL` + 频道号-1。
   - GM 等级不足 → 与「未找到」同款回执（GM 隐身保护）。
3. `WHISPER|REQUEST` → `handleWhisper(message, user, target)`：
   - 200ms 防刷（autoban 7 号 spam 槽）；
   - 消息长度 > `Byte.MAX_VALUE` → `AutobanFactory.PACKET_EDIT.alert` + warn + `disconnect(true, false)`；
   - `ChatLogger.log(client, "Whisper To X", message)` 落日志；
   - 目标收 `getWhisperReceive(发送者名, 频道-1, isGM, message)`；发送者收 `getWhisperResult(目标名, !hidden)`，`hidden = target.isHidden() && target.gmLevel() > user.gmLevel()`（对更高等级隐身 GM 显示「不在线」）。

### 核心协作类

- `PacketCreator.getWhisperResult/getWhisperReceive/getFindResult`、`PacketCreator.WhisperFlag`
- `AutoBanManager.spam/getLastSpam`、`ChatLogger`、`AutobanFactory.PACKET_EDIT`

---

## 附：本篇 handler 与 RecvOpcode 对照表

| Handler | RecvOpcode | Handler | RecvOpcode |
|---------|-----------|---------|-----------|
| QuickslotKeyMappedModifiedHandler | `CHANGE_QUICKSLOT` | TransferNameHandler | `NAME_TRANSFER` |
| RaiseIncExpHandler | `USE_ITEMUI` | TransferNameResultHandler | `CHECK_CHAR_NAME` |
| RaiseUIStateHandler | `OPEN_ITEMUI` | TransferWorldHandler | `WORLD_TRANSFER` |
| RangedAttackHandler | `RANGED_ATTACK` | TrockAddMapHandler | `TROCK_ADD_MAP` |
| ReactorHitHandler | `DAMAGE_REACTOR` | UseCashItemHandler | `USE_CASH_ITEM` |
| RemoteGachaponHandler | `USE_REMOTE` | UseCatchItemHandler | `USE_CATCH_ITEM` |
| RemoteStoreHandler | `REMOTE_STORE` | UseChairHandler | `USE_CHAIR` |
| ReportHandler | `REPORT` | UseDeathItemHandler | `USE_DEATHITEM` |
| RingActionHandler | `RING_ACTION` | UseGachaExpHandler | `USE_GACHA_EXP` |
| RPSActionHandler | `RPS_ACTION` | UseHammerHandler | `USE_HAMMER` |
| ScriptedItemHandler | `SCRIPTED_ITEM` | UseItemEffectHandler | `USE_ITEMEFFECT` |
| ScrollHandler | `USE_UPGRADE_SCROLL` | UseItemHandler | `USE_ITEM` / `USE_RETURN_SCROLL` |
| SetHpMpAlertHandler | `SET_HPMPALERT` | UseMapleLifeHandler | `USE_MAPLELIFE` |
| SkillBookHandler | `USE_SKILL_BOOK` | UseMountFoodHandler | `USE_MOUNT_FOOD` |
| SkillEffectHandler | `SKILL_EFFECT` | UseOwlOfMinervaHandler | `OWL_ACTION` |
| SkillMacroHandler | `SKILL_MACRO` | UseSolomonHandler | `USE_SOLOMON_ITEM` |
| SnowballHandler | `SNOWBALL` | UseSummonBagHandler | `USE_SUMMON_BAG` |
| SpawnPetHandler | `SPAWN_PET` | UseTreasureChestHandler | `USE_TREASUER_CHEST` |
| SpecialMoveHandler | `SPECIAL_MOVE` | UseWaterOfLifeHandler | `WATER_OF_LIFE` |
| SpouseChatHandler | `SPOUSE_CHAT` | WeddingHandler | `WEDDING_ACTION` |
| StorageHandler | `STORAGE` | WeddingTalkHandler | `WEDDING_TALK` |
| SummonDamageHandler | `SUMMON_ATTACK` | WeddingTalkMoreHandler | `WEDDING_TALK_MORE` |
| TakeDamageHandler | `TAKE_DAMAGE` | WhisperHandler | `WHISPER` |
| TouchingCashShopHandler | `CHECK_CASH` | TouchMonsterDamageHandler | `TOUCH_MONSTER_ATTACK` |
| TouchReactorHandler | `TOUCHING_REACTOR` | | |

> 备注：`UseItemHandler` 同时注册在 `USE_ITEM` 与 `USE_RETURN_SCROLL` 两个 opcode 上（见 `PacketProcessor` 第 192–193 行）；`RingActionHandler` 与 `UseCashItemHandler` 为构造器注入 `NoteService` 的 Spring 依赖 handler（`PacketProcessor` 第 191、250 行）。
