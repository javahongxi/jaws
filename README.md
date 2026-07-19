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
- **流量调度 / Command** — 跨分组流量调度，支持按权重合并多 group 服务、IP 路由规则，灰度发布利器

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
    address: nacos://127.0.0.1:8848
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

## 深入了解更多

| 主题 | 说明 |
|------|------|
| [泛化调用](doc/generic-invocation.md) | 无需接口 JAR 包即可发起 RPC 调用 |
| [优雅停机](doc/graceful-shutdown.md) | 四阶段零损伤发布 |
| [服务鉴权](doc/token-auth.md) | 基于 Token 的服务认证 |
| [连接预热](doc/warm-up.md) | Provider 冷启动权重渐增 |
| [配置热更新](doc/config-hot-reload.md) | 运行时动态调整负载均衡、容错、超时等参数 |
| [流量调度](doc/command-routing.md) | 跨分组流量合并与 IP 路由规则 |
| [可观测性](doc/observability.md) | Micrometer 指标 + OpenTelemetry 链路追踪 |
| [性能测试](doc/benchmark.md) | Benchmark 环境变量与参数选择建议 |

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
