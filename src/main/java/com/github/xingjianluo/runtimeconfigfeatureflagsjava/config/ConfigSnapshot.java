package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 不可变配置快照：一个完整版本。
 *
 * <p>构建后内容永不改变，因此可以被任意线程无锁共享；
 * 一次请求内只要持有同一个快照引用，看到的就必然是同一个完整版本。
 *
 * @param version       单调递增的版本号
 * @param values        合并后的最终取值（固定键一定存在）
 * @param issues        装载/合并过程中记录的问题（非法值、未知键等）
 * @param overrideLayer 该版本生效时运行时覆盖层的原始内容（供回滚还原）
 */
public record ConfigSnapshot(
        long version,
        Map<String, ResolvedValue> values,
        List<KeyIssue> issues,
        Map<String, String> overrideLayer
) {
    public ConfigSnapshot {
        values = Map.copyOf(values);
        issues = List.copyOf(issues);
        overrideLayer = Map.copyOf(overrideLayer);
    }

    public ResolvedValue get(String key) {
        ResolvedValue v = values.get(key);
        if (v == null) {
            throw new NoSuchElementException("快照版本 " + version + " 中不存在配置键: " + key);
        }
        return v;
    }

    public int getInt(String key) {
        return ((Long) get(key).value()).intValue();
    }

    public boolean getBoolean(String key) {
        return (Boolean) get(key).value();
    }

    /** 是否存在装载问题记录（用于可解释输出，不影响版本完整性）。 */
    public boolean hasIssues() {
        return !issues.isEmpty();
    }
}
