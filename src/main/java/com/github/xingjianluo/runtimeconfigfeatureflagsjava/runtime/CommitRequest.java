package com.github.xingjianluo.runtimeconfigfeatureflagsjava.runtime;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlag;

import java.util.List;
import java.util.Map;

/**
 * 一次统一提交：配置覆盖与开关定义要么一起进入新版本，要么整体不变。
 *
 * 任一字段为 null 表示本次提交不改动该部分（沿用当前版本的值）。
 *
 * @param configOverrides 非 null 时整体替换上一版本的配置覆盖集合
 * @param flags           非 null 时整体替换上一版本的开关定义集合
 */
public record CommitRequest(Map<String, String> configOverrides, List<FeatureFlag> flags) {

    public boolean touchesConfig() {
        return configOverrides != null;
    }

    public boolean touchesFlags() {
        return flags != null;
    }
}
