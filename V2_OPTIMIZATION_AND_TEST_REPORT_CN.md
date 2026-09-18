# 实验室资源预约系统 V2 优化与测试报告

> 文档用途：供代码审核者或 GPT 对本轮改造的完整性、正确性、风险与后续工作进行复核。  
> 生成日期：2026-09-17  
> 项目目录：`E:\IdeaData\lab-resource-reservation`  
> 开发分支：`codex/v2-domain-and-api`  
> 对比基线：`main`（基线提交 `2097099`）

## 1. 执行结论

本轮在不直接改动 `main` 分支的前提下，完成了预约系统 V2 领域模型、用户接口、管理员接口、权限模型、并发一致性和 Redis 异步队列的系统性升级。

最终结果：

- 在生成本报告前，V2 功能与原有说明文档相对 `main` 共变更 **72 个文件**；把本报告本身计入后，分支总变更为 **73 个文件**。
- 代码统计约为 **新增 2706 行、删除 179 行**。
- 共形成 **13 个独立 Git 提交**，每个主要阶段均可单独审查和回退。
- Maven 最终回归测试：**72 项通过，0 失败、0 错误、0 跳过**。
- Docker Compose 完整部署验证通过。
- 应用、PostgreSQL、Redis 三个容器最终均为 `healthy`。
- Actuator 健康检查返回 `UP`。
- Redis 返回 `PONG`，PostgreSQL 正常接受连接。
- 真实登录接口调用成功。
- OpenAPI 成功暴露 19 个业务路径。
- 工作区在本轮完成时无未提交的受 Git 管理文件。
- 当前改动尚未合并进 `main`，应在审核通过后再决定是否合并。

## 2. 优化目标

原项目的核心能力偏向“用户通过邮箱预约最近时段”的演示流程。本轮主要解决以下问题：

1. 缺少明确的资源领域，时段无法清晰归属于实验室、设备或会议室。
2. 预约缺少完整生命周期，难以保留取消和完成历史。
3. 部分预约接口信任客户端传入邮箱，存在越权与身份伪造风险。
4. 缺少普通用户可使用的资源、时段、预约历史与详情接口。
5. 缺少管理员维护资源、时段和预约的能力。
6. 自动预约与手工预约规则没有统一收口。
7. 高并发下需要同时防止同一时段重复预约和同一用户时间冲突。
8. Redis 队列消息仍依赖邮箱，且需要与 V2 领域模型对齐。
9. 缺少覆盖真实 PostgreSQL、Redis、权限和并发场景的系统回归证据。

## 3. 已完成的优化

### 3.1 新增资源领域模型

新增 `Resource`，把实验室、会议室、GPU、设备或工作站等可预约对象从时段中独立出来。

核心关系：

```text
User 1 ---- N Reservation N ---- 1 AvailableSlot N ---- 1 Resource
```

新增内容：

- `Resource` 实体。
- `ResourceType` 资源类型枚举。
- `ResourceStatus` 资源状态枚举。
- `ResourceRepository`。
- `AvailableSlot.resource` 外键关系。
- 资源与时段相关数据库索引和约束。

资源状态含义：

```text
ACTIVE       正常开放，可被普通用户查询和预约
MAINTENANCE  维护中
DISABLED     已软禁用
```

资源删除采用软禁用方式，避免破坏已有时段和预约历史。

### 3.2 完善预约生命周期

新增 `ReservationStatus`：

```text
ACTIVE -> CANCELLED
ACTIVE -> COMPLETED
```

预约增加：

- 当前状态。
- 取消时间。
- 完成时间。
- 取消原因。

取消预约不会删除记录。对于尚未开始的预约，取消时会释放时段；已经结束的有效预约由定时任务转为 `COMPLETED`。

数据库使用部分唯一索引保证同一时段最多存在一条 `ACTIVE` 预约，同时允许保留该时段过去的取消记录。

### 3.3 修正身份信任边界

预约接口不再使用客户端请求体中的邮箱确定用户身份。

当前流程：

```text
Authorization: Bearer <JWT>
        -> JwtFilter 验证令牌
        -> 从数据库读取用户
        -> 构造 AuthenticatedUser
        -> Controller/Service 使用可信 userId
```

安全效果：

- 未登录访问受保护接口返回 `401`。
- 普通用户访问管理员接口返回 `403`。
- 普通用户不能查询或取消他人的预约。
- 即使请求体伪造其他邮箱，业务仍以 JWT 对应用户为准。
- 管理员接口统一位于 `/api/v1/admin/**`，要求 `ADMIN` 角色。

### 3.4 新增普通用户资源与时段查询接口

已增加：

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/api/v1/resources` | 分页查询可见资源并进行条件筛选 |
| GET | `/api/v1/resources/{id}` | 查询单个可见资源 |
| GET | `/api/v1/slots` | 查询未来可预约时段 |
| GET | `/api/v1/slots/{id}` | 查询单个可预约时段 |

时段查询支持资源、资源类型、时间区间和分页条件，并排除已预约、已过期或资源非 `ACTIVE` 的时段。

### 3.5 新增手工预约和自动预约

主要接口：

| 方法 | 路径 | 作用 |
|---|---|---|
| POST | `/api/v1/me/reservations` | 当前用户预约指定 `slotId` |
| POST | `/api/v1/me/reservation-requests` | 自动选择最近可用时段 |
| GET | `/api/v1/me/reservation-requests/{requestId}` | 查询异步预约状态 |

手工预约会验证：

- 用户存在。
- 时段存在且尚未开始。
- 资源为 `ACTIVE`。
- 时段未被占用。
- 用户没有时间重叠的有效预约。

自动预约与手工预约最终都调用同一个 `ReservationService`，避免同步路径与异步路径产生不同业务规则。

### 3.6 完成用户预约闭环

新增：

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/api/v1/me/reservations` | 分页查询当前用户预约，可按状态过滤 |
| GET | `/api/v1/me/reservations/{id}` | 查询当前用户拥有的预约详情 |
| DELETE | `/api/v1/me/reservations/{id}` | 取消当前用户拥有的有效预约 |

对应服务会检查预约所有权，不通过客户端提供的用户标识判断归属。

### 3.7 新增管理员资源与时段管理

资源接口：

```text
POST   /api/v1/admin/resources
GET    /api/v1/admin/resources
PUT    /api/v1/admin/resources/{id}
DELETE /api/v1/admin/resources/{id}
```

时段接口：

```text
POST   /api/v1/admin/slots
POST   /api/v1/admin/slots/batches
GET    /api/v1/admin/slots
PUT    /api/v1/admin/slots/{id}
DELETE /api/v1/admin/slots/{id}
```

管理规则包括：

- 创建和更新资源时进行字段校验。
- 批量创建时段时校验日期范围、每日起止时间和时段长度。
- 防止同一资源的时段重叠。
- 已预约时段不能随意修改关键时间或删除。
- 删除资源采用状态软删除。

### 3.8 新增管理员预约管理

```text
GET    /api/v1/admin/reservations
DELETE /api/v1/admin/reservations/{id}
```

管理员可以按用户、资源、预约状态和时间范围分页筛选预约，也可以取消有效预约，但不会物理删除历史。

### 3.9 加强并发与数据库一致性

当前预约流程采用多层保护：

1. 在事务中锁定用户，串行化同一用户的重叠检查。
2. 手工预约使用悲观写锁锁定指定时段。
3. 自动预约通过 PostgreSQL `FOR UPDATE SKIP LOCKED` 领取最近候选时段。
4. 获取锁后重新验证资源状态、时间、占用状态和用户时间冲突。
5. 数据库部分唯一索引保证一个时段最多有一条有效预约。
6. 实体 `version` 字段提供额外乐观锁保护。
7. 预约记录和时段占用状态在同一事务中提交或回滚。

时间重叠判断采用半开区间：

```text
existing.start < candidate.end
AND existing.end > candidate.start
```

因此两个首尾相接的预约不视为冲突。

### 3.10 Redis 异步预约队列升级

队列消息由邮箱身份改为可信用户主键，并增加处理模式：

```json
{
  "requestId": "...",
  "request": {
    "userId": 1,
    "slotId": 8,
    "resourceType": null,
    "mode": "MANUAL"
  },
  "attempts": 0
}
```

支持 `MANUAL` 和 `AUTO` 两种消息，消费者按模式调用统一预约服务。

保留或补强的可靠性能力：

- 等待队列。
- 已领取任务集合及领取时间。
- 同一用户排队去重集合。
- 请求状态和 TTL。
- 可重试错误重新入队。
- 超过最大次数进入 DLQ。
- 消费者异常退出后恢复超时 claim。
- Lua 脚本保证关键 Redis 状态变更的原子性。

状态流程：

```text
QUEUED -> PROCESSING -> SUCCESS
                     -> QUEUED  （可重试）
                     -> FAILED  （明确失败或达到最大次数）
```

### 3.11 数据库迁移与版本管理

新增 Liquibase 迁移：

- `changeset-004-resource-domain.xml`
- `changeset-005-reservation-lifecycle.xml`
- `changeset-006-user-role.xml`

数据库中的 `z_liq_changelog` 用于记录已执行的迁移，`z_liq_changelog_lock` 用于防止多个实例同时迁移。这两个表属于 Liquibase 基础设施，不应作为业务表删除。

### 3.12 健康检查修复

完整 Docker 验证期间发现 `/actuator/health` 被 JWT 规则拦截并返回 `401`，导致应用容器无法转为 `healthy`。

已采取的修复：

- 仅匿名放行 `/actuator/health` 和其子路径。
- 其他管理端点没有因此整体公开。
- 增加安全集成测试防止回归。
- 重新构建 Docker 镜像后，应用容器成功变为 `healthy`。

## 4. 测试结果

### 4.1 最终全量回归

执行命令：

```powershell
.\mvnw.cmd "-Dmaven.repo.local=C:\Users\admin\.m2\repository" test
```

结果：

```text
Tests run: 72, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 04:39 min
```

测试使用 Testcontainers 启动真实 PostgreSQL 15 和 Redis 7，而不是仅使用内存数据库或纯 Mock 替代关键基础设施。

### 4.2 测试套件明细

| 测试套件 | 数量 | 结果 |
|---|---:|---|
| 配置、审计、队列健康检查 | 5 | 全部通过 |
| Controller、限流、Request ID | 10 | 全部通过 |
| Repository 查询与锁 | 9 | 全部通过 |
| 应用启动 | 2 | 全部通过 |
| JWT 与 Security 集成 | 10 | 全部通过 |
| 管理员资源、时段、预约 | 3 | 全部通过 |
| 手工预约、自动预约与并发 | 6 | 全部通过 |
| 预约过期与用户生命周期 | 4 | 全部通过 |
| Redis 队列单元与集成 | 11 | 全部通过 |
| ReservationService | 8 | 全部通过 |
| 资源、时段查询集成 | 4 | 全部通过 |
| **总计** | **72** | **0 失败、0 错误、0 跳过** |

重点覆盖场景：

- Liquibase 在空数据库上执行迁移。
- JWT 身份提取及伪造邮箱不生效。
- USER 与 ADMIN 角色边界。
- 用户预约所有权。
- 资源和时段分页、筛选及可见性。
- 手工预约成功与非法资源状态拒绝。
- 自动预约最近时段。
- 同一时段的并发争抢只能成功一次。
- 同一用户重叠预约被拒绝。
- 不重叠时段允许同一用户分别预约。
- 预约取消、历史保留和时段释放。
- 过期预约自动完成。
- Redis 入队、去重、领取、完成、重试、DLQ 和超时 claim 恢复。
- 管理员资源、时段和预约管理。
- Actuator 健康检查无需 JWT 即可访问。

### 4.3 Docker Compose 验证

执行：

```powershell
docker compose up -d --build
docker compose ps
```

最终状态：

```text
app       Up (healthy)
postgres  Up (healthy)
redis     Up (healthy)
```

额外冒烟验证：

| 验证项 | 结果 |
|---|---|
| `http://localhost:8081/actuator/health` | `UP` |
| Redis `PING` | `PONG` |
| PostgreSQL `pg_isready` | accepting connections |
| `POST /api/v1/auth/login` | 成功返回 JWT |
| `GET /v3/api-docs` | 成功，包含 19 个业务路径 |

Docker 构建过程中 Liquibase 成功执行全部适用的 changeset，应用随后正常启动。

## 5. Git 阶段记录

按执行顺序的提交如下：

| 提交 | 内容 |
|---|---|
| `a5fddec` | 引入资源领域模型 |
| `0974f36` | 增加预约生命周期状态 |
| `aa3f72a` | 预约接口绑定认证用户 |
| `4f4a444` | 增加资源查询 API |
| `5a7e913` | 增加可用时段查询 API |
| `873d66f` | 增加手工指定时段预约 API |
| `3c1e905` | 完成用户预约生命周期 API |
| `9956e9b` | 自动预约适配 V2 领域模型 |
| `e7fc806` | 增加管理员资源与时段管理 |
| `2705c58` | 增加管理员预约管理 |
| `a303a7d` | Redis 异步队列适配 V2 模型 |
| `91b8fa2` | 修复容器健康检查认证问题 |
| `40a9bc4` | 更新 V2 项目文档 |

审核者可使用以下命令检查全部差异：

```powershell
git diff main...codex/v2-domain-and-api
git log --oneline main..codex/v2-domain-and-api
```

## 6. 主要新增或修改文件范围

### 6.1 文档与配置

- `README.md`
- `V2_PROJECT_GUIDE_CN.md`
- `V2_OPTIMIZATION_AND_TEST_REPORT_CN.md`（本报告）
- `src/main/resources/application.yml`
- `src/main/java/com/azki/reservation/config/SecurityConfig.java`

### 6.2 Controller

- `AdminReservationController.java`
- `AdminResourceController.java`
- `AdminSlotController.java`
- `MyReservationController.java`
- `ReservationController.java`
- `ResourceController.java`
- `SlotController.java`
- `GlobalExceptionHandler.java`

### 6.3 Entity 与枚举

- `Resource.java`
- `ResourceType.java`
- `ResourceStatus.java`
- `ReservationStatus.java`
- `Reservation.java`
- `AvailableSlot.java`
- `User.java`
- `UserRole.java`

### 6.4 Service

- `AdminReservationService.java`
- `AdminResourceService.java`
- `AdminSlotService.java`
- `ReservationDtoMapper.java`
- `ReservationExpiryService.java`
- `ReservationQueueService.java`
- `ReservationService.java`
- `ResourceQueryService.java`
- `SlotQueryService.java`
- `UserReservationQueryService.java`

### 6.5 Repository 与迁移

- `ReservationRepository.java`
- `ResourceRepository.java`
- `TimeSlotRepository.java`
- `UserRepository.java`
- `changeset-004-resource-domain.xml`
- `changeset-005-reservation-lifecycle.xml`
- `changeset-006-user-role.xml`
- `db.changelog-master.xml`

### 6.6 测试

新增或扩展了安全、资源、时段、手工预约、自动预约、管理员管理、并发控制、用户生命周期和 Redis 队列相关测试，共形成 24 个测试套件、72 个测试用例。

## 7. 当前已知边界

以下内容不是本轮缺陷，但仍属于后续可继续完善的功能：

1. 暂无公开注册接口、刷新令牌和注销令牌黑名单。
2. 暂无邮件、短信或站内通知。
3. 暂无完整的管理员用户与角色管理 API；管理员角色目前需要通过安全运维流程授予。
4. `capacity` 当前是资源属性，但一个时段仍只允许一条有效预约；尚未实现按容量扣减名额。
5. 自动预约消息已预留 `resourceType`，当前仍按所有 `ACTIVE` 资源选择最近时段。
6. HTTP 高负载路径会让自动预约进入队列；手工预约当前仍同步返回详细结果。
7. 尚未补充前端页面。
8. 尚未建立 CI/CD 流水线、覆盖率阈值、性能基准和长时间压力测试。
9. Docker 本地 `.env` 包含测试环境变量且已被 Git 忽略；生产环境必须使用独立密钥管理方式。

## 8. 建议审核者重点检查

请审核者重点回答以下问题：

### 8.1 领域和数据模型

- `Resource -> AvailableSlot -> Reservation` 的关系是否合理？
- 资源软禁用和预约历史保留策略是否符合业务预期？
- `ACTIVE/CANCELLED/COMPLETED` 是否足以覆盖当前业务？
- 部分唯一索引和 Liquibase 迁移是否能安全应用到已有数据库？

### 8.2 安全

- 是否仍存在任何接口信任客户端邮箱或用户 ID 的情况？
- `/api/v1/admin/**` 是否都受到 ADMIN 角色保护？
- 健康接口匿名放行是否保持在最小范围？
- 用户预约详情与取消操作是否严格校验所有权？

### 8.3 并发和事务

- 用户锁、时段锁、`SKIP LOCKED`、数据库约束是否存在死锁或锁顺序风险？
- 自动预约候选选择和锁内二次校验是否完整？
- 预约记录和 `isReserved` 状态能否在异常时保持一致回滚？
- 取消和过期任务并发执行时是否存在状态竞争？

### 8.4 Redis 队列

- 用户排队去重集合在成功、失败、重试和宕机恢复后是否都能正确清理？
- claim 恢复和 DLQ 转移是否可能造成重复消费或任务丢失？
- Redis 状态仅作为过程状态、PostgreSQL 作为最终事实来源的边界是否清楚？

### 8.5 API 与可维护性

- 新旧兼容预约地址是否需要保留，还是应在后续版本废弃？
- DTO 校验、异常状态码和分页参数是否一致？
- 管理员批量时段创建的边界条件是否足够？
- 是否需要为 OpenAPI 增加更完整的请求示例和错误响应说明？

## 9. 建议审核结论格式

建议审核者按照以下格式反馈，便于继续修改：

```text
总体结论：可合并 / 修改后可合并 / 不建议合并

阻塞问题：
1. 文件 + 代码位置 + 原因 + 建议修改方式

非阻塞问题：
1. 文件 + 代码位置 + 改进建议

测试缺口：
1. 建议补充的测试场景

后续功能建议：
1. 建议优先级和原因
```

## 10. 最终说明

当前结果已经通过自动化回归和完整 Docker 环境冒烟验证，但测试通过不等于不存在设计风险。合并前仍建议重点审查数据库迁移兼容性、锁顺序、Redis 异常恢复、安全边界以及旧接口兼容策略。

在审核完成前，建议继续保留 `codex/v2-domain-and-api` 分支，不要立即合并到 `main`。
