package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source.InMemoryConfigSource;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlag;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.runtime.CommitRequest;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.runtime.RuntimeSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 运行时统一版本服务：持有当前生效的不可变 {@link RuntimeSnapshot}，
 * 配置与开关在同一套版本下原子生效、原子回滚。
 *
 * 原子性：读取方通过 {@link #state()}（或 {@link #current()}）一次性拿到
 * 整个快照引用，提交在同一把锁内完成装载、校验与引用替换，因此任何一次
 * 读取只能观察到某一个完整版本，不可能出现配置已更新而开关仍旧值
 * （或反之）的中间态。
 *
 * 失败语义：提交前先完整装载并校验；与本次提交直接相关的问题
 * （提交所写入的覆盖来源上的问题、或提交携带的非法开关）会整体拒绝，
 * 当前快照保持不动，旧配置/旧开关继续可用，绝不只生效一半。
 * 其它来源上早就遗留、与本次提交无关的问题不再拖累合法提交。
 */
public class ConfigService {

    private final ConfigLoader loader;
    private final InMemoryConfigSource overrideSource;
    private final List<FeatureFlag> initialFlags;
    private final AtomicReference<RuntimeSnapshot> current = new AtomicReference<>();
    private final Map<Long, RuntimeSnapshot> history = new ConcurrentHashMap<>();
    /** 每个版本对应的覆盖集合，用于精确回滚与失败恢复 */
    private final Map<Long, Map<String, String>> overridesByVersion = new ConcurrentHashMap<>();
    private final Object updateLock = new Object();

    public ConfigService(ConfigLoader loader, InMemoryConfigSource overrideSource) {
        this(loader, overrideSource, List.of());
    }

    public ConfigService(ConfigLoader loader, InMemoryConfigSource overrideSource,
                         List<FeatureFlag> initialFlags) {
        this.loader = loader;
        this.overrideSource = overrideSource;
        this.initialFlags = List.copyOf(initialFlags);
    }

    /** 初始装载。初始装载允许带遗留问题生效（缺省值兜底），问题记录在快照中。 */
    public void init() {
        synchronized (updateLock) {
            ConfigSnapshot config = loader.load(1L);
            Map<String, FeatureFlag> flags = new LinkedHashMap<>();
            for (FeatureFlag flag : initialFlags) {
                flags.put(flag.name(), flag);
            }
            RuntimeSnapshot snapshot = new RuntimeSnapshot(config, flags);
            current.set(snapshot);
            history.put(snapshot.version(), snapshot);
            overridesByVersion.put(snapshot.version(), Map.of());
        }
    }

    // ---- 读取：统一快照 / 配置视图 ----

    /** 当前生效的统一快照（配置 + 开关，同一版本）。 */
    public RuntimeSnapshot state() {
        return current.get();
    }

    public ConfigSnapshot current() {
        return current.get().config();
    }

    public RuntimeSnapshot runtimeVersion(long version) {
        return history.get(version);
    }

    public ConfigSnapshot version(long version) {
        RuntimeSnapshot snapshot = history.get(version);
        return snapshot == null ? null : snapshot.config();
    }

    // ---- 提交 ----

    /**
     * 提交一组配置覆盖值并尝试原子生效（仅配置维度的便捷入口，
     * 语义等价于一次只带配置的统一提交）。
     */
    public UpdateResult applyOverrides(Map<String, String> overrides, Long expectedVersion) {
        return commit(new CommitRequest(overrides, null), expectedVersion);
    }

    /**
     * 统一提交：配置覆盖与开关定义要么一起进入新版本，要么整体不变。
     *
     * @param request         提交内容；字段为 null 表示该维度沿用当前版本
     * @param expectedVersion 期望的当前版本；非 null 且不匹配时拒绝（VERSION_CONFLICT）
     */
    public UpdateResult commit(CommitRequest request, Long expectedVersion) {
        synchronized (updateLock) {
            RuntimeSnapshot before = current.get();
            if (expectedVersion != null && expectedVersion != before.version()) {
                return UpdateResult.rejected(UpdateResult.RejectionReason.VERSION_CONFLICT, List.of(),
                        "expected version " + expectedVersion + " but current is " + before.version());
            }

            // 1. 先在本地构造候选开关集合，非法定义（如比例越界）整体拒绝，不触碰现场
            Map<String, FeatureFlag> candidateFlags;
            try {
                candidateFlags = resolveCandidateFlags(before, request);
            } catch (IllegalArgumentException e) {
                return UpdateResult.rejected(UpdateResult.RejectionReason.VALIDATION_FAILED, List.of(),
                        "update rejected: invalid flag definition: " + e.getMessage()
                                + "; previous version " + before.version() + " remains active");
            }

            // 2. 暂存旧覆盖集合；仅当本次确实携带配置覆盖时才改写覆盖来源
            Map<String, String> previousOverrides =
                    overridesByVersion.getOrDefault(before.version(), Map.of());
            boolean touchesConfig = request.touchesConfig();
            if (touchesConfig) {
                overrideSource.setValues(request.configOverrides());
            }
            ConfigSnapshot candidateConfig = loader.load(before.version() + 1);

            // 3. 区分“与本次提交直接相关的问题”与“别处遗留问题”：
            //    只有提交所写覆盖来源上的问题才阻断本次提交；
            //    其它来源遗留的问题不应拖累本次合法值生效。
            List<LoadIssue> blocking = blockingIssuesOf(candidateConfig);
            if (!blocking.isEmpty()) {
                if (touchesConfig) {
                    overrideSource.setValues(previousOverrides);
                }
                return UpdateResult.rejected(UpdateResult.RejectionReason.VALIDATION_FAILED, blocking,
                        "update rejected: " + blocking.size() + " blocking issue(s) in this commit; "
                                + "previous version " + before.version() + " remains active");
            }

            // 4. 全部通过：配置与开关作为同一个新版本整体替换
            RuntimeSnapshot next = new RuntimeSnapshot(candidateConfig, candidateFlags);
            current.set(next);
            history.put(next.version(), next);
            overridesByVersion.put(next.version(),
                    Map.copyOf(touchesConfig ? request.configOverrides() : previousOverrides));
            return UpdateResult.ok(next.version(), candidateConfig.issues(),
                    "commit applied, version " + before.version() + " -> " + next.version());
        }
    }

    /** 回滚到历史版本：配置与开关一起回到该版本当时的取值；回滚本身生成新版本号。 */
    public UpdateResult rollback(long targetVersion) {
        synchronized (updateLock) {
            RuntimeSnapshot before = current.get();
            RuntimeSnapshot target = history.get(targetVersion);
            if (target == null) {
                return UpdateResult.rejected(UpdateResult.RejectionReason.UNKNOWN_ROLLBACK_VERSION, List.of(),
                        "no snapshot with version " + targetVersion + "; current version "
                                + before.version() + " remains active");
            }
            Map<String, String> previousOverrides =
                    overridesByVersion.getOrDefault(before.version(), Map.of());
            Map<String, String> targetOverrides =
                    overridesByVersion.getOrDefault(targetVersion, Map.of());
            overrideSource.setValues(targetOverrides);
            ConfigSnapshot rolledConfig = loader.load(before.version() + 1);
            List<LoadIssue> blocking = blockingIssuesOf(rolledConfig);
            if (!blocking.isEmpty()) {
                overrideSource.setValues(previousOverrides);
                return UpdateResult.rejected(UpdateResult.RejectionReason.VALIDATION_FAILED, blocking,
                        "rollback rejected; previous version " + before.version() + " remains active");
            }
            RuntimeSnapshot rolled = new RuntimeSnapshot(rolledConfig, target.flags());
            current.set(rolled);
            history.put(rolled.version(), rolled);
            overridesByVersion.put(rolled.version(), targetOverrides);
            return UpdateResult.ok(rolled.version(), rolledConfig.issues(),
                    "rolled back to values of version " + targetVersion + ", version "
                            + before.version() + " -> " + rolled.version());
        }
    }

    // ---- 内部 ----

    private Map<String, FeatureFlag> resolveCandidateFlags(RuntimeSnapshot before, CommitRequest request) {
        if (!request.touchesFlags()) {
            return before.flags();
        }
        Map<String, FeatureFlag> candidate = new LinkedHashMap<>();
        for (FeatureFlag flag : request.flags()) {
            // FeatureFlag 构造器已对比例做 [0,100] 校验；重名键直接拒绝
            if (candidate.put(flag.name(), flag) != null) {
                throw new IllegalArgumentException("duplicate flag name in commit: " + flag.name());
            }
        }
        return candidate;
    }

    /**
     * 挑出与本次提交直接相关、确实该拦下的问题：即提交所写覆盖来源上的问题。
     * 其它来源（应用自带 / 运维投放文件等）上的遗留问题与本次提交无关，
     * 不阻断本次合法值生效；它们仍完整保留在快照 issues 中可被查询。
     */
    private List<LoadIssue> blockingIssuesOf(ConfigSnapshot candidate) {
        return candidate.issues().stream()
                .filter(issue -> overrideSource.name().equals(issue.sourceName()))
                .toList();
    }
}
