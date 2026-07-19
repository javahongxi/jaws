# 可观测性

引入 `jaws-observability-spring-boot-starter` 即可获得 Micrometer 指标采集和 OpenTelemetry 链路追踪能力，无需额外代码。

## Maven 依赖

```xml
<dependency>
    <groupId>org.hongxi</groupId>
    <artifactId>jaws-observability-spring-boot-starter</artifactId>
    <version>${jaws.version}</version>
</dependency>
```

## 功能说明

### 链路追踪

基于 Micrometer Tracing + OpenTelemetry，通过 `Propagator` 自动在 consumer/provider 间传播 trace context（W3C TraceContext 格式），确保全链路 traceId 一致。

### 指标采集

自动统计 RPC 调用次数、成功率、耗时分布、活跃请求数等指标，通过 `side` tag 区分 consumer/provider 视角。

### 日志关联

traceId/spanId 由 OTel 日志桥接层自动注入 MDC，日志格式 `{traceId}-{spanId}`。
