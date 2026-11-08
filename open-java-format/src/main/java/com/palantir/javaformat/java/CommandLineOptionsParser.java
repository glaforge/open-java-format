/*
 * Copyright 2016 Google Inc.
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

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** A parser for {@link CommandLineOptions}. */
final class CommandLineOptionsParser {

    private static final Splitter COMMA_SPLITTER = Splitter.on(',');
    private static final Splitter COLON_SPLITTER = Splitter.on(':');

    /**
     * Splits the arguments of a parameter file on whitespace (including tabs and line breaks), and lets an argument be
     * quoted so that it keeps the whitespace inside it unchanged.
     *
     * <p>The regex matches either a quoted string (single or double quotes are allowed) or a plain unquoted string.
     * Double quotes may appear inside a single-quoted string and vice versa, and are then kept as they are. For
     * simplicity, escaped quotes are not handled.
     */
    private static final Pattern ARG_MATCHER = Pattern.compile(
            "\"([^\"]*)(?:\"|$)" // group 1: string in double quotes (or until EOF), with whitespace allowed
                    + "|" // OR
                    + "'([^']*)(?:'|$)" // group 2: string in single quotes (or until EOF), with whitespace allowed
                    + "|" // OR
                    + "([^\\s\"']+)" // group 3: unquoted string, without whitespace and without any quotes
            );

    /** Parses {@link CommandLineOptions}. */
    static CommandLineOptions parse(Iterable<String> options) {
        CommandLineOptions.Builder optionsBuilder = CommandLineOptions.builder();
        List<String> expandedOptions = new ArrayList<>();
        expandParamsFiles(options, expandedOptions, new ArrayDeque<>());
        Iterator<String> it = expandedOptions.iterator();
        while (it.hasNext()) {
            String option = it.next();
            if (!option.startsWith("-")) {
                optionsBuilder.filesBuilder().add(option).addAll(it);
                break;
            }
            String flag;
            @Nullable String value;
            int idx = option.indexOf('=');
            if (idx >= 0) {
                flag = option.substring(0, idx);
                value = option.substring(idx + 1, option.length());
            } else {
                flag = option;
                value = null;
            }
            // NOTE: update usage information in UsageException when new flags are added
            switch (flag) {
                case "-i", "-r", "-replace", "--replace" -> optionsBuilder.inPlace(true);
                case "--lines", "-lines", "--line", "-line" ->
                    parseRangeSet(optionsBuilder.linesBuilder(), getValue(flag, it, value));
                case "--character-ranges", "-character-ranges", "--character-range", "-character-range" ->
                    parseCharacterRanges(optionsBuilder.characterRangesBuilder(), getValue(flag, it, value));
                case "--offset", "-offset" -> optionsBuilder.addOffset(parseInteger(it, flag, value));
                case "--length", "-length" -> optionsBuilder.addLength(parseInteger(it, flag, value));
                case "--aosp", "-aosp", "-a", "--ojf", "-ojf" ->
                    // There is one style. The old style flags are accepted so that a script keeps working, and
                    // Main warns about each of them.
                    optionsBuilder.addUnsupportedFlag(flag);
                case "--version", "-version", "-v" -> optionsBuilder.version(true);
                case "--help", "-help", "-h" -> optionsBuilder.help(true);
                case "--fix-imports-only" -> optionsBuilder.fixImportsOnly(true);
                case "--skip-sorting-imports" -> optionsBuilder.sortImports(false);
                case "--skip-removing-unused-imports" -> optionsBuilder.removeUnusedImports(false);
                case "--skip-reflowing-long-strings" -> optionsBuilder.reflowLongStrings(false);
                case "-" -> optionsBuilder.stdin(true);
                case "-n", "--dry-run" -> optionsBuilder.dryRun(true);
                case "--set-exit-if-changed" -> optionsBuilder.setExitIfChanged(true);
                case "-assume-filename", "--assume-filename" ->
                    optionsBuilder.assumeFilename(getValue(flag, it, value));
                case "-output-replacements", "--output-replacements" -> optionsBuilder.outputReplacements(true);
                default -> throw new IllegalArgumentException("unexpected flag: " + flag);
            }
        }
        return optionsBuilder.build();
    }

    private static Integer parseInteger(Iterator<String> it, String flag, String value) {
        try {
            return Integer.valueOf(getValue(flag, it, value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format("invalid integer value for %s: %s", flag, value), e);
        }
    }

    private static String getValue(String flag, Iterator<String> it, @Nullable String value) {
        if (value != null) {
            return value;
        }
        if (!it.hasNext()) {
            throw new IllegalArgumentException("required value was not provided for: " + flag);
        }
        return it.next();
    }

    /**
     * Parse multiple --character-ranges flags, like {"0:12,14,20:36", "0:45,50"}. Multiple ranges can be given with multiple
     * --character-ranges flags or separated by commas. A single character can be set by a single number.
     * See more details on how character ranges are set in {@code com.palantir.javaformat.bootstrap.RangeUtils}
     */
    private static void parseCharacterRanges(ImmutableRangeSet.Builder<Integer> result, String ranges) {
        for (String range : COMMA_SPLITTER.split(ranges)) {
            result.add(parseCharacterRange(range));
        }
    }

    /**
     * Parse a character range, as in "0:45" or "45". Character ranges provided are {@code 0}-based, closed ranges.
     */
    private static Range<Integer> parseCharacterRange(String range) {
        List<String> args = COLON_SPLITTER.splitToList(range);
        switch (args.size()) {
            case 1 -> {
                int lowerUpperRange = Integer.parseInt(args.get(0));
                return Range.closed(lowerUpperRange, lowerUpperRange);
            }
            case 2 -> {
                int lower = Integer.parseInt(args.get(0));
                int higher = Integer.parseInt(args.get(1));
                return Range.closed(lower, higher);
            }
            default -> throw new IllegalArgumentException(range);
        }
    }

    /**
     * Parse multiple --lines flags, like {"1:12,14,20:36", "40:45,50"}. Multiple ranges can be given with multiple
     * --lines flags or separated by commas. A single line can be set by a single number. Line numbers are
     * {@code 1}-based, but are converted to the {@code 0}-based numbering used internally by google-java-format.
     */
    private static void parseRangeSet(RangeSet<Integer> result, String ranges) {
        for (String range : COMMA_SPLITTER.split(ranges)) {
            result.add(parseRange(range));
        }
    }

    /**
     * Parse a range, as in "1:12" or "42". Line numbers provided are {@code 1}-based, but are converted here to
     * {@code 0}-based.
     */
    private static Range<Integer> parseRange(String arg) {
        List<String> args = COLON_SPLITTER.splitToList(arg);
        switch (args.size()) {
            case 1 -> {
                int line = Integer.parseInt(args.get(0)) - 1;
                return Range.closedOpen(line, line + 1);
            }
            case 2 -> {
                int line0 = Integer.parseInt(args.get(0)) - 1;
                int line1 = Integer.parseInt(args.get(1)) - 1;
                return Range.closedOpen(line0, line1 + 1);
            }
            default -> throw new IllegalArgumentException(arg);
        }
    }

    /**
     * Pre-processes an argument list, expanding arguments of the form {@code @filename} by reading the content of the
     * file and appending whitespace-delimited options to {@code arguments}.
     */
    private static void expandParamsFiles(Iterable<String> args, List<String> expanded, Deque<String> paramFilesStack) {
        for (String arg : args) {
            if (arg.isEmpty()) {
                continue;
            }
            if (!arg.startsWith("@")) {
                expanded.add(arg);
            } else if (arg.startsWith("@@")) {
                expanded.add(arg.substring(1));
            } else {
                String filename = arg.substring(1);
                if (paramFilesStack.contains(filename)) {
                    throw new IllegalArgumentException("parameter file was included recursively: " + filename);
                }
                paramFilesStack.push(filename);
                expandParamsFiles(getParamsFromFile(filename), expanded, paramFilesStack);
                String finishedFilename = paramFilesStack.pop();
                Preconditions.checkState(filename.equals(finishedFilename));
            }
        }
    }

    /** Reads the parameters from a file, keeping quoted parameters whole. */
    private static List<String> getParamsFromFile(String filename) {
        String fileContent;
        try {
            fileContent = Files.readString(Path.of(filename));
        } catch (IOException e) {
            throw new UncheckedIOException(filename + ": could not read file: " + e.getMessage(), e);
        }
        List<String> paramsFromFile = new ArrayList<>();
        Matcher m = ARG_MATCHER.matcher(fileContent);
        while (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                if (m.group(i) != null) { // only one group matches: double quotes, single quotes or unquoted string.
                    paramsFromFile.add(m.group(i));
                }
            }
        }
        return paramsFromFile;
    }
}
