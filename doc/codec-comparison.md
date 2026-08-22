# Jaws 编解码设计与 Dubbo 对比分析

## 一、整体架构

### Jaws 编解码结构

```
NettyDecoder      (Netty 层 - 帧检测 + 半包等待，输出 NettyMessage)
        ↕
NettyChannelHandler (业务层 - 调用 Codec 编解码，服务端线程池调度)
        ↕
JawsCodec         (协议层 - 业务编解码, magic=0xF0F0, 直接操作 ByteBuf)
```

- **NettyDecoder**：继承 `ByteToMessageDecoder`，校验 `0xF0F0` magic，读取协议帧头（version、flag、requestId、bodyLength），等待完整 body 后通过 `readRetainedSlice()` 提取完整协议帧（header + body）封装为 `NettyMessage` record 传递给下游
- **NettyChannelHandler**：服务端接收 `NettyMessage`，通过 `threadPoolExecutor` 将 decode 调度到业务线程；客户端在 IO 线程直接 decode。编码时 `Codec.encode()` 直接写入 `ByteBuf`，由 `ctx.channel().writeAndFlush()` 发送
- **NettyChannel**：客户端发送请求时分配 `ByteBuf`，调用 `Codec.encode()` 直接写入，通过 `channel.writeAndFlush()` 发送
- **JawsCodec**：协议层编解码，处理 `0xF0F0` 协议帧的业务语义，编码采用「预留 header 空间 + body 直写 ByteBuf + 回填 header」模式，解码通过 `retainedSlice` + `ByteBufInputStream` 零拷贝反序列化

> 注：早期版本有独立的 `NettyEncoder`（`MessageToByteEncoder<byte[]>`），Codec 升级为直接操作 `ByteBuf` 后已移除，编码路径减少一次中间层。

### Dubbo 编解码结构

```
NettyCodecAdapter  (Netty 适配层 - ByteBuf ↔ ChannelBuffer 桥接)
        ↕
ExchangeCodec      (传输层 - 头解析 + 流式 body 解码, magic=0xdabb, 操作自建 ChannelBuffer)
        ↕
DubboCodec         (RPC 层 - 覆盖 body 编解码逻辑)
```

- **NettyCodecAdapter**：Netty 传输层与 Dubbo Codec 之间的桥接。编码时创建 `DynamicChannelBuffer`（底层 `byte[]`）交给 `Codec2.encode()`，完成后通过 `toByteBuffer()` 转换为 `ByteBuffer` 再包装为 Netty `ChannelBuffer`；解码时从 Netty `ChannelBuffer` 通过 `toByteBuffer()` 提取数据，拷贝到 Dubbo `ChannelBuffer` 后交给 `Codec2.decode()`
- **ExchangeCodec**：操作 Dubbo 自建的 `ChannelBuffer` 抽象（`dubbo-remoting-api` 模块，不依赖 Netty），一次完成头解析和 body 流式解码，通过 `decodeBody()` 模板方法留给子类扩展
- **DubboCodec**：通过继承覆盖 `encodeRequestData`/`decodeBody` 等方法，注入 RPC 语义

**架构对比**：Jaws 的 `Codec` 接口直接操作 Netty `ByteBuf`，编解码全程无桥接开销；Dubbo 的 `Codec2` 操作自建 `ChannelBuffer` 抽象，保持了 `dubbo-remoting-api` 的传输层无关性，但在与 Netty 交互时通过 `NettyCodecAdapter` 桥接，产生额外的分配和拷贝。Jaws 将帧检测（NettyDecoder）与协议语义解析（JawsCodec）分离，中间通过 `NettyMessage` record 解耦，职责边界清晰；Dubbo 通过继承体系在同一个 Codec 类中完成（ExchangeCodec + DubboCodec），少一层间接调用。

## 二、线上数据格式

```
┌──────────── 协议帧 header (16B) ────────────┐┌─────┐
│ 0xF0F0 │ ver │ flag │ reqId │ bodyLen       ││ body│
└──────────────────────────────────────────────┘└─────┘
```

- 单层帧结构，`magic=0xF0F0` 既是帧标识也是协议标识
- `NettyDecoder` 读取 `bodyLen` 后等待完整 body，然后 `readRetainedSlice(HEADER_LENGTH + bodyLength)` 提取完整协议帧交给 `NettyChannelHandler`

## 三、协议帧结构对比

### Jaws 协议帧 (16 bytes header + body)

```
Bytes 0-1   : magic 0xF0F0
Byte  2     : version (当前 = 1)
Byte  3     : flag (低 3 位 = dataType: 0x00=request, 0x01=response, 0x03=void, 0x05=exception;
                    bit 2 = event (心跳); 高 5 位 = serializationId，最多 32 种序列化协议)
Bytes 4-11  : requestId
Bytes 12-15 : body length
```

### Dubbo 协议帧 (16 bytes header + body)

```
Bytes 0-1   : magic 0xdabb
Byte  2     : flag (bit7=request, bit6=twoway, bit5=event, bit0-4=serializationId)
Byte  3     : status (OK=20, CLIENT_ERROR, BAD_RESPONSE, SERIALIZATION_ERROR...)
Bytes 4-11  : requestId
Bytes 12-15 : body length
```

### 设计差异分析

| 维度 | Jaws | Dubbo | 评价 |
|------|------|-------|------|
| 版本号 | header 有独立 version 字节 | 无 header 版字段，版本在 body 内 writeUTF 传递 | **Jaws 更优**：header 层即可完成版本校验，无需解析 body |
| 序列化方式 | 嵌入 flag 高 5 位，通过 `@SpiMeta(number)` + `ExtensionLoader.getExtensionByNumber()` 查找 | 嵌入 flag 低 5 位 | **持平**：双方均支持每消息独立序列化方式 |
| 响应状态 | flag 低 3 位区分 void/exception/normal | status 字节支持多种状态码 | **各有优势**：Jaws 在 header 即可判断响应类型；Dubbo 状态粒度更细 |
| 心跳/事件 | `FLAG_EVENT` (bit 2) 原生支持 | FLAG_EVENT 位原生支持 | **持平** |
| 双向标记 | 无 | FLAG_TWOWAY 位 | **Dubbo 更优**：支持单向调用 |
| 帧结构 | 单层，简洁直接 | 单层 | **持平** |

## 四、数据流与效率对比

### Jaws 编码路径

```
JawsCodec.encodeRequest() / encodeResponse()
  ① out.writerIndex(headerStart + HEADER_LENGTH)     预留 header 空间
  ② ByteBufOutputStream(out) → ObjectOutput 直写 body  [零拷贝，无中间 byte[]]
  ③ writeHeader() 回填 header 到预留位置               [原地回写]
NettyChannel / NettyChannelHandler
  ④ channel.writeAndFlush(buf)                        [Netty 直接发送]
```

共 **0 次额外 byte[] 分配 + 0 次 body 拷贝**。body 直接写入最终目标 ByteBuf，header 通过预留空间 + 回填完成。

### Dubbo 编码路径

```
NettyCodecAdapter.InternalEncoder.encode()
  ① ChannelBuffers.dynamicBuffer(1024)                    [分配 HeapChannelBuffer (byte[])]
  ② codec.encode(channel, buffer, msg)                    [序列化写入 byte[]]
  ③ ChannelBuffers.wrappedBuffer(buffer.toByteBuffer())   [ByteBuffer.wrap(byte[]) → Netty ChannelBuffer]
ExchangeCodec.encodeRequest() (在步骤②内)
  ④ byte[] header = new byte[16]                          [临时 header 数组]
  ⑤ ChannelBufferOutputStream(buffer) 直写 body            [在 ChannelBuffer 内零拷贝]
  ⑥ buffer.writeBytes(header) 回填 header                  [header 写入 ChannelBuffer]
```

Codec 层内部（ExchangeCodec）零拷贝，但 NettyCodecAdapter 桥接层产生 **1 次 HeapChannelBuffer 分配 + 1 次 toByteBuffer() 转换**。

### Dubbo 解码路径

```
NettyCodecAdapter.InternalDecoder.messageReceived()
  ① input.toByteBuffer()                                  [Netty ChannelBuffer → ByteBuffer]
  ② ChannelBuffers.wrappedBuffer(byteBuffer)              [ByteBuffer → Dubbo ChannelBuffer，可能触发 arraycopy]
  ③ 有残留数据时：dynamicBuffer(size) + writeBytes × 2    [残留 + 新数据合并拷贝]
ExchangeCodec.decode() (在步骤②③后的 ChannelBuffer 上)
  ④ byte[] header = new byte[16]; buffer.readBytes(header) [header 拷贝到 byte[]]
  ⑤ ChannelBufferInputStream(buffer, len) 包装 body 区域   [在 ChannelBuffer 内零拷贝]
  ⑥ CodecSupport.deserialize(url, is, proto) 流式反序列化
```

Codec 层内部（ExchangeCodec）零拷贝，但 NettyCodecAdapter 桥接层产生 **1-2 次 buffer 转换/拷贝**（取决于是否有半包残留）。

### Jaws 解码路径

```
NettyDecoder.decode()
  ① readRetainedSlice(frame) → ByteBuf slice                       [零拷贝]
JawsCodec.decode()
  ② retainedSlice → ByteBufInputStream 包装 body 区域               [零拷贝]
  ③ Serialization.deserialize(InputStream) → ObjectInput 流式读取
```

共 **0 次额外 byte[] 分配 + 0 次 body 拷贝 + 0 次 per-field byte[] 拷贝**。

> 注：Serialization 接口已升级为流式 API（`serialize(OutputStream) → ObjectOutput` / `deserialize(InputStream) → ObjectInput`），所有协议元数据和业务对象通过同一个流顺序读写，Hessian2 实现零中间 byte[] 拷贝。Fastjson2 因 JSONB 不支持原生流式传输，内部仍使用 length-prefixed byte[] 适配，但接口层面已与 Dubbo 对齐。

### Dubbo 解码路径（旧版，见下方修正）

> **注意**：以下描述的是 ExchangeCodec 在 ChannelBuffer 抽象层内部的行为。
> 实际上 NettyCodecAdapter 桥接层还有额外的 buffer 转换开销（见上文「Dubbo 解码路径」）。

```
ExchangeCodec.decode() (在 ChannelBuffer 上)
  ① byte[] header = new byte[16]; buffer.readBytes(header)  [header 拷贝]
  ② ChannelBufferInputStream 包装 body 区域                 [在 ChannelBuffer 内零拷贝]
  ③ DecodeableRpcInvocation 支持懒反序列化
```

Codec 层内部 0 次额外 body 拷贝，但 header 读取有 1 次 `byte[16]` 分配。

### 效率小结

| 维度 | Jaws | Dubbo |
|------|------|-------|
| Codec 接口 | 直接操作 Netty `ByteBuf` | 操作自建 `ChannelBuffer` 抽象 |
| 编码路径（Codec 层） | 0 次中间分配，body 直写 ByteBuf | 0 次中间分配，body 直写 ChannelBuffer |
| 编码路径（Netty 桥接） | 无桥接层 | 1 次 HeapChannelBuffer 分配 + toByteBuffer() 转换 |
| 解码路径（Codec 层） | 0 次中间分配，`retainedSlice` + `ByteBufInputStream` | 0 次中间 body 拷贝，`ChannelBufferInputStream` |
| 解码路径（Netty 桥接） | 无桥接层 | 1-2 次 buffer 转换/拷贝（`toByteBuffer()` + 残留合并） |
| 底层内存 | Netty 池化/非池化 `ByteBuf` | 默认 `HeapChannelBuffer` = `new byte[]`，无池化 |
| 内存管理 | Netty 引用计数 + 内存池 | JVM GC |

Jaws 的编解码路径**优于** Dubbo：
- **编码**：Jaws 采用「预留 header 空间 + body 直写 ByteBuf + 回填 header」模式，全程在 Netty ByteBuf 上操作，0 次中间分配。Dubbo 在 Codec 层同样零拷贝，但 NettyCodecAdapter 桥接层需额外分配 HeapChannelBuffer 并转换为 Netty ChannelBuffer
- **解码**：Jaws 通过 `retainedSlice` + `ByteBufInputStream` 零拷贝反序列化，全程在 Netty ByteBuf 上操作。Dubbo 在 Codec 层同样零拷贝，但 NettyCodecAdapter 桥接层需将 Netty ChannelBuffer 通过 `toByteBuffer()` 转换后拷贝到 Dubbo ChannelBuffer
- **Serialization**：双方均已升级为流式 API（`ObjectOutput`/`ObjectInput`），Hessian2 实现零中间 byte[] 拷贝，此维度持平

根本差异在于：Jaws 的 `Codec` 接口直接依赖 Netty `ByteBuf`，消除了桥接开销；Dubbo 的 `Codec2` 接口通过自建 `ChannelBuffer` 抽象保持传输层无关性（`dubbo-remoting-api` 不依赖 Netty），代价是在 Netty 传输层引入桥接拷贝。这是架构设计上的取舍 — 模块纯净性 vs 运行时效率。

## 五、Jaws 设计的优点

1. **分层清晰** — NettyDecoder 专注帧检测（magic 校验、body 长度读取、半包等待），NettyChannelHandler 专注消息分发与业务编排（服务端线程池调度、OOM 保护），JawsCodec 专注协议语义解析，职责边界明确
2. **NettyMessage 解耦** — NettyDecoder 与 NettyChannelHandler 之间通过 `record NettyMessage` 传递，不可变、轻量，天然线程安全
3. **独立 version 字段** — header byte 2 显式声明协议版本，版本不兼容时直接拒绝，无需解析 body。Dubbo 没有此字段
4. **flag 区分响应类型** — void/exception/normal 在 header 即可判断，无需解析 body 才能知道响应类别
5. **每消息序列化协议** — flag 高 5 位嵌入 serializationId，编码时写入、解码时提取，支持同一服务端接收不同客户端的序列化方式，通过 `@SpiMeta(number)` + `ExtensionLoader.getExtensionByNumber()` 实现 O(1) 查找
6. **序列化链路闭环** — 编码从 URL 配置取序列化方式写入 flag → 解码从 flag 提取 serializationId → handler 拷贝到 response → encodeResponse 使用 response 上的 serializationNumber，全链路一致
7. **OOM 保护** — NettyDecoder 有 `maxContentLength` 检查，超大消息跳过并对 request 返回错误响应；NettyChannelHandler 有线程池拒绝策略
8. **编码降级** — NettyDecoder 在 OOM 拦截时，若消息为 request，会调用 `codec.encode()` 构造错误响应返回对端，避免对端超时等待
9. **代码简洁** — JawsCodec 整体约 360 行，逻辑直白易读，没有复杂的继承体系
10. **心跳/事件支持** — flag bit 2 作为 event 标记，`NettyDecoder` 识别后直接消费不进业务线程池；`HeartbeatHandler` + `IdleStateHandler` 实现双向心跳检测，可选配置启用

## 六、可改进空间

### ~~1. 协议头嵌入 serializationId~~ (已完成)

flag 字节高 5 位存储 serializationId（最多 32 种），通过 `@SpiMeta(number)` 注解 + `ExtensionLoader.getExtensionByNumber()` 实现按数字查找。编码写入 flag、解码从 flag 提取，序列化链路全链路闭环。

### ~~2. 编码路径减少中间分配~~ (已完成)

- Codec 接口已升级为操作 `ByteBuf`，移除 `NettyEncoder` 中间层
- 编码采用「预留 header + body 直写 ByteBuf + 回填 header」模式，0 次额外分配
- 解码通过 `retainedSlice` + `ByteBufInputStream` 零拷贝反序列化

### ~~3. Serialization 接口升级为流式 API~~ (已完成)

- 新增 `ObjectOutput` / `ObjectInput` 接口（类比 Dubbo 的同名接口）
- `Serialization` 接口从 `byte[] serialize(Object)` / `T deserialize(byte[], Class)` 改为 `ObjectOutput serialize(OutputStream)` / `ObjectInput deserialize(InputStream)`
- Hessian2 实现真正的零中间 byte[] 流式序列化（直接委托 Hessian2Output/Hessian2Input）
- Fastjson2 因 JSONB 不支持原生流式传输，使用 DataOutputStream + length-prefixed JSONB 适配
- JawsCodec 编解码路径不再依赖 Java ObjectOutputStream/ObjectInputStream，协议元数据和业务对象通过同一个 ObjectOutput/ObjectInput 流式读写
- 消除了每个参数/返回值的 per-field byte[] 拷贝

### ~~4. 增加心跳/事件消息~~ (已完成)

flag 字节 bit 2 作为 event 标记（`FLAG_EVENT = 0x04`）。`JawsCodec.encodeHeartbeat()` 编码 16 字节心跳帧（零长度 body），`NettyDecoder` 识别 event 位后直接消费，不进入业务线程池。`HeartbeatHandler` 配合 `IdleStateHandler` 实现双向心跳检测：WRITER_IDLE 发送心跳保持连接活跃，READER_IDLE 关闭死连接。通过 URL 参数 `heartbeat`（毫秒）控制启用，默认 0 禁用。

### ~~4. 懒反序列化~~ (评估后不实现)

经评估决定不实现。服务端已有线程池将 decode 放到业务线程执行，客户端侧收益有限（Response 回到业务线程后立刻消费，中间仅差几行代码开销），而 ByteBuf 引用计数跨线程管理复杂度高（retainedSlice/release 配对、超时泄漏风险），属于高风险低收益优化。

## 七、总结

Jaws 的编解码设计**分层合理、代码简洁、健壮性好**。单层帧结构简洁直接；NettyDecoder 专注帧检测、JawsCodec 专注协议语义的分层使职责明确；NettyMessage record 解耦了帧检测与业务处理；独立 version 字段和 flag 区分响应类型是合理的设计选择；flag 高 5 位嵌入 serializationId 实现了每消息独立序列化且全链路闭环；NettyDecoder/NettyChannelHandler 的双向 OOM 保护和编码降级体现了工程上的细致考量。

与 Dubbo 的编解码设计相比，Jaws 在**运行时效率上更优**：直接操作 Netty `ByteBuf` 消除了 Dubbo `ChannelBuffer` 抽象带来的桥接拷贝开销。Dubbo 选择保持 `dubbo-remoting-api` 模块的传输层无关性（通过自建 `ChannelBuffer` 抽象），Jaws 选择让 `jaws-core` 直接依赖 Netty 以换取零桥接开销 — 这是模块纯净性 vs 运行时效率的架构取舍。
