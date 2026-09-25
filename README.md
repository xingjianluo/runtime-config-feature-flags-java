# 运行时热更新配置与功能开关（Java / Spring Boot）

本服务提供三类能力，并保证在 **HTTP 入口与内部调用入口** 下结论一致、可解释：

1. **多源配置装载与确定性优先级合并**：缺省值、类型不匹配、取值非法有明确且可区分的结论；非法配置不会半生效，也不会静默覆盖低优先级来源的有效值。
2. **配置版本化原子生效与回滚**：读操作永远看到某一个完整版本；并发更新要么整体成功、要么整体拒绝，旧版本持续可用；拒绝原因逐条可区分。
3. **功能开关按比例灰度分流**：同一（开关、标识）在任何入口、任何时间点结论一致、可复现；`0`、`100`、未知开关均有确定语义，**未知开关 fail-closed，不静默放行**。

---

## 1. 配置来源与优先级

### 1.1 来源列表（数字越大优先级越高）

| 优先级 | 来源名 | 位置 | 说明 |
|---|---|---|---|
| `Integer.MAX_VALUE` | `runtime-override` | 内存 | 由管理端 `submit` 提交的覆盖层，热更新即时生效 |
| 200 | `local-file` | `${app.config.dir}/application-local.properties` | 本地覆盖文件，缺省目录 `./config` |
| 100 | `default-file` | `${app.config.dir}/application-default.properties` | 基础环境文件 |
| 0 | `classpath-defaults` | jar 内 `config-defaults.properties` | 随包内置兜底值 |

排序规则确定：**priority 降序，同名次按来源名升序**，因此合并结果不受 JVM/文件系统遍历顺序影响。
配置目录可用系统属性切换：`-Dapp.config.dir=/etc/myapp/config`。文件缺失视为该来源"未提供任何值"，不阻止启动。

### 1.2 已注册配置键

| 键 | 类型 | 合法范围 | 内置缺省 |
|---|---|---|---|
| `app.http.timeout-ms` | int | 1..60000 | `1000`（键缺省）/`2000`（classpath） |
| `app.http.max-retries` | int | 0..10 | `3`/`2` |
| `app.resilience.circuit-enabled` | boolean | 仅 `true`/`false`（忽略大小写） | `true` |
| `feature.flags.<开关名>.percentage` | int | 0..100（动态键） | 未配置即为"未知开关" |

开关名允许字母、数字、`.`、`_`、`-`，非空。

### 1.3 合并与非法处理语义（逐键独立）

对每个键，按优先级从高到低扫描，取**第一个类型与取值均合法**的值：

- 合法：状态 `RESOLVED`，`source` 记录命中的来源，`detail` 给出判定依据。
- 高优先级值**类型不匹配**（如整数键给 `abc`、布尔键给 `yes`）：记录一条
  `status=TYPE_MISMATCH` 的问题（含来源、原始值、原因），**继续降级**到下一来源。
- 高优先级值**取值非法**（如 `max-retries=999`）：记录 `INVALID` 问题，继续降级。
- 全部来源均无有效值：使用键声明的缺省值。若此前发生过非法尝试，最终状态保留为
  `TYPE_MISMATCH`/`INVALID` 以便与"纯缺省"（`DEFAULT`）区分。
- 未注册键（如 `mystery.key`）：在**文件装载**阶段记录 `UNKNOWN_KEY` 问题并忽略，绝不进入快照值；
  在 **submit 提交**阶段会导致整次更新被拒绝（见 2.2）。

> 关键保证：高来源的坏值被隔离拒绝，不会"半生效"，也不会静默覆盖低来源的好值；
> 每条拒绝/降级都在快照 `issues` 或更新响应 `errors` 中可查。

---

## 2. 原子生效与回滚

### 2.1 版本模型

- 每次生效构建全新的不可变 `ConfigSnapshot(version, values, issues, overrideLayer)`，
  通过 `volatile` 引用发布。读取无需加锁，持有一个快照引用即锁定一个完整版本，
  一个请求内不可能读到"前一半旧值、后一半新值"。
- 版本号单调递增；最近 20 个版本保留在历史中（`GET /api/config/history`）。
- 写操作（`reload` / `submit` / `rollback`）在同一把锁上串行化。

### 2.2 `submit`：版本化原子更新

`POST /api/admin/config/submit`，请求体：

```json
{ "expectedVersion": 3, "values": { "app.http.max-retries": "5", "feature.flags.x.percentage": "20" } }
```

处理顺序：

1. **乐观锁校验**：`expectedVersion` 必须等于当前版本，否则返回
   `VERSION_CONFLICT`（HTTP 409），不做任何修改。
2. **整包校验**（任一不过即**整体拒绝**，旧版本与旧覆盖层原封不动）：
   - 空值 → `EMPTY_VALUE`
   - 未注册键 → `UNKNOWN_KEY`
   - 无法解析为声明类型 → `TYPE_MISMATCH`
   - 越界等非法取值 → `INVALID_VALUE`
3. 全部通过：整体替换内存覆盖层 → 构建并发布新版本。

### 2.3 `reload` 与 `rollback`

- `POST /api/admin/config/reload`：重新读取全部来源（含当前覆盖层）。文件装载采用
  2.3/1.3 的**逐键降级**语义（个别坏值被拒绝并记录，好值照常生效）；仅当来源本身
  I/O 失败时以 `SOURCE_IO_ERROR` 整体拒绝。
- `POST /api/admin/config/rollback/{version}`：以目标历史版本的内容（含覆盖层）
  构造并发布一个**新版本号**（不复用旧号）；目标版本已不在历史中则
  `VERSION_NOT_FOUND` 拒绝。

所有被拒绝的更新均返回 HTTP 409，响应 `errors` 逐条给出 `key/rawValue/reason/detail`，
并在 `detail` 中说明旧版本继续可用。

---

## 3. 灰度分流算法

开关比例配置键：`feature.flags.<flag>.percentage`（0..100）。

分桶（对所有入口统一，代码见 `FeatureGateService`）：

```
text   = flag + ":" + identity
digest = SHA-256(UTF-8(text))
h      = digest 前 8 字节按大端序组成的有符号 long
bucket = floorMod(h, 100)          // 0..99
enabled = bucket < percentage
```

- 哈希输入只含 `(flag, identity)`，与入口、时间、线程、JVM 哈希种子无关，
  因此同标识跨入口、跨时间结论稳定可复现。
- 判定时只取一次当前快照，结论中附带 `configVersion`。

边界语义：

| 场景 | 结论 | reason |
|---|---|---|
| `percentage = 0` | 恒关 | `ZERO_PERCENTAGE` |
| `percentage = 100` | 恒开 | `FULL_PERCENTAGE` |
| 0 < p < 100 且 `bucket < p` | 开 | `GRAY_IN` |
| 0 < p < 100 且 `bucket >= p` | 关 | `GRAY_OUT` |
| 开关未配置（未知开关） | **恒关，fail-closed** | `UNKNOWN_FLAG`（`percentage=-1`） |

判定响应示例同时给出输入与依据：

```json
{
  "flag": "ai-search", "identity": "user-2", "enabled": true,
  "percentage": 50, "bucket": 22,
  "reason": "GRAY_IN: bucket=22 < percentage=50；配置来源状态=RESOLVED [default-file]",
  "configVersion": 1
}
```

---

## 4. HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/config/snapshot` | 当前完整版本（值、来源状态、问题清单） |
| GET | `/api/config/keys/{name}` | 单键结论（404 表示该键不存在于快照） |
| GET | `/api/config/history` | 当前版本与历史版本号 |
| GET | `/api/flags/{flag}/check?identity=...` | 功能开关分流判定 |
| POST | `/api/admin/config/reload` | 重新从各来源装载 |
| POST | `/api/admin/config/submit` | 原子提交覆盖层（带乐观版本号） |
| POST | `/api/admin/config/rollback/{version}` | 回滚到历史版本 |

启动：`mvn spring-boot:run`（`server.port=0` 为随机端口；生产可自行覆盖）。

快速手工验证：

```bash
curl 'http://localhost:<port>/api/flags/new-checkout/check?identity=user-1'
curl -X POST localhost:<port>/api/admin/config/reload
curl -X POST -H 'Content-Type: application/json' \
  -d '{"expectedVersion":1,"values":{"app.http.max-retries":"5"}}' \
  localhost:<port>/api/admin/config/submit
curl -X POST localhost:<port>/api/admin/config/rollback/1
```

---

## 5. 验证方法（测试）

```bash
mvn test
```

21 个用例覆盖（断言中均打印输入值与判定依据）：

| 测试类 | 覆盖点 |
|---|---|
| `ConfigMergeTest` | 优先级胜出；高来源非法值降级且不覆盖低来源好值；`TYPE_MISMATCH`/`INVALID`/`DEFAULT` 三态可区分；未知键记录不生效 |
| `ConfigVersioningTest` | 合法提交原子推进；非法提交整体拒绝、旧版本不动、四类原因可区分；乐观版本冲突；回滚生成新版本；回滚未知版本拒绝；reload 语义 |
| `FeatureGateTest` | 0/100/未知开关边界；同 `(flag,identity)` 恒定桶；500 标识两轮判定完全一致且比例近似；bucket=49/50 边界具体输入 |
| `ConfigConcurrencyTest` | 6 读线程 × 3s（约 3500 万次完整快照读取）与 2 写线程并发：读者永远观察到内部一致的完整版本；合法提交成功、非法/冲突提交被拒、线上始终合法可用 |
| `WebEndToEndTest` | Spring 全链路：HTTP 分流入口与内部服务逐项结论一致；非法提交经 HTTP 409 拒绝且不生效；HTTP 提交+回滚；快照可解释字段 |

示例配置位于仓库 `config/` 目录，内置兜底值位于 `src/main/resources/config-defaults.properties`。
