package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source.ClasspathConfigSource;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source.ConfigSource;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source.PropertiesFileConfigSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

/**
 * Spring 装配：注册键、配置来源（含确定优先级）与配置服务。
 *
 * <p>来源优先级（数字越大越优先）：
 * <ol>
 *   <li>{@code runtime-override}（内存，MAX_VALUE）——运行时 submit 提交</li>
 *   <li>{@code local-file}（200）—— {@code <configDir>/application-local.properties}</li>
 *   <li>{@code default-file}（100）—— {@code <configDir>/application-default.properties}</li>
 *   <li>{@code classpath-defaults}（0，最低）—— jar 内 config-defaults.properties</li>
 * </ol>
 * configDir 由系统属性 {@code app.config.dir} 指定，缺省为工作目录下的 {@code config/}。
 */
@Configuration
public class ConfigBootstrap {

    @Bean
    public ConfigRegistry configRegistry() {
        return ConfigKeys.defaultRegistry();
    }

    @Bean
    public List<ConfigSource> configSources() {
        String dir = System.getProperty("app.config.dir", "config");
        Path base = Path.of(dir);
        return List.of(
                new PropertiesFileConfigSource("local-file", 200,
                        base.resolve("application-local.properties")),
                new PropertiesFileConfigSource("default-file", 100,
                        base.resolve("application-default.properties")),
                new ClasspathConfigSource("classpath-defaults", 0, "config-defaults.properties")
        );
    }

    @Bean
    public ConfigService configService(ConfigRegistry registry, List<ConfigSource> sources) {
        ConfigService service = new ConfigService(registry, sources);
        service.initialize();
        return service;
    }
}
