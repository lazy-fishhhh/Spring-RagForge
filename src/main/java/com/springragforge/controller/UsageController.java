package com.springragforge.controller;

import com.springragforge.usage.ModelUsage;
import com.springragforge.usage.TokenUsageProperties;
import com.springragforge.usage.TokenUsageRegistry;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * token 用量与成本查询。金额是估算：按 {@code rag.usage.pricing} 价格表算，
 * 币种跟价格表一致，不做汇率换算。
 */
@RestController
@RequestMapping("/admin/usage")
public class UsageController {

    @Resource
    private TokenUsageRegistry registry;

    @Resource
    private TokenUsageProperties properties;

    /** 全量用量报表：按模型分行 + 全局合计。 */
    @GetMapping
    public UsageReport usage() {
        List<UsageLine> lines = new ArrayList<>();
        double totalCost = 0.0;
        boolean allPriced = true;

        Map<String, ModelUsage> perModel = registry.snapshot();
        for (ModelUsage usage : perModel.values()) {
            UsageLine line = toLine(usage);
            lines.add(line);
            if (line.priced()) {
                totalCost += line.totalCost();
            } else {
                allPriced = false;
            }
        }

        // 合计行的金额只累加有价格的模型；有任一模型缺价则标记 total 的 priced=false 以示不完整
        ModelUsage total = registry.total();
        UsageLine totalLine = new UsageLine(total.modelName(), total.calls(), total.inputTokens(),
                total.outputTokens(), total.totalTokens(), null, null,
                allPriced ? round(totalCost) : null, allPriced);
        return new UsageReport(lines, totalLine);
    }

    /** 清零所有计数，供评测前归零。 */
    @DeleteMapping
    public Map<String, String> reset() {
        registry.reset();
        return Map.of("status", "reset");
    }

    private UsageLine toLine(ModelUsage usage) {
        TokenUsageProperties.ModelPrice price = properties.priceFor(usage.modelName());
        if (price == null) {
            return new UsageLine(usage.modelName(), usage.calls(), usage.inputTokens(), usage.outputTokens(),
                    usage.totalTokens(), null, null, null, false);
        }
        double inputCost = cost(usage.inputTokens(), price.getInputPerMillion());
        double outputCost = cost(usage.outputTokens(), price.getOutputPerMillion());
        return new UsageLine(usage.modelName(), usage.calls(), usage.inputTokens(), usage.outputTokens(),
                usage.totalTokens(), inputCost, outputCost, round(inputCost + outputCost), true);
    }

    private static double cost(long tokens, double perMillion) {
        return tokens / 1_000_000.0 * perMillion;
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    /** 报表：按模型逐行 + 合计行。 */
    public record UsageReport(List<UsageLine> models, UsageLine total) {
    }

    /** 单个模型的用量与估算费用。priced=false 表示价格表里没有该模型，金额字段为 null。 */
    public record UsageLine(String modelName, long calls, long inputTokens, long outputTokens, long totalTokens,
                            Double inputCost, Double outputCost, Double totalCost, boolean priced) {
    }
}
