package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * classpath 内置缺省来源：随包发布的 {@code config-defaults.properties}，
 * 在所有本地文件来源之下（优先级最低）。资源不存在时视为空。
 */
public final class ClasspathConfigSource implements ConfigSource {

    private final String name;
    private final int priority;
    private final String resource;

    public ClasspathConfigSource(String name, int priority, String resource) {
        this.name = name;
        this.priority = priority;
        this.resource = resource;
    }

    @Override public String name() { return name; }

    @Override public int priority() { return priority; }

    @Override
    public Map<String, String> load() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = ClasspathConfigSource.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(resource)) {
            if (in == null) {
                return Map.of();
            }
            Properties props = new Properties();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            Map<String, String> result = new LinkedHashMap<>();
            for (String key : props.stringPropertyNames()) {
                result.put(key, props.getProperty(key));
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("读取 classpath 配置失败 [" + resource + "]", e);
        }
    }
}
