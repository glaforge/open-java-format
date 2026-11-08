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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.palantir.javaformat.java.JavaFormatterOptions.Style;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.google.common.base.Joiner;
import com.google.common.collect.Range;
import com.google.common.io.CharStreams;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import javax.lang.model.element.Modifier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
final class FormatterTest {

    private static final Formatter FORMATTER = Formatter.createFormatter(
            JavaFormatterOptions.builder().style(Style.OJF).build());

    @TempDir
    public Path testFolder;

    @Test
    void formatNonJavaFiles() throws Exception {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(new PrintWriter(out, true), new PrintWriter(err, true), System.in);

        // should succeed because non-Java files are skipped
        assertThat(main.format("foo.go")).isEqualTo(0);
        assertThat(err.toString()).contains("Skipping non-Java file: " + "foo.go");

        // format still fails on missing files
        assertThat(main.format("Foo.java")).isEqualTo(2);
        assertThat(err.toString()).contains("Foo.java: could not read file: ");
    }

    @Test
    void formatStdinStdoutWithDashFlag() throws Exception {
        String input = "class Foo{\n" + "void f\n" + "() {\n" + "}\n" + "}\n";
        String expectedOutput = "class Foo {\n" + "    void f() {}\n" + "}\n";

        InputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        InputStream oldIn = System.in;
        System.setIn(in);

        Main main = new Main(new PrintWriter(out, true), new PrintWriter(err, true), System.in);
        assertThat(main.format("-")).isEqualTo(0);
        assertThat(out.toString()).isEqualTo(expectedOutput);

        System.setIn(oldIn);
    }

    @Test
    void formatLengthUpToEOF() throws Exception {
        String input = "class Foo{\n" + "void f\n" + "() {\n" + "}\n" + "}\n\n\n\n\n\n";
        String expectedOutput = "class Foo {\n" + "    void f() {}\n" + "}\n";

        Path path = testFolder.resolve("Foo.java");
        Files.write(path, input.getBytes(StandardCharsets.UTF_8));

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        Main main = new Main(new PrintWriter(out, true), new PrintWriter(err, true), System.in);
        String[] args = {"--offset", "0", "--length", String.valueOf(input.length()), path.toString()};
        assertThat(main.format(args)).isEqualTo(0);
        assertThat(out.toString()).isEqualTo(expectedOutput);
    }

    @Test
    void formatLengthOutOfRange() throws Exception {
        String input = "class Foo{}\n";

        Path path = testFolder.resolve("Foo.java");
        Files.write(path, input.getBytes(StandardCharsets.UTF_8));

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        Main main = new Main(new PrintWriter(out, true), new PrintWriter(err, true), System.in);
        String[] args = {"--offset", "0", "--length", "9999", path.toString()};
        assertThat(main.format(args)).isEqualTo(2);
        assertThat(err.toString()).contains("error: invalid length 9999, offset + length (9999) is outside the file");
    }

    @Test
    void blankInClassBodyNoTrailing() throws FormatterException {
        String input = "package test;\nclass T {\n\n}";
        String output = FORMATTER.formatSource(input);
        String expect = "package test;\n\nclass T {}\n";
        assertThat(output).isEqualTo(expect);
    }

    private static final String UNORDERED_IMPORTS = Joiner.on('\n')
            .join(
                    "import com.google.common.base.Preconditions;",
                    "",
                    "import static org.junit.Assert.fail;",
                    "import static com.google.truth.Truth.assertThat;",
                    "",
                    "import org.junit.runners.JUnit4;",
                    "import org.junit.runner.RunWith;",
                    "",
                    "import java.util.List;",
                    "",
                    "import javax.annotations.Nullable;");

    @Test
    void importsNotReorderedByDefault() throws FormatterException {
        String input = "package com.google.example;\n" + UNORDERED_IMPORTS + "\npublic class ExampleTest {}\n";
        String output = FORMATTER.formatSource(input);
        String expect = "package com.google.example;\n\n" + UNORDERED_IMPORTS + "\n\npublic class ExampleTest {}\n";
        assertThat(output).isEqualTo(expect);
    }

    @Test
    void importOrderingWithoutFormatting() throws IOException, UsageException {
        importOrdering("--fix-imports-only", "com/palantir/javaformat/java/testimports/A.imports-only");
    }

    @Test
    void importOrderingAndFormatting() throws IOException, UsageException {
        importOrdering(null, "com/palantir/javaformat/java/testimports/A.imports-and-formatting");
    }

    @Test
    void formattingWithoutImportOrdering() throws IOException, UsageException {
        importOrdering(
                "--skip-sorting-imports",
                "com/palantir/javaformat/java/testimports/A.formatting-and-unused-import-removal");
    }

    @Test
    void formattingWithoutRemovingUnusedImports() throws IOException, UsageException {
        importOrdering(
                "--skip-removing-unused-imports",
                "com/palantir/javaformat/java/testimports/A.formatting-and-import-sorting");
    }

    private void importOrdering(@Nullable String sortArg, String outputResourceName)
            throws IOException, UsageException {
        Path path = testFolder.resolve("Foo.java");

        String inputResourceName = "com/palantir/javaformat/java/testimports/A.input";
        String input = getResource(inputResourceName);
        String expectedOutput = getResource(outputResourceName);
        Files.write(path, input.getBytes(StandardCharsets.UTF_8));

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(new PrintWriter(out, true), new PrintWriter(err, true), System.in);
        String[] args =
                sortArg != null ? new String[] {sortArg, "-i", path.toString()} : new String[] {"-i", path.toString()};
        main.format(args);

        assertThat(err.toString()).isEmpty();
        assertThat(out.toString()).isEmpty();
        String output = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        assertThat(output).isEqualTo(expectedOutput);
    }

    private String getResource(String resourceName) throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            assertWithMessage("Missing resource: " + resourceName).that(stream).isNotNull();
            return CharStreams.toString(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }

    // regression test for google-java-format#47
    @Test
    void trailingCommentWithoutTerminalNewline() throws Exception {
        assertThat(FORMATTER.formatSource("/*\n * my comment */")).isEqualTo("/*\n * my comment */\n");
    }

    @Test
    void throwsFormatterException() throws Exception {
        assertThatThrownBy(() -> FORMATTER.formatSourceAndFixImports("package foo; public class {"))
                .isInstanceOf(FormatterException.class);
    }

    @Test
    void blankLinesImportComment() throws FormatterException {
        String withBlank = "package p;\n"
                + "\n"
                + "/** test */\n"
                + "\n"
                + "import a.A;\n"
                + "\n"
                + "class T {\n"
                + "    A a;\n"
                + "}\n";
        String withoutBlank =
                "package p;\n" + "\n" + "/** test */\n" + "import a.A;\n" + "\n" + "class T {\n" + "    A a;\n" + "}\n";

        // Formatting deletes the blank line between the "javadoc" and the first import.
        assertThat(FORMATTER.formatSource(withBlank)).isEqualTo(withoutBlank);
        assertThat(FORMATTER.formatSourceAndFixImports(withBlank)).isEqualTo(withoutBlank);
        assertThat(FORMATTER.formatSource(withoutBlank)).isEqualTo(withoutBlank);
        assertThat(FORMATTER.formatSourceAndFixImports(withoutBlank)).isEqualTo(withoutBlank);

        // Just fixing imports preserves whitespace around imports.
        assertThat(RemoveUnusedImports.removeUnusedImports(withBlank)).isEqualTo(withBlank);
        assertThat(ImportOrderer.reorderImports(withBlank, Style.OJF)).isEqualTo(withBlank);
        assertThat(RemoveUnusedImports.removeUnusedImports(withoutBlank)).isEqualTo(withoutBlank);
        assertThat(ImportOrderer.reorderImports(withoutBlank, Style.OJF)).isEqualTo(withoutBlank);
    }

    @Test
    void canParse_java9_private_interface_methods() {
        assertThatCode(() -> FORMATTER.formatSourceAndFixImports(""
                        + "interface T {\n"
                        + "    private static void foo() {}\n" //
                        + "}"))
                .doesNotThrowAnyException();
    }

    @Test
    void producesFormattingChangesOnAlreadyFormattedFiles() throws FormatterException {
        Formatter formatter = FORMATTER;
        String simpleClass = "package com.palantir;\n"
                + "\n"
                + "public final class Formatted {\n"
                + "  public static int count(int number) {\n"
                + "    return number;\n"
                + "  }\n"
                + "}";
        // This is kinda overkill but the point of the test is to show that it
        // is for sure formatted and still producing a replacement.
        String formattedClass = formatter.formatSourceAndFixImports(simpleClass);
        String reformattedClass = formatter.formatSourceAndFixImports(formattedClass);

        // The formatSourceAndFixImports code path flows through getFormatReplacements and
        // this shows that despite the replacements happening they are superfluous
        assertThat(formattedClass).isEqualTo(reformattedClass);

        assertWithMessage("""
            If this test is failing and you are reading this message it means that an underlying bug
            in the formatting service was fixed. Previously, this formatter always produced a "replacement"
            even when the document was fully formatted. You can see evidence of this in
            https://github.com/palantir/palantir-java-format/pull/1188. To fix this failing test, consider
            relying on the new behavior and instead checking that the 'replacements' is empty instead of
            full string comparison.
            """)
                .that(formatter.getFormatReplacements(
                        formattedClass, List.of(Range.closedOpen(0, formattedClass.length()))))
                .isNotEmpty();
    }

    @Test
    void formatsDeeplyNestedCallsQuickly() {
        // Nine nested Map.ofEntries(Map.entry(...)) are one more than fit in 120 columns with each argument kept on its
        // call's line. Every such attempt then failed, but only after laying out everything inside it, and each level
        // above repeated it for every layout it tried: formatting took time exponential in the depth.
        String call = "Map.ofEntries(Map.entry(\"key0\", \"value0\"), Map.entry(\"key1\", \"value1\"))";
        for (int depth = 0; depth < 9; depth++) {
            call = "Map.ofEntries(Map.entry(\"key" + depth + "\", " + call + "))";
        }
        String input = "class DeepNesting {\n    Object foo() {\n        return " + call + ";\n    }\n}\n";
        Formatter formatter = Formatter.createFormatter(
                JavaFormatterOptions.builder().style(Style.OJF).build());

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> formatter.formatSource(input));
    }

    @Test
    void indentsCommentsThatShareALineLinearly() throws FormatterException {
        // Commented-out code that had javadoc in it puts many multi-line comments on one line: "*//** javadoc *//*".
        // Each comment's continuation lines are indented to the column where the comment starts, and the column after
        // a comment used to be counted from where it started plus its last line, which already holds that indent. So
        // every comment started twice as far right as the one before, and these sixteen came out as 4.3 MB.
        StringBuilder input = new StringBuilder("/*\nclass X {\n");
        for (int i = 0; i < 16; i++) {
            input.append("\t*//** javadoc *//*\n\tpublic void foo(Bar bar) {}\n\n");
        }
        input.append("}*/\n");
        Formatter formatter = Formatter.createFormatter(
                JavaFormatterOptions.builder().style(Style.OJF).build());

        assertThat(formatter.formatSource(input.toString()).length()).isLessThan(10_000);
    }

    @Test
    void valueClassAndRecord() throws FormatterException {
        Assumptions.assumeTrue(
                Arrays.stream(Modifier.values())
                        .anyMatch(modifier -> modifier.name().equals("VALUE")),
                "`value` is a modifier only on a JDK with Project Valhalla's value classes");
        String input = """
            value final public class PointValueClass {
                final int x;
                final int y;

                PointValueClass(int x, int y) {
                    this.x = x;
                    this.y = y;
                }
            }

            value public record PointValueRecord(int x, int y) {}
            """;
        String expected = """
            public final value class PointValueClass {
                final int x;
                final int y;

                PointValueClass(int x, int y) {
                    this.x = x;
                    this.y = y;
                }
            }

            public value record PointValueRecord(int x, int y) {}
            """;
        Formatter formatter = Formatter.createFormatter(
                JavaFormatterOptions.builder().style(Style.OJF).build());

        assertThat(formatter.formatSource(input)).isEqualTo(expected);
    }
}
