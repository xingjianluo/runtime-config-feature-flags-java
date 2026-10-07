# 运行时配置与功能开关

支持运行时可热更新的配置管理与按比例灰度的功能开关。**配置与开关纳入同一套
版本**：一次提交要么一起进入新版本、要么整体不变；回滚时两者一起回到历史取值；
服务内部调用与 HTTP 请求对同一标识给出完全一致、可解释的分流结论。

## 配置来源与优先级

配置从多个本地来源装载，按优先级数值合并，**数值大者优先**：

| 优先级 | 来源 | 说明 |
|--------|------|------|
| 10 | `classpath:application.properties` | 应用自带配置 |
| 20 | `classpath:runtime-config.properties` | 运维投放的运行时配置 |
| 30 | 运行时覆盖（经提交接口写入） | 优先级最高，是唯一的“提交通道” |

每个配置项都在 `ConfigRegistry` 中注册，包含类型、缺省值与取值校验器。
合并时按来源优先级从低到高依次尝试，取第一个**有效**值；都没有时取缺省值。

### 异常值的可区分处理

| 情况 | 问题类别 | 处理结果 |
|------|----------|----------|
| 键缺失 | —（无问题记录） | 采用缺省值 |
| 值无法解析为目标类型 | `TYPE_MISMATCH` | 丢弃该来源的该键，回退到下一优先级的有效值 |
| 类型正确但取值非法 | `INVALID_VALUE` | 同上 |
| 来源读取失败 | `SOURCE_UNREACHABLE` | 该来源整体视为空，不影响其他来源 |

非法值**绝不半生效，也绝不静默覆盖**已有的有效值；所有问题都记录在
当前快照的 `issues` 中，可通过 `GET /runtime/state`（或 `GET /config`）查看。

## 统一版本与原子提交

`ConfigService` 持有不可变的 `RuntimeSnapshot`：**版本号 + 全量有效配置
（`ConfigSnapshot`）+ 全量开关定义**。

- 一次统一提交（`POST /runtime/commit`）携带 `configOverrides` 与 `flags` 两部分，
  在同一把锁内完成装载、校验与快照引用替换：**配置和开关要么一起进入新版本，
  要么整体不变**，不存在只改一半的中间态。
- 读取方（含分流判定）一次性拿到整个快照引用；热更新进行中并发发起的判定，
  只会看到某一个完整版本，不可能看到“新配置 + 旧开关”的混合态。
- 每次成功提交生成一个递增版本号，历史版本全部保留。字段为 `null` 的维度表示
  本次不改该维度（沿用当前版本）。
- **回滚**（`POST /config/rollback/{version}`）把配置覆盖集合与开关定义一起恢复到
  目标版本当时的取值；回滚本身也走同一校验路径并生成新版本号，可再次回滚。

### 提交问题的分类：直接相关 vs. 别处遗留

装载时发现的问题按“是否与本次提交直接相关”区分对待：

- **直接相关（阻断提交）**：问题出在提交通道（优先级 30 的运行时覆盖来源）上，
  或提交携带了非法开关定义（如比例越界、同次提交内开关重名）。这类问题照旧
  **整体拒绝**，当前快照保持不动，旧配置/旧开关继续可用，非法提交绝不只生效一半。
- **别处遗留（不阻断）**：问题出在应用自带文件、运维投放文件等非提交来源上，
  是早就遗留、与本次提交无关的问题。这类问题**不再拖累合法提交**——本次提交的
  合法值可以正常生效；遗留问题仍然完整记录在快照 `issues` 中可被发现与清理。

这样修掉了“某来源遗留一个非法值后，之后无论提交什么（包括用来修正的合法值）
都被整体挡回、热更新被锁死”的问题。

### 入口上的拒绝表现

被拒绝的提交在 HTTP 层以明确的 4xx 状态表达，响应体始终带 `accepted=false`，
不会“看着像成功返回”：

| 拒绝原因（`UpdateResult.reason`） | HTTP 状态 | 含义 |
|-----------------------------------|-----------|------|
| `VALIDATION_FAILED` | 422 | 本次提交含类型不匹配 / 取值非法 / 非法开关定义 |
| `VERSION_CONFLICT` | 409 | `expectedVersion` 与当前版本不一致（并发冲突） |
| `UNKNOWN_ROLLBACK_VERSION` | 404 | 回滚目标版本不存在 |
| `NONE` | 200 | 已接受并生效（`accepted=true`，带新版本号） |

## 两个入口的一致性语义

分流逻辑只有一份实现（`FeatureFlagService.decide`）：服务内部代码调用与
HTTP 接口 `GET /flags/{name}/check` 都委托给它；开关服务绑定到
`ConfigService` 的当前快照，开关定义与版本号全部来自该快照。

对**同一个开关、同一个标识**，两个入口给出的以下字段**完全一致**：

- 是否放行 `allowed`；
- 桶号 `bucket`；
- 判定原因 `reason`；
- 依据的比例档 `percentage`；
- 依据的统一版本号 `version`。

`version` 即该次判定所依据的 `RuntimeSnapshot` 版本；`percentage` 是判定时该开关
所处的比例档；`bucket` 是标识落入的桶号，结论可追溯。热更新并发进行时，每次
判定都与它所依据的那个完整版本自洽（`allowed == bucket < percentage`）。

### 分流算法

`bucket = floorMod(SHA-256(flag + ":" + identifier) 前 8 字节, 100)`，
`bucket < percentage` 时放行。

- 哈希只依赖 `(flag, identifier)`，与进程、入口、时间无关，因此同一标识在
  任何入口、任何时间点得到**一致且可复现**的桶号。
- 边界语义：`percentage = 0` 一律不放行（`OUTSIDE_PERCENTAGE`）；`100` 一律放行
  （`WITHIN_PERCENTAGE`）；比例超出 `[0,100]` 在开关定义期即被拒绝。
- **未知开关在两个入口都一律不放行**，原因显式标记为 `UNKNOWN_FLAG`
  （`bucket=-1`、`percentage=null`），绝不悄悄放行；禁用开关标记 `FLAG_DISABLED`。

## API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/runtime/commit?expectedVersion=` | **统一提交**：`{"configOverrides":{...},"flags":[{"name","enabled","percentage"}]}`，配置与开关原子生效；字段可省略（不改该维度） |
| GET | `/runtime/state` | 当前统一版本：版本号、配置值、问题列表、开关定义 |
| GET | `/config` | 当前配置快照（版本、值、问题列表） |
| POST | `/config/overrides?expectedVersion=` | 仅提交配置覆盖（等价于只带 `configOverrides` 的统一提交） |
| POST | `/config/rollback/{version}` | 回滚到指定版本（配置与开关一起恢复） |
| GET | `/flags` | 当前版本的开关定义与版本号 |
| GET | `/flags/{name}/check?id=` | 查询某标识的分流结论（含 version/bucket/percentage/reason） |
| PUT | `/flags?expectedVersion=` | 新增/更新单个开关：并入当前其它开关后走统一提交（兼容入口） |

## 验证方法

```bash
mvn test
```

自动化测试（25 个用例，断言与运行日志均打印输入、判定依据与结论）：

- `RuntimeVersioningTest`（本轮新增）：
  - 配置与开关一次提交同版本生效，判定结论带依据版本号；
  - 含非法值的提交整体拒绝、旧配置与旧开关保持同一旧版本（同一快照引用），
    不出现只改一半；
  - 按版本回滚后配置与开关一起恢复，判定依据为回滚后的新版本；
  - **别的来源遗留非法值不再阻塞合法提交**，修正值正常生效；与本次提交直接
    相关的非法值照旧整体拒绝；
  - 热更新与分流判定并发：所有判定只依据真实存在的完整版本，配置与开关配套，
    零新旧混合观察。
- `EntryConsistencyHttpTest`（本轮新增，真实 HTTP + 随机端口）：
  - 服务内部调用与 HTTP 入口对同一标识的 `allowed/bucket/percentage/reason/version`
    逐项一致；
  - 未知开关两入口都显式拒绝（`UNKNOWN_FLAG`）；
  - 经 HTTP 统一提交热更新后两入口同步进入新版本；热更新并发期间两入口各自
    依据完整版本、自洽且桶号稳定；
  - 非法提交经 HTTP 返回 422、响应体 `accepted=false`，版本与旧值不变。
- `ConfigLoaderTest` / `ConfigServiceTest` / `FeatureFlagServiceTest`（既有用例，
  保持全绿、无回归）：多来源合并、异常值可区分处理、版本化原子生效、版本冲突、
  回滚、并发读写、分流一致性、边界比例、未知开关拒绝等。

手工验证（启动后）：

```bash
mvn spring-boot:run
curl -s localhost:8080/runtime/state
# 配置与开关一次提交，一起进入新版本
curl -s -X POST localhost:8080/runtime/commit -H 'Content-Type: application/json' -d '{
  "configOverrides": {"service.thread-pool.size":"64"},
  "flags": [
    {"name":"new-checkout","enabled":true,"percentage":100},
    {"name":"beta-search","enabled":true,"percentage":0},
    {"name":"legacy-report","enabled":false,"percentage":100}
  ]
}'
# 判定结论带 version/bucket/percentage/reason；服务内部调用同一方法得到同一结论
curl -s 'localhost:8080/flags/new-checkout/check?id=user-42'
curl -s 'localhost:8080/flags/no-such-flag/check?id=user-42'      # UNKNOWN_FLAG，拒绝
# 非法提交：HTTP 422，body 中 accepted=false，旧版本继续可用
curl -i -X POST localhost:8080/runtime/commit -H 'Content-Type: application/json' -d '{
  "configOverrides": {"service.thread-pool.size":"not-a-number"}
}'
# 回滚：配置与开关一起回到版本 1 当时的取值（回滚本身生成新版本）
curl -s -X POST localhost:8080/config/rollback/1
```

## 兼容范围

- Java 21、Spring Boot 3.4.x；构建：`mvn test` / `mvn spring-boot:run`。
- 沿用的既有入口语义不变：`GET /config`、`POST /config/overrides`、
  `POST /config/rollback/{version}`、`GET /flags/{name}/check` 路径与请求体保持
  兼容；`POST /config/overrides` 与 `POST /config/rollback/{version}` 的响应由
  “直接 200 返回结果体”改为**按结果返回 2xx/4xx**（拒绝时为 422/409/404，
  响应体字段不变并显式带 `accepted`），依赖 2xx 判定成功的调用方语义更清晰，
  但原先不看状态码、只读响应体 `accepted` 的调用不受影响。
- `PUT /flags` 由“旁路直接写开关”改为**走统一提交**，成功时返回 200 与
  `UpdateResult`（含版本号），而非回显开关对象；非法/冲突时返回 422/409。
  需要批量、原子地同时改配置和开关，请使用 `POST /runtime/commit`。
- `FeatureFlagService` 保留独立模式构造器与 `upsert`，供不接入统一版本的轻量
  使用与单测；在 Spring 装配中它被绑定到统一快照，绑定后调用 `upsert` 会抛出
  `UnsupportedOperationException`（开关变更必须经统一提交）。
- 统一版本自本轮起建立；历史版本回滚依赖进程内保留的版本历史（重启后清空），
  配置来源仍为本地 classpath/文件/内存，不含跨进程持久化。
