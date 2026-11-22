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

## 安全边界与生产实践

token 随 Provider 的服务 URL 注册到注册中心，Consumer 订阅时自动获得。这意味着能读到注册中心元数据的人就能拿到 token，所以该机制的安全前提是注册中心自身有访问控制：它防的是绕过注册中心直连 Provider 的未授权调用方。

生产上 Nacos 一般会开启鉴权（`nacos.core.auth.enabled=true`），只有持有合法凭据的客户端才能订阅服务、拿到 token。两者配合形成完整的安全效果：

- **注册中心鉴权**：管"谁能发现服务、获取 token"；
- **服务 token**：管"谁能调用服务"，即使攻击者知道了 Provider 的 IP 和端口，没有 token 也无法调用。

## 兼容性

未配置 token 时自动跳过校验，完全向后兼容。
