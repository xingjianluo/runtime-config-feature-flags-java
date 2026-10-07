package com.github.xingjianluo.runtimeconfigfeatureflagsjava.web;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.ConfigService;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.UpdateResult;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlag;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlagService;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FlagDecision;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 开关入口：查询分流结论、维护开关定义。
 * 与服务内部调用共用同一个 {@link FeatureFlagService#decide}，
 * 因此两个入口对同一开关、同一标识的结论完全一致。
 */
@RestController
@RequestMapping("/flags")
public class FeatureFlagController {

    private final FeatureFlagService flagService;
    private final ConfigService configService;

    public FeatureFlagController(FeatureFlagService flagService, ConfigService configService) {
        this.flagService = flagService;
        this.configService = configService;
    }

    @GetMapping("/{name}/check")
    public FlagDecision check(@PathVariable String name, @RequestParam String id) {
        return flagService.decide(name, id);
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of(
                "version", configService.current().version(),
                "flags", flagService.currentFlags());
    }

    /** 新增/更新开关：走版本化原子提交，拒绝时返回 4xx 而非看似成功的 200。 */
    @PutMapping
    public ResponseEntity<UpdateResult> upsert(@RequestBody FeatureFlag flag,
                                               @RequestParam(required = false) Long expectedVersion) {
        UpdateResult result = flagService.upsertFlag(flag, expectedVersion);
        return UpdateResponses.of(result);
    }
}
