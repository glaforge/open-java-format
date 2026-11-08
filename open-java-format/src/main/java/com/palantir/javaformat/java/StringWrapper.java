/*
 * Copyright 2019 Google Inc.
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

import static com.google.common.collect.Iterables.getLast;
import static java.util.stream.Collectors.joining;

import com.google.common.base.CharMatcher;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeMap;
import com.google.common.collect.TreeRangeSet;
import com.palantir.javaformat.Newlines;
import com.palantir.javaformat.Utils;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Options;
import com.sun.tools.javac.util.Position;
import com.sun.tools.javac.util.Position.LineMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** Wraps string literals that exceed the column limit, and joins adjacent ones that share a line. */
public final class StringWrapper {

    public static final String TEXT_BLOCK_DELIMITER = "\"\"\"";

    /**
     * How many times {@link #wrapOnce} may be re-run while it is still changing the source. The indentation chosen
     * for a text block is derived from the layout around it, which this pass can itself move, so a single round is
     * not always a fixed point and the formatter would not be idempotent. See
     * <a href="https://github.com/palantir/open-java-format/issues/1343">#1343</a>.
     */
    private static final int MAX_ROUNDS = 5;

    /**
     * Reflows string literals in the given Java source code that extend past the given column limit, and joins
     * adjacent string literals that share a line.
     */
    static String wrap(final int columnLimit, String input, Formatter formatter) throws FormatterException {
        String result = wrapOnce(columnLimit, input, formatter);
        for (int round = 1; round < MAX_ROUNDS && !result.equals(input); round++) {
            input = result;
            result = wrapOnce(columnLimit, input, formatter);
        }
        return result;
    }

    private static String wrapOnce(final int columnLimit, String input, Formatter formatter) throws FormatterException {
        if (!needWrapping(columnLimit, input)) {
            // fast path
            return input;
        }

        // The pieces continue the way the formatter continues a chain of +: four columns times the multiplier of its
        // style, eight in OJF.
        var continuationIndent = 4 * formatter.options().indentationMultiplier();
        TreeRangeMap<Integer, String> replacements = getReflowReplacements(columnLimit, continuationIndent, input);
        if (replacements.asMapOfRanges().isEmpty()) {
            // needWrapping is an over-approximation, so there may be nothing to do after all.
            return input;
        }
        String firstPass =
                formatter.formatSource(input, replacements.asMapOfRanges().keySet());

        if (!firstPass.equals(input)) {
            // If formatting the replacement ranges resulted in a change, recalculate the replacements on
            // the updated input.
            input = firstPass;
            replacements = getReflowReplacements(columnLimit, continuationIndent, input);
        }

        String result = applyReplacements(input, replacements);

        // Format again, because broken strings might now fit on the first line in case of assignments
        String secondPass = formatter.formatSource(result, rangesAfterAppliedReplacements(replacements));

        if (!secondPass.equals(result)) {
            replacements = getReflowReplacements(columnLimit, continuationIndent, secondPass);
            result = applyReplacements(secondPass, replacements);
        }

        {
            // We really don't want bugs in this pass to change the behaviour of programs we're
            // formatting, so check that the pretty-printed AST is the same before and after reformatting.
            String expected = parse(input, /* allowStringFolding= */ true).toString();
            String actual = parse(result, /* allowStringFolding= */ true).toString();
            if (!expected.equals(actual)) {
                throw new FormatterException(String.format(
                        "Something has gone terribly wrong. Please file a bug: "
                                + "https://github.com/openjavaformat/open-java-format/issues/new"
                                + "\n\n=== Actual: ===\n%s\n=== Expected: ===\n%s\n",
                        actual, expected));
            }
        }

        return result;
    }

    private static ImmutableSet<Range<Integer>> rangesAfterAppliedReplacements(
            TreeRangeMap<Integer, String> replacements) {
        ImmutableSet.Builder<Range<Integer>> outputRanges = ImmutableSet.builder();
        int offset = 0;
        for (Map.Entry<Range<Integer>, String> entry :
                replacements.asMapOfRanges().entrySet()) {
            Range<Integer> range = entry.getKey();
            String replacement = entry.getValue();

            int lower = offset + range.lowerEndpoint();
            int upper = lower + replacement.length();
            outputRanges.add(Range.closedOpen(lower, upper));

            int originalLength = range.upperEndpoint() - range.lowerEndpoint();
            int newLength = upper - lower;
            offset += newLength - originalLength;
        }
        return outputRanges.build();
    }

    public static TreeRangeMap<Integer, String> getReflowReplacements(
            int columnLimit, int continuationIndent, final String input) throws FormatterException {
        return new Reflower(columnLimit, continuationIndent, input).getReflowReplacements();
    }

    private static class Reflower {

        private final String input;
        private final int columnLimit;
        private final int continuationIndent;
        private final String separator;
        private final JCTree.JCCompilationUnit unit;
        private final Position.LineMap lineMap;

        Reflower(int columnLimit, int continuationIndent, String input) throws FormatterException {
            this.columnLimit = columnLimit;
            this.continuationIndent = continuationIndent;
            this.input = input;
            this.separator = Newlines.guessLineSeparator(input);
            this.unit = parse(input, /* allowStringFolding= */ false);
            this.lineMap = unit.getLineMap();
        }

        protected TreeRangeMap<Integer, String> getReflowReplacements() {
            // Paths to string literals that extend past the column limit.
            List<TreePath> longStringLiterals = new ArrayList<>();
            // Paths to text blocks to be re-indented.
            List<TreePath> textBlocks = new ArrayList<>();
            // Every string literal, for the numbering of the NON-NLS markers of a line.
            List<Tree> stringLiterals = new ArrayList<>();
            new LongStringsAndTextBlockScanner(longStringLiterals, textBlocks, stringLiterals)
                    .scan(new TreePath(unit), null);
            TreeRangeMap<Integer, String> replacements = TreeRangeMap.create();
            indentTextBlocks(replacements, textBlocks);
            wrapLongStrings(replacements, longStringLiterals, stringLiterals);
            // Last: it leaves alone the literals that the passes above rewrite.
            joinAdjacentLiterals(replacements);
            return replacements;
        }

        /**
         * Joins adjacent string literals that share a line, so that {@code "one " + "two"} becomes {@code "one two"}:
         * there the {@code +} breaks nothing, whether the author wrote it or the formatter brought the literals onto
         * one line. Literals on different lines are wrapped on purpose and stay as they are.
         */
        private void joinAdjacentLiterals(TreeRangeMap<Integer, String> replacements) {
            new ConcatenationScanner(replacements).scan(new TreePath(unit), null);
        }

        private class ConcatenationScanner extends TreePathScanner<Void, Void> {

            private final TreeRangeMap<Integer, String> replacements;

            ConcatenationScanner(TreeRangeMap<Integer, String> replacements) {
                this.replacements = replacements;
            }

            @Override
            public Void visitBinary(BinaryTree node, Void unused) {
                // Only the root of a chain of +, so that `a + b + c` is flattened once.
                if (node.getKind() == Kind.PLUS
                        && getCurrentPath().getParentPath().getLeaf().getKind() != Kind.PLUS) {
                    joinRuns(flattenExpressionTree(node));
                }
                return super.visitBinary(node, null);
            }

            /** Replaces each longest run of adjacent literals that can be joined with the one literal they make. */
            private void joinRuns(List<Tree> flat) {
                var start = 0;
                while (start < flat.size()) {
                    if (!isJoinableLiteral(flat.get(start))) {
                        start++;
                        continue;
                    }
                    var end = start + 1;
                    while (end < flat.size() && canJoinWithPrevious(flat.get(end - 1), flat.get(end))) {
                        end++;
                    }
                    if (end - start > 1) {
                        putJoinReplacement(flat.subList(start, end));
                    }
                    start = end;
                }
            }

            private boolean canJoinWithPrevious(Tree previous, Tree next) {
                return isJoinableLiteral(next)
                        && lineMap.getLineNumber(getEndPosition(unit, previous))
                                == lineMap.getLineNumber(getStartPosition(next))
                        && noComments(input, unit, previous, next)
                        && !joiningWouldChangeEscapes(literalText(previous), literalText(next))
                        && !endsInLineComment(getStartPosition(next));
            }

            private boolean isJoinableLiteral(Tree tree) {
                return tree.getKind() == Kind.STRING_LITERAL
                        // indentTextBlocks re-indents a text block, which cannot be spliced into a literal.
                        && !input.startsWith(TEXT_BLOCK_DELIMITER, getStartPosition(tree));
            }

            /**
             * Whether the line of a literal that starts at a position ends in a line comment. The literals of such a
             * line stay as they are written, because the comment may speak of them that way: a NON-NLS marker counts
             * the literals of its line, see {@link NonNlsMarkers}, and a table of literals has a comment for each row.
             */
            private boolean endsInLineComment(int position) {
                var lineEnd = position;
                while (lineEnd < input.length() && Newlines.hasNewlineAt(input, lineEnd) == -1) {
                    lineEnd++;
                }
                return lineCommentStart(input.substring(position, lineEnd)) >= 0;
            }

            private void putJoinReplacement(List<Tree> run) {
                var range =
                        Range.closedOpen(getStartPosition(run.get(0)), getEndPosition(unit, run.get(run.size() - 1)));
                if (!replacements.subRangeMap(range).asMapOfRanges().isEmpty()) {
                    // A text block or a reflowed chain overlaps it; that pass wins.
                    return;
                }
                var joined = new StringBuilder("\"");
                for (var tree : run) {
                    joined.append(literalText(tree));
                }
                replacements.put(range, joined.append('"').toString());
            }

            /** The source text of a literal between its quotes, with its escapes as they are written. */
            private String literalText(Tree tree) {
                return input.substring(getStartPosition(tree) + 1, getEndPosition(unit, tree) - 1);
            }
        }

        private class LongStringsAndTextBlockScanner extends TreePathScanner<Void, Void> {

            private final List<TreePath> longStringLiterals;
            private final List<TreePath> textBlocks;
            private final List<Tree> stringLiterals;

            LongStringsAndTextBlockScanner(
                    List<TreePath> longStringLiterals, List<TreePath> textBlocks, List<Tree> stringLiterals) {
                this.longStringLiterals = longStringLiterals;
                this.textBlocks = textBlocks;
                this.stringLiterals = stringLiterals;
            }

            @Override
            public Void visitLiteral(LiteralTree literalTree, Void aVoid) {
                if (literalTree.getKind() != Kind.STRING_LITERAL) {
                    return null;
                }
                stringLiterals.add(literalTree);
                int pos = getStartPosition(literalTree);
                if (input.substring(pos, Math.min(input.length(), pos + TEXT_BLOCK_DELIMITER.length()))
                        .equals(TEXT_BLOCK_DELIMITER)) {
                    textBlocks.add(getCurrentPath());
                    return null;
                }
                Tree parent = getCurrentPath().getParentPath().getLeaf();
                if (parent instanceof MemberSelectTree memberSelectTree
                        && memberSelectTree.getExpression().equals(literalTree)) {
                    return null;
                }
                int endPosition = getEndPosition(unit, literalTree);
                int lineEnd = endPosition;
                while (Newlines.hasNewlineAt(input, lineEnd) == -1) {
                    lineEnd++;
                }
                if (lineMap.getColumnNumber(lineEnd) - 1 <= columnLimit) {
                    return null;
                }
                longStringLiterals.add(getCurrentPath());
                return null;
            }
        }

        private void indentTextBlocks(TreeRangeMap<Integer, String> replacements, List<TreePath> textBlocks) {
            // pjf specific - compute textBlock's parents & store indentation info for each parent
            Map<TreePath, String> textBlockToIndent = computeCustomTextBlocksIndent(textBlocks);

            for (TreePath treePath : textBlocks) {
                Tree tree = treePath.getLeaf();
                int startPosition = getStartPosition(tree);
                int endPosition = getEndPosition(unit, tree);
                String text = input.substring(startPosition, endPosition);
                int lineStartPosition = lineMap.getStartPosition(lineMap.getLineNumber(startPosition));
                int startColumn =
                        CharMatcher.whitespace().negate().indexIn(input.substring(lineStartPosition, endPosition));

                // Find the source code of the text block with incidental whitespace removed.
                // The first line of the text block is always """, and it does not affect incidental
                // whitespace.
                ImmutableList<String> initialLines = text.lines().collect(ImmutableList.toImmutableList());
                String stripped = initialLines.stream()
                        .skip(1)
                        .collect(joining(separator))
                        .stripIndent();
                ImmutableList<String> lines = stripped.lines().collect(ImmutableList.toImmutableList());

                // pjf specific: try to indent the text block, unless the text block will be indented more than the
                // starting quotes
                String prefix = textBlockToIndent.getOrDefault(
                        treePath,
                        (lineStartPosition + startColumn + 4 > startPosition ? "" : " ".repeat(4))
                                + " ".repeat(startColumn));
                StringBuilder output = new StringBuilder(initialLines.get(0));
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    output.append(separator);
                    if (!line.isEmpty()) {
                        // Don't add incidental leading whitespace to empty lines
                        output.append(prefix);
                    }
                    if (i == lines.size() - 1) {
                        String withoutDelimiter = line.substring(0, line.length() - TEXT_BLOCK_DELIMITER.length())
                                .stripTrailing();
                        if (!withoutDelimiter.isEmpty()) {
                            output.append(withoutDelimiter)
                                    .append('\\')
                                    .append(separator)
                                    .append(prefix);
                        }
                        // If the trailing line is just """, indenting it more than the prefix of incidental
                        // whitespace has no effect, and results in a javac text-blocks warning that 'trailing
                        // white space will be removed'.
                        output.append(TEXT_BLOCK_DELIMITER);
                    } else {
                        output.append(line);
                    }
                }
                replacements.put(Range.closedOpen(startPosition, endPosition), output.toString());
            }
        }

        /**
         * Pjf specific: When the AST needs to break a concatenation expression ('ONE'+'TWO') or the parameters of a method,
         * the indentation will be set to 8, in which case we need to align the text blocks to the correct indentation.
         * In order to compute the indentation value we need to do:
         * 1. for each textBlock find the enclosing block/parent (only for concat expressions/method invocations)
         * 2. for each parent, find the arguments/concatenated expressions and find the max indentation level
         * (ignore the lines that start with a text block ending)
         * 3. store the mapping between the textBlock and the indentation level computed before.
         */
        private Map<TreePath, String> computeCustomTextBlocksIndent(List<TreePath> textBlocks) {
            Map<Tree, String> parentToIndent = new HashMap<>();
            Map<TreePath, Tree> textBlockToParent = new HashMap<>();
            for (TreePath textBlock : textBlocks) {
                TreePath parentPath = textBlock.getParentPath();
                Tree parent = parentPath.getLeaf();
                if (parent instanceof MethodInvocationTree) {
                    textBlockToParent.put(textBlock, parent);
                    List<Tree> allArguments = new ArrayList<>(((JCMethodInvocation) parent).getArguments());
                    parentToIndent.computeIfAbsent(
                            parent,
                            // A method can be split in multiple lines (eg. for field access
                            // Class.builder()
                            //      .method("arguments")
                            //      .build()
                            // In this case the parent of the arguments should be the method ("Class.builder().method")
                            // the indentation of the arguments will be relative to the indentation of the last line of
                            // the method name.
                            parentTree -> computePrefixIndentation(
                                    ((JCMethodInvocation) parentTree).getMethodSelect(), allArguments, false));
                } else if (parent.getKind() == Kind.PLUS) {
                    while (parentPath.getParentPath().getLeaf().getKind() == Kind.PLUS) {
                        parentPath = parentPath.getParentPath();
                    }
                    Tree concatenationRoot = parentPath.getLeaf();
                    textBlockToParent.put(textBlock, concatenationRoot);
                    parentToIndent.computeIfAbsent(
                            concatenationRoot,
                            concatenationRootTree -> computePrefixIndentation(
                                    concatenationRootTree, flattenExpressionTree(concatenationRootTree), true));
                }
            }

            return textBlockToParent.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> parentToIndent.get(e.getValue())));
        }

        private String computePrefixIndentation(
                Tree parentPath, List<Tree> childExpressions, boolean shouldUseStartLineParent) {
            int startParentPosition = getStartPosition(parentPath);
            int startParentLine = lineMap.getLineNumber(startParentPosition);
            int endParentPosition = getEndPosition(unit, parentPath);
            int endParentLine = lineMap.getLineNumber(endParentPosition);
            int lineParentStartPosition =
                    lineMap.getStartPosition(shouldUseStartLineParent ? startParentLine : endParentLine);
            int startParentColumn = CharMatcher.whitespace()
                    .negate()
                    .indexIn(input.substring(lineParentStartPosition, endParentPosition));
            int extraIndent = 4;
            for (Tree expression : childExpressions) {
                int startingPos = getStartPosition(expression);
                int startLine = lineMap.getLineNumber(startingPos);
                int lineStartPosition = lineMap.getStartPosition(startLine);
                int endPos = getEndPosition(unit, expression);
                int parentLine = shouldUseStartLineParent ? startParentLine : endParentLine;
                // ignore indentation if the current argument is on the same line as the parent
                if (startLine == parentLine) {
                    continue;
                }
                // if this is a line that ends a textBlock (if the line starts with triple quotes & the current tree
                // starts after)
                int startColumn = CharMatcher.whitespace().negate().indexIn(input.substring(lineStartPosition, endPos));
                if (input.startsWith(TEXT_BLOCK_DELIMITER, lineStartPosition + startColumn)
                        && startingPos != lineStartPosition + startColumn) {
                    continue;
                }

                extraIndent = Math.max(extraIndent, startColumn - startParentColumn);
            }
            return " ".repeat(extraIndent + startParentColumn);
        }

        /**
         * A long literal with its neighbours in a chain of {@code +}, reflowed as one text; see {@link #flatten}.
         * {@code plain} is its text reflowed without regard to markers, in pieces, one per line.
         */
        private record Chain(
                List<Tree> flat,
                boolean firstInChain,
                int start,
                int end,
                int lineEnd,
                int startColumn,
                int firstLineColumn,
                List<String> plain) {}

        private void wrapLongStrings(
                TreeRangeMap<Integer, String> replacements,
                List<TreePath> longStringLiterals,
                List<Tree> stringLiterals) {
            var chains = new ArrayList<Chain>();
            for (TreePath path : longStringLiterals) {
                // Find the outermost contiguous enclosing concatenation expression
                TreePath enclosing = path;
                while (enclosing.getParentPath().getLeaf().getKind() == Tree.Kind.PLUS) {
                    enclosing = enclosing.getParentPath();
                }
                // Is the literal being wrapped the first in a chain of concatenation expressions?
                // i.e. `ONE + TWO + THREE`
                // We need this information to handle continuation indents.
                AtomicBoolean first = new AtomicBoolean(false);
                // Finds the set of string literals in the concat expression that includes the one that needs
                // to be wrapped.
                List<Tree> flat = flatten(input, unit, path, enclosing, first);

                // ==== START pjf specific ===
                // Walk up as long as parents are on the same line, in order to find the correct
                // startColumn.
                TreePath startingPath = enclosing;
                while (startingPath.getParentPath() != null && onSameLineAsParent(lineMap, startingPath)) {
                    startingPath = startingPath.getParentPath();
                }
                // Zero-indexed start column
                int startColumn = lineMap.getColumnNumber(getStartPosition(flat.get(0))) - 1;
                int fistLineCol = lineMap.getColumnNumber(getStartPosition(startingPath.getLeaf())) - 1;
                // ==== END pjf specific ===

                // Handling leaving trailing non-string tokens at the end of the literal,
                // e.g. the trailing `);` in `foo("...");`.
                int end = getEndPosition(unit, getLast(flat));
                int lineEnd = end;
                while (Newlines.hasNewlineAt(input, lineEnd) == -1) {
                    lineEnd++;
                }
                int trailing = lineEnd - end;

                // Get the original source text of the string literals, excluding `"` and `+`.
                ImmutableList<String> components = stringComponents(input, unit, flat);
                var plain = reflow(
                        columnLimit,
                        continuationIndent,
                        trailing,
                        components,
                        first.get(),
                        startColumn,
                        fistLineCol,
                        "",
                        "");
                chains.add(new Chain(
                        flat,
                        first.get(),
                        getStartPosition(flat.get(0)),
                        end,
                        lineEnd,
                        startColumn,
                        fistLineCol,
                        plain));
            }
            for (var chain : chains) {
                var joined = join(chain, chain.plain(), "", "");
                if (joined.equals(input.substring(chain.start(), chain.end()))) {
                    // Every literal on a line past the limit is a candidate; this one does not change.
                    continue;
                }
                var markers = lineMarkers(chains, chain, stringLiterals);
                if (markers == null) {
                    replacements.put(Range.closedOpen(chain.start(), chain.end()), joined);
                    continue;
                }
                var components = stringComponents(input, unit, chain.flat());
                var pieces = reflow(
                        columnLimit,
                        continuationIndent,
                        markers.tail(2).length(),
                        components,
                        chain.firstInChain(),
                        chain.startColumn(),
                        chain.firstLineColumn(),
                        markers.firstLine(),
                        markers.otherLines());
                replacements.put(
                        Range.closedOpen(chain.start(), chain.lineEnd()),
                        join(chain, pieces, markers.firstLine(), markers.otherLines()) + markers.tail(pieces.size()));
            }
        }

        private String join(Chain chain, List<String> pieces, String firstLineMarkers, String otherLineMarkers) {
            return render(
                    separator,
                    pieces,
                    chain.firstInChain() ? chain.firstLineColumn() + continuationIndent : chain.startColumn() - 2,
                    firstLineMarkers,
                    otherLineMarkers);
        }

        /**
         * The NON-NLS markers of the line a chain ends on, see {@link NonNlsMarkers}, sorted by where they go once the
         * chain is reflowed: a marker of a literal before the chain keeps its number on the first line; the chain, if
         * marked, gets a marker after every piece, numbered by the piece's line; a marker of a literal after the chain
         * is counted from the last piece on the last line; and a marker without a literal stays at the end. Null when
         * the markers are left alone: when the comment holds anything else, or when another chain of the line
         * changes too, since the chain rewrites the rest of its line.
         */
        @Nullable
        private LineMarkers lineMarkers(List<Chain> chains, Chain chain, List<Tree> stringLiterals) {
            var tail = input.substring(chain.end(), chain.lineEnd());
            var comment = lineCommentStart(tail);
            if (comment < 0
                    || !NonNlsMarkers.isMarkersOnly(tail.substring(comment).stripTrailing())) {
                return null;
            }
            var lastLine = lineMap.getLineNumber(chain.end() - 1);
            for (var other : chains) {
                // The same chain is found once per long literal in it.
                if (other.start() != chain.start()
                        && lineMap.getLineNumber(other.end() - 1) == lastLine
                        && !join(other, other.plain(), "", "").equals(input.substring(other.start(), other.end()))) {
                    return null;
                }
            }
            var literals = literalsOnLine(stringLiterals, lastLine);
            var lastOfChain = literals.indexOf(getLast(chain.flat()));
            if (lastOfChain < 0) {
                // A text block, which starts on another line.
                return null;
            }
            var firstOfChain = lastOfChain;
            while (firstOfChain > 0 && chain.flat().contains(literals.get(firstOfChain - 1))) {
                firstOfChain--;
            }
            var before = new ArrayList<String>();
            var after = new ArrayList<Integer>();
            var unmatched = new ArrayList<String>();
            var chainMarked = false;
            for (int n : NonNlsMarkers.numbers(tail.substring(comment))) {
                var i = n - 1;
                if (i < 0 || i >= literals.size()) {
                    unmatched.add(NonNlsMarkers.marker(n));
                } else if (i < firstOfChain) {
                    before.add(NonNlsMarkers.marker(n));
                } else if (i <= lastOfChain) {
                    chainMarked = true;
                } else {
                    after.add(i - lastOfChain);
                }
            }
            var beforeOnFirstLine = literalsOnLine(stringLiterals, lineMap.getLineNumber(chain.start())).stream()
                    .filter(literal -> getStartPosition(literal) < chain.start())
                    .count();
            return new LineMarkers(
                    List.copyOf(before),
                    (int) beforeOnFirstLine,
                    chainMarked,
                    List.copyOf(after),
                    List.copyOf(unmatched),
                    tail.substring(0, comment));
        }

        /** See {@link #lineMarkers}; {@code code} is the text between the chain and its line's comment. */
        private record LineMarkers(
                List<String> before,
                int beforeOnFirstLine,
                boolean chainMarked,
                List<Integer> after,
                List<String> unmatched,
                String code) {

            /** The markers after the first piece, when it ends its line. */
            String firstLine() {
                var markers = new ArrayList<>(before);
                if (chainMarked) {
                    markers.add(NonNlsMarkers.marker(beforeOnFirstLine + 1));
                }
                return markers.isEmpty() ? "" : " " + String.join(" ", markers);
            }

            /** The markers after a piece in the middle. */
            String otherLines() {
                return chainMarked ? " " + NonNlsMarkers.marker(1) : "";
            }

            /** The text after the last of {@code pieces}: the code, then the markers of that line. */
            String tail(int pieces) {
                var markers = new ArrayList<String>();
                var chainPosition = pieces > 1 ? 1 : beforeOnFirstLine + 1;
                if (pieces == 1) {
                    markers.addAll(before);
                }
                if (chainMarked) {
                    markers.add(NonNlsMarkers.marker(chainPosition));
                }
                for (int position : after) {
                    markers.add(NonNlsMarkers.marker(chainPosition + position));
                }
                markers.addAll(unmatched);
                return markers.isEmpty() ? code.stripTrailing() : code + String.join(" ", markers);
            }
        }

        /** The string literals that start on a line, in order. */
        private List<Tree> literalsOnLine(List<Tree> stringLiterals, long line) {
            return stringLiterals.stream()
                    .filter(literal -> lineMap.getLineNumber(getStartPosition(literal)) == line)
                    .sorted(Comparator.comparingInt(StringWrapper::getStartPosition))
                    .toList();
        }

        /** The index of the line comment in a line of code, the first {@code //} outside a literal, or -1. */
        private static int lineCommentStart(String line) {
            for (var i = 0; i < line.length(); i++) {
                var c = line.charAt(i);
                if (c == '"' || c == '\'') {
                    for (i++; i < line.length() && line.charAt(i) != c; i++) {
                        if (line.charAt(i) == '\\') {
                            i++;
                        }
                    }
                } else if (c == '/' && line.startsWith("//", i)) {
                    return i;
                }
            }
            return -1;
        }

        private static boolean onSameLineAsParent(LineMap lineMap, TreePath path) {
            return lineMap.getLineNumber(getStartPosition(path.getLeaf()))
                    == lineMap.getLineNumber(
                            getStartPosition(path.getParentPath().getLeaf()));
        }
    }

    /**
     * Returns the source text of the given string literal trees, excluding the leading and trailing double-quotes and
     * the `+` operator.
     */
    private static ImmutableList<String> stringComponents(
            String input, JCTree.JCCompilationUnit unit, List<Tree> flat) {
        ImmutableList.Builder<String> result = ImmutableList.builder();
        StringBuilder piece = new StringBuilder();
        for (Tree tree : flat) {
            // adjust for leading and trailing double quotes
            String text = input.substring(getStartPosition(tree) + 1, getEndPosition(unit, tree) - 1);
            int start = 0;
            for (int idx = 0; idx < text.length(); idx++) {
                if (CharMatcher.whitespace().matches(text.charAt(idx))) {
                    // continue below
                } else if (hasEscapedWhitespaceAt(text, idx) != -1) {
                    // continue below
                } else if (hasEscapedNewlineAt(text, idx) != -1) {
                    int length;
                    while ((length = hasEscapedNewlineAt(text, idx)) != -1) {
                        idx += length;
                    }
                } else {
                    continue;
                }
                piece.append(text, start, idx);
                result.add(piece.toString());
                piece = new StringBuilder();
                start = idx;
            }
            if (piece.length() > 0) {
                result.add(piece.toString());
                piece = new StringBuilder();
            }
            if (start < text.length()) {
                piece.append(text, start, text.length());
            }
        }
        if (piece.length() > 0) {
            result.add(piece.toString());
        }
        return result.build();
    }

    static int hasEscapedWhitespaceAt(String input, int idx) {
        if (input.startsWith("\\t", idx) && !isEscaped(input, idx)) {
            return 2;
        }
        return -1;
    }

    static int hasEscapedNewlineAt(String input, int idx) {
        int offset = 0;
        if (input.startsWith("\\r", idx)) {
            offset += 2;
        }
        if (input.startsWith("\\n", idx)) {
            offset += 2;
        }
        return offset > 0 && !isEscaped(input, idx) ? offset : -1;
    }

    /**
     * Whether the character at {@code idx} is escaped by the backslashes before it. In {@code \\t} the second backslash
     * is, so it starts no escape sequence of its own and the {@code t} is an ordinary letter.
     */
    private static boolean isEscaped(String input, int idx) {
        int backslashes = 0;
        while (idx - backslashes > 0 && input.charAt(idx - backslashes - 1) == '\\') {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    /**
     * Reflows the given source text, trying to split on word boundaries. Returns the pieces, one per line; see
     * {@link #render}.
     *
     * @param columnLimit the number of columns to wrap at
     * @param continuationIndent how far the lines after the first are indented from the start of the first, before
     * their {@code + }, when the text begins its chain
     * @param trailing extra space to leave after the last line
     * @param components the text to reflow
     * @param first0 true if the text includes the beginning of its enclosing concat chain, i.e. a
     * @param textStartColumn the column position of the beginning of the original text
     * @param firstLineStartColumn the column where the very first line starts (can be less than textStartColumn if text
     * follows variable declaration)
     * @param firstLineMarkers the NON-NLS markers that trail the first line, with their leading blank, or empty
     * @param otherLineMarkers the NON-NLS markers that trail every further line but the last, whose markers are part
     * of {@code trailing}
     */
    private static List<String> reflow(
            int columnLimit,
            int continuationIndent,
            int trailing,
            ImmutableList<String> components,
            boolean first0,
            int textStartColumn,
            int firstLineStartColumn,
            String firstLineMarkers,
            String otherLineMarkers) {
        // We have space between the start column and the limit to output the first line.
        // Reserve two spaces for the quotes, and room for the markers of the line.
        var width = columnLimit - textStartColumn - 2 - firstLineMarkers.length();
        Deque<String> input = new ArrayDeque<>(components);
        List<String> lines = new ArrayList<>();
        boolean first = first0;
        while (!input.isEmpty()) {
            int length = 0;
            List<String> line = new ArrayList<>();
            if (input.stream().mapToInt(String::length).sum() <= width) {
                // The last line, probably: its markers are in the trailing text, not reserved on their own.
                width -= trailing - (lines.isEmpty() ? 0 : otherLineMarkers.length());
            }
            while (!input.isEmpty()
                    && (length <= 4 || (length + input.peekFirst().length()) < width)) {
                String text = input.removeFirst();
                line.add(text);
                length += text.length();
                if (hasEscapedNewlineAt(text, text.length() - 2) != -1) {
                    break;
                }
            }
            if (line.isEmpty()) {
                line.add(input.removeFirst());
            }
            // add the split line to the output, and process whatever's left
            lines.add(String.join("", line));
            if (first) {
                // Subsequent lines have a continuation indent and a `+ `.
                width -= continuationIndent + 2;
                // Also, switch to firstLineStartColumn in order to account for the fact that continuations
                // should get indented from the beginning of the first line.
                // This is to handle cases like:
                // String foo = "first component"
                //     + "rest";
                width += textStartColumn - firstLineStartColumn;
                first = false;
            }
            if (lines.size() == 1) {
                width += firstLineMarkers.length() - otherLineMarkers.length();
            }
        }
        return lines;
    }

    /** Renders reflowed pieces as literals in a chain of {@code +}, one per line, indented by {@code column}. */
    private static String render(
            String separator, List<String> pieces, int column, String firstLineMarkers, String otherLineMarkers) {
        var continuation = separator + " ".repeat(column) + "+ ";
        var result = new StringBuilder();
        for (var i = 0; i < pieces.size(); i++) {
            if (i > 0) {
                result.append(continuation);
            }
            result.append('"').append(pieces.get(i)).append('"');
            if (i < pieces.size() - 1) {
                result.append(i == 0 ? firstLineMarkers : otherLineMarkers);
            }
        }
        return result.toString();
    }

    /**
     * Flattens the given binary expression tree, and extracts the subset that contains the given path and any adjacent
     * nodes that are also string literals.
     */
    private static List<Tree> flatten(
            String input, JCTree.JCCompilationUnit unit, TreePath path, TreePath parent, AtomicBoolean firstInChain) {
        List<Tree> flat = flattenExpressionTree(parent.getLeaf());

        int idx = flat.indexOf(path.getLeaf());
        Verify.verify(idx != -1);

        // walk outwards from the leaf for adjacent string literals to also reflow
        int startIdx = idx;
        int endIdx = idx + 1;
        while (startIdx > 0
                && flat.get(startIdx - 1).getKind() == Tree.Kind.STRING_LITERAL
                && noComments(input, unit, flat.get(startIdx - 1), flat.get(startIdx))) {
            startIdx--;
        }
        while (endIdx < flat.size()
                && flat.get(endIdx).getKind() == Tree.Kind.STRING_LITERAL
                && noComments(input, unit, flat.get(endIdx - 1), flat.get(endIdx))) {
            endIdx++;
        }

        firstInChain.set(startIdx == 0);
        return ImmutableList.copyOf(flat.subList(startIdx, endIdx));
    }

    /**
     * Returns the flatten expression tree with a pre-order traversal
     * @param parent a {@link com.sun.tools.javac.tree.JCTree.JCExpression}
     * @return the list of concatenated parameters in order.
     */
    private static List<Tree> flattenExpressionTree(Tree parent) {
        // flatten the expression tree with a pre-order traversal
        List<Tree> flat = new ArrayList<>();
        ArrayDeque<Tree> todo = new ArrayDeque<>();
        todo.add(parent);
        while (!todo.isEmpty()) {
            Tree first = todo.removeFirst();
            if (first.getKind() == Tree.Kind.PLUS) {
                BinaryTree bt = (BinaryTree) first;
                todo.addFirst(bt.getRightOperand());
                todo.addFirst(bt.getLeftOperand());
            } else {
                flat.add(first);
            }
        }
        return flat;
    }

    /**
     * Whether appending the source text {@code right} to {@code left} would change what it stands for. That happens
     * when {@code left} ends in an octal escape short enough to take in another digit: {@code "\1" + "2"} holds
     * {@code \1} and then {@code 2}, but {@code "\12"} is a single character. An octal escape is at most three digits,
     * so one of three is safe, as is a run of digits that is not preceded by an odd number of backslashes, which is
     * text rather than an escape. It also happens when either holds a Unicode escape: javac reads those before the
     * escapes of a literal, so one can stand for the backslash or the digit of such an escape.
     */
    private static boolean joiningWouldChangeEscapes(String left, String right) {
        if (left.contains("\\u") || right.contains("\\u")) {
            return true;
        }
        if (right.isEmpty() || !isOctalDigit(right.charAt(0))) {
            return false;
        }
        var index = left.length();
        while (index > 0 && isOctalDigit(left.charAt(index - 1))) {
            index--;
        }
        var digits = left.length() - index;
        if (digits == 0 || digits > 2) {
            return false;
        }
        var backslashes = 0;
        while (index - backslashes > 0 && left.charAt(index - backslashes - 1) == '\\') {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private static boolean isOctalDigit(char character) {
        return character >= '0' && character <= '7';
    }

    /**
     * Whether a line may hold two adjacent string literals to join: a {@code +} with a quote right before and after
     * it, blanks aside. A cheap over-approximation that keeps the fast paths fast; the {@link Reflower} decides what
     * is joined.
     */
    private static boolean mayHaveJoinableLiterals(String line) {
        for (var plus = line.indexOf('+'); plus != -1; plus = line.indexOf('+', plus + 1)) {
            var before = plus - 1;
            while (before >= 0 && (line.charAt(before) == ' ' || line.charAt(before) == '\t')) {
                before--;
            }
            var after = plus + 1;
            while (after < line.length() && (line.charAt(after) == ' ' || line.charAt(after) == '\t')) {
                after++;
            }
            if (before >= 0 && line.charAt(before) == '"' && after < line.length() && line.charAt(after) == '"') {
                return true;
            }
        }
        return false;
    }

    private static boolean noComments(String input, JCTree.JCCompilationUnit unit, Tree one, Tree two) {
        return STRING_CONCAT_DELIMITER.matchesAllOf(
                input.subSequence(getEndPosition(unit, one), getStartPosition(two)));
    }

    public static final CharMatcher STRING_CONCAT_DELIMITER =
            CharMatcher.whitespace().or(CharMatcher.anyOf("\"+"));

    private static int getEndPosition(JCTree.JCCompilationUnit unit, Tree tree) {
        return Trees.getEndPosition(tree, unit);
    }

    private static int getStartPosition(Tree tree) {
        return ((JCTree) tree).getStartPosition();
    }

    /**
     * Returns true if any lines in the given Java source exceed the column limit, contain text blocks or may hold
     * adjacent string literals to join. Keep this method and {@code linesNeedWrapping} in line.
     * */
    private static boolean needWrapping(int columnLimit, String input) {
        // TODO(cushon): consider adding Newlines.lineIterable?
        Iterator<String> it = Newlines.lineIterator(input);
        while (it.hasNext()) {
            String line = it.next();
            if (line.length() > columnLimit
                    || line.contains(TEXT_BLOCK_DELIMITER)
                    // Literals joined into a line that fits leave no over-long line to set off the wrapping.
                    || mayHaveJoinableLiterals(line)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the lines containing the {@code initialRangesToChange} in the given Java source exceed the
     * column limit, contain text blocks or may hold adjacent string literals to join. Used by the Intellij plugin to
     * check if we need to run StringWrapper.wrap. Keep this method and {@code needWrapping} in line.
     * */
    public static boolean linesNeedWrapping(int columnLimit, String input, RangeSet<Integer> initialRangesToChange) {
        // TODO(cushon): consider adding Newlines.lineIterable?
        RangeSet<Integer> linesToChange = TreeRangeSet.create();
        Iterator<String> it = Newlines.lineIterator(input);
        int i = 0;
        boolean insideTextBlock = false;
        while (it.hasNext()) {
            String line = it.next();
            if (line.length() > columnLimit || mayHaveJoinableLiterals(line)) {
                linesToChange.add(Range.closedOpen(i, i + 1));
            }
            if (!insideTextBlock && line.contains(TEXT_BLOCK_DELIMITER)) {
                insideTextBlock = true;
                linesToChange.add(Range.closedOpen(i, i + 1));
            } else if (insideTextBlock && line.contains(TEXT_BLOCK_DELIMITER)) {
                insideTextBlock = false;
                linesToChange.add(Range.closedOpen(i, i + 1));
            } else if (insideTextBlock) {
                linesToChange.add(Range.closedOpen(i, i + 1));
            }
            ++i;
        }
        RangeSet<Integer> charRangeToCheck = Utils.lineRangesToCharRanges(input, linesToChange);
        return hasOverlap(initialRangesToChange, charRangeToCheck);
    }

    static boolean hasOverlap(RangeSet<Integer> a, RangeSet<Integer> b) {
        for (Range<Integer> range : a.asRanges()) {
            if (!b.subRangeSet(range).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Parses the given Java source. */
    private static JCTree.JCCompilationUnit parse(String source, boolean allowStringFolding) throws FormatterException {
        Context context = new Context();
        Options.instance(context).put("allowStringFolding", Boolean.toString(allowStringFolding));
        return Formatter.parseJcCompilationUnit(context, source);
    }

    /** Applies replacements to the given string. */
    private static String applyReplacements(String javaInput, TreeRangeMap<Integer, String> replacementMap) {
        // process in descending order so the replacement ranges aren't perturbed if any replacements
        // differ in size from the input
        Map<Range<Integer>, String> ranges = replacementMap.asDescendingMapOfRanges();
        if (ranges.isEmpty()) {
            return javaInput;
        }
        StringBuilder sb = new StringBuilder(javaInput);
        for (Map.Entry<Range<Integer>, String> entry : ranges.entrySet()) {
            Range<Integer> range = entry.getKey();
            sb.replace(range.lowerEndpoint(), range.upperEndpoint(), entry.getValue());
        }
        return sb.toString();
    }

    private StringWrapper() {}
}
