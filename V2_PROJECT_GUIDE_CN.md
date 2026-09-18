# 实验室资源预约系统 V2 项目说明

## 1. 项目定位

本项目是一个基于 Java 21 与 Spring Boot 3.5 的实验室资源预约后端。它不仅提供“预约最近空闲时段”的演示能力，还形成了用户、资源、可用时段、预约生命周期、权限管理、并发控制和 Redis 异步削峰组成的完整业务闭环。

PostgreSQL 是最终业务事实来源；Redis 只承担缓存、异步队列、请求状态、重试、死信队列和宕机恢复。所有同步与异步预约最终都调用同一个 `ReservationService`，不会在队列消费者中复制业务规则。

## 2. 核心业务模型

```text
User 1 ---- N Reservation N ---- 1 AvailableSlot N ---- 1 Resource
```

- `User`：登录用户，角色为 `USER` 或 `ADMIN`。
- `Resource`：可预约对象，例如实验室、会议室、GPU、设备、工作站。
- `AvailableSlot`：某个资源的一段可预约时间。
- `Reservation`：用户对一个时段的一次预约及其完整历史。

资源状态：

```text
ACTIVE       可查询、可预约
MAINTENANCE  维护中，不向普通用户开放
DISABLED     已软禁用，不向普通用户开放
```

预约状态及合法流转：

```text
ACTIVE -> CANCELLED
ACTIVE -> COMPLETED
```

取消预约不会删除数据库记录。系统会保留取消时间和原因，并释放尚未开始的时段。已经结束的有效预约由定时任务转为 `COMPLETED`，历史数据同样保留。

## 3. 分层结构

```text
controller  HTTP 接口、参数接收、认证用户获取
service     业务规则、事务、DTO 转换、队列处理
repository  JPA 查询、分页、数据库行锁
entity      User / Resource / AvailableSlot / Reservation
dto         对外请求与响应，避免直接暴露 Entity
security    JWT 解析、认证主体、角色权限
config      Security、Redis、缓存、审计、OpenAPI 等配置
filter      Request ID 与限流
exception   统一业务异常和 HTTP 错误响应
```

## 4. 身份与权限

登录入口：

```text
POST /api/v1/auth/login
```

预约相关接口不再接收或信任客户端传入的邮箱。JWT 经 `JwtFilter` 解析后形成 `AuthenticatedUser`，业务层只使用其中的可信 `userId`。

- 未登录访问业务接口：`401 Unauthorized`
- `USER` 访问 `/api/v1/admin/**`：`403 Forbidden`
- 用户访问他人的预约详情或取消他人的预约：`403 Forbidden`
- 管理员接口：必须具有 `ADMIN` 角色

开发环境的三个演示账号密码均为 `password`：

```text
johndoe1@example.com
johndoe2@example.com
user125@example.com
```

默认迁移会把已有账号设为 `USER`。仅在本地验证管理员链路时，可以把一个演示账号改为管理员：

```sql
UPDATE users SET role = 'ADMIN' WHERE email = 'johndoe1@example.com';
```

## 5. 主要 API

### 5.1 认证

| 方法 | 地址 | 作用 |
|---|---|---|
| POST | `/api/v1/auth/login` | 使用邮箱和密码换取 JWT |

### 5.2 普通用户资源与时段查询

| 方法 | 地址 | 作用 |
|---|---|---|
| GET | `/api/v1/resources` | 分页查询 ACTIVE 资源，支持 type、status、location |
| GET | `/api/v1/resources/{id}` | 查询单个可见资源 |
| GET | `/api/v1/slots` | 查询未来、未预约且资源为 ACTIVE 的时段 |
| GET | `/api/v1/slots/{id}` | 查询单个可用时段 |

时段列表支持 `resourceId`、`resourceType`、`start`、`end`、`page`、`size`。

### 5.3 预约

| 方法 | 地址 | 作用 |
|---|---|---|
| POST | `/api/v1/me/reservations` | 当前用户手工预约指定 `slotId` |
| POST | `/api/v1/me/reservation-requests` | 自动预约最近可用时段 |
| GET | `/api/v1/me/reservation-requests/{requestId}` | 查询异步请求状态 |
| GET | `/api/v1/me/reservations` | 分页查询当前用户预约，可按 status 过滤 |
| GET | `/api/v1/me/reservations/{id}` | 查询自己的预约详情 |
| DELETE | `/api/v1/me/reservations/{id}` | 取消自己的预约并保留历史 |

手工预约请求示例：

```json
{ "slotId": 8 }
```

自动预约在低负载时同步处理；高负载时返回 `202 Accepted` 和 `requestId`，由 Redis 队列异步处理。

### 5.4 管理员资源与时段

```text
POST   /api/v1/admin/resources
GET    /api/v1/admin/resources
PUT    /api/v1/admin/resources/{id}
DELETE /api/v1/admin/resources/{id}

POST   /api/v1/admin/slots
POST   /api/v1/admin/slots/batches
GET    /api/v1/admin/slots
PUT    /api/v1/admin/slots/{id}
DELETE /api/v1/admin/slots/{id}
```

资源删除采用软禁用。批量创建时段会检查日期范围、每日时间范围、时长以及同一资源已有时段的重叠。已预约时段不能修改关键时间或删除。

### 5.5 管理员预约管理

| 方法 | 地址 | 作用 |
|---|---|---|
| GET | `/api/v1/admin/reservations` | 按 userId、resourceId、status、start、end 分页筛选 |
| DELETE | `/api/v1/admin/reservations/{id}` | 管理员取消有效预约，不物理删除 |

## 6. 并发与一致性

预约流程中的关键保护如下：

1. 在事务中锁定用户，串行化同一用户的重叠检查。
2. 手工预约用悲观写锁锁定指定时段。
3. 自动预约使用 PostgreSQL `FOR UPDATE SKIP LOCKED` 领取最近候选时段。
4. 在锁内重新检查资源状态、开始时间、占用状态和用户时间冲突。
5. 数据库部分唯一索引保证同一时段最多只有一条 ACTIVE 预约。
6. 实体版本字段提供额外的乐观锁保护。
7. 预约与时段状态在同一事务内提交或回滚，不产生半成品。

用户时间冲突采用半开区间规则：

```text
existing.start < candidate.end
AND existing.end > candidate.start
```

首尾相接的两个预约不算重叠。

## 7. Redis 队列 V2

队列消息不再依赖邮箱，而是携带可信用户主键和处理模式：

```json
{
  "requestId": "...",
  "request": {
    "userId": 1,
    "slotId": 8,
    "mode": "MANUAL"
  },
  "attempts": 0
}
```

自动模式使用 `AUTO`，`slotId` 可为空。队列可靠性结构包括：

- List：等待处理
- ZSet：已领取、处理中及领取时间
- Set：同一用户排队去重
- String：请求状态与 TTL
- List：DLQ 死信队列
- Lua：原子入队、领取、完成、重试、转移死信和过期 claim 恢复

状态流转：

```text
QUEUED -> PROCESSING -> SUCCESS
                     -> QUEUED（可重试错误）
                     -> FAILED（明确失败或达到最大次数）
```

## 8. 启动方式

要求：Docker Desktop 可用。复制环境模板并替换示例密码：

```powershell
Copy-Item .env.example .env
docker compose up -d --build
docker compose ps
```

服务地址：

```text
业务 API       http://localhost:8080
Swagger UI    http://localhost:8080/swagger-ui/index.html
健康检查       http://localhost:8081/actuator/health
Prometheus     http://localhost:8081/actuator/prometheus
PostgreSQL     localhost:5432
Redis          localhost:6379
```

停止服务但保留数据：

```powershell
docker compose down
```

只有确认不再需要本地数据库数据时，才使用 `docker compose down -v`。

## 9. 测试

Windows：

```powershell
.\mvnw.cmd test
```

测试通过 Testcontainers 启动真实 PostgreSQL 15 和 Redis 7，覆盖：

- Liquibase 全新数据库迁移
- 资源和时段筛选、分页、可见性
- JWT 身份和 USER/ADMIN 权限
- 手工预约、自动预约、生命周期与所有权
- 同一时段和同一用户的并发竞争
- Redis 入队、状态、重试、DLQ、异常 claim 恢复
- 管理员资源、时段和预约管理

本轮最终回归结果为 **72 项测试全部通过，0 失败、0 错误**。另外已使用完整 Docker Compose 环境验证应用、PostgreSQL、Redis 均为 `healthy`，健康接口返回 `UP`，登录接口和 OpenAPI 文档可正常访问。

## 10. 推荐手工验证顺序

普通用户链路：

```text
登录 -> 查询资源 -> 查询时段 -> 手工预约 -> 查询我的预约
-> 查询详情 -> 取消预约 -> 确认历史仍存在且时段已释放
```

管理员链路：

```text
本地设置 ADMIN -> 登录 -> 创建资源 -> 批量创建时段
-> 普通用户查询和预约 -> 管理员筛选预约 -> 管理员取消
```

异步链路：

```text
触发自动预约高负载路径 -> 获得 requestId
-> 轮询 status -> 确认 SUCCESS 或 FAILED
```

## 11. 当前边界

- 当前没有开放注册、刷新令牌、注销黑名单和通知功能。
- `capacity` 当前是资源说明字段；一个时段仍只允许一条 ACTIVE 预约。
- 队列 V2 已支持 MANUAL/AUTO 消息分派；现有 HTTP 高负载自动入队，手工接口仍同步返回详细预约结果。
- 自动预约消息预留了 `resourceType`，当前版本仍按所有 ACTIVE 资源选择最近时段。
- 管理员账号不由生产迁移自动创建，需要部署方通过安全的用户管理流程授予角色。
