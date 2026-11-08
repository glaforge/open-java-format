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
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Locale;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
final class MainTest {

    private static final ImmutableList<String> ADD_EXPORTS = ImmutableList.of(
            "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED");

    @TempDir
    public Path testFolder;

    // PrintWriter instances used below are hard-coded to use system-default line separator.
    private final Joiner joiner = Joiner.on(System.lineSeparator());

    @Test
    void usageOutput() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(new PrintWriter(out, true), new PrintWriter(err, true), System.in);

        try {
            main.format("--help");
            throw new AssertionError("Expected UsageException to be thrown");
        } catch (UsageException e) {

            String usage = e.getMessage();

            // Check that doc links are included.
            assertThat(usage).contains("https://github.com/openjavaformat/open-java-format");
            assertThat(usage).contains("Usage: open-java-format");

            // Sanity check that a flag and description is in included.
            assertThat(usage).contains("--length");
            assertThat(usage).contains("Character length to format.");

            // Check that some of the additional text is included.
            assertThat(usage).contains("the result is sent to stdout");
        }
    }

    @Test
    void version() throws UsageException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(new PrintWriter(out, true), new PrintWriter(err, true), System.in);
        assertThat(main.format("-version")).isEqualTo(0);
        assertThat(err.toString()).contains("open-java-format: Version ");
    }

    // Main used to leave its thread pool running after format returned. The command line does not notice, because it
    // exits, but anything that runs Main in-process kept the idle threads (#40, from google/google-java-format#384).
    @Test
    void formatLeavesNoPoolThreadRunning() throws Exception {
        Path path = Files.writeString(testFolder.resolve("A.java"), "class A {}\n");
        Main main = new Main(
                new PrintWriter(new StringWriter(), true), new PrintWriter(new StringWriter(), true), System.in);
        // The pool's threads join the thread group of the thread that creates the pool.
        ThreadGroup group = new ThreadGroup("formatLeavesNoPoolThreadRunning");
        FutureTask<Integer> format = new FutureTask<>(() -> main.format(path.toString()));
        new Thread(group, format).start();
        assertThat(format.get()).isEqualTo(0);

        Thread[] threads = new Thread[group.activeCount() + 16];
        int count = group.enumerate(threads);
        for (int i = 0; i < count; i++) {
            threads[i].join(TimeUnit.SECONDS.toMillis(10));
            assertWithMessage(threads[i].getName() + " is still running")
                    .that(threads[i].isAlive())
                    .isFalse();
        }
    }

    @Test
    void preserveOriginalFile() throws Exception {
        Path path = Files.createFile(testFolder.resolve("Test.java"));
        Files.write(path, "class Test {}\n".getBytes(UTF_8));
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ));
        } catch (UnsupportedOperationException e) {
            return;
        }
        Main main = new Main(
                new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.out, UTF_8)), true),
                new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.err, UTF_8)), true),
                System.in);
        int errorCode = main.format("-replace", path.toAbsolutePath().toString());
        assertWithMessage("Error Code").that(errorCode).isEqualTo(0);
    }

    @Test
    void printsUsageWithoutArguments() throws Exception {
        Process process = formatterMain().start();
        process.waitFor();
        String err = new String(ByteStreams.toByteArray(process.getErrorStream()), UTF_8);
        assertThat(err).contains("Usage: open-java-format");
        assertThat(process.exitValue()).isEqualTo(0);
    }

    // end to end javadoc formatting test
    @Test
    void javadoc() throws Exception {
        String[] input = {
            "/**",
            " * graph",
            " *",
            " * graph",
            " *",
            " * @param foo lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do"
                    + " eiusmod tempor incididunt ut labore et dolore magna aliqua",
            " */",
            "class Test {",
            "  /**",
            "   * creates entropy",
            "   */",
            "  public static void main(String... args) {}",
            "}",
        };
        String[] expected = {
            "/**",
            " * graph",
            " *",
            " * graph",
            " *",
            " * @param foo lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do"
                    + " eiusmod tempor incididunt ut labore et dolore magna aliqua",
            " */",
            "class Test {",
            "    /**",
            "     * creates entropy",
            "     */",
            "    public static void main(String... args) {}",
            "}",
            "",
        };
        InputStream in = new ByteArrayInputStream(joiner.join(input).getBytes(UTF_8));
        StringWriter out = new StringWriter();
        Main main = new Main(
                new PrintWriter(out, true),
                new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.err, UTF_8)), true),
                in);
        assertThat(main.format("-")).isEqualTo(0);
        assertThat(out.toString()).isEqualTo(joiner.join(expected));
    }

    // end to end import fixing test
    @Test
    void imports() throws Exception {
        String[] input = {
            "import java.util.LinkedList;",
            "import java.util.List;",
            "import java.util.ArrayList;",
            "class Test {",
            "  /**",
            "   * May be an {@link ArrayList}.",
            "   */",
            "  public static List<String> names;",
            "}",
        };
        String[] expected = {
            "import java.util.ArrayList;",
            "import java.util.List;",
            "",
            "class Test {",
            "  /**",
            "   * May be an {@link ArrayList}.",
            "   */",
            "  public static List<String> names;",
            "}",
        };
        InputStream in = new ByteArrayInputStream(joiner.join(input).getBytes(UTF_8));
        StringWriter out = new StringWriter();
        Main main = new Main(
                new PrintWriter(out, true),
                new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.err, UTF_8)), true),
                in);
        assertThat(main.format("-", "--fix-imports-only")).isEqualTo(0);
        assertThat(out.toString()).isEqualTo(joiner.join(expected));
    }

    @Test
    void optimizeImportsDoesNotLeaveEmptyLines() throws Exception {
        String[] input = {
            "package abc;",
            "",
            "import java.util.LinkedList;",
            "import java.util.List;",
            "import java.util.ArrayList;",
            "",
            "import static java.nio.charset.StandardCharsets.UTF_8;",
            "",
            "import java.util.EnumSet;",
            "",
            "class Test ",
            "extends ArrayList {",
            "}"
        };
        String[] expected = {
            "package abc;", //
            "",
            "import java.util.ArrayList;",
            "",
            "class Test extends ArrayList {}",
            ""
        };

        // pre-check expectation with local formatter instance
        String optimized = Formatter.create().formatSourceAndFixImports(joiner.join(input));
        assertThat(optimized).isEqualTo(joiner.join(expected));

        InputStream in = new ByteArrayInputStream(joiner.join(input).getBytes(UTF_8));
        StringWriter out = new StringWriter();
        Main main = new Main(
                new PrintWriter(out, true),
                new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.err, UTF_8)), true),
                in);
        assertThat(main.format("-")).isEqualTo(0);
        assertThat(out.toString()).isEqualTo(joiner.join(expected));
    }

    // test that -lines handling works with import removal
    @Test
    void importRemovalLines() throws Exception {
        String[] input = {
            "import java.util.ArrayList;",
            "import java.util.List;",
            "class Test {",
            "ArrayList<String> a = new ArrayList<>();",
            "ArrayList<String> b = new ArrayList<>();",
            "}",
        };
        String[] expected = {
            "import java.util.ArrayList;",
            "",
            "class Test {",
            "    ArrayList<String> a = new ArrayList<>();",
            "ArrayList<String> b = new ArrayList<>();",
            "}",
        };
        StringWriter out = new StringWriter();
        Main main = new Main(
                new PrintWriter(out, true),
                new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.err, UTF_8)), true),
                new ByteArrayInputStream(joiner.join(input).getBytes(UTF_8)));
        assertThat(main.format("-", "-lines", "4")).isEqualTo(0);
        assertThat(out.toString()).isEqualTo(joiner.join(expected));
    }

    // An unused import between two blank lines must not leave both of them behind: one run of the command line gives
    // what a second run would, and what the entry point of the Gradle and Spotless step gives (#37, from
    // google/google-java-format#1436).
    @Test
    void unusedImportRemovalLeavesOneBlankLine() throws Exception {
        String[] input = {
            "package com.example;",
            "",
            "import static io.grpc.MethodDescriptor.generateFullMethodName;",
            "",
            "/**",
            " * Javadoc for class.",
            " */",
            "public class TestBug {",
            "}",
            "",
        };
        String[] expected = {
            "package com.example;", //
            "",
            "/**",
            " * Javadoc for class.",
            " */",
            "public class TestBug {}",
            "",
        };
        StringWriter out = new StringWriter();
        Main main = new Main(
                new PrintWriter(out, true),
                new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.err, UTF_8)), true),
                new ByteArrayInputStream(joiner.join(input).getBytes(UTF_8)));
        assertThat(main.format("-")).isEqualTo(0);
        assertThat(out.toString()).isEqualTo(joiner.join(expected));

        Formatter formatter = Formatter.createFormatter(JavaFormatterOptions.builder()
                .style(JavaFormatterOptions.Style.OJF)
                .build());
        assertThat(formatter.formatSourceAndFixImports(joiner.join(input))).isEqualTo(joiner.join(expected));
    }

    // test that errors are reported on the right line when imports are removed
    @Test
    void importRemoveErrorParseError() throws Exception {
        Locale backupLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ROOT);

            String[] input = {
                "import java.util.ArrayList;", //
                "import java.util.List;",
                "class Test {",
                "}}",
            };
            StringWriter out = new StringWriter();
            StringWriter err = new StringWriter();
            Main main = new Main(
                    new PrintWriter(out, true),
                    new PrintWriter(err, true),
                    new ByteArrayInputStream(joiner.join(input).getBytes(UTF_8)));
            assertThat(main.format("-")).isEqualTo(2);
            assertThat(err.toString()).contains("<stdin>:4:2: error: class, interface");

        } finally {
            Locale.setDefault(backupLocale);
        }
    }

    @Test
    void packageInfo() throws Exception {
        String[] input = {
            "@CheckReturnValue",
            "@ParametersAreNonnullByDefault",
            "package com.google.common.labs.base;",
            "",
            "import javax.annotation.CheckReturnValue;",
            "import javax.annotation.ParametersAreNonnullByDefault;",
            "",
        };
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                new ByteArrayInputStream(joiner.join(input).getBytes(UTF_8)));
        assertThat(main.format("-")).isEqualTo(0);
        assertThat(out.toString()).isEqualTo(joiner.join(input));
    }

    @Test
    void newline() throws Exception {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                new ByteArrayInputStream("class T {}\n\t".getBytes(UTF_8)));
        assertThat(main.format("-")).isEqualTo(0);
        assertThat(out.toString()).isEqualTo("class T {}\n");
    }

    @Test
    void dryRunStdinUnchanged() throws Exception {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                new ByteArrayInputStream("class Test {}\n".getBytes(UTF_8)));
        assertThat(main.format("-n", "-")).isEqualTo(0);
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void dryRunStdinChanged() throws Exception {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        String input = "class Test {\n}\n";
        Main main = new Main(
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                new ByteArrayInputStream(input.getBytes(UTF_8)));
        assertThat(main.format("-n", "-")).isEqualTo(0);
        assertThat(out.toString()).isEqualTo("<stdin>" + System.lineSeparator());
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void dryRunFiles() throws Exception {
        Path a = Files.createFile(testFolder.resolve("A.java"));
        Path b = Files.createFile(testFolder.resolve("B.java"));
        Path c = Files.createFile(testFolder.resolve("C.java"));
        Files.write(a, "class A {}\n".getBytes(UTF_8));
        Files.write(b, "class B {\n}\n".getBytes(UTF_8));
        Files.write(c, "class C {\n}\n".getBytes(UTF_8));

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(new PrintWriter(out, true), new PrintWriter(err, true), System.in);
        int exitCode = main.format(
                "-n",
                a.toAbsolutePath().toAbsolutePath().toString(),
                b.toAbsolutePath().toString(),
                c.toAbsolutePath().toString());

        assertThat(exitCode).isEqualTo(0);

        assertThat(out.toString())
                .isEqualTo(b.toAbsolutePath().toString()
                        + System.lineSeparator()
                        + c.toAbsolutePath().toString()
                        + System.lineSeparator());
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void keepGoingWhenFilesDontExist() throws Exception {
        Path a = Files.createFile(testFolder.resolve("A.java"));
        Path b = Files.createFile(testFolder.resolve("B.java"));
        Path c = Files.createFile(testFolder.resolve("C.java"));
        File cFile = c.toFile();
        cFile.delete();

        Files.write(a, "class A{}\n".getBytes(UTF_8));
        Files.write(b, "class B{}\n".getBytes(UTF_8));

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(new PrintWriter(out, true), new PrintWriter(err, true), System.in);

        int exitCode = main.format(
                "",
                a.toAbsolutePath().toString(),
                c.toAbsolutePath().toString(),
                b.toAbsolutePath().toString());

        // Formatter returns failure if a file was not present.
        assertThat(exitCode).isEqualTo(2);

        // Present files were correctly formatted.
        assertThat(out.toString()).isEqualTo("class A {}\nclass B {}\n");

        // File not found still showed error.
        assertThat(err.toString()).isNotEmpty();
    }

    @Test
    void exitIfChangedStdin() throws Exception {
        Path path = Files.createFile(testFolder.resolve("Test.java"));
        Files.write(path, "class Test {\n}\n".getBytes(UTF_8));
        Process process = formatterMain("-").redirectInput(path.toFile()).start();
        process.waitFor();
        String out = new String(ByteStreams.toByteArray(process.getInputStream()), UTF_8);
        assertThat(out).isEqualTo("<stdin>" + System.lineSeparator());
        assertThat(process.exitValue()).isEqualTo(1);
    }

    @Test
    void exitIfChangedFiles() throws Exception {
        Path path = Files.createFile(testFolder.resolve("Test.java"));
        Files.write(path, "class Test {\n}\n".getBytes(UTF_8));
        Process process = formatterMain(path.toAbsolutePath().toString()).start();
        process.waitFor();
        String out = new String(ByteStreams.toByteArray(process.getInputStream()), UTF_8);
        assertThat(out).isEqualTo(path.toAbsolutePath().toString() + System.lineSeparator());
        assertThat(process.exitValue()).isEqualTo(1);
    }

    @Test
    void styleIsAlwaysOjf() throws Exception {
        String input = "class T {\nvoid f() {\nint x;\n}\n}\n";
        String expected = "class T {\n    void f() {\n        int x;\n    }\n}\n";

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                new ByteArrayInputStream(input.getBytes(UTF_8)));
        assertThat(main.format("-")).isEqualTo(0);
        assertThat(out.toString()).isEqualTo(expected);
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void styleFlagIsIgnoredWithAWarning() throws Exception {
        String input = "class T {\nvoid f() {\nint x;\n}\n}\n";
        String expected = "class T {\n    void f() {\n        int x;\n    }\n}\n";

        for (String flag : new String[] {"--aosp", "-aosp", "-a", "--ojf", "-ojf"}) {
            StringWriter out = new StringWriter();
            StringWriter err = new StringWriter();
            Main main = new Main(
                    new PrintWriter(out, true),
                    new PrintWriter(err, true),
                    new ByteArrayInputStream(input.getBytes(UTF_8)));
            assertThat(main.format(flag, "-")).isEqualTo(0);
            assertThat(out.toString()).isEqualTo(expected);
            assertThat(err.toString()).contains("warning: flag \"" + flag + "\" is not supported");
            assertThat(err.toString()).contains("remove it from the command line");
        }
    }

    @Test
    void unknownFlagIsAnError() throws Exception {
        Path path = Files.createFile(testFolder.resolve("Test.java"));
        Files.write(path, "class Test {\n}\n".getBytes(UTF_8));
        Process process =
                formatterMain("--palantir", path.toAbsolutePath().toString()).start();
        process.waitFor();
        String err = new String(ByteStreams.toByteArray(process.getErrorStream()), UTF_8);
        // A check that never ran must not pass for one that found nothing.
        assertThat(err).contains("unexpected flag: --palantir");
        assertThat(process.exitValue()).isEqualTo(2);
    }

    @Test
    void noFilesIsNotAnError() throws Exception {
        // What a script gets when it passes on an empty list, as in $(git ls-files '*.java').
        Process process = formatterMain("--ojf", "--replace").start();
        process.waitFor();
        String err = new String(ByteStreams.toByteArray(process.getErrorStream()), UTF_8);
        assertThat(err).contains("no files were provided");
        assertThat(process.exitValue()).isEqualTo(0);
    }

    @Test
    void helpIsNotAnError() throws Exception {
        Process process = formatterMain("--help").start();
        process.waitFor();
        String err = new String(ByteStreams.toByteArray(process.getErrorStream()), UTF_8);
        assertThat(err).contains("Usage: open-java-format");
        assertThat(process.exitValue()).isEqualTo(0);
    }

    @Test
    void exitIfChangedLosesToParseError() throws Exception {
        Path unformatted = Files.createFile(testFolder.resolve("Unformatted.java"));
        Files.write(unformatted, "class Unformatted {\n}\n".getBytes(UTF_8));
        Path broken = Files.createFile(testFolder.resolve("Broken.java"));
        Files.write(broken, "class Broken {\n".getBytes(UTF_8));

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(new PrintWriter(out, true), new PrintWriter(err, true), System.in);

        // 1 means "not formatted" and 2 means "could not format", so that a caller can tell them apart.
        assertThat(main.format("-n", "--set-exit-if-changed", unformatted.toString()))
                .isEqualTo(1);
        assertThat(main.format("-n", "--set-exit-if-changed", unformatted.toString(), broken.toString()))
                .isEqualTo(2);
        assertThat(err.toString()).contains("Broken.java:1:15: error: reached end of file");
    }

    @Test
    void assumeFilename_error() throws Exception {
        String[] input = {
            "class Test {}}",
        };
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                new ByteArrayInputStream(joiner.join(input).getBytes(UTF_8)));
        assertThat(main.format("--assume-filename=Foo.java", "-")).isEqualTo(2);
        assertThat(err.toString()).contains("Foo.java:1:14: error: class, interface");
    }

    @Test
    void assumeFilename_dryRun() throws Exception {
        String[] input = {
            "class Test {", //
            "}",
        };
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                new ByteArrayInputStream(joiner.join(input).getBytes(UTF_8)));
        assertThat(main.format("--dry-run", "--assume-filename=Foo.java", "-")).isEqualTo(0);
        assertThat(out.toString()).isEqualTo("Foo.java" + System.lineSeparator());
    }

    @Test
    void reflowLongStrings() throws Exception {
        String[] input = {
            "class T {", //
            "  String s = \"one long incredibly unbroken sentence moving from topic to topic so that no"
                    + " one had a chance to interrupt, and it kept going well past the column limit\";",
            "}"
        };
        String[] expected = {
            "class T {",
            "    String s = \"one long incredibly unbroken sentence moving from topic to topic so that no one had a"
                    + " chance to\"",
            "            + \" interrupt, and it kept going well past the column limit\";",
            "}",
            "",
        };
        InputStream in = new ByteArrayInputStream(joiner.join(input).getBytes(UTF_8));
        StringWriter out = new StringWriter();
        Main main = new Main(
                new PrintWriter(out, true),
                new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.err, UTF_8)), true),
                in);
        assertThat(main.format("-")).isEqualTo(0);
        assertThat(out.toString()).isEqualTo(joiner.join(expected));
    }

    @Test
    void noReflowLongStrings() throws Exception {
        String[] input = {
            "class T {", //
            "  String s = \"one long incredibly unbroken sentence moving from topic to topic so that no"
                    + " one had a chance to interrupt, and it kept going well past the column limit\";",
            "}"
        };
        String[] expected = {
            "class T {",
            "    String s =",
            "            \"one long incredibly unbroken sentence moving from topic to topic so that no"
                    + " one had a chance to interrupt, and it kept going well past the column limit\";",
            "}",
            "",
        };
        InputStream in = new ByteArrayInputStream(joiner.join(input).getBytes(UTF_8));
        StringWriter out = new StringWriter();
        Main main = new Main(
                new PrintWriter(out, true),
                new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.err, UTF_8)), true),
                in);
        assertThat(main.format("--skip-reflowing-long-strings", "-")).isEqualTo(0);
        assertThat(out.toString()).isEqualTo(joiner.join(expected));
    }

    // A comment between two imports goes with the import after it, where it used to fail the whole file with "Imports
    // not contiguous" (#39, from google/google-java-format#424). A second run leaves the result alone.
    @Test
    void commentBetweenImportsMovesWithTheImportAfterIt() throws Exception {
        String[] input = {
            "import b.B;", "", "// why we need A", "import a.A;", "", "class T {", "    A a;", "    B b;", "}", "",
        };
        String[] expected = {
            "// why we need A", "import a.A;", "import b.B;", "", "class T {", "    A a;", "    B b;", "}", "",
        };
        for (String[] source : ImmutableList.of(input, expected)) {
            StringWriter out = new StringWriter();
            Main main = new Main(
                    new PrintWriter(out, true),
                    new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.err, UTF_8)), true),
                    new ByteArrayInputStream(joiner.join(source).getBytes(UTF_8)));
            assertThat(main.format("-")).isEqualTo(0);
            assertThat(out.toString()).isEqualTo(joiner.join(expected));
        }
    }

    private static ProcessBuilder formatterMain(String... args) {
        return new ProcessBuilder(ImmutableList.<String>builder()
                        .add(Paths.get(System.getProperty("java.home"))
                                .resolve("bin/java")
                                .toString())
                        .addAll(ADD_EXPORTS)
                        .add("-cp", System.getProperty("java.class.path"))
                        .add(Main.class.getName())
                        .add("-n")
                        .add("--set-exit-if-changed")
                        .add(args)
                        .build())
                .redirectError(Redirect.PIPE)
                .redirectOutput(Redirect.PIPE);
    }
}
