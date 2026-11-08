/*
 * (c) Copyright 2026 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.javaformat.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.javaformat.java.JavaFormatterOptions.Style;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests comment rewriting against the comment toks javac produces, without needing the JDK that produces them. From
 * JDK 23 on, a run of {@code ///} markdown lines (JEP 467) arrives as a single comment tok whose lines after the first
 * carry their source indentation; before 23 each {@code //} line is its own tok. Building the tok directly covers the
 * multi-line shape on any JDK.
 */
final class JavaCommentsHelperTest {

    private static final int COLUMN = 4;

    /** Rewrites a comment tok that sits at column {@link #COLUMN}, as javac 23 or later would report it. */
    private static String rewrite(String commentText, Style style) throws FormatterException {
        JavaFormatterOptions options =
                JavaFormatterOptions.builder().style(style).build();
        JavaInput.Tok tok = new JavaInput.Tok(0, commentText, commentText, 0, COLUMN, false, null);
        // The helper reads the line separator and the index of the first code token from the input. Any input whose
        // first token has index 0 will do: the tok above then counts as a comment inside the code, which is wrapped.
        JavaInput input = new JavaInput("class T {}\n");
        return new JavaCommentsHelper(input, options).rewrite(tok, options.maxLineLength(), COLUMN);
    }

    /** A run of {@code ///} lines, indented as javac reports it, with {@code body} as the second line's text. */
    private static String markdownRun(String body) {
        return "/// Summary line.\n" + " ".repeat(COLUMN) + "/// " + body;
    }

    private static String wordsOfLength(int length) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < length) {
            sb.append(sb.length() == 0 ? "" : " ").append("word");
        }
        return sb.substring(0, length);
    }

    @Test
    void everyWrappedLineKeepsTheSlashPrefix() throws Exception {
        // Before the fix the prefix was read from the untrimmed continuation line, came back empty, and the
        // overflow was emitted as bare code -- which stops being a comment at all.
        String rewritten = rewrite(markdownRun(wordsOfLength(140) + " @Deprecated"), Style.OJF);

        assertThat(rewritten).contains("\n");
        assertThat(rewritten.lines()).allSatisfy(line -> assertThat(line.trim()).startsWith("///"));
        assertThat(rewritten).doesNotContain("\n    @Deprecated");
    }

    @Test
    void aContinuationLineAtTheLimitIsNotWrapped() throws Exception {
        // The line's visual width is COLUMN + its trimmed length, so the budget is maxLineLength - COLUMN.
        int budget = Style.OJF.maxLineLength() - COLUMN;
        String body = wordsOfLength(budget - "/// ".length());

        assertThat(rewrite(markdownRun(body), Style.OJF).lines()).hasSize(2);
    }

    @Test
    void aContinuationLineOverTheLimitIsWrapped() throws Exception {
        int budget = Style.OJF.maxLineLength() - COLUMN;
        String body = wordsOfLength(budget - "/// ".length() + 1);

        assertThat(rewrite(markdownRun(body), Style.OJF).lines()).hasSize(3);
    }

    @Test
    void anUnbreakableTokenIsLeftLong() throws Exception {
        // There is nowhere to break, so the line stays over the limit rather than becoming an empty `///`
        // followed by a bare URL.
        String url = "https://example.com/" + "a".repeat(Style.OJF.maxLineLength());

        String rewritten = rewrite(markdownRun(url), Style.OJF);

        assertThat(rewritten.lines()).hasSize(2);
        assertThat(rewritten).contains("/// " + url);
    }

    @Test
    @SuppressWarnings("FormatStringConcatenation") // Written out like the input, not as a template
    void theMissingSpaceRuleAppliesToEveryLineOfTheRun() throws Exception {
        // Otherwise the same source formats differently depending on whether the running JDK hands the run
        // over as one tok (23 and later) or as one tok per line.
        String rewritten = rewrite("///Summary line.\n" + " ".repeat(COLUMN) + "///More text.", Style.OJF);

        assertThat(rewritten).isEqualTo("/// Summary line.\n" + " ".repeat(COLUMN) + "/// More text.");
    }

    @Test
    void anOrdinaryLineCommentStillWraps() throws Exception {
        String rewritten = rewrite("// " + wordsOfLength(140), Style.OJF);

        assertThat(rewritten.lines()).hasSize(2);
        assertThat(rewritten.lines()).allSatisfy(line -> assertThat(line.trim()).startsWith("// "));
    }

    @Test
    void aRunOfNonNlsMarkersIsNeverWrapped() throws Exception {
        // Eclipse reads a marker only on the line of its literal, so a run that reaches past the limit stays as it is
        // instead of being wrapped at a blank into a `// //$NON-NLS-3$` line of its own.
        var run = "//$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$"
                + " //$NON-NLS-8$ //$NON-NLS-9$";

        assertThat(rewrite(run, Style.OJF)).isEqualTo(run);
    }

    @Test
    void addMissingSpacePutsABlankAfterTheSlashesWhateverTheirNumber() {
        assertThat(JavaCommentsHelper.addMissingSpace("//foo")).isEqualTo("// foo");
        assertThat(JavaCommentsHelper.addMissingSpace("///foo")).isEqualTo("/// foo");
        assertThat(JavaCommentsHelper.addMissingSpace("//-- foo")).isEqualTo("// -- foo");
    }

    @Test
    void addMissingSpaceLeavesSpacedCommentsAndIdeMarkersAlone() {
        // A blank or a tab after the slashes is a space already; the IDE markers are recognised only without one.
        var comments = List.of(
                "// foo",
                "//  two spaces",
                "//\tfoo",
                "//",
                "///",
                "//$NON-NLS-1$ //$NON-NLS-2$",
                "//noinspection unchecked");

        for (var comment : comments) {
            assertThat(JavaCommentsHelper.addMissingSpace(comment)).as(comment).isEqualTo(comment);
        }
    }
}
