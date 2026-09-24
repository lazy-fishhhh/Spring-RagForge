package com.springragforge.rag.splitter;

/**
 * AsciiDoc 文档里切出来的一个原子块。切分时只在块这一层拼接，块内部不再拆开。
 *
 * <p>这是一个 record（Java 16+），编译器会自动生成：
 * <ul>
 *     <li>一个包含全部字段的构造器</li>
 *     <li>每个字段的访问方法：type()、text()、headingLevel()、headingTitle()、anchor()</li>
 *     <li>equals()、hashCode()、toString()</li>
 * </ul>
 *
 * <p>因为它是 record，所以是不可变的：创建之后字段不能再改。
 * 块与块之间的区分就靠这些字段，尤其是 {@code type}。
 *
 * @param type         块类型（标题、段落、代码块……）
 * @param text         块的原始文本，原样保留（含缩进、换行）
 * @param headingLevel 标题层级，只有 HEADING 类型才有意义；其他类型为 0
 * @param headingTitle 标题文字，只有 HEADING 类型才有意义；其他类型为空串
 * @param anchor       标题的锚点 id，只有 HEADING 类型才可能有值；其他类型为空串
 */
record AsciiDocBlock(Type type, String text, int headingLevel, String headingTitle, String anchor) {

    /** 块类型，注释里是对应的 AsciiDoc 写法。 */
    enum Type {
        HEADING,     // = 标题
        PARAGRAPH,   // 普通段落
        CODE,        // ---- 围起来的源码块
        TABLE,       // |=== 表格
        TABS,        // [tabs] + ==== 多语言示例
        ADMONITION,  // [NOTE]/[TIP] 等 + ==== 告示块
        LITERAL      // .... 原样输出块
    }

    /**
     * 创建一个标题块。
     *
     * <p>只有标题才需要 level、title、anchor 这三个额外信息，
     * 所以单独提供一个工厂方法，避免调用方手动传一堆参数。
     *
     * @param text   标题行的原始文本，比如 "== AOP 通知"
     * @param level  等号个数，也就是标题层级。= 是 1 级，== 是 2 级，以此类推
     * @param title  标题文字，比如 "AOP 通知"
     * @param anchor 标题上一行 [[xxx]] 里的 id；没有锚点就传 null
     * @return 一个 HEADING 类型的块
     */
    static AsciiDocBlock heading(String text, int level, String title, String anchor) {
        // anchor 可能是 null，统一转成空串，避免后续使用时到处判空。
        return new AsciiDocBlock(Type.HEADING, text, level, title, anchor == null ? "" : anchor);
    }

    /**
     * 创建一个非标题块（段落、代码块、表格等）。
     *
     * <p>这些块用不到 headingLevel / headingTitle / anchor，
     * 所以统一填 0 和空串。
     *
     * @param type 块类型
     * @param text 块的原始文本
     * @return 对应类型的块
     */
    static AsciiDocBlock of(Type type, String text) {
        return new AsciiDocBlock(type, text, 0, "", "");
    }

    /**
     * 判断这个块是不是“空的”。
     *
     * <p>text 为 null，或者只有空白字符（空格、换行、制表符），就算空块。
     * 用来过滤掉切分过程中可能产生的无意义块。
     */
    boolean isBlank() {
        return text == null || text.isBlank();
    }
}