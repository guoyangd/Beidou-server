# 00-启动与Spring层 详细设计

- 模块路径：
  - `gms-server/src/main/java/org/gms/ServerApplication.java`
  - `gms-server/src/main/java/org/gms/manager/`
  - `gms-server/src/main/java/org/gms/config/`
  - `gms-server/src/main/java/org/gms/property/`
  - `gms-server/src/main/java/org/gms/aop/`
  - `gms-server/src/main/java/org/gms/exception/`
- 类数量：18（启动类 1 + manager 1 + config 5 + property 1 + aop 3 + exception 8（含内部类 `CachedHttpServletRequest`/`CachedServletInputStream` 则为 20））
- 依赖模块：
  - Spring Boot 3 / Spring Security / Spring MVC（`spring-boot-starter-web`、`spring-boot-starter-security`）
  - MyBatis-Flex（`@MapperScan("org.gms.dao.mapper")`）、Druid、MySQL 8+、Flyway（自动配置触发）
  - fastjson2（`JSONObject`）、SnakeYAML（手动解析 yml）、springdoc-openapi（Swagger）
  - 本项目：`org.gms.net.server.Server`/`World`（Netty 游戏服单例）、`org.gms.service.*`（ConfigService、AccountService、UserDetailsServiceImpl）、`org.gms.util.*`（JwtUtils、I18nUtil、RateLimitUtil、RequireUtil）、`org.gms.dao.entity.GameConfigDO`

---

## ServerApplication

Spring Boot 启动入口，`@SpringBootApplication` + `@MapperScan("org.gms.dao.mapper")`，在 Spring 启动前手动解析 yml 自动创建 MySQL 库（源码路径：`src/main/java/org/gms/ServerApplication.java`）。

| 字段/注解 | 说明 |
| --- | --- |
| `@MapperScan("org.gms.dao.mapper")` | 扫描 MyBatis-Flex Mapper 接口 |
| `log` | `@Slf4j` 生成的日志对象 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static void main(String[] args)` | 程序入口 | ① 调 `initDb(args)` 尝试自动建库，异常时记录错误并 `return` 直接终止进程（不让 Spring 在库缺失时反复报连接错误）；② 成功后 `SpringApplication.run(ServerApplication.class, args)` 启动 Spring 容器（随后由 `ServerManager` 拉起 Netty 游戏服） |
| `private static void initDb(String[] args) throws Exception` | 在 Flyway/JPA 自动配置拿连接之前创建目标库 | ① 读取 yml：优先 `spring.config.location` 启动参数指定的文件（不存在则直接返回），否则读 classpath 的 `application.yml`；② 用 SnakeYAML 加载为 `LinkedHashMap`，取 `mybatis-flex.datasource.mysql` 节点；③ driver/url/username/password 四项均按「JVM 系统属性 → `--param=value` 启动参数 → 环境变量（`.` 换 `_`）→ yml 值」的顺序取值；④ 从 url 截去 `?参数` 后按 `/` 拆出库名，拼出库前缀地址；⑤ 用 `DriverManager` 直连 `{prefix}mysql` 系统库，`SHOW DATABASES LIKE '<dbName>'` 已存在则返回，否则 `CREATE DATABASE <dbName> DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci` |
| `private static Connection getConnection(String driver, String url, String username, String password) throws Exception` | 建立裸 JDBC 连接 | `Class.forName(driver)` 显式加载驱动后 `DriverManager.getConnection` |
| `private static String getStartParam(String[] args, String paramName)` | 按三级优先级读取配置值 | ① `System.getProperty`；② 遍历 args 找 `--paramName=` 前缀并取 `=` 后的值；③ `System.getenv(paramName 中 . 替换为 _)`；均无则返回 null |

---

## ServerManager

Spring 与 Netty 游戏服之间的桥接器：`@Component`，实现 `ApplicationContextAware`（持有静态上下文供非 Spring 的遗留代码反向取 Bean）、`ApplicationRunner`（Spring 就绪后启动游戏服）、`DisposableBean`（容器销毁时关服）（源码路径：`src/main/java/org/gms/manager/ServerManager.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `applicationContext` | `static ApplicationContext`（`@Getter`） | 静态持有 Spring 上下文，遗留代码（如 `GameConfig`）通过 `ServerManager.getApplicationContext().getBean(...)` 获取 Spring 依赖 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException` | 注入并静态保存上下文 | Spring 回调，赋值给静态字段 |
| `public void run(ApplicationArguments args) throws Exception` | Spring 就绪后拉起游戏服并打印启动提示 | ① `Server.getInstance().init()` 初始化 OdinMS 式单例 Netty 游戏服（LoginServer + ChannelServer）；② 从容器取 `SpringDocConfigProperties`/`SwaggerUiConfigProperties`/`Environment`；③ 打印北斗版本号与构建时间（i18n `ServerManager.run.info3`）；④ 若 api-docs 与 swagger-ui 均开启，打印 Swagger 地址（本机 IP + `server.port`）；⑤ 尝试读取 classpath `static/index.html`，非 null 说明前端 dist 已嵌入 jar，打印前端访问地址（`ServerManager.run.info2`） |
| `public void destroy() throws Exception` | Spring 容器关闭回调 | `Server.getInstance().shutdownInternal(false)` 关闭游戏服（不广播停服倒计时消息） |

---

## CorsConfig

跨域配置，`@Configuration` + `WebMvcConfigurer`（源码路径：`src/main/java/org/gms/config/CorsConfig.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `vue` | `String`（`@Value("${app.vue}")`） | 允许的前端来源，如 `http://localhost:8787` |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void addCorsMappings(CorsRegistry registry)` | 放开全部接口跨域 | 对 `/**` 注册：`allowedOriginPatterns(vue)` 指定前端地址、`allowCredentials(true)` 允许携带凭证、`allowedMethods("*")` 全方法、`maxAge(3600)` 预检缓存 1 小时 |

---

## GameConfig

动态游戏配置单例（非 Spring Bean），启动时从 `game_config` 表加载为 JSON 树，结构 `type → subType → code → {value, clazz}`（如 `world.0.exp_rate`、`server.global.WORLDS`），支持热更新并把部分参数即时写回 `World` 对象（源码路径：`src/main/java/org/gms/config/GameConfig.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `config` | `static final GameConfig` | 饿汉单例；私有构造器中经 `ServerManager.getApplicationContext().getBean(ConfigService.class)` 调 `loadGameConfigs()` 全量加载 |
| `properties` | `JSONObject` | 三层嵌套的配置树 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `private GameConfig()` | 私有构造 | 从 Spring 容器取 `ConfigService`，遍历 `GameConfigDO` 列表逐条 `add` 进配置树 |
| `public static void add(GameConfigDO gameConfigDO)` | 新增一条配置到树 | 委托私有 `add(config, do)`：逐层取/建 `type → subType → code` JSONObject，末层写入 `value`（`configValue`）与 `clazz`（`configClazz` 全限定类名） |
| `public static void remove(GameConfigDO gameConfigDO)` | 从树中删除一条配置并清理空层 | 按 type/subType 逐层判空提前返回；删除 code 节点后，若 subType 空则连同删除，type 空同理，避免残留空对象 |
| `public static void update(GameConfigDO gameConfigDO)` | 热更新一条配置 | ① `getValueProp` 定位末层节点，不存在则转 `add`；② 覆写 `value`；③ world 类参数即时写回运行中的 `World`：`exp_rate/meso_rate/drop_rate/boss_drop_rate/quest_rate/travel_rate/fishing_rate` 调对应 `setXxxRate(Float.parseFloat)`，`server_message/event_message` 重新读取后 `setServerMessage/setEventMessage`，`recommend_message` 更新 `Server.worldRecommendedList` 对应下标的 Pair，`flag` 写回 `world.setFlag`；④ 其余全局参数：`allow_steal_quest_item` 变更时 `MonsterInformationProvider.getInstance().clearDrops()` 触发怪物掉落重载 |
| `public static Object getObject(String key)` / `public static <T> T get(String key)` | 按全局 key 取值（忽略 type，遍历所有大类） | 委托 `get(key, null)` |
| `public static <T> T get(String key, T defaultValue)` | 全局 key + 默认值 | 遍历 `properties` 的每个 type，用 `get(type, key, null)` 两层查找，首个非 null 结果返回，否则返回默认值 |
| `public static <T> T get(String type, String key)` / `get(String type, String key, T defaultVal)` | 在指定 type 下按 key 查（两层查找，遍历该 type 的所有 subType） | 经 `getValueProp(type, key)` 定位后 `getValue(valueProp)` 按 `clazz` 反序列化 |
| `public static <T> T get(String type, String subType, String key)` / `get(String type, String subType, String key, T defaultVal)` | 精确三层查找 | 经 `getValueProp(type, subType, key)` 定位后按 `clazz` 反序列化，缺失返回默认值 |
| `private static <T> T getValue(JSONObject valueProp)` | 按配置自带的 `clazz` 转换 value | `Class.forName(clazz)`（找不到类直接抛 RuntimeException）；先 `valueProp.getObject("value", clz)`，捕获 JSONException 后回退 `JSONObject.parseObject(getString("value"), clz)`（value 存的是字符串形式时） |
| `public static JSONObject getValueProp(String type, String subType, String key)` | 精确定位末层节点 | 逐层判空返回 null；注意 key 会 `toLowerCase()` 后再取 |
| `public static JSONObject getValueProp(String type, String key)` | 在 type 下遍历所有 subType 找 key | 任一 subType 下命中（key 小写化）即返回该节点 |
| `public static Integer getInteger(String key)` … `getLong/getShort/getByte/getFloat/getDouble/getBoolean/getString` | 全局取包装类型（null 默认） | 统一走 `getValue(key, null/“”/对应零值, mapper)`，mapper 用 fastjson2 对应的 `getXxx("value")` |
| `public static int getIntValue(String key)` … `getLongValue/getShortValue/getByteValue/getFloatValue/getDoubleValue/getBooleanValue/getStringValue` | 全局取原始类型（零值/空串默认） | 同上，`getStringValue` 对 null 再兜底 `""` |
| `public static <T> T getObject(String key, Class<T> clz)` | 按「调用方指定类型」全局取值 | `getObject("value", clz)` 失败（JSONException）时回退字符串解析（适配 Map 等复杂类型） |
| `private static <T> T getValue(String key, T defaultVal, Function<JSONObject, T> mapper)` | 上述类型化取值的公共实现 | 遍历所有 type 调 `getValueProp(type, key)`，首个命中节点交给 mapper，否则返回默认值 |
| `public static <T> T getWorld(int worldId, String key)` | 按大区取值 | `get("world", String.valueOf(worldId), key)` |
| `public static <T> T getServer(String key)` | 按服务端大类取值 | `get("server", key)`，即 server 下遍历所有 subType |
| `public static int getWorldInt(int worldId, String key)` / `getServerInt(String key)` | 类型化取 int | 直接 `getValueProp` 定位后 `getIntValue("value")`，节点缺失返回 0（不走全局遍历，避免不同大区同名参数取错） |
| `public static byte getWorldByte/getServerByte`、`long getWorldLong/getServerLong`、`short getWorldShort/getServerShort`、`float getWorldFloat/getServerFloat`、`double getWorldDouble/getServerDouble` | 类型化取对应原始类型 | 同上模式，缺失返回对应零值 |
| `public static String getWorldString/getServerString`、`boolean getWorldBoolean/getServerBoolean` | 类型化取字符串/布尔 | 同上模式，缺失返回 `""`/`false` |
| `public static <T> T getWorldObject(int worldId, String key, Class<T> clz)` / `getWorldObject(int worldId, String key, T defaultVal)` | 按大区取复杂对象 | 委托 `getValue(false, subType, key, clz)`；带默认值重载按 `defaultVal.getClass()` 转换，null 时返回默认值 |
| `public static <T> T getServerObject(String key, Class<T> clz)` / `getServerObject(String key, T defaultVal)` | 按服务端大类取复杂对象 | 委托 `getValue(true, null, key, clz)` |
| `private static <T> T getValue(boolean isServer, String subType, String key, Class<?> clz)` | 上两组的公共实现 | isServer 走 `getValueProp("server", key)` 两层查找，否则 `getValueProp("world", subType, key)` 三层查找；`getObject("value", clz)` 失败回退字符串解析 |
| `public static <T> T getWorldObject(int worldId, String key, TypeReference<T> type)` / `getServerObject(String key, TypeReference<T> type)` | 按泛型类型标记取值（如 List/嵌套结构） | 定位节点后 `getObject("value", type)`，JSONException 回退 `parseObject(getString, type)` |
| `public static JSONObject getConfig()` | 暴露整棵配置树 | 返回 `config.properties`，供配置导入/导出等场景 |

---

## I18nConfig

国际化资源装配，`@Configuration`，为三类 properties 文件各建独立 `MessageSource` Bean，避免单一 Bean 循环扫描全部 basename 浪费时间（源码路径：`src/main/java/org/gms/config/I18nConfig.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public MessageSource messageSource()` | 通用业务文案源 | `ResourceBundleMessageSource`，basename `i18n/message`，UTF-8 编码 |
| `public MessageSource logSource()` | 日志文案源 | basename `i18n/log`，UTF-8 |
| `public MessageSource exceptionSource()` | 异常文案源 | basename `i18n/exception`，UTF-8（`BizExceptionEnum` 静态初始化即经 `I18nUtil` 使用它） |

---

## ServerConfig

Servlet Filter 注册与 OpenAPI（Swagger）文档配置（源码路径：`src/main/java/org/gms/config/ServerConfig.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public FilterRegistrationBean<ServerFilter> filterRegistrationBean(ServerFilter serverFilter)` | 注册 `ServerFilter` 到 Servlet 过滤器链 | 拦截 `/*` 全部请求（限流、封禁 IP 校验、请求体缓存均在该 Filter 中完成） |
| `public OpenAPI openAPI()` | 定义 Swagger 文档 | 文档标题 `BeiDou api`、描述含项目 GitHub 地址、版本 v1；声明 `Authorization` 请求头为 APIKEY 型 SecurityScheme 并对全部接口添加该安全要求，使 Swagger UI 可直接填 JWT |

---

## SpringSecurityConfig

Spring Security 配置，`@EnableWebSecurity` + `@EnableMethodSecurity`（开启方法级注解鉴权），JWT 无状态认证（源码路径：`src/main/java/org/gms/config/SpringSecurityConfig.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `userDetailsService` | `UserDetailsServiceImpl` | 从库加载用户（构造器注入） |
| `unauthorizedHandler` | `AuthEntryPointJwt` | 未认证入口点（构造器注入） |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public AuthTokenFilter authenticationJwtTokenFilter()` | 声明 JWT 过滤器 Bean | `new AuthTokenFilter()`（字段注入依赖） |
| `public DaoAuthenticationProvider authenticationProvider()` | DAO 认证提供者 | 绑定 `userDetailsService` 与 BCrypt `passwordEncoder`，供登录时账号密码校验 |
| `public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception` | 暴露 AuthenticationManager | 从 `AuthenticationConfiguration` 获取，供 AuthService 登录使用 |
| `public PasswordEncoder passwordEncoder()` | 密码加密器 | `BCryptPasswordEncoder` |
| `public SecurityFilterChain filterChain(HttpSecurity http) throws Exception` | 主过滤链 | ① 启用 CORS（对接 `CorsConfig`）、禁用 CSRF；② 未认证时交给 `unauthorizedHandler`；③ 会话策略 STATELESS（纯 token）；④ 放行：`/auth/**`、`/swagger-ui/**`、`/v3/api-docs/**`、前端静态资源（`/`、`/static/**`、`/index.html`、`/assets/**`），其余 `anyRequest().authenticated()`；⑤ 挂 `authenticationProvider`；⑥ `addFilterBefore(authenticationJwtTokenFilter(), UsernamePasswordAuthenticationFilter.class)` 在用户名密码过滤器前解析 JWT |

---

## ServiceProperty

`gms.service` 前缀的配置属性 Bean（`@ConfigurationProperties` + `@Component` + `@Data`），承载服务语言、限流与网络地址端口等运营配置（源码路径：`src/main/java/org/gms/property/ServiceProperty.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `language` | `String` | 服务语言（`zh-CN`/`en-US`），决定 wz/scripts 双语覆盖目录 |
| `rateLimit` | `RateLimitProperty` | 限流子配置 |
| `wanHost` | `String` | 外网地址 |
| `lanHost` | `String` | 内网地址 |
| `localhost` | `String` | 本机地址 |
| `loginPort` | `int` | 登录服端口 |

内嵌 `RateLimitProperty`（`@Data`）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `enabled` | `boolean` | 是否启用限流 |
| `limit` | `int` | 时间窗内允许的请求数 |
| `duration` | `long` | 时间窗长度 |
| `autoBan` | `boolean` | 超限是否自动封禁 |

（本类为纯数据类，无自定义公有方法，getter/setter 由 Lombok 生成。）

---

## AuthTokenFilter

JWT 认证过滤器，继承 `OncePerRequestFilter`，每次请求解析 `Authorization: Bearer <token>` 并写入 SecurityContext；含 Swagger 测试后门（源码路径：`src/main/java/org/gms/aop/AuthTokenFilter.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `jwtUtils` | `JwtUtils`（`@Autowired`） | token 解析/校验工具 |
| `userDetailsService` | `UserDetailsServiceImpl`（`@Autowired`） | 按用户名加载用户 |
| `springDocConfigProperties` / `swaggerUiConfigProperties` | springdoc 属性（`@Autowired(required = false)`） | 判断 Swagger 是否开启，可空以兼容无 springdoc 环境 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain) throws ServletException, IOException` | 每请求一次的认证逻辑 | ① `/auth/` 前缀请求直接放行（登录/刷新不需要身份）；② `parseJwt` 取 token；③ Swagger 后门：springdoc 与 swagger-ui Bean 均存在、token 恰为 `"swagger"` 且两个开关均开启时，直接 `loadUserByUsername("admin")` 以管理员身份通过（仅测试用，生产必须关 Swagger）；④ 正常分支：token 非空且 `jwtUtils.validateJwtToken` 通过时，取出用户名加载 `UserDetails`，构造无凭证的 `UsernamePasswordAuthenticationToken`（含权限与 WebAuthenticationDetails），放入 `SecurityContextHolder`；⑤ 任何异常记录日志并关闭请求/响应流防泄漏后直接返回（不继续链）；⑥ 正常放行 `filterChain.doFilter` |
| `private String parseJwt(HttpServletRequest request)` | 从请求头解析 token | 取 `Authorization` 头，非空且以 `"Bearer "` 开头则截取第 7 位之后的子串，否则返回 null |

---

## AuthEntryPointJwt

未认证（401）入口点，`@Component` 实现 `AuthenticationEntryPoint`（源码路径：`src/main/java/org/gms/aop/AuthEntryPointJwt.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException, ServletException` | 认证失败统一响应 | 记录 error 日志（URI + 异常消息），`response.sendError(401, "Error: Unauthorized")` |

---

## ServerFilter

业务前置过滤器（`@Component`，继承 `HttpFilter`），在 Security 之后执行：真实 IP 解析、封禁 IP 拦截、限流、并把请求体包装为可重复读的 `CachedHttpServletRequest`（源码路径：`src/main/java/org/gms/aop/ServerFilter.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `accountService` | `AccountService`（`@AllArgsConstructor` 构造注入） | 提供 `isBanned(ip)` 封禁校验 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `protected boolean shouldNotFilter(final HttpServletRequest request)` | 判定无需业务过滤的请求 | `/assets`、`/swagger-ui`、`/v3/api-docs` 前缀或根路径 `/` 返回 true（前端静态资源与 Swagger 直接放行） |
| `protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException` | 主过滤逻辑 | ① 解析真实 IP：`remoteAddr` 为空依次回退 `X-Forwarded-For`、`X-Real-IP`，仍为空则抛出「Unknown remote address」；② 封禁 IP：`accountService.isBanned(remoteAddr)` 为真时关闭请求流并抛 BizException（消息含三级 IP 便于排查）；③ 限流：`RateLimitUtil.getInstance().check(remoteAddr)` 不通过则抛 BizException；④ 上述 try 块任何异常记录日志、关闭请求/响应流防泄漏并直接返回；⑤ `shouldNotFilter` 或 `Content-Type` 为空/multipart 上传的请求原样放行；⑥ 其余请求以 `new CachedHttpServletRequest(request)` 包装后再 `chain.doFilter`（使 body 可多次读取，供鉴权/日志/参数校验复用） |

内部类 `CachedHttpServletRequest`（`HttpServletRequestWrapper`）：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public CachedHttpServletRequest(HttpServletRequest request) throws IOException` | 构造时缓存请求体 | `cacheRequestBody`：1KB 缓冲循环读入 `ByteArrayOutputStream` 存为 `cachedBody` 字节数组 |
| `public BufferedReader getReader()` | 覆写读字符流 | 由 `getInputStream()` 包装 InputStreamReader/BufferedReader |
| `public ServletInputStream getInputStream()` | 覆写读字节流 | 每次返回基于 `cachedBody` 的新 `CachedServletInputStream` |

内部类 `CachedServletInputStream`（`ServletInputStream`）：

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public CachedServletInputStream(byte[] cachedBody)` | 构造 | 包装 `ByteArrayInputStream` |
| `public boolean isFinished()` | 流是否读完 | `available() == 0` |
| `public boolean isReady()` | 是否就绪 | 恒 true |
| `public void setReadListener(ReadListener listener)` | 异步读取 | 不支持，抛 `UnsupportedOperationException` |
| `public int read()` | 读一字节 | 委托 `ByteArrayInputStream.read()` |

---

## BaseErrorInfoInterface

错误码契约接口，供枚举实现统一的 code/msg 访问（源码路径：`src/main/java/org/gms/exception/BaseErrorInfoInterface.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `Integer getResultCode()` | 取错误码 | — |
| `String getResultMsg()` | 取错误文案 | — |

---

## BizExceptionEnum

业务错误码枚举，实现 `BaseErrorInfoInterface`，文案经 `I18nUtil.getExceptionMessage` 从 `i18n/exception_*.properties` 取得（源码路径：`src/main/java/org/gms/exception/BizExceptionEnum.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `SUCCESS` | 20000 | 成功（前端拦截器以此判定成功） |
| `BODY_NOT_MATCH` | 40000 | 请求体不匹配（也被用于运行时异常兜底） |
| `REQUEST_METHOD_SUPPORT` | 40001 | 请求方法不支持 |
| `ILLEGAL_PARAMETERS` | 40002 | 非法参数 |
| `NOT_FOUND` | 40004 | 资源不存在 |
| `INTERNAL_SERVER_ERROR` | 50000 | 服务器内部错误 |
| `SERVER_BUSY` | 50003 | 服务器繁忙 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public Integer getResultCode()` | 取码 | 返回构造时传入的 `resultCode` |
| `public String getResultMsg()` | 取文案 | 返回构造时传入的 `resultMsg`（i18n 已解析） |

---

## BizException

业务运行时异常基类（`@Getter/@Setter`），携带 `errorCode/errorMsg`，提供静态构造与抛出快捷方法；覆写 `fillInStackTrace` 关闭栈捕获以提升性能（源码路径：`src/main/java/org/gms/exception/BizException.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `errorCode` | `protected Integer` | 业务错误码 |
| `errorMsg` | `protected String` | 业务错误文案 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public BizException()` | 空构造 | — |
| `public BizException(BaseErrorInfoInterface errorInfoInterface)` | 按枚举构造 | `super(code)`，取枚举的 code/msg |
| `public BizException(BaseErrorInfoInterface errorInfoInterface, Throwable cause)` | 按枚举 + 原因构造 | 同上并挂 cause |
| `public BizException(String errorMsg)` | 仅消息构造 | code 为 null，`super(errorMsg)` |
| `public BizException(Integer errorCode, String errorMsg)` | code + 消息构造 | `super(String.valueOf(errorCode))` |
| `public BizException(Integer errorCode, String errorMsg, Throwable cause)` | code + 消息 + 原因构造 | 同上并挂 cause |
| `public static BizException illegalArgument()` | 生成「非法参数」异常（返回由调用方抛出） | `new BizException(BizExceptionEnum.ILLEGAL_PARAMETERS)` |
| `public static BizException illegalArgument(String errorMsg)` | 生成带自定义消息的非法参数异常 | code 取 `ILLEGAL_PARAMETERS.getResultCode()`，msg 自定义 |
| `public static void throwIllegalArgument()` | 直接抛出非法参数异常 | `throw new BizException(...)`（注释说明堆栈会多一层） |
| `public static void throwIllegalArgument(String errorMsg)` | 直接抛出带消息的非法参数异常 | 同上 |
| `public String getMessage()` | 覆写取消息 | 返回 `errorMsg` 而非 Throwable 原生 message |
| `public Throwable fillInStackTrace()` | 关闭栈捕获 | 直接 `return this`，异常不填充堆栈 |

---

## EmptyMovementException

HeavenMS 遗留受检异常（AGPL 头），表示收到的移动包为空（源码路径：`src/main/java/org/gms/exception/EmptyMovementException.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public EmptyMovementException(InPacket inPacket)` | 构造 | 消息 `"Empty movement: " + inPacket`（包对象 toString） |

---

## EventInstanceInProgressException

HeavenMS 遗留受检异常（AGPL 头），表示同名活动副本已在进行中，无法再次开启（源码路径：`src/main/java/org/gms/exception/EventInstanceInProgressException.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `EIIP_KEY` | `public static String` | 消息前缀常量 `"Event instance "` |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public EventInstanceInProgressException(String eventName, String eventInstance)` | 构造 | 消息 `EIIP_KEY + "already in progress - " + eventName + ", EM: " + eventInstance` |

---

## GlobalExceptionHandler

`@ControllerAdvice` 全局异常处理器，把各类异常统一转为 `ResultBody` 错误响应（源码路径：`src/main/java/org/gms/exception/GlobalExceptionHandler.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public ResultBody<Object> bizExceptionHandler(HttpServletRequest req, BizException e)` | 处理业务异常 | error 日志记录 `e.getErrorMsg()`，返回 `ResultBody.error(req, e.getErrorCode(), e.getErrorMsg())`（保留业务码） |
| `public ResultBody<Object> exceptionHandler(HttpServletRequest req, RuntimeException e)` | 处理运行时异常（统一覆盖 IllegalArgument/NullPointer/UnsupportedOperation 等） | 日志含完整堆栈，返回 `ResultBody.error(req, BizExceptionEnum.BODY_NOT_MATCH)`（40000） |
| `public ResultBody<Object> exceptionHandler(HttpServletRequest req, ServletException e)` | 处理 Servlet 异常（如 Method Not Supported） | 日志含堆栈，返回 `REQUEST_METHOD_SUPPORT`（40001） |
| `public ResultBody<Object> exceptionHandler(HttpServletRequest req, Exception e)` | 兜底处理其他异常 | 日志含堆栈，返回 `INTERNAL_SERVER_ERROR`（50000） |

---

## IdTypeNotSupportedException

受检异常：给定的 ID 类型不被支持（源码路径：`src/main/java/org/gms/exception/IdTypeNotSupportedException.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public IdTypeNotSupportedException()` | 默认构造 | 消息 `"The given ID type is not supported"` |
| `public IdTypeNotSupportedException(String message)` | 自定义消息构造 | `super(message)` |

---

## NotEnabledException

运行时异常：功能未在 `ServerConstant` 中开启（源码路径：`src/main/java/org/gms/exception/NotEnabledException.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public NotEnabledException()` | 默认构造 | 消息 `"Feature not enabled, please enable the feature in ServerConstant"` |
| `public NotEnabledException(String message)` | 自定义消息构造 | `super(message)` |
