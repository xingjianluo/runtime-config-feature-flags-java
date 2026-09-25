package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source.ConfigSource;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source.InMemoryConfigSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 运行时配置服务。
 *
 * <h3>合并规则（确定性）</h3>
 * <ol>
 *   <li>来源按 priority 降序、name 升序排序，顺序完全确定。</li>
 *   <li>逐键从高优先级到低优先级寻找第一个“类型与取值均合法”的值；
 *       高来源的非法值会被明确记录为 {@link KeyIssue} 并降级到下一来源，
 *       不会半生效，也不会静默覆盖低来源的有效值。</li>
 *   <li>所有来源均无有效值时使用键声明的缺省值，状态为 DEFAULT；
 *       若曾有非法尝试，最终状态保留为 TYPE_MISMATCH/INVALID 以便区分。</li>
 *   <li>未注册键在装载阶段记入问题清单（UNKNOWN_KEY），不进入快照值。</li>
 * </ol>
 *
 * <h3>原子生效与回滚</h3>
 * <ul>
 *   <li>每次生效构建全新不可变 {@link ConfigSnapshot}，通过 volatile 引用发布，
 *       读操作无锁，保证一次请求只观察到某一个完整版本。</li>
 *   <li>{@link #submit} 为版本化原子更新：先做乐观版本校验，再对全部值整体校验，
 *       任一值非法则整体拒绝（拒绝原因逐条可区分），旧版本继续可用。</li>
 *   <li>{@link #rollback} 用历史快照内容构造新版本原子生效，并同步还原覆盖层。</li>
 *   <li>写操作在同一把锁上串行化，版本号单调递增。</li>
 * </ul>
 */
public final class ConfigService {

    /** 运行时提交覆盖层的固定名称与最高优先级。 */
    public static final String OVERRIDE_SOURCE_NAME = "runtime-override";
    public static final int OVERRIDE_PRIORITY = Integer.MAX_VALUE;

    private static final int HISTORY_LIMIT = 20;

    private final ConfigRegistry registry;
    private final List<ConfigSource> sources;
    private final InMemoryConfigSource overrideSource;
    private final Object writeLock = new Object();

    private volatile ConfigSnapshot current;
    // 版本号 -> 历史快照（含当前版本）。跳表支持高并发无锁读历史。
    private final NavigableMap<Long, ConfigSnapshot> history = new ConcurrentSkipListMap<>();
    private long nextVersion = 1;

    public ConfigService(ConfigRegistry registry, List<ConfigSource> sources) {
        this.registry = registry;
        List<ConfigSource> ordered = new ArrayList<>(sources);
        ordered.sort(Comparator.comparingInt(ConfigSource::priority).reversed()
                .thenComparing(ConfigSource::name));
        this.sources = List.copyOf(ordered);
        this.overrideSource = new InMemoryConfigSource(OVERRIDE_SOURCE_NAME, OVERRIDE_PRIORITY, Map.of());
    }

    /** 启动时装载第一个版本（装载采用逐键降级语义，不会因单个坏值失败）。 */
    public ConfigSnapshot initialize() {
        synchronized (writeLock) {
            if (current == null) {
                publish(buildSnapshot(List.copyOf(this.sources)));
            }
            return current;
        }
    }

    public ConfigSnapshot current() {
        ConfigSnapshot c = current;
        if (c == null) {
            throw new IllegalStateException("ConfigService 尚未 initialize");
        }
        return c;
    }

    /**
     * 从全部来源重新装载并原子生效。
     *
     * <p>装载语义与启动一致：逐键独立降级，非法值被拒绝并记录在问题清单，
     * 绝不半生效或覆盖有效值；仅当来源读取本身发生 I/O 错误时整体拒绝。
     */
    public UpdateResult reload() {
        synchronized (writeLock) {
            long expected = current.version();
            List<ConfigSource> all = sourcesWithOverride();
            ConfigSnapshot candidate;
            try {
                candidate = buildSnapshot(all);
            } catch (SourceReadFailure f) {
                return UpdateResult.rejected(expected, List.of(new ValidationError(
                        f.sourceKey(), f.rawValue(), RejectReason.SOURCE_IO_ERROR, f.getMessage())));
            }
            publish(candidate);
            return UpdateResult.accepted(current.version(),
                    "已从 " + all.size() + " 个来源重新装载并原子生效；问题 "
                            + candidate.issues().size() + " 条（见快照 issues）");
        }
    }

    /**
     * 提交完整覆盖层（最高优先级、内存态）。
     *
     * <p>原子语义：全部值校验通过后才整体替换覆盖层并构建新版本；
     * 任一值非法或版本不匹配则整体拒绝，旧版本与旧覆盖层原封不动。
     */
    public UpdateResult submit(Map<String, String> values, long expectedVersion) {
        synchronized (writeLock) {
            long cur = current.version();
            if (expectedVersion != cur) {
                return UpdateResult.rejected(cur, List.of(new ValidationError(
                        "*", null, RejectReason.VERSION_CONFLICT,
                        "expectedVersion=" + expectedVersion + " 与当前版本 " + cur + " 不一致")));
            }
            Map<String, String> next = values == null ? Map.of() : values;
            List<ValidationError> errors = new ArrayList<>();
            for (Map.Entry<String, String> e : next.entrySet()) {
                String key = e.getKey();
                String raw = e.getValue();
                if (raw == null || raw.isBlank()) {
                    errors.add(new ValidationError(key, raw, RejectReason.EMPTY_VALUE,
                            "提交值不允许为空白"));
                    continue;
                }
                Optional<ConfigKey> resolved = registry.find(key);
                if (resolved.isEmpty()) {
                    errors.add(new ValidationError(key, raw, RejectReason.UNKNOWN_KEY,
                            "未注册的配置键"));
                    continue;
                }
                ConfigKey.ParseResult pr = resolved.get().parse(raw);
                if (pr.status() == ResolveStatus.TYPE_MISMATCH) {
                    errors.add(new ValidationError(key, raw, RejectReason.TYPE_MISMATCH, pr.detail()));
                } else if (pr.status() == ResolveStatus.INVALID) {
                    errors.add(new ValidationError(key, raw, RejectReason.INVALID_VALUE, pr.detail()));
                }
            }
            if (!errors.isEmpty()) {
                // 整体拒绝：覆盖层与当前版本均不改变。
                return UpdateResult.rejected(cur, errors);
            }
            overrideSource.replace(next);
            ConfigSnapshot candidate;
            try {
                candidate = buildSnapshot(sourcesWithOverride());
            } catch (SourceReadFailure f) {
                return UpdateResult.rejected(cur, List.of(new ValidationError(
                        f.sourceKey(), f.rawValue(), RejectReason.SOURCE_IO_ERROR, f.getMessage())));
            }
            publish(candidate);
            return UpdateResult.accepted(current.version(),
                    "覆盖层 " + next.size() + " 项已原子生效");
        }
    }

    /** 回滚到历史版本：以该版本内容构造新版本发布，历史不丢。 */
    public UpdateResult rollback(long targetVersion) {
        synchronized (writeLock) {
            long cur = current.version();
            ConfigSnapshot target = history.get(targetVersion);
            if (target == null) {
                return UpdateResult.rejected(cur, List.of(new ValidationError(
                        "*", String.valueOf(targetVersion), RejectReason.VERSION_NOT_FOUND,
                        "版本 " + targetVersion + " 不在保留的历史中" + history.navigableKeySet())));
            }
            overrideSource.replace(target.overrideLayer());
            ConfigSnapshot restored = buildSnapshot(sourcesWithOverride());
            publish(restored);
            return UpdateResult.accepted(current.version(),
                    "已回滚到版本 " + targetVersion + " 的内容并作为新版本 "
                            + current.version() + " 原子生效");
        }
    }

    public List<Long> history() {
        return new ArrayList<>(history.descendingKeySet());
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private List<ConfigSource> sourcesWithOverride() {
        List<ConfigSource> all = new ArrayList<>(sources.size() + 1);
        all.add(overrideSource);
        all.addAll(sources);
        all.sort(Comparator.comparingInt(ConfigSource::priority).reversed()
                .thenComparing(ConfigSource::name));
        return all;
    }

    private void publish(ConfigSnapshot snapshot) {
        this.current = snapshot;
        history.put(snapshot.version(), snapshot);
        while (history.size() > HISTORY_LIMIT) {
            history.pollFirstEntry();
        }
    }

    private ConfigSnapshot buildSnapshot(List<ConfigSource> orderedSources) {
        long version = nextVersion++;

        // 1) 读取全部来源；单个来源 I/O 失败立即中断，不产生半版本。
        List<Map<String, String>> loaded = new ArrayList<>(orderedSources.size());
        for (ConfigSource source : orderedSources) {
            try {
                loaded.add(source.load());
            } catch (RuntimeException e) {
                throw new SourceReadFailure(source.name(), null,
                        "来源 [" + source.name() + "] 读取失败: " + e.getMessage(), e);
            }
        }

        List<KeyIssue> issues = new ArrayList<>();
        Set<String> candidateKeys = new LinkedHashSet<>();
        for (Map<String, String> m : loaded) {
            candidateKeys.addAll(m.keySet());
        }

        // 2) 逐键按优先级合并。
        Map<String, ResolvedValue> values = new LinkedHashMap<>();

        // 固定键必须全部存在于快照（无来源值时走缺省）。
        for (ConfigKey key : registry.fixedKeys()) {
            mergeOne(key.name(), orderedSources, loaded, issues, values);
        }
        // 动态键（功能开关比例等）：仅在来源中出现时进入快照。
        for (String key : candidateKeys) {
            if (values.containsKey(key)) {
                continue;
            }
            if (registry.find(key).isEmpty()) {
                // 未知键：每个来源各记录一条，快照值中不包含。
                for (int i = 0; i < orderedSources.size(); i++) {
                    String raw = loaded.get(i).get(key);
                    if (raw != null) {
                        issues.add(new KeyIssue(orderedSources.get(i).name(), key, raw,
                                ResolveStatus.UNKNOWN_KEY, "未注册的配置键，已忽略，不生效"));
                    }
                }
                continue;
            }
            mergeOne(key, orderedSources, loaded, issues, values);
        }

        Map<String, String> overrideLayer = new LinkedHashMap<>(overrideSource.load());
        return new ConfigSnapshot(version, values, issues, overrideLayer);
    }

    private void mergeOne(String key,
                          List<ConfigSource> orderedSources,
                          List<Map<String, String>> loaded,
                          List<KeyIssue> issues,
                          Map<String, ResolvedValue> out) {
        ConfigKey def = registry.find(key).orElseThrow();
        ResolveStatus degradedStatus = null;
        String firstProblemDetail = null;
        for (int i = 0; i < orderedSources.size(); i++) {
            ConfigSource source = orderedSources.get(i);
            String raw = loaded.get(i).get(key);
            if (raw == null) {
                continue;
            }
            ConfigKey.ParseResult pr = def.parse(raw);
            if (pr.status() == ResolveStatus.RESOLVED) {
                out.put(key, new ResolvedValue(key, pr.value(), def.kind(),
                        degradedStatus == null ? ResolveStatus.RESOLVED : degradedStatus,
                        source.name(),
                        degradedStatus == null
                                ? "采用最高优先级来源 [" + source.name() + "] 的合法值 [" + raw + "]"
                                : firstProblemDetail
                                        + "；已降级并采用来源 [" + source.name() + "] 的合法值 [" + raw + "]"));
                return;
            }
            if (pr.status() == ResolveStatus.DEFAULT) {
                continue;
            }
            // 记录该来源的非法尝试，并继续向更低优先级寻找有效值。
            issues.add(new KeyIssue(source.name(), key, raw, pr.status(), pr.detail()));
            if (degradedStatus == null) {
                degradedStatus = pr.status();
                firstProblemDetail = "来源 [" + source.name() + "] 的值被拒绝: " + pr.detail();
            }
        }
        // 所有来源均无有效值：使用缺省值。
        out.put(key, new ResolvedValue(key, def.parsedDefault(), def.kind(),
                degradedStatus == null ? ResolveStatus.DEFAULT : degradedStatus,
                "default",
                degradedStatus == null
                        ? "所有来源均未提供有效值，使用缺省值 [" + def.defaultValue() + "]"
                        : firstProblemDetail + "；全部来源无有效值，回退到缺省值 ["
                                + def.defaultValue() + "]"));
    }

    /** 来源读取失败（装载阶段的整体拒绝条件）。 */
    private static final class SourceReadFailure extends RuntimeException {
        private final String sourceName;
        private final String rawValue;

        SourceReadFailure(String sourceName, String rawValue, String message, Throwable cause) {
            super(message, cause);
            this.sourceName = sourceName;
            this.rawValue = rawValue;
        }

        String sourceKey() { return "source:" + sourceName; }
        String rawValue() { return rawValue; }
    }
}
