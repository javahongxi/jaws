# jaws wire 客户端调 grpc-go 服务，寻址走 Nacos

对端不是 jaws：是 [sweet](https://github.com/javahongxi/sweet) 的 `example/greeter`，一个
grpc-go 服务端，用**手写**的 Nacos 命名协议把自己注册进去。jaws 这边一行 Go 代码都不碰，只把
自己适配到位；四种 gRPC 方法形态全部经**管线**发出（cluster → 负载均衡 → filter 链 →
`WireReference` → `WireClient`）。

所以这一发证明的是：jaws 能消费一个**不是它注册的**服务——注册中心里躺着的是别的进程按自己的
命名习惯写下的记录，jaws 不去改对方。

## 三处必须对齐，缺一处就调不通

| 对齐点 | jaws 这边 | 对端（grpc-go）那边 |
| --- | --- | --- |
| 服务名 | `registryNamespace=`（空）⇒ 去掉 `jaws/` 前缀，按 `path` 原样查 | 注册名 `sweet.example.v1.Greeter` |
| group | `ref.setGroup("DEFAULT_GROUP")` | 默认 group `DEFAULT_GROUP`（jaws 自己默认 `default_rpc`）|
| `:path` | 由 Java 接口全限定名 + 方法名逐字拼成 `/sweet.example.v1.Greeter/Greet` | 只认 proto 的 `package.Service/Method` |

第三条是本模块最别扭也最硬的一条：出站 `:path` 的服务段取自 `interfaceClass.getName()`、方法段
取自 `method.getName()`，全程没有任何转换，也没有任何注解或配置能覆盖它。因此
`src/main/java/sweet/example/v1/Greeter.java` **必须**叫这个名字，方法**必须**是 `Greet` /
`GreetStream` / `GreetAll` / `GreetChat` 这种大写入头——包名 `sweet.example.v1` 在 Java 侧合法
（`v1` 以字母开头），但不合 Java 习惯，这是为了让出站 path 命中 grpc-go 而付的代价。把它改成
`org.hongxi...` 那样"顺手"的名字，四个方法会全部变成 `UNIMPLEMENTED`。

`jaws-registry-nacos` 里那个「去掉命名空间前缀」的开关是随本模块新增的：
`UrlParam.Identity.REGISTRY_NAMESPACE`，默认沿用 `jaws`，所以现存 8 个 nacos 样例与 `jaws↔jaws`
的注册/查询行为一字未变；显式置空时 `NacosPathUtils.toServiceName` 直接返回 `path`。它只能经
`ProtocolConfig#setParameter` 传下来——`BaseConfig#putIfPresent` 会把空串丢掉，而 protocol 的
参数表是 `params.putAll(parameters)` 原样并入 URL 的。

## 跑

前提：本地 Nacos 3.x 在 `127.0.0.1:8848`（gRPC 口自动取 `9848`），鉴权关闭。

```
# 终端 1：grpc-go 服务端（在 sweet 仓库里）
cd /path/to/sweet
go run ./example/greeter/cmd/server -registry=nacos -server 127.0.0.1:9848 -listen 127.0.0.1:20001

# 终端 2：jaws 消费端（在 jaws 仓库根目录）
mvn -q -pl jaws-samples/jaws-sample-wire-sweet -am -DskipTests install
DEPS=$(mvn -pl jaws-samples/jaws-sample-wire-sweet dependency:build-classpath \
  -DincludeScope=runtime -DexcludeGroupIds=org.hongxi -Dmdep.outputFile=/dev/stdout -q | tail -1)
java -cp "jaws-stream-api/target/classes:jaws-core/target/classes:jaws-wire/target/classes:\
jaws-registry-nacos/target/classes:jaws-samples/jaws-sample-wire-sweet/target/classes:$DEPS" \
  org.hongxi.jaws.sample.wire.sweet.SweetGreeterConsumer
```

入参：`-name`（一元与服务端流用）、`-times`（服务端流回几条）、`-names`（两种流式每个名字一帧）、
`-hold`（秒，让进程活着等注册中心推送）。

## 实测（2026-10-28）

四种形态，全部由注册中心喂地址：

```
resolving sweet.example.v1.Greeter through nacos, group=DEFAULT_GROUP, namespace=(none)
Greet          => Hello, jaws!   server=127.0.0.1:20001
GreetStream    => [Hello #1, jaws!, Hello #2, jaws!, Hello #3, jaws!]   server=127.0.0.1:20001
GreetAll       => Hello, alice, bob, charlie!   server=127.0.0.1:20001
GreetChat      => [Hello, alice!, Hello, bob!, Hello, charlie!]   server=127.0.0.1:20001
done.
```

`server=...` 那半行来自 `RpcContext#getServerUrl`，是集群层真正拨出去的地址。它之所以算证据而不
是装饰，是因为消费端命令行里没有任何 host:port——地址只能来自这一次订阅：

```
INFO org.hongxi.jaws.registry.nacos.NacosRegistry -- subscribe service: serviceName=sweet.example.v1.Greeter, group=DEFAULT_GROUP, info=wire://10.62.19.145:0/sweet.example.v1.Greeter?registryNamespace=&protocol=wire&endpointType=service&application=sample-wire-sweet&module=sample-wire&check=false&transportFactory=wire&version=1.0&requestTimeout=5000&group=DEFAULT_GROUP&
INFO org.hongxi.jaws.cluster.directory.RegistryDirectory -- init discover: consumerUrl=wire://10.62.19.145:0/sweet.example.v1.Greeter?group=DEFAULT_GROUP, discoveredCount=1
INFO org.hongxi.jaws.cluster.directory.RegistryDirectory -- discovered urls: [wire://127.0.0.1:20001/DEFAULT_GROUP/sweet.example.v1.Greeter/1.0/service]
```

第一行的 `serviceName=sweet.example.v1.Greeter` 就是那个开关生效的样子（默认形态是
`serviceName=jaws/...`），URL 尾部的 `registryNamespace=` 是空值原样进了 reference URL。

## 对端优雅下线，这边收到推送

`-hold 12` 挂着消费端，第 6 秒把 Go 服务端 `SIGTERM` 掉。服务端先注销再 drain：

```
sweet server up: listen=127.0.0.1:20001 advertised=127.0.0.1:20001 service=sweet.example.v1.Greeter registry=nacos
signal received, draining (grace period 200ms)
stopped: deregistered then drained
```

消费端这边（同一次运行，按真实时序；前两行是四发成功之后才有 holding）：

```
Greet          => Hello, jaws!   server=127.0.0.1:20001
--- holding 12s: kill the Go server now to watch the instance list empty out ---
INFO org.hongxi.jaws.registry.nacos.NacosRegistry -- service list change: serviceName=sweet.example.v1.Greeter, group=DEFAULT_GROUP, instanceCount=1
INFO org.hongxi.jaws.registry.nacos.NacosRegistry -- service list change: serviceName=sweet.example.v1.Greeter, group=DEFAULT_GROUP, instanceCount=0
--- calling again after the hold ---
Greet          => failed: error_message: AdaptiveLoadBalance No available references for call : references_size=1 requestId=8 interface=sweet.example.v1.Greeter method=Greet(org.hongxi.jaws.sample.wire.sweet.proto.GreetRequest), error_code: 40001
done.
```

`instanceCount` 从 1 走到 0 是 Nacos 推过来的：没有人告诉消费端"服务端下线了"，它是订阅回调收到
的。等待期结束后那一发失败在**发出请求之前**——`AbstractLoadBalance#selectCandidates`
（`jaws-core/.../cluster/loadbalance/AbstractLoadBalance.java:88-109`）先按路由过滤出 1 条
reference，再按 `isAvailable()` 筛候选，筛完为空就抛。所以错误里的 `references_size=1` 不是自相
矛盾：那是列表长度，不是候选数。这条错误也就不是 gRPC 状态码——请求根本没上到线上。

## 状态码跨语言

Go 侧四个入口共用的那条 `status.Error(codes.InvalidArgument, "name is required")`，在 `-name ""`
这一发里以 gRPC 状态原样回到 Java：

```
Greet          => failed: error_message: gRPC INVALID_ARGUMENT: name is required, error_code: 40001   (从 JawsServiceException 的 cause 链里取出)
GreetStream    => failed: error_message: gRPC INVALID_ARGUMENT: name is required, error_code: 40001
```

两种形态的落点不同：一元经 `FailoverCluster`，状态在 cause 链里；服务端流的顶层异常本身就是
`StatusException`。示例里 `failureMessage` 把两者统一到同一行输出上，所以读起来是同一句话。

## 不在这里的东西

- 不改 `run-sample.sh`：跑法就是上面那串 `java -cp`，脚本接入等看过实现再定。
- 没有反向（grpc-go 调 jaws 注册的服务）：那要 sweet 侧实现 Java 那套 `protocol`/`path` 哨兵
  元数据的解释，属于另一件事。
- 服务提供方仍是 sweet 自己的样例，不是本模块的一部分；`src/main/proto/greeter.proto` 是它的
  vendored 副本，只加了 `java_*` 选项。两边不一致时以 sweet 为准，这里手工同步。
