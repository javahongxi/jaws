# 服务鉴权 / Token

Provider 配置 token 后，Consumer 自动从注册中心获取并携带，无需额外代码。基于 Filter 自动生效，防止未授权调用。

## 使用方式

**注解方式（Provider 端）：**

```java
@JawsService(token = "my-secret-token")
public class DemoServiceImpl implements DemoService { }
```

**编程式（Provider 端）：**

```java
serviceConfig.setToken("my-secret-token");
```

**YAML 全局配置：**

```yaml
jaws:
  service:
    token: my-secret-token
```

## 兼容性

未配置 token 时自动跳过校验，完全向后兼容。
