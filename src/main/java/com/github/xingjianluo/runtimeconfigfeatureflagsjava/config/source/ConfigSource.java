package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source;

import java.util.Map;

/**
 * 一个本地配置来源：提供原始字符串键值对。
 * priority 越大优先级越高。
 */
public interface ConfigSource {

    String name();

    int priority();

    /** 重新读取来源内容；来源不存在时返回空 Map。 */
    Map<String, String> load();
}
