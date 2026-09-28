# Contract Mock Service (Java / Spring Boot)

一个接口契约驱动的本地 HTTP 模拟服务。根据可配置的接口契约匹配请求、返回符合契约的响应，
支持有状态场景、场景切换、注入延迟/错误、整组原子更新、请求取消/超时、提交持久化与
从当前契约自动生成的接口文档。

## 构建与运行

```bash
mvn package
java -jar target/contract-mock-service-java-1.0-SNAPSHOT.jar \
  --server.port=8080 \
  --mock.store.file=target/mock-config.json \
  --mock.request-timeout-ms=30000
```

## 管理 API（`/__admin`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/__admin/configs` | 提交**整组**配置（一个版本）。校验通过后先持久化再原子发布。 |
| GET  | `/__admin/configs/current` | 读取当前已发布版本。 |
| POST | `/__admin/scenarios/reset?scenario=<name>` | 重置全部（或指定）场景的调用计数。 |
| GET  | `/__admin/docs` | 由当前版本生成的 Markdown 接口文档（随契约/场景变更同步）。 |

其他所有路径（`/**`）均为模拟流量入口。

## 配置模型

顶层为 `MockConfig`：`version`（正整数）、`note`、`contracts[]`。

- `Contract`：`id`、`method`、`pathTemplate`（如 `/users/{id}`）、
  `queryParams[]`、`headers[]`、`body`、`selectors[]`、`defaultScenario`、`scenarios[]`。
- `ParamSpec` / `FieldSpec`：`name`/`path`、`required`、`pattern`（正则）、`equals`，
  字段另支持 `type`：`string|integer|number|boolean|object|array`。
- `ScenarioSelector`：`source` 为 `HEADER|QUERY|BODY`，`name` 为名称或点分 body 路径，
  值等于 `equals` 时选择 `scenario`，否则回落到 `defaultScenario`。
- `Scenario`：`name`、`steps[]`、`repeatLast`（序列耗尽后是否重复最后一步）。
- `ResponseStep`：`status`、`headers`、`body`、`delayMillis`、`error`、`errorCode`。
  每个匹配调用按顺序消费一步；延迟与错误只作用于当前请求。

## 语义与并发保证

- **原子版本**：请求开始时捕获一个不可变 `Snapshot`，处理全过程只看到该版本；
  发布以 volatile 整组引用切换，处理中的请求不会看到新旧混合。
- **状态消费先于延迟**：进入延迟前就完成场景步进计数。延迟中取消/超时只会释放定时任务，
  已消费的步骤不会重复也不会丢失；重置后行为确定。
- **持久化**：提交写临时文件 + fsync + 原子 rename。未完成/校验失败的更新不会落盘；
  重启恢复到最近一次已提交版本。目标文件若损坏会被隔离并以空配置启动。
- **稳定错误信封**：`{"error":{"code","message"}}`，错误码包括
  `no_match(404)`、`method_not_allowed(405)`、`invalid_query/invalid_header/invalid_body(400)`、
  `invalid_scenario_selector(400)`、`scenario_exhausted(500)`、
  `request_timeout(504)`、`request_cancelled(499)`、`invalid_contract(400)`、`internal_error(500)`。

## 测试

```bash
mvn test
```

覆盖：契约匹配与结构校验、状态场景与重置、按头/查询/体选择场景、延迟与错误隔离、
并发整组更新、取消与超时、重启恢复、文档一致性。
