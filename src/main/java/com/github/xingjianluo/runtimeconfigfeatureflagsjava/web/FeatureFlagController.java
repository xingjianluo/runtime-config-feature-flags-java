package com.github.xingjianluo.runtimeconfigfeatureflagsjava.web;

import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.ConfigService;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.config.UpdateResult;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlag;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FeatureFlagService;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.featureflag.FlagDecision;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.runtime.CommitRequest;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.runtime.RuntimeSnapshot;
import com.github.xingjianluo.runtimeconfigfeatureflagsjava.runtime.web.RuntimeController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 开关入口：查询分流结论、维护开关定义。判定与服务内部调用共用同一逻辑。 */
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

    /** 当前统一版本下的全部开关定义。 */
    @GetMapping
    public Map<String, Object> list() {
        RuntimeSnapshot snapshot = configService.state();
        return Map.of(
                "version", snapshot.version(),
                "flags", new ArrayList<>(snapshot.flags().values()));
    }

    /**
     * 新增/更新单个开关：并入当前版本的其它开关后走统一提交，
     * 因此开关变更与可能携带的配置变更加版本、原子生效。
     * 非法提交返回 4xx 且响应体显式标记 accepted=false。
     */
    @PutMapping
    public ResponseEntity<UpdateResult> upsert(@RequestBody FeatureFlag flag,
                                               @RequestParam(required = false) Long expectedVersion) {
        RuntimeSnapshot before = configService.state();
        Map<String, FeatureFlag> merged = new LinkedHashMap<>(before.flags());
        merged.put(flag.name(), flag);
        CommitRequest request = new CommitRequest(null, new ArrayList<>(merged.values()));
        UpdateResult result = configService.commit(request, expectedVersion);
        return ResponseEntity.status(RuntimeController.httpStatusOf(result.reason())).body(result);
    }
}
