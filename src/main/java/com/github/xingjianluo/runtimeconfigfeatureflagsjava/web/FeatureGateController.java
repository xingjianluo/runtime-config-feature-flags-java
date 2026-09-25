package com.github.xingjianluo.runtimeconfigfeatureflagsjava.web;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.feature.FeatureGateService;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.feature.GateDecision;
import org.springframework.web.bind.annotation.*;

/**
 * 功能开关分流入口（HTTP 入口）；判定全部委托 {@link FeatureGateService}，
 * 与任何内部入口使用同一算法、同一份当前版本，结论一致且可复现。
 */
@RestController
@RequestMapping("/api/flags")
public final class FeatureGateController {

    private final FeatureGateService featureGateService;

    public FeatureGateController(FeatureGateService featureGateService) {
        this.featureGateService = featureGateService;
    }

    @GetMapping("/{flag}/check")
    public GateDecision check(@PathVariable String flag,
                              @RequestParam("identity") String identity) {
        return featureGateService.evaluate(flag, identity);
    }
}
