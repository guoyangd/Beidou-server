# DAO 层 Mapper 详细设计（org.gms.dao.mapper）

- **模块路径**：`gms-server/src/main/java/org/gms/dao/mapper`
- **类数量**：84 个 Mapper 接口（与 `org.gms.dao.entity` 的 84 个 DO 实体一一对应）
- **依赖模块**：MyBatis-Flex（`com.mybatis.flex.core.BaseMapper<T>`，提供 `insert/deleteById/update/selectOneByEntityProperty/selectListByCondition/paginate` 等全套通用 CRUD）；自定义方法使用 MyBatis 原生注解 `@Select/@Update/@Insert/@Delete` + `@Param`。启动时由 `@MapperScan("org.gms.dao.mapper")` 扫描注册，上层由 `dao/*Dao` 或 `service`/`manager` 层调用
- **生成方式**：多数 Mapper 由 `CodeGen#genMapperAndEntity`（test 作用域，mybatis-flex-codeen）生成，因此默认无任何自定义方法，仅继承 `BaseMapper<XXXDO>`；少数按需手写了注解 SQL

## 1. Mapper 总表（84 个）

统计结论：**仅 6 个 Mapper 含自定义方法**（AccountsMapper、CharactersMapper、ExtendValueMapper、NxcodeMapper、NxcodeItemsMapper、NxcouponsMapper，共 9 个自定义方法），其余 78 个均为空的 `BaseMapper` 继承接口。

| # | Mapper | 对应实体（表） | 自定义方法 | # | Mapper | 对应实体（表） | 自定义方法 |
|---|---|---|---|---|---|---|---|
| 1 | AccountsMapper | AccountsDO（accounts） | **有（3 个）** | 43 | MacfiltersMapper | MacfiltersDO（macfilters） | 无 |
| 2 | AllianceMapper | AllianceDO（alliance） | 无 | 44 | MakercreatedataMapper | MakercreatedataDO（makercreatedata） | 无 |
| 3 | AllianceguildsMapper | AllianceguildsDO（allianceguilds） | 无 | 45 | MakerreagentdataMapper | MakerreagentdataDO（makerreagentdata） | 无 |
| 4 | AreaInfoMapper | AreaInfoDO（area_info） | 无 | 46 | MakerrecipedataMapper | MakerrecipedataDO（makerrecipedata） | 无 |
| 5 | AutobanConfigMapper | AutobanConfigDO（autoban_config） | 无 | 47 | MakerrewarddataMapper | MakerrewarddataDO（makerrewarddata） | 无 |
| 6 | BbsRepliesMapper | BbsRepliesDO（bbs_replies） | 无 | 48 | MarriagesMapper | MarriagesDO（marriages） | 无 |
| 7 | BbsThreadsMapper | BbsThreadsDO（bbs_threads） | 无 | 49 | MedalmapsMapper | MedalmapsDO（medalmaps） | 无 |
| 8 | BosslogDailyMapper | BosslogDailyDO（bosslog_daily） | 无 | 50 | ModifiedCashItemMapper | ModifiedCashItemDO（modified_cash_item） | 无 |
| 9 | BosslogWeeklyMapper | BosslogWeeklyDO（bosslog_weekly） | 无 | 51 | MonstercarddataMapper | MonstercarddataDO（monstercarddata） | 无 |
| 10 | BuddiesMapper | BuddiesDO（buddies） | 无 | 52 | MonsterbookMapper | MonsterbookDO（monsterbook） | 无 |
| 11 | CharactersMapper | CharactersDO（characters） | **有（2 个）** | 53 | MtsCartMapper | MtsCartDO（mts_cart） | 无 |
| 12 | CommandInfoMapper | CommandInfoDO（command_info） | 无 | 54 | MtsItemsMapper | MtsItemsDO（mts_items） | 无 |
| 13 | CooldownsMapper | CooldownsDO（cooldowns） | 无 | 55 | NamechangesMapper | NamechangesDO（namechanges） | 无 |
| 14 | DropDataMapper | DropDataDO（drop_data） | 无 | 56 | NewyearMapper | NewyearDO（newyear） | 无 |
| 15 | DropDataGlobalMapper | DropDataGlobalDO（drop_data_global） | 无 | 57 | NotesMapper | NotesDO（notes） | 无 |
| 16 | DueyitemsMapper | DueyitemsDO（dueyitems） | 无 | 58 | NxcouponsMapper | NxcouponsDO（nxcoupons） | **有（1 个）** |
| 17 | DueypackagesMapper | DueypackagesDO（dueypackages） | 无 | 59 | NxcodeMapper | NxcodeDO（nxcode） | **有（1 个）** |
| 18 | EventstatsMapper | EventstatsDO（eventstats） | 无 | 60 | NxcodeItemsMapper | NxcodeItemsDO（nxcode_items） | **有（1 个）** |
| 19 | ExtendValueMapper | ExtendValueDO（extend_value） | **有（1 个）** | 61 | PetignoresMapper | PetignoresDO（petignores） | 无 |
| 20 | FamelogMapper | FamelogDO（famelog） | 无 | 62 | PetsMapper | PetsDO（pets） | 无 |
| 21 | FamilyCharacterMapper | FamilyCharacterDO（family_character） | 无 | 63 | PlayerdiseasesMapper | PlayerdiseasesDO（playerdiseases） | 无 |
| 22 | FamilyEntitlementMapper | FamilyEntitlementDO（family_entitlement） | 无 | 64 | PlayernpcsMapper | PlayernpcsDO（playernpcs） | 无 |
| 23 | FlywaySchemaHistoryMapper | FlywaySchemaHistoryDO（flyway_schema_history） | 无 | 65 | PlayernpcsEquipMapper | PlayernpcsEquipDO（playernpcs_equip） | 无 |
| 24 | FredstorageMapper | FredstorageDO（fredstorage） | 无 | 66 | PlayernpcsFieldMapper | PlayernpcsFieldDO（playernpcs_field） | 无 |
| 25 | GachaponRewardMapper | GachaponRewardDO（gachapon_reward） | 无 | 67 | PlifeMapper | PlifeDO（plife） | 无 |
| 26 | GachaponRewardPoolMapper | GachaponRewardPoolDO（gachapon_reward_pool） | 无 | 68 | QuestactionsMapper | QuestactionsDO（questactions） | 无 |
| 27 | GameConfigMapper | GameConfigDO（game_config） | 无 | 69 | QuestprogressMapper | QuestprogressDO（questprogress） | 无 |
| 28 | GiftsMapper | GiftsDO（gifts） | 无 | 70 | QuestrequirementsMapper | QuestrequirementsDO（questrequirements） | 无 |
| 29 | GuildsMapper | GuildsDO（guilds） | 无 | 71 | QueststatusMapper | QueststatusDO（queststatus） | 无 |
| 30 | HpMpAlertMapper | HpMpAlertDO（hp_mp_alert） | 无 | 72 | QuickslotkeymappedMapper | QuickslotkeymappedDO（quickslotkeymapped） | 无 |
| 31 | HwidaccountsMapper | HwidaccountsDO（hwidaccounts） | 无 | 73 | ReactordropsMapper | ReactordropsDO（reactordrops） | 无 |
| 32 | HwidbansMapper | HwidbansDO（hwidbans） | 无 | 74 | ReportsMapper | ReportsDO（reports） | 无 |
| 33 | InventoryequipmentMapper | InventoryequipmentDO（inventoryequipment） | 无 | 75 | ResponsesMapper | ResponsesDO（responses） | 无 |
| 34 | InventoryitemsMapper | InventoryitemsDO（inventoryitems） | 无 | 76 | RingsMapper | RingsDO（rings） | 无 |
| 35 | InventorymerchantMapper | InventorymerchantDO（inventorymerchant） | 无 | 77 | SavedlocationsMapper | SavedlocationsDO（savedlocations） | 无 |
| 36 | IpbansMapper | IpbansDO（ipbans） | 无 | 78 | ServerQueueMapper | ServerQueueDO（server_queue） | 无 |
| 37 | KeymapMapper | KeymapDO（keymap） | 无 | 79 | ShopitemsMapper | ShopitemsDO（shopitems） | 无 |
| 38 | LangResourcesMapper | LangResourcesDO（lang_resources） | 无 | 80 | ShopsMapper | ShopsDO（shops） | 无 |
| 39 | MacbansMapper | MacbansDO（macbans） | 无 | 81 | SkillmacrosMapper | SkillmacrosDO（skillmacros） | 无 |
| 40 | —（接左栏） | | | 82 | SkillsMapper | SkillsDO（skills） | 无 |
| 41 | SpecialcashitemsMapper | SpecialcashitemsDO（specialcashitems） | 无 | 83 | StoragesMapper | StoragesDO（storages） | 无 |
| 42 | TrocklocationsMapper | TrocklocationsDO（trocklocations） | 无 | 84 | WishlistsMapper / WorldtransfersMapper | WishlistsDO / WorldtransfersDO | 无 |

> 注：表中第 40 行为排版占位；实际 84 个 Mapper 即左侧 1-39、41-43 与右侧全部。无 XML 映射文件（`resources` 下无 mapper XML），全部注解 SQL 或 BaseMapper 内置方法。

## 2. 含自定义方法的 Mapper 详解

### 2.1 AccountsMapper（accounts 表）

```java
public interface AccountsMapper extends BaseMapper<AccountsDO> {
    @Update("UPDATE accounts SET loggedin = #{value}")
    void updateAllLoggedIn(Integer value);

    @Select("SELECT * FROM accounts WHERE name = #{name}")
    AccountsDO selectOneByName(String name);

    @Insert("INSERT INTO accounts(name, password, birthday, tempban, language) VALUES (#{name}, #{password}, #{birthday}, #{tempban}, #{language})")
    void addAccount(AccountsDO accountsDO);
}
```

| 方法 | 注解 | SQL | 说明 |
|---|---|---|---|
| `updateAllLoggedIn(Integer value)` | `@Update` | `UPDATE accounts SET loggedin = #{value}` | **全表更新**登录状态：服务器启动/关闭时把所有账号的 `loggedin` 重置为 0，清理上次异常退出遗留的"假在线"状态 |
| `selectOneByName(String name)` | `@Select` | `SELECT * FROM accounts WHERE name = #{name}` | 按账号名查唯一账号（登录流程、重名校验）；等价于 BaseMapper 的按属性查询，此处显式写 SQL |
| `addAccount(AccountsDO accountsDO)` | `@Insert` | 仅插入 `name/password/birthday/tempban/language` 5 列 | 注册新账号，其余列走数据库默认值；参数复用实体（MyBatis 按 `#{属性名}` 取值） |

### 2.2 CharactersMapper（characters 表）

```java
public interface CharactersMapper extends BaseMapper<CharactersDO> {
    @Update("UPDATE characters SET HasMerchant = #{value}")
    void updateAllHasMerchant(Integer value);

    @Select("SELECT id, world FROM characters WHERE accountid = #{accountId}")
    List<CharactersDO> selectIdAndWorldListByAccountId(int accountId);
}
```

| 方法 | 注解 | SQL | 说明 |
|---|---|---|---|
| `updateAllHasMerchant(Integer value)` | `@Update` | `UPDATE characters SET HasMerchant = #{value}` | **全表更新**精灵商店标志：启动时重置所有角色的"开店中"状态 |
| `selectIdAndWorldListByAccountId(int accountId)` | `@Select` | `SELECT id, world FROM characters WHERE accountid = #{accountId}` | 按账号查角色 ID+世界列表（登录时列角色、判断世界频道进入合法性），只取 2 列减少传输 |

### 2.3 ExtendValueMapper（extend_value 表）

```java
public interface ExtendValueMapper extends BaseMapper<ExtendValueDO> {
    @Delete("delete from extend_value where extend_type = #{extendType} and create_time < #{createTime}")
    void clean(@Param("extendType") String extendType, @Param("createTime") Date createTime);
}
```

| 方法 | 说明 |
|---|---|
| `clean(String extendType, Date createTime)`（`@Delete`） | 按 `extend_type` 与 `create_time` 阈值清理过期扩展字段记录（日清/周清类型的回收）。多参数用 `@Param` 显式命名（否则 MyBatis 无法确定 `#{extendType}` 取值） |

### 2.4 NxcodeMapper（nxcode 表）

```java
@Delete("DELETE FROM nxcode WHERE expiration <= #{timeClear}")
void clearExpirations(long timeClear);
```

| 方法 | 说明 |
|---|---|
| `clearExpirations(long timeClear)`（`@Delete`） | 删除所有已过期（`expiration <= timeClear`）的 NX 兑换码，配合定时任务清理 |

### 2.5 NxcodeItemsMapper（nxcode_items 表）

```java
@Delete("DELETE FROM nxcode_items WHERE codeid IN (SELECT id FROM nxcode WHERE expiration <= #{timeClear})")
void clearExpirations(long timeClear);
```

| 方法 | 说明 |
|---|---|
| `clearExpirations(long timeClear)`（`@Delete`） | **子查询关联删除**：先查出所有过期兑换码的 id，再删除其奖励明细，与 `NxcodeMapper.clearExpirations` 成对调用（先清明细再清主码，避免孤儿记录） |

### 2.6 NxcouponsMapper（nxcoupons 表）

```java
@Select("SELECT couponid FROM nxcoupons WHERE (activeday & #{weekDay}) = #{weekDay} AND starthour <= #{hourDay} AND endhour > #{hourDay}")
List<Integer> selectActiveCouponIds(@Param("weekDay") int weekDay, @Param("hourDay") int hourDay);
```

| 方法 | 说明 |
|---|---|
| `selectActiveCouponIds(int weekDay, int hourDay)`（`@Select`） | 查询当前生效的 NX 双倍券 ID 列表：`activeday` 是星期位掩码，用按位与 `(activeday & #{weekDay}) = #{weekDay}` 判断当日是否生效，再限定 `starthour <= 当前小时 < endhour`；返回 `couponid` 列表供商店结算时计算倍率 |

## 3. 无自定义方法 Mapper 的通用说明

其余 78 个 Mapper 形如：

```java
public interface XxxMapper extends BaseMapper<XxxDO> {
}
```

所有 CRUD 均来自 MyBatis-Flex `BaseMapper<T>`，常用方法包括（以实际调用为准）：

- 插入：`insert(entity)`、`insertSelective(entity)`、`insertBatch(entities)`
- 删除：`deleteById(id)`、`deleteByCondition(QueryWrapper)`
- 更新：`update(entity)`、`updateByMapEntity`/`updateByQuery`
- 查询：`selectOneById(id)`、`selectListByCondition(...)`、`selectListByQuery(...)`、`selectCountByCondition(...)`、`paginate(page, size, query)` 分页

条件构造统一使用 `QueryWrapper` + `org.gms.dao.entity.table.XxxTableDef`（CodeGen 同步生成的表定义常量类）或 Lambda 风格；联合主键实体（如 `MonsterbookDO`、`HwidaccountsDO`、`Maker*DO`、`ExtendValueDO`）通过 `selectOneByMultiId` 或按条件查询定位行。

## 4. 使用规范（摘自 CLAUDE.md 约定）

- 新增表结构走新版本号 Flyway 迁移脚本（`src/main/resources/db/migration/V*__*.sql`）。
- 生成实体/Mapper：运行 test 作用域 `CodeGen#genMapperAndEntity`，改 `globalConfig.setGenerateTable(...)` 指定表名，实体后缀固定 `DO`。
- Mapper 包必须保持在 `org.gms.dao.mapper`（`@MapperScan` 扫描范围）。
- 简单单表 CRUD 优先使用 `BaseMapper` 通用方法 + `QueryWrapper`；确有跨表清理、位运算判断、全表重置等通用方法无法表达的逻辑时，才在 Mapper 上追加注解 SQL（现有 9 个自定义方法即此类场景）。
