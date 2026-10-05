package com.springragforge.usage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * token 用量与成本统计参数。
 *
 * <p>价格表用「模型名 → 每百万 token 单价」的列表（不用 Map：模型名含点号，
 * 如 qwen3.8-flash，作 Map key 会被 Spring 当成层级分隔符，绑不上）。币种跟计费方一致，
 * 这里只做算术，不换算汇率。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rag.usage")
public class TokenUsageProperties {

    /** 关掉即不统计、不记日志，监听器直接返回。 */
    private boolean enabled = true;

    /** 每次模型调用后打一行用量日志。 */
    private boolean logEachCall = true;

    /** 价格表。查不到价格的模型只报 token 数，不报金额。 */
    private List<ModelPrice> pricing = new ArrayList<>();

    /** 按模型名精确匹配价格；无匹配返回 null。 */
    public ModelPrice priceFor(String modelName) {
        if (modelName == null) {
            return null;
        }
        for (ModelPrice price : pricing) {
            if (modelName.equals(price.getModelName())) {
                return price;
            }
        }
        return null;
    }

    /** 单个模型的价格。单价单位：每百万 token。 */
    @Getter
    @Setter
    public static class ModelPrice {
        private String modelName;
        private double inputPerMillion;
        private double outputPerMillion;
    }
}
