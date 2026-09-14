# Reservation 项目问题整理与改进计划（审查补充版）

> 原始材料：`E:\Google\reservation_project_improvement_plan.md`  
> 对照代码：当前 `lab-resource-reservation` 工作区  
> 审查日期：2026-09-14  
> 本文将原计划视为待审查资料，不把其中建议当成需要立即执行的代码修改指令。

## 1. 审查结论

原计划的总体判断是正确的，已经覆盖以下重要主题：

- 登录失败审计不完整；
- JWT Secret 硬编码；
- 同一用户并发重复预约；
- 同类内部调用导致事务注解失效；
- Redis `KEYS` 阻塞风险；
- 预约过期规则定义错误；
- PostgreSQL 与 Redis 缓存一致性；
- 日志泄露密码、令牌等敏感数据；
- 单实例负载计数；
- 定时任务多实例重复执行；
- Redis List 没有 ACK，消息可靠性不足。

这些内容抓住了项目的主要架构风险，但清单仍然不够完整，尤其遗漏了数个会导致系统**当前就无法按设计运行**的问题。因此，原文更适合看作第一轮风险梳理，不能直接作为完整整改清单。

本次补充将问题分为：

- **P0：运行或数据正确性阻断项**；
- **P1：安全与并发正确性**；
- **P2：可靠性、可观测性与接口一致性**；
- **P3：领域建模和长期演进**。

## 2. 原计划需要修正或进一步明确的地方

### 2.1 “使用 Micrometer/Prometheus 解决多实例负载判断”不够准确

Micrometer 和 Prometheus 主要负责观测，不适合直接放在同步请求路径中作为实时分流决策源。Prometheus 抓取存在周期和延迟，不能保证请求到达时得到精确一致的全局活动数。

更合理的选择是：

- 优先依赖 Web 线程池、连接池、队列长度等自然背压；
- 使用网关或负载均衡器进行限流；
- 必须维护全局计数时使用具有原子操作和过期补偿的 Redis；
- Micrometer/Prometheus 用于观察阈值是否合理，而不是直接控制每个请求。

### 2.2 “为所有 Scheduled 任务加分布式锁”需要分类

过期清理任务确实需要避免多实例重复执行，可以使用 ShedLock 或数据库抢占。

但队列消费不一定应该用“一次只允许一个实例”的全局锁。这样会主动失去横向扩展能力。更适合的方案是：

- Redis Streams Consumer Group；
- RabbitMQ competing consumers；
- Kafka consumer group；
- 或可靠队列的 processing list + ACK/恢复机制。

多个实例可以并行消费，但同一条消息只能由一个消费者持有并确认。

### 2.3 缓存一致性不能只写“延迟双删”

延迟双删只是特定场景的折中方案，并不能天然解决所有一致性问题。此项目缓存的是“最近可用时段”，它直接参与预约候选选择，比普通详情缓存更敏感。

应先判断这个缓存是否真的值得保留：数据库查询如果有正确索引，并使用 `LIMIT 1 / SKIP LOCKED`，可能已经足够快。删除该缓存反而能显著降低并发复杂度。如果保留，应至少做到事务提交后失效，并允许数据库约束成为最终正确性防线。

### 2.4 用户重复预约不能简单使用普通唯一约束

业务约束是“同一用户只能有一个活跃/未来预约”，而不是“一个用户终生只能有一条预约”。普通 `UNIQUE(user_id)` 会阻止历史预约。

更适合的模型是：

- 增加明确的预约状态；
- PostgreSQL 使用针对活跃状态的部分唯一索引；
- 或维护单独的 active reservation 表；
- 同时在事务内加用户级锁，数据库约束负责最终兜底。

## 3. P0：原计划遗漏的运行与数据正确性问题

### P0-1：定时任务没有启用，队列消费者和清理任务不会运行

#### 证据

项目中存在三个 `@Scheduled` 方法：

- `ReservationQueueService.processReservationQueue()`；
- `ReservationExpiryService.processExpiredReservations()`；
- `RedisCleanupService.cleanupOldStatusKeys()`。

但应用入口 `ReservationApplication` 没有 `@EnableScheduling`，其他配置类也没有开启调度。

#### 影响

- 高负载请求进入 Redis 后会长期停留在 `QUEUED`；
- 预约不会自动过期；
- 历史 Redis 状态键不会被补设 TTL；
- README 描述的异步处理实际上无法成立。

#### 改进

- 在应用入口或独立调度配置类添加 `@EnableScheduling`；
- 编写集成测试验证三个任务确实被注册；
- 不要只验证方法可以手动调用，还要验证调度器会触发。

#### 验收标准

- 入队请求能在设定轮询周期内从 `QUEUED` 进入终态；
- Actuator 或测试能确认 scheduled task 已注册；
- 多实例策略明确后再部署多个副本。

---

### P0-2：Spring Retry 没有启用，`@Retryable` 和 `@Recover` 不会按设计工作

#### 证据

`ReservationService.reserveNearestSlot()` 使用了 `@Retryable`，并定义了 `@Recover`，但项目没有 `@EnableRetry`。

#### 影响

- 乐观锁冲突不会自动重试三次；
- `recoverFromOptimisticLockingFailure()` 不会在重试耗尽后执行；
- README 所称的重试能力与实际运行行为不一致。

#### 改进

- 添加 `@EnableRetry`；
- 明确 Retry AOP 与 Transaction AOP 的顺序；
- 使用真实并发集成测试验证每次重试是否获得新事务和新实体状态；
- 只重试暂时性异常，不要重试确定性的业务错误。

#### 验收标准

- 人为制造乐观锁冲突后可以观察到预期重试次数；
- 重试耗尽后返回统一的容量繁忙错误；
- 不会对重复预约、用户不存在等错误重复执行。

---

### P0-3：当前队列重试代码会覆盖或删除下一条用户消息

原计划只写了“重试可靠性不足”，但当前问题更具体、更严重。

#### 当前流程

`dequeueQueueItem()` 首先执行：

```java
leftPop(QUEUE_KEY)
```

这一步已经删除当前消息。处理失败后，代码却执行：

```java
redisTemplate.opsForList().set(QUEUE_KEY, 0, updatedJson);
```

此时索引 0 是**下一条消息**，因此当前失败消息会覆盖下一位用户的请求。

达到最大重试次数或重新序列化失败时，代码还会再次执行：

```java
leftPop(QUEUE_KEY)
```

这会继续误删下一条消息。

#### 影响

- 请求无声丢失；
- 用户 A 的失败消息可能覆盖用户 B；
- 状态键与队列内容失去对应；
- 邮箱去重集合可能残留错误状态；
- 指标无法反映真实丢失量。

#### 改进

优先改用 Redis Streams Consumer Group。若暂时保留 List，至少需要：

- 原子地将消息从 pending 移动到 processing；
- 成功后 ACK/删除 processing 消息；
- 失败时把**同一条消息**重新入队；
- 消费者崩溃后扫描并恢复超时 processing 消息；
- 移除所有对下一条队首消息的覆盖或额外 `leftPop`。

#### 验收标准

- 准备 A、B、C 三条消息，让 A 连续失败，B/C 仍完整且只处理一次；
- 消费者在取出消息后崩溃，重启后消息能恢复；
- 每条消息的 requestId、邮箱和状态始终一一对应。

---

### P0-4：队列消息可能无法被 Jackson 反序列化

#### 证据

内部类 `QueueItem`：

- 是 `private static class`；
- 只有一个带三个参数的构造方法；
- 没有无参构造方法；
- 没有 `@JsonCreator` / `@JsonProperty` 构造参数声明。

在没有可靠构造器元数据时，Jackson 通常无法创建该对象。

#### 影响

`leftPop` 已经删除消息后，如果 `readValue` 失败，方法只记录日志并返回 null，消息会永久丢失。

#### 改进

- 将消息模型改为可公开反序列化的 DTO 或 Java record；
- 或增加无参构造方法；
- 或显式添加 `@JsonCreator` 和 `@JsonProperty`；
- 反序列化失败的原始消息必须进入专门的 malformed DLQ，不能直接丢弃。

#### 验收标准

- 对真实 Redis 序列化结果做往返测试：对象 → JSON → Redis → JSON → 对象；
- 缺字段、未知字段、旧版本消息都有明确兼容策略；
- 非法消息可以在 DLQ 中找到原始内容和失败原因。

---

### P0-5：Liquibase 变更集需要保持不可变并进行迁移验证

#### 证据

原文件中的两个 ID 分别是：

```text
1-create-initial-tables
1-insert-initial-data
```

它们都以 `1-` 开头，但完整 ID 不同，因此**不构成重复标识**。此前把它们判断为重复是不准确的。

真正需要注意的是：这些 changeSet 可能已经被某些数据库执行。直接修改 ID 或 SQL 会导致旧数据库把它当成新迁移重复执行，或者触发 checksum 校验问题。

#### 影响

如果直接重写旧变更集，已有数据库可能迁移失败或重复插入数据；如果完全不验证，空数据库与升级数据库的行为也可能不一致。

#### 改进

- 保留已经存在的 changeSet ID 和内容；
- 需要修正演示数据时新增后续 changeSet，并通过 `context="dev"` 与测试/生产隔离；
- 用全新数据库执行一次完整迁移；
- 用已有数据库执行升级验证，避免随意修改已执行 changeSet。

#### 验收标准

- `liquibase validate` 和 checksum 校验通过；
- 空数据库可一次启动成功；
- 已迁移数据库升级不会出现 checksum 或重复标识错误。

---

### P0-6：初始化数据无法支撑当前时间下的基本演示

#### 证据

- 初始用户密码是 `hashed_password_125` 等占位文本，不是 BCrypt 哈希；
- 初始可用时段全部位于 2025-10-01；
- 当前日期为 2026-09-14，这些时段全部已过去。

#### 影响

- 初始化用户无法通过 BCrypt 登录；
- “预约最近未来时段”永远找不到候选项；
- 新开发者会误判应用或核心业务代码损坏。

#### 改进

- 开发环境使用合法 BCrypt 演示密码，并明确仅用于开发；
- 动态生成相对当前时间的测试时段，或提供开发数据脚本；
- 生产迁移与演示种子数据分开管理，使用 Liquibase context/profile。

#### 验收标准

- 全新开发数据库启动后，可以完成登录、预约、查询/取消的最小闭环；
- 生产 Profile 不插入演示账号和演示时段。

---

### P0-7：预约过期调度单位与属性名称不一致

#### 证据

代码为：

```java
@Scheduled(fixedDelayString = "${reservation.expiry.check-minutes:15}000")
```

配置名声称单位是“分钟”，但字符串拼接 `000` 后被 Spring 按毫秒解释：

- 默认 `15` 变成 `15000ms`，即 15 秒；
- 配置 `1` 变成 `1000ms`，即 1 秒。

#### 影响

启用调度后，清理任务会比设计频繁 60 倍，增加数据库查询、锁和日志压力。

#### 改进

使用明确的 Duration 配置，例如：

```java
@Scheduled(fixedDelayString = "${reservation.expiry.check-interval:PT15M}")
```

或使用 SpEL 做分钟到毫秒的明确换算，但 Duration 可读性更好。

#### 验收标准

- 默认执行间隔实际为 15 分钟；
- 自动化测试验证配置值对应的时间单位；
- 属性名、注释和运行行为保持一致。

## 4. P1：安全和权限方面的遗漏

### P1-1：核心预约接口全部允许匿名访问

#### 证据

`SecurityConfig` 将：

```text
/api/v1/reservations/**
```

整体配置为 `permitAll()`。

#### 影响

- 任何人都可以用任意已知邮箱创建预约；
- 任何人知道预约 ID 后都可以取消；
- 状态查询没有用户隔离；
- JWT 登录没有真正保护核心业务。

#### 改进

- 只允许 `/api/auth/login`、必要的 Swagger/健康端点匿名；
- 创建预约从认证身份读取邮箱，不信任请求体中的邮箱；
- 取消和查询必须验证预约/请求属于当前用户；
- 管理员操作单独定义角色权限。

#### 验收标准

- 未登录调用创建、状态、取消均被拒绝；
- 用户 A 无法查询或取消用户 B 的请求/预约；
- 管理员权限有明确测试。

---

### P1-2：登录接口没有专用防暴力破解措施

当前限流设计只打算匹配预约路径，登录接口没有：

- 按 IP/账号限速；
- 连续失败退避；
- 临时锁定或验证码策略；
- 登录失败指标和告警闭环。

建议对登录单独限流，且不要让所有用户共享单个全局 Bucket。错误响应避免泄露“账号存在但密码错误”之类的枚举信息。

---

### P1-3：JWT 除硬编码外还缺少密钥与令牌治理

原计划已经指出 Secret 硬编码，还应补充：

- 使用显式 UTF-8 获取密钥字节，避免依赖平台默认字符集；
- 设置 issuer、audience，并在验证时检查；
- 设计密钥版本与轮换窗口；
- 明确注销、强制失效和用户被禁用后的处理；
- 避免在日志中记录完整 token；
- 对系统时钟偏差设置合理容忍范围。

当前 `JwtFilter` 会再次查询数据库，因此已删除用户不能继续建立身份，这是积极的一点；但系统仍没有用户状态字段和令牌撤销机制。

---

### P1-4：审计人解析可能把匿名用户记录为真实操作人

`AuditorAwareImpl` 只检查：

```java
authentication == null || !authentication.isAuthenticated()
```

Spring Security 的 `AnonymousAuthenticationToken` 也可能返回 authenticated=true，其名称通常是 `anonymousUser`。

建议显式排除匿名认证，并对后台任务、匿名用户、真实用户分别定义审计标识。

此外，`AuditorAwareImpl` 同时通过 `@Component` 和 `JpaAuditingConfig.@Bean` 注册，容器中可能存在两个同类型 Bean。虽然 `auditorAwareRef` 指定了名称，不一定立即报错，但属于冗余配置，应统一为一种注册方式。

---

### P1-5：异常响应和状态字符串可能泄露内部信息

`GlobalExceptionHandler` 把 `ex.getMessage()` 放进 `ApiError.error`；队列失败状态也拼接异常消息。数据库、Redis、序列化或实现细节可能经 API 暴露。

建议区分：

- 对外稳定错误码和安全消息；
- 内部日志中的完整异常和 traceId；
- 可观测系统中的错误类型标签。

不要把内部异常文本作为长期 API 契约。

---

### P1-6：数据库与 Docker 中存在明文凭据

除了 JWT Secret，`application.yml`、`application-dev.yml` 和 `docker-compose.yml` 还包含数据库明文密码。

开发环境可以提供 `.env.example`，但真实值应通过环境变量、Docker Secret、Kubernetes Secret 或 Secret Manager 注入，并确保不会提交生产凭据。

## 5. P1：并发和事务方面的进一步遗漏

### P1-7：选取最近时段会查询并锁住全部候选行

`TimeSlotRepository.findAvailableSlots()` 返回所有未来空闲时段，并带有 `PESSIMISTIC_WRITE`，随后 Java 只取第一条。

这可能导致：

- 加载大量无用数据；
- 锁定比实际需要更多的时段；
- 并发请求相互等待；
- 高并发吞吐量显著下降。

建议在数据库层只选一条。PostgreSQL 高并发抢占常见方案是：

```sql
ORDER BY start_time
FOR UPDATE SKIP LOCKED
LIMIT 1
```

需要通过 native query、合适事务边界和集成测试验证。

---

### P1-8：缓存命中会绕过带悲观锁的候选查询

第一次未命中缓存时，Repository 查询可能取得悲观锁；后续缓存命中直接返回 `AvailableSlot`，不会再次执行该锁查询。之后虽然 `findById` 会重新查库，但没有声明悲观锁。

当前系统混用了：

- 悲观锁；
- `@Version` 乐观锁；
- Redis 缓存；
- 数据库唯一约束。

机制越多不代表越安全，反而需要明确哪一层是最终保证。建议简化为一条可证明的并发策略，并用真实 PostgreSQL 并发测试验证。

---

### P1-9：队列邮箱去重不是原子操作，且可能形成永久脏标记

当前逻辑先：

```text
SISMEMBER email
```

再执行：

```text
RPUSH message
SET status
SADD email
```

问题包括：

- 两个并发请求可能同时发现邮箱不存在，然后都入队；
- 主队列写入成功但状态或 SADD 失败，会留下部分状态；
- `reservation:emails:queued` 没有 TTL；
- 进程崩溃或消息丢失后，邮箱可能永久不能再次入队。

建议使用 `SADD` 的返回值做原子抢占，并用 Lua、Redis Transaction 或 Streams 将消息、状态和去重标记组织成可补偿操作。还要提供孤儿标记清理机制。

---

### P1-10：当前“幂等性”只检查状态字符串，不足以保证只处理一次

`isAlreadyProcessed(requestId)` 只在状态为 `SUCCESS` 时跳过。

不足之处：

- 状态键有 TTL，过期后失去幂等记录；
- `PROCESSING` 消息被重复投递时仍可能再次执行；
- 数据库写成功、Redis 状态更新失败时会再次处理；
- direct 请求没有同一套幂等键；
- 用户防重复查询本身存在并发漏洞。

最终幂等性应落到数据库唯一约束/幂等记录上，Redis 状态只用于加速与展示。

---

### P1-11：同步与异步路径返回语义不统一

同步请求返回：

```text
requestId = direct-{reservationId}
status = SUCCESS
```

但状态查询只访问 Redis，所以拿 `direct-*` ID 查询会得到 404。异步成功状态又不返回最终 reservationId 和分配到的时段。

建议统一“命令请求”和“预约结果”模型：

- 所有创建请求都生成统一 requestId；
- 状态查询能返回 reservationId、分配时段、失败错误码；
- 同步处理也写入同一结果存储，或直接返回完整预约资源并明确无需轮询；
- API 文档说明 200 与 202 的区别。

## 6. P2：部署、接口和可观测性遗漏

### P2-1：Docker Compose 健康检查有多处不一致

#### PostgreSQL 用户不一致

容器创建用户是 `azki`，健康检查却执行：

```text
pg_isready -U reservation_user -d reservationdb
```

应使用实际配置用户，或通过变量统一引用。

#### Actuator 端口不一致

应用配置：

```text
management.server.port = 8081
```

应用容器健康检查却访问：

```text
http://localhost:8080/actuator/health
```

Compose 也没有映射 8081。容器内部健康检查不要求映射到宿主机，但必须访问正确的容器端口。

#### curl 可能不存在

运行镜像是 Alpine JRE，不能假设自带 `curl`。如果工具不存在，应用即使健康，健康检查也会失败。

#### 验收标准

- `docker compose up` 后三个服务都进入 healthy；
- 健康检查命令使用镜像中确定存在的工具；
- 应用健康检查确实覆盖 Redis、PostgreSQL 和自定义队列状态。

---

### P2-2：README、Postman、Controller 路径已经漂移

Controller 当前路径是：

```text
/api/v1/reservations/reserve
/api/v1/reservations/status/{requestId}
/api/v1/reservations/cancel/{id}
```

Postman 集合仍使用 `/api/reservations` 等旧路径；监控请求也访问 8080，而 Actuator 位于 8081。

建议从 OpenAPI 自动生成客户端/测试集合，或至少在 CI 中执行契约测试，避免文档、示例和实现长期分叉。

---

### P2-3：配置已注册 Timer，但没有记录任何耗时

`MetricsConfig` 创建：

- `reservation.processing.time`；
- `reservation.slot.selection.time`。

业务代码没有注入或调用这两个 Timer，因此指标不会提供有意义的数据。应真正包裹业务逻辑，或者删除这些空指标，避免监控看板给出虚假的“已覆盖”感觉。

---

### P2-4：自定义健康状态 `WARNING` 需要定义聚合和 HTTP 行为

`ReservationQueueHealthIndicator` 返回自定义 `WARNING`。需要明确：

- Actuator 总体状态如何排序；
- `WARNING` 对应哪个 HTTP 状态码；
- Kubernetes readiness/liveness 是否应因此摘流；
- 队列积压是“存活失败”还是“就绪降级”。

通常队列积压更适合作为指标和 readiness 判断，不应轻易让 liveness 重启仍然正常工作的进程。

---

### P2-5：健康阈值、连接池和限流参数硬编码

当前硬编码内容包括：

- Redis 连接池 10/5/1；
- Redis 命令超时 2 秒；
- 队列 WARNING/DOWN 阈值；
- DLQ 阈值；
- 限流容量和补充速率；
- JWT 有效期。

这些参数应使用类型安全的 `@ConfigurationProperties`，分环境配置，并增加合法范围校验。

---

### P2-6：测试代码与当前实现明显脱节

当前测试存在以下问题：

- `ReservationController` 已增加 `LoadMonitoringService` 依赖，Controller 测试没有 Mock；
- Controller 返回 `ReservationResponseDto`，测试仍与字符串直接比较；
- `ReservationService` 已通过 `CacheableOperations` 查询，测试仍 Mock `TimeSlotRepository.findNextAvailable()`；
- Queue 测试构造器要求 JUnit 注入普通对象，`RedisCleanupService` 也没有正确 Mock；
- Repository 测试依赖本机真实 PostgreSQL，没有 H2 或 Testcontainers 隔离；
- 测试 Profile 同时开启 Liquibase 和 `ddl-auto=create-drop`，建表职责重叠；
- 缺少认证、安全规则、限流、定时任务、缓存一致性和真实并发测试。

在修复生产代码前，应先建立能稳定执行的测试基线，否则每次整改都无法确认是否引入回归。

#### 推荐测试层次

1. 纯单元测试：业务分支、异常映射；
2. `@WebMvcTest`：认证、状态码、所有权；
3. PostgreSQL Testcontainers：锁、唯一约束、Liquibase；
4. Redis Testcontainers：序列化、重试、崩溃恢复、幂等；
5. Docker Compose 冒烟测试：从登录到预约/取消完整闭环；
6. 并发压测：同一用户和同一时段的竞争。

---

### P2-7：日志切面会重复记录并可能放大日志量

`LoggingAspect` 同时使用 `@Before`、`@AfterReturning` 和 `@Around` 覆盖相同方法。DEBUG/TRACE 下，一次调用可能产生多组进入/退出日志；数据库又有单独切面，事务方法还会再次被记录。

除了原计划提到的敏感字段泄漏，还应：

- 合并重复日志通知；
- 加入 requestId/traceId；
- 避免把完整实体和大型集合写入日志；
- 控制高频队列轮询日志；
- 对邮箱等个人信息定义脱敏和保留策略。

---

### P2-8：数据库缺少关键约束和索引设计

建议补充检查：

- `end_time > start_time` 的 CHECK 约束；
- 防止同一资源下时段重叠；
- `available_slot(is_reserved, start_time)` 查询索引；
- `reservation(user_id)` 索引；
- 活跃预约的部分唯一索引；
- 时段与资源关联后的复合索引。

仅依赖 Java 校验无法阻止其他服务、脚本或并发事务写入非法数据。

---

### P2-9：时间类型和时区策略没有定义

项目大量使用 `LocalDateTime` 和服务器默认时区，JWT 过期时间也转换成 `ZoneId.systemDefault()`。

多地区或容器部署时，应明确：

- 数据库存 UTC 还是本地时间；
- API 是否使用 ISO-8601 带 offset 的格式；
- 实验室所属时区如何处理；
- 夏令时重叠/跳跃时段如何预约；
- 测试如何固定 Clock，避免依赖系统当前时间。

推荐业务时间使用明确 ZoneId，持久化时间点优先考虑 `Instant`/`OffsetDateTime`，并通过可注入 `Clock` 提升可测试性。

## 7. P3：业务模型和长期演进遗漏

### P3-1：项目名称是实验室资源预约，但没有资源实体

当前只有：

- User；
- AvailableSlot；
- Reservation。

没有 Lab、Room、Equipment、Resource、ResourceType 等实体，所有时段相当于属于一个全局资源池。

如果目标真的是实验室资源预约，应在稳定化后增加：

- 资源及资源类型；
- 资源所属实验室/地点；
- 可预约规则和开放时间；
- 时段容量；
- 维护、停用和冲突日历；
- 预约人与资源权限；
- 按资源维度的缓存键、唯一约束和并发锁。

---

### P3-2：预约生命周期不应主要依赖删除记录

当前取消和过期都会删除 Reservation，导致：

- 无法审计历史；
- 无法统计取消率、爽约率；
- 无法恢复误操作；
- 用户看不到历史预约。

原计划已建议增加状态，这里进一步建议采用状态机和状态变更时间，并保留事件历史。删除只用于数据保留策略，而不是表达业务取消。

---

### P3-3：缺少可用时段管理入口和容量模型

系统只能消费数据库中预先存在的时段，没有创建/批量生成/关闭/维护时段的管理接口。`isReserved` 也只能表达容量为 1 的情况。

如果实验室或设备允许多人共享，应使用：

- capacity；
- reservedCount；
- 原子条件更新；
- 或独立 seat/allocation 模型。

不要继续用单个 boolean 承担多容量需求。

---

### P3-4：没有通知、取消策略和权限规则的领域定义

原计划把通知列为后续功能，但在实现前应先定义：

- 预约成功/失败/临近开始通知；
- 取消截止时间；
- 管理员代预约/代取消；
- 黑名单和爽约处罚；
- 审批制资源；
- 通知发送失败是否影响预约事务。

通常通知应通过事务外盒（Outbox）异步发送，不能让邮件/短信故障回滚已经成功的预约。

## 8. 修订后的实施顺序

### 阶段 0：建立可重复验证的基线

1. 修复测试工程，使 `mvn test` 可稳定执行；
2. 使用 Testcontainers 准备 PostgreSQL 和 Redis；
3. 验证 Liquibase 全新迁移与旧库升级，并保持旧 changeSet 不变；
4. 提供有效开发种子数据；
5. 增加 Docker Compose 冒烟测试。

### 阶段 1：修复会丢数据或无法运行的问题

1. 启用并验证 Scheduling；
2. 修复预约过期调度单位；
3. 重写 Redis 队列确认、重试、DLQ 和崩溃恢复；
4. 修复 QueueItem 序列化/反序列化；
5. 启用并验证 Spring Retry；
6. 修复 Docker 健康检查。

### 阶段 2：建立安全和数据库最终一致性

1. 保护预约接口，从认证上下文读取用户；
2. 增加预约所有权和角色权限；
3. JWT/数据库 Secret 全部外部化；
4. 为登录和预约分别设计限流；
5. 建立活跃预约数据库约束；
6. 将最近时段选择改为数据库级单行原子抢占；
7. 决定是否删除“最近时段”缓存。

### 阶段 3：统一 API 和可观测性

1. 统一同步/异步 requestId 与结果模型；
2. 同步 Controller、Swagger、README、Postman；
3. 建立稳定错误码，隐藏内部异常；
4. 修复并完善 Timer、Counter、Gauge；
5. 定义 readiness/liveness 和 WARNING 行为；
6. 日志脱敏并加入 traceId。

### 阶段 4：再扩展实验室资源业务

1. Resource/Lab/Equipment 模型；
2. 预约状态机和历史记录；
3. 时段生成、维护和容量；
4. 签到、审批、取消策略；
5. Outbox 通知；
6. 审计、数据保留和统计报表。

## 9. 建议建立的最终验收场景

### 正常功能

- 用户能登录并创建最近可用预约；
- 同步和异步请求都能返回最终预约 ID 与时段；
- 用户能查看和取消自己的预约；
- 历史预约不会因为取消而直接消失。

### 并发正确性

- 100 个请求抢同一时段，最多一个成功；
- 同一用户并发请求，最多产生一个活跃预约；
- 不同可用时段可以被多个消费者并行分配；
- 数据库中不存在时段重复占用或计数超容量。

### 队列可靠性

- 消费者处理前、处理中、写库后崩溃均不会静默丢消息；
- 重试不会覆盖或删除其他消息；
- poison message 最终进入 DLQ；
- 重复投递不会创建重复预约；
- 去重标记不会永久残留。

### 安全

- 匿名用户不能创建、查询或取消预约；
- 用户不能操作他人的预约；
- 登录暴力尝试会被限速；
- 日志和错误响应不包含密码、完整 JWT、数据库异常详情；
- 生产仓库和镜像不包含真实 Secret。

### 部署与运维

- 空数据库可完成 Liquibase 迁移；
- `docker compose up` 后全部服务健康；
- readiness、liveness、指标和告警含义明确；
- 多实例消费不会重复处理，清理任务不会相互冲突；
- PostgreSQL/Redis 故障时系统有可预测的返回与恢复行为。

## 10. 最终判断

原计划的方向是对的，但覆盖程度大约属于“核心风险已识别，落地阻断项和验收闭环不足”。最值得补充的不是更多宽泛的架构名词，而是当前源码中已经能定位的具体故障：

1. 调度与 Retry 未启用；
2. 队列重试会误伤下一条消息；
3. 队列消息反序列化和出队失败会丢消息；
4. 种子数据和 Docker 健康检查可能阻断演示与部署验证；
5. 预约接口匿名开放，取消没有所有权；
6. 测试已经无法代表当前实现；
7. 时段查询锁范围过大，缓存又削弱了锁语义；
8. 时间单位、API 路径和监控配置存在明显漂移。

建议将本文件的 P0 和 P1 作为下一轮工作范围。只有这些问题形成自动化测试和验收闭环之后，再开始实验室资源模型、签到、通知等功能扩展。
