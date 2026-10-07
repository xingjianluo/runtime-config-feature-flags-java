package com.github.xingjianluo.runtimeconfigfeatureflagsjava.web;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.UpdateResult;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlag;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlagService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 统一提交入口：一次提交配置覆盖与开关变更，
 * 两者要么一起进入新版本，要么整体不变。
 */
@RestController
@RequestMapping("/commit")
public class CommitController {

    private final FeatureFlagService flagService;

    public CommitController(FeatureFlagService flagService) {
        this.flagService = flagService;
    }

    /**
     * 请求体：{"config": {...}, "flags": {"name": {"enabled": true, "percentage": 25}}}
     * config 整体替换配置覆盖集合；flags 在当前版本开关集合上增量合并。
     */
    @PostMapping
    public ResponseEntity<UpdateResult> commit(@RequestBody CommitRequest request,
                                               @RequestParam(required = false) Long expectedVersion) {
        Map<String, String> config = request.config() == null ? Map.of() : request.config();
        Map<String, FeatureFlag> flags = request.flags() == null ? Map.of() : request.flags();
        UpdateResult result = flagService.commitAll(config, flags, expectedVersion);
        return UpdateResponses.of(result);
    }

    public record CommitRequest(Map<String, String> config, Map<String, FeatureFlag> flags) {
    }
}
