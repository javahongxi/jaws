# Jaws Framework

Jaws 是一个基于 Java 17 和 Netty 的高性能 RPC 框架，提供服务注册与发现、负载均衡、容错等完整的微服务通信能力。

## 特性

- **自定义协议** — 基于 Netty 的 jaws 二进制协议，支持 fastjson2 / hessian2 序列化
- **injvm 协议** — JVM 内部直调，零网络开销，适合本地开发与测试
- **服务注册与发现** — ZooKeeper 注册中心，支持心跳续约与失败重连
- **多种负载均衡** — random、roundRobin、leastActive、shortestResponse、consistentHash
- **高可用容错** — failover（失败切换）、failfast（快速失败）
- **SPI 扩展** — 所有核心组件（Protocol、Cluster、LoadBalance、Filter、Serialization 等）均通过 SPI 可插拔
- **方法级别配置** — 可为单个方法设置独立的超时、重试策略
- **RpcContext** — 消费端可获取实际调用的服务端地址，提供端可获取调用方 IP
- **动态端口** — 端口设为 -1 时自动从 10000 递增分配，避免冲突

## 模块

```
jaws-parent
├── jaws-core                  # 核心：协议抽象、SPI、序列化、集群、Filter、配置
├── jaws-transport-netty       # Netty 传输层实现
├── jaws-registry-zookeeper    # ZooKeeper 注册中心实现
└── jaws-samples
    ├── jaws-sample-api        # 服务接口定义（DemoService、OrderService）
    ├── jaws-sample-injvm      # injvm 协议示例（无需 ZK）
    ├── jaws-sample-provider   # 服务提供者（jaws + ZooKeeper）
    ├── jaws-sample-consumer   # 服务消费者
    └── jaws-sample-benchmark  # 性能基准测试
```

## 快速开始

### 环境要求

- Java 17+
- Maven 3.8+（或使用内置 `./mvnw`）
- ZooKeeper 3.9+（仅 jaws 协议示例需要）

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

# 启动服务提供者（需要 ZK 在 127.0.0.1:2181 运行）
./run-sample.sh provider           # 前台启动，默认端口 10000
./run-sample.sh provider 10001     # 前台启动，指定端口
./run-sample.sh provider-bg        # 后台启动
./run-sample.sh provider-bg -1     # 后台启动，自动分配端口

# 启动服务消费者（需要先启动 provider）
./run-sample.sh consumer

# 停止所有后台 provider 并清理
./run-sample.sh stop
```

### 性能测试

```bash
# injvm 协议基准（框架纯开销）
./run-sample.sh bench-injvm

# jaws + Netty 网络传输基准
./run-sample.sh bench-jaws

# 自定义参数
THREADS=8 WARMUP=5 DURATION=20 ./run-sample.sh bench-jaws
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

### 发布服务（Provider）

```java
ServiceConfig<DemoService> serviceConfig = new ServiceConfig<>();
serviceConfig.setRef(new DemoServiceImpl());
serviceConfig.setApplication("my-provider");
serviceConfig.setInterface(DemoService.class);
serviceConfig.setGroup("test");
serviceConfig.setVersion("1.0");
serviceConfig.setProtocol(protocolConfig);
serviceConfig.setRegistry(registryConfig);
serviceConfig.setExport("jaws:10000");
serviceConfig.export();
```

### 引用服务（Consumer）

```java
ReferenceConfig<DemoService> ref = new ReferenceConfig<>();
ref.setInterface(DemoService.class);
ref.setApplication("my-consumer");
ref.setGroup("test");
ref.setVersion("1.0");
ref.setProtocol(protocolConfig);
ref.setRegistry(registryConfig);

DemoService demoService = ref.getRef();
String result = demoService.hello("jaws");

// 获取实际调用的服务端地址
URL serverUrl = RpcContext.getContext().getServerUrl();
System.out.println("server => " + serverUrl.getHost() + ":" + serverUrl.getPort());
```

## 技术栈

| 组件   | 技术                       | 版本             |
|------|--------------------------|----------------|
| 语言   | Java                     | 17             |
| 网络   | Netty                    | 4.1.132        |
| 注册中心 | ZooKeeper + Curator      | 3.9 / 5.9      |
| 序列化  | fastjson2 / hessian-lite | 2.0.62 / 4.0.5 |
| 工具   | Guava                    | 33.6           |
| 日志   | slf4j                    | 2.0.17         |


&copy; [hongxi.org](http://hongxi.org)
