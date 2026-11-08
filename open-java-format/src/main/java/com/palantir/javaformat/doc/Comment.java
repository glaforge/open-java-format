/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.javaformat.doc;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Iterators;
import com.google.common.collect.Range;
import com.palantir.javaformat.CommentsHelper;
import com.palantir.javaformat.Input;
import com.palantir.javaformat.Newlines;
import com.palantir.javaformat.Op;
import com.palantir.javaformat.Output;
import com.palantir.javaformat.java.JavaCommentsHelper;
import com.palantir.javaformat.java.NonNlsMarkers;
import java.util.List;

/** A leaf node in a {@link Doc} for a non-token. */
public final class Comment extends Doc implements Op {
    private final Input.Tok tok;

    /** For a comment of NON-NLS markers, the index of the string literal each marker marks, or -1; else empty. */
    private final List<Integer> nonNlsLiterals;

    /** Whether the comment trails code on its line. */
    private final boolean trailing;

    /** Set by {@link #withFirstWordOnly}. */
    private final boolean firstWordOnly;

    private Comment(Input.Tok tok, List<Integer> nonNlsLiterals, boolean trailing, boolean firstWordOnly) {
        this.tok = tok;
        this.nonNlsLiterals = nonNlsLiterals;
        this.trailing = trailing;
        this.firstWordOnly = firstWordOnly;
    }

    /**
     * Factory method for a {@code Tok}.
     *
     * @param tok the {@link Input.Tok} to wrap
     * @return the new {@code Tok}
     */
    public static Comment make(Input.Tok tok) {
        return new Comment(tok, List.of(), false, false);
    }

    /** A comment that trails code on its line. */
    public static Comment makeTrailing(Input.Tok tok) {
        return new Comment(tok, List.of(), true, false);
    }

    /**
     * A comment of {@code //$NON-NLS-n$} markers, see {@link NonNlsMarkers}. A marker with a literal is written at
     * the end of the literal's output line and takes its room there, as a {@link ReservedWidth} after the literal;
     * only the markers without one take room here.
     *
     * @param literals for each marker, the index of the string literal it marks, or -1
     */
    public static Comment makeNonNlsMarkers(Input.Tok tok, List<Integer> literals) {
        return new Comment(tok, List.copyOf(literals), false, false);
    }

    /**
     * This comment, laid out by its slashes and its first word only, as a line comment that spans lines is. The next
     * run reads a wrapped trailing comment back as one that spans lines, so laying it out that way from the start gives
     * both runs the same layout, and the code before it is not broken for room that the comment does not get.
     */
    public Comment withFirstWordOnly() {
        return new Comment(tok, nonNlsLiterals, trailing, true);
    }

    /**
     * Whether this trailing line comment, counted in full by the layout, came out of {@code state} wrapped: it did not
     * fit after the code before it, however that was broken.
     */
    public boolean wrappedIn(State state) {
        return trailing
                && tok.isSlashSlashComment()
                && !countsFirstWordOnly()
                && Newlines.containsBreaks(state.getTokText(this));
    }

    /** Whether the layout counts only the slashes and the first word of this line comment. */
    boolean countsFirstWordOnly() {
        return tok.isSlashSlashComment() && (firstWordOnly || Newlines.containsBreaks(tok.getOriginalText()));
    }

    @Override
    public void add(DocBuilder builder) {
        builder.add(this);
    }

    @Override
    protected float computeWidth() {
        if (!nonNlsLiterals.isEmpty()) {
            var width = 0;
            var numbers = NonNlsMarkers.numbers(tok.getOriginalText());
            for (var i = 0; i < numbers.size(); i++) {
                if (nonNlsLiterals.get(i) < 0) {
                    width += (width > 0 ? 1 : 0)
                            + NonNlsMarkers.marker(numbers.get(i)).length();
                }
            }
            return width;
        }
        if (countsFirstWordOnly()) {
            // The rest of the comment goes where the wrap puts it, so the code before it is laid out as if it were not
            // there; see withFirstWordOnly.
            return JavaCommentsHelper.firstWordLength(getFlat());
        }
        int idx = Newlines.firstBreak(tok.getOriginalText());
        // only count the first line of multi-line block comments
        if (tok.isComment()) {
            if (idx > 0) {
                return idx;
            } else if (tok.isSlashSlashComment()) {
                // The flat text may have gained the space after the slashes, see computeFlat.
                return getFlat().length();
            } else {
                return tok.length();
            }
        }
        return idx != -1 ? Float.POSITIVE_INFINITY : (float) tok.length();
    }

    @Override
    protected String computeFlat() {
        // A level that fits on one line is written from its flat text, and commentsHelper.rewrite is not called for
        // the comments in it. So the space a line comment gets after its slashes is added here as well, by the rule
        // that rewrite applies: a trailing `//foo` and a trailing `//$NON-NLS-1$` then come out the same either way.
        if (tok.isSlashSlashComment()) {
            return JavaCommentsHelper.addMissingSpace(tok.getOriginalText());
        }
        return tok.getOriginalText();
    }

    @Override
    protected Range<Integer> computeRange() {
        return Range.singleton(tok.getIndex()).canonical(INTEGERS);
    }

    @Override
    public State computeBreaks(
            CommentsHelper commentsHelper, int maxWidth, State state, Obs.ExplorationNode observationNode) {
        String text = commentsHelper.rewrite(tok, maxWidth, state.column());
        int lastLineStart = Iterators.getLast(Newlines.lineOffsetIterator(text));
        int lastLineLength = text.length() - lastLineStart;
        // rewrite() indents every line after the first to state.column(), so after a comment that spans lines the
        // column is the length of its last line. Adding state.column() to it as well doubled the column for each
        // further comment on that line.
        int column = lastLineStart == 0 ? state.column() + lastLineLength : lastLineLength;
        return state.withColumn(column)
                .addNewLines(Iterators.size(Newlines.lineOffsetIterator(text)))
                .withTokState(this, new State.TokState(text));
    }

    @Override
    public void write(State state, Output output) {
        if (nonNlsLiterals.isEmpty()) {
            output.append(state, state.getTokText(this), range());
        } else {
            output.appendNonNlsMarkers(state, state.getTokText(this), range(), nonNlsLiterals);
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("tok", tok).toString();
    }
}
