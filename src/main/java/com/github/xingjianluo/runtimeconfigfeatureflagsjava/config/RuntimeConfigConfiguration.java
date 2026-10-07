package com.github.xingjianluo.runtimeconfigfeatureflagsjava.config;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source.InMemoryConfigSource;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.source.PropertiesFileConfigSource;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlagService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 装配配置中心与开关服务。
 *
 * 来源与优先级（数值大者优先）：
 *   10 classpath:application.properties（应用自带，最低）
 *   20 classpath:runtime-config.properties（运维投放）
 *   30 运行时覆盖（经 /config/overrides 提交，最高）
 */
@Configuration
public class RuntimeConfigConfiguration {

    @Bean
    public ConfigRegistry configRegistry() {
        return new ConfigRegistry()
                .register(new ConfigDefinition("service.thread-pool.size", ConfigValueType.INTEGER, "8",
                        v -> Integer.parseInt(v.trim()) >= 1 && Integer.parseInt(v.trim()) <= 512,
                        "worker thread pool size, 1..512"))
                .register(new ConfigDefinition("service.request-timeout-ms", ConfigValueType.LONG, "3000",
                        v -> Long.parseLong(v.trim()) > 0,
                        "request timeout in ms, must be positive"))
                .register(new ConfigDefinition("service.rate-limit.enabled", ConfigValueType.BOOLEAN, "false",
                        null, "whether rate limiting is on"))
                .register(new ConfigDefinition("service.cache.ratio", ConfigValueType.DOUBLE, "0.5",
                        v -> {
                            double d = Double.parseDouble(v.trim());
                            return d >= 0.0 && d <= 1.0;
                        }, "cache ratio, 0.0..1.0"))
                .register(new ConfigDefinition("service.greeting", ConfigValueType.STRING, "hello",
                        null, "greeting text"));
    }

    @Bean
    public InMemoryConfigSource overrideSource() {
        return new InMemoryConfigSource("runtime-overrides", 30);
    }

    @Bean
    public ConfigLoader configLoader(ConfigRegistry registry, InMemoryConfigSource overrideSource) {
        return new ConfigLoader(registry, List.of(
                PropertiesFileConfigSource.fromClasspath("application.properties", 10, "application.properties"),
                PropertiesFileConfigSource.fromClasspath("runtime-config.properties", 20, "runtime-config.properties"),
                overrideSource));
    }

    @Bean
    public ConfigService configService(ConfigLoader loader, InMemoryConfigSource overrideSource) {
        ConfigService service = new ConfigService(loader, overrideSource);
        service.init();
        return service;
    }

    @Bean
    public FeatureFlagService featureFlagService(ConfigService configService) {
        // 开关状态完全来自配置快照（flag.<name>.enabled/percentage 键），
        // 初始开关由 runtime-config.properties 中的 flag.* 键播种
        return new FeatureFlagService(configService);
    }
}
