package com.springragforge.rag.source;

import java.util.Map;

/**
 * 把本地语料文件换算成官方文档站链接，形如
 * {@code https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.spring-mvc}。
 *
 * <p>每个语料源一行映射：本地目录名对应「要丢掉的本地路径段 + 文档站 URL 前缀」。
 * 目录名和站点路径并不总是一致，所以是显式映射表，不做猜测。
 *
 * <p>换算只做字符串处理，不用 {@code Path.relativize}：语料路径的盘符大小写、分隔符写法
 * 在不同机器上不一致，Path 会直接抛异常。
 *
 * <p>语料根目录可配置：灌库时传当前配置的根，默认 {@value #DEFAULT_CORPUS_ROOT}。
 * 根目录决定「哪些文件算语料」——根之外的文件换不出 docKey，也就算不出 chunk_id。
 */
public final class SourceLinkResolver {

    /** 默认语料根目录。下面一层目录就是一个语料源。 */
    public static final String DEFAULT_CORPUS_ROOT = "E:/springDoc/corpus";

    /** 当前使用的语料根目录。 */
    private final String corpusRoot;

    /** 用默认语料根目录。 */
    public SourceLinkResolver() {
        this(DEFAULT_CORPUS_ROOT);
    }

    public SourceLinkResolver(String corpusRoot) {
        this.corpusRoot = corpusRoot == null ? "" : corpusRoot;
    }

    /**
     * 一个语料源。
     *
     * @param stripFromPath 本地路径里不属于 URL 的那一段，没有就是空串
     * @param urlBase       文档站 URL 前缀，后面直接接去掉上面那段的路径
     */
    private record Project(String stripFromPath, String urlBase) {
    }

    private static final Map<String, Project> PROJECTS = Map.of(
            "spring-framework", new Project("", "https://docs.spring.io/spring-framework/reference/"),
            // spring-boot 语料在本地多了 reference/pages 两层，URL 里只保留 reference
            "spring-boot", new Project("reference/pages/", "https://docs.spring.io/spring-boot/reference/"),
            "spring-ai", new Project("", "https://docs.spring.io/spring-ai/reference/")
    );

    /** 本地位置，项目名 + 项目内目录 + 去掉扩展名的文件名。 */
    private record Located(String project, String relativeDir, String fileBase) {
    }

    /**
     * 文档标识，形如 {@code spring-boot/reference/pages/web/servlet.adoc}。
     * 相对语料根算，不依赖映射表，用来拼 chunk_id。
     */
    public String documentKey(String absoluteDirectoryPath, String fileName) {
        Located located = locate(absoluteDirectoryPath, fileName);
        if (located == null) {
            return "";
        }
        String dir = join(located.project(), located.relativeDir());
        return dir.isEmpty() ? fileName : dir + "/" + fileName;
    }

    /**
     * 文档站链接。项目不在映射表里、或路径不在语料根下时返回空串，调用方据此不写该字段。
     *
     * @param anchor 标题锚点 id，没有就传空串；只拼页级链接
     */
    public String source(String absoluteDirectoryPath, String fileName, String anchor) {
        Located located = locate(absoluteDirectoryPath, fileName);
        if (located == null) {
            return "";
        }
        Project project = PROJECTS.get(located.project());
        if (project == null) {
            return "";
        }
        String path = stripPrefix(located.relativeDir(), project.stripFromPath());
        String url = project.urlBase()
                + (path.isEmpty() ? "" : path + "/")
                + located.fileBase() + ".html";
        return anchor == null || anchor.isBlank() ? url : url + "#" + anchor;
    }

    /** 把绝对目录路径拆成项目名和项目内目录。不在语料根下则返回 null。 */
    private Located locate(String absoluteDirectoryPath, String fileName) {
        if (absoluteDirectoryPath == null || absoluteDirectoryPath.isBlank()
                || fileName == null || fileName.isBlank()) {
            return null;
        }
        String root = normalize(corpusRoot);
        String dir = normalize(absoluteDirectoryPath);
        if (!dir.toLowerCase().startsWith(root.toLowerCase() + "/")) {
            return null;
        }
        String rest = dir.substring(root.length() + 1);
        int slash = rest.indexOf('/');
        String project = slash < 0 ? rest : rest.substring(0, slash);
        String relativeDir = slash < 0 ? "" : rest.substring(slash + 1);
        if (project.isEmpty()) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        String fileBase = dot <= 0 ? fileName : fileName.substring(0, dot);
        return new Located(project, relativeDir, fileBase);
    }

    /** 反斜杠换正斜杠，去掉首尾斜杠。 */
    private static String normalize(String path) {
        String normalized = path.replace('\\', '/');
        int start = 0;
        while (start < normalized.length() && normalized.charAt(start) == '/') {
            start++;
        }
        int end = normalized.length();
        while (end > start && normalized.charAt(end - 1) == '/') {
            end--;
        }
        return normalized.substring(start, end);
    }

    /** 去掉前缀段。前缀不匹配时原样返回。 */
    private static String stripPrefix(String path, String prefix) {
        if (prefix.isEmpty()) {
            return path;
        }
        String bare = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        if (path.equals(bare)) {
            return "";
        }
        return path.startsWith(bare + "/") ? path.substring(bare.length() + 1) : path;
    }

    private static String join(String left, String right) {
        if (left.isEmpty()) {
            return right;
        }
        return right.isEmpty() ? left : left + "/" + right;
    }
}
