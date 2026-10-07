package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source.InMemoryConfigSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 配置服务：持有当前生效的不可变快照，支持原子更新与回滚。
 *
 * 原子性：读取方通过 {@link #current()} 一次性拿到整个快照引用，
 * 并发更新只替换引用，因此任何一次读取只能观察到某一个完整版本。
 *
 * 失败语义：更新前先完整装载并校验，存在任何问题时整体拒绝，
 * 当前快照保持不动，旧配置继续可用；拒绝原因通过
 * {@link UpdateResult.RejectionReason} 区分。
 */
public class ConfigService {

    private final ConfigLoader loader;
    private final InMemoryConfigSource overrideSource;
    private final AtomicReference<ConfigSnapshot> current = new AtomicReference<>();
    private final Map<Long, ConfigSnapshot> history = new ConcurrentHashMap<>();
    /** 每个版本对应的覆盖集合，用于精确回滚与失败恢复 */
    private final Map<Long, Map<String, String>> overridesByVersion = new ConcurrentHashMap<>();
    private final Object updateLock = new Object();

    public ConfigService(ConfigLoader loader, InMemoryConfigSource overrideSource) {
        this.loader = loader;
        this.overrideSource = overrideSource;
    }

    /** 初始装载。初始装载允许带问题生效（缺省值兜底），但问题会记录在快照中。 */
    public void init() {
        synchronized (updateLock) {
            ConfigSnapshot snapshot = loader.load(1L);
            current.set(snapshot);
            history.put(snapshot.version(), snapshot);
            overridesByVersion.put(snapshot.version(), Map.of());
        }
    }

    public ConfigSnapshot current() {
        return current.get();
    }

    public ConfigSnapshot version(long version) {
        return history.get(version);
    }

    /**
     * 提交一组覆盖值并尝试原子生效。
     *
     * @param overrides       覆盖键值，整体替换上一版本的覆盖集合
     * @param expectedVersion 期望的当前版本；非 null 且不匹配时拒绝（VERSION_CONFLICT）
     */
    public UpdateResult applyOverrides(Map<String, String> overrides, Long expectedVersion) {
        return commit(overrides, Map.of(), expectedVersion);
    }

    /**
     * 一次提交配置覆盖与开关变更，两者要么一起进入新版本，要么整体不变。
     *
     * <p>合并规则：配置覆盖键整体替换上一版本的配置覆盖；开关键
     * （{@link ConfigLoader#FLAG_PREFIX} 前缀）在上一版本的基础上按
     * {@code flagOverrides} 做增量合并，未涉及的开关原样保留。
     *
     * <p>拒绝规则：只有与本次提交直接相关的问题（来自运行时覆盖来源，
     * 即本次提交的值本身非法）才会整体拒绝；其他来源里遗留的、与本次
     * 提交无关的问题只记录在快照中，不阻塞合法提交。
     *
     * @param configOverrides 配置覆盖键值（不含开关键），整体替换
     * @param flagOverrides   开关变更键值（flag.&lt;name&gt;.enabled/percentage），增量合并
     * @param expectedVersion 期望的当前版本；非 null 且不匹配时拒绝（VERSION_CONFLICT）
     */
    public UpdateResult commit(Map<String, String> configOverrides, Map<String, String> flagOverrides,
                               Long expectedVersion) {
        synchronized (updateLock) {
            ConfigSnapshot before = current.get();
            if (expectedVersion != null && expectedVersion != before.version()) {
                return UpdateResult.rejected(UpdateResult.RejectionReason.VERSION_CONFLICT, List.of(),
                        "expected version " + expectedVersion + " but current is " + before.version());
            }
            Map<String, String> newOverrides = new LinkedHashMap<>(configOverrides);
            // 开关键在提交间自动保留：先带上上一版本的开关覆盖，再应用本次变更
            Map<String, String> previousOverrides = overridesByVersion.getOrDefault(before.version(), Map.of());
            previousOverrides.forEach((k, v) -> {
                if (k.startsWith(ConfigLoader.FLAG_PREFIX)) {
                    newOverrides.putIfAbsent(k, v);
                }
            });
            newOverrides.putAll(flagOverrides);
            overrideSource.setValues(newOverrides);

            ConfigSnapshot candidate = loader.load(before.version() + 1);
            List<LoadIssue> blocking = candidate.issues().stream()
                    .filter(i -> i.sourceName().equals(overrideSource.name()))
                    .toList();
            if (!blocking.isEmpty()) {
                // 本次提交的值本身非法：整体拒绝，恢复覆盖来源，旧快照继续生效，绝不半生效
                restoreOverridesOf(before);
                return UpdateResult.rejected(UpdateResult.RejectionReason.VALIDATION_FAILED, blocking,
                        "commit rejected: " + blocking.size() + " issue(s) in submitted values; previous version "
                                + before.version() + " remains active");
            }
            // 其他来源遗留的问题不阻塞提交，但记录在结果与快照中保持可见
            current.set(candidate);
            history.put(candidate.version(), candidate);
            overridesByVersion.put(candidate.version(), Map.copyOf(newOverrides));
            return UpdateResult.ok(candidate.version(), candidate.issues(),
                    "applied " + configOverrides.size() + " config override(s) and "
                            + flagOverrides.size() + " flag change(s), version " + before.version()
                            + " -> " + candidate.version()
                            + (candidate.issues().isEmpty() ? ""
                            : "; " + candidate.issues().size() + " pre-existing issue(s) recorded, not blocking"));
        }
    }

    /** 回滚到历史版本：目标版本必须存在，回滚本身也生成一个新版本号。 */
    public UpdateResult rollback(long targetVersion) {
        synchronized (updateLock) {
            ConfigSnapshot before = current.get();
            ConfigSnapshot target = history.get(targetVersion);
            if (target == null) {
                return UpdateResult.rejected(UpdateResult.RejectionReason.UNKNOWN_ROLLBACK_VERSION, List.of(),
                        "no snapshot with version " + targetVersion + "; current version "
                                + before.version() + " remains active");
            }
            // 回滚通过恢复覆盖来源后重新装载完成，保证与正常更新走同一条校验路径
            restoreOverridesOf(target);
            ConfigSnapshot rolledBack = loader.load(before.version() + 1);
            List<LoadIssue> blocking = rolledBack.issues().stream()
                    .filter(i -> i.sourceName().equals(overrideSource.name()))
                    .toList();
            if (!blocking.isEmpty()) {
                restoreOverridesOf(before);
                return UpdateResult.rejected(UpdateResult.RejectionReason.VALIDATION_FAILED, blocking,
                        "rollback rejected; previous version " + before.version() + " remains active");
            }
            current.set(rolledBack);
            history.put(rolledBack.version(), rolledBack);
            overridesByVersion.put(rolledBack.version(), overridesByVersion.get(targetVersion));
            return UpdateResult.ok(rolledBack.version(), rolledBack.issues(),
                    "rolled back to values of version " + targetVersion + ", version "
                            + before.version() + " -> " + rolledBack.version());
        }
    }

    /** 让覆盖来源恢复到指定版本当时使用的覆盖集合。 */
    private void restoreOverridesOf(ConfigSnapshot snapshot) {
        overrideSource.setValues(overridesByVersion.getOrDefault(snapshot.version(), Map.of()));
    }
}
