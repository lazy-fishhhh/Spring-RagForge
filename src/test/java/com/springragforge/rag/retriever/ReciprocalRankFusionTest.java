package com.springragforge.rag.retriever;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RRF 融合的纯逻辑测试。不碰 ES，只验排名相加的算术和顺序。
 */
class ReciprocalRankFusionTest {

    private static final int K = 60;

    @Test
    void emptyInputReturnsNothing() {
        assertThat(ReciprocalRankFusion.fuse(List.of(), K)).isEmpty();
        assertThat(ReciprocalRankFusion.fuse(List.of(List.of(), List.of()), K)).isEmpty();
    }

    @Test
    void documentAppearingInBothListsOutranksSingleListDocuments() {
        // a 两路都在，b 只有 BM25，c 只有 KNN
        List<String> bm25 = List.of("a", "b");
        List<String> knn = List.of("c", "a");

        List<String> order = ids(ReciprocalRankFusion.fuse(List.of(bm25, knn), K));

        // a = 1/61 + 1/62 最大；c = 1/61 次之；b = 1/62 最小
        assertThat(order).containsExactly("a", "c", "b");
    }

    @Test
    void higherRankScoresHigherWithinOneList() {
        List<String> only = List.of("first", "second", "third");

        List<ReciprocalRankFusion.Fused> fused = ReciprocalRankFusion.fuse(List.of(only), K);

        assertThat(ids(fused)).containsExactly("first", "second", "third");
        assertThat(fused.get(0).score()).isCloseTo(1.0 / 61, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(fused.get(1).score()).isCloseTo(1.0 / 62, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void duplicateWithinOneListCountsOnceAtBestRank() {
        // 同一路里 a 重复出现，只按第 1 名计一次分
        List<ReciprocalRankFusion.Fused> fused =
                ReciprocalRankFusion.fuse(List.of(List.of("a", "a", "b")), K);

        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).id()).isEqualTo("a");
        assertThat(fused.get(0).score()).isCloseTo(1.0 / 61, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void tieKeepsFirstSeenOrder() {
        // 两个文档分数相同，谁先出现谁排前
        List<String> bm25 = List.of("a", "b");
        List<String> knn = List.of("b", "a");

        assertThat(ids(ReciprocalRankFusion.fuse(List.of(bm25, knn), K)))
                .containsExactly("a", "b");
    }

    private static List<String> ids(List<ReciprocalRankFusion.Fused> fused) {
        return fused.stream().map(ReciprocalRankFusion.Fused::id).toList();
    }
}
