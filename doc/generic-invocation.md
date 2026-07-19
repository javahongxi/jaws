# 泛化调用

无需依赖 Provider 的接口 JAR 包，通过 `GenericService.$invoke()` 即可发起 RPC 调用，适用于网关、测试平台等场景。

## 使用方式

Spring Boot 注解方式：

```java
@JawsReference(generic = true, serviceInterface = "org.hongxi.jaws.sample.api.DemoService")
private GenericService demoService;
```

调用示例：

```java
Object result = demoService.$invoke(
    "hello",
    new String[]{"java.lang.String"},
    new Object[]{"jaws"}
);
```

完整示例请参考 `jaws-sample-consumer` 中的 `GenericSampleConsumer`。
