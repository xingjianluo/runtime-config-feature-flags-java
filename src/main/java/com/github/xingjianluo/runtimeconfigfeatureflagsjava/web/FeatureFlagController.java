package com.github.xingjianluo.runtimeconfigfeatureflagsjava.web;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlag;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlagService;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FlagDecision;
import org.springframework.web.bind.annotation.*;

/** 开关入口：查询分流结论、维护开关定义。 */
@RestController
@RequestMapping("/flags")
public class FeatureFlagController {

    private final FeatureFlagService flagService;

    public FeatureFlagController(FeatureFlagService flagService) {
        this.flagService = flagService;
    }

    @GetMapping("/{name}/check")
    public FlagDecision check(@PathVariable String name, @RequestParam String id) {
        return flagService.decide(name, id);
    }

    @PutMapping
    public FeatureFlag upsert(@RequestBody FeatureFlag flag) {
        flagService.upsert(flag);
        return flag;
    }
}
