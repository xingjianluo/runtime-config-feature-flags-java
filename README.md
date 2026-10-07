# 运行时配置与功能开关

支持运行时可热更新的配置管理与按比例灰度的功能开关。**配置与开关纳入
同一套版本**：一次提交要么配置和开关一起进入新版本，要么整体不变；
回滚到历史版本时两者一起恢复。所有入口（服务内部调用与 HTTP）对同一
开关、同一标识给出完全一致、可解释的判定结论。

## 配置来源与优先级

配置从多个本地来源装载，按优先级数值合并，**数值大者优先**：

| 优先级 | 来源 | 说明 |
|--------|------|------|
| 10 | `classpath:application.properties` | 应用自带配置 |
| 20 | `classpath:runtime-config.properties` | 运维投放的运行时配置（含初始开关） |
| 30 | 运行时覆盖（`POST /config/overrides`、`POST /commit`） | 优先级最高 |

每个配置项都在 `ConfigRegistry` 中注册，包含类型、缺省值与取值校验器。
合并时按来源优先级从低到高依次尝试，取第一个**有效**值；都没有时取缺省值。

### 开关即配置：统一的版本与回滚规则

开关以配置键形式表达，与配置项走同一条装载、校验、提交与回滚路径：

```
flag.<name>.enabled    = true|false   （缺省 false）
flag.<name>.percentage = 0..100       （缺省 0）
```

- 开关的启用状态与灰度比例跟配置项**同属一个版本快照**。`POST /commit`
  可以在一次提交里同时改配置和开关：要么一起进入新版本，要么整体不变，
  不存在只改一半的中间态。
- 回滚（`POST /config/rollback/{version}`）把配置和开关**一起**恢复到
  目标版本当时的取值；回滚本身也生成新版本号，可再次回滚。
- 初始开关由 `runtime-config.properties` 中的 `flag.*` 键播种；
  运行时开关变更（`PUT /flags`、`POST /commit`）经版本化提交生效。
- 合并规则：每次提交中，配置覆盖键**整体替换**上一版本的配置覆盖；
  开关键在上一版本基础上**增量合并**（未涉及的开关原样保留，纯配置
  提交不会弄丢开关）。

### 异常值的可区分处理

| 情况 | 问题类别 | 处理结果 |
|------|----------|----------|
| 键缺失 | —（无问题记录） | 采用缺省值 |
| 值无法解析为目标类型 | `TYPE_MISMATCH` | 丢弃该来源的该键，回退到下一优先级的有效值 |
| 类型正确但取值非法 | `INVALID_VALUE` | 同上 |
| 开关键格式错误（非 `flag.<name>.enabled\|percentage`） | `INVALID_VALUE` | 同上 |
| 来源读取失败 | `SOURCE_UNREACHABLE` | 该来源整体视为空，不影响其他来源 |

非法值**绝不半生效，也绝不静默覆盖**已有的有效值；所有问题都记录在
快照的 `issues` 中，可通过 `GET /config` 查看。

### 提交问题的分类：什么该拦，什么不该拦

提交校验把问题分成两类，避免"来源里遗留一个非法值就锁死全部热更新"：

| 分类 | 判定标准 | 处理 |
|------|----------|------|
| 与本次提交直接相关 | 问题来自运行时覆盖来源（即本次提交的值本身非法） | **整体拒绝**，旧配置继续可用，绝不半生效 |
| 别处遗留、与本次提交无关 | 问题来自其他来源（如 properties 文件里的历史非法值） | **不阻塞**合法提交；问题记录在快照 `issues` 与提交结果中保持可见 |

因此：某个来源里遗留非法值后，针对其他键的合法提交、以及**用来修正
该非法值的合法提交**都能正常生效（高优先级的合法覆盖值生效，遗留问题
仅记录）。被拒绝的提交在 HTTP 入口返回明确的 4xx（见下），而不是看着
像成功的 200。

## 原子生效与回滚

- `ConfigService` 持有不可变的 `ConfigSnapshot`（版本号 + 全量有效值
  （含开关键）+ 问题列表）。读取方一次性拿到整个快照引用，更新只替换
  引用，因此**并发读写下任何一次读取只能观察到某一个完整版本**。
- 每次成功生效生成一个递增版本号，历史版本全部保留。
- 拒绝原因可区分：
  - `VALIDATION_FAILED`（HTTP 422）：本次提交的值未通过校验；
  - `VERSION_CONFLICT`（HTTP 409）：提交的 `expectedVersion` 与当前版本不一致；
  - `UNKNOWN_ROLLBACK_VERSION`（HTTP 404）：回滚目标版本不存在。

## 入口一致性语义

- 服务内部调用（`FeatureFlagService.decide`）与 HTTP 入口
  （`GET /flags/{name}/check`）**共用同一条判定路径与同一份快照**，
  对同一开关、同一标识给出的放行结果、桶号、判定原因和依据的版本号
  完全一致。
- **未知开关在两个入口都明确拒绝**：`allowed=false` 且
  `reason=UNKNOWN_FLAG`，绝不静默放行；禁用开关标记 `FLAG_DISABLED`。
- 热更新并发下，每次判定先一次性拿到整个快照引用，因此只能看到某一个
  完整版本，不会看到新旧混合的中间态。
- 每次判定返回 `FlagDecision`：是否放行、桶号、比例、**依据的版本号**、
  原因、可读说明，结论可解释、可对账。

## 分流算法

`bucket = floorMod(SHA-256(flag + ":" + identifier) 前 8 字节, 100)`，
`bucket < percentage` 时放行。

- 哈希只依赖 `(flag, identifier)`，与进程、入口、时间无关，因此同一标识在
  任何入口、任何时间点得到**一致且可复现**的结论。
- 边界语义：`percentage = 0` 一律不放行；`100` 一律放行；比例超出 `[0,100]`
  在定义期即抛出 `IllegalArgumentException`，提交期记为 `INVALID_VALUE`。

## API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/config` | 当前生效快照（版本、值含开关键、问题列表） |
| POST | `/config/overrides?expectedVersion=` | 提交配置覆盖（整体替换配置覆盖；开关自动保留） |
| POST | `/commit?expectedVersion=` | 统一提交 `{"config": {...}, "flags": {"name": {"enabled","percentage"}}}`，配置与开关同版本原子生效 |
| POST | `/config/rollback/{version}` | 回滚到指定版本（配置与开关一起恢复） |
| GET | `/flags` | 当前生效的全部开关及版本号 |
| GET | `/flags/{name}/check?id=` | 查询某标识的分流结论（含版本号） |
| PUT | `/flags?expectedVersion=` | 版本化提交单个开关 `{"name","enabled","percentage"}` |

提交类接口：被接受返回 200；被拒绝返回 422 / 409 / 404，响应体中
`accepted=false` 并携带拒绝原因与问题列表。

## 验证方法

```bash
mvn test
```

测试覆盖（断言输出均打印输入、判定依据与结论）：

- `ConfigLoaderTest`：多来源合并优先级；缺省值；`TYPE_MISMATCH` / `INVALID_VALUE` /
  `SOURCE_UNREACHABLE` 的可区分处理与回退。
- `ConfigServiceTest`：合法更新原子生效并递增版本；非法更新整体拒绝且旧快照
  原样保留（同一引用）；过期 `expectedVersion` 报 `VERSION_CONFLICT`；回滚恢复
  历史值、未知版本报 `UNKNOWN_ROLLBACK_VERSION`；4 写 8 读并发下零不一致观察；
  并发非法更新不破坏当前配置。
- `UnifiedVersioningTest`：配置与开关一次提交同版本生效；回滚后两者一起恢复；
  判定结论携带版本号/比例/桶号；纯配置提交不改动既有开关。
- `EntryConsistencyTest`（Spring Boot + MockMvc）：服务内部调用与 HTTP 入口对
  同一标识的结论逐字段一致；未知开关在两个入口都明确拒绝；非法提交在 HTTP 上
  返回 422 且旧值仍可用；热更新并发下两个入口只观察到完整版本。
- `LegacyInvalidValueTest`：来源里遗留非法值不阻塞无关的合法提交；用于修正
  遗留非法值的合法提交正常生效；与本次提交直接相关的非法值照旧整体拒绝、
  绝不半生效。
- `FeatureFlagServiceTest`：同一标识跨并发入口结论一致且桶号可复现；
  比例 0/100 边界语义；未知开关与禁用开关显式拒绝；2 万标识下放行比例
  接近设定值（25% ± 3%）；非法比例在定义期被拒绝。

手工验证（启动后，端口见启动日志，`server.port=0` 表示随机端口）：

```bash
mvn spring-boot:run
curl -s localhost:8080/config
# 一次提交同时改配置和开关，同版本原子生效
curl -s -X POST localhost:8080/commit -H 'Content-Type: application/json' \
     -d '{"config":{"service.thread-pool.size":"64"},"flags":{"new-checkout":{"name":"new-checkout","enabled":true,"percentage":50}}}'
# 非法提交：返回 422，旧配置继续可用
curl -s -X POST localhost:8080/config/overrides -H 'Content-Type: application/json' \
     -d '{"service.thread-pool.size":"not-a-number"}'
# 回滚：配置和开关一起回到该版本
curl -s -X POST localhost:8080/config/rollback/1
# 两个入口结论一致：内部 FeatureFlagService.decide 与此 HTTP 调用
curl -s 'localhost:8080/flags/new-checkout/check?id=user-42'
# 未知开关：明确拒绝
curl -s 'localhost:8080/flags/no-such-flag/check?id=user-42'
```

## 兼容范围

- 需要 JDK 21 与 Maven；依赖 Spring Boot 3.4.1（web / test）。
- 配置来源为本地 properties（classpath / 文件系统）与内存覆盖来源；
  未接入外部配置中心，但 `ConfigSource` 接口可自行扩展。
- 历史版本快照保存在进程内存中，重启后不保留；初始状态由各来源重新装载。
- 开关分桶算法（SHA-256 取模）跨版本稳定：同一 `(flag, identifier)` 的
  桶号不随版本变化，比例调整只改变放行阈值。
- `UpdateResult` / `FlagDecision` 的 JSON 字段为对外契约；`FlagDecision`
  本轮新增 `version` 字段（只增不改）。
