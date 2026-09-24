package com.springragforge.rag.splitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 AsciiDoc 源码扫成块。代码围栏、tabs、表格、告示块作为原子块，内部不再切开。
 */
final class AsciiDocBlockScanner {

    // ========== 各种结构性写法的正则 ==========
    // 注释里是对应的 AsciiDoc 源码示例

    // 标题：= 标题、== 二级标题 …… ====== 六级标题
    private static final Pattern HEADING = Pattern.compile("^(={1,6})\\s+(.+?)\\s*$");

    // 锚点：[[aop-advice]] 或 [[aop-advice,别名]]
    private static final Pattern ANCHOR = Pattern.compile("^\\[\\[([^\\],]+)(?:,[^\\]]*)?\\]\\]\\s*$");

    // 属性行：:toc: left、:tabs:、:foo!: 等
    private static final Pattern ATTRIBUTE = Pattern.compile("^:[A-Za-z][\\w-]*!?:(?:\\s.*)?$");

    // 代码块分隔符：----（4个及以上减号）
    private static final Pattern DASH_DELIM = Pattern.compile("^-{4,}$");

    // ==== 分隔符：可能是 tabs，也可能是告示块
    private static final Pattern EQUALS_DELIM = Pattern.compile("^={4,}$");

    // 原样块分隔符：....（4个及以上点）
    private static final Pattern DOT_DELIM = Pattern.compile("^\\.{4,}$");

    // 表格分隔符：|===
    private static final Pattern TABLE_DELIM = Pattern.compile("^\\|={3,}$");

    // 整块注释分隔符：////
    private static final Pattern COMMENT_DELIM = Pattern.compile("^/{4,}$");

    // 告示块的名字集合：NOTE、TIP、WARNING、IMPORTANT、CAUTION、EXAMPLE
    private static final Set<String> ADMONITION_NAMES =
            Set.of("NOTE", "TIP", "WARNING", "IMPORTANT", "CAUTION", "EXAMPLE");

    // 工具类，不允许实例化
    private AsciiDocBlockScanner() {
    }

    /**
     * 主入口：把整篇 AsciiDoc 文本扫描成块列表。
     */
    static List<AsciiDocBlock> scan(String text) {
        // 统一换行符：把 \r\n 和 \r 都换成 \n，再按行拆开
        List<String> lines = text.replace("\r\n", "\n").replace('\r', '\n').lines().toList();

        List<AsciiDocBlock> blocks = new ArrayList<>();

        // pendingAnchor：暂存锚点。
        // 因为 [[anchor]] 通常写在标题上一行，遇到锚点时先存起来，
        // 等下一行遇到标题时再挂到这个标题上。
        String pendingAnchor = "";

        int i = 0; // 当前处理到第几行
        while (i < lines.size()) {
            String raw = lines.get(i);        // 原始行（保留缩进）
            String trimmed = raw.trim();      // 去掉首尾空白后的行

            // 空行直接跳过
            if (trimmed.isEmpty()) {
                i++;
                continue;
            }

            // 属性行（:toc:、:tabs: 之类）和图片行不是正文，直接跳过
            if (ATTRIBUTE.matcher(trimmed).matches() || trimmed.startsWith("image::")) {
                i++;
                continue;
            }

            // //// 包围的是整块注释，连内容一起跳过
            if (COMMENT_DELIM.matcher(trimmed).matches()) {
                i = skipDelimited(lines, i, trimmed); // 跳到注释结束的下一行
                continue;
            }

            // 锚点行：[[anchor-id]]
            Matcher anchorMatcher = ANCHOR.matcher(trimmed);
            if (anchorMatcher.matches()) {
                pendingAnchor = anchorMatcher.group(1).trim(); // 先存着
                i++;
                continue;
            }

            // 标题行：= 标题
            Matcher headingMatcher = HEADING.matcher(trimmed);
            if (headingMatcher.matches()) {
                int level = headingMatcher.group(1).length(); // = 的个数就是层级
                String title = headingMatcher.group(2).trim(); // 标题文字
                // 把之前暂存的锚点挂到这个标题上
                blocks.add(AsciiDocBlock.heading(trimmed, level, title, pendingAnchor));
                pendingAnchor = ""; // 锚点用掉了，清空
                i++;
                continue;
            }

            // ---- / ==== / |=== 这些带收尾符的块，整块当一个原子块收走
            DelimitedBlock delimited = tryReadDelimited(lines, i);
            if (delimited != null) {
                blocks.add(AsciiDocBlock.of(delimited.type, delimited.text));
                i = delimited.endIndex; // 跳到块结束的下一行
                continue;
            }

            // 剩下的按段落收：
            // 从当前行开始，一路吃到空行，或者下一个结构性开头（标题、块、锚点）为止
            int next = i + 1;
            StringBuilder paragraph = new StringBuilder(raw);
            while (next < lines.size()) {
                String nextTrimmed = lines.get(next).trim();
                // 遇到空行或结构性开头，段落就该收尾了
                if (nextTrimmed.isEmpty() || isStructuralStart(lines, next)) {
                    break;
                }
                paragraph.append('\n').append(lines.get(next));
                next++;
            }
            blocks.add(AsciiDocBlock.of(AsciiDocBlock.Type.PARAGRAPH, paragraph.toString()));
            i = next;
        }
        return blocks;
    }

    /**
     * 判断这一行是不是一个新的结构性开头，是的话当前段落就该收尾了。
     */
    private static boolean isStructuralStart(List<String> lines, int index) {
        String trimmed = lines.get(index).trim();

        // 空行、属性行、图片、注释、锚点、标题，都算结构性开头
        if (trimmed.isEmpty()
                || ATTRIBUTE.matcher(trimmed).matches()
                || trimmed.startsWith("image::")
                || COMMENT_DELIM.matcher(trimmed).matches()
                || ANCHOR.matcher(trimmed).matches()
                || HEADING.matcher(trimmed).matches()) {
            return true;
        }

        // 如果能读出一个带收尾符的块，也算结构性开头
        return tryReadDelimited(lines, index) != null;
    }

    /**
     * 试着从 start 行开始读一个带收尾符的块，读不到就返回 null。
     * <p>
     * 麻烦的地方在于收尾符本身看不出是什么块：==== 既可能是 [tabs]，也可能是 [NOTE]。
     * 所以先往后扫几行，把分隔符前面那些方括号行（[tabs]、[NOTE]）记下来，再用它们判断类型。
     */
    private static DelimitedBlock tryReadDelimited(List<String> lines, int start) {
        int j = start;
        boolean sawTabs = false;        // 是否看到 [tabs]
        boolean sawAdmonition = false;  // 是否看到 [NOTE] 之类的告示块标记

        // 先跳过前面的方括号行（[tabs]、[NOTE]、[source,java] 等），
        // 同时记录看到了哪些标记
        while (j < lines.size()) {
            String trimmed = lines.get(j).trim();

            // 空行在块开始之后允许跳过（j > start 表示不是第一行）
            if (trimmed.isEmpty() && j > start) {
                j++;
                continue;
            }

            // 方括号行：[tabs]、[NOTE]、[source,java] 等
            if (isBracketLine(trimmed)) {
                String name = bracketName(trimmed); // 取出方括号里的第一个名字
                if ("tabs".equals(name)) {
                    sawTabs = true;
                }
                if (ADMONITION_NAMES.contains(name.toUpperCase(Locale.ROOT))) {
                    sawAdmonition = true;
                }
                j++;
                continue;
            }

            // 遇到不是方括号行，就跳出循环，准备看这一行是不是分隔符
            break;
        }

        // 如果已经到文件末尾，说明没有块
        if (j >= lines.size()) {
            return null;
        }

        // 这一行应该是分隔符，比如 ----、====、|===
        String delimiter = lines.get(j).trim();

        // 根据分隔符 + 前面看到的标记，判断块类型
        AsciiDocBlock.Type type = classifyDelimiter(delimiter, sawTabs, sawAdmonition);
        if (type == null) {
            return null; // 不是已知的分隔符，说明不是块
        }

        // 块从 start（含前面的 [tabs] 属性行）一直收到收尾符，整段原样保留
        int end = findClosing(lines, j, delimiter);

        // joinLines 把 start 到 end（不含）之间的行拼成完整文本
        return new DelimitedBlock(type, joinLines(lines, start, end), end);
    }

    /**
     * 分隔符到块类型的映射。
     * ==== 靠前面有没有 [tabs] 区分：是 tabs 就返回 TABS，否则按告示块算。
     */
    private static AsciiDocBlock.Type classifyDelimiter(String delimiter, boolean sawTabs, boolean sawAdmonition) {
        if (DASH_DELIM.matcher(delimiter).matches()) {
            return AsciiDocBlock.Type.CODE;        // ---- 代码块
        }
        if (TABLE_DELIM.matcher(delimiter).matches()) {
            return AsciiDocBlock.Type.TABLE;       // |=== 表格
        }
        if (DOT_DELIM.matcher(delimiter).matches()) {
            return AsciiDocBlock.Type.LITERAL;     // .... 原样块
        }
        if (EQUALS_DELIM.matcher(delimiter).matches()) {
            if (sawTabs) {
                return AsciiDocBlock.Type.TABS;    // ==== + [tabs] → tabs 块
            }
            return AsciiDocBlock.Type.ADMONITION;  // ==== 其他情况 → 告示块
        }
        return null; // 不认识的 delimiter
    }

    /**
     * 返回收尾符下一行的下标；没找到配对就认为块一直到文件末尾。
     */
    private static int findClosing(List<String> lines, int openIndex, String delimiter) {
        for (int i = openIndex + 1; i < lines.size(); i++) {
            // 找到和开头的分隔符完全一样的行，就认为块结束
            if (delimiter.equals(lines.get(i).trim())) {
                return i + 1; // 返回收尾符的下一行
            }
        }
        return lines.size(); // 没找到配对，就到文件末尾
    }

    /**
     * 跳过一整块被 delimiter 包围的内容（用于 //// 整块注释）。
     */
    private static int skipDelimited(List<String> lines, int openIndex, String delimiter) {
        return findClosing(lines, openIndex, delimiter);
    }

    /**
     * [tabs] 这种算方括号行；[[anchor]] 是锚点，不算。
     */
    private static boolean isBracketLine(String trimmed) {
        return trimmed.startsWith("[") && trimmed.endsWith("]") && !trimmed.startsWith("[[");
    }

    /**
     * 从 [source,java,indent=0] 里取出 source；
     * 从 [NOTE] 里取出 NOTE；
     * 从 [%hardbreaks] 里取出 hardbreaks。
     */
    private static String bracketName(String trimmed) {
        // 去掉首尾的方括号
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();

        // 如果有逗号，只取逗号前面的部分：[source,java] → source
        int comma = inner.indexOf(',');
        if (comma >= 0) {
            inner = inner.substring(0, comma);
        }

        // 如果有等号，只取等号前面的部分：[foo=bar] → foo
        int eq = inner.indexOf('=');
        if (eq >= 0) {
            inner = inner.substring(0, eq);
        }

        // 去掉开头的 %：[%hardbreaks] → hardbreaks
        if (inner.startsWith("%")) {
            inner = inner.substring(1);
        }

        return inner.trim();
    }

    /**
     * 把 lines 中 [start, endExclusive) 范围的行拼成一个字符串，行间用 \n 连接。
     */
    private static String joinLines(List<String> lines, int start, int endExclusive) {
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < endExclusive; i++) {
            if (i > start) {
                builder.append('\n');
            }
            builder.append(lines.get(i));
        }
        return builder.toString();
    }

    /**
     * 内部记录类：表示一个读到的带收尾符的块。
     * type     块类型
     * text     块的完整原文
     * endIndex 块结束后的下一行下标
     */
    private record DelimitedBlock(AsciiDocBlock.Type type, String text, int endIndex) {
    }
}