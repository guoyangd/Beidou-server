# 详细设计文档 06 —— 网络层：加密 / Netty 服务器 / Opcode / Packet

> 模块路径：`org.gms.net`（含子包 `encryption`、`encryption.protocol`、`netty`、`opcodes`、`packet`、`packet.logging`、`packet.out`）
>
> 类数量：**36**（net 根 4 + encryption 7 + encryption.protocol 4 + netty 7 + opcodes 3 + packet 5 + packet.logging 5 + packet.out 2）
>
> 依赖模块：`io.netty`（buffer/channel/handler.codec）、`org.gms.client`（Client/Character）、`org.gms.constants.net`（ServerConstants/OpcodeConstants）、`org.gms.config.GameConfig`、`org.gms.dao.entity.NotesDO`、`org.gms.service.NoteService`、`org.gms.client.processor.npc.FredrickProcessor`、`org.gms.net.server.*`（Server/SessionCoordinator/handlers）、`org.gms.util`（HexTool/PacketCreator/I18nUtil/RateLimitUtil/ThreadLocalUtil）

## 目录

1. [net 根（4 类）](#1-net-根4-类)
2. [net.encryption（7 类）](#2-netencryption7-类)
3. [net.encryption.protocol（4 类）](#3-netencryptionprotocol4-类)
4. [net.netty（7 类）](#4-netnetty7-类)
5. [net.opcodes（3 类）](#5-netopcodes3-类)
6. [net.packet（5 类）](#6-netpacket5-类)
7. [net.packet.logging（5 类）](#7-netpacketlogging5-类)
8. [net.packet.out（2 类）](#8-netpacketout2-类)

---

## 1. net 根（4 类）

### 1.1 PacketHandler（接口）

**概述**：所有客户端封包处理器的顶层接口，OdinMS 遗留代码（AGPL 头）。每个 RecvOpcode 对应一个实现类，由 `PacketProcessor` 注册分发。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void handlePacket(InPacket p, Client c)` | 处理一个客户端上行封包 | 由实现类解析 `p` 中字段并驱动 `c`（账号/角色）状态变更 |
| `boolean validateState(Client c)` | 校验客户端状态是否允许执行本处理器 | 在分发前调用，返回 false 时封包被丢弃 |

### 1.2 AbstractPacketHandler（抽象类，implements PacketHandler）

**概述**：PacketHandler 的默认实现基类，提供"须已登录"的默认状态校验与服务器时间取值。

**关键字段**：无实例字段。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `@Override boolean validateState(Client c)` | 默认状态校验 | 直接返回 `c.isLoggedIn()`，要求客户端已完成账号登录 |
| `protected static long currentServerTime()` | 取服务器当前时间 | 委托 `Server.getInstance().getCurrentTime()`，供子类写封包时间戳用 |

### 1.3 ChannelDependencies（record）

**概述**：游戏频道处理器所需的 Spring 依赖载体，把非 Spring 的 handler 体系与 Spring bean（NoteService、FredrickProcessor）桥接起来。

**关键字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `noteService` | `NoteService` | 留言/纸条服务（NoteActionHandler、UseCashItemHandler 等使用） |
| `fredrickProcessor` | `FredrickProcessor` | 雇佣商人 Fredrick 相关业务处理器 |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `ChannelDependencies(NoteService, FredrickProcessor)`（紧凑构造器） | 非空校验 | 对两个字段 `Objects.requireNonNull`，任一为 null 即 NPE 快速失败 |
| `NoteService noteService()` / `FredrickProcessor fredrickProcessor()` | record 自动生成的访问器 | 返回对应组件 |

### 1.4 PacketProcessor（final 类）

**概述**：RecvOpcode → PacketHandler 的注册表与分发器。按 `(world, channel)` 维度缓存多个实例：登录服用 `(-1, -1)`，每个游戏频道一个；处理器数组以 opcode 值为下标实现 O(1) 查表。

**关键字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `instances` | `static Map<String, PacketProcessor>`（LinkedHashMap） | 以 `"world channel"` 为 key 的处理器实例缓存 |
| `channelDeps` | `static ChannelDependencies` | 频道处理器依赖，须先经 `registerGameHandlerDependencies` 注入 |
| `handlers` | `PacketHandler[]` | 下标 = RecvOpcode 值的查找表，长度为最大 opcode 值 + 1 |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `private PacketProcessor()` | 构造器 | 遍历 `RecvOpcode.values()` 求最大 opcode 值，按 `maxRecvOp + 1` 创建 handlers 数组 |
| `static void registerGameHandlerDependencies(ChannelDependencies channelDependencies)` | 注入频道依赖 | 静态字段赋值；须在 `getChannelServerProcessor` 之前调用 |
| `static PacketProcessor getLoginServerProcessor()` | 取登录服处理器 | `getProcessor(LoginServer.WORLD_ID, LoginServer.CHANNEL_ID)`，即 `(-1,-1)` |
| `static PacketProcessor getChannelServerProcessor(int world, int channel)` | 取频道处理器 | 先检查 `channelDeps == null` 则抛 `IllegalStateException`；再委托 `getProcessor(world, channel)` |
| `PacketHandler getHandler(short packetId)` | 按 opcode 查处理器 | 下标越界返回 null，否则返回 `handlers[packetId]`（未注册亦为 null） |
| `void registerHandler(Opcode code, PacketHandler handler)` | 注册单个处理器 | `handlers[code.getValue()] = handler`；捕获 AIOOBE 记 error 日志（opcode 超出数组范围时） |
| `static synchronized PacketProcessor getProcessor(int world, int channel)` | 获取/创建实例 | 以 `world + " " + channel` 为 key 查缓存；miss 则 new + `reset(channel)` + 入缓存 |
| `void reset(int channel)` | 重建处理器表 | 重开数组；按 `ServerConstants.VERSION == 83` 分支：先 `registerCommonHandlers()`，`channel < 0` 走 `registerLoginHandlers()`，否则 `registerChannelHandlers()`；其他版本仅 warn |
| `private void registerCommonHandlers()` | 公共处理器 | 注册 `PONG → KeepAliveHandler`（心跳）、`CUSTOM_PACKET(0x3713) → CustomPacketHandler`（GM 调试） |
| `private void registerLoginHandlers()` | 登录服处理器 | 注册 21 个登录流 handler：ACCEPT_TOS、AFTER_LOGIN、SERVERLIST_(RE)REQUEST、CHARLIST_REQUEST、CHAR_SELECT(_WITH_PIC)、LOGIN_PASSWORD、RELOG、SERVERSTATUS_REQUEST、CHECK_CHAR_NAME、CREATE_CHAR、DELETE_CHAR、VIEW_ALL_CHAR、PICK_ALL_CHAR、REGISTER_PIN、GUEST_LOGIN、REGISTER_PIC、SET_GENDER、VIEW_ALL_WITH_PIC、VIEW_ALL_PIC_REGISTER |
| `private void registerChannelHandlers()` | 游戏频道处理器 | 注册约 140 个游戏 handler（聊天/移动/攻击/物品/组队/公会/宠物/商城/MTS/家族/婚礼/Admin 等）；其中 `PLAYER_LOGGEDIN → PlayerLoggedinHandler(noteService)`、`USE_CASH_ITEM → UseCashItemHandler(noteService)`、`CASHSHOP_OPERATION → CashOperationHandler(noteService)`、`NOTE_ACTION → NoteActionHandler(noteService)`、`RING_ACTION → RingActionHandler(noteService)`、`FREDRICK_ACTION → FredrickHandler(fredrickProcessor)` 从 channelDeps 注入依赖；`STRANGE_DATA` 用单例 `LoginRequiringNoOpHandler`；`USE_RETURN_SCROLL` 复用 `UseItemHandler` |

---

## 2. net.encryption（7 类）

### 2.1 InitializationVector

**概述**：4 字节初始向量（IV）的不可变包装，按 OdinMS 惯例生成发送/接收两个方向的固定前 3 字节 + 随机末字节的 IV。

**关键字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `bytes` | `private final byte[]` | 4 字节 IV 内容 |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `private InitializationVector(byte[] bytes)` | 私有构造 | 直接持有引用（不拷贝） |
| `byte[] getBytes()` | 取 IV 字节 | 返回内部数组 |
| `static InitializationVector generateSend()` | 生成发送 IV | 前 3 字节固定 `{82, 48, 120}`，末字节随机 |
| `static InitializationVector generateReceive()` | 生成接收 IV | 前 3 字节固定 `{70, 114, 122}`，末字节随机 |
| `private static byte getRandomByte()` | 随机字节 | `(byte)(Math.random() * 255)` |

### 2.2 MapleAESOFB

**概述**：MapleStory 自定义 AES-OFB 变种流密码（OdinMS 遗留核心）。以全局 256 位 AES 密钥加密 IV 生成密钥流与数据异或；每处理完一包推进 IV（`getNewIv`，内部经 256 字节 `funnyBytes` 表混合的 "funnyShit" 变换）。发送方向还负责生成 4 字节封包头（含长度与 IV 校验信息）。

**关键字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `skey` | `static final SecretKeySpec` | 硬编码 32 字节 GMS AES 密钥（0x13,0x08,0x06,0xB4,0x1B,0x0F,0x33,0x52 及其补零展开） |
| `funnyBytes` | `static final byte[256]` | IV 推进用的混淆置换表 |
| `mapleVersion` | `private final short` | 高低字节交换后的版本号（发送方向为 `0xFFFF - VERSION` 的再交换） |
| `cipher` | `private final Cipher` | `Cipher.getInstance("AES")` 加密模式实例，用于对 IV 做 AES 加密产生密钥流 |
| `iv` | `private byte[]` | 当前 4 字节 IV，每包后推进 |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `public MapleAESOFB(InitializationVector iv, short mapleVersion)` | 构造器 | 初始化 AES cipher（异常转 RuntimeException）；保存 IV；版本号高低字节互换 `(v>>8)&0xFF \| (v<<8)&0xFF00` |
| `private static byte[] multiplyBytes(byte[] in, int count, int mul)` | 复制扩展 IV | 把 `in` 前 `count` 字节循环填充成 `count*mul` 长度（IV 4 字节 → 16 字节密钥流块） |
| `public synchronized byte[] crypt(byte[] data)` | 加/解密包体（OFB 异或，对称） | 分段处理：首段 `0x5B0` 字节、后续段 `0x5B4` 字节；每段把 IV 扩为 16 字节，每逢 16 字节边界用 AES 加密该块刷新密钥流（`cipher.doFinal` 覆盖回 myIv）；数据逐字节异或；完成后 `updateIv()` 推进 IV；原地返回 |
| `private synchronized void updateIv()` | 推进 IV | `this.iv = getNewIv(this.iv)` |
| `public byte[] getPacketHeader(int length)` | 生成 4 字节发送包头 | 由 `iv[3]|iv[2]<<8` 构成 16 位 IV 摘要并与 `mapleVersion` 异或得 `iiv`；包长高低字节互换得 `mlength`；输出 `[iiv>>8, iiv&0xFF, (iiv^mlength)>>8, (iiv^mlength)&0xFF]` |
| `public static int getPacketLength(int packetHeader)` | 从 4 字节包头解出长度 | `(header>>>16) ^ (header&0xFFFF)` 再高低字节互换（静态工具，供外部使用） |
| `private boolean checkPacket(byte[] packet)` | 校验包头前 2 字节与 IV/版本匹配 | `(packet[0]^iv[2]) == (version>>8)` 且 `(packet[1]^iv[3]) == (version&0xFF)` |
| `public boolean isValidHeader(int packetHeader)` | 校验 32 位包头是否合法 | 取包头高 16 位为 2 字节数组走 `checkPacket` |
| `public static byte[] getNewIv(byte[] oldIv)` | IV 推进 | 初始 `in={0xF2,0x53,0x50,0xC6}`，对 oldIv 每字节执行 `funnyShit` 后返回 |
| `private static byte[] funnyShit(byte inputByte, byte[] in)` | IV 混淆变换（原版命名） | 用 funnyBytes 表做加减/异或混合 4 字节状态，最后整体循环左移 3 位 |
| `@Override public String toString()` | 调试输出 | `"IV: " + HexTool.toHexString(iv)` |

### 2.3 MapleCustomEncryption

**概述**：MapleStory 私有混淆层（俗称 Shanda 加密），在 AES 之内再包一层按位滚动/取反/链式记忆字节的变换；全部静态方法，加密 6 轮、解密按逆序 6 轮。

**关键字段**：无（纯静态工具类，不可实例化与否未限制但仅含 static 方法）。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `private static byte rollLeft(byte in, int count)` | 字节循环左移 | `tmp = in << (count%8)`，`(tmp & 0xFF) \| (tmp >> 8)` |
| `private static byte rollRight(byte in, int count)` | 字节循环右移 | `tmp = (tmp << 8) >>> (count%8)`，取 `(tmp & 0xFF) \| (tmp >>> 8)` |
| `public static byte[] encryptData(byte[] data)` | 加密数据 | 6 轮（j=0..5）：偶数轮正向遍历（rollLeft 3 → +=dataLength → ^=remember → rollRight(dataLength) → 取反 → +=0x48，dataLength 递减）；奇数轮反向遍历（rollLeft 4 → +=dataLength → ^=remember → ^=0x13 → rollRight 3）。原地返回 |
| `public static byte[] decryptData(byte[] data)` | 解密数据 | 6 轮逆变换（j=1..6，与加密轮序相反）：偶数轮正向（-=0x48 → 取反 → rollLeft(dataLength) → 链式 ^remember → -=dataLength → rollRight 3）；奇数轮反向（rollLeft 3 → ^=0x13 → 链式 ^remember → -=dataLength → rollRight 4）。原地返回 |

> 注意 `remember`/`nextRemember` 链式传递与 `dataLength`（初值 `data.length & 0xFF`，逐字节减 1）是加解密可逆的关键，不能改动轮内顺序。

### 2.4 ClientCyphers

**概述**：一个连接的双向密码器对（发送 + 接收），工厂方法按服务端版本号构造。

**关键字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `send` | `private final MapleAESOFB` | 服务端→客户端方向的密码器 |
| `receive` | `private final MapleAESOFB` | 客户端→服务端方向的密码器 |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `private ClientCyphers(MapleAESOFB send, MapleAESOFB receive)` | 私有构造 | 保存两个密码器 |
| `public static ClientCyphers of(InitializationVector sendIv, InitializationVector receiveIv)` | 工厂方法 | 发送方向版本参数为 `(short)(0xFFFF - ServerConstants.VERSION)`（即 0xFFAC 对 v83 的补码形式），接收方向为 `ServerConstants.VERSION`（83）；分别 new MapleAESOFB |
| `public MapleAESOFB getSendCypher()` | 取发送密码器 | 返回 `send` |
| `public MapleAESOFB getReceiveCypher()` | 取接收密码器 | 返回 `receive` |

### 2.5 PacketCodec

**概述**：Netty 组合双工 handler，把 PacketDecoder（入站）与 PacketEncoder（出站）捆绑为一个 pipeline 节点。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `public PacketCodec(ProtocolFactory protocolFactory)` | 构造器 | `super(new PacketDecoder(protocolFactory), new PacketEncoder(protocolFactory))` 组合两个 handler，二者共享同一 ProtocolFactory（同连接同密钥流） |

### 2.6 PacketDecoder

**概述**：入站解密 handler，继承 `ReplayingDecoder<Void>`（字节不足时自动等待，无需手工检查可读长度），把解密后的字节包装为 `ByteBufInPacket` 传给后续 handler（即 Client）。

**关键字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `protocolFactory` | `private final ProtocolFactory` | 按版本取协议实现 |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `public PacketDecoder(ProtocolFactory protocolFactory)` | 构造器 | 保存工厂 |
| `@Override protected void decode(ChannelHandlerContext context, ByteBuf in, List<Object> out)` | 解码一帧 | 直接委托 `protocolFactory.getProtocol(ServerConstants.VERSION).decode(context, in, out)`（v83 下即 GMSV83PacketProtocol.decode） |

### 2.7 PacketEncoder

**概述**：出站加密 handler，继承 `MessageToByteEncoder<Packet>`，只处理 `Packet` 类型消息（OutPacket），其余透传。

**关键字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `protocolFactory` | `private final ProtocolFactory` | 按版本取协议实现 |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `public PacketEncoder(ProtocolFactory protocolFactory)` | 构造器 | 保存工厂 |
| `@Override protected void encode(ChannelHandlerContext ctx, Packet in, ByteBuf out)` | 编码一帧 | 委托 `protocolFactory.getProtocol(ServerConstants.VERSION).encode(ctx, in, out)`：写加密包头 + Shanda + AES 加密后的包体 |

---

## 3. net.encryption.protocol（4 类）

### 3.1 PacketProtocol（接口）

**概述**：特定协议版本的封包编解码抽象，当前仓库只有 GMS v83 一个实现；为多版本协议预留扩展点。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void decode(ChannelHandlerContext context, ByteBuf in, List<Object> out)` | 从入站字节流解出一个明文包 | 由实现完成包头校验、长度解析、双层解密，产出 InPacket |
| `void encode(ChannelHandlerContext ctx, Packet in, ByteBuf out)` | 把明文 Packet 编码为密文写出 | 由实现完成 Shanda + AES 加密并附加包头 |
| `void writeInitialUnencryptedHelloPacket(SocketChannel socketChannel, InitializationVector sendIv, InitializationVector recvIv, Client client)` | 连接建立时发送不加密的握手包（Hello） | 携带版本号与双向 IV，供客户端初始化解密器 |

### 3.2 ProtocolConstants

**概述**：协议版本常量类。

**关键字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `GMS_V83` | `public static final short` | 值 83，GMS v83 协议版本号 |

**方法**：无。

### 3.3 ProtocolFactory

**概述**：版本号 → PacketProtocol 的注册工厂。每个连接一个实例（持有该连接的 ClientCyphers）。

**关键字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `PROTOCOLS` | `private final Map<Short, PacketProtocol>`（HashMap） | 版本号到协议实现的映射 |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `public ProtocolFactory(ClientCyphers clientCyphers)` | 构造器 | 注册 `ProtocolConstants.GMS_V83 → new GMSV83PacketProtocol(clientCyphers)`（源码注释标明在此处注册版本） |
| `public PacketProtocol getProtocol(short version)` | 按版本取协议 | 查不到抛 `UnsupportedOperationException("PacketProtocol is a unsupported version: " + version)` |

### 3.4 GMSV83PacketProtocol

**概述**：GMS v83 协议的完整实现：封包头校验/长度解析、Shanda + AES 双层解密（收）与加密（发）、明文 Hello 握手。

**关键字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `receiveCypher` | `private final MapleAESOFB` | 接收方向密码器（来自 ClientCyphers） |
| `sendCypher` | `private final MapleAESOFB` | 发送方向密码器 |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `public GMSV83PacketProtocol(ClientCyphers clientCyphers)` | 构造器 | 取出双向密码器 |
| `@Override public void decode(ChannelHandlerContext context, ByteBuf in, List<Object> out)` | 解码上行帧 | ① `readInt()` 读 4 字节包头；② `receiveCypher.isValidHeader(header)` 不合法抛 `InvalidPacketHeaderException`；③ `decodePacketLength(header)` 求包长并读出密文体；④ 依次 `receiveCypher.crypt`（AES）、`MapleCustomEncryption.decryptData`（Shanda）；⑤ `out.add(new ByteBufInPacket(Unpooled.wrappedBuffer(packet)))` |
| `private static int decodePacketLength(byte[] header)` | 字节数组版长度解析（静态） | `((header[1]^header[3])&0xFF)<<8 \| (header[0]^header[2])&0xFF`（当前 decode 未使用，保留的字节序变体） |
| `private int decodePacketLength(int header)` | 32 位包头长度解析 | `(header>>>16) ^ (header&0xFFFF)` 再高低字节互换 |
| `@Override public void encode(ChannelHandlerContext ctx, Packet in, ByteBuf out)` | 编码下行帧 | 先 `writeBytes(getEncodedHeader(packet.length))` 写 4 字节密文头；再对包体 `MapleCustomEncryption.encryptData` + `sendCypher.crypt` 后写出 |
| `private byte[] getEncodedHeader(int length)` | 生成发送包头 | 委托 `sendCypher.getPacketHeader(length)` |
| `@Override public void writeInitialUnencryptedHelloPacket(SocketChannel socketChannel, InitializationVector sendIv, InitializationVector recvIv, Client client)` | 发送握手包 | `socketChannel.writeAndFlush(PacketCreator.getHello(ServerConstants.VERSION, sendIv, recvIv).getBytes())`——明文下发版本与双向 IV |

---

## 4. net.netty（7 类）

### 4.1 AbstractServer（抽象类）

**概述**：LoginServer / ChannelServer 的共同基类，只承载端口号并约定生命周期。

**关键字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `port` | `final int` | 监听端口（包级私有） |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `AbstractServer(int port)` | 构造器 | 记录端口 |
| `public abstract void start()` | 启动监听 | 由子类用 Netty ServerBootstrap 实现 |
| `public abstract void stop()` | 停止监听 | 由子类关闭 channel 并优雅关闭事件循环组 |

### 4.2 LoginServer

**概述**：登录服务器（默认端口由外部传入，见 `Server` 初始化）。标准的 Netty NIO TCP 服务器。

**关键字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `WORLD_ID` | `public static final int` | `-1`，登录服世界标识 |
| `CHANNEL_ID` | `public static final int` | `-1`，登录服频道标识（PacketProcessor 据此区分登录/频道处理器集） |
| `channel` | `private Channel` | 服务端监听 channel |
| `parentGroup` / `childGroup` | `private EventLoopGroup` | Netty 主/从事件循环组 |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `public LoginServer(int port)` | 构造器 | `super(port)` |
| `@Override public void start()` | 启动 | 创建两个 `NioEventLoopGroup`；`ServerBootstrap` 绑定 `NioServerSocketChannel` 与 `LoginServerInitializer`；`bind(port).syncUninterruptibly()` 阻塞至绑定成功并保存 channel |
| `@Override public void stop()` | 停止 | channel 为 null 抛 `IllegalStateException`；否则关闭 channel 并 `shutdownGracefully()` 两个循环组 |

### 4.3 ChannelServer

**概述**：单个游戏频道的服务器，逻辑与 LoginServer 一致，仅多携带 world/channel 标识并使用 ChannelServerInitializer。

**关键字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `world` / `channel` | `private final int` | 频道所属世界与频道号 |
| `nettyChannel` | `private Channel` | 监听 channel |
| `parentGroup` / `childGroup` | `private EventLoopGroup` | 事件循环组 |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `public ChannelServer(int port, int world, int channel)` | 构造器 | 保存端口与频道标识 |
| `@Override public void start()` | 启动 | 同 LoginServer，childHandler 为 `new ChannelServerInitializer(world, channel)` |
| `@Override public void stop()` | 停止 | 同 LoginServer（未启动先停则抛 `IllegalStateException`） |

### 4.4 ServerChannelInitializer（抽象类，extends ChannelInitializer\<SocketChannel\>）

**概述**：Login/Channel 两个 Initializer 的共同父类：生成双向 IV、发送明文 Hello、装配 Netty pipeline（空闲检测 → 收发日志 → 编解码 → Client handler）。

**关键字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `IDLE_TIME_SECONDS` | `private static final int` | 30 秒空闲超时（触发 Client 的 IdleEvent 断线检测） |
| `sendPacketLogger` | `static final ChannelHandler`（OutPacketLogger，@Sharable） | 所有连接共享的出站日志 handler |
| `receivePacketLogger` | `static final ChannelHandler`（InPacketLogger，@Sharable） | 共享入站日志 handler |
| `sessionId` | `static final AtomicLong`（初始 7777） | 全局递增的客户端会话 ID 生成器 |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `String getRemoteAddress(Channel channel)` | 取客户端 IP | 强转 `InetSocketAddress` 取 hostAddress；NPE 时 warn 并返回 `"null"` |
| `void initPipeline(SocketChannel socketChannel, Client client)` | 建立加密链路 | ① `generateSend()/generateReceive()` 生成双向 IV；② `new ProtocolFactory(ClientCyphers.of(sendIv, recvIv))`；③ 调协议的 `writeInitialUnencryptedHelloPacket` 明文握手；④ `setUpHandlers` 装 pipeline |
| `private void setUpHandlers(ChannelPipeline pipeline, ProtocolFactory protocolFactory, Client client)` | 装配 pipeline | 顺序：`IdleStateHandler(0,0,30)` → `PacketCodec` → `Client`；再把两个 PacketLogger `addBefore("Client", ...)` 插到 Client 之前（SendPacketLogger、ReceivePacketLogger），保证日志记录的是明文包 |

### 4.5 LoginServerInitializer

**概述**：登录服连接初始化器：创建登录 Client 并装配 pipeline，含限流与防爆破会话协调检查。

**关键字段**：`log`（Logger）。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `@Override public void initChannel(SocketChannel socketChannel)` | 新连接接入 | ① 记录 clientIp（i18n 日志）；② 取 `PacketProcessor.getLoginServerProcessor()`；③ `sessionId.getAndIncrement()` 生成会话 ID；④ `RateLimitUtil.check(remoteAddress)` 不过则 warn + close；⑤ `Client.createLoginClient(sessionId, remoteAddress, processor, -1, -1)`；⑥ `SessionCoordinator.canStartLoginSession(client)` 拒绝则 close（防并发登录尝试滥用）；⑦ `initPipeline(socketChannel, client)` |

### 4.6 ChannelServerInitializer

**概述**：游戏频道连接初始化器：与登录初始化流程类似，但额外校验目标频道是否已注册。

**关键字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `world` / `channel` | `private final int` | 目标世界/频道 |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `public ChannelServerInitializer(int world, int channel)` | 构造器 | 保存频道标识 |
| `@Override public void initChannel(SocketChannel socketChannel)` | 新连接接入 | ① i18n 日志（clientIp, world, channel）；② `PacketProcessor.getChannelServerProcessor(world, channel)`；③ 会话 ID + `RateLimitUtil` 限流不过则 close；④ `Client.createChannelClient(...)`；⑤ `Server.getInstance().getChannel(world, channel) == null` 时经 `SessionCoordinator.closeSession(client, true)` 再 close（频道未开）；⑥ `initPipeline` |

### 4.7 InvalidPacketHeaderException

**概述**：封包头校验失败（可能为恶意/错位数据流）时抛出的运行时异常，携带原始 4 字节头便于排查。

**关键字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `header` | `private final int` | 非法的 32 位包头 |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `public InvalidPacketHeaderException(String message, int header)` | 构造器 | super(message) 并保存 header |
| `public int getHeader()` | 取非法包头 | 返回 header |

---

## 5. net.opcodes（3 类）

> 说明：本包不含独立的"反查映射"类；opcode 值 → 名称的反查表位于 `org.gms.constants.net.OpcodeConstants`（`recvOpcodeNames` / `sendOpcodeNames`，供 packet.logging 使用），`MonitoredChrLogger.getOpcodeFromValue` 亦按值反查 `RecvOpcode`。本包仅含枚举组织本身。

### 5.1 Opcode（接口）

**概述**：收/发 opcode 枚举的公共接口。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `int getValue()` | 取 opcode 数值 | 枚举实现返回构造时传入的 code |
| `String getName()` | 取 opcode 名称 | 枚举实现返回 `name()` |

### 5.2 RecvOpcode（enum implements Opcode）

**概述**：客户端→服务端 opcode 枚举，约 190 个条目，每条带 16 进制值与中文注释（登录/移动/战斗/物品/NPC/组队/公会/宠物/商城/家族/Admin 等）。`PacketProcessor` 以 `getValue()` 作为 handlers 数组下标。

**关键字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | `private final int` | opcode 数值，默认占位 -2 |

**组织方式与代表值**：

- 构造 `RecvOpcode(int code)`；`getValue()`/`getName()` 实现 Opcode 接口。
- 代表值：`LOGIN_PASSWORD(0x01)`、`CHARLIST_REQUEST(0x05)`、`CHAR_SELECT(0x13)`、`PLAYER_LOGGEDIN(0x14)`、`CREATE_CHAR(0x16)`、`PONG(0x18)`（心跳）、`CHANGE_MAP(0x26)`、`CHANGE_CHANNEL(0x27)`、`MOVE_PLAYER(0x29)`、`CLOSE_RANGE_ATTACK(0x2C)`、`GENERAL_CHAT(0x31)`、`NPC_TALK(0x3A)`、`STORAGE(0x3E)`、`ITEM_MOVE(0x46)`（示例段）、`USE_ITEM`、`PARTY_OPERATION`、`GUILD_OPERATION`、`SPAWN_PET`、`ENTER_CASHSHOP(0x28)`、`ADMIN_COMMAND`、`FREDRICK_ACTION(0x40)`、`SET_HPMPALERT(0x1000)`，以及调试用 `CUSTOM_PACKET(0x3713)`（源码注释 "13 37 lol"）。
- 最大值为 `CUSTOM_PACKET`，决定了 PacketProcessor 数组长度约 0x3714。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `RecvOpcode(int code)` | 构造器 | 保存 code |
| `@Override public int getValue()` | 取值 | 返回 code |
| `@Override public String getName()` | 取名 | 返回 `this.name()` |

### 5.3 SendOpcode（enum implements Opcode）

**概述**：服务端→客户端 opcode 枚举，约 330 个条目，同样带中文注释。`ByteBufOutPacket` 构造时以 `writeShortLE(opcode)` 写入包头前 2 字节。

**关键字段**：`code`（`private final int`，默认 -2）。

**组织方式与代表值**：

- 同 RecvOpcode 的构造/接口实现方式。
- 代表值：`LOGIN_STATUS(0x00)`、`SERVERSTATUS(0x03)`、`SERVERLIST(0x0A)`、`CHARLIST(0x0B)`、`SERVER_IP(0x0C)`、`CHAR_NAME_RESPONSE(0x0D)`、`ADD_NEW_CHAR_ENTRY(0x0E)`、`DELETE_CHAR_RESPONSE(0x0F)`、`CHANGE_CHANNEL(0x10)`、`PING(0x11)`（心跳）、以及 `MEMO_RESULT`（留言，见 packet.out）等；尾部到 `UPDATE_HPMPAALERT(0x1000)`、`VEGA_SCROLL(0x166)`、`VICIOUS_HAMMER(0x162)` 等。

**方法表**：与 RecvOpcode 完全一致（`RecvOpcode(int)`/`getValue()`/`getName()`）。

---

## 6. net.packet（5 类）

### 6.1 Packet（接口）

**概述**：所有封包的最顶层抽象——只要求能导出字节数组。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `byte[] getBytes()` | 导出封包全部字节 | InPacket 为明文收包内容（已解密），OutPacket 为待加密的明文发包 |

### 6.2 InPacket（interface extends Packet）

**概述**：上行封包读取接口，定义小端序的基本类型读取、定长字符串（长度前缀 short）读取与游标操作。注意 short/int/long 均为 **LE（小端）**，与 MapleStory 协议一致。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `byte readByte()` | 读 1 字节 | — |
| `short readUnsignedByte()` | 读无符号字节 | 返回 0~255 的 short |
| `short readShort()` | 读 2 字节小端 | — |
| `int readInt()` | 读 4 字节小端 | — |
| `long readLong()` | 读 8 字节小端 | — |
| `Point readPos()` | 读坐标 | 两个 LE short → `java.awt.Point(x, y)` |
| `String readString()` | 读长度前缀字符串 | 先 LE short 长度，再按字节读出；字符集由实现决定（客户端语言） |
| `byte[] readBytes(int numberOfBytes)` | 读定长字节块 | — |
| `void skip(int numberOfBytes)` | 跳过字节 | — |
| `int available()` | 剩余可读字节数 | — |
| `void seek(int byteOffset)` | 移动读游标 | — |
| `int getPosition()` | 当前读游标位置 | — |

### 6.3 OutPacket（interface extends Packet）

**概述**：下行封包写入接口，与 InPacket 对称；另提供静态工厂 `create(Opcode)`。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void writeByte(byte value)` / `void writeByte(int value)` | 写 1 字节 | int 版隐式截断为 byte |
| `void writeBytes(byte[] value)` | 写字节块 | — |
| `void writeShort(int value)` | 写 2 字节小端 | — |
| `void writeInt(int value)` | 写 4 字节小端 | — |
| `void writeLong(long value)` | 写 8 字节小端 | — |
| `void writeBool(boolean value)` | 写布尔 | `value ? 1 : 0` 单字节 |
| `void writeString(String value)` | 写长度前缀字符串 | LE short 长度 + 字节（字符集由实现决定） |
| `void writeFixedString(String value)` | 写定长字符串（默认 13 字节） | 委托 `writeFixedString(value, 13)`（Nexon 固定字段） |
| `void writeFixedString(String value, int fixed)` | 写定长字符串 | `Arrays.copyOf` 补/截到 fixed 字节后写出 |
| `void writePos(Point value)` | 写坐标 | x、y 各写一个 LE short |
| `void skip(int numberOfBytes)` | 跳写字节 | 写 numberOfBytes 个 0x00 |
| `static OutPacket create(Opcode opcode)` | 静态工厂 | `new ByteBufOutPacket(opcode)`，自动写入 opcode 头 |

### 6.4 ByteBufInPacket（implements InPacket）

**概述**：基于 Netty `ByteBuf` 的上行封包实现，由 `GMSV83PacketProtocol.decode` 创建；字符串按客户端语言（ThreadLocal）动态选字符集，支持中英文双语。

**关键字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `byteBuf` | `private final ByteBuf` | 解密后的明文包数据（含 2 字节 opcode 头） |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `public ByteBufInPacket(ByteBuf byteBuf)` | 构造器 | 包装已有 ByteBuf |
| `@Override public byte[] getBytes()` | 导出全部字节 | `ByteBufUtil.getBytes(byteBuf)` |
| `@Override public byte readByte()` | 读字节 | `byteBuf.readByte()` |
| `@Override public short readUnsignedByte()` | 读无符号字节 | `byteBuf.readUnsignedByte()` |
| `@Override public short readShort()` | 读 2 字节小端 | `readShortLE()` |
| `@Override public int readInt()` | 读 4 字节小端 | `readIntLE()` |
| `@Override public long readLong()` | 读 8 字节小端 | `readLongLE()` |
| `@Override public Point readPos()` | 读坐标 | 两个 `readShortLE` 组成 Point |
| `@Override public String readString()` | 读字符串 | LE short 长度 + 字节，按 `CharsetConstants.getCharset(ThreadLocalUtil.getClientLang())` 解码（i18n 关键点） |
| `@Override public byte[] readBytes(int numberOfBytes)` | 读字节块 | — |
| `@Override public void skip(int numberOfBytes)` | 跳过 | `skipBytes` |
| `@Override public int available()` | 剩余可读 | `readableBytes()` |
| `@Override public void seek(int byteOffset)` | 定位游标 | `readerIndex(byteOffset)` |
| `@Override public int getPosition()` | 取游标 | `readerIndex()` |
| `@Override public boolean equals(Object o)` | 相等判断 | 模式匹配 `ByteBufInPacket` 且内部 byteBuf equals |
| `@Override public String toString()` | 调试输出（十六进制 + 读位置标记） | markReaderIndex 后回到 0，`hexDump` 大写，在 `2*readerIndex` 处插入 `_` 标记原读位置，最后 reset |
| `private static String insertReaderPosition(String hexDump, int index)` | 插入读位置标记 | StringBuilder 在 `2*index` 处 insert `_` |

### 6.5 ByteBufOutPacket（implements OutPacket，@NotThreadSafe）

**概述**：基于 Netty `ByteBuf` 的下行封包实现；三个构造器分别用于无头包、带 opcode 头包和指定初始容量包。非线程安全（jcip 注解标注）。

**关键字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `byteBuf` | `private final ByteBuf` | 待发送的明文缓冲区（`Unpooled.buffer()`） |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `public ByteBufOutPacket()` | 无头构造 | 空 buffer，不写 opcode（自定义/中继包用） |
| `public ByteBufOutPacket(Opcode op)` | 带 opcode 构造 | `Unpooled.buffer()` 后 `writeShortLE((short) op.getValue())` 写 2 字节头 |
| `public ByteBufOutPacket(SendOpcode op, int initialCapacity)` | 指定容量构造 | `Unpooled.buffer(initialCapacity)` + 写 opcode 头（大包性能优化） |
| `@Override public byte[] getBytes()` | 导出 | `ByteBufUtil.getBytes(byteBuf)` |
| `@Override public void writeByte(byte value)` | 写字节 | `writeByte` |
| `@Override public void writeByte(int value)` | 写字节（int 重载） | 转(byte) 后委托 byte 版 |
| `@Override public void writeBytes(byte[] value)` | 写块 | — |
| `@Override public void writeShort(int value)` | 写 LE short | `writeShortLE` |
| `@Override public void writeInt(int value)` | 写 LE int | `writeIntLE` |
| `@Override public void writeLong(long value)` | 写 LE long | `writeLongLE` |
| `@Override public void writeBool(boolean value)` | 写布尔 | `value ? 1 : 0` |
| `@Override public void writeString(String value)` | 写长度前缀字符串 | 按 `CharsetConstants.getCharset(ThreadLocalUtil.getClientLang())` 编码，先写 LE short 长度再写字节 |
| `@Override public void writeFixedString(String value)` | 写 13 字节定长串 | 委托 `writeFixedString(value, 13)` |
| `@Override public void writeFixedString(String value, int fixed)` | 写定长串 | `Arrays.copyOf(编码字节, fixed)` 补零/截断后写出 |
| `@Override public void writePos(Point value)` | 写坐标 | x、y 各 LE short |
| `@Override public void skip(int numberOfBytes)` | 跳写 | 写等长全零数组 |
| `@Override public boolean equals(Object o)` | 相等判断 | 模式匹配比较内部 byteBuf |

---

## 7. net.packet.logging（5 类）

### 7.1 PacketLogger（接口）

**概述**：封包日志器抽象，收发两个 Netty handler 均实现它。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `void log(Packet packet)` | 记录一个封包 | 由实现决定格式与过滤 |

### 7.2 LoggingUtil

**概述**：日志工具类：读包头 opcode 与高频包过滤集合。

**关键字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `ignoredDebugRecvPackets` | `private static final Set<Short>` | 调试日志忽略的高频上行 opcode：MOVE_PLAYER、HEAL_OVER_TIME、SPECIAL_MOVE、QUEST_ACTION、MOVE_PET、MOVE_LIFE、NPC_ACTION |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `public static short readFirstShort(byte[] bytes)` | 读封包前 2 字节 opcode（LE） | `Unpooled.wrappedBuffer(bytes).readShortLE()` |
| `public static boolean isIgnoredRecvPacket(short opcode)` | 判断是否为忽略的调试包 | Set.contains |

### 7.3 InPacketLogger（@Sharable，extends ChannelInboundHandlerAdapter implements PacketLogger）

**概述**：入站封包日志 handler。仅当 `GameConfig` 开关 `use_debug_show_packet` 打开时记录明文收包；所有连接共享一个实例（父类中以 static 字段挂载于 "ReceivePacketLogger" 节点）。

**关键字段**：`log`（Logger）、`LOG_CONTENT_THRESHOLD = 3_000`（超过只记前 2 字节）。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `@Override public void channelRead(ChannelHandlerContext ctx, Object msg)` | 拦截入站消息 | 开关开启且 msg 为 InPacket 时 `log(packet)`；无论如何 `ctx.fireChannelRead(msg)` 继续传递 |
| `@Override public void log(Packet packet)` | 记录收包 | 取字节与长度；≤3000 时读首 short opcode，经 `OpcodeConstants.recvOpcodeNames` 反查名称（未知加 `<UnknownPacket>` 前缀），输出 `ClientSend:名 [HEX] (长度) <HEX> .. <TEXT> ..`；超限只记前两字节 hex |
| `private String getRecvOpcodeName(short opcode)` | 反查 opcode 名 | `OpcodeConstants.recvOpcodeNames.get((int) opcode)` |

### 7.4 OutPacketLogger（@Sharable，extends ChannelOutboundHandlerAdapter implements PacketLogger）

**概述**：出站封包日志 handler，与 InPacketLogger 对称（阈值更大，50_000 字节）。

**关键字段**：`log`、`LOG_CONTENT_THRESHOLD = 50_000`。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `@Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)` | 拦截出站消息 | 开关开启且 msg 为 OutPacket 时 `log(packet)`；随后 `ctx.write(msg)` 继续 |
| `@Override public void log(Packet packet)` | 记录发包 | 同 InPacketLogger 逻辑，前缀 `ServerSend:`，名称查 `OpcodeConstants.sendOpcodeNames` |
| `private String getSendOpcodeName(short opcode)` | 反查 opcode 名 | `OpcodeConstants.sendOpcodeNames.get((int) opcode)` |

### 7.5 MonitoredChrLogger（@NotThreadSafe）

**概述**：被监控角色的收包记录器（GM 调试用）：维护一个被监控角色 ID 集合，命中时把该角色的上行包写入独立 logger，高频操作包屏蔽。

**关键字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `log` | `static final Logger` | 独立 logger（按类名落盘） |
| `monitoredChrIds` | `private static final Set<Integer>`（HashSet） | 被监控角色 ID 集合 |

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `public static boolean toggleMonitored(int chrId)` | 切换监控状态 | 在集合中则移除并返回 false（取消监控），否则加入并返回 true（开始监控） |
| `public static Collection<Integer> getMonitoredChrIds()` | 取监控列表 | 返回集合视图 |
| `public static void logPacketIfMonitored(Client c, short packetId, byte[] packetContent)` | 条件记录收包 | 取 `c.getPlayer()`，为 null 或角色 ID 不在监控集合则直接返回；`getOpcodeFromValue` 反查 RecvOpcode，`isRecvBlocked` 屏蔽 MOVE_PLAYER/GENERAL_CHAT/TAKE_DAMAGE/MOVE_PET/MOVE_LIFE/NPC_ACTION/FACE_EXPRESSION；否则输出 `账号名-角色名 opcodeHex`（空包记 `<empty>`） |
| `private static boolean isRecvBlocked(RecvOpcode op)` | 高频包屏蔽 | switch 表达式列出上述 7 个 opcode 返回 true，其余 false |
| `private static RecvOpcode getOpcodeFromValue(int value)` | 值反查枚举 | 遍历 `RecvOpcode.values()` 过滤 `value == opcode.getValue()`，`findAny().orElse(null)` |

---

## 8. net.packet.out（2 类）

### 8.1 SendNoteSuccessPacket（extends ByteBufOutPacket，final）

**概述**：发送留言成功的下行包（opcode `MEMO_RESULT`，模式 `4`）。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `public SendNoteSuccessPacket()` | 构造即完成封包 | `super(SendOpcode.MEMO_RESULT)` 写头后 `writeByte(4)`（发送成功子命令） |

### 8.2 ShowNotesPacket（extends ByteBufOutPacket，final）

**概述**：打开留言箱、下发留言列表的下行包（opcode `MEMO_RESULT`，模式 `3`）。

**关键字段**：无（继承 ByteBuf 的缓冲）。

**方法表**：

| 方法签名 | 作用 | 关键逻辑 |
|---|---|---|
| `public ShowNotesPacket(List<NotesDO> notes)` | 构造即完成封包 | `Objects.requireNonNull(notes)`；`writeByte(3)`（展示列表子命令）+ `writeByte(notes.size())`；逐条 `writeNote` |
| `private void writeNote(NotesDO note)` | 写单条留言 | `writeInt(id)` + `writeString(from + " ")`（源码注释：Nexon 忘了空格）+ `writeString(message)` + `writeLong(PacketCreator.getTime(timestamp))`（v83 时间戳纪元换算）+ `writeByte(fame)` |

---

## 附：一次完整收发时序（串联所有模块）

```
[Client TCP connect]
  LoginServerInitializer/ChannelServerInitializer.initChannel
    → RateLimitUtil 限流 → Client.createXxxClient → initPipeline
    → 生成双向 IV → ClientCyphers.of → ProtocolFactory(v83)
    → GMSV83PacketProtocol.writeInitialUnencryptedHelloPacket   # 明文 Hello(版本+IV)

[Recv] PacketDecoder(ReplayingDecoder)
  → GMSV83PacketProtocol.decode
    → isValidHeader(IV/版本校验) → 解包长 → MapleAESOFB.crypt → MapleCustomEncryption.decryptData
  → ByteBufInPacket → InPacketLogger(可选) → Client(handler)
  → PacketProcessor.getHandler(opcode) → validateState → handlePacket

[Send] OutPacket.create(SendOpcode) → ByteBufOutPacket(LE 写入)
  → OutPacketLogger(可选) → PacketEncoder
  → GMSV83PacketProtocol.encode
    → MapleCustomEncryption.encryptData → MapleAESOFB.crypt → writeHeader+body
```
