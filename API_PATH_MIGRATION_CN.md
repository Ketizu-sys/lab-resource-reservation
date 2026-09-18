# REST API 最终路径与迁移映射

本文档记录项目最终整理阶段采用的 REST API 路径。所有业务接口统一使用 `/api/v1` 前缀；管理员资源位于 `/api/v1/admin`，当前用户拥有的资源位于 `/api/v1/me`。

## 路径迁移表

| HTTP 方法 | 旧路径 | 新路径 | 说明 |
|---|---|---|---|
| POST | `/api/auth/login` | `/api/v1/auth/login` | 认证接口纳入版本管理 |
| POST | `/api/v1/reservations` | `/api/v1/me/reservations` | 当前用户创建指定时段预约 |
| GET | `/api/v1/reservations/{id}` | `/api/v1/me/reservations/{id}` | 查询当前用户拥有的预约 |
| DELETE | `/api/v1/reservations/{id}` | `/api/v1/me/reservations/{id}` | 取消当前用户拥有的预约 |
| DELETE | `/api/v1/reservations/cancel/{id}` | `/api/v1/me/reservations/{id}` | 删除重复的动词式路径 |
| POST | `/api/v1/reservations/auto` | `/api/v1/me/reservation-requests` | 创建自动预约请求 |
| POST | `/api/v1/reservations/reserve` | `/api/v1/me/reservation-requests` | 合并重复的自动预约入口 |
| GET | `/api/v1/reservations/status/{requestId}` | `/api/v1/me/reservation-requests/{requestId}` | 查询本人异步请求状态 |
| POST | `/api/v1/admin/slots/batch` | `/api/v1/admin/slots/batches` | 批量时段使用复数子资源名 |

旧地址不再作为别名保留，避免同一业务长期存在多套命名。客户端、Postman 集合和自动化脚本应使用新地址。

## 保持不变的主要路径

- `/api/v1/resources`
- `/api/v1/resources/{id}`
- `/api/v1/slots`
- `/api/v1/slots/{id}`
- `/api/v1/admin/resources`
- `/api/v1/admin/resources/{id}`
- `/api/v1/admin/slots`
- `/api/v1/admin/slots/{id}`
- `/api/v1/admin/reservations`
- `/api/v1/admin/reservations/{id}`

## 认证和权限

- `POST /api/v1/auth/login` 保持匿名可访问。
- `/api/v1/me/**` 必须携带有效的 JWT。
- `/api/v1/admin/**` 除 JWT 外还要求 `ADMIN` 角色。
- 资源和时段查询继续按当前安全配置要求认证。
