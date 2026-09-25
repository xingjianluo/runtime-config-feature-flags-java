package com.github.xingjianluo.runtimeconfigfeatureflagsjava.feature;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.ConfigService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 功能开关服务装配。
 */
@Configuration
public class FeatureBootstrap {

    @Bean
    public FeatureGateService featureGateService(ConfigService configService) {
        return new FeatureGateService(configService);
    }
}
