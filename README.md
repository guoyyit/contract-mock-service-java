# Contract-driven Mock Service

一个接口契约驱动的本地 HTTP 模拟服务（Spring Boot 3 / Java 17）。根据可在运行时更新的接口
契约匹配请求、做结构校验、按有状态场景返回响应，并支持在测试中切换场景、注入延迟与错误。

## 运行

```bash
mvn spring-boot:run
# 可选参数
#   --contractmock.store-path=data/mock-config.json   # 已提交配置的持久化文件
#   --contractmock.request-timeout-ms=30000           # 请求（含场景延迟）绝对超时上限
```

启动时若持久化文件存在且合法，会恢复到最近一次**已提交**的完整配置；否则从空配置开始。

## 管理接口

| 方法 & 路径 | 说明 |
| --- | --- |
| `GET /__admin/config` | 查看当前整组配置（含版本号、提交时间） |
| `POST /__admin/config` | 校验并以**整组原子版本**提交一份新配置 |
| `POST /__admin/scenarios/reset` | 重置场景调用序列：不带参数=全部；`?endpoint=`=该接口全部；再加 `&scenario=`=单个 |
| `GET /__docs` | 由当前契约实时生成的接口文档 |

除上述管理/文档路径外，**所有其它请求**都按已提交的契约进行模拟。

### 提交语义（并发安全 / 持久化）

- 一次 `POST /__admin/config` 是一次**全量替换**：整体校验通过才生效，任一条目不合法则整组拒绝，旧版本保持不变。
- 版本号在服务端单调递增（`version` 由服务端生成，请求体里可传 `null`）。
- 更新采用"先持久化（临时文件 + 原子 rename），再单次原子切换内存引用"。进行中的请求只可能看到更新前**或**更新后的完整配置，绝不会看到新旧混合。
- 只有完整写入并切换成功的配置才会落盘；写入中途崩溃不会恢复半成品（残留临时文件被忽略）。
- 每次提交都会得到全新、归零的场景状态；旧版本上的进行中请求继续使用旧版本的配置与状态。

## 契约结构

```json
{
  "version": null,
  "committedAt": null,
  "endpoints": [
    {
      "id": "orders-create",
      "method": "POST",
      "pathTemplate": "/orders/{id}",
      "request": {
        "headers":    [ { "name": "X-Tenant", "required": true,  "type": "STRING", "pattern": "[a-z]+" } ],
        "queryParams": [ { "name": "page",     "required": false, "type": "INTEGER" } ],
        "body": {
          "required": true,
          "contentType": "application/json",
          "fields": [
            { "path": "customerId", "type": "STRING",  "required": true },
            { "path": "amount",     "type": "NUMBER",  "required": false },
            { "path": "items.0.sku","type": "STRING",  "required": false }
          ]
        }
      },
      "defaultScenario": "default",
      "scenarios": {
        "default": {
          "name": "default",
          "selector": null,
          "delayMs": null,
          "errorResponse": null,
          "steps": [
            { "name": "created",
              "response": { "status": 201, "headers": {}, "body": { "orderId": "o-1" } },
              "delayMs": null,
              "errorResponse": null }
          ],
          "fallback": { "status": 200, "headers": {}, "body": { "orderId": "fb" } }
        },
        "vip": {
          "name": "vip",
          "selector": { "source": "HEADER", "field": "X-Tier", "equalsValue": "gold" },
          "steps": [],
          "fallback": { "status": 200, "headers": { "X-Vip": "yes" }, "body": { "tier": "vip" } },
          "delayMs": null,
          "errorResponse": null
        }
      }
    }
  ]
}
```

- **匹配**：HTTP 方法 + `PathPattern` 路径模板（支持 `{var}`）。路径匹配但方法不符返回 `405` 并带 `Allow` 头；都不匹配返回 `404`。
- **结构校验**：对请求头（大小写不敏感）、查询参数、请求体字段做必填 / 类型 / 正则校验。失败返回 `400 VALIDATION_FAILED`，`issues[]` 中每条带稳定的 `location`(`header`/`query`/`body`)、`field`、`reason`(`missing`/`type_mismatch`/`pattern_mismatch`)、`expected`、`actual`，便于区分具体原因。
- 类型：`STRING / NUMBER / INTEGER / BOOLEAN / OBJECT / ARRAY / NULL`（头与查询按字符串标量解析；请求体按 JSON 类型校验）。

## 有状态场景、选择器与故障注入

- **状态序列**：每个场景有一组有序 `steps`。请求每来一次消费下一步（第 1 次→step[0]，第 2 次→step[1]…）；序列耗尽后返回 `fallback`。调用 `reset` 后行为确定地回到第一步。
- **场景选择**：按 `selector` 从 `HEADER` / `QUERY` / `BODY`（点路径字段）读取值，等于 `equalsValue` 即命中该场景；否则使用 `defaultScenario`。
- **延迟与错误**：可在**场景级**（`delayMs` / `errorResponse`）或**步骤级**（每个 step 的同名字段，优先级更高）配置。
  - `delayMs` 让该请求异步延迟后再响应，不占用请求线程。
  - `errorResponse` 命中时用指定的错误响应替代正常响应。
  - 延迟与错误**只作用于命中该场景/步骤的那一个请求**，不会污染其它请求或接口。

## 请求取消与超时

- 请求在延迟等待期间被客户端取消或到达 `request-timeout-ms` 上限时，会取消其调度任务、释放调度线程；超时返回 `504 REQUEST_TIMEOUT`。
- 场景调用序号在延迟开始**之前**就已原子消费。因此延迟中取消：已消费的步骤既不会因重试被重复消费，也不会从序列中丢失（下一次请求得到的是下一步）。

## 文档

`GET /__docs` 返回的文档完全由当前活跃配置派生（版本、路径、请求约束、每个场景的步骤/回退/延迟/错误、从响应体推断的 JSON 结构，以及统一错误码），契约或场景一旦变更，文档在下一次请求即同步，不存在文档与实际响应漂移。

## 统一错误码

| code | HTTP | 含义 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | 请求结构不满足契约，见 `issues[]` |
| `MALFORMED_REQUEST_BODY` | 400 | 请求体不是合法 JSON |
| `ENDPOINT_NOT_FOUND` | 404 | 无方法+路径匹配（重置时引用未知接口也用此码） |
| `METHOD_NOT_ALLOWED` | 405 | 路径存在但方法不允许，带 `Allow` 头 |
| `REQUEST_TIMEOUT` | 504 | 场景延迟等待期间超时/中止 |
| `INVALID_CONFIG` | 400 | 提交的配置整组校验失败 |
| `NO_SCENARIO` / `NO_RESPONSE_DEFINED` | 500 | 契约缺少场景或该步无任何响应 |
| `INTERNAL_ERROR` | 500 | 未预期内部错误 |

## 测试

```bash
mvn test
```

覆盖：契约/结构校验、有状态序列与重置、场景选择与延迟/错误隔离、并发整组更新的原子性、
延迟中取消与超时（状态不重复不丢失）、持久化重启恢复与半成品防护、文档与契约一致性。
