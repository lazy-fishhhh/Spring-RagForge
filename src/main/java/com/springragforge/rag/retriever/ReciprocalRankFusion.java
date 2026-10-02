package com.springragforge.rag.retriever;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reciprocal Rank Fusion：把多路各自有序的召回结果融合成一个排序。
 *
 * <p>公式 {@code score(d) = Σ_i 1 / (k + rank_i(d))}，rank 从 1 开始。
 * 只用排名，不用原始分数——BM25 的分数和向量相似度不在一个量纲上，
 * 直接加权相加得先做归一化，RRF 绕开了这一步，这也是它稳的原因。
 *
 * <p>纯计算，不碰 ES，所以能单独测。
 */
public final class ReciprocalRankFusion {

    private ReciprocalRankFusion() {
    }

    /**
     * 融合若干路有序 id 列表。
     *
     * @param rankedLists 每路一个列表，列表内按相关度降序，列表内是chunk_id，前面的是更相关的
     * @param k           公式里的 k，通常取 60
     * @return 按融合分数降序的 id；分数相同则保持首次出现的先后
     */
    public static List<Fused> fuse(List<List<String>> rankedLists, int k) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<String> list : rankedLists) {
            // 同一路里重复的 id 只按最好名次计一次，否则重复出现的 id 会被多加分
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < list.size(); i++) {
                String id = list.get(i);
                if (!seen.add(id)) {
                    continue;
                }
                scores.merge(id, 1.0 / (k + i + 1), Double::sum);
            }
        }

        List<Fused> result = new ArrayList<>(scores.size());
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            result.add(new Fused(entry.getKey(), entry.getValue()));
        }
        // 稳定排序：LinkedHashMap 已保证同分时维持首次出现顺序
        result.sort(Comparator.comparingDouble(Fused::score).reversed());
        return result;
    }

    /** 融合结果的一项：id 和它的融合分数。 */
    public record Fused(String id, double score) {
    }
}
