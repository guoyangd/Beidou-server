# 详细设计文档 — 数据模型（org.gms.model：dto 39 + pojo 7）

- **模块路径**：`gms-server/src/main/java/org/gms/model/dto/`、`gms-server/src/main/java/org/gms/model/pojo/`
- **类数量**：DTO 39 个、POJO 7 个
- **依赖模块**：
  - Lombok（`@Data`/`@Getter/@Setter`/`@Builder`/`@SuperBuilder`/`@AllArgsConstructor`/`@NoArgsConstructor`）
  - Jackson（`@JsonProperty`/`@JsonFormat`/`@JsonInclude`，HTTP 序列化）；fastjson2（`ResultBody` 内部解析请求体）
  - MyBatis-Flex（`@Column`，个别 DTO 复用为查询条件）；Swagger v3 注解（`@Schema`，仅 `ServerShutdownDTO`）
  - `org.gms.dao.entity.*DO`（`CashShopBatchOnSaleReqDTO` 内嵌 `ModifiedCashItemDO`、`GachaponPoolSearchRtnDTO` 继承 `GachaponRewardPoolDO`）
  - `org.gms.client.inventory.Item/Equip`（`InventorySearchRtnDTO.toItem()`）
- **通用约定**：
  - 分页入参统一继承 `BasePageDTO`（`@SuperBuilder`），分页出参统一用 MyBatis-Flex `Page<T>`（DTO 不承载出参分页字段）。
  - DTO 命名：`*ReqDTO` 请求、`*RtnDTO` 返回；下划线/缩写字段（`_int` 对应 Java 关键字 `int`）通过 `@JsonProperty("int")` 或 `@Column("int")` 与 JSON/DB 列对齐。
  - Lombok 生成 getter/setter/equals/hashCode/toString/Builder，除特别说明外方法表不再罗列。

---

# 一、DTO（org.gms.model.dto，39 个）

## 1. 通用协议与信封

### ResultBody

> 源码：`dto/ResultBody.java`。REST 统一响应体 `ResultBody<T>`（`code/message/responseId/data`），成功码 `20000`。

| 字段 | 类型 | 说明 |
|---|---|---|
| code | Integer | 业务码（`BizExceptionEnum.SUCCESS` 等） |
| message | String | 提示消息 |
| responseId | String | 响应追踪 id（POST 回填请求 requestId，否则 UUID） |
| data | T | 业务数据 |

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `ResultBody()` | 无参构造 | — |
| `ResultBody(BaseErrorInfoInterface errorInfo)` | 错误码构造 | 取 code/message |
| `static <T> ResultBody<T> success()` | 空数据成功响应 | 委托 `success(null)` |
| `static <T> ResultBody<T> success(T data)` | 成功响应 | UUID 作 responseId，code/message 取 SUCCESS 枚举 |
| `static <T> ResultBody<T> success(SubmitBody<?> request, T data)` | 成功响应（回填请求 id） | responseId 取 `request.getRequestId()`（前后端追踪对齐） |
| `static <T> ResultBody<T> error(HttpServletRequest req, BaseErrorInfoInterface errorInfo)` | 错误响应（枚举） | 委托三参重载 |
| `static <T> ResultBody<T> error(HttpServletRequest req, String message)` | 错误响应（文案） | code=-1 |
| `static <T> ResultBody<T> error(HttpServletRequest req, Integer code, String message)` | 错误响应（全参） | POST+JSON 时读请求体 fastjson2 反解 `SubmitBody` 取 requestId 回填（失败回退 UUID）；GET 等直接 UUID；data 置 null |
| `String toString()` | 序列化 | fastjson2 `toJSONString` |

### SubmitBody

> 源码：`dto/SubmitBody.java`。POST 请求统一信封（前端拦截器自动包装）。

| 字段 | 类型 | 说明 |
|---|---|---|
| requestId | String | 请求追踪 id（前端 uuid） |
| data | T | 实际业务入参 |

### BasePageDTO

> 源码：`dto/BasePageDTO.java`。分页入参基类（`@SuperBuilder`），所有 `*ReqDTO` 分页查询的父类；出参不用它而用 `Page`。

| 字段 | 类型 | 说明 |
|---|---|---|
| pageNo | Integer | 页码 |
| pageSize | Integer | 每页条数 |
| onlyTotal | boolean | 只统计总数 |
| notPage | boolean | 不分页 |

## 2. 账号与认证

### AddAccountDTO

> 源码：`dto/AddAccountDTO.java`。新增账号入参（`AccountService.addAccount`）。

| 字段 | 类型 | 说明 |
|---|---|---|
| name | String | 账号名 |
| password | String | 明文密码（服务端加密） |
| birthday | Date | 生日，`@JsonFormat(pattern="yyyy-MM-dd")` |
| language | Integer | 语言（必填，防 swagger 直调） |

### UpdateAccountByUserDTO

> 源码：`dto/UpdateAccountByUserDTO.java`。用户自助改资料入参。

| 字段 | 类型 | 说明 |
|---|---|---|
| oldPwd | String | 旧密码（校验用） |
| newPwd | String | 新密码（≥6 位才生效） |
| pin / pic | String | 安全码 / 二级密码 |
| birthday | Date | 生日（`@JsonFormat yyyy-MM-dd`） |
| nick / email | String | 昵称 / 邮箱 |
| language | Integer | 语言 |

### UpdateAccountByGmDTO

> 源码：`dto/UpdateAccountByGmDTO.java`。GM 改账号入参（高权限字段全集）。

| 字段 | 类型 | 说明 |
|---|---|---|
| newPwd / pin / pic | String | 密码与安全码 |
| birthday | Date | 生日 |
| nxCredit / maplePoint / nxPrepaid | Integer | 三种点券货币（`@Column` 对齐列名） |
| characterslots | Integer | 角色槽位 |
| gender | Integer | 性别 |
| webadmin | Integer | 后台管理员标记 |
| nick / mute / email | String/Integer | 昵称 / 禁言 / 邮箱 |
| rewardpoints / votepoints | Integer | 奖励/投票点 |
| language | Integer | 语言 |

## 3. 角色与在线列表

### ChrOnlineListReqDTO

> 源码：`dto/ChrOnlineListReqDTO.java`。继承 `BasePageDTO`；在线角色查询条件。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Integer | 角色 id |
| name | String | 角色名（模糊） |
| map | Integer | 地图 id |
| world | int | 世界 id（必传） |

### ChrOnlineListRtnDTO

> 源码：`dto/ChrOnlineListRtnDTO.java`。在线角色列表项。

| 字段 | 类型 | 说明 |
|---|---|---|
| world / id / map / job / level / gm | int | 世界、角色、地图、职业、等级、GM 等级 |
| name / jobName | String | 角色名、职业名 |

### CharacterListItemDTO

> 源码：`dto/CharacterListItemDTO.java`。账号下角色列表项（GM 后台展示）。

| 字段 | 类型 | 说明 |
|---|---|---|
| id / job / level / world / gm / meso / fame / guildid | Integer | 角色 id、职业、等级、世界、GM、金币、人气、公会 |
| name / jobName / worldName | String | 名称类 |
| createdate / lastLogoutTime | Timestamp | 创建时间、最后登出 |
| online | boolean | 是否在线 |

### GiveResourceReqDTO

> 源码：`dto/GiveResourceReqDTO.java`。后台发放资源入参（GiveService）；数值属性字段用 `@JsonProperty` 对齐前端命名（`pAtk/mAtk/pDef/mDef`），`_int` 映射 JSON 键 `int`。

| 字段 | 类型 | 说明 |
|---|---|---|
| worldId / playerId | Integer | 目标世界/角色（playerId=0 表示全服在线） |
| player | String | 玩家名（辅助） |
| type | Byte | 资源类型（0~13，见 GiveService） |
| id | Integer | 物品/装备 id |
| quantity | Integer | 数量（天数/等级/地图 id 复用） |
| rate | Float | 倍率值 |
| str/dex/_int/luk/hp/mp | Short | 装备六维（`_int`=@JsonProperty("int")） |
| pAtk/mAtk/pDef/mDef/acc/avoid/hands/speed/jump | Short | 装备攻防命回等（各带 @JsonProperty） |
| upgradeSlot | Byte | 砸券次数 |
| expire | Long | 有效期 |

## 4. 服务器信息

### ServerInfoReqDto

> 源码：`dto/ServerInfoReqDto.java`。在线人数查询入参。

| 字段 | 类型 | 说明 |
|---|---|---|
| worldIdList | List\<Integer> | 世界 id 列表（空则合计 0） |

### WorldListRtnDTO

> 源码：`dto/WorldListRtnDTO.java`。世界列表项（含 8 项倍率）。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Integer | 世界 id |
| expRate/dropRate/mesoRate/bossDropRate/questRate/travelRate/fishingRate | Float | 各类倍率 |

### ChannelListRtnDTO

> 源码：`dto/ChannelListRtnDTO.java`。频道列表项。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Integer | 频道 id |
| worldId | Integer | 所属世界 |

### ServerShutdownDTO

> 源码：`dto/ServerShutdownDTO.java`。关服请求参数（全字段 `@Schema` Swagger 描述）。

| 字段 | 类型 | 说明 |
|---|---|---|
| minutes | int | 多少分钟后关服（≤0 默认 1 分钟），必填 |
| shutdownMsg | String | 关服消息（空用系统默认） |
| showServerMsg | Boolean | 顶部黄色滚动通知（默认 false） |
| showCenterMsg | Boolean | 屏幕中央提示 |
| showChatMsg | Boolean | 聊天框蓝色 GM 消息 |

### CommandReqDTO

> 源码：`dto/CommandReqDTO.java`。继承 `BasePageDTO`；GM 指令查询/更新复用（id/level/syntax 为更新参数，各 List 与 enabled 为查询条件，description 支持模糊）。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Integer | 记录 id（更新用） |
| level / levelList | Integer / List\<Integer> | 当前等级（更新 / 多选查询） |
| syntax | String | 指令文本（like） |
| defaultLevel / defaultLevelList | Integer / List\<Integer> | 默认等级（不可改，查询用） |
| clazz | String | 实现类名 |
| description | String | 描述（模糊查询） |
| enabled | Boolean | 启用状态（精确） |

## 5. 配置

### ConfigTypeDTO

> 源码：`dto/ConfigTypeDTO.java`。配置类型去重列表。

| 字段 | 类型 | 说明 |
|---|---|---|
| types / subTypes | List\<String> | configType / configSubType 去重集合 |

### GameConfigReqDTO

> 源码：`dto/GameConfigReqDTO.java`。继承 `BasePageDTO`；配置分页查询条件。

| 字段 | 类型 | 说明 |
|---|---|---|
| type / subType | String | 参数类型 / 子类型 |
| filter | String | 名称、描述模糊搜索 |

### AutobanConfigDTO

> 源码：`dto/AutobanConfigDTO.java`。自动封禁配置（含枚举默认值与前端控制位）。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Integer | 自增 id |
| type | String | 封禁类型（AutobanFactory 枚举名） |
| name | String | 类型名称（i18n） |
| disabled | Boolean | 是否禁用该类型检测 |
| points | Integer | 触发积分（null 用枚举默认） |
| expireTimeSeconds | Long | 检测周期秒（库存毫秒，-1 永不过期） |
| description | String | 描述 |
| defaultPoints / defaultExpireTimeSeconds | Integer / Long | 枚举默认值（仅前端展示） |
| changePoints / changeExpireTime | boolean | 控制位：true 更新字段，false 置 null |

## 6. 点券商城

### CashShopBatchOnSaleReqDTO

> 源码：`dto/CashShopBatchOnSaleReqDTO.java`。批量上架请求。

| 字段 | 类型 | 说明 |
|---|---|---|
| data | ModifiedCashItemDO[] | 待上架商品（sn 为准） |
| type | String | 统一设置类型：价格/数量/有效期 |
| value | Integer | 统一设置值 |

### CashShopSearchRtnDTO

> 源码：`dto/CashShopSearchRtnDTO.java`。商城商品查询项；每个可覆盖字段均配 `default*` 字段保存 wz 原值（前端展示差异与还原）。

| 字段 | 类型 | 说明 |
|---|---|---|
| categoryId / categoryName / subcategoryId / subcategoryName | Integer/String×2 | 分类信息 |
| sn | Integer | 商品序列号 |
| itemId / itemName | Integer / String | 物品与名称 |
| price / period / priority / count / onSale | Integer/Long/Short | 售价、有效期、优先级、数量、上架状态 |
| bonus / maplePoint / meso / forPremiumUser / gender / clz / limit | Integer | 加成、抵用券、金币价、会员限定、性别、职业限定、限购 |
| pbCash / pbPoint / pbGift | Integer | 跑跑币相关 |
| packageSn | Integer | 套餐 SN |
| 以上每个字段对应的 defaultXxx | 同类型 | wz 基线值（defaultPrice、defaultPeriod、defaultPriority、defaultCount、defaultOnSale、defaultBonus、defaultMaplePoint、defaultMeso、defaultForPremiumUser、defaultGender、defaultClz、defaultLimit、defaultPBCash、defaultPBPoint、defaultPBGift、defaultPackageSn） |

## 7. 背包与装备

### InventoryTypeRtnDTO

> 源码：`dto/InventoryTypeRtnDTO.java`。背包栏类型项。

| 字段 | 类型 | 说明 |
|---|---|---|
| inventoryType | Byte | 栏目类型值（InventoryType） |
| name | String | 栏目名 |

### InventorySearchReqDTO

> 源码：`dto/InventorySearchReqDTO.java`。继承 `BasePageDTO`；背包查询条件（`getCharacterList` 复用作返回项）。

| 字段 | 类型 | 说明 |
|---|---|---|
| inventoryType | Byte | 背包栏类型 |
| characterId | Integer | 角色 id |
| characterName | String | 角色名（查询用） |
| onlineStatus | boolean | 在线状态（列表返回时填充） |

### InventorySearchRtnDTO

> 源码：`dto/InventorySearchRtnDTO.java`。背包物品项；内含 `toItem()` 反向转换为游戏内存对象。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | inventoryitemid（在线内存对象为 -1） |
| characterId / itemId / itemType / inventoryType | Integer/Byte | 角色、物品、物品来源类型（ItemFactory）、背包栏 |
| position / quantity | Short | 格位、数量 |
| owner / giftFrom | String | 制作者、送礼人 |
| petId | Integer | 宠物 id |
| flag / expiration | Short / Long | 标记、有效期 |
| online / equipment | boolean | 在线、是否装备（子表有数据） |
| inventoryEquipment | InventoryEquipRtnDTO | 装备详情（equipment=true 时有值） |
| itemName | String | 物品名称（按 itemId 查） |

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `Item toItem()` | DTO→游戏 `Item` 对象 | 装备：`new Equip(itemId, position)` 并逐属性 `Optional.orElse(0)` 兜底回填（upgradeSlots/level/六维/watk 等 19 项 + ringId）；非装备：`new Item(itemId, position, quantity, petId)`；公共回填 owner/expiration/giftFrom/flag |

### InventoryEquipRtnDTO

> 源码：`dto/InventoryEquipRtnDTO.java`。装备详情子对象（字段名与 inventoryequipment 列的映射见各注释）。

| 字段 | 类型 | 说明 |
|---|---|---|
| id / inventoryItemId | Long | inventoryequipmentid / 外键 inventoryitems.id |
| upgradeSlots | Byte | 砸券次数（upgradeslots） |
| level | Byte | 装备等级（level） |
| attStr/attDex/attInt/attLuk | Short | 四维（str/dex/int/luk） |
| hp / mp | Short | 血蓝 |
| pAtk / mAtk / pDef / mDef | Short | 物攻/魔攻/物防/魔防（watk/matk/wdef/mdef） |
| acc / avoid / hands / speed / jump | Short | 命中/回避/攻速/移速/跳跃 |
| locked | Integer | 锁定（locked） |
| vicious | Short | 锤子次数（vicious） |
| itemLevel / itemExp | Byte / Integer | 装备升级等级/经验 |
| ringId | Integer | 戒指 id（ringid） |

### InventoryequipmentReqDTO / InventoryequipmentRtnDTO

> 源码：`dto/InventoryequipmentReqDTO.java`、`dto/InventoryequipmentRtnDTO.java`。装备表按主键查询的旧版请求/返回 DTO（列名风格，`inte` 用 `@Column("int")` 消歧）；Req 继承 `BasePageDTO` 仅保留两个 id 字段，Rtn 含 inventoryequipment 全列。为早期接口遗留，与 `InventoryEquipRtnDTO` 功能重叠。

ReqDTO 字段：`inventoryequipmentid`、`inventoryitemid`（均 Long）。

RtnDTO 字段（Long/Integer）：`inventoryequipmentid`、`inventoryitemid`、`upgradeslots`、`level`、`str`、`dex`、`inte`、`luk`、`hp`、`mp`、`watk`、`matk`、`wdef`、`mdef`、`acc`、`avoid`、`hands`、`speed`、`jump`、`locked`、`vicious`(Long)、`itemlevel`、`itemexp`(Long)。

## 8. 物品/装备信息

### EquipmentInfoReqDTO

> 源码：`dto/EquipmentInfoReqDTO.java`。装备模板查询入参；大量历史字段被注释，仅保留：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Integer | 装备（物品）id |

### EquipmentInfoRtnDTO

> 源码：`dto/EquipmentInfoRtnDTO.java`。装备模板属性返回；`_int` 用 `@JsonProperty("int")` 对齐 JSON 键。

| 字段 | 类型 | 说明 |
|---|---|---|
| str / dex / _int / luk | Short | 四维 |
| hp / mp | Short | 血蓝 |
| pAtk / mAtk / pDef / mDef | Short | 攻防 |
| acc / avoid / hands / speed / jump | Short | 命中/回避/攻速/移速/跳 |
| upgradeSlot | Byte | 砸券次数 |
| expire | Long | 有效期 |

## 9. 掉落与商店

### DropSearchReqDTO

> 源码：`dto/DropSearchReqDTO.java`。继承 `BasePageDTO`；掉落查询条件（全局/怪物两用）。

| 字段 | 类型 | 说明 |
|---|---|---|
| dropperId | Integer | 掉落怪物 id |
| dropperName | String | 怪物名（模糊，反查 id） |
| continent | Integer | 大陆（全局掉落用） |
| itemId / itemName | Integer / String | 物品 id / 名（模糊） |
| questId | Integer | 任务 id |

### DropSearchRtnDTO

> 源码：`dto/DropSearchRtnDTO.java`。掉落记录返回项。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | 记录 id |
| dropperId / dropperName | Integer / String | 怪物（全局掉落为空） |
| continent | Integer | 大陆（怪物掉落为空） |
| itemId / itemName | Integer / String | 物品 |
| minimumQuantity / maximumQuantity | Integer | 数量区间 |
| questId / questName | Integer / String | 关联任务 |
| chance | Integer | 掉率 |
| comments | String | 备注（仅全局） |

### ShopSearchReqDTO

> 源码：`dto/ShopSearchReqDTO.java`。继承 `BasePageDTO`；商店与商品查询复用。

| 字段 | 类型 | 说明 |
|---|---|---|
| shopId | Long | 商店 id |
| npcId / npcName | Integer / String | NPC id / 名（模糊） |
| itemId / itemName | Integer / String | 物品 id / 名（模糊） |

### ShopSearchRtnDTO

> 源码：`dto/ShopSearchRtnDTO.java`。商店检索返回项。

| 字段 | 类型 | 说明 |
|---|---|---|
| shopId | Long | 商店 id |
| npcId | Integer | NPC id |
| npcName | String | NPC 名 |

### ShopItemSearchRtnDTO

> 源码：`dto/ShopItemSearchRtnDTO.java`。商店商品返回项。

| 字段 | 类型 | 说明 |
|---|---|---|
| id / shopId | Long | shopitemid / 商店 id |
| itemId | Integer | 物品 id |
| price / pitch / position | Integer | 价格 / 展示行 / 排序位 |
| itemName / itemDesc | String | 物品名 / 描述 |

## 10. 百宝箱

### GachaponPoolSearchReqDTO

> 源码：`dto/GachaponPoolSearchReqDTO.java`。继承 `BasePageDTO`；奖池查询条件。

| 字段 | 类型 | 说明 |
|---|---|---|
| gachaponId | Integer | 百宝箱 NPC id（-1 公共池） |

### GachaponPoolSearchRtnDTO

> 源码：`dto/GachaponPoolSearchRtnDTO.java`。**继承 `GachaponRewardPoolDO`**（直接复用实体全部字段：id/name/gachaponId/weight/isPublic/prob/startTime/endTime/notification/comment），另加：

| 字段 | 类型 | 说明 |
|---|---|---|
| realProb | Integer | 展示用真实概率（按权重换算，百万分比） |

## 11. 文件树

### FileTreeDTO / FileReadDTO

> 源码：`dto/FileTreeDTO.java`、`dto/FileReadDTO.java`。两者字段相同（title+currentKey）：目录树查询 / 文件读取入参。

| 字段 | 类型 | 说明 |
|---|---|---|
| title | String | 文件/目录名 |
| currentKey | String | 树 key（下标路径，空=根） |

### FileWriteDTO

> 源码：`dto/FileWriteDTO.java`。文件写入入参。

| 字段 | 类型 | 说明 |
|---|---|---|
| title | String | 文件名（与 key 定位结果一致性校验） |
| currentKey | String | 树 key |
| content | String | 写入内容（UTF-8） |

### FileTreeNodeDTO

> 源码：`dto/FileTreeNodeDTO.java`。目录树节点（Arco 树组件约定格式）。

| 字段 | 类型 | 说明 |
|---|---|---|
| title | String | 节点名（文件/目录名） |
| key | String | 树 key（下标路径拼接） |
| children | List\<FileTreeNodeDTO> | 子节点；目录为空列表，文件为 null（`@JsonInclude(NON_NULL)`） |
| leaf | boolean | 是否叶子（序列化键为 `isLeaf`，`@JsonProperty`） |

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `FileTreeNodeDTO(File file, String key)` | 由 File 构造节点 | title=文件名；目录 children=空列表、leaf=false；文件 children=null、leaf=true |

---

# 二、POJO（org.gms.model.pojo，7 个）

POJO 与 DTO 的边界：POJO 承载服务端内部运行时数据（含游戏对象上下文），不直接对前端序列化（除少数经 ResultBody 透出）。

## CashCategory

> 源码：`pojo/CashCategory.java`

点券商城分类对象，同时复用为商城商品查询条件与分页入参。

**依赖**：继承 `model.dto.BasePageDTO`（因此携带 pageNo/pageSize，`getCommodityByCategory` 固定 pageSize=10）。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Integer | 主分类 id（SN 首位） |
| name | String | 主分类名（CategoryType） |
| subId | Integer | 子分类 id（SN 第 2-3 位） |
| subName | String | 子分类名（wz Name） |
| onSale | Boolean | 上架状态过滤条件 |
| itemId | Integer | 物品 id 过滤条件 |

方法：Lombok 生成（`@SuperBuilder` 支持继承构建）。

## InformationResult

> 源码：`pojo/InformationResult.java`

通用信息检索结果项（`CommonService.getInformation` 返回）。

| 字段 | 类型 | 说明 |
|---|---|---|
| type | String | 信息类型（InformationType.getType） |
| id | Integer | 资源 id（NPC/地图/任务等） |
| name | String | 名称 |
| desc | String | 描述 |

方法：Lombok 生成。

## InformationSearch

> 源码：`pojo/InformationSearch.java`

通用信息检索条件（Web 传参与服务内部调用双用途）。

| 字段 | 类型 | 说明 |
|---|---|---|
| types | List\<String> | 接口参数：检索类型列表（空=全部 InformationType） |
| filter | String | 接口参数：过滤串（id 或 name） |
| filterType | int | 内部参数：0-both / 1-id / 2-name |
| fullMatch | boolean | 内部参数：是否精确匹配 |

方法：Lombok 生成。

## NewYearCardRecord

> 源码：`pojo/NewYearCardRecord.java`（HeavenMS 遗留，保留 AGPL 版权头）

新年卡运行时记录：封装卡片数据、投递定时任务与原生 JDBC 持久化（newyear 表）。

**字段**：`id`、`senderId/senderName/senderDiscardCard`、`receiverId/receiverName/receiverDiscardCard/receiverReceivedCard`、`message`、`dateSent/dateReceived`、`sendTask`（ScheduledFuture）；静态持有 `NewYearCardService`（经 `ServerManager.getApplicationContext()` 获取）。

| 签名 | 作用 | 关键逻辑 |
|---|---|---|
| `NewYearCardRecord(int senderId, String senderName, int receiverId, String receiverName, String message)` | 新卡构造 | id=-1，各 discard/Received 置 false，dateSent=now、dateReceived=0 |
| `void setExtraNewYearCardRecord(int id, boolean senderDiscardCard, boolean receiverDiscardCard, boolean receiverReceivedCard, long dateSent, long dateReceived)` | 回填 DB 状态 | 供从 DB/DO 恢复时补齐持久化字段 |
| `static void saveNewYearCard(NewYearCardRecord)` | 插入 newyear 表 | 原生 JDBC `INSERT INTO newyear VALUES (DEFAULT,…)`，回写自增 id；SQLException 仅 printStackTrace |
| `static void updateNewYearCard(NewYearCardRecord)` | 标记已领取 | 内存置 receiverReceivedCard=true、dateReceived=now；`UPDATE newyear SET received=1, timereceived=?` |
| `static NewYearCardRecord loadNewYearCard(int cardid)` | 按 id 加载卡片 | 先查 Server 内存缓存；miss 则 `SELECT * FROM newyear WHERE id=?` 构造并回填缓存；异常返回 null |
| `static void loadPlayerNewYearCards(Character chr)` | 加载玩家全部卡片 | 委托 `NewYearCardService.loadPlayerNewYearCards`（DO 查询），逐条构造后 `chr.addNewYearRecord`；异常记日志 |
| `static void printNewYearRecords(Character chr)` | 调试打印 | dropMessage(5) 输出数量与每张卡字段（遗留英文硬编码文案） |
| `void startNewYearCardTask()` | 启动投递提醒任务 | TimerManager 每小时执行：接收者世界为 -1（离线/不存在）取消任务；在线则发 `onNewYearCardRes(0xC)` 提醒 |
| `void stopNewYearCardTask()` | 停止任务 | cancel 并置 null |
| `static void removeAllNewYearCard(boolean send, Character chr)` | 全部丢弃卡片 | send=true 以发送者身份、false 以接收者身份；遍历玩家卡片：置 discard、从双方 record 列表移除、`deleteNewYearCard`（DB 硬删 + Server 缓存移除）、双方地图广播 0xE 包并提示对方（遗留英文文案） |

私有 static：`deleteNewYearCard(int id)` 从 Server 缓存与 newyear 表删除。

## NextLevelContext

> 源码：`pojo/NextLevelContext.java`

`nextLevel` 计算上下文（升级/转职等字符串渲染辅助）。

| 字段 | 类型 | 说明 |
|---|---|---|
| levelType | NextLevelType | 上下文类型 |
| lastLevel / nextLevel | String | 上一级/下一级文本 |
| prefix | String | 前缀文本 |

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void clear()` | 清空上下文 | 四字段全部置 null |

其余方法 Lombok 生成。

## RateLimitContext

> 源码：`pojo/RateLimitContext.java`

REST 限流上下文（`ServerFilter` 使用）：滑动窗口计数与过期时间。

| 字段 | 类型 | 说明 |
|---|---|---|
| curr | AtomicInteger | 当前窗口请求计数 |
| expire | Long | 窗口过期时间戳 |

方法：Lombok 生成（无业务方法）。

## SkillEntry

> 源码：`pojo/SkillEntry.java`

技能条目（public 字段风格，无 Lombok）：角色某技能的等级/满级/到期。

| 字段 | 类型 | 说明 |
|---|---|---|
| skillLevel | byte | 技能当前等级 |
| masterLevel | int | 技能满级 |
| expiration | long | 到期时间戳 |

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `SkillEntry(byte skillLevel, int masterLevel, long expiration)` | 构造器 | 直接赋值 |
| `String toString()` | 序列化 | `"skillLevel:masterLevel"` |
