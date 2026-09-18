# 测试套件整理说明

## 审查结论

本轮逐一审查了 `src/test` 下的测试。没有发现已经失效、完全重复或只针对已删除实现的测试，因此没有删除任何测试文件。相近测试分别处于单元、Web 安全、数据库集成和真实 Redis 集成层，不能互相替代。

`AutoReservationV2IntegrationTest` 已重命名为 `AutoReservationIntegrationTest`。测试内容不变，仅移除实现阶段名称，便于后续长期维护。

## 测试分组

| 分组 | 主要测试 | 目的 |
|---|---|---|
| 应用与基础设施 | `ReservationApplicationTests`、`ContainerIntegrationTestSupport` | 验证 Spring 上下文、PostgreSQL 和 Redis 基线 |
| 数据库迁移 | `V1ToV2MigrationIntegrationTest` | 验证带历史数据的数据库可升级，并执行最新演示数据变更集 |
| Repository | `ResourceRepositoryTest`、`TimeSlotRepositoryTest`、`ReservationRepositoryTest` | 验证外键、约束、锁定查询和生命周期查询 |
| 资源与时段查询 | `ResourceQueryServiceIntegrationTest`、`SlotQueryServiceIntegrationTest` | 验证过滤、分页、可见性与 PostgreSQL 查询行为 |
| 预约创建 | `ManualReservationIntegrationTest`、`AutoReservationIntegrationTest` | 验证手工预约和自动预约 |
| 并发控制 | `ReservationConcurrencyIntegrationTest`、`ReservationLifecycleConcurrencyIntegrationTest` | 验证时段竞争、用户重叠预约和生命周期并发 |
| 生命周期 | `UserReservationLifecycleIntegrationTest`、`ReservationExpiryServiceTest` | 验证查询、取消、完成及历史保留 |
| Redis 队列 | `ReservationQueueServiceTest`、`ReservationQueueServiceRedisIntegrationTest` | 验证入队、去重、重试、DLQ、状态和 stale recovery |
| 安全 | `SecurityIntegrationTest`、`JwtUtilTest`、`PasswordConfigTest` | 验证 JWT、RBAC、匿名登录与配置边界 |
| Web 与文档 | `ReservationControllerTest`、`OpenApiDocumentationIntegrationTest` | 验证控制器分支和 Swagger 路径/认证声明 |
| 横切能力 | `RateLimitFilterTest`、`RequestCorrelationFilterTest`、`AuditorAwareImplTest`、`ReservationQueueHealthIndicatorTest` | 验证限流、请求追踪、审计和健康状态 |
| 时间语义 | `TimeSemanticsIntegrationTest` | 验证本地业务时间和绝对生命周期时间的一致性 |

## 打包边界

Maven 的测试源码位于标准 `src/test` 目录，编译结果进入 `target/test-classes`，不会进入 Spring Boot 可执行 JAR。最终验收仍需执行：

```text
mvn clean package
```

并检查生成的 JAR 不包含 `*Test.class`、`*IntegrationTest.class` 或 `src/test` 内容。
