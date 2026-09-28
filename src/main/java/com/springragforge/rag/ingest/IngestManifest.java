package com.springragforge.rag.ingest;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 灌库清单，落盘在 {@code rag.ingest.manifest-file}。
 *
 * <p>它是「上次灌了什么」的唯一凭据：向量库里只有向量，没有文件名，想按文档删旧向量，
 * 只能靠这里记下的 chunk_id 列表。
 *
 * <p>文档按 docKey 排序存放（TreeMap），保证同样的语料写出同样的文件，方便 diff 和人工核对。
 */
@Getter
@Setter
@NoArgsConstructor
public class IngestManifest {

    /** 切分器签名。和当前签名不一致说明切法变了，历史向量整体作废。 */
    private String chunkerSignature = "";

    /** embedding 模型名。换模型后新旧向量不可比，同样整体作废。 */
    private String embeddingModel = "";

    /** docKey（如 spring-boot/reference/pages/web/servlet.adoc）到该文档灌库结果的映射。 */
    private Map<String, DocEntry> documents = new TreeMap<>();

    /** 单个文档的灌库结果。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class DocEntry {

        /** 源文件内容的 sha256。内容没变就跳过整个文档。 */
        private String sha256 = "";

        /** 该文档产出的向量 id，即各片段的 chunk_id。换版本时按这个列表删旧向量。 */
        private List<String> chunkIds = new ArrayList<>();

        public DocEntry(String sha256, List<String> chunkIds) {
            this.sha256 = sha256;
            this.chunkIds = new ArrayList<>(chunkIds);
        }
    }
}
