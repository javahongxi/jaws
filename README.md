# Jaws Framework 🦈

![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)
[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://adoptium.net/zh-CN/temurin/releases)
![CI](https://github.com/javahongxi/jaws/actions/workflows/ci.yml/badge.svg)

> 取名自《大白鲨》(*Jaws*)——**J**ava **A**sync **W**ire **S**ervice：Java 生态、异步调用、线级协议、服务治理，四个词正是一个 RPC 框架的四层解剖。

Jaws 是一个**核心不到 2 万行、可以从头读到尾**的轻量级 RPC 框架。它用不到 Dubbo 十分之一的代码量，
完整实现了一个工业级 RPC 的核心机制：三传输(Netty TCP 全链路零拷贝 + 自研 HTTP/2 消除队头阻塞 + 
gRPC 线格式跨语言互通)、 Server Streaming、自适应负载均衡与高可用容错，实测 10 万 QPS、150+ 个测试全绿。 
目标是成为 **RPC 骨架的标杆**——读完 Jaws 源码，再去读 Dubbo 会快十倍。

## 特性

- **自定义协议** — 基于 Netty 的 jaws 二进制协议，编解码全链路零拷贝（零额外 byte[] 分配）
- **HTTP/2 传输** — 基于 Netty 自研 HTTP/2 传输，支持 Server Streaming 流式调用
- **gRPC 线格式** — `jaws-wire` 模块支持标准 gRPC 线格式与 protobuf 序列化，兼容 grpcurl 等标准工具
- **多种序列化** — 内置 fastjson2 / hessian2 / protostuff，消费端指定序列化方式，协议头携带序列化标识
- **连接心跳** — 定期互发心跳保持连接存活，防止长时间空闲的连接被中间设备断开
- **服务注册与发现** — ZooKeeper / Nacos 注册中心，支持心跳续约与失败重连
- **多种负载均衡** — random、roundRobin、leastActive、shortestResponse、adaptive、consistentHash
- **高可用容错** — failover（失败切换）、failfast（快速失败）、failback（异步重试）
- **路由链 / Router** — 可扩展的调用时路由过滤链，内置标签路由（灰度发布）与动态配置路由
- **SPI 扩展** — 所有核心组件（Protocol、Cluster、LoadBalance、Filter、Serialization 等）均通过 SPI 可插拔
- **连接预热 / Warm-up** — 新启动的 Provider 权重随时间线性增长，避免冷启动被打爆
- **优雅停机** — 四阶段停机（停止接收 → 等待在途请求 → 注销注册中心 → 关闭连接），零损伤发布
- **可观测性** — 可选 Micrometer 指标采集和 OpenTelemetry 链路追踪，通过 Filter SPI 自动生效
- **动态配置** — 支持全局/服务级/方法级三层热更新（超时、重试、路由规则、Filter 开关等）
- **泛化调用** — 无需依赖接口 JAR 包即可发起 RPC 调用，适用于网关、测试平台等场景
- **MCP 桥接** — 将 Jaws RPC 服务自动暴露为 MCP Tools，AI Agent 可直接调用后端服务

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
# 一键运行（启动 provider → 运行 consumer → 停止 provider）
./run-sample.sh netty              # Netty 直连（无需注册中心）
./run-sample.sh http2              # HTTP/2 直连（无需注册中心）
./run-sample.sh stream             # HTTP/2 流式 Server Streaming（无需注册中心）
./run-sample.sh run                # ZooKeeper 注册中心（需要 ZK 在 127.0.0.1:2181）
./run-sample.sh nacos              # Nacos 注册中心（需要 Nacos 在 127.0.0.1:8848）

# injvm 协议示例（进程内直调，不走网络）
./run-sample.sh injvm

# 分步运行
./run-sample.sh provider           # 前台启动 provider（需要 ZK 在 127.0.0.1:2181 运行）
./run-sample.sh provider 10001     # 指定端口
./run-sample.sh provider-bg        # 后台启动
./run-sample.sh provider-bg -1     # 后台启动，自动分配端口
./run-sample.sh consumer           # 运行 consumer（需要先启动 provider）
./run-sample.sh stop               # 停止所有后台 provider 并清理

# 性能测试（8 核实测 10 万 QPS，详见 doc/benchmark.md）
THREADS=20 WARMUP=10 DURATION=40 ./run-sample.sh bench-jaws
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
    name: sample-boot-provider

jaws:
  application:
    name: ${spring.application.name}
  protocol:
    name: jaws
    port: 10000
    serialization: fastjson2
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

| 主题                                       | 说明                                     |
|--------------------------------------------|------------------------------------------|
| [泛化调用](doc/generic-invocation.md)      | 无需接口 JAR 包即可发起 RPC 调用         |
| [优雅停机](doc/graceful-shutdown.md)       | 四阶段零损伤发布                         |
| [服务鉴权](doc/token-auth.md)              | 基于 Token 的服务认证                    |
| [连接预热](doc/warm-up.md)                 | Provider 冷启动权重渐增                  |
| [可观测性](doc/observability.md)           | Micrometer 指标 + OpenTelemetry 链路追踪 |
| [动态配置](doc/dynamic-config.md)          | 全局/服务级/方法级三层热更新             |
| [MCP 桥接](doc/mcp-bridge.md)              | 将 RPC 服务自动暴露为 MCP Tools          |
| [与 Dubbo 对比](doc/dubbo-comparison.md)   | 八个维度的系统性能力对比                 |
| [注册中心对比](doc/registry-comparison.md) | Nacos 与 ZooKeeper 实现对比              |
| [编解码设计](doc/codec-comparison.md)      | Jaws 与 Dubbo 编解码架构对比分析         |
| [性能测试](doc/benchmark.md)               | Benchmark 环境变量与参数选择建议         |


&copy; [hongxi.org](http://hongxi.org)
