# Jaws Framework 🦈

Jaws 是一个基于 Java 17 和 Netty 的高性能 RPC 框架，提供服务注册与发现、负载均衡、容错等完整的微服务通信能力。

## 特性

- **自定义协议** — 基于 Netty 的 jaws 二进制协议，支持 fastjson2 / hessian2 序列化
- **injvm 协议** — JVM 内部直调，零网络开销，适合本地开发与测试
- **服务注册与发现** — ZooKeeper / Nacos 注册中心，支持心跳续约与失败重连
- **Spring Boot Starter** — `@EnableJaws` + `@JawsService` / `@JawsReference` 注解，开箱即用
- **多种负载均衡** — random、roundRobin、leastActive、shortestResponse、consistentHash
- **高可用容错** — failover（失败切换）、failfast（快速失败）、failback（异步重试）
- **SPI 扩展** — 所有核心组件（Protocol、Cluster、LoadBalance、Filter、Serialization 等）均通过 SPI 可插拔
- **优雅停机** — 四阶段停机（停止接收 → 等待在途请求 → 注销注册中心 → 关闭连接），零损伤发布
- **泛化调用** — 无需依赖接口 JAR 包即可发起 RPC 调用，适用于网关、测试平台等场景
- **可观测性** — 内置 Micrometer 指标采集和 OpenTelemetry 链路追踪，通过 Filter SPI 自动生效
- **方法级别配置** — 可为单个方法设置独立的超时、重试策略
- **RpcContext** — 消费端可获取实际调用的服务端地址，提供端可获取调用方 IP
- **动态端口** — 端口设为 -1 时自动从 10000 递增分配，避免冲突
- **连接预热 / Warm-up** — 新启动的 Provider 权重随时间线性增长，避免冷启动被打爆
- **服务鉴权 / Token** — 基于 Token 的服务认证，防止未授权调用，通过 Filter 自动生效
- **配置热更新** — 运行时动态调整负载均衡、容错策略、超时、重试等参数，无需重启服务

## 模块

```
jaws-parent
├── jaws-core                  # 核心：协议抽象、SPI、序列化、集群、路由、Filter、配置
├── jaws-transport-netty       # Netty 传输层实现
├── jaws-registry-zookeeper    # ZooKeeper 注册中心实现
├── jaws-registry-nacos        # Nacos 注册中心实现
├── jaws-extensions            # 扩展：Micrometer 指标 + OpenTelemetry 链路追踪 Filter
├── jaws-spring-boot
│   ├── jaws-spring-boot-starter                # Spring Boot 自动配置与注解支持
│   └── jaws-observability-spring-boot-starter  # 可观测性 Spring Boot 自动装配
└── jaws-samples
    ├── jaws-sample-api        # 服务接口定义（DemoService、OrderService）
    ├── jaws-sample-injvm      # injvm 协议示例（无需 ZK）
    ├── jaws-sample-provider   # 服务提供者（jaws + ZooKeeper）
    ├── jaws-sample-consumer   # 服务消费者
    ├── jaws-sample-provider-boot  # Spring Boot 服务提供者（jaws + Nacos）
    ├── jaws-sample-consumer-boot  # Spring Boot 服务消费者
    └── jaws-sample-benchmark  # 性能基准测试
```

## 快速开始

### 环境要求

- Java 17+
- Maven 3.8+（或使用内置 `./mvnw`）
- ZooKeeper 3.9+（ZooKeeper 注册中心模式需要）
- Nacos 3.x（Nacos 注册中心模式需要）

### 编译

```bash
./mvnw install -DskipTests
# 或
./run-sample.sh build
```

### 运行示例

项目提供 `run-sample.sh` 脚本统一管理示例：

```bash
# injvm 协议示例（无需 ZK，开箱即用）
./run-sample.sh injvm

# 一键运行：启动 provider → 运行 consumer → 停止 provider（需要 ZK）
./run-sample.sh run

# 分步运行
./run-sample.sh provider           # 前台启动 provider（需要 ZK 在 127.0.0.1:2181 运行）
./run-sample.sh provider 10001     # 指定端口
./run-sample.sh provider-bg        # 后台启动
./run-sample.sh provider-bg -1     # 后台启动，自动分配端口
./run-sample.sh consumer           # 运行 consumer（需要先启动 provider）
./run-sample.sh stop               # 停止所有后台 provider 并清理
```

### 性能测试

```bash
# injvm 协议基准（框架纯开销）
./run-sample.sh bench-injvm

# jaws + Netty 网络传输基准
./run-sample.sh bench-jaws

# 自定义参数
THREADS=8 WARMUP=5 DURATION=20 ./run-sample.sh bench-jaws

# 切换序列化方式
SERIALIZATION=hessian2 ./run-sample.sh bench-jaws

# 模拟业务耗时（Provider 端每次调用 sleep 5ms）
SLEEP=5 ./run-sample.sh bench-jaws
```

**Benchmark 环境变量：**

| 变量          | 说明           | 默认值      | 适用范围      |
|-------------|--------------|-----------|-----------|
| `THREADS`   | 并发线程数        | 4         | bench-injvm / bench-jaws |
| `WARMUP`    | 预热秒数         | 5         | bench-injvm / bench-jaws |
| `DURATION`  | 测量秒数         | 10        | bench-injvm / bench-jaws |
| `PORT`      | jaws 协议端口    | 10010     | 仅 bench-jaws |
| `SERIALIZATION` | 序列化方式（fastjson2 / hessian2） | fastjson2 | 仅 bench-jaws |
| `SLEEP`       | Provider 端模拟业务耗时（毫秒）      | 0         | bench-injvm / bench-jaws |

**参数选择建议：**

| 场景 | 线程数 | 测量秒数 | 说明 |
|------|--------|---------|------|
| 快速验证 | 4 | 10 | 默认值，确认功能正常 |
| 常规压测 | 8~16 | 10 | 观察中等并发下的表现 |
| 极限吞吐 | 32~64 | 20 | 探索 QPS 天花板 |
| 模拟业务（有 sleep） | 8~16 | 20~30 | 单次调用慢，需更多时间积累样本 |

> QPS = 总调用次数 / 测量秒数。并发线程数决定“同时有多少请求在飞”，线程越多 QPS 越高，直到达到系统瓶颈。

## 代码示例

### 定义服务接口

```java
public interface DemoService {
    String hello(String name);
    User rename(User user, String newName);
    List<User> getUsers();
}
```

### Spring Boot 用法

引入 `jaws-spring-boot-starter` 和注册中心依赖后，通过注解即可完成服务发布与引用。更多用法请参考 `jaws-samples` 各示例模块。

**Maven 依赖：**

```xml
<dependency>
    <groupId>org.hongxi</groupId>
    <artifactId>jaws-spring-boot-starter</artifactId>
    <version>${jaws.version}</version>
</dependency>
<!-- 注册中心：二选一 -->
<dependency>
    <groupId>org.hongxi</groupId>
    <artifactId>jaws-registry-nacos</artifactId>
    <version>${jaws.version}</version>
</dependency>
<!-- 或使用 ZooKeeper -->
<!--
<dependency>
    <groupId>org.hongxi</groupId>
    <artifactId>jaws-registry-zookeeper</artifactId>
    <version>${jaws.version}</version>
</dependency>
-->
```

**application.yml：**

```yaml
spring:
  application:
    name: sample-provider-boot

jaws:
  application:
    name: ${spring.application.name}
  protocol:
    name: jaws
    serialization: fastjson2
  service:
    export: "jaws:10000"
  registry:
    address: 127.0.0.1
    port: 8848
    protocol: nacos
```

**发布服务（Provider）：**

```java
@EnableJaws
@SpringBootApplication
public class ProviderApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProviderApplication.class, args);
    }
}

@JawsService
public class DemoServiceImpl implements DemoService {
    @Override
    public String hello(String name) {
        return "hello " + name;
    }
}
```

**引用服务（Consumer）：**

```java
@Component
public class MyRunner implements CommandLineRunner {

    @JawsReference
    private DemoService demoService;

    @Override
    public void run(String... args) {
        String result = demoService.hello("jaws");
        System.out.println("result: " + result);
    }
}
```

### 泛化调用

无需依赖 Provider 的接口 JAR 包，通过 `GenericService.$invoke()` 即可发起 RPC 调用。Spring Boot 注解方式使用 `@JawsReference(generic = true, serviceInterface = "...")` 即可，完整示例请参考 `jaws-sample-consumer` 中的 `GenericSampleConsumer`。

### 优雅停机

Jaws 支持四阶段优雅停机，确保服务下线时不中断在途请求：

```
Phase 1: stopAccept()              关闭 ServerChannel，不再接收新连接
Phase 2: awaitInactiveRequests()   等待在途请求处理完成（默认超时 10s）
Phase 3: unregister()              从注册中心注销服务
Phase 4: unexport() → destroy()    关闭连接、释放 EventLoopGroup 和线程池
```

通过 `gracefulShutdownTimeout` URL 参数可配置 Phase 2 最大等待时间（默认 10000ms）。

**验证步骤：**

```bash
# 1. 启动 ZooKeeper（如未运行）
# 2. 启动 Provider
./run-sample.sh provider

# 3. 另开终端，运行 Consumer
./run-sample.sh consumer

# 4. 在 Provider 运行期间发送 SIGTERM（不要用 kill -9）
jps | grep SampleProvider   # 找到 PID
kill -TERM <PID>

# 5. 观察 Provider 日志，应依次输出：
# [GracefulShutdown] Phase 1: Stop accepting new requests
# [GracefulShutdown] Phase 2: Waiting for in-flight requests to complete
# All in-flight requests completed before shutdown
# [GracefulShutdown] Phase 3: Unregister from registry
# [GracefulShutdown] Phase 4: Close connections and release resources
# [GracefulShutdown] Graceful shutdown completed
```

### 服务鉴权 / Token

Provider 配置 token 后，Consumer 自动从注册中心获取并携带，无需额外代码：

```java
// Provider 端：注解方式
@JawsService(token = "my-secret-token")
public class DemoServiceImpl implements DemoService { }

// Provider 端：编程式
serviceConfig.setToken("my-secret-token");
```

也可通过 YAML 全局配置：

```yaml
jaws:
  service:
    token: my-secret-token
```

未配置 token 时自动跳过校验，完全向后兼容。

### 连接预热 / Warm-up

新启动的 Provider 逐步增加权重，避免 JIT 未充分编译、缓存未热时被打爆：

- Provider 注册时自动写入启动时间戳
- Consumer 端 LoadBalance 根据运行时长线性加权（0 → 满权重）
- 支持 Random / RoundRobin / LeastActive / ShortestResponse
- 默认预热时长 10 分钟，可通过 `warmup` URL 参数自定义（毫秒）

### 配置热更新

Consumer 端订阅注册中心的配置节点，运行时动态调整参数，无需重启服务。支持动态切换的参数：

| 参数 | 说明 | 示例值 |
|------|------|--------|
| `loadbalance` | 负载均衡策略 | `random`、`roundRobin`、`leastActive` |
| `haStrategy` | 容错策略 | `failover`、`failfast`、`failback` |
| `requestTimeout` | 调用超时（ms） | `5000` |
| `retries` | 重试次数 | `2` |

**Nacos 模式：** 在 Nacos 控制台 → 配置管理 → 配置列表 中新建配置：

- **Data ID**：接口全限定名，如 `org.hongxi.jaws.sample.api.DemoService`
- **Group**：JAWS 服务分组（即 `jaws.registry.group` 配置值，默认 `default`）
- **配置格式**：JSON
- **配置内容**：

```json
{
  "loadbalance": "random",
  "haStrategy": "failfast",
  "requestTimeout": 5000,
  "retries": 2
}
```

**ZooKeeper 模式：** 在 `/jaws/{group}/config` 节点写入同样的 JSON 内容，Consumer 通过 CuratorCache 自动感知变更。

### 可观测性

引入 `jaws-observability-spring-boot-starter` 即可获得 Micrometer 指标采集和 OpenTelemetry 链路追踪能力，无需额外代码。

**Maven 依赖：**

```xml
<dependency>
    <groupId>org.hongxi</groupId>
    <artifactId>jaws-observability-spring-boot-starter</artifactId>
    <version>${jaws.version}</version>
</dependency>
```

**功能说明：**

- **链路追踪** — 基于 Micrometer Tracing + OpenTelemetry，通过 `Propagator` 自动在 consumer/provider 间传播 trace context（W3C TraceContext 格式），确保全链路 traceId 一致
- **指标采集** — 自动统计 RPC 调用次数、成功率、耗时分布、活跃请求数等指标，通过 `side` tag 区分 consumer/provider 视角
- **日志关联** — traceId/spanId 由 OTel 日志桥接层自动注入 MDC，日志格式 `{traceId}-{spanId}`

## 技术栈

| 组件   | 技术                       | 版本             |
|------|--------------------------|----------------|
| 语言   | Java                     | 17             |
| 网络   | Netty                    | 4.1.132        |
| 注册中心 | ZooKeeper + Curator      | 3.9 / 5.9      |
| 注册中心 | Nacos                    | 3.2.2          |
| 序列化  | fastjson2 / hessian-lite | 2.0.62 / 4.0.5 |
| 框架集成 | Spring Boot              | 3.5            |
| 可观测性 | Micrometer + OpenTelemetry | 1.14 / 1.48  |
| 工具   | Guava                    | 33.6           |
| 日志   | slf4j                    | 2.0.17         |


&copy; [hongxi.org](http://hongxi.org)
