# 19-工具层（util）详细设计

- 模块路径：`gms-server/src/main/java/org/gms/util/`（21 类）与 `gms-server/src/main/java/org/gms/util/packets/`（2 类）
- 类数量：23（`BasePageUtil`、`BCrypt`、`CashIdGenerator`、`CustomSpringBeanConfig`、`DatabaseConnection`、`ExtendUtil`、`HexTool`、`I18nUtil`、`IntervalBuilder`、`JwtUtils`、`LRUCache`、`NumberTool`、`PacketCreator`、`Pair`、`Quartet`、`Randomizer`、`RateLimitUtil`、`RequireUtil`、`StringUtil`、`ThreadLocalUtil`、`Trio` + packets 子包 `Fishing`、`WeddingPackets`）
- 依赖模块：
  - Spring：`spring-context`（`MessageSource`/`@Configuration`/`@Value`/`@Component`）、springdoc（`CustomSpringBeanConfig`）、jjwt（`JwtUtils`）、MyBatis-Flex（`QueryWrapper`/`Page`）
  - 本项目：`org.gms.manager.ServerManager`（非 Spring 代码反向取 Bean）、`org.gms.property.ServiceProperty`、`org.gms.service.AccountService`/`GachaponService`、`org.gms.dao.mapper.*`（`ExtendValueMapper`/`PetsMapper`/`RingsMapper`）、`org.gms.dao.entity.*`、`org.gms.model.pojo.*`（`RateLimitContext`/`NextLevelCardRecord` 等）、`org.gms.exception.*`（`BizException`）、`org.gms.constants.*`、`org.gms.net.packet.*`（`OutPacket`/`Packet`）、`org.gms.net.opcodes.SendOpcode`、`org.gms.client.*`/`org.gms.server.*`（仅 `PacketCreator`/`Fishing`/`WeddingPackets`）
- 说明：本包是全局工具层。`PacketCreator` 是全服出站封包的唯一构造入口（约 400+ 个静态方法，按业务域分组概述）；`WeddingPackets` 直接继承它补充婚礼封包。其余为连接/i18n/安全/断言/分页/随机/数值/字节等基础工具。

---

## DatabaseConnection

遗留代码的 JDBC 连接入口：把 OdinMS 时代的连接池实现替换为「从 Spring 容器取 Druid `DataSource` 再 `getConnection()`」，使所有遗留 SQL 代码无需改造即走 Spring 数据源（源码路径：`util/DatabaseConnection.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static Connection getConnection() throws SQLException` | 取一条数据库连接 | `ServerManager.getApplicationContext().getBean(DataSource.class).getConnection()`（Druid 池，配置见 `application.yml` 的 `mybatis-flex.datasource`） |

## CustomSpringBeanConfig

Spring 补充配置类（`@Configuration`）：当 Swagger 关闭（`springdoc.*.enabled=false`）时手动补建 springdoc 的两个配置 Bean，避免条件装配缺 Bean 报错（源码路径：`util/CustomSpringBeanConfig.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public SpringDocConfigProperties springDocConfigProperties()` | api-docs 关闭时提供默认配置 Bean | `@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "false")` + `new SpringDocConfigProperties()` |
| `public SwaggerUiConfigProperties swaggerUiConfigProperties()` | swagger-ui 关闭时提供默认配置 Bean | 同上条件注解 |

## JwtUtils

REST 层 JWT 工具（`@Component`）：HS512 签发/校验令牌，供 `AuthTokenFilter` 鉴权使用（源码路径：`util/JwtUtils.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `jwtSecret` | `String`（`@Value("${jwt.secret}")`） | 签名密钥 |
| `jwtDuration` | `int`（`@Value("${jwt.duration}")`） | 有效期（毫秒） |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public String generateJwtToken(String username)` | 签发令牌 | subject=用户名，iat=now，exp=now+duration，HS512 签名 |
| `public String getUserNameFromJwtToken(String token)` | 从令牌取用户名 | 解析 Claims 取 subject |
| `public boolean validateJwtToken(String authToken)` | 校验令牌 | 解析成功 true；按 `SignatureException`/`MalformedJwtException`/`ExpiredJwtException`/`UnsupportedJwtException`/`IllegalArgumentException` 分类打中文 error 日志后 false |

## I18nUtil

服务端 i18n 取值入口：按「面向对象（message）/日志（log）/异常（exception）」三个 MessageSource Bean 分别取文案；客户端请求线程自动跟随客户端语言，服务端主动调用时用服务器语言（源码路径：`util/I18nUtil.java`）。资源文件在 `src/main/resources/i18n/{exception,log,message}_{en_US,zh_CN}.properties`。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `LANGUAGE` | `static final Locale` | 服务器语言（`gms.service.language` → `Locale.forLanguageTag`） |
| `messageSource` / `logSource` / `exceptionSource` | `static final MessageSource` | 三个按文件名细分的 Bean（避免底层遍历全部 basename，加快查找） |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static String getMessage(String code, Object... args)` | 取面向玩家的文案（自动语言） | 语言取 `ThreadLocalUtil.getClientLang()` 对应 Locale（无客户端上下文回退 0=服务器语言）；所有参数 `String.valueOf` 转字符串（避免数字被本地化千分符污染）后 `getMessage` |
| `public static String getMessage(Locale locale, String code, Object... args)` | 按指定语言取文案 | 指定 Locale |
| `public static String getLogMessage(String code, Object... args)` / `(Locale locale, String code, Object... args)` | 取日志文案（`{0}` 占位符格式） | logSource，默认服务器语言 |
| `public static String getExceptionMessage(String code, Object... args)` / `(Locale locale, ...)` | 取异常文案 | exceptionSource，默认服务器语言 |

## ThreadLocalUtil

客户端上下文传递：Netty 处理线程/REST 请求线程上暂存当前 `Client`，供 `I18nUtil`/`HexTool` 等取「客户端语言」等会话信息（源码路径：`util/ThreadLocalUtil.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `threadLocal` | `static final ThreadLocal<Client>` | 当前线程绑定的客户端 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static void setCurrentClient(Client c)` / `Client getCurrentClient()` / `removeCurrentClient()` | 设置/获取/清除当前客户端 | 标准 ThreadLocal 三件套 |
| `public static int getClientLang()` | 当前客户端语言代号 | `Optional` 包装，无绑定返回 0（服务器默认语言） |

## RateLimitUtil

REST 接口限流器（懒加载单例）：按 IP 的固定窗口计数限流，支持超限自动封禁，被 `ServerFilter` 调用（源码路径：`util/RateLimitUtil.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `instance` | `static RateLimitUtil` | 单例（非线程安全的懒加载） |
| `rateLimitProperty` | `ServiceProperty.RateLimitProperty` | 限流配置（enabled/duration/limit/autoBan） |
| `contextMap` | `Map<String, RateLimitContext>` | IP → 计数上下文 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `private RateLimitUtil()` | 构造函数 | 从 Spring 取限流配置 |
| `public static RateLimitUtil getInstance()` | 取单例 | null 则创建 |
| `public boolean check(String ip)` | 检查该 IP 本次请求是否放行 | ① 未启用直接 true；② 无上下文则新建（计数 1、窗口到期时间）；③ 窗口过期则重置计数放行；④ 计数 +1 超过 limit：开启 `autoBan` 时调 `AccountService.ban(ip, "Auto banned by rate limit", true)` 自动封禁，返回 false；异常记日志返回 false |

## RequireUtil

参数断言工具集：以「抛 `IllegalArgumentException`（无文案）或 `BizException(ILLEGAL_PARAMETERS)`（有文案）」的方式做前置校验，并提供函数式消费变体（源码路径：`util/RequireUtil.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static void requireNull(Object obj[, String msg])` | 断言为 null | 非 null 抛异常（有 msg 走 BizException） |
| `public static void requireNotNull(Object obj[, String msg])` | 断言非 null | 注意：无参版本内部调的是 `requireNull`（遗留缺陷，含 msg 版本逻辑正确：null 时抛异常） |
| `public static void requireNotEmpty(Object obj[, String msg])` | 断言非空 | 复用 `isEmpty` 判断，空则抛异常 |
| `public static void requireTrue(boolean b, String msg)` / `requireFalse(boolean b, String msg)` | 断言真/假 | 违背则抛 BizException |
| `public static boolean isEmpty(Object obj)` | 广义空判断 | null、空白 String、空 Iterable/Iterator/数组/Map 均为 true |
| `public static boolean isZero(Number obj)` | 是否为数值 0 | null 返回 false |
| `public static void requireNotEmptyOrElse(Object obj, Runnable runnable)` | 为空则执行动作 | 空时 `runnable.run()` |
| `public static void requireNotEmptyAndThen(Object obj, Runnable runnable)` | 非空则执行动作 | 非空时 run |
| `public static <T, R> void requireNotEmptyAndThen(T t, R r, BiConsumer<T, R> consumer)` | 两者均非空才消费 | 任一为空直接返回 |

## BasePageUtil

内存分页工具：对已有 Collection 数据做「过滤 → 排序 → 分页」的流式处理，产出 MyBatis-Flex `Page<T>`（数据库分页直接用 mybatis-flex，本类仅用于内存数据，如在线玩家列表）（源码路径：`util/BasePageUtil.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `data` | `Stream<T>` | 数据流（filter/sorted 会替换） |
| `basePageDTO` | `final BasePageDTO` | 分页参数（pageNo 默认 1、pageSize 默认 20、onlyTotal、notPage） |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `private BasePageUtil(Collection<T> data[, BasePageDTO basePageDTO])` | 构造函数 | 空集合转空流；DTO 缺省补默认页码/页大小 |
| `public static <T> BasePageUtil<T> create(Collection<T> data)` / `(Collection<T>, BasePageDTO)` / `(Collection<T>, Integer pageNo, Integer pageSize)` / `(Collection<T>, boolean onlyTotal, boolean notPage)` | 工厂方法（4 重载） | 组装 DTO 后构造 |
| `public BasePageUtil<T> filter(Predicate<T> predicate)` | 过滤 | `data.filter`，返回自身链式 |
| `public BasePageUtil<T> sorted(Comparator<? super T> comparator)` | 排序 | `data.sorted`，链式 |
| `public Page<T> page()` | 生成分页结果 | `notPage` → 全量单页；`onlyTotal` → 仅 totalRow；否则 `skip((pageNo-1)*pageSize).limit(pageSize)` 切页，Page 携带总数 |
| `public <R> Page<R> page(Function<T, R> mapper)` | 分页并转换元素类型 | 同上，切页后逐项 `map(mapper)` |

## Randomizer

全局随机数门面：包装一个共享 `java.util.Random`，全服随机统一入口（便于未来替换算法/种子调试）（源码路径：`util/Randomizer.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static int nextInt()` / `nextInt(int arg0)` | 随机 int / [0,n) | 委托 `rand` |
| `public static void nextBytes(byte[] bytes)` | 随机字节数组 | — |
| `public static boolean nextBoolean()` / `double nextDouble()` / `float nextFloat()` / `long nextLong()` | 各类型随机 | — |
| `public static int rand(int lbound, int ubound)` | [lbound, ubound] 闭区间随机 int | `(int)(nextDouble() * (ubound-lbound+1)) + lbound` |

## NumberTool

数值转换工具（源码路径：`util/NumberTool.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static long BytesToLong(byte[] aToConvert)` | 8 字节大端转 long | 长度必须恰为 8 否则抛 IllegalArgumentException；逐字节 `<<=8 \|\=` 累加 |
| `public static byte[] LongToBytes(long nToConvert)` | long 转 8 字节大端 | 从低位逐字节拆出 |
| `public static int floatToInt(float f)` / `int doubleToInt(double d)` | 浮点安全转 int | 超过 `Integer.MAX_VALUE` 钳制为 MAX_VALUE（经验/金币倍率运算防溢出） |

## HexTool

字节-十六进制互转工具（源码路径：`util/HexTool.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static String toHexString(byte[] bytes)` | 字节数组 → 大写十六进制（空格分隔） | `HexFormat.ofDelimiter(" ").withUpperCase()` |
| `public static String toCompactHexString(byte[] bytes)` | 字节数组 → 大写紧凑十六进制（无分隔） | `HexFormat.of().withUpperCase()` |
| `public static byte[] toBytes(String hexString)` | 十六进制串（可带空格/大小写混合）→ 字节数组 | 先 `removeAllSpaces` 再 `HexFormat.parseHex` |
| `private static String removeAllSpaces(String input)` | 去全部空白 | 正则 `\s` |
| `public static String toStringFromCharset(byte[] bytes)` | 字节 → 可读字符串（封包调试） | ASCII 控制字符（0-31）替换为 `.`，按客户端语言对应字符集（`CharsetConstants.getCharset`）解码 |
| `private static boolean isSpecialCharacter(byte asciiCode)` | 是否控制字符 | 0-31 |

## StringUtil

字符串工具（源码路径：`util/StringUtil.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static String getLeftPaddedStr(String in, char padchar, int length)` | 左补齐到定长 | 前缀拼 padchar（协议定长字段常用） |
| `public static String getRightPaddedStr(String in, char padchar, int length)` | 右补齐到定长 | 尾部拼 padchar（`\0` 填充的 13 字节角色名等） |
| `public static String joinStringFrom(String[] arr, int start)` / `(String[] arr, int start, String sep)` | 从下标 start 起拼接数组 | 默认空格分隔（聊天命令参数重组） |
| `public static String makeEnumHumanReadable(String enumName)` | 枚举名转可读文本 | 按 `_` 分词；≤2 字符视为缩写保留，否则首字母大写其余小写 |
| `public static int countCharacters(String str, char chr)` | 统计字符出现次数 | 逐字符比较 |
| `public static boolean isNumeric(String str)` | 是否数字（可含负号/小数） | 正则 `-?\d+(\.\d+)?` |

## Pair / Trio / Quartet

多元组值对象，服务端各处轻量返回多值的载体（源码路径：`util/Pair.java`、`util/Trio.java`、`util/Quartet.java`）。

| 类 | 字段 | 方法 | 说明 |
| --- | --- | --- | --- |
| `Pair<E, F>` | `public E left; public F right;` | 构造器、`getLeft()`、`getRight()`、`toString()`（`left:right`）、`hashCode()`（31 素数组合）、`equals()`（逐字段 null 安全比较） | 二元组，字段直接 public 可写 |
| `Trio<A, B, C>` | `first/second/third`（Lombok `@Data`） | `@AllArgsConstructor`/`@NoArgsConstructor` 自动生成 | 三元组 |
| `Quartet<A, B, C, D>` | `first/second/third/fourth`（`@Data`） | 同上 | 四元组 |

## LRUCache

基于 `LinkedHashMap` 的 LRU 缓存：accessOrder=true 使访问即重排，容量超限自动驱逐最旧项（源码路径：`util/LRUCache.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public LRUCache()` / `LRUCache(int capacity)` | 构造（默认容量 1024） | `super(capacity, 0.75f, true)` 开启访问顺序排序 |
| `public boolean removeEldestEntry(Map.Entry<K, V> eldest)` | 驱逐条件 | `size() > capacity` 时由 LinkedHashMap 自动移除最老项 |

## IntervalBuilder

线程安全区间集合：维护一组不相交区间（`Line2D` 表示 [x1,x2]），支持合并插入、二分查询、清空——用于 WZ/任务中「ID 段命中判断」等场景（源码路径：`util/IntervalBuilder.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `intervalLimits` | `List<Line2D>` | 按起点有序的不相交区间表 |
| `intervalRlock` / `intervalWlock` | `Lock` | 公平读写锁 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public IntervalBuilder()` | 构造函数 | 建公平 ReentrantReadWriteLock |
| `private void refitOverlappedIntervals(int st, int en, int newFrom, int newTo)` | 合并 [st,en) 内区间并插入新区间 | 取被覆盖区间的最左/最右端点扩展新区间，删除旧区间后在 st 位置插入 |
| `private int bsearchInterval(int point)` | 二分找 point 所在/左侧区间下标 | 经典二分，命中起点返回 idx，否则返回 en（左侧区间号，可为 -1） |
| `public void addInterval(int from, int to)` | 插入区间并自动合并 | 写锁下二分定位起止区间，校正边界后 `refitOverlappedIntervals` |
| `public boolean inInterval(int point)` / `inInterval(int from, int to)` | 点/区间是否被包含 | 读锁下二分：idx>=0 且 `to <= 区间.x2` |
| `public void clear()` | 清空全部区间 | 写锁 |

## CashIdGenerator

现金物品（宠物/戒指）ID 生成器：启动时从 `rings`/`pets` 表装载已用 ID，自增发号，接近上限（777000000）时重新装载（源码路径：`util/CashIdGenerator.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `existentCashIds` | `static Set<Integer>` | 已占用 ID 集 |
| `runningCashId` | `static Integer` | 当前发号游标 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static synchronized void loadExistentCashIdsFromDb()` | 从 DB 装载已用 ID | RingsMapper/PetsMapper 全表收集非空 ID；游标从 0 自增找到首个空位 |
| `private static void getNextAvailableCashId()` | 游标 +1 | 达到 777000000 触发重新装载（防撞上限） |
| `public static synchronized int generateCashId()` | 发放一个现金 ID | 循环跳过已占用 ID；发放后游标前移（不回收登记，回卷时由 DB 重查兜底） |
| `public static synchronized void freeCashId(int cashId)` | 归还 ID | 从占用集中移除 |

## ExtendUtil

扩展值表（`extend_value`）读写工具：脚本与业务模块的 KV 持久化（角色/账号 × 永久/每日/每周）底层实现，配合 `ExtendType`/`ExtendValueDO`（源码路径：`util/ExtendUtil.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `extendValueMapper` | `static ExtendValueMapper` | 静态取 Spring Bean（类加载时初始化） |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static ExtendValueDO getExtendValue(String extendId, String extendType, String extendName)` | 查询单条扩展值 | `selectOneByQuery` 按 id+type+name 三键精确匹配 |
| `public static void saveOrUpdateExtendValue(String extendId, String extendType, String extendName, String extendValue)` | 写入（存在则更新） | 无记录 `insertSelective`（带 createTime）；有记录清空 createTime、写 updateTime 与新值后 `update` |

## BCrypt

OpenBSD 风格 Blowfish 密码散列（jBCrypt 0.4 源码内置，无外部依赖）：账号密码加密/校验，另附 SHA-512 工具（源码路径：`util/BCrypt.java`）。

### 关键字段/常量

| 字段 | 说明 |
| --- | --- |
| `GENSALT_DEFAULT_LOG2_ROUNDS = 10` | 默认加密轮数（2^10 次） |
| `BCRYPT_SALT_LEN = 16` | 盐字节数 |
| `BLOWFISH_NUM_ROUNDS = 16` | Blowfish 轮数 |
| `P_orig` / `S_orig` | Blowfish 初始 P 盒/S 盒（Schneier 标准常量） |
| `bf_crypt_ciphertext` | bcrypt IV（"OrpheanBeholderScryDoubt"） |
| `base64_code` / `index_64` | bcrypt 专用 base64 编解码表（与 MIME base64 不兼容） |
| `P` / `S` | 运行时展开的密钥调度表 |

### 方法：公开 API

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static String hashpw(String password, String salt)` / `hashpw(byte[] passwordb, String salt)` | 按盐散列密码，产出 `$2a$10$...` 完整散列串 | ① 校验盐格式（`$2` + 可选 minor a/x/y/b）；② 截取轮数与真实盐（22 字符 base64 → 16 字节）；③ minor≥'a' 时密码补 NUL 终结符；④ `crypt_raw`（'2x' 启用符号扩展 bug、'2a' 启用 0x10000 安全位）；⑤ 拼装 `$2<minor>$<rounds>$<salt><hash>` |
| `public static String gensalt(String prefix, int log_rounds, SecureRandom random)` / `(String prefix, int log_rounds)` / `(int log_rounds, SecureRandom)` / `(int log_rounds)` / `gensalt()` | 生成盐（5 重载，默认 `$2y` + 10 轮） | 校验 prefix（$2a/$2y/$2b）与轮数 4-30；SecureRandom 产 16 字节随机数，拼 `$2y$10$<base64>` |
| `public static boolean checkpw(String plaintext, String hashed)` / `checkpw(byte[] plaintext, String hashed)` | 校验明文是否匹配散列 | 用原散列作盐重算 `hashpw`，与存量散列逐字节**恒时比较**（XOR 累计，不短路），防时序侧信道 |
| `public static String hashpwSHA512(String pwd)` | SHA-512 摘要（小写十六进制紧凑串） | `MessageDigest("SHA-512")` + `HexTool.toHexString` 去空格转小写（旧版账号密码兼容） |

### 方法：内部实现（择要）

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `private static String encode_base64(byte[] d, int len)` / `byte[] decode_base64(String s, int maxolen)` / `private static byte char64(char x)` | bcrypt base64 编解码 | 自定义字母表 `./A-Za-z0-9` |
| `private final void encipher(int[] lr, int off)` | Blowfish 加密一个 64 位块 | 16 轮 Feistel（S 盒加/异或组合） |
| `private static int[] streamtowords(byte[] data, int[] offp, int[] signp)` / `streamtoword` / `streamtoword_bug` | 循环抽取 32 位密钥字 | 同步产出正确/带符号扩展 bug 两个结果并记录非良性符号扩展标志 |
| `private void init_key()` / `key(byte[] key, boolean sign_ext_bug)` | 初始化/装填密钥调度 | P/S 克隆原表；key 字异或装填 P 后连锁 encipher 展开 P、S |
| `private void ekskey(byte[] data, byte[] key, boolean sign_ext_bug, int safety)` | 增强密钥调度（salt 参与） | 装填 P 时累计正确/bug 结果差异，检测到「多 bug 撞一正确」碰撞且 safety 位开启时翻转 P[0] 位规避；随后 salt 字混入展开 P/S |
| `private byte[] crypt_raw(byte[] password, byte[] salt, int log_rounds, boolean sign_ext_bug, int safety, int[] cdata)` | 核心散列 | 轮数 4-30 校验；`ekskey(salt, password)` 后 `2^log_rounds` 次交替 `key(password)/key(salt)`；对 IV 密文 64 轮 encipher；输出 4×clen 字节 |
| `private static byte[] stringToBytes(String plaintext)` | 明文转 UTF-8 字节 | — |

---

## PacketCreator

全服出站封包（S→C）构造器：约 400+ 个静态方法，按 `SendOpcode` 建包并按 v83 协议逐字段写入，是服务端所有「发往客户端」数据的唯一出口（`Client.sendPacket`/地图广播最终都调到这里）。无实例状态，全部方法 `static`；`WeddingPackets` 继承本类复用 `addItemInfo/addCharLook` 等保护方法（源码路径：`util/PacketCreator.java`）。

### 关键常量与基础方法

| 成员 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `EMPTY_STATUPDATE` | 空 stat 更新列表 | `enableActions()` 的参数来源 |
| `FT_UT_OFFSET` / `DEFAULT_TIME` / `ZERO_TIME` / `PERMANENT` | FILETIME 纪元偏移与三个哨兵时间 | UTC 毫秒 → Windows FILETIME（100ns）换算，含本地时区补偿 |
| `public static long getTime(long utcTimestamp)` | UTC 毫秒 → 协议时间 | -1/-2/-3 分别映射 DEFAULT_TIME/ZERO_TIME/PERMANENT；其余 `×10000 + FT_UT_OFFSET` |
| `private static void writeMobSkillId(OutPacket, MobSkillId)` | 写怪物技能标识 | short type + short level |
| `WhisperFlag`（内部类） | 私语包 flag 位常量 | LOCATION/WHISPER/REQUEST/RESULT/RECEIVE/BLOCKED/LOCATION_FRIEND |

### 复合结构编码（protected/private，被各业务方法复用）

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `private static void addCharStats(OutPacket p, Character chr)` | 角色属性块 | id、13 字节定长名、性别/肤色/脸/发、3 个宠物唯一 ID、等级/职业/四维/HP/MP/AP/SP（有 SP 表职业走 `addRemainingSkillInfo`）、经验/人气/GachaExp/地图/出生点 |
| `protected static void addCharLook(OutPacket p, Character chr, boolean mega)` | 角色外观块 | 性别/肤色/脸/发 + `addCharEquips` 装备位映射（现金装备走 masked 槽，修复他人看不到现金勋章） |
| `private static void addCharEquips(OutPacket p, Character chr)` | 已装备表 | 槽位取反、>100 槽拆 mask 两轮写入，武器（-111）与 3 宠物装备单独写 |
| `private static void addCharacterInfo(OutPacket p, Character chr)` | 进图/商城完整角色数据 | 依次：stats、好友容量、linked 名、金币、背包（`addInventoryInfo`）、技能（`addSkillInfo`）、任务（`addQuestInfo`）、小游戏、戒指（`addRingInfo`）、传送点（`addTeleportInfo`）、怪物卡、贺年卡、AreaInfo |
| `protected static void addItemInfo(OutPacket p, Item item[, boolean zeroPosition])` | 物品通用编码 | 现金物品写唯一 ID（宠物/戒指/现金序号）；宠物分支写名字/等级/亲密度/饱食度/属性；普通物品写数量/主人/flag（可充电道具补 4 字节）；装备写全属性 + 升级槽/等级/经验/锤子数 |
| `public static void addCashItemInformation(OutPacket p, Item item, int accountId[, String giftMessage])` | 商城现金物品编码 | 唯一 ID + 账号 + SN + 数量 + 赠送人；礼物分支写 73 字节定长留言并提前返回 |
| `private static void addPartyStatus(int forchannel, Party party, OutPacket p, boolean leaving)` | 队伍成员状态块 | 补空位到 6 人后按「id/名/职业/等级/频道/队长/地图/门」七轮并行写 |
| `private static Packet spawnMonsterInternal(...)` | 怪物生成/控制通用实现 | makeInvis 时仅发控制头；requestController 时附带 `encodeTemporary` 怪物 Buff；处理父怪关联（召唤物在父怪死后出现）、出生特效码与 fade-in 标志 |
| `private static void encodeTemporary(OutPacket p, Map<MonsterStatus, MonsterStatusEffect> stati)` | 怪物状态掩码编码 | 过滤 WATK/WDEF（防崩）；`writeLongEncodeTemporaryMask` + 各状态值/来源技能；反射状态额外写计数与 100% 概率 |
| `private static void writeLongMask(...)` / `writeLongMaskD(...)` / `writeLongMaskFromList(...)` / `writeLongEncodeTemporaryMask(...)` / `writeIntMask(...)` | Buff/Disease/怪物状态 64 位（或 128 位双 long）掩码 | 按 isFirst 拆高低两张掩码 |
| `private static void addAttackBody(...)` | 近战/远程/法系攻击通用体 | 攻击者 id、技能与等级、朝向/姿态/速度、弹射物 id、逐目标伤害列表（金钱爆炸 4211006 额外写 hit 数） |
| `private static void rebroadcastMovementList(...)` / `serializeMovementList(...)` | 移动数据原样转发/序列化 | 客户端移动字节按长度复读广播（不解析），或按 `LifeMovementFragment.serialize` 逐条写 |
| `private static void addRingLook(OutPacket p, Character chr, boolean crush)` / `addMarriageRingLook(Client, OutPacket, Character)` / `addRingInfo(OutPacket p, Character chr)` | 戒指外观/信息（情侣戒/友情戒/婚戒） | 三类戒指分别编码；婚戒对伴侣本人隐身发送 |
| `private static void addAnnounceBox(...)`×2 / `updateHiredMerchantBoxInfo` / `updatePlayerShopBoxInfo` | 玩家头顶商店/小游戏广告牌 | — |
| `private static void addPetInfo(OutPacket p, Pet pet, boolean showpet, boolean hasNameTag, boolean hasChatBalloon)` | 宠物外观块 | 道具/名字/唯一 ID/位置/姿态/落脚点 + 名牌与气泡标记 |
| `private static void encodeNewYearCard(...)` / `encodeNewYearCardInfo(...)` | 贺年卡编码 | — |
| `private static void writeModifiedCashItem(OutPacket p, ModifiedCashItemDO item)` | 商城商品字段编码（BeiDou 重写） | 反射遍历 `ModifiedCashItemDO` 字段（`@Column` 优先、否则驼峰转下划线匹配 `CommodityFlag`），按 flag 顺序聚合写入并补 FLAG 位掩码 |
| `private static String getRightPaddedStr(...)` | 本地右补齐（避免依赖） | — |

### 方法分组概览（约 400 个公开封包方法，按业务域归类；每类给出代表方法）

| 分组 | 代表方法 | 覆盖内容 |
| --- | --- | --- |
| 登录/账号/封禁 | `getHello`（握手，含双 IV）、`getPing`、`getAuthSuccess`（登录成功，先同步加载账号角色与仓库）、`getLoginFailed`/`getAfterLoginError`（30 种错误码）、`getPermBan`/`getTempBan`、`sendPolice`、PIN/PIC 系列（`requestPin`/`registerPin`/`pinAccepted`/`wrongPic` 等）、`getServerList`/`getEndOfServerList`/`getServerStatus`/`getServerIP`/`getChannelChange`、`getCharList`、`charNameResponse`/`addNewCharEntry`/`deleteCharResponse`、`selectWorld`/`sendRecommended`、`showAllCharacter(Info)`、`updateGender`、`sendMapleLife*`、`getRelogResponse`、`sendGuestTOS`、`enableTV`/`removeTV`/`sendTV` | 登录服务器全流程封包 |
| 角色数据/外观 | `getCharInfo`（SET_FIELD 全量）、`charInfo`（右键查看：宠物/骑宠/愿望单/怪物卡/勋章任务）、`spawnPlayerMapObject`（他人视角刷角色，含 `writeForeignBuffs`）、`removePlayerFromMap`、`updateCharLook`、`damagePlayer`、`facialExpression`、`useChalkboard`、`aranGodlyStats`/`resetForcedStats`、`showForcedEquip`、`updateClientSettings`（BeiDou：HP/MP 警报阈值回显） | 角色上下线与外观同步 |
| 属性/Buff/Debuff | `enableActions`/`updatePlayerStats`（stat 掩码按值宽排序写入）、`giveBuff`/`giveForeignBuff`/`cancelBuff`/`cancelForeignBuff`、`giveDebuff`/`giveForeignDebuff`/`cancelDebuff` 系列、`givePirateBuff`/`giveForeignPirateBuff`（海盗速灌特殊布局）、`giveForeignWKChargeEffect`/`giveForeignChairSkillEffect`、`giveForeignSlowDebuff`、`showMonsterRiding`、`giveFinalAttack`、`familyBuff`/`cancelFamilyBuff`、`petStatUpdate` | 状态机封包 |
| 攻击/技能 | `closeRangeAttack`/`rangedAttack`/`magicAttack`（共用 `addAttackBody`）、`throwGrenade`、`summonAttack`/`damageSummon`、`skillEffect`/`skillCancel`/`summonSkill`/`skillCooldown`/`updateSkill`/`skillBookResult`、`showBuffEffect`×3/`showOwnBuffEffect`、`showBerserk` 系、`showCombo`、`getEnergy`（能量条）、`showMakerEffect` 系（锻造） | 战斗表现 |
| 怪物 | `spawnMonster`×2/`controlMonster`/`spawnFakeMonster`/`makeMonsterReal`/`makeMonsterInvisible`/`removeMonsterInvisibility`/`stopControllingMonster`、`moveMonster`/`moveMonsterResponse`×2、`killMonster`×2、`damageMonster`/`healMonster`/`MobDamageMobFriendly`、`applyMonsterStatus`/`cancelMonsterStatus`、`showMonsterHP`/`showBossHP`/`customShowBossHP`（超 INT 上限血条归一化）、`catchMonster`×2/`catchMessage` | 怪物生命周期 |
| NPC | `getNPCTalk`（含 BeiDou speaker 重载 `getNPCTalkNum`/`getNPCTalkText`）、`getNPCTalkStyle`、`getDimensionalMirror`、`OnAskQuiz`/`OnAskSpeedQuiz`、`spawnNPC`/`spawnNPCRequestController`/`removeNPC`/`removeNPCController`、`spawnPlayerNPC`/`getPlayerNPC`/`removePlayerNPC`、`setNPCScriptable`（中文名按 GBK 手工编码适配客户端） | NPC 交互 |
| 背包/物品 | `modifyInventory`（增/删/改/移动四模式 + 装备移动附加字节）、`updateInventorySlotLimit`、`getInventoryFull`/`getShowInventoryFull`/`showItemUnavailable`、`getScrollEffect`/`sendVegaScroll`、`sendHammerData`/`sendHammerMessage`、`itemExpired`/`itemEffect`/`getItemMessage`、`finishedSort`/`finishedSort2`、`trockRefreshMapList` | 物品操作 |
| 掉落 | `dropItemFromMapObject`（含归属类型/掉落动画）、`updateMapItemObject`、`removeItemFromMap`×2/`silentRemoveItemFromMap`（0-过期 1-无动画 2-拾取 4-爆炸） | 地面物品 |
| 任务 | `updateQuest`（含 infoNumber 关联任务）、`forfeitQuest`/`completeQuest`/`updateQuestInfo`/`updateQuestFinish`、`addQuestTimeLimit`/`removeQuestTimeLimit`、`questError`/`questFailure`/`questExpire`、`getShowQuestCompletion`、`updateAreaInfo`、`getDojoInfo`/`updateDojoStats`/`sendDojoAnimation`/`dojoWarpUp` | 任务进度 |
| 地图/传送 | `getWarpToMap`×2（SET_FIELD 增量换图）、`spawnPortal`/`spawnDoor`/`removeDoor`、`musicChange`/`showEffect`/`playSound`/`environmentChange`/`environmentMove(List)`/`environmentMoveReset`、`startMapEffect`/`removeMapEffect`、`mapEffect`/`mapSound`、`trembleEffect`、`changeBackgroundEffect`、`blockedMessage`×2、`disableMinimap`、`crogBoatPacket`/`boatPacket`（船只）、`getClock(Number)`（BeiDou：修复旅行倍率小数）/`getClockTime`/`removeClock` | 地图表现 |
| Reactor/Summon/Mist/Dragon/风筝 | `spawnReactor`/`triggerReactor`/`destroyReactor`；`spawnSummon`/`removeSummon`/`spawnKite`/`removeKite`/`sendCannotSpawnKite`；`spawnMist`/`spawnMobMist`/`removeMist`；`spawnDragon`/`moveDragon`/`removeDragon` | 特殊地图对象 |
| 移动 | `movePlayer`/`moveSummon`/`moveMonster`/`movePet`/`moveDragon` | 移动广播 |
| 宠物 | `showPet`、`petChat`/`commandResponse`、`petFoodResponse`/`petEatCashFoodFail`、`showOwnPetLevelUp`/`showPetLevelUp`、`changePetName`、`loadExceptionList` | 宠物系统 |
| 骑宠/椅子/UI | `updateMount`、`showChair`/`cancelChair`、`setExtraPendantSlot`、`openUI`（含 UI 编号表注释）、`lockUI`/`disableUI`、`sendHint`、`showIntro`/`showInfo`/`showForeignInfo`、`showSpecialEffect` 系（升级/传送门音/任务完成/恢复/命运之轮等）、`showForeignEffect`×2、`getMacros`/`getKeymap`/`QuickslotMappedInit`、`sendAutoHpPot`/`sendAutoMpPot`、`showOXQuiz` | 客户端界面 |
| 消息/广播 | `serverMessage`（私有统一实现）/`serverNotice`×4/`serverMessage`、`getChatText`、`getAvatarMega`/`byeAvatarMega`/`itemMegaphone`/`getMultiMegaphone`/`gachaponMessage`（中文文案）、`sendYellowTip`、`levelUpMessage`/`marriageMessage`/`jobMessage`、`multiChat`（好友/组队/公会频道）、私语系 `getFindResult`/`getWhisperResult`/`getWhisperReceive`、`OnCoupleMessage`（情侣/配偶聊天）、`noteError` | 聊天广播 |
| 获取提示 | `getShowExpGain`（含组队/装备/网吧/彩虹周加成位）、`getShowFameGain`、`getShowMesoGain`、`getShowItemGain`×2、`giveFameResponse`/`giveFameErrorResponse`/`receiveFame`、`getGPMessage`、`showInfoText`、`earnTitleMessage`、`getShowInventoryStatus` | 收益提示 |
| 组队 | `partyCreated`（含队伍门）、`partyInvite`/`partySearchInvite`、`partyStatusMessage`×2、`updateParty`（JOIN/LEAVE/EXPEL/DISBAND/SILENT_UPDATE/LOG_ONOFF/CHANGE_LEADER）、`partyPortal`、`updatePartyMemberHP` | 队伍 |
| 好友/群组 | `updateBuddylist`/`buddylistMessage`/`requestBuddylistAdd`/`updateBuddyChannel`/`updateBuddyCapacity`、messenger 系（`messengerInvite`/`addMessengerPlayer`/`removeMessengerPlayer`/`updateMessengerPlayer`/`joinMessenger`/`messengerChat`/`messengerNote`） | 社交 |
| 家族 | `loadFamily`、`sendFamilyMessage`、`getFamilyInfo`/`getEmptyFamilyInfo`、`showPedigree`/`addPedigreeEntry`、`sendFamilyInvite`/`sendFamilySummonRequest`/`sendFamilyLoginNotice`/`sendFamilyJoinResponse`/`getSeniorMessage`/`sendGainRep` | 家族系统 |
| 交易/商店 | `getNPCShop`/`shopTransaction`/`shopErrorMessage`；`getTradeStart`/`getTradePartnerAdd`/`tradeInvite`/`getTradeMesoSet`/`getTradeItemAdd`/`getTradeConfirmation`/`getTradeResult`/`getTradeChat`；`getPlayerShop` 系（聊天/访客/商品/售出/广告牌）；雇佣商人系（`getHiredMerchant`/`updateHiredMerchant`/`spawnHiredMerchantBox`/`removeHiredMerchantBox`/`viewMerchantVisitorHistory`/`viewMerchantBlacklist`/Fredrick 系/`hiredMerchantBox`）；猫头鹰系（`owlOfMinerva`/`getOwlOpen`/`getOwlMessage`） | 玩家经济 |
| 仓库/背包整理 | `getStorage`/`getStorageError`/`mesoStorage`/`storeStorage`/`takeOutStorage`/`arrangeStorage` | 仓库 |
| 小游戏/RPS | `getMiniGame` 系（准备/开始/落子/求和/结果/访客/关闭）、`getMatchCard` 系、`addOmokBox`/`addMatchCardBox`/`removeMinigameBox`、`getMiniRoomError`；`openRPSNPC`/`rpsMesoError`/`rpsSelection`/`rpsMode` | 互动玩法 |
| 活动/PQ | CPQ 系（`CPUpdate`/`CPQMessage`/`playerSummoned`/`playerDiedMessage`/`startMonsterCarnival`）、雪球（`rollSnowBall`/`hitSnowBall`/`snowballMessage`）、椰子（`coconutScore`/`hitCoconut`）、Ariant（`showAriantScoreBoard`/`updateAriantPQRanking`）、金字塔（`pyramidGauge`/`pyramidScore`）、羊牧场（`sheepRanchInfo`/`sheepRanchClothes`）、HPQ（`bunnyPacket`/`hpqMessage`）、OX 测验（`showOXQuiz`）、GM 活动（`showEventInstructions`）、`incubatorResult`、`MassacreResult`/Tournament 系（私有） | 活动 |
| 商城/MTS/转账 | `openCashShop`（含 BeiDou 反射版商品表编码与热销榜）、`showCashInventory`/`showWishList`/`showGifts`/`showGiftSucceed`、`showBoughtCashItem`/`showBoughtCashRing`/`showBoughtCashPackage`/`showBoughtQuestItem`/槽位购买系、`takeFromCashInventory`/`putIntoCashInventory`/`deleteCashItem`/`refundCashItem`、`showCashShopMessage`（60+ 错误码注释）、`onCashGachaponOpenSuccess` 等、`showCouponRedeemedItems`、`showCash`/`enableCSUse`、`sendMesoLimit`；MTS 系（`sendMTS`/`transferInventory`/`notYetSoldInv`/`MTSConfirmSell/Buy/Transfer` 等）；世界转移/改名系（`sendWorldTransferRules`/`showWorldTransferSuccess`/`sendNameTransferRules`/`sendNameTransferCheck`/取消系）；Duey 包裹系（`sendDuey`/`removeItemFromDuey` 等） | 现金经济 |
| 贺年卡 | `onNewYearCardRes`×2（mode 4-0xE 全套） | 贺年卡 |
| GM/管理 | `getGMEffect`（hide/封禁警告/排行）、`findMerchantResponse`、`enableReport`/`reportResponse`、`sendPolice`、`customPacket`×2（裸 hex 调试包）、`updateSkill` | 管理 |
| 怪物卡/称号 | `addCard`/`showGainCard`/`showForeignCardEffect`/`changeCover` | 怪物卡图鉴 |
| 锻造 Maker | `makerResult`/`makerResultCrystal`/`makerResultDesynth`/`makerEnableActions` | 锻造系统 |
| 新手引导 | `spawnGuide`/`talkGuide`/`guideHint` | 向导精灵 |
| 宝箱 | `UseTreasureBox` | 扭蛋宝箱 |

---

## Fishing（util/packets）

自定义钓鱼系统：基于「年内天数 + 时分秒」的三角函数似然度模型判定是否咬钩，命中后随机发放金币/经验/物品并全图播报（源码路径：`util/packets/Fishing.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `private static double getFishingLikelihood(int x)` | 似然度函数 | `50 + 7·7·sin(x)·cos(x^0.777)`（周期性波动，制造「好日子/好时辰」） |
| `public static double[] fetchFishingLikelihood()` | 取当前时刻的年/时两个似然度 | 按日历年天数与 `HOUR+MINUTE+SECOND` 分别计算，返回 `[yearLikelihood, timeLikelihood]` |
| `private static boolean hitFishingTime(Character chr, int baitLevel, double yearLikelihood, double timeLikelihood)` | 是否咬钩 | 权重和 `0.23·年 + 0.77·时 + 鱼饵`（鱼饵 = `0.0002 × 世界钓鱼倍率 × baitLevel`）> 57.777 命中；`use_debug` 且 GM 时输出明细 |
| `public static void doFishing(Character chr, int baitLevel, double yearLikelihood, double timeLikelihood)` | 执行一次钓鱼 | 校验在线/存活/钓鱼区（`MapId.isFishingArea`）/等级 ≥30；未命中播失败特效；命中按 1/3 概率分别发金币（`1400·rand+1201`×倍率 + 等级加成）、经验（`645·rand+620`×倍率）、随机物品（背包满提示），全图 dropMessage 播报战果；对本人 `showInfo` 播特效并广播他人视角 |
| `public static int getRandomItem()` | 钓鱼奖池抽取 | 按随机数分档：≥25 普通（药水/杂物）、4-7 稀有（装备/卷轴）、其余极稀有（高价值卷轴/道具），各池内均匀抽取 |
| `private static void debugFishingLikelihood()` | 全年命中率统计（调试用） | 遍历 365 天 × 24×60×60 秒统计命中数与 +10 提前命中数，输出最值与千分比 |

## WeddingPackets（util/packets）

婚礼系统封包集：`extends PacketCreator` 复用物品/外观编码，实现 CField_Wedding/CWeddingMan/OnMarriageResult 等 v83 婚礼协议；类内嵌客户端结构体镜像与 6 个枚举（源码路径：`util/packets/WeddingPackets.java`）。

### 嵌套类型（客户端结构镜像/协议枚举）

| 类型 | 说明 |
| --- | --- |
| `Field_Wedding` / `Field_WeddingPhoto` / `GW_WeddingReservation` / `WeddingWishList` / `GW_WeddingWishList` | 客户端婚礼管理器结构体的服务端镜像（注释含 IDA 反汇编布局），仅作字段参考 |
| `enum MarriageStatus` | SINGLE/ENGAGED/RESERVED/MARRIED（0-3） |
| `enum MarriageRequest` | Add/Set/Delete MarriageRecord、Load/Add/Delete/Get Reservation（0-6） |
| `enum WeddingType` | 教堂/拉斯维加斯 × 普通/高级（0x1/0x2/0xA/0xB/0x14/0x15） |
| `enum WeddingMap` | 结婚小镇/教堂与礼拜堂祭坛/照相图/出口图（`MapId` 常量） |
| `enum WeddingItem` | 婚戒/订婚戒盒（月石/星宝石/金心/银天鹅）、双方父母祝福/主婚人许可、预约收据、请柬（发送/接收）、情侣宝箱、婚礼门票等 30 个 `ItemId` 常量映射 |

### 封包方法

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static Packet onMarriageRequest(String name, int playerid)` | 求婚确认弹窗 | MARRIAGE_REQUEST，mode 0=求婚；写名字与角色 id |
| `public static Packet onTakePhoto(String groom, String bride, int field, List<Character> guests)` | 婚礼合影（未启用，需 WvsMapGen） | WEDDING_PHOTO：逐宾客编码外观/公会徽章/坐标/姿态 + 截图段（供远端 MapGen 服务转 JPG） |
| `public static Packet OnMarriageResult(int marriageId, Character chr, boolean wedding)` | 婚姻状态同步（免重登启用配偶聊天与婚戒显示） | MARRIAGE_RESULT mode 11：按性别定 groom/bride 顺序，婚礼状态写 3/订婚写 1，双写婚戒 itemId，双方 13 字节定长名 |
| `public static Packet OnMarriageResult(byte msg)` | 婚姻结果弹窗 | msg==36 时附加 "You are now engaged." |
| `public static Packet OnNotifyWeddingPartnerTransfer(int partner, int mapid)` | 伴侣换图通知（世界地图红心定位） | NOTIFY_MARRIED_PARTNER_MAP_TRANSFER |
| `public static Packet OnWeddingProgress(boolean setBlessEffect, int groom, int bride, byte step)` | 婚礼仪式进度/祝福特效 | setBlessEffect 走 WEDDING_CEREMONY_END，否则 WEDDING_PROGRESS 且 step 需先发 2 |
| `public static Packet sendWeddingInvitation(String groom, String bride)` | 打开请柬展示双方 | MARRIAGE_RESULT mode 15，附婚礼类型 short |
| `public static Packet sendWishList()` | 打开愿望单编辑窗 | MARRIAGE_REQUEST mode 9 |
| `public static Packet onWeddingGiftResult(byte mode, List<String> itemnames, List<Item> items)` | 愿望单/礼物全套交互 | mode 分支：0xC/0xE 错误空包；0x09 载入愿望单列表；0xA/0xF/0xB 载入礼物（0xB 先写愿望单再写物品清单，`addItemInfo` 编码物品）；未知 mode 记 warn |
