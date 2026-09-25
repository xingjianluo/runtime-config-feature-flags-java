# 运行时配置与功能开关

支持运行时可热更新的配置管理与按比例灰度的功能开关，保证配置变更、
异常回滚与开关分流在各入口下给出一致且可解释的结论。

## 配置来源与优先级

配置从多个本地来源装载，按优先级数值合并，**数值大者优先**：

| 优先级 | 来源 | 说明 |
|--------|------|------|
| 10 | `classpath:application.properties` | 应用自带配置 |
| 20 | `classpath:runtime-config.properties` | 运维投放的运行时配置 |
| 30 | 运行时覆盖（`POST /config/overrides`） | 优先级最高 |

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
快照的 `issues` 中，可通过 `GET /config` 查看。

## 原子生效与回滚

- `ConfigService` 持有不可变的 `ConfigSnapshot`（版本号 + 全量有效值 + 问题列表）。
  读取方一次性拿到整个快照引用，更新只替换引用，因此**并发读写下任何一次
  读取只能观察到某一个完整版本**。
- 更新（`POST /config/overrides`）先完整装载并校验，**存在任何问题时整体拒绝**，
  旧快照原样保留、继续可用。拒绝原因可区分：
  - `VALIDATION_FAILED`：新配置含类型不匹配 / 取值非法 / 来源不可达；
  - `VERSION_CONFLICT`：提交的 `expectedVersion` 与当前版本不一致（并发更新冲突）；
  - `UNKNOWN_ROLLBACK_VERSION`：回滚目标版本不存在。
- 每次成功生效生成一个递增版本号，历史版本全部保留。
- 回滚（`POST /config/rollback/{version}`）恢复目标版本的覆盖集合并重新装载，
  与正常更新走同一条校验路径；回滚本身也生成新版本号，可再次回滚。

## 分流算法

`bucket = floorMod(SHA-256(flag + ":" + identifier) 前 8 字节, 100)`，
`bucket < percentage` 时放行。

- 哈希只依赖 `(flag, identifier)`，与进程、入口、时间无关，因此同一标识在
  任何入口、任何时间点得到**一致且可复现**的结论。
- 边界语义：`percentage = 0` 一律不放行；`100` 一律放行；比例超出 `[0,100]`
  在定义期即抛出 `IllegalArgumentException`。
- **未知开关一律不放行**，结论显式标记 `UNKNOWN_FLAG`，绝不静默放行；
  禁用开关标记 `FLAG_DISABLED`。
- 每次判定返回 `FlagDecision`（是否放行、桶号、比例、原因、可读说明），
  结论可解释。

## API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/config` | 当前生效快照（版本、值、问题列表） |
| POST | `/config/overrides?expectedVersion=` | 提交覆盖更新（整体替换覆盖集合） |
| POST | `/config/rollback/{version}` | 回滚到指定版本 |
| GET | `/flags/{name}/check?id=` | 查询某标识的分流结论 |
| PUT | `/flags` | 新增/更新开关定义 `{"name","enabled","percentage"}` |

## 验证方法

```bash
mvn test
```

测试覆盖（断言输出均打印输入与判定依据）：

- `ConfigLoaderTest`：多来源合并优先级；缺省值；`TYPE_MISMATCH` / `INVALID_VALUE` /
  `SOURCE_UNREACHABLE` 的可区分处理与回退。
- `ConfigServiceTest`：合法更新原子生效并递增版本；非法更新整体拒绝且旧快照
  原样保留（同一引用）；过期 `expectedVersion` 报 `VERSION_CONFLICT`；回滚恢复
  历史值、未知版本报 `UNKNOWN_ROLLBACK_VERSION`；4 写 8 读并发下零不一致观察；
  并发非法更新不破坏当前配置。
- `FeatureFlagServiceTest`：同一标识跨并发入口结论一致且桶号可复现；
  比例 0/100 边界语义；未知开关与禁用开关显式拒绝；2 万标识下放行比例
  接近设定值（25% ± 3%）；非法比例在定义期被拒绝。

手工验证（启动后）：

```bash
mvn spring-boot:run
curl -s localhost:8080/config
curl -s -X POST localhost:8080/config/overrides -H 'Content-Type: application/json' \
     -d '{"service.thread-pool.size":"64"}'
curl -s -X POST localhost:8080/config/overrides -H 'Content-Type: application/json' \
     -d '{"service.thread-pool.size":"not-a-number"}'   # 被拒绝，旧配置继续可用
curl -s -X POST localhost:8080/config/rollback/1
curl -s 'localhost:8080/flags/new-checkout/check?id=user-42'
```
