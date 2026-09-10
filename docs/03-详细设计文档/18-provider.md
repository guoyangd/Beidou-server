# 18-数据提供层（provider / WZ XML 加载）详细设计

- 模块路径：`gms-server/src/main/java/org/gms/provider/`（根包 9 类）与 `gms-server/src/main/java/org/gms/provider/wz/`（wz 实现 7 类）
- 类数量：16（根包：接口 `Data`/`DataEntity`/`DataEntry`/`DataDirectoryEntry`/`DataFileEntry`/`DataProvider` + 类 `DataTool`/`DataProviderFactory`/`LocalizedDataProvider`；wz 包：枚举 `DataType`/`WZFiles` + 类 `WZEntry`/`WZFileEntry`/`WZDirectoryEntry`/`XMLWZFile`/`XMLDomMapleData`）
- 依赖模块：
  - JDK：`javax.xml.parsers`（DOM 解析）、`org.w3c.dom`、NIO（`Path`/`Files`/`DirectoryStream`）、java.awt（`Point`）
  - 本项目：`org.gms.constants.game.GameConstants.parseNumber`（数字字符串解析）、`org.gms.manager.ServerManager` + `org.gms.property.ServiceProperty`（读取 `gms.service.language` 语言配置）、SLF4J
- 总体说明：本模块是服务端读取 WZ 游戏数据的统一入口。数据以「导出的 XML 文件」形式存放于 `gms-server/wz/`（英文基础，如 `wz/String.wz/` 目录）与 `wz-<lang>/`（如 `wz-zh-CN/`，只放已本地化的文件）两个目录树中。`DataProviderFactory.getDataProvider(WZFiles.X)` 按语言返回 `XMLWZFile` 或「语言层优先、基础层兜底」的 `LocalizedDataProvider`；节点读取用 DOM 惰性封装为 `XMLDomMapleData`（实现 `Data` 接口）；取值时的类型转换/默认值处理集中在 `DataTool`。

---

## Data（根包，接口）

WZ 数据节点接口：一个 `Data` 即 WZ 树中的一个节点（imgdir/int/string/vector 等），既是实体（`DataEntity`，有名字与父节点）又是可迭代容器（`Iterable<Data>`，迭代子节点）（源码路径：`provider/Data.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `String getName()` | 节点名（覆盖 DataEntity） | WZ 节点的 name 属性 |
| `DataType getType()` | 节点数据类型 | 见 `DataType` 枚举 |
| `List<Data> getChildren()` | 全部子节点列表 | 仅 ELEMENT 节点（实现保证） |
| `Data getChildByPath(String path)` | 按 `/` 分隔的相对路径取子孙节点 | 首段可为 `..` 表示从父节点起查（实现支持）；找不到返回 null |
| `Object getData()` | 取节点承载的值 | 数值/字符串返回对应包装类型，vector/canvas 返回 `Point`，容器型返回 null |
| `String getAttributeValue(String name)` | 取 XML 节点任意属性值 | BeiDou 扩展（如读 canvas 的 width/height 等）；无该属性返回 null |

## DataEntity（根包，接口）

数据实体基础接口：任何有名字、有父引用的 WZ 结构（节点或目录项）的公共父类型（源码路径：`provider/DataEntity.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `String getName()` | 实体名 | — |
| `DataEntity getParent()` | 父实体 | 根节点返回 null |

## DataEntry（根包，接口）

WZ 包内「条目」（文件/目录）的描述接口，继承 `DataEntity`——保留 OdinMS 二进制 WZ 时代的 size/checksum/offset 元信息，XML 实现下多为 0（源码路径：`provider/DataEntry.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `String getName()` / `DataEntity getParent()` | 继承自 DataEntity | — |
| `int getSize()` | 条目大小（字节） | XML 实现恒 0 |
| `int getChecksum()` | 校验和 | XML 实现恒 0 |
| `int getOffset()` | 在 WZ 包内的偏移 | XML 实现恒 0（文件条目可 set） |

## DataDirectoryEntry（根包，接口）

WZ 目录条目接口：可枚举子目录与文件，供 `DataProvider.getRoot()` 之后的导航遍历（如遍历 `Map.wz/Map0` 下所有地图）（源码路径：`provider/DataDirectoryEntry.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `List<DataDirectoryEntry> getSubdirectories()` | 子目录列表 | 实现返回不可变视图 |
| `List<DataFileEntry> getFiles()` | 子文件（.xml）列表 | 实现返回不可变视图 |
| `DataEntry getEntry(String name)` | 按名取直接子条目（目录或文件） | 未找到返回 null |

## DataFileEntry（根包，接口）

WZ 文件条目接口：在 `DataEntry` 之上补充可写偏移（二进制 WZ 加载两遍扫描时回填用）（源码路径：`provider/DataFileEntry.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `void setOffset(int offset)` | 设置文件在包内的偏移 | XML 实现下无实际作用，保留接口兼容 |

## DataProvider（根包，接口）

数据源接口：代表一个 WZ 分类（如 `String.wz`、`Map.wz`）的读取入口（源码路径：`provider/DataProvider.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `Data getData(String path)` | 按路径加载数据节点（不含 `.xml` 后缀） | 未找到返回 null（`LocalizedDataProvider` 语义为语言层 null 才回退） |
| `DataDirectoryEntry getRoot()` | 取根目录条目（导航树） | — |

## DataProviderFactory（根包）

数据源工厂：按 `WZFiles` 枚举构造 `DataProvider`，并实现双语目录合并——语言目录（`wz-<lang>/`）存在时返回 `LocalizedDataProvider`（语言层优先 + 基础层回退），否则直接返回基础层（源码路径：`provider/DataProviderFactory.java`）。全服各处（`ItemInformationProvider`、`MapFactory`、`LifeFactory`、`SkillFactory` 等）统一经 `getDataProvider(WZFiles.X)` 取数。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `private static DataProvider getWZ(Path in)` | 构造单个 XML 数据源 | `new XMLWZFile(in)` |
| `public static DataProvider getDataProvider(WZFiles in)` | 取（可能带语言覆盖的）数据源 | `basePath = in.getBaseFile()`（`wz/<name>.wz`）、`languagePath = in.getLanguageFile()`（`wz-<lang>/<name>.wz`）；语言路径不存在或与基础路径相同 → 直接返回基础 `XMLWZFile`；否则返回 `new LocalizedDataProvider(语言层, 基础层)`——中文 WZ 只维护被本地化的文件，缺失文件回退原始 WZ |

## LocalizedDataProvider（根包）

双语数据源装饰器：把「语言层数据源」与「基础（英文）数据源」组合成一个 `DataProvider`，实现文件级 i18n 覆盖（BeiDou 汉化核心机制之一）（源码路径：`provider/LocalizedDataProvider.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `localized` | `final DataProvider` | 语言层（如 `wz-zh-CN/Character.wz`） |
| `fallback` | `final DataProvider` | 基础层（`wz/Character.wz`，英文） |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public LocalizedDataProvider(DataProvider localized, DataProvider fallback)` | 构造函数 | 保存两层 |
| `public Data getData(String path)` | 语言层优先加载 | `localized.getData(path)` 非 null 返回之；为 null（该 XML 未本地化）回退 `fallback.getData(path)`，避免为少量翻译复制整包资源 |
| `public DataDirectoryEntry getRoot()` | 导航树取基础层完整文件树 | 保证未翻译资源仍可枚举 |

## DataTool（根包）

WZ 取值静态工具集：对 `Data` 节点做类型转换与 null 安全读取（默认值重载），是业务代码读取 WZ 数值/字符串/坐标的标配入口。大量重载按「是否带 path」「是否带默认值」「是否容忍 STRING 节点存数字」三个维度展开（源码路径：`provider/DataTool.java`）。

### 方法：字符串读取

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static String getString(Data data)` | 取字符串值 | 直接强转 `data.getData()` |
| `public static String getString(Data data, String def)` | 取字符串（null 安全） | data 或其值为 null 返回 def |
| `public static String getString(String path, Data data)` / `getString(String path, Data data, String def)` | 按路径取子节点字符串 | 先 `getChildByPath` 再走上述两方法 |

### 方法：数值读取

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static double getDouble(Data data)` / `float getFloat(Data data)` | 取 double/float | 强转 `getData()`（无 null 保护） |
| `public static int getInt(Data data)` | 取 int | data/值为 null 返回 0 |
| `public static int getInt(String path, Data data)` / `getInt(String path, Data data, int def)` / `getInt(Data data, int def)` | 按路径/带默认值取 int | def 版本额外容忍 STRING 型数字（`Integer.parseInt`），非 Integer 数值按 `Short` 拆箱 |
| `public static int getIntConvert(Data data)` / `(Data data, int def)` / `(String path, Data data)` / `(String path, Data data, int def)` | 「可转换」取 int（4 重载） | 节点为 STRING 时解析数字（def 版本先剥尾部 `%` 并捕获 NumberFormatException 返回 def；path+def 版本子节点缺失直接 def） |
| `public static Integer getInteger(String path, Data data)` / `int getInteger(String path, Data data, int def)` | 取包装 Integer/带默认 int | 子节点缺失或值为 null 返回 null/def；STRING 解析、数值 `((Number)...).intValue()` |
| `public static Short getShort(String path, Data data)` / `short getShort(String path, Data data, short def)` | 取 short（含 STRING 解析） | 同 getInteger 模式 |
| `public static Long getLong(String path, Data data)` / `long getLong(String path, Data data, long def)` | 取 long（含 STRING 解析） | 同上 |

### 方法：坐标与杂项

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public static Point getPoint(Data data)` / `getPoint(String path, Data data)` / `getPoint(String path, Data data, Point def)` | 取 vector 坐标（3 重载） | 强转 `getData()`；def 版本子节点缺失返回默认 |
| `public static String getFullDataPath(Data data)` | 求节点在 WZ 树中的完整路径 | 沿 parent 链逐级拼 `name/`，最后去掉末尾斜杠（排障用） |
| `public static String getAttributeValue(Data data, String name)` / `(Data data, String name, String def)` / `int getAttributeValueInt(Data data, String name, int def)` | 读 XML 属性（BeiDou 扩展） | 委托 `Data.getAttributeValue`；def 版本做 null 兜底，Int 版本 `Integer.parseInt` |

---

## DataType（wz 包，枚举）

WZ 节点数据类型枚举，对应 WZ 二进制格式的类型标记（XML 化后由标签名映射，见 `XMLDomMapleData.getType`）（源码路径：`provider/wz/DataType.java`）。

| 枚举值 | 说明 |
| --- | --- |
| `NONE` / `IMG_0x00` | 无类型 / null 节点（XML 标签 `null`） |
| `SHORT` / `INT` / `FLOAT` / `DOUBLE` | 数值类型（标签 `short`/`int`/`float`/`double`） |
| `STRING` / `EXTENDED` | 字符串 / WZ 扩展类型 |
| `PROPERTY` | 属性容器（标签 `imgdir`，即目录节点） |
| `CANVAS` / `VECTOR` / `CONVEX` / `SOUND` / `UOL` | 画布（尺寸→Point）/ 向量（坐标→Point）/ 凸包 / 音频 / 内部链接（标签同名，`uol` 值同 string） |
| `UNKNOWN_TYPE` / `UNKNOWN_EXTENDED_TYPE` | 未知类型占位 |

## WZFiles（wz 包，枚举）

WZ 分类清单枚举：13 个 WZ 目录（`Quest`/`Etc`/`Item`/`Character`/`String`/`List`/`Mob`/`Map`/`Npc`/`Reactor`/`Skill`/`Sound`/`UI`），并负责把分类名映射为「基础路径 `wz/<name>.wz`」与「语言路径 `wz-<lang>/<name>.wz`」（源码路径：`provider/wz/WZFiles.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `fileName` | `final String` | 分类名 + `.wz`（目录名，如 `String.wz`） |
| `DIRECTORY` | `static final String = "wz"` | 基础 WZ 根目录名 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `WZFiles(String name)` | 构造函数 | 拼接 `.wz` 后缀 |
| `public Path getFile()` | 取实际使用的 WZ 路径（兼容旧调用） | 语言路径存在则用 `wz-<lang>/...`，否则 `wz/...` |
| `public Path getBaseFile()` | 基础（英文）路径 | `Path.of("wz", fileName)` |
| `public Path getLanguageFile()` | 语言路径 | 经 `ServerManager` 取 `ServiceProperty.getLanguage()`，拼 `wz-<lang>/<fileName>` |
| `public String getFilePath()` | 路径字符串 | `getFile().toString()` |

## WZEntry（wz 包）

`DataEntry` 的通用实现：WZ 条目（文件/目录共用基类），保存名字/大小/校验和/父引用（源码路径：`provider/wz/WZEntry.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `name` / `size` / `checksum` | `final` | 条目名、大小、校验和（XML 下 size/checksum 为 0） |
| `offset` | `int` | 偏移（基类版本只读，子类覆盖） |
| `parent` | `final DataEntity` | 父实体 |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public WZEntry(String name, int size, int checksum, DataEntity parent)` | 构造函数 | 直接赋值 |
| `public String getName()` / `int getSize()` / `int getChecksum()` / `int getOffset()` / `DataEntity getParent()` | 全量 getter | 简单返回字段 |

## WZFileEntry（wz 包）

WZ 文件条目：`WZEntry` + `DataFileEntry`，额外持有可写的独立 `offset` 字段（源码路径：`provider/wz/WZFileEntry.java`）。

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public WZFileEntry(String name, int size, int checksum, DataEntity parent)` | 构造函数 | `super(...)`，offset 默认 0 |
| `public int getOffset()` | 取偏移 | 返回自有 offset（遮蔽父类字段） |
| `public void setOffset(int offset)` | 写偏移 | 实现 `DataFileEntry` 接口 |

## WZDirectoryEntry（wz 包）

WZ 目录条目：`WZEntry` + `DataDirectoryEntry`，维护子目录/文件两张列表与按名索引 Map；`XMLWZFile` 扫描磁盘目录时用它构建导航树（源码路径：`provider/wz/WZDirectoryEntry.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `subdirs` / `files` | `List` | 子目录/文件列表 |
| `entries` | `Map<String, DataEntry>` | 名字 → 条目索引（目录与文件统一） |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public WZDirectoryEntry(String name, int size, int checksum, DataEntity parent)` / `WZDirectoryEntry()` | 两个构造 | 无参版本构造无名根（name=null, parent=null） |
| `public void addDirectory(DataDirectoryEntry dir)` | 登记子目录 | 入 subdirs 与 entries |
| `public void addFile(DataFileEntry fileEntry)` | 登记文件 | 入 files 与 entries（文件名去掉 `.xml` 后缀后登记） |
| `public List<DataDirectoryEntry> getSubdirectories()` / `List<DataFileEntry> getFiles()` | 取子项列表 | `Collections.unmodifiableList` 包装 |
| `public DataEntry getEntry(String name)` | 按名取条目 | entries 查表，未找到 null |

## XMLWZFile（wz 包）

XML 实现的 `DataProvider`：构造时递归扫描一个 WZ 目录（如 `wz/Map.wz/`）构建 `WZDirectoryEntry` 导航树；`getData` 时惰性把单个 `.xml` 文件 DOM 解析为 `XMLDomMapleData`（源码路径：`provider/wz/XMLWZFile.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `root` | `final Path` | WZ 目录根（如 `wz/String.wz`） |
| `rootForNavigation` | `final WZDirectoryEntry` | 导航树根（以目录名为名） |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public XMLWZFile(Path fileIn)` | 构造函数 | 保存根路径，`fillMapleDataEntitys` 递归建导航树 |
| `private void fillMapleDataEntitys(Path lroot, WZDirectoryEntry wzdir)` | 递归扫描目录 | `DirectoryStream` 遍历：是目录且名不以 `.img` 结尾 → 递归子目录；名以 `.xml` 结尾 → 登记 `WZFileEntry`（名字剥 `.xml`）；IO 异常 warn 日志 |
| `public synchronized Data getData(String path)` | 加载一个数据节点 | ① 拼 `root/<path>.xml`，不存在返回 null；② `FileInputStream` 打开后 `new XMLDomMapleData(fis, imageDataDir.getParent())`——imageDataDir 指向同名目录（vector/canvas 等资源引用的基准目录）；③ 文件不存在抛 RuntimeException（并发下被删） |
| `public DataDirectoryEntry getRoot()` | 取导航树 | 返回 rootForNavigation |

## XMLDomMapleData（wz 包）

`Data` 接口的 DOM 实现：一个实例包一个 `org.w3c.dom.Node`，全部读方法 `synchronized`（DOM Node 非线程安全）；按 XML 标签名映射 `DataType`，按类型把 `value`/`x`/`y`/`width`/`height` 属性转成 Java 值（源码路径：`provider/wz/XMLDomMapleData.java`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `node` | `final Node` | 被包装的 DOM 节点 |
| `imageDataDir` | `Path` | 该节点对应的 .img 资源目录（供子节点解析外部资源定位） |

| 方法签名 | 作用 | 关键逻辑 |
| --- | --- | --- |
| `public XMLDomMapleData(FileInputStream fis, Path imageDataDir)` | 顶层构造：解析整个 XML 文件 | `DocumentBuilderFactory` 建 builder，`parse(fis)` 取 `document.getFirstChild()`（即根 imgdir）；解析异常一律包 RuntimeException；记录 imageDataDir |
| `private XMLDomMapleData(Node node)` | 子节点构造 | 仅包 node（imageDataDir 由调用方补设） |
| `public synchronized Data getChildByPath(String path)` | 按 `/` 路径找子孙 | 首段 `..` 时改从 `getParent()` 起查；逐段在子元素中按 `name` 属性匹配下钻，任一段未命中返回 null；命中后构造子 Data 并推导其 imageDataDir（`imageDataDir/<本节点名>/<path>` 的父路径） |
| `public synchronized List<Data> getChildren()` | 全部元素子节点 | 遍历 childNodes 过滤 `ELEMENT_NODE`，逐个包装（子节点 imageDataDir = 本节点目录 + 本节点名） |
| `public synchronized Object getData()` | 取节点值 | 按 `getType()` 分派：SHORT/INT/FLOAT/DOUBLE → `GameConstants.parseNumber(value)` 再按类型 narrow；STRING/UOL → 原样字符串；VECTOR → `Point(x, y)`；CANVAS → `Point(width, height)`；其余返回 null |
| `public synchronized DataType getType()` | 标签名 → 类型枚举 | `imgdir`→PROPERTY、`canvas`→CANVAS、`convex`→CONVEX、`sound`→SOUND、`uol`→UOL、`double/float/int/short/string/vector`→同名、`null`→IMG_0x00；未知标签返回 null |
| `public synchronized DataEntity getParent()` | 取父节点包装 | DOM 父为 DOCUMENT_NODE 返回 null（到根）；否则包一层并上移一层 imageDataDir |
| `public synchronized String getName()` | 节点名 | 读 `name` 属性值 |
| `public synchronized Iterator<Data> iterator()` | 实现 Iterable | `getChildren().iterator()`（即 for-each 遍历子节点） |
| `public synchronized String getAttributeValue(String name)` | 读任意 XML 属性（BeiDou 扩展） | `getAttributes().getNamedItem(name)`，无该属性返回 null |
