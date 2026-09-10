# DAO 层实体详细设计（org.gms.dao.entity）

- **模块路径**：`gms-server/src/main/java/org/gms/dao/entity`
- **类数量**：84 个 DO 实体（统一后缀 `DO`，实现 `Serializable`，Lombok `@Data/@Builder/@NoArgsConstructor/@AllArgsConstructor`，MyBatis-Flex `@Table("表名")` 注解映射数据库表）
- **依赖模块**：MyBatis-Flex（`com.mybatisflex.annotation.Table/Id/Column`、`KeyType`）、Lombok、Jackson（`@JsonIgnore/@JsonFormat`）、`java.sql.Timestamp/Date`；个别实体（`ModifiedCashItemDO`）额外依赖 `client.inventory.*`、`server.ItemInformationProvider` 等游戏逻辑类
- **数据来源**：实体由 `CodeGen#genMapperAndEntity`（test 作用域）从 MySQL `beidou` 库生成；表结构见 `src/main/resources/db/migration/V1.0.x ~ V1.11.x` Flyway 迁移脚本
- **约定**：主键多用 `@Id(keyType = KeyType.Auto)`（数据库自增）；联合主键实体（如 `HwidaccountsDO`、`MonsterbookDO`、`MakerreagentdataDO` 等）在多个字段上标注 `@Id` 且无自增；与 SQL 列名不一致或命中 SQL 关键字的字段用 `@Column` 显式映射（如 `str/dex/luk/int`、`old/new`、`class`）；非数据库字段用 `@Column(ignore = true)`

## 1. 实体 ↔ 数据库表对照总表

| # | 实体类 | 数据库表 | 迁移脚本（首次建表） | 主键 | 业务域 |
|---|--------|----------|----------------------|------|--------|
| 1 | AccountsDO | accounts | V1.0.0__create_accounts.sql | id（自增） | 账号 |
| 2 | CharactersDO | characters | V1.0.6__create_characters.sql | id（自增） | 角色 |
| 3 | HwidaccountsDO | hwidaccounts | V1.0.16__create_hwid.sql | accountid+hwid（联合） | 账号 |
| 4 | HwidbansDO | hwidbans | V1.0.16__create_hwid.sql | hwidbanid（自增） | 账号 |
| 5 | IpbansDO | ipbans | V1.0.18__create_ban.sql | ipbanid（自增） | 账号 |
| 6 | MacbansDO | macbans | V1.0.18__create_ban.sql | macbanid（自增） | 账号 |
| 7 | MacfiltersDO | macfilters | V1.0.18__create_ban.sql | macfilterid（自增） | 账号 |
| 8 | QuickslotkeymappedDO | quickslotkeymapped | V1.0.34__create_quickslotkeymapped.sql | accountid | 账号 |
| 9 | StoragesDO | storages | V1.0.45__create_storages.sql | storageid（自增） | 账号 |
| 10 | ExtendValueDO | extend_value | V1.1.2__create_extend_value.sql | extendId+extendType+extendName（联合） | 账号/角色扩展 |
| 11 | SkillsDO | skills | V1.0.43__create_skill.sql | id（自增） | 角色 |
| 12 | SkillmacrosDO | skillmacros | V1.0.43__create_skill.sql | id（自增） | 角色 |
| 13 | CooldownsDO | cooldowns | V1.0.7__create_cooldowns.sql | id（自增） | 角色 |
| 14 | KeymapDO | keymap | V1.0.19__create_keymap.sql | id（自增） | 角色 |
| 15 | AreaInfoDO | area_info | V1.0.2__create_area_info.sql | id（自增） | 角色 |
| 16 | SavedlocationsDO | savedlocations | V1.0.39__create_savedlocations.sql | id（自增） | 角色 |
| 17 | TrocklocationsDO | trocklocations | V1.0.46__create_trocklocations.sql | trockid（自增） | 角色 |
| 18 | PlayerdiseasesDO | playerdiseases | V1.0.30__create_playerdiseases.sql | id（自增） | 角色 |
| 19 | MonsterbookDO | monsterbook | V1.0.23__create_monster.sql | charid+cardid（联合） | 角色 |
| 20 | MedalmapsDO | medalmaps | V1.0.22__create_medalmaps.sql | id（自增） | 角色 |
| 21 | HpMpAlertDO | hp_mp_alert | V1.3.2__create_hp_mp_alert.sql | id（自增） | 角色 |
| 22 | NamechangesDO | namechanges | V1.0.25__create_namechanges.sql | id（自增） | 角色 |
| 23 | WorldtransfersDO | worldtransfers | V1.0.48__create_worldtransfers.sql | id（自增） | 角色 |
| 24 | InventoryitemsDO | inventoryitems | V1.0.17__create_inventory.sql | inventoryitemid（自增） | 物品 |
| 25 | InventoryequipmentDO | inventoryequipment | V1.0.17__create_inventory.sql | inventoryequipmentid（自增） | 物品 |
| 26 | InventorymerchantDO | inventorymerchant | V1.0.17__create_inventory.sql | inventorymerchantid（自增） | 物品 |
| 27 | PetsDO | pets | V1.0.29__create_pets.sql | petid（自增） | 物品 |
| 28 | PetignoresDO | petignores | V1.0.29__create_pets.sql | id（自增） | 物品 |
| 29 | DueypackagesDO | dueypackages | V1.0.9__create_dueyi.sql | packageid（自增） | 物品 |
| 30 | DueyitemsDO | dueyitems | V1.0.9__create_dueyi.sql | id（自增） | 物品 |
| 31 | FredstorageDO | fredstorage | V1.0.13__create_fredstorage.sql | id（自增） | 物品 |
| 32 | QueststatusDO | queststatus | V1.0.33__create_quest.sql | queststatusid（自增） | 任务 |
| 33 | QuestprogressDO | questprogress | V1.0.33__create_quest.sql | id（自增） | 任务 |
| 34 | QuestactionsDO | questactions | V1.0.33__create_quest.sql | questactionid（自增） | 任务 |
| 35 | QuestrequirementsDO | questrequirements | V1.0.33__create_quest.sql | questrequirementid（自增） | 任务 |
| 36 | BuddiesDO | buddies | V1.0.5__create_buddies.sql | id（自增） | 社交 |
| 37 | FamelogDO | famelog | V1.0.11__create_famelog.sql | famelogid（自增） | 社交 |
| 38 | NotesDO | notes | V1.0.27__create_notes.sql | id（自增） | 社交 |
| 39 | GuildsDO | guilds | V1.0.15__create_guilds.sql | guildid（自增） | 社交 |
| 40 | AllianceDO | alliance | V1.0.1__create_alliance.sql | id（自增） | 社交 |
| 41 | AllianceguildsDO | allianceguilds | V1.0.1__create_alliance.sql | id（自增） | 社交 |
| 42 | BbsThreadsDO | bbs_threads | V1.0.4__create_bbs.sql | threadid（自增） | 社交 |
| 43 | BbsRepliesDO | bbs_replies | V1.0.4__create_bbs.sql | replyid（自增） | 社交 |
| 44 | MarriagesDO | marriages | V1.0.21__create_marriages.sql | marriageid（自增） | 社交 |
| 45 | RingsDO | rings | V1.0.38__create_rings.sql | id（自增） | 社交 |
| 46 | FamilyCharacterDO | family_character | V1.0.12__create_family.sql | cid | 社交（家族） |
| 47 | FamilyEntitlementDO | family_entitlement | V1.0.12__create_family.sql | id（自增） | 社交（家族） |
| 48 | NewyearDO | newyear | V1.0.26__create_newyear.sql | id（自增） | 社交 |
| 49 | GiftsDO | gifts | V1.0.14__create_gifts.sql | id（自增） | 社交/商城 |
| 50 | MtsItemsDO | mts_items | V1.0.24__create_mts.sql | id（自增） | 商城/交易 |
| 51 | MtsCartDO | mts_cart | V1.0.24__create_mts.sql | id（自增） | 商城/交易 |
| 52 | WishlistsDO | wishlists | V1.0.47__create_wishlists.sql | id（自增） | 商城 |
| 53 | NxcodeDO | nxcode | V1.0.28__create_nx.sql | id（自增） | 商城 |
| 54 | NxcodeItemsDO | nxcode_items | V1.0.28__create_nx.sql | id（自增） | 商城 |
| 55 | NxcouponsDO | nxcoupons | V1.0.28__create_nx.sql | id（自增） | 商城 |
| 56 | SpecialcashitemsDO | specialcashitems | V1.0.44__create_specialcashitems.sql | id | 商城 |
| 57 | ModifiedCashItemDO | modified_cash_item | V1.1.3__create_modified_cash_item.sql | sn | 商城 |
| 58 | ShopsDO | shops | V1.0.42__create_shops.sql | shopid（自增） | 商城/NPC商店 |
| 59 | ShopitemsDO | shopitems | V1.0.41__create_shopitems.sql | shopitemid（自增） | 商城/NPC商店 |
| 60 | GachaponRewardPoolDO | gachapon_reward_pool | V1.4.1__create_gachapon_reward_pool.sql | id（自增） | 商城/转蛋 |
| 61 | GachaponRewardDO | gachapon_reward | V1.4.0__create_gachapon_reward.sql | id（自增） | 商城/转蛋 |
| 62 | DropDataDO | drop_data | V1.0.8__create_drop_data.sql | id（自增） | 游戏世界 |
| 63 | DropDataGlobalDO | drop_data_global | V1.0.8__create_drop_data.sql | id（自增） | 游戏世界 |
| 64 | ReactordropsDO | reactordrops | V1.0.35__create_reactordrops.sql | reactordropid（自增） | 游戏世界 |
| 65 | MonstercarddataDO | monstercarddata | V1.0.23__create_monster.sql | id（自增） | 游戏世界 |
| 66 | EventstatsDO | eventstats | V1.0.10__create_eventstats.sql | characterid | 游戏世界 |
| 67 | BosslogDailyDO | bosslog_daily | V1.0.3__create_bosslog.sql | id（自增） | 游戏世界 |
| 68 | BosslogWeeklyDO | bosslog_weekly | V1.0.3__create_bosslog.sql | id（自增） | 游戏世界 |
| 69 | MakercreatedataDO | makercreatedata | V1.0.20__create_maker.sql | id+itemid（联合） | 游戏世界 |
| 70 | MakerreagentdataDO | makerreagentdata | V1.0.20__create_maker.sql | itemid | 游戏世界 |
| 71 | MakerrecipedataDO | makerrecipedata | V1.0.20__create_maker.sql | itemid+reqItem（联合） | 游戏世界 |
| 72 | MakerrewarddataDO | makerrewarddata | V1.0.20__create_maker.sql | itemid+rewardid（联合） | 游戏世界 |
| 73 | PlayernpcsDO | playernpcs | V1.0.31__create_playernpcs.sql | id（自增） | 游戏世界 |
| 74 | PlayernpcsEquipDO | playernpcs_equip | V1.0.31__create_playernpcs.sql | id（自增） | 游戏世界 |
| 75 | PlayernpcsFieldDO | playernpcs_field | V1.0.31__create_playernpcs.sql | id（自增） | 游戏世界 |
| 76 | PlifeDO | plife | V1.0.32__create_plife.sql | id（自增） | 游戏世界 |
| 77 | GameConfigDO | game_config | V1.7.0__create_game_config.sql | id（自增） | 运维配置 |
| 78 | LangResourcesDO | lang_resources | V1.7.1__create_lang_resources.sql | id（自增） | 运维配置 |
| 79 | CommandInfoDO | command_info | V1.5.1__create_command_info.sql | id（自增） | 运维配置 |
| 80 | AutobanConfigDO | autoban_config | V1.10.1__create_autoban_config.sql | id（自增） | 运维配置 |
| 81 | ServerQueueDO | server_queue | V1.0.40__create_server_queue.sql | id（自增） | 运维配置 |
| 82 | ResponsesDO | responses | V1.0.37__create_responses.sql | id（自增） | 运维配置 |
| 83 | ReportsDO | reports | V1.0.36__create_reports.sql | id（自增） | 运维配置 |
| 84 | FlywaySchemaHistoryDO | flyway_schema_history | Flyway 框架自动维护 | installedRank | 运维配置 |

> 说明：`characterexplogs`（V1.4.3）等表未生成 DO 实体，不在 `org.gms.dao.entity` 包内；`game_config`、`world_prop`、`server_prop` 相关表中的 `game_config` 有对应实体。

## 2. 账号域

### 2.1 AccountsDO（accounts，账号主表）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 账号 ID，自增主键 |
| name | String | 账号名（登录名） |
| password | String | 密码（序列化时 `@JsonIgnore` 不外露） |
| pin | String | PIN 码（角色删除二次校验） |
| pic | String | PIC 码（建角色校验） |
| loggedin | Integer | 登录状态（0 未登录 / 1 登录中 / 2 已进游戏） |
| lastlogin | Timestamp | 最后登录时间 |
| createdat | Timestamp | 创建时间 |
| birthday | Date | 生日（8 位日期校验用） |
| banned | Boolean | 是否永久封禁 |
| banreason | String | 封禁原因 |
| macs | String | 登录 MAC 地址串 |
| nxCredit（列 nxCredit） | Integer | NX 点券（信用点）余额 |
| maplePoint（列 maplePoint） | Integer | 抵用券余额 |
| nxPrepaid（列 nxPrepaid） | Integer | NX 预付点余额 |
| characterslots | Integer | 角色位上限 |
| gender | Integer | 性别 |
| tempban | Timestamp | 临时封禁截止时间 |
| greason | Integer | 封禁原因编号 |
| tos | Boolean | 是否同意用户协议 |
| sitelogged | String | 站点登录标识 |
| webadmin | Integer | Web 管理后台权限等级 |
| nick | String | 昵称 |
| mute | Integer | 禁言等级 |
| email | String | 邮箱 |
| ip | String | 最后登录 IP |
| rewardpoints | Integer | 奖励点数 |
| votepoints | Integer | 投票点数 |
| hwid | String | 最后登录硬件指纹 |
| language | Integer | 界面语言 |

关系：1:N `CharactersDO`（accountid）；1:N `StoragesDO`（accountid，按 world 分仓）；1:N `InventoryitemsDO`（accountid，现金物品挂在账号下）；1:1 `QuickslotkeymappedDO`（accountid）；N:M `HwidaccountsDO`（accountid+hwid）。

### 2.2 HwidaccountsDO（hwidaccounts，账号-硬件指纹关联）

| 字段 | 类型 | 含义 |
|---|---|---|
| accountid | Integer | 账号 ID（联合主键） |
| hwid | String | 硬件指纹（联合主键） |
| relevance | Integer | 关联度（同指纹登录次数权重） |
| expiresat | Timestamp | 记录过期时间 |

关系：N:1 `AccountsDO`；与 `HwidbansDO.hwid` 联动判断硬件封禁。

### 2.3 HwidbansDO（hwidbans，硬件封禁表）

| 字段 | 类型 | 含义 |
|---|---|---|
| hwidbanid | Long | 自增主键 |
| hwid | String | 被封硬件指纹 |

### 2.4 IpbansDO（ipbans，IP 封禁表）

| 字段 | 类型 | 含义 |
|---|---|---|
| ipbanid | Long | 自增主键 |
| ip | String | 被封 IP（支持 CIDR 前缀匹配） |
| aid | String | 关联账号名 |

### 2.5 MacbansDO（macbans，MAC 封禁表）

| 字段 | 类型 | 含义 |
|---|---|---|
| macbanid | Long | 自增主键 |
| mac | String | 被封 MAC 地址 |
| aid | String | 关联账号名 |

### 2.6 MacfiltersDO（macfilters，MAC 黑名单过滤表）

| 字段 | 类型 | 含义 |
|---|---|---|
| macfilterid | Long | 自增主键 |
| filter | String | 过滤规则（禁止注册/登录的 MAC 模式） |

### 2.7 QuickslotkeymappedDO（quickslotkeymapped，快捷键配置）

| 字段 | 类型 | 含义 |
|---|---|---|
| accountid | Integer | 账号 ID（主键） |
| keymap | Long | 快捷键位图编码值（8 个快捷键状态打包） |

关系：1:1 `AccountsDO`。

### 2.8 StoragesDO（storages，账号仓库）

| 字段 | 类型 | 含义 |
|---|---|---|
| storageid | Long | 自增主键 |
| accountid | Integer | 所属账号 |
| world | Integer | 所属世界（仓库按世界隔离） |
| slots | Integer | 仓库槽位上限 |
| meso | Integer | 仓库存放金币 |

关系：N:1 `AccountsDO`；仓库内物品为 `type=2`（Storage）的 `InventoryitemsDO`。

### 2.9 ExtendValueDO（extend_value，扩展字段表，联合主键）

| 字段 | 类型 | 含义 |
|---|---|---|
| extendId | String | 扩展字段 ID（联合主键），通常传账号/角色 ID |
| extendType | String | 扩展字段类型（迁移脚本注释：11-账号，12-账号日清，13-账号周清；21-角色，22-角色日清，23-角色周清） |
| extendName | String | 扩展字段名称（联合主键） |
| extendValue | String | 扩展字段值 |
| createTime | Date | 创建时间 |
| updateTime | Date | 更新时间 |

关系：extendId 逻辑指向 `AccountsDO.id` 或 `CharactersDO.id`（无外键）。

## 3. 角色域

### 3.1 CharactersDO（characters，角色主表，字段最多）

| 字段 | 类型 | 含义 | 字段 | 类型 | 含义 |
|---|---|---|---|---|---|
| id | Integer | 角色 ID（自增 PK） | merchantmesos | Integer | 精灵商店累计金币 |
| accountid | Integer | 所属账号 ID | hasmerchant | Boolean | 是否开着精灵商店 |
| world | Integer | 所在世界 | equipslots | Integer | 装备栏槽位数 |
| name | String | 角色名 | useslots | Integer | 消耗栏槽位数 |
| level | Integer | 等级 | setupslots | Integer | 设置栏槽位数 |
| exp | Integer | 经验 | etcslots | Integer | 其他栏槽位数 |
| gachaexp | Integer | 转蛋累积经验 | familyId（列 familyId） | Integer | 家族 ID |
| attrStr（列 str） | Integer | 力量 STR | monsterbookcover | Integer | 怪物书封面卡片 |
| attrDex（列 dex） | Integer | 敏捷 DEX | allianceRank（列 allianceRank） | Integer | 联盟内职位 |
| attrLuk（列 luk） | Integer | 运气 LUK | vanquisherStage | Integer | 远征队讨伐阶段 |
| attrInt（列 int） | Integer | 智力 INT | ariantPoints | Integer | 阿里安特竞技场点 |
| hp / mp | Integer | 当前 HP/MP | dojoPoints | Integer | 道场点数 |
| maxhp / maxmp | Integer | 最大 HP/MP | lastDojoStage | Integer | 道场最高层 |
| meso | Integer | 携带金币 | finishedDojoTutorial | Integer | 是否完成道场教程 |
| hpMpUsed（列 hpMpUsed） | Integer | 已用 HP/MP 洗点量 | vanquisherKills | Integer | 讨伐击杀数 |
| job | Integer | 职业 ID | summonValue（列 summonValue） | Long | 召唤物相关值 |
| skincolor | Integer | 肤色 | partnerId（列 partnerId） | Integer | 结婚对象角色 ID |
| gender | Integer | 性别 | marriageItemId | Integer | 结婚戒指物品 ID |
| fame | Integer | 人气值 | reborns | Integer | 转生次数（私服扩展） |
| fquest | Integer | 人气任务计数 | pqpoints | Integer | 组队任务点（私服扩展） |
| hair / face | Integer | 发型/脸型 | dataString | String | 扩展数据串 |
| ap | Integer | 可用能力点 | lastLogoutTime | Timestamp | 最后登出时间 |
| sp | String | 可用技能点（按职业技能组打包的串） | lastExpGainTime | Timestamp | 最后获得经验时间 |
| map | Integer | 所在地图 ID | partySearch | Boolean | 是否开启组队搜索 |
| spawnpoint | Integer | 出生点编号 | jailexpire | Long | 监禁到期时间戳 |
| gm | Integer | GM 等级 | createdate | Timestamp | 创建时间 |
| party | Integer | 所属队伍 ID | rank / rankMove | Integer | 全服排名 / 排名变化 |
| buddyCapacity（列 buddyCapacity） | Integer | 好友列表容量 | jobRank / jobRankMove | Integer | 职业排名 / 变化 |
| guildid | Integer | 公会 ID | guildrank | Integer | 公会职位（1 会长~5 成员） |
| messengerid | Integer | 所属小密 ID | messengerposition | Integer | 小密中的位置 |
| mountlevel / mountexp / mounttiredness | Integer | 骑宠等级/经验/疲劳 | | | |
| omokwins/losses/ties | Integer | 五子棋胜/负/平 | matchcardwins/losses/ties | Integer | 配对卡胜/负/平 |

关系：N:1 `AccountsDO`；1:N `SkillsDO/SkillmacrosDO/CooldownsDO/KeymapDO/InventoryitemsDO/QueststatusDO/BuddiesDO/...`（均以 characterid 关联）；N:1 `GuildsDO`、`AllianceDO`（经 guilds）。

### 3.2 SkillsDO（skills，角色技能）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| skillid | Integer | 技能 ID |
| characterid | Integer | 所属角色 |
| skilllevel | Integer | 技能等级 |
| masterlevel | Integer | 大师等级 |
| expiration | Long | 到期时间戳（限时技能） |

### 3.3 SkillmacrosDO（skillmacros，技能宏）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| characterid | Integer | 所属角色 |
| position | Integer | 宏槽位 |
| skill1/skill2/skill3 | Integer | 宏绑定的 3 个技能 |
| name | String | 宏名称 |
| shout | Integer | 是否喊话施放 |

### 3.4 CooldownsDO（cooldowns，技能冷却）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| charid | Integer | 所属角色 |
| skillid | Integer | 技能 ID |
| length | Long | 冷却时长（毫秒） |
| starttime | Long | 冷却开始时间戳 |

### 3.5 KeymapDO（keymap，键盘快捷键）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| characterid | Integer | 所属角色（V1.11.4 加了索引） |
| key | Integer | 键位码 |
| type | Integer | 绑定类型（技能/物品/表情等） |
| action | Integer | 绑定动作 ID |

### 3.6 AreaInfoDO（area_info，区域进度信息）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| charid | Integer | 所属角色 |
| area | Integer | 区域编号 |
| info | String | 区域进度值 |

### 3.7 SavedlocationsDO（savedlocations，记录点传送）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| characterid | Integer | 所属角色 |
| locationtype | String | 记录点类型（免费市场/婚礼地图等） |
| map | Integer | 地图 ID |
| portal | Integer | 传送门编号 |

### 3.8 TrocklocationsDO（trocklocations，岩石传送点）

| 字段 | 类型 | 含义 |
|---|---|---|
| trockid | Integer | 自增主键 |
| characterid | Integer | 所属角色 |
| mapid | Integer | 记录的地图 ID |
| vip | Integer | 0 普通岩石 / 1 VIP 岩石 |

### 3.9 PlayerdiseasesDO（playerdiseases，角色异常状态）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| charid | Integer | 所属角色 |
| disease | Integer | 异常状态类型 |
| mobskillid | Integer | 施加状态的怪物技能 ID |
| mobskilllv | Integer | 怪物技能等级 |
| length | Long | 持续时长（毫秒） |

### 3.10 MonsterbookDO（monsterbook，怪物图鉴，联合主键）

| 字段 | 类型 | 含义 |
|---|---|---|
| charid | Integer | 所属角色（联合主键） |
| cardid | Integer | 怪物卡片 ID（联合主键） |
| level | Integer | 卡片等级（重复获得升级） |

### 3.11 MedalmapsDO（medalmaps，勋章探索地图）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| characterid | Integer | 所属角色 |
| queststatusid | Long | 关联任务状态 ID（`QueststatusDO`） |
| mapid | Integer | 已访问的勋章计数地图 |

### 3.12 HpMpAlertDO（hp_mp_alert，HP/MP 药品预警比例）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键（c_id 上有唯一索引） |
| cId | Integer | 角色 ID |
| hp | Byte | HP 低于百分比自动喝药阈值 |
| mp | Byte | MP 低于百分比自动喝药阈值 |

### 3.13 NamechangesDO（namechanges，改名记录）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| characterid | Integer | 角色 ID |
| older（列 old） | String | 旧角色名 |
| newer（列 new） | String | 新角色名 |
| requestTime（列 requestTime） | Timestamp | 申请时间 |
| completionTime | Timestamp | 完成时间 |

### 3.14 WorldtransfersDO（worldtransfers，世界转移记录）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| characterid | Integer | 角色 ID |
| from / to | Integer | 原世界 / 目标世界 |
| requestTime | Timestamp | 申请时间 |
| completionTime | Timestamp | 完成时间 |

## 4. 物品域

### 4.1 InventoryitemsDO（inventoryitems，物品实例主表）

| 字段 | 类型 | 含义 |
|---|---|---|
| inventoryitemid | Long | 物品实例 ID（自增 PK） |
| type | Integer | 归属类型（1 角色 / 2 账号仓库 / 3 商店 / 4 Duey 等） |
| characterid | Integer | 所属角色（账号级物品为空） |
| accountid | Integer | 所属账号（仓库/现金物品） |
| itemid | Integer | 物品模板 ID |
| inventorytype | Integer | 背包栏类型（1 装备 2 消耗 3 设置 4 其他） |
| position | Integer | 栏内位置 |
| quantity | Integer | 数量 |
| owner | String | 所有者刻名 |
| petid | Integer | 关联宠物 ID（`PetsDO.petid`） |
| flag | Integer | 物品属性标志位（锁定/防滑等） |
| expiration | Long | 到期时间戳 |
| giftFrom（列 giftFrom） | String | 赠送者名字 |

关系：1:1 `InventoryequipmentDO`（inventoryitemid，仅装备）；1:1 `PetsDO`（petid）；被 `InventorymerchantDO`、`DueyitemsDO`、`MtsItemsDO` 引用（inventoryitemid）。

### 4.2 InventoryequipmentDO（inventoryequipment，装备附加属性）

| 字段 | 类型 | 含义 |
|---|---|---|
| inventoryequipmentid | Long | 自增主键 |
| inventoryitemid | Long | 关联物品实例 ID（`InventoryitemsDO`） |
| upgradeslots | Integer | 剩余可卷轴数 |
| level | Integer | 强化等级（卷轴成功次数） |
| str / dex / inte（列 int）/ luk | Integer | 四维附加属性 |
| hp / mp | Integer | 附加 HP/MP |
| watk / matk | Integer | 物理/魔法攻击力 |
| wdef / mdef | Integer | 物理/魔法防御 |
| acc / avoid / hands | Integer | 命中/回避/手技 |
| speed / jump | Integer | 移动/跳跃 |
| locked | Integer | 锁定标志 |
| vicious | Integer | 诅咒卷附加（锤子/vicious 计数） |
| itemlevel | Integer | 道具等级（成长装备） |
| itemexp | Integer | 道具经验 |
| ringid | Integer | 关联戒指 ID（`RingsDO`） |

### 4.3 InventorymerchantDO（inventorymerchant，精灵商店在售物品）

| 字段 | 类型 | 含义 |
|---|---|---|
| inventorymerchantid | Long | 自增主键 |
| inventoryitemid | Long | 关联物品实例 ID |
| characterid | Integer | 店主角色 ID |
| bundles | Integer | 捆绑数量 |

### 4.4 PetsDO（pets，宠物实例）

| 字段 | 类型 | 含义 |
|---|---|---|
| petid | Long | 宠物实例 ID（自增 PK） |
| name | String | 宠物名 |
| level | Long | 宠物等级 |
| closeness | Long | 亲密度 |
| fullness | Long | 饱食度 |
| summoned | Boolean | 是否被召唤 |
| flag | Long | 宠物道具标志 |

### 4.5 PetignoresDO（petignores，宠物自动拾取忽略项）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Long | 自增主键 |
| petid | Integer | 宠物实例 ID（`PetsDO`） |
| itemid | Integer | 忽略拾取的物品 ID |

### 4.6 DueypackagesDO（dueypackages，快递包裹）

| 字段 | 类型 | 含义 |
|---|---|---|
| packageid | Long | 包裹 ID（自增 PK） |
| receiverid | Long | 收件人角色 ID |
| sendername | String | 发件人名 |
| mesos | Long | 附带金币 |
| timestamp | Timestamp | 寄送时间 |
| message | String | 附言 |
| checked | Integer | 是否已被查收 |
| type | Integer | 包裹类型（0 物品 / 1 金币） |

### 4.7 DueyitemsDO（dueyitems，快递包裹内物品）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Long | 自增主键 |
| packageid | Long | 关联包裹 ID（`DueypackagesDO`） |
| inventoryitemid | Long | 关联物品实例 ID（`InventoryitemsDO`） |

### 4.8 FredstorageDO（fredstorage，活动/任务暂存记录）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Long | 自增主键 |
| cid | Long | 角色 ID |
| daynotes | Long | 每日记录计数值 |
| timestamp | Timestamp | 记录时间 |

## 5. 任务域

### 5.1 QueststatusDO（queststatus，角色任务状态）

| 字段 | 类型 | 含义 |
|---|---|---|
| queststatusid | Long | 自增主键 |
| characterid | Integer | 所属角色 |
| quest | Integer | 任务 ID |
| status | Integer | 任务状态（1 进行中 2 完成 3 已放弃） |
| time | Integer | 状态更新时间（秒） |
| expires | Long | 任务过期时间戳 |
| forfeited | Integer | 放弃次数 |
| completed | Integer | 完成次数 |
| info | Integer | 附加信息（自定义计数） |

关系：1:N `QuestprogressDO`（queststatusid）；1:N `MedalmapsDO`（queststatusid）。

### 5.2 QuestprogressDO（questprogress，任务进度）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Long | 自增主键 |
| characterid | Integer | 所属角色 |
| queststatusid | Long | 关联任务状态 ID（`QueststatusDO`） |
| progressid | Integer | 进度项 ID（杀怪/收集等） |
| progress | String | 进度值 |

### 5.3 QuestactionsDO（questactions，任务动作数据）

| 字段 | 类型 | 含义 |
|---|---|---|
| questactionid | Long | 自增主键 |
| questid | Integer | 任务 ID |
| status | Integer | 任务状态（同一任务不同状态的动作） |
| data | byte[] | 动作数据（序列化的 NPC 对话/奖励等） |

### 5.4 QuestrequirementsDO（questrequirements，任务完成条件数据）

| 字段 | 类型 | 含义 |
|---|---|---|
| questrequirementid | Long | 自增主键 |
| questid | Integer | 任务 ID |
| status | Integer | 任务状态 |
| data | byte[] | 条件数据（序列化的收集/杀怪/前置任务要求等） |

> `questactions`/`questrequirements` 是服务端 WZ 预处理生成的静态任务定义表，与角色无关。

## 6. 社交域

### 6.1 BuddiesDO（buddies，好友）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| characterid | Integer | 好友列表主人 |
| buddyid | Integer | 好友角色 ID |
| pending | Integer | 1 待对方确认 |
| group | String | 好友分组名 |

（V1.8.7 加了 characterid 索引。）

### 6.2 FamelogDO（famelog，人气赠送记录）

| 字段 | 类型 | 含义 |
|---|---|---|
| famelogid | Integer | 自增主键 |
| characterid | Integer | 赠送人角色 ID |
| characteridTo | Integer | 被赠送角色 ID |
| when | Timestamp | 赠送时间（同月内不可重复赠送） |

### 6.3 NotesDO（notes，系统便签）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| to | String | 收件人角色名 |
| from | String | 发件人名 |
| message | String | 内容 |
| timestamp | Long | 时间戳 |
| fame | Integer | 附加人气操作标记 |
| deleted | Integer | 是否已删除 |

### 6.4 GuildsDO（guilds，公会）

| 字段 | 类型 | 含义 |
|---|---|---|
| guildid | Long | 公会 ID（自增 PK） |
| leader | Long | 会长角色 ID |
| gp | Long | 公会 GP（贡献点） |
| logo | Long | 公会徽章图标 |
| logoColor（列 logoColor） | Integer | 徽章前景色 |
| name | String | 公会名 |
| rank1title~rank5title | String | 5 个职位名称 |
| capacity | Long | 成员上限 |
| logoBG（列 logoBG） | Long | 徽章背景图 |
| logoBGColor | Integer | 徽章背景色 |
| notice | String | 公会公告 |
| signature | Integer | 公会签名 |
| allianceId（列 allianceId） | Long | 所属联盟 ID（`AllianceDO`） |

### 6.5 AllianceDO（alliance，公会联盟）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Long | 联盟 ID（自增 PK） |
| name | String | 联盟名 |
| capacity | Long | 成员公会上限 |
| notice | String | 联盟公告 |
| rank1~rank5 | String | 联盟 5 个职位名称 |

### 6.6 AllianceguildsDO（allianceguilds，联盟-公会关联）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Long | 自增主键 |
| allianceid | Integer | 联盟 ID（`AllianceDO`） |
| guildid | Integer | 公会 ID（`GuildsDO`） |

### 6.7 BbsThreadsDO（bbs_threads，公会 BBS 帖子）

| 字段 | 类型 | 含义 |
|---|---|---|
| threadid | Long | 帖子 ID（自增 PK） |
| postercid | Long | 发帖角色 ID |
| name | String | 帖子标题 |
| timestamp | BigInteger | 发帖时间戳 |
| icon | Integer | 帖子图标 |
| replycount | Integer | 回复数 |
| startpost | String | 正文 |
| guildid | Long | 所属公会 |
| localthreadid | Long | 公会内序号 |

### 6.8 BbsRepliesDO（bbs_replies，公会 BBS 回复）

| 字段 | 类型 | 含义 |
|---|---|---|
| replyid | Long | 回复 ID（自增 PK） |
| threadid | Long | 所属帖子 ID（`BbsThreadsDO`） |
| postercid | Long | 回复角色 ID |
| timestamp | BigInteger | 回复时间戳 |
| content | String | 回复内容 |

### 6.9 MarriagesDO（marriages，婚姻）

| 字段 | 类型 | 含义 |
|---|---|---|
| marriageid | Long | 婚姻 ID（自增 PK） |
| husbandid | Long | 丈夫角色 ID |
| wifeid | Long | 妻子角色 ID |

### 6.10 RingsDO（rings，戒指配对）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 戒指 ID（自增 PK） |
| partnerRingId（列 partnerRingId） | Integer | 配对戒指 ID |
| partnerChrId（列 partnerChrId） | Integer | 配对角色 ID |
| itemid | Integer | 戒指物品 ID |
| partnername | String | 配对角色名 |

### 6.11 FamilyCharacterDO（family_character，家族成员）

| 字段 | 类型 | 含义 |
|---|---|---|
| cid | Integer | 角色 ID（PK） |
| familyid | Integer | 家族 ID |
| seniorid | Integer | 上级（长辈）角色 ID |
| reputation | Integer | 当前声望 |
| todaysrep | Integer | 今日获得声望 |
| totalreputation | Integer | 累计声望 |
| reptosenior | Integer | 已上交给长辈的声望 |
| precepts | String | 家训 |
| lastresettime | Long | 上次每日重置时间戳 |

### 6.12 FamilyEntitlementDO（family_entitlement，家族特权使用记录）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| charid | Integer | 角色 ID（`FamilyCharacterDO.cid`） |
| entitlementid | Integer | 特权 ID |
| timestamp | Long | 使用时间戳（判断是否可重复使用） |

### 6.13 NewyearDO（newyear，新年贺卡）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Long | 自增主键 |
| senderid | Integer | 发件角色 ID |
| sendername | String | 发件角色名 |
| receiverid | Integer | 收件角色 ID |
| receivername | String | 收件角色名 |
| message | String | 祝福内容 |
| senderdiscard | Boolean | 发件人已丢弃 |
| receiverdiscard | Boolean | 收件人已丢弃 |
| received | Boolean | 是否已领取 |
| timesent | Long | 发送时间戳 |
| timereceived | Long | 领取时间戳 |

### 6.14 GiftsDO（gifts，商城礼物赠送）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Long | 自增主键 |
| to | Integer | 收件人角色 ID |
| from | String | 赠送者名 |
| message | String | 赠言 |
| sn | Long | 商城商品 SN（`ModifiedCashItemDO.sn`） |
| ringid | Integer | 情侣戒指 ID（`RingsDO`） |

## 7. 商城与交易域

### 7.1 ModifiedCashItemDO（modified_cash_item，商城物品定义/覆盖表）

主键 `sn`（商城商品 SN 码）。字段：`itemId` 物品 ID、`count` 数量（Short）、`price` 价格、`bonus` 属性奖励、`priority` 优先级、`period` 有效期（天）、`maplePoint` 抵用券、`meso` 金币、`forPremiumUser` 高级用户限定、`commodityGender` 性别限制、`onSale` 是否销售、`clz`（列 class）分类、`limit` 限购、`pbCash`/`pbPoint`/`pbGift` 点数支付方式、`packageSn` 礼包 SN。

该实体是唯一含业务方法的 DO：`isSelling()` 判断是否在售；`toItem()` 将商品定义转换为 `client.inventory.Item` 实例（处理宠物创建、装备获取、按 `period`/特定 itemId 计算到期时间）；并实现 `Cloneable`。

### 7.2 SpecialcashitemsDO（specialcashitems，商城特殊物品规则）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 主键 |
| sn | Integer | 商城商品 SN |
| modifier | Integer | 修饰类型 |
| info | Integer | 修饰参数（如折扣、数量修正） |

### 7.3 WishlistsDO（wishlists，商城心愿单）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| charid | Integer | 角色 ID |
| sn | Integer | 心愿商品 SN |

### 7.4 NxcodeDO（nxcode，NX 兑换码）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| code | String | 兑换码 |
| retriever | String | 兑换者角色名（空为未使用） |
| expiration | BigInteger | 过期时间戳 |

### 7.5 NxcodeItemsDO（nxcode_items，兑换码奖励明细）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| codeid | Integer | 关联兑换码 ID（`NxcodeDO`） |
| type | Integer | 奖励类型（0 NX / 1 枫点 / 2 物品等） |
| item | Integer | 物品 ID（type 为物品时） |
| quantity | Integer | 数量 |

### 7.6 NxcouponsDO（nxcoupons，NX 双倍券时段配置）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| couponid | Integer | 券 ID |
| rate | Integer | 倍率 |
| activeday | Integer | 生效日（按位与判断星期，位掩码） |
| starthour | Integer | 生效开始小时 |
| endhour | Integer | 生效结束小时 |

### 7.7 MtsItemsDO（mts_items，MTS 交易行物品快照）

字段极多（47 列），自增主键 `id`。交易信息：`tab`（分类页）、`type`（栏类型）、`itemid`（此处为 `inventoryitems` 实例 ID）、`quantity`、`seller`（卖家角色 ID）、`price`、`bidIncre`（竞价增幅）、`buyNow`（一口价）、`position`、`transfer`（是否已转出）、`sellername`、`sellEnds`（截止时间串）；装备属性快照：`isequip`、`upgradeslots`、`level`、`itemlevel`、`itemexp`（Long）、`ringid`、`str/dex/inte/luk`、`hp/mp`、`watk/matk`、`wdef/mdef`、`acc/avoid/hands`、`speed/jump`、`locked`、`vicious`（Long）、`flag`（Long）、`expiration`（Long）、`owner`、`giftFrom`。

### 7.8 MtsCartDO（mts_cart，MTS 购物车）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| cid | Integer | 角色 ID |
| itemid | Integer | MTS 商品 ID（`MtsItemsDO.id`） |

### 7.9 ShopsDO（shops，NPC 商店）

| 字段 | 类型 | 含义 |
|---|---|---|
| shopid | Long | 商店 ID（自增 PK） |
| npcid | Integer | 开店的 NPC ID |

### 7.10 ShopitemsDO（shopitems，商店商品）

| 字段 | 类型 | 含义 |
|---|---|---|
| shopitemid | Long | 自增主键 |
| shopid | Long | 所属商店 ID（`ShopsDO`） |
| itemid | Integer | 物品 ID |
| price | Integer | 售价 |
| pitch | Integer | 购买所需徽章数（pitch） |
| position | Integer | 排列位置 |

### 7.11 GachaponRewardPoolDO（gachapon_reward_pool，转蛋奖池）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| name | String | 奖池名称（V1.4.2 补充） |
| gachaponId | Integer | 绑定转蛋机 ID |
| gachaponName | String | 转蛋机名称（`@Column(ignore=true)` 非库字段，查询时手工填充） |
| weight | Integer | 权重（多奖池抽取权重） |
| isPublic | Boolean | 是否公共奖池 |
| prob | Integer | 概率 |
| startTime / endTime | LocalDateTime | 奖池开放起止时间 |
| notification | Boolean | 中奖是否喇叭通知 |
| comment | String | 备注 |

### 7.12 GachaponRewardDO（gachapon_reward，转蛋奖池奖励项）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| poolId | Integer | 所属奖池 ID（`GachaponRewardPoolDO`） |
| itemId | Integer | 奖品物品 ID |
| itemName | String | 物品名（`@Column(ignore=true)` 非库字段） |
| quantity | Short | 单次抽取数量 |
| createTime | LocalDateTime | 创建时间 |
| comment | String | 备注 |

## 8. 游戏世界域（静态数据 + 玩法日志）

### 8.1 DropDataDO（drop_data，怪物掉落表）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Long | 自增主键 |
| dropperid | Integer | 掉落者 ID（怪物） |
| itemid | Integer | 掉落物品 ID |
| minimumQuantity / maximumQuantity | Integer | 数量区间 |
| questid | Integer | 关联任务 ID（任务专用掉落，0 为普通掉落） |
| chance | Integer | 掉率（分母 900000） |

### 8.2 DropDataGlobalDO（drop_data_global，全局掉落表）

字段同 `DropDataDO` 但以 `continent`（大洲/世界编号）替代 `dropperid`，另有 `comments` 备注。

### 8.3 ReactordropsDO（reactordrops，反应堆掉落表）

| 字段 | 类型 | 含义 |
|---|---|---|
| reactordropid | Long | 自增主键 |
| reactorid | Integer | 反应堆 ID |
| itemid | Integer | 掉落物品 ID |
| chance | Integer | 掉率 |
| questid | Integer | 关联任务 ID |

### 8.4 MonstercarddataDO（monstercarddata，怪物卡对应关系）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| cardid | Integer | 怪物卡片 ID（对应 `MonsterbookDO.cardid`） |
| mobid | Integer | 对应怪物 ID |

### 8.5 EventstatsDO（eventstats，活动统计）

| 字段 | 类型 | 含义 |
|---|---|---|
| characterid | Long | 角色 ID（PK） |
| name | Integer | 活动/统计项名 |
| info | Integer | 统计值（如击杀数） |

### 8.6 BosslogDailyDO（bosslog_daily，Boss 每日击杀记录）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| characterid | Integer | 角色 ID |
| bosstype | String | Boss 类型标识 |
| attempttime | Timestamp | 挑战时间 |

### 8.7 BosslogWeeklyDO（bosslog_weekly，Boss 每周击杀记录）

结构与 `BosslogDailyDO` 完全相同，按周清理限制。

### 8.8 MakercreatedataDO（makercreatedata，制造系统-装备合成）

联合主键 `id`+`itemid`。字段：`reqLevel` 需求等级、`reqMakerLevel` 需求制造等级、`reqMeso` 需求金币、`reqItem` 需求材料物品、`reqEquip` 需求基础装备、`catalyst` 触媒物品、`quantity` 产出数量、`tuc` 可升级次数。

### 8.9 MakerreagentdataDO（makerreagentdata，制造系统-强化剂）

主键 `itemid`。字段：`stat` 强化的属性名、`value` 强化值。

### 8.10 MakerrecipedataDO（makerrecipedata，制造系统-配方材料）

联合主键 `itemid`+`reqItem`。字段：`count` 材料需求量。

### 8.11 MakerrewarddataDO（makerrewarddata，制造系统-奖励产出）

联合主键 `itemid`+`rewardid`。字段：`quantity` 数量、`prob` 概率。

### 8.12 PlayernpcsDO（playernpcs，玩家 NPC）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| name | String | NPC 名（取自上榜角色名） |
| hair / face / skin / gender | Integer | 外观 |
| x / cy / fh / rx0 / rx1 | Integer | 出生坐标与范围 |
| world / map / dir | Integer | 世界 / 地图 / 朝向 |
| scriptid | Integer | 关联脚本 ID |
| worldrank / overallrank / worldjobrank / job | Integer | 上榜时的排名与职业 |

### 8.13 PlayernpcsEquipDO（playernpcs_equip，玩家 NPC 穿戴装备）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| npcid | Integer | 玩家 NPC ID（`PlayernpcsDO`） |
| equipid | Integer | 装备物品 ID |
| type | Integer | 装备类别 |
| equippos | Short | 穿戴部位 |

### 8.14 PlayernpcsFieldDO（playernpcs_field，玩家 NPC 分布槽位）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| world / map | Integer | 世界 / 地图 |
| step / podium | Integer | 分布步号 / 台座号 |

### 8.15 PlifeDO（plife，地图自定义生命周期对象）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Long | 自增主键 |
| world / map | Integer | 世界 / 地图 |
| life | Integer | 生命周期对象 ID（NPC 或怪物） |
| type | String | "n"（NPC）/"m"（怪物） |
| cy / f / fh / rx0 / rx1 / x / y | Integer | 坐标、脚手架、活动范围 |
| hide | Integer | 是否隐藏 |
| mobtime | Integer | 怪物刷新间隔 |
| team | Integer | 队伍编号（PQ 用） |

## 9. 运维与配置域

### 9.1 GameConfigDO（game_config，动态游戏参数表）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Long | 自增主键 |
| configType | String | 参数类型（type，如 world/server） |
| configSubType | String | 参数子类型（subType，如世界 ID） |
| configClazz | String | 参数值 Java 类型 |
| configCode | String | 参数名（如 exp_rate） |
| configValue | String | 参数值 |
| configDesc | String | 参数说明 |
| updateTime | Date | 更新时间 |

由 `org.gms.config.GameConfig` 启动时整表加载为 `type→subType→code→{value,clazz}` JSON 树，支持热更新。后续多个 `V1.8.x/V1.11.x` 迁移脚本向该表插入参数。

### 9.2 LangResourcesDO（lang_resources，数据库 i18n 表）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Long | 自增主键 |
| langType | String | 语言类型（zh-CN / en-US） |
| langBase | String | 预留，区分同 code 不同 value |
| langCode | String | i18n 编码 |
| langValue | String | i18n 值 |
| langExtend | String | 预留扩展字段 |

### 9.3 CommandInfoDO（command_info，GM 指令配置）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| level | Integer | 指令等级 0-6（可调整） |
| syntax | String | 指令语法 |
| defaultLevel | Integer | 默认指令等级（不可修改，用于还原） |
| clazz | String | 指令处理类 |
| enabled | boolean | 是否启用 |

### 9.4 AutobanConfigDO（autoban_config，自动封禁配置表）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| type | String | 封禁类型，对应 AutobanFactory 枚举名（唯一键） |
| disabled | Boolean | 是否禁用该类型检测 |
| points | Integer | 触发封禁所需积分（NULL 用枚举默认值） |
| expireTime | Long | 积分过期时间（毫秒，NULL 用枚举默认值） |
| description | String | 描述说明 |
| createTime / updateTime | Date | 创建/更新时间 |

### 9.5 ServerQueueDO（server_queue，服务器异步执行队列）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Integer | 自增主键 |
| accountid | Integer | 账号 ID |
| characterid | Integer | 角色 ID |
| type | Integer | 队列任务类型 |
| value | Integer | 任务参数 |
| message | String | 附加消息 |
| createTime（列 createTime） | Timestamp | 入队时间 |

### 9.6 ResponsesDO（responses，玩家喊话自动回应）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Long | 自增主键 |
| chat | String | 触发喊话内容 |
| response | String | 回应内容 |

### 9.7 ReportsDO（reports，玩家举报）

| 字段 | 类型 | 含义 |
|---|---|---|
| id | Long | 自增主键 |
| reporttime | Timestamp | 举报时间 |
| reporterid | Integer | 举报人角色 ID |
| victimid | Integer | 被举报人角色 ID |
| reason | Integer | 举报原因编号 |
| chatlog | String | 聊天记录 |
| description | String | 补充描述 |

### 9.8 FlywaySchemaHistoryDO（flyway_schema_history，Flyway 迁移历史）

| 字段 | 类型 | 含义 |
|---|---|---|
| installedRank | Integer | 安装序号（PK） |
| version | String | 版本号 |
| description | String | 描述 |
| type | String | 类型（SQL/JDBC 等） |
| script | String | 脚本文件名 |
| checksum | Integer | 校验和 |
| installedBy | String | 执行用户 |
| installedOn | Timestamp | 执行时间 |
| executionTime | Integer | 执行耗时（ms） |
| success | Boolean | 是否成功 |

## 10. 实体关系总览（核心链路）

- 账号链：`AccountsDO` →(accountid)→ `CharactersDO` / `StoragesDO` / `InventoryitemsDO`（现金）/ `QuickslotkeymappedDO`；`AccountsDO` ↔(accountid+hwid)→ `HwidaccountsDO` →(hwid)→ `HwidbansDO`。
- 角色链：`CharactersDO` →(characterid)→ `SkillsDO`、`SkillmacrosDO`、`CooldownsDO`、`KeymapDO`、`QueststatusDO`、`BuddiesDO`、`SavedlocationsDO`、`TrocklocationsDO`、`AreaInfoDO`、`PlayerdiseasesDO`、`MonsterbookDO`、`MedalmapsDO`、`HpMpAlertDO`、`BosslogDaily/WeeklyDO`、`FamelogDO`、`SkillmacrosDO`、`WishlistsDO`、`ServerQueueDO` 等。
- 物品链：`InventoryitemsDO` →(inventoryitemid)→ `InventoryequipmentDO`（装备属性）；→(petid)→ `PetsDO` →(petid)→ `PetignoresDO`；快递：`DueypackagesDO` →(packageid)→ `DueyitemsDO` →(inventoryitemid)→ `InventoryitemsDO`；商店摆摊：`InventorymerchantDO`；MTS：`MtsItemsDO`（快照物品属性）→(id)→ `MtsCartDO`。
- 任务链：静态定义 `QuestactionsDO`/`QuestrequirementsDO`（按 questid+status）；运行时 `QueststatusDO` →(queststatusid)→ `QuestprogressDO`、`MedalmapsDO`。
- 公会链：`GuildsDO` →(guildid)→ `CharactersDO`（guildid 字段）/ `BbsThreadsDO` →(threadid)→ `BbsRepliesDO`；`AllianceDO` →(allianceid)→ `AllianceguildsDO` →(guildid)→ `GuildsDO`。
- 家族链：`FamilyCharacterDO`（cid 即角色 ID，seniorid 自关联）→(charid)→ `FamilyEntitlementDO`。
- 婚链：`MarriagesDO`（husbandid/wifeid→`CharactersDO`）+ `RingsDO`（partnerChrId/partnerRingId 自关联）+ `CharactersDO.partnerId`。
- 商城链：`ModifiedCashItemDO`（sn）→ `SpecialcashitemsDO`（sn）/ `GiftsDO`（sn）/ `WishlistsDO`（sn）。
- 商店链：`ShopsDO`（npcid）→(shopid)→ `ShopitemsDO`。
- 转蛋链：`GachaponRewardPoolDO`（gachaponId 绑定转蛋机）→(poolId)→ `GachaponRewardDO`。
- 制造链：`MakercreatedataDO`（itemid）/ `MakerrecipedataDO`（itemid+reqItem）/ `MakerrewarddataDO`（itemid+rewardid）/ `MakerreagentdataDO`（itemid）按 itemid 关联。

> 除上述逻辑外键外，数据库层未建立物理外键（OdinMS 系传统），一致性由服务端代码保证。
