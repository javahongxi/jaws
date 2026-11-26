# Jaws Netty 解码器：半包与粘包处理

基于 [NettyDecoder](../jaws-core/src/main/java/org/hongxi/jaws/transport/netty/NettyDecoder.java) 与 [JawsCodec](../jaws-core/src/main/java/org/hongxi/jaws/transport/netty/JawsCodec.java) 源码，梳理 jaws 协议在 TCP 字节流上的定帧机制：半包（拆包）怎么等、粘包怎么切、帧长知识归谁所有，以及为什么不用 Netty 自带的 `LengthFieldBasedFrameDecoder`；末节对比 HTTP/2 传输层——标准协议的帧层由 Netty 包办，消息边界仍要自己处理。

## 一、问题背景：TCP 没有消息边界

TCP 是无边界的字节流协议，发送端一次 `write` 的完整帧（16 字节 header + body），到接收端可能呈现为任意切分形态：

- **半包（拆包）**：一帧被拆成多次 `channelRead` 到达，如第一次只有 header + body 前半段，第二次才补齐后半段；
- **粘包**：多个帧的字节挤在同一次读里，一次 `read()` 收进 N 个完整帧外加第 N+1 帧的残段。

两者是同一事实的两面，解码器必须自己按协议边界切帧。jaws 中容易触发粘包的典型场景：

1. **连接共享 + 多路复用**：客户端按地址复用连接（clientMap），高并发下多个请求帧在同一条 channel 上背靠背写出；对端事件循环稍忙，一次读事件就把多个帧一起收进来。响应方向同理——服务端处理完一批请求后密集写回。
2. **心跳帧**：只有 16 字节（header + 空 body，见 `JawsCodec.encodeHeartbeat`），极小，很容易和业务帧挤在同一个 TCP 段或同一次读里。
3. **TCP 自身的合并**：Nagle 算法把连续的小写合并成一个段；接收侧内核缓冲区攒了多个段时，Netty 一次读事件全部读出。帧越小、QPS 越高，粘包越常见。

## 二、帧结构：长度字段自描述

jaws 协议帧 = 定长 16 字节 header + 变长 body（`JawsCodec.HEADER_LENGTH`）：

| 字节偏移 | 字段 | 说明 |
|---|---|---|
| 0-1 | magic | `0x4A57`（ASCII 'JW'），协议标识 |
| 2 | version | 协议版本，当前为 1 |
| 3 | flag | 低 3 位数据类型（`MASK=0x07`：0x00=request、0x01=response、0x02=exception、0x03=void）；bit 2 为 `FLAG_EVENT=0x04`（心跳）；高 5 位序列化 id（`SERIALIZATION_MASK=0xF8`） |
| 4-11 | requestId | 请求 ID（long，心跳帧为 0） |
| 12-15 | bodyLength | body 字节数（int） |

**帧总长 = 16 + bodyLength**，其中 bodyLength 由编码端"预留 + 回填"产生：`encodeRequest`/`encodeResponse` 先把 writerIndex 前移 16 字节预留 header 空间，再用序列化实现的流式 `ObjectOutput` 写 body（接口名、方法名、参数、attachments），写完按 `writerIndex - headerStart - HEADER_LENGTH` 算出实际长度，最后 `writeHeader` 回到预留位置回填整个 header。body 长度只能在序列化完成后确定，回填模式避免了先序列化到临时 byte[] 再拷贝的额外分配。

TCP 字节流定帧无非三种办法，jaws 用的是第三种：

| 方式 | 帧边界依据 | Netty 现成实现 |
|---|---|---|
| 固定长度 | 写死 N 字节 | `FixedLengthFrameDecoder` |
| 分隔符 | 特殊字节序列 | `DelimiterBasedFrameDecoder` |
| **长度字段** | **header 携带 body 长度** | `LengthFieldBasedFrameDecoder` |

## 三、职责划分：Netty 不知道一帧多长

"一帧多长"这个知识完全来自 jaws 协议约定，父类 `ByteToMessageDecoder` 只是个不懂协议的循环外壳。它的 `callDecode` 核心逻辑只看字节数：

```java
// ByteToMessageDecoder.callDecode（简化）
while (in.isReadable()) {
    int oldInputLength = in.readableBytes();
    decode(ctx, in, out);
    if (oldInputLength == in.readableBytes()) {
        break;   // 一个字节都没消费 → 数据不够，退出等下次
    }
    // 消费了字节 → 不管消费了多少，继续调 decode
}
```

它从不关心"帧"的概念，只观察 readerIndex 有没有前进、`out` 有没有新增。帧长知识在 `NettyDecoder.decode` 内部分两步获得：

1. **定长部分靠写死的协议结构**：header 固定 16 字节是编解码两端共同约定，所以开头敢直接判断 `readableBytes() < HEADER_LENGTH` 就 return；
2. **变长部分靠 header 自描述**：`in.readInt()` 从 bytes 12-15 读出编码端回填的 bodyLength，帧总长随之确定。

"每轮 decode 只切一帧"是这份实现的行为约定，不是 Netty 提供的机制：decode 每次恰好消费 16 + bodyLength 字节就停手，把下一帧字节留在缓冲区，父类发现"消费了字节"就再调一轮。

## 四、半包处理：两处长度检查 + mark/reset + 累积缓冲区

`NettyDecoder` 中有两处半包检查，对应两种不完整形态：

| 检查 | 场景 | 处理方式 |
|---|---|---|
| `readableBytes() < HEADER_LENGTH` | header 本身被拆包（连 16 字节都没到齐） | 直接 return；此时尚未动读指针，无需 reset |
| `readableBytes() < bodyLength` | header 完整但 body 被拆包 | 已消费 16 字节，必须 `resetReaderIndex()` 回退后 return |

第二处的关键配套是指针管理：读 header 前先 `markReaderIndex()` 把帧起点存为书签，发现 body 不齐时 `resetReaderIndex()` 精确回退，保证这一帧的字节原封不动留在缓冲区。

真正让"等下次"成立的是父类的**累积缓冲区（cumulation buffer）**：本次未消费的数据留在缓冲区，下次网络数据到达时 Netty 合并新旧数据再次调用 decode，从头重新解析这一帧。等 body 到齐，`readRetainedSlice(bodyLength)` 零拷贝切出 body，封装为 [DecodedFrame](../jaws-core/src/main/java/org/hongxi/jaws/transport/netty/DecodedFrame.java)（record：isRequest、requestId、flag、body）传给下游。

**变体：超限帧的跨 chunk 排水**。body 超过 `maxContentLength` 的帧被拒绝后，其 body 可能分多个 chunk 陆续到达。decode 用 `bytesToSkip` 字段记账：本次先 skip 掉已到达的部分，剩余量留待后续 decode 调用继续 drain（方法开头的 `bytesToSkip > 0` 分支），drain 完才恢复正常解析。这本质也是半包场景——只不过等的不是"凑齐后解析"，而是"凑齐后丢弃"，目的都是保持流边界同步且不断连接。

## 五、粘包处理：父类循环"消费了就继续切"

粘包不需要 decode 自己写循环，由第三节的 `callDecode` 循环天然处理。对照 NettyDecoder 的实现，每轮恰好消费一个完整帧：

- **业务帧**：`readRetainedSlice(bodyLength)` 精确切走 16 + bodyLength 字节，`out.add` 产出一帧；
- **心跳帧**：`FLAG_EVENT` 分支 skip 完 requestId 和 body 后 return，虽没产出消息，但 readerIndex 前进了，循环继续。

缓冲区里粘着 N 个帧时 decode 被调 N 次切出 N 帧；切到最后一帧发现字节不够（半包），reset 后 return，此时"没消费字节也没产出"，循环退出，残段留在累积缓冲区等下次数据。**半包靠 decode 里的长度检查 + reset 兜住，粘包靠父类循环兜住**，两者拼起来保证：无论字节流怎么切分合并，下游拿到的永远是边界精确的 DecodedFrame。

## 六、为什么不用 LengthFieldBasedFrameDecoder

`LengthFieldBasedFrameDecoder`（下称 LFBD）参数上配得出来：`lengthFieldOffset=12, lengthFieldLength=4, lengthAdjustment=0, initialBytesToStrip=0`。但它只解决"切帧"，而 NettyDecoder 在切帧的同时还承担了几个 LFBD 给不了的行为：

1. **超限帧处置策略：回错误响应 + 排水，不断连接**。这是最核心的差异。jaws 的策略是 body 超限时解析 requestId、构造错误 Response 写回对端，再跨多次 decode 把残余 body drain 掉（`bytesToSkip`），连接继续用。LFBD 遇到超长帧只有两种内置行为：抛 `TooLongFrameException`（沿 pipeline 传播，通常走到 exceptionCaught 关连接），或 fail-fast 后静默丢弃整帧（客户端只能等超时，拿不到明确错误原因）。要在 LFBD 上实现"回错误响应"，还是得先解析 header 拿 requestId 和序列化位，等于把 header 解析又写一遍。
2. **心跳帧在解码层就地消费**。`FLAG_EVENT` 帧直接 skip，不进业务层。LFBD 不认识 flag 字节，会把心跳帧原样吐给下游，需要再加一个 handler 过滤——多一次帧传递、多一层 pipeline。
3. **魔数/版本快速失败**。手写版读到第 2 个字节就能校验 magic，协议不匹配立刻抛异常。LFBD 会先把 bytes 12-15 当长度读出来——对垃圾流或误连的其它协议客户端，这个"长度"可能是天文数字，结果要么误报 TooLongFrame，要么傻等永远凑不齐的字节。先验 magic 能把这类问题在 16 字节内识别掉。
4. **header 只解析一次，下游拿到结构化元数据**。LFBD 的产物是完整帧 ByteBuf（或固定 strip 前 N 字节）：`initialBytesToStrip=0` 时下游得把 16 字节 header 重新解析一遍；`=16` 时 requestId/flag 直接丢了。手写版一次遍历读完 header，body 到齐后零拷贝切片，元数据随 DecodedFrame 传下去，[NettyChannelHandler](../jaws-core/src/main/java/org/hongxi/jaws/transport/netty/NettyChannelHandler.java) 直接调 `JawsCodec.decodeBody`，不再碰 header。

**代价**是自己承担切帧的正确性责任：mark/reset 时机、心跳分支的 skip 偏移、bytesToSkip 的跨 chunk 记账，错一处就流错位（见第七节）。这些坑 LFBD 帮你趟平了，手写就得自己趟。

这不是 jaws 独创的取舍——Dubbo 的 `ExchangeCodec.InternalDecoder` 同样继承 `ByteToMessageDecoder` 手写，动机一致：header 校验、payload 超限回错误响应、心跳帧短路，都要求解码器理解协议语义，而不只是按长度切字节。**LFBD 适合"纯切帧、语义在下游"的场景；当切帧和协议语义处置耦合在一起时，手写一个反而更省。**

## 七、不变量与常见陷阱

手写 decoder 必须守住的不变量：**每轮 decode 要么精确消费整数个帧的字节，要么一个字节都不消费（reset 干净）**。违反它的典型形态：

- **心跳分支偏移错误**：检测到 `FLAG_EVENT` 后必须先 `skipBytes(8)` 跳过 requestId 再 `readInt()` 读 bodyLength（此时才对应 bytes 12-15）。漏跳 8 字节会读到错误偏移，只消费部分帧数据，残留字节在下一次 decode 被误判为新帧的 magic——典型症状 `magic not match: 0`。粘包场景下这个错误会被立即放大，因为残留字节后面紧跟着的就是真实数据。
- **半包回退不干净**：body 不齐时若忘了 reset，已消费的 16 字节 header 就丢了，下次 decode 从 body 中间开始解析，同样报 magic 不匹配。
- **排水记账错位**：`bytesToSkip` 少记或多记，都会让后续真实帧的字节被当成垃圾丢弃或让垃圾被当成帧头解析。

下游契约：DecodedFrame 的 body 是 `readRetainedSlice` 产生的 retained slice（引用计数 +1），由 NettyChannelHandler 处理完后 release；异步提交到 serverExecutor 前需再 retain，避免 channelRead 返回后框架自动 release 导致 use-after-free。

该 decoder 由三处 pipeline 共用：[NettyServer](../jaws-core/src/main/java/org/hongxi/jaws/transport/netty/NettyServer.java)、[NettyClient](../jaws-core/src/main/java/org/hongxi/jaws/transport/netty/NettyClient.java) 与 [AdaptiveServer](../jaws-core/src/main/java/org/hongxi/jaws/transport/adaptive/AdaptiveServer.java)，请求/响应两个方向走同一套定帧逻辑，靠 flag 的数据类型位区分。

## 八、HTTP/2 传输层：帧层交给 Netty，消息层自己兜底

jaws 的 HTTP/2 传输（http2/wire）不走 NettyDecoder，pipeline 是（[AbstractHttp2Server](../jaws-core/src/main/java/org/hongxi/jaws/transport/http2/AbstractHttp2Server.java) / [AbstractHttp2Client](../jaws-core/src/main/java/org/hongxi/jaws/transport/http2/AbstractHttp2Client.java)）：

```
[ssl] → flush_consolidation → http2_codec(Http2FrameCodec) → http2_multiplex(Http2MultiplexHandler)
```

半包/粘包要分两层看，答案不一样。

### 8.1 帧层：Netty 全包

`Http2FrameCodec` 内部就是 HTTP/2 版的"NettyDecoder"：按 9 字节帧头（3B length + type + flags + streamId）从 TCP 字节流里切帧——帧半包等累积缓冲补齐、帧粘包循环切分、HEADERS 的 HPACK 跨 CONTINUATION 帧拼装，全在 Netty 里完成。传到业务 handler 的已经是完整的 `Http2HeadersFrame`/`Http2DataFrame` 对象，jaws 代码里看不到裸字节流。jaws 二进制协议是私有协议，没有现成帧层才要手写 NettyDecoder；HTTP/2 是标准协议，这层 Netty 替你写好了。

多请求在同一条连接上交错（HTTP/2 意义上的"粘包"），也由 `Http2MultiplexHandler` 按 streamId 分发到各自独立的 stream channel 解决——二进制协议里对应的是 requestId 分发。

### 8.2 消息层：Netty 只保证帧完整，不保证消息完整

**unary / server-streaming 的请求体**：一条 RPC 消息可能被发送端拆成多个 DATA 帧（流控窗口、maxFrameSize 限制），Netty 交付的是 N 个完整帧而非一条完整消息。[Http2StreamServerHandler.onData](../jaws-core/src/main/java/org/hongxi/jaws/transport/http2/Http2StreamServerHandler.java) 自己攒：逐帧写入 `ByteArrayOutputStream`，直到 `END_STREAM` 才 dispatch，`maxContentLength` 封顶。这就是消息级的"半包重组"，边界标志是 END_STREAM 而不是长度字段。

**streaming 的每个 item**：jaws 的约定是**一个 DATA 帧 = 一个 item**——发送端 [Http2StreamCodec.encodeItem](../jaws-core/src/main/java/org/hongxi/jaws/transport/http2/Http2StreamCodec.java) 整体序列化后写一帧，接收端 [Http2StreamStreamingHandler.onData](../jaws-core/src/main/java/org/hongxi/jaws/transport/http2/Http2StreamStreamingHandler.java) 逐帧直接 `decodeItem`，没有长度前缀、也没有跨帧重组。它依赖"帧层保证这一帧字节不多不少"，把消息边界问题转嫁给了帧边界。这与 gRPC 线格式不同——gRPC 用 5 字节长度前缀在 DATA 载荷内自定界，item 与帧解耦。

### 8.3 收束

| 层 | 边界依据 | 谁处理 |
|---|---|---|
| TCP 字节流 → HTTP/2 帧 | 9B 帧头 length | Netty `Http2FrameCodec` |
| 帧 → 流（多路复用分离） | streamId | Netty `Http2MultiplexHandler` |
| 帧 → unary 消息 | END_STREAM + 累积 | jaws `Http2StreamServerHandler` |
| 帧 → streaming item | 一帧一 item 约定 | jaws `Http2StreamCodec`（无重组） |

一个隐含边界：逐帧 decodeItem 的约定要求 item 不超过 maxFrameSize（默认 16KB）——超大 item 会被 Netty 远端流控拆成多帧，逐帧解码就会失败；若要支持超大 item，需像 gRPC 那样加长度前缀做跨帧重组。