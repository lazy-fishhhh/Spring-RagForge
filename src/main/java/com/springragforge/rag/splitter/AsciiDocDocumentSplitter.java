package com.springragforge.rag.splitter;

import com.springragforge.rag.source.SourceLinkResolver;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 面向 Spring 风格 AsciiDoc 的第一期切分器：按 {@code ==} 切分，超长再降到 {@code ===}，
 * 仍超长则按原子块拼接。代码块、tabs、表格不从内部切开。
 *
 * <p>整体是两步：先用 {@link AsciiDocBlockScanner} 把源码扫成块，再按标题层级把块组织成树，
 * 最后从树上发段。每一段文本开头都会重复一遍自己所在的标题路径（形如 {@code 父 > 子}），
 * 这样单独看一段也知道它是从哪讲起的。
 */
public class AsciiDocDocumentSplitter implements DocumentSplitter {

    // 默认单段字符上限：3200 字符。超过就往下切。
    static final int DEFAULT_MAX_SEGMENT_SIZE_CHARS = 3200;
    // 默认相邻两段重叠字符数：0，表示不重叠。
    static final int DEFAULT_MAX_OVERLAP_SIZE_CHARS = 0;

    /** 切分算法版本号。切分逻辑一改就往上加，让历史 chunk 整体作废。 */
    private static final String ALGORITHM_VERSION = "asciidoc-v1";

    /** 单段字符数上限，超了就往下切。是上限不是目标，段通常比它小很多。 */
    private final int maxSegmentSizeInChars;
    /** 相邻两段重叠的字符数，0 表示不重叠。只在段内被迫切开时才用得上。 */
    private final int maxOverlapSizeInChars;
    /** 语料根目录换算器，决定 chunk_id 和 source 长什么样。 */
    private final SourceLinkResolver sourceLinkResolver;

    /** 无参构造：用默认配置（3200 上限，不重叠，默认语料根）。 */
    public AsciiDocDocumentSplitter() {
        this(DEFAULT_MAX_SEGMENT_SIZE_CHARS, DEFAULT_MAX_OVERLAP_SIZE_CHARS);
    }

    /**
     * 带参构造。
     *
     * @param maxSegmentSizeInChars 单段字符上限，必须为正数
     * @param maxOverlapSizeInChars 相邻段重叠字符数，必须 >= 0
     */
    public AsciiDocDocumentSplitter(int maxSegmentSizeInChars, int maxOverlapSizeInChars) {
        this(maxSegmentSizeInChars, maxOverlapSizeInChars, SourceLinkResolver.DEFAULT_CORPUS_ROOT);
    }

    /**
     * 指定语料根目录的构造：灌库时用配置里的根，保证文件都能换算出 chunk_id。
     *
     * @param corpusRoot 语料根目录，非空
     */
    public AsciiDocDocumentSplitter(String corpusRoot) {
        this(DEFAULT_MAX_SEGMENT_SIZE_CHARS, DEFAULT_MAX_OVERLAP_SIZE_CHARS, corpusRoot);
    }

    public AsciiDocDocumentSplitter(int maxSegmentSizeInChars, int maxOverlapSizeInChars, String corpusRoot) {
        if (maxSegmentSizeInChars <= 0) {
            throw new IllegalArgumentException("maxSegmentSizeInChars must be positive");
        }
        if (maxOverlapSizeInChars < 0) {
            throw new IllegalArgumentException("maxOverlapSizeInChars must not be negative");
        }
        if (corpusRoot == null || corpusRoot.isBlank()) {
            throw new IllegalArgumentException("corpusRoot must not be blank");
        }
        this.maxSegmentSizeInChars = maxSegmentSizeInChars;
        this.maxOverlapSizeInChars = maxOverlapSizeInChars;
        this.sourceLinkResolver = new SourceLinkResolver(corpusRoot);
    }

    /**
     * 切分签名：算法版本 + 关键参数。
     *
     * <p>签名一变，历史 chunk 的边界和内容都可能不同，已有向量不能再被增量复用，
     * 必须整体重建。增量灌库把它写进 manifest 做比对。
     */
    public String signature() {
        return ALGORITHM_VERSION + "/" + maxSegmentSizeInChars + "/" + maxOverlapSizeInChars;
    }

    /**
     * 切分入口：把一个 Document 切成若干 TextSegment。
     *
     * <p>整体四步：
     * <ol>
     *   <li>源码 → 块（AsciiDocBlockScanner.scan）</li>
     *   <li>块 → 标题树（buildTree）</li>
     *   <li>标题树 → 段草稿（emitSection）</li>
     *   <li>段草稿 + 元数据 → TextSegment</li>
     * </ol>
     */
    @Override
    public List<TextSegment> split(Document document) {
        String text = document.text();
        // 空文档直接返回空列表，不用往下走
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<AsciiDocBlock> blocks = AsciiDocBlockScanner.scan(text);   // 1. 源码 -> 块
        Section root = buildTree(blocks);                               // 2. 块 -> 标题树
        String docTitle = firstTitle(root);                             // 取第一个一级标题当文档名
        List<Draft> drafts = new ArrayList<>();
        emitSection(root, "", docTitle, drafts);                        // 3. 从树上发段

        // 4. 元数据统一在这里挂，前面只负责决定每段文本是什么
        String directoryPath = document.metadata().getString(Document.ABSOLUTE_DIRECTORY_PATH);
        String fileName = document.metadata().getString(Document.FILE_NAME);
        String documentKey = sourceLinkResolver.documentKey(directoryPath, fileName);

        List<TextSegment> segments = new ArrayList<>();
        int total = drafts.size();
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i);
            // 空段直接跳过，不产出
            if (draft.text.isBlank()) {
                continue;
            }
            // 复制原文档的元数据，再往上挂切分相关的字段
            Metadata metadata = document.metadata().copy();
            // 本地绝对路径不落库：检索和引用都用不上，只暴露本机目录结构
            metadata.remove(Document.ABSOLUTE_DIRECTORY_PATH);
            metadata.put("chunk_index", i);                     // 段的序号
            metadata.put("chunk_total", total);                 // 总段数
            metadata.put("heading_level", draft.headingLevel);  // 所在标题层级
            metadata.put("has_code", String.valueOf(draft.hasCode)); // 段内是否含代码示例
            if (!documentKey.isEmpty()) {
                // 稳定唯一的段标识：文档路径 + 段序号
                metadata.put("chunk_id", documentKey + "#" + i);
            }
            String source = sourceLinkResolver.source(directoryPath, fileName, draft.anchor);
            if (!source.isEmpty()) {
                metadata.put("source", source);                 // 官方文档站链接，带锚点
            }
            if (!draft.docTitle.isEmpty()) {
                metadata.put("doc_title", draft.docTitle);      // 文档标题
            }
            if (!draft.headingPath.isEmpty()) {
                metadata.put("heading_path", draft.headingPath); // 标题路径，如 "父 > 子"
            }
            if (!draft.anchor.isEmpty()) {
                metadata.put("anchor", draft.anchor);           // 锚点 id
            }
            segments.add(TextSegment.from(draft.text, metadata));
        }
        return segments;
    }

    /** 取第一个一级标题（{@code = xxx}）当文档名，每个段的元数据都会带上它。 */
    private static String firstTitle(Section root) {
        // 根节点的 children 就是所有一级标题，取第一个非空的即可
        for (Section child : root.children) {
            if (child.level == 1 && !child.title.isEmpty()) {
                return child.title;
            }
        }
        return "";
    }

    /**
     * 按标题层级把块挂成一棵树：标题是节点，正文块挂到它前面最近的那个标题下。
     *
     * <p>做法是维护一条从根到当前节点的路径（栈）。遇到新标题时，先把栈里层级不比自己浅的
     * 节点弹掉——它们都不可能是自己的父节点了——剩下的栈顶就是父节点。
     */
    private static Section buildTree(List<AsciiDocBlock> blocks) {
        // 虚拟根节点：level=0，没有标题、没有正文，只是给所有一级标题当统一挂载点
        Section root = new Section(0, "", "", "");

        // 栈保存“从根到当前节点”的路径，栈顶就是当前所在的节
        Deque<Section> stack = new ArrayDeque<>();
        stack.push(root);

        for (AsciiDocBlock block : blocks) {
            if (block.type() == AsciiDocBlock.Type.HEADING) {
                // 遇到标题：新建一个 Section 节点
                Section section = new Section(block.headingLevel(), block.headingTitle(),
                        block.anchor(), block.text());

                // 关键：把栈里层级不比自己浅的节点弹掉。
                // 因为层级 >= 自己的节点，都不可能是自己的父节点。
                while (!stack.isEmpty() && stack.peek().level >= section.level) {
                    stack.pop();
                }

                // 弹完后，栈顶就是自己的父节点，挂上去
                stack.peek().children.add(section);

                // 自己入栈，成为新的“当前节”
                stack.push(section);
            } else {
                // 非标题块（段落、代码、表格等）归属于当前所在的节（栈顶）
                stack.peek().blocks.add(block);
            }
        }
        return root;
    }

    /**
     * 递归发段。顺序始终是「本节自己的内容在前，各子节按原顺序在后」，所以段的先后和原文一致。
     *
     * <p>按层级分三种走法：
     * <ol>
     *   <li>0、1 级（根和文档标题）：只发自己抬头那段引导文字，正文全交给子节各发各的。
     *       这里不能把子孙一起收进来，否则子节递归时同一份内容会再发一遍。</li>
     *   <li>2 级且整节超限、又有子节：本节抬头单独发一段，正文降级按 3 级标题切。</li>
     *   <li>其余：本节连同所有子孙摊平，按块的边界切。原子块（代码、表格、tabs）不动。</li>
     * </ol>
     */
    private void emitSection(Section section, String parentPath, String docTitle, List<Draft> drafts) {
        // 把父路径和本节标题拼起来，形成 "父 > 子" 形式的路径
        String path = joinPath(parentPath, section.title);

        // ===== 走法 A：0、1 级（根和文档标题） =====
        // 它们通常是容器，本身没什么正文，只发自己的抬头，
        // 子孙内容交给子节递归发，避免重复。
        if (section.level <= 1) {
            // preambleOnly=true：只处理本节自己的块，不摊平子孙
            emitBody(section, path, section.level, docTitle, section.anchor, drafts, true);
            // 递归每个子节
            for (Section child : section.children) {
                // 传下去的 parentPath：如果 path 非空就传 path；
                // 如果 path 为空（根节点），就传 section.title（根 title 也是空）
                emitSection(child, path.isEmpty() ? section.title : path, docTitle, drafts);
            }
            return;
        }

        // 计算本节整节（含前缀路径 + 所有子孙内容）的字符数
        int size = prefixLength(path) + flattenSize(section);

        // ===== 走法 B：2 级且整节超限、又有子节 =====
        // 太大了，硬摊平会切碎三级标题边界，所以降级按三级标题切：
        // 本节抬头单独发一段，正文下放给子节。
        boolean splitByChildren = section.level == 2 && size > maxSegmentSizeInChars && !section.children.isEmpty();
        if (splitByChildren) {
            // 只发本节自己的抬头和直接正文
            emitBody(section, path, section.level, docTitle, section.anchor, drafts, true);
            // 子节各自递归发（path 已经包含本节标题，往下传）
            for (Section child : section.children) {
                emitSection(child, path, docTitle, drafts);
            }
            return;
        }

        // ===== 走法 C：其余情况 =====
        // 本节连同所有子孙摊平，按块边界切。原子块内部不切开。
        packOrEmit(flatten(section), path, section.level, docTitle, section.anchor, drafts);
    }

    /**
     * 发一节自己的抬头和正文。
     *
     * @param preambleOnly true 表示不管子孙节点，只处理本节自己的块；false 表示把子孙也摊平一起发。
     */
    private void emitBody(
            Section section,
            String path,
            int level,
            String docTitle,
            String anchor,
            List<Draft> drafts,
            boolean preambleOnly
    ) {
        List<AsciiDocBlock> body = new ArrayList<>();

        // 如果本节不是根（level > 0）且有标题原文行，就把标题行加进来
        if (section.level > 0 && !section.headingLine.isEmpty()) {
            body.add(AsciiDocBlock.heading(section.headingLine, section.level, section.title, section.anchor));
        }
        // 加本节自己的正文块（不含子孙）
        body.addAll(section.blocks);

        // 只有标题、下面没正文的一节不值得单独成段，直接跳过
        if (preambleOnly && section.blocks.isEmpty()) {
            return;
        }

        // 如果要求把子孙也摊平一起发（preambleOnly=false），就把每个子节摊平后加进来
        if (!preambleOnly) {
            for (Section child : section.children) {
                body.addAll(flatten(child));
            }
        }

        // 空块，或者只有标题这一种块，也跳过（避免产出只有标题的空段）
        if (body.isEmpty() || (body.size() == 1 && body.get(0).type() == AsciiDocBlock.Type.HEADING)) {
            return;
        }

        // 交给 packOrEmit 真正切段
        packOrEmit(body, path, level, docTitle, anchor, drafts);
    }

    /**
     * 把一批块变成段。先看整批能不能塞进一段，能就发一段完事；不能就按块顺序贪心累加，
     * 装到接近上限就发一段、重新开一段接着装。
     *
     * <p>切分只发生在块的边界上，所以单个块自身超过上限时不会被切开，只能独自成段（字段注释里说的原子块）。
     */
    private void packOrEmit(
            List<AsciiDocBlock> blocks,
            String path,
            int level,
            String docTitle,
            String anchor,
            List<Draft> drafts
    ) {
        // 把所有块拼成一段大文本
        String flattened = joinBlockTexts(blocks);

        // 每段开头的"我属于哪一节"，让段自带上下文。
        // 形如 "父 > 子\n\n"，后面跟正文。
        String prefix = path.isEmpty() ? "" : path + "\n\n";

        // 情况 1：整批能塞进一段，直接发一段完事
        if (prefix.length() + flattened.length() <= maxSegmentSizeInChars) {
            String text = (prefix + flattened).trim();
            if (!text.isEmpty()) {
                drafts.add(new Draft(text, path, level, docTitle, anchor, hasCode(blocks)));
            }
            return;
        }

        // 情况 2：装不下，改成一块一块往当前段里塞（贪心累加）。
        // 攒的是块不是文本：段的 has_code 要靠块类型判断，摊平成字符串就丢了。
        List<AsciiDocBlock> current = new ArrayList<>();   // 当前正在攒的段
        String overlap = "";                               // 新段开头垫的上一段结尾
        String previousBody = "";                          // 上一段的正文，供算重叠用
        for (AsciiDocBlock block : blocks) {
            // 空块跳过
            if (block.isBlank()) {
                continue;
            }

            // 试着把当前块加进去
            List<AsciiDocBlock> candidate = new ArrayList<>(current);
            candidate.add(block);
            int candidateSize = prefix.length() + overlapSize(overlap) + joinBlockTexts(candidate).length();

            // 还塞得下，或者当前段本来就是空的（块再大也得放进去），就接受
            if (current.isEmpty() || candidateSize <= maxSegmentSizeInChars) {
                current = candidate;
                continue;
            }

            // 塞不下了：先把攒好的发掉，再从这个块开始开新段
            previousBody = emitPacked(current, overlap, prefix, path, level, docTitle, anchor, drafts);

            // 开新段：开头垫一点上一段的结尾，避免正好切在句子中间
            current = new ArrayList<>();
            overlap = overlapFrom(previousBody);
            current.add(block);
        }

        // 循环结束，把最后攒着的一段发掉
        if (!current.isEmpty()) {
            emitPacked(current, overlap, prefix, path, level, docTitle, anchor, drafts);
        }
    }

    /** 把攒好的几块发成一段，返回这段的正文，供下一段算重叠用。 */
    private String emitPacked(
            List<AsciiDocBlock> blocks,
            String overlap,
            String prefix,
            String path,
            int level,
            String docTitle,
            String anchor,
            List<Draft> drafts
    ) {
        String body = joinBlockTexts(blocks).trim();
        // 空段不产出新段
        if (body.isEmpty()) {
            return "";
        }
        // 前缀 + 重叠 + 正文，组成最终段文本
        String text = (prefix + (overlap.isEmpty() ? body : overlap + "\n\n" + body)).trim();
        drafts.add(new Draft(text, path, level, docTitle, anchor, hasCode(blocks)));
        // 返回本段正文，供下一段算重叠
        return body;
    }

    /** 段内是否含代码示例。tabs 里装的就是分语言代码，算代码。 */
    private static boolean hasCode(List<AsciiDocBlock> blocks) {
        return blocks.stream().anyMatch(block ->
                block.type() == AsciiDocBlock.Type.CODE || block.type() == AsciiDocBlock.Type.TABS);
    }

    /** 重叠文本占的字符数（含它和正文之间的空行）。 */
    private static int overlapSize(String overlap) {
        return overlap.isEmpty() ? 0 : overlap.length() + 2;
    }

    /** 从上一段结尾取一小截文字。配置为 0（默认）时不重叠，直接返回空串。 */
    private String overlapFrom(String previousBody) {
        // 不重叠配置，或上一段为空，直接返回空
        if (maxOverlapSizeInChars == 0 || previousBody.isEmpty()) {
            return "";
        }
        // 从上一段结尾取最多 maxOverlapSizeInChars 个字符
        String slice = previousBody.length() <= maxOverlapSizeInChars
                ? previousBody
                : previousBody.substring(previousBody.length() - maxOverlapSizeInChars);

        // 从整行开头切，免得开头是个半截句子
        int newline = slice.indexOf('\n');
        if (newline >= 0 && newline + 1 < slice.length()) {
            slice = slice.substring(newline + 1);
        }
        return slice.trim();
    }

    /** 段开头标题路径的字符数（含后面空的那一行）。 */
    private static int prefixLength(String path) {
        // path 为空 → 0；否则是 path 长度 + 2（两个换行符）
        return path.isEmpty() ? 0 : path.length() + 2;
    }

    /** 本节整节（含子孙）摊平后的字符数。 */
    private static int flattenSize(Section section) {
        return joinBlockTexts(flatten(section)).length();
    }

    /** 把一节连同它的所有子孙，按原文顺序摊平成一个块列表。 */
    private static List<AsciiDocBlock> flatten(Section section) {
        List<AsciiDocBlock> out = new ArrayList<>();

        // 先放本节标题行（如果 level > 0 且有标题原文）
        if (section.level > 0 && !section.headingLine.isEmpty()) {
            out.add(AsciiDocBlock.heading(section.headingLine, section.level, section.title, section.anchor));
        }

        // 再放本节自己的正文块
        out.addAll(section.blocks);

        // 递归放每个子节（保持原文顺序）
        for (Section child : section.children) {
            out.addAll(flatten(child));
        }
        return out;
    }

    /** 把块列表拼成文本，跳过空块，块间用 "\n\n" 连接。 */
    private static String joinBlockTexts(List<AsciiDocBlock> blocks) {
        List<String> parts = new ArrayList<>();
        for (AsciiDocBlock block : blocks) {
            if (!block.isBlank()) {
                parts.add(block.text());
            }
        }
        return joinParts(parts);
    }

    /** 用 "\n\n" 连接若干段文本。 */
    private static String joinParts(List<String> parts) {
        return String.join("\n\n", parts);
    }

    /** 拼标题路径，形如 {@code Declaring Advice > Before Advice}。 */
    private static String joinPath(String parent, String title) {
        // 自己标题为空：返回父路径（或空串）
        if (title == null || title.isBlank()) {
            return parent == null ? "" : parent;
        }
        // 父路径为空：返回自己的标题
        if (parent == null || parent.isBlank()) {
            return title;
        }
        // 都有：拼成 "父 > 子"
        return parent + " > " + title;
    }

    /** 标题树上的一个节点。 */
    private static final class Section {
        private final int level;      // = 的个数，根节点是 0
        private final String title;   // 标题文字
        private final String anchor;  // 标题上方的 [[xxx]] id，没有就是空串
        private final String headingLine;   // 标题原样那行，比如 "== Before Advice"
        private final List<AsciiDocBlock> blocks = new ArrayList<>();   // 本节自己的正文，不含子孙
        private final List<Section> children = new ArrayList<>();        // 子节

        private Section(int level, String title, String anchor, String headingLine) {
            this.level = level;
            this.title = title;
            // anchor / headingLine 允许传 null，统一转成空串，避免后面 NPE
            this.anchor = anchor == null ? "" : anchor;
            this.headingLine = headingLine == null ? "" : headingLine;
        }
    }

    /** 定好但还没挂元数据的段，最后统一转成 TextSegment。 */
    private record Draft(String text, String headingPath, int headingLevel, String docTitle, String anchor,
                         boolean hasCode) {
    }
}