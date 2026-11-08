/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.palantir.javaformat.java;

import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableSet;
import com.palantir.javaformat.CommentsHelper;
import com.palantir.javaformat.Input.Tok;
import com.palantir.javaformat.Newlines;
import com.palantir.javaformat.java.javadoc.JavadocFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** {@code JavaCommentsHelper} extends {@link CommentsHelper} to rewrite Java comments. */
public final class JavaCommentsHelper implements CommentsHelper {

    private final String lineSeparator;
    private final JavaFormatterOptions options;

    /** Comments are numbered along with tokens, so the comments before the first line of code have smaller indices. */
    private final int firstTokenIndex;

    @Nullable
    private final JavadocFormatter javadocFormatter;

    public JavaCommentsHelper(JavaInput javaInput, JavaFormatterOptions options) {
        this.lineSeparator = javaInput.getLineSeparator();
        this.options = options;
        this.firstTokenIndex = javaInput.getTokens().get(0).getTok().getIndex();
        this.javadocFormatter = options.formatJavadoc() ? new JavadocFormatter(options.maxLineLength()) : null;
    }

    @Override
    public String rewrite(Tok tok, int maxWidth, int column0) {
        if (!tok.isComment()) {
            return tok.getOriginalText();
        }
        String text = tok.getOriginalText();
        if (javadocFormatter != null && tok.isJavadocComment()) {
            text = javadocFormatter.formatJavadoc(text, column0);
        }
        List<String> lines = new ArrayList<>();
        Iterator<String> it = Newlines.lineIterator(text);
        while (it.hasNext()) {
            lines.add(CharMatcher.whitespace().trimTrailingFrom(it.next()));
        }
        if (tok.isSlashSlashComment()) {
            if (isJBangHeaderLine(tok)) {
                return lines.get(0);
            }
            return indentLineComments(lines, column0);
        } else if (javadocShaped(lines)) {
            return indentJavadoc(lines, column0);
        } else {
            return preserveIndentation(lines, column0);
        }
    }

    // For non-javadoc-shaped block comments, shift the entire block to the correct
    // column, but do not adjust relative indentation.
    private String preserveIndentation(List<String> lines, int column0) {
        StringBuilder builder = new StringBuilder();

        // find the leftmost non-whitespace character in all trailing lines
        int startCol = -1;
        for (int i = 1; i < lines.size(); i++) {
            int lineIdx = CharMatcher.whitespace().negate().indexIn(lines.get(i));
            if (lineIdx >= 0 && (startCol == -1 || lineIdx < startCol)) {
                startCol = lineIdx;
            }
        }

        // output the first line at the current column
        builder.append(lines.get(0));

        // output all trailing lines with plausible indentation
        for (int i = 1; i < lines.size(); ++i) {
            builder.append(lineSeparator).append(" ".repeat(column0));
            // check that startCol is valid index, e.g. for blank lines
            if (lines.get(i).length() >= startCol) {
                builder.append(lines.get(i).substring(startCol));
            } else {
                builder.append(lines.get(i));
            }
        }
        return builder.toString();
    }

    // Wraps and re-indents line comments.
    private String indentLineComments(List<String> lines, int column0) {
        lines = wrapLineComments(lines, column0);
        StringBuilder builder = new StringBuilder();
        builder.append(lines.get(0).trim());
        String indentString = " ".repeat(column0);
        for (int i = 1; i < lines.size(); ++i) {
            builder.append(lineSeparator)
                    .append(indentString)
                    .append(lines.get(i).trim());
        }
        return builder.toString();
    }

    // JBang reads the directives of a script, such as `//DEPS info.picocli:picocli:4.7.6`, from the line comments
    // before its first line of code, and a shell runs the first line, `///usr/bin/env jbang "$0" "$@" ; exit $?`, when
    // the file is executed. A space after the slashes or a wrapped line breaks both, so these lines stay as written.
    // https://www.jbang.dev/documentation/jbang/latest/script-directives.html
    private boolean isJBangHeaderLine(Tok tok) {
        if (tok.getIndex() >= firstTokenIndex) {
            return false;
        }
        String text = tok.getOriginalText();
        boolean firstLine = tok.getIndex() == 0;
        return isJBangDirective(text)
                || (firstLine && SHELL_COMMAND.matcher(text).lookingAt());
    }

    // The directive names JBang knows, from dev.jbang.source.parser.Directives.Names.
    private static final ImmutableSet<String> JBANG_DIRECTIVE_NAMES = ImmutableSet.of(
            "CDS",
            "COMPILE_OPTIONS",
            "DEPS",
            "DESCRIPTION",
            "DOCS",
            "FILES",
            "GAV",
            "GROOVY",
            "JAVA",
            "JAVAAGENT",
            "JAVAC_OPTIONS",
            "JAVA_OPTIONS",
            "KOTLIN",
            "MAIN",
            "MANIFEST",
            "MODULE",
            "NATIVE_OPTIONS",
            "NOINTEGRATIONS",
            "PREVIEW",
            "REPOS",
            "RUNTIME_OPTIONS",
            "SOURCES");

    // JBang's syntax: the name right after the slashes, then whitespace or the end of the line. A name with a prefix,
    // such as Quarkus's `//Q:CONFIG`, belongs to a build integration, so it is kept whatever follows the prefix.
    private static final Pattern JBANG_DIRECTIVE = Pattern.compile("//([A-Z]+:)?([A-Z_]+)(?=\\s|$)");

    // A line comment whose first word is a path: the command a shell runs.
    private static final Pattern SHELL_COMMAND = Pattern.compile("//+[^\\s/]+/");

    private static boolean isJBangDirective(String text) {
        Matcher matcher = JBANG_DIRECTIVE.matcher(text);
        return matcher.lookingAt() && (matcher.group(1) != null || JBANG_DIRECTIVE_NAMES.contains(matcher.group(2)));
    }

    private static final Pattern LINE_COMMENT_MISSING_SPACE_PREFIX = Pattern.compile("^(//+)[^\\s/]");

    // Preserve special `//noinspection` and `//$NON-NLS-x$` comments used by IDEs, which cannot
    // contain leading spaces.
    private static final Pattern LINE_COMMENT_NO_SPACE_PREFIX =
            Pattern.compile("^//+(noinspection|\\$NON-NLS-\\d+\\$)");

    /**
     * Adds the missing space after the slashes of a line comment: {@code //foo} becomes {@code // foo}. The IDE markers
     * {@code //noinspection} and {@code //$NON-NLS-n$} stay as written, since their tools recognise them only without
     * the space. {@link com.palantir.javaformat.doc.Comment#computeFlat()} applies the same rule to a comment that is
     * written flat, for which {@link #rewrite} is not called.
     */
    public static String addMissingSpace(String line) {
        var matcher = LINE_COMMENT_MISSING_SPACE_PREFIX.matcher(line);
        if (matcher.find() && !LINE_COMMENT_NO_SPACE_PREFIX.matcher(line).find()) {
            var length = matcher.group(1).length();
            return "/".repeat(length) + " " + line.substring(length);
        }
        return line;
    }

    static String lineCommentPrefix(String line) {
        int prefixLength = 0;
        while (prefixLength < line.length() && line.charAt(prefixLength) == '/') {
            prefixLength++;
        }
        return "/".repeat(prefixLength);
    }

    /**
     * The length of the slashes and the first word of a line comment, which stay on its line however it is wrapped:
     * {@link #wrapLineComments} breaks a line only at whitespace after them.
     */
    public static int firstWordLength(String line) {
        var prefixLength = lineCommentPrefix(line).length();
        for (var i = prefixLength + 1; i < line.length(); i++) {
            if (CharMatcher.breakingWhitespace().matches(line.charAt(i))) {
                return i;
            }
        }
        return line.length();
    }

    private List<String> wrapLineComments(List<String> lines, int column0) {
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            // From JDK 23 on, javac returns a run of `///` markdown lines as a single comment tok, so
            // every line after the first still carries its source indentation. indentLineComments
            // trims and re-indents all of them, so both the slash prefix and the width budget have to
            // be read from the trimmed text: otherwise the prefix comes back empty and the wrapped
            // remainder is emitted as bare code.
            line = CharMatcher.whitespace().trimLeadingFrom(line);
            line = addMissingSpace(line);
            if (NonNlsMarkers.isMarkersOnly(line)) {
                // Eclipse reads the markers only on the line of their literal, so a run of them is never wrapped.
                result.add(line);
                continue;
            }
            // Preserve the original slash prefix (e.g. `///` for markdown docstrings) on wrapped lines.
            String prefix = lineCommentPrefix(line);
            while (line.length() + column0 > options.maxLineLength()) {
                int idx = options.maxLineLength() - column0;
                // only break on whitespace characters, and ignore the leading `// `. Not on a no-break space
                // such as U+00A0: the new line would start with `//` and that space, which the next run takes
                // for a missing space and pads, and then the comment never settles.
                while (idx >= prefix.length()
                        && !CharMatcher.breakingWhitespace().matches(line.charAt(idx))) {
                    idx--;
                }
                if (idx <= prefix.length()) {
                    break;
                }
                result.add(line.substring(0, idx));
                line = prefix + line.substring(idx);
            }
            result.add(line);
        }
        return result;
    }

    // Remove leading whitespace (trailing was already removed), and re-indent.
    // Add a +1 indent before '*', and add the '*' if necessary.
    private String indentJavadoc(List<String> lines, int column0) {
        StringBuilder builder = new StringBuilder();
        builder.append(lines.get(0).trim());
        int indent = column0 + 1;
        String indentString = " ".repeat(indent);
        for (int i = 1; i < lines.size(); ++i) {
            builder.append(lineSeparator).append(indentString);
            String line = lines.get(i).trim();
            if (!line.startsWith("*")) {
                builder.append("* ");
            }
            builder.append(line);
        }
        return builder.toString();
    }

    // Returns true if the comment looks like javadoc
    private static boolean javadocShaped(List<String> lines) {
        Iterator<String> it = lines.iterator();
        if (!it.hasNext()) {
            return false;
        }
        String first = it.next().trim();
        // if it's actually javadoc, we're done
        if (first.startsWith("/**")) {
            return true;
        }
        // if it's a block comment, check all trailing lines for '*'
        if (!first.startsWith("/*")) {
            return false;
        }
        while (it.hasNext()) {
            if (!it.next().trim().startsWith("*")) {
                return false;
            }
        }
        return true;
    }
}
