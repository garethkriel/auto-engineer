package ae.mdlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ae.mdlite.Block.CodeBlock;
import ae.mdlite.Block.Heading;
import ae.mdlite.Block.ListBlock;
import ae.mdlite.Block.Paragraph;
import ae.mdlite.Block.Quote;
import ae.mdlite.Block.Rule;
import ae.mdlite.Inline.Code;
import ae.mdlite.Inline.Emph;
import ae.mdlite.Inline.Link;
import ae.mdlite.Inline.Strong;
import ae.mdlite.Inline.Text;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MarkdownTest {

    private static List<Block> blocks(String src) {
        return Markdown.parse(src).blocks();
    }

    private static List<Inline> para(String src) {
        List<Block> b = blocks(src);
        assertEquals(1, b.size(), "expected one block for: " + src);
        return ((Paragraph) b.get(0)).inlines();
    }

    private static void assertErr(String src, int line, int col) {
        MarkdownException e = assertThrows(MarkdownException.class, () -> Markdown.parse(src), src);
        assertEquals(line + ":" + col, e.line() + ":" + e.column(), "doc=" + src + " msg=" + e.getMessage());
    }

    // ---- blocks -------------------------------------------------------

    @Test
    void emptyAndBlankDocumentsHaveNoBlocks() {
        assertEquals(List.of(), blocks(""));
        assertEquals(List.of(), blocks("\n  \n\n"));
    }

    @Test
    void headingsLevelOneToSix() {
        assertEquals(List.of(new Heading(1, List.of(new Text("Hi")))), blocks("# Hi"));
        assertEquals(List.of(new Heading(6, List.of(new Text("x")))), blocks("###### x"));
    }

    @Test
    void sevenHashesIsAParagraphAndHashWithoutSpaceIsText() {
        assertEquals(List.of(new Text("####### x")), para("####### x"));
        assertEquals(List.of(new Text("#tag")), para("#tag"));
    }

    @Test
    void bareHashIsEmptyHeading() {
        assertEquals(List.of(new Heading(1, List.of())), blocks("#"));
    }

    @Test
    void paragraphLinesKeepSoftBreakAndBlankLineSplits() {
        assertEquals(List.of(new Text("a\nb")), para("a\nb"));
        assertEquals(2, blocks("a\n\nb").size());
    }

    @Test
    void paragraphEndsAtHeadingAndFenceAndList() {
        List<Block> b = blocks("text\n# H\nmore\n- item\n```\nx\n```");
        assertEquals(5, b.size());
        assertTrue(b.get(0) instanceof Paragraph);
        assertTrue(b.get(1) instanceof Heading);
        assertTrue(b.get(2) instanceof Paragraph);
        assertTrue(b.get(3) instanceof ListBlock);
        assertTrue(b.get(4) instanceof CodeBlock);
    }

    @Test
    void fencedCodeKeepsRawTextAndLanguage() {
        assertEquals(List.of(new CodeBlock("java", "a *b*\n  # c\n")), blocks("```java\na *b*\n  # c\n```"));
        assertEquals(List.of(new CodeBlock("", "")), blocks("```\n```"));
    }

    @Test
    void unclosedFenceReportsFenceLine() {
        assertErr("ok\n\n```js\ncode\n", 3, 1);
    }

    @Test
    void badFenceLanguageIsRejectedAtLanguageColumn() {
        assertErr("```ja\"va\nx\n```", 1, 4);
    }

    @ParameterizedTest
    @CsvSource({"---", "***", "_____", "-------"})
    void rules(String src) {
        assertEquals(List.of(new Rule()), blocks(src));
    }

    @Test
    void twoDashesIsNotARule() {
        assertEquals(List.of(new Text("--")), para("--"));
    }

    @Test
    void bulletAndOrderedLists() {
        assertEquals(List.of(new ListBlock(false, List.of(List.of(new Text("a")), List.of(new Text("b"))))),
                blocks("- a\n* b"));
        assertEquals(List.of(new ListBlock(true, List.of(List.of(new Text("x")), List.of(new Text("y"))))),
                blocks("1. x\n2. y"));
    }

    @Test
    void changingListKindStartsNewListAndBlankLineSplits() {
        List<Block> b = blocks("- a\n1. b\n\n- c");
        assertEquals(3, b.size());
        assertEquals(false, ((ListBlock) b.get(0)).ordered());
        assertEquals(true, ((ListBlock) b.get(1)).ordered());
        assertEquals(false, ((ListBlock) b.get(2)).ordered());
    }

    @Test
    void listItemContinuationLinesJoinWithNewline() {
        assertEquals(List.of(new ListBlock(false,
                List.of(List.of(new Text("a\nb "), new Emph(List.of(new Text("c"))))))),
                blocks("- a\n  b *c*"));
    }

    @Test
    void quoteParsesNestedBlocks() {
        assertEquals(List.of(new Quote(List.of(new Heading(2, List.of(new Text("t"))),
                new Paragraph(List.of(new Text("p")))))), blocks("> ## t\n>\n> p"));
    }

    @Test
    void quoteDepthCap() {
        assertEquals(1, blocks(">".repeat(50) + " x").size());
        assertErr(">".repeat(51) + " x", 1, 51);
        assertErr("a\n\n" + ">".repeat(100000), 3, 51);
    }

    @Test
    void windowsLineEndingsAreAccepted() {
        assertEquals(List.of(new Heading(1, List.of(new Text("a"))), new Paragraph(List.of(new Text("b")))),
                blocks("# a\r\n\r\nb\r\n"));
    }

    // ---- inlines ------------------------------------------------------

    @Test
    void emphasisStrongAndCode() {
        assertEquals(List.of(new Text("a "), new Emph(List.of(new Text("b"))), new Text(" "),
                new Strong(List.of(new Text("c"))), new Text(" "), new Code("*d*")), para("a *b* **c** `*d*`"));
    }

    @Test
    void nestedEmphasisInsideStrong() {
        assertEquals(List.of(new Strong(List.of(new Text("a "), new Emph(List.of(new Text("b"))), new Text(" c")))),
                para("**a *b* c**"));
    }

    @Test
    void unmatchedOrSpacedStarsAreLiteral() {
        assertEquals(List.of(new Text("2 * 3 * 4")), para("2 * 3 * 4"));
        assertEquals(List.of(new Text("a *b")), para("a *b"));
        assertEquals(List.of(new Text("a****")), para("a****"));
        assertEquals(List.of(new Text("a ` b")), para("a ` b"));
    }

    @Test
    void escapes() {
        assertEquals(List.of(new Text("*a* [b](c) # `d` \\ x\\y")), para("\\*a\\* \\[b\\]\\(c\\) \\# \\`d\\` \\\\ x\\y"));
    }

    @Test
    void links() {
        assertEquals(List.of(new Text("see "), new Link(List.of(new Text("the "), new Strong(List.of(new Text("docs")))),
                "https://e.com/a?b=1&c=2"), new Text(".")), para("see [the **docs**](https://e.com/a?b=1&c=2)."));
        assertEquals(List.of(new Link(List.of(new Text("x")), "/rel")), para("[x]( /rel )"));
        assertEquals(List.of(new Link(List.of(new Text("m")), "mailto:a@b.c")), para("[m](mailto:a@b.c)"));
        assertEquals(List.of(new Link(List.of(new Text("a")), "#top")), para("[a](#top)"));
    }

    @Test
    void bracketsWithoutUrlAreLiteralButContentIsStillParsed() {
        assertEquals(List.of(new Text("[a "), new Emph(List.of(new Text("b"))), new Text("] c")), para("[a *b*] c"));
        assertEquals(List.of(new Text("[x")), para("[x"));
    }

    @Test
    void unclosedUrlIsErrorAtOpeningBracket() {
        assertErr("ab\n\nhello [x](http://a", 3, 7);
    }

    @ParameterizedTest
    @CsvSource({"javascript:alert(1)", "JaVaScRiPt:x", "data:text/html;base64,AAA", "//evil.com", "ftp://x"})
    void disallowedUrlsAreRejected(String url) {
        assertErr("> [x](" + url + ")", 1, 3);
    }

    @Test
    void whitespaceInsideUrlIsRejected() {
        assertErr("[x](java\tscript:1)", 1, 1);
        assertErr("[x](a b)", 1, 1);
    }

    @Test
    void errorPositionsInsideListAndQuoteUseSourceColumns() {
        assertErr("- item [x](javascript:1)", 1, 8);
        assertErr("para\nsecond [x](javascript:1)", 2, 8);
        assertErr("> - q [x](javascript:1)", 1, 7);
    }

    // ---- rendering ----------------------------------------------------

    @Test
    void rendersFullDocument() {
        String src = "# T & <x>\n\nHello *w* **s** `c<d>` [l](https://a.b/?q=\"1\"&r=2)\nnext\n\n- one\n- two\n\n1. a\n\n"
                + "> quote\n\n---\n\n```py\nprint(\"<hi>\")\n```\n";
        String expected = "<h1>T &amp; &lt;x&gt;</h1>\n"
                + "<p>Hello <em>w</em> <strong>s</strong> <code>c&lt;d&gt;</code> "
                + "<a href=\"https://a.b/?q=&quot;1&quot;&amp;r=2\">l</a>\nnext</p>\n"
                + "<ul>\n<li>one</li>\n<li>two</li>\n</ul>\n"
                + "<ol>\n<li>a</li>\n</ol>\n"
                + "<blockquote>\n<p>quote</p>\n</blockquote>\n"
                + "<hr>\n"
                + "<pre><code class=\"language-py\">print(&quot;&lt;hi&gt;&quot;)\n</code></pre>\n";
        assertEquals(expected, Markdown.toHtml(src));
    }

    @Test
    void rendersEmptyCodeBlockAndEmptyDocument() {
        assertEquals("<pre><code></code></pre>\n", Markdown.toHtml("```\n```"));
        assertEquals("", Markdown.toHtml(""));
    }

    @Test
    void rawHtmlNeverSurvives() {
        String html = Markdown.toHtml("<script>alert(1)</script> *<b>*");
        assertTrue(!html.contains("<script>") && !html.contains("<b>"), html);
    }

    // ---- robustness ---------------------------------------------------

    @Test
    void deepInlineNestingIsErrorNotStackOverflow() {
        assertEquals(1, blocks("[".repeat(100) + "x" + "](/a)".repeat(100)).size());
        String src = "[".repeat(5000) + "x" + "](/a)".repeat(5000);
        MarkdownException e = assertThrows(MarkdownException.class, () -> Markdown.parse(src));
        assertTrue(e.getMessage().contains("nested too deeply"), e.getMessage());
        assertEquals(1, e.line());
    }

    @Test
    void adversarialUnclosedDelimitersAreLinearish() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertEquals(1, blocks("*a ".repeat(30000)).size());
            assertEquals(1, blocks("[a ".repeat(30000)).size());
            assertEquals(1, blocks("`a ".repeat(30000)).size());
            assertEquals(1, blocks("**a ".repeat(30000)).size());
        });
    }

    @Test
    void fuzzNeverThrowsAnythingButMarkdownException() {
        java.util.Random r = new java.util.Random(7);
        String alphabet = "ab *_`[]()#>-1. \n\\:/jav";
        for (int i = 0; i < 20000; i++) {
            StringBuilder sb = new StringBuilder();
            int n = r.nextInt(40);
            for (int j = 0; j < n; j++) {
                sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
            }
            String doc = sb.toString();
            try {
                String html = Markdown.toHtml(doc);
                assertTrue(!html.contains("<script"), doc);
            } catch (MarkdownException e) {
                assertTrue(e.line() >= 1 && e.column() >= 1, doc);
            }
        }
    }
}
