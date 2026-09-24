package com.springragforge.rag.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsciiDocDocumentSplitterTest {

    private static final String DOC_TITLE = "Declaring Advice";

    private static String doc(String... sections) {
        return "[[aop-advice]]\n= " + DOC_TITLE + "\n\n" + String.join("\n\n", sections);
    }

    private static String paragraph(int length, String seed) {
        StringBuilder builder = new StringBuilder();
        while (builder.length() < length) {
            builder.append(seed).append(' ');
        }
        return builder.toString().trim();
    }
    @Test
    void a(){
        Document doc = FileSystemDocumentLoader.loadDocument(Path.of("E:\\springDoc\\corpus\\spring-boot\\reference\\pages\\web\\servlet.adoc"));
        System.out.println(doc.metadata().toMap());
    }

    @Test
    void rejectsInvalidSizes() {
        assertThatThrownBy(() -> new AsciiDocDocumentSplitter(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AsciiDocDocumentSplitter(100, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotEmitTheSameContentTwice() {
        String text = doc(
                "== Before Advice\n\n" + paragraph(120, "before") + "\n\n=== Details\n\n" + paragraph(200, "detail"),
                "== After Advice\n\n" + paragraph(300, "after"),
                "== Around Advice\n\n" + paragraph(400, "around"));

        List<TextSegment> segments = new AsciiDocDocumentSplitter().split(Document.from(text));

        Set<String> distinct = segments.stream()
                .map(s -> s.metadata().getString("heading_path") + "|" + s.text())
                .collect(Collectors.toCollection(HashSet::new));
        assertThat(distinct).hasSize(segments.size());
        assertThat(segments).isNotEmpty();
    }

    @Test
    void keepsEveryLineOfACodeBlockInOneSegment() {
        String text = doc("== AspectJ Support\n\nIntro paragraph.\n\n[source,java]\n----\n"
                + "class Sample {\n    void run() {\n        System.out.println(\"hi\");\n    }\n}\n----");

        List<TextSegment> segments = new AsciiDocDocumentSplitter().split(Document.from(text));

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).text())
                .contains("[source,java]")
                .contains("class Sample {")
                .contains("System.out.println(\"hi\");")
                .contains("----");
    }

    @Test
    void keepsEveryRowOfATableInOneSegment() {
        String text = doc("== Table Section\n\n|===\n|Name |Type\n\n|alpha |String\n\n|beta |int\n|===");

        List<TextSegment> segments = new AsciiDocDocumentSplitter().split(Document.from(text));

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).text()).contains("alpha").contains("beta").contains("|===");
    }

    @Test
    void frontMatterAndImagesAreDropped() {
        String text = "[NOTE]\n"
                + "====\n"
                + "Note body.\n"
                + "====\n\n"
                + "= " + DOC_TITLE + "\n\n"
                + ":toc: left\n\n"
                + "image::diagram.png[Diagram]\n\n"
                + "== Body\n\nReal content.";

        List<TextSegment> segments = new AsciiDocDocumentSplitter().split(Document.from(text));

        assertThat(segments).hasSize(2);
        assertThat(segments.stream().filter(s -> s.text().contains("Real content.")).findFirst().orElseThrow()
                .metadata().getString("heading_path")).isEqualTo(DOC_TITLE + " > Body");
        assertThat(segments).allSatisfy(s -> assertThat(s.text()).doesNotContain(":toc:").doesNotContain("image::"));
        assertThat(segments.stream().filter(s -> s.text().contains("Note body.")).findFirst().orElseThrow()
                .metadata().getString("heading_path")).isNull();
    }

    @Test
    void carriesHeadingMetadataOnEverySegment() {
        String text = doc("== Before Advice\n\n" + paragraph(100, "x"));

        List<TextSegment> segments = new AsciiDocDocumentSplitter().split(Document.from(text));

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).metadata().getString("doc_title")).isEqualTo(DOC_TITLE);
        assertThat(segments.get(0).metadata().getString("heading_path")).isEqualTo(DOC_TITLE + " > Before Advice");
        assertThat(segments.get(0).metadata().getInteger("chunk_total")).isEqualTo(1);
        assertThat(segments.get(0).metadata().getInteger("chunk_index")).isEqualTo(0);
    }

    @Test
    void splitsBySubsectionWhenParentSectionIsTooLarge() {
        String text = doc(
                "== Parent\n\n" + paragraph(600, "parent"),
                "=== Child\n\n" + paragraph(2900, "child"));

        List<TextSegment> segments = new AsciiDocDocumentSplitter().split(Document.from(text));

        assertThat(segments).hasSize(2);
        assertThat(segments.stream().map(s -> s.metadata().getString("heading_path")))
                .containsExactlyInAnyOrder(DOC_TITLE + " > Parent", DOC_TITLE + " > Parent > Child");
    }

    @Test
    void splitsOversizedChildlessSectionIntoSegmentsUnderLimit() {
        int max = 2000;
        String text = doc("== Wide\n\n" + paragraph(1500, "first") + "\n\n" + paragraph(1500, "second"));

        List<TextSegment> segments = new AsciiDocDocumentSplitter(max, 200).split(Document.from(text));

        assertThat(segments).hasSizeGreaterThan(1);
        String path = DOC_TITLE + " > Wide";
        for (TextSegment segment : segments) {
            assertThat(segment.metadata().getString("heading_path")).isEqualTo(path);
            assertThat(segment.text()).startsWith(path);
            assertThat(segment.text().length()).isLessThanOrEqualTo(max);
        }
    }

    @Test
    void everySegmentRepeatsTheHeadingPathSoItIsSelfContained() {
        String text = doc(
                "== First\n\n" + paragraph(1500, "a"),
                "=== Second\n\n" + paragraph(1500, "b"),
                "==== Third\n\n" + paragraph(1500, "c"));

        List<TextSegment> segments = new AsciiDocDocumentSplitter(2000, 0).split(Document.from(text));

        assertThat(segments).hasSizeGreaterThanOrEqualTo(3);
        for (TextSegment segment : segments) {
            String path = segment.metadata().getString("heading_path");
            assertThat(path).startsWith(DOC_TITLE);
            assertThat(segment.text()).startsWith(path);
        }
    }

    private static Document frameworkDoc(String text) {
        return Document.from(text, Metadata.from(Map.of(
                Document.ABSOLUTE_DIRECTORY_PATH, "E:\\springDoc\\corpus\\spring-framework\\core\\aop\\ataspectj",
                Document.FILE_NAME, "advice.adoc")));
    }

    @Test
    void attachesSourceChunkIdAndHasCode() {
        String text = doc("[[aop-advice-before]]\n== Before Advice\n\nIntro.\n\n[source,java]\n----\nint a = 1;\n----");

        List<TextSegment> segments = new AsciiDocDocumentSplitter().split(frameworkDoc(text));

        assertThat(segments).hasSize(1);
        Metadata metadata = segments.get(0).metadata();
        assertThat(metadata.getString("source"))
                .isEqualTo("https://docs.spring.io/spring-framework/reference/core/aop/ataspectj/advice.html#aop-advice-before");
        assertThat(metadata.getString("chunk_id")).isEqualTo("spring-framework/core/aop/ataspectj/advice.adoc#0");
        assertThat(metadata.getString("has_code")).isEqualTo("true");
        assertThat(metadata.getInteger("chunk_index")).isEqualTo(0);
        assertThat(metadata.getInteger("chunk_total")).isEqualTo(1);
        assertThat(metadata.containsKey(Document.ABSOLUTE_DIRECTORY_PATH)).isFalse();
        assertThat(metadata.containsKey("index")).isFalse();
    }

    @Test
    void marksMissingCodeAndOmitsAnchorFragmentWhenSectionHasNoAnchor() {
        String text = doc("== Before Advice\n\n" + paragraph(100, "x"));

        List<TextSegment> segments = new AsciiDocDocumentSplitter().split(frameworkDoc(text));

        assertThat(segments.get(0).metadata().getString("source"))
                .isEqualTo("https://docs.spring.io/spring-framework/reference/core/aop/ataspectj/advice.html");
        assertThat(segments.get(0).metadata().getString("has_code")).isEqualTo("false");
    }

    @Test
    void stripsLocalOnlyPathSegmentsForSpringBoot() {
        String text = "= Servlet Web Applications\n\n[[web.servlet.spring-mvc]]\n== Spring MVC\n\n" + paragraph(100, "x");
        Document document = Document.from(text, Metadata.from(Map.of(
                Document.ABSOLUTE_DIRECTORY_PATH, "E:\\springDoc\\corpus\\spring-boot\\reference\\pages\\web",
                Document.FILE_NAME, "servlet.adoc")));

        List<TextSegment> segments = new AsciiDocDocumentSplitter().split(document);

        assertThat(segments.get(0).metadata().getString("source"))
                .isEqualTo("https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.spring-mvc");
    }

    @Test
    void omitsSourceForUnknownCorpus() {
        String text = doc("== Body\n\n" + paragraph(100, "x"));
        Document document = Document.from(text, Metadata.from(Map.of(
                Document.ABSOLUTE_DIRECTORY_PATH, "D:\\notes\\asciidoc",
                Document.FILE_NAME, "body.adoc")));

        List<TextSegment> segments = new AsciiDocDocumentSplitter().split(document);

        assertThat(segments.get(0).metadata().getString("source")).isNull();
        assertThat(segments.get(0).metadata().getString("chunk_id")).isNull();
    }
}
