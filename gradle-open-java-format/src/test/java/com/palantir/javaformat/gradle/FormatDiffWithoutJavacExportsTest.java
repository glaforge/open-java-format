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
package com.palantir.javaformat.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.javaformat.gradle.testing.GradleTestProject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A build without the JVM flags used to fail on the first file with a raw IllegalAccessError from the formatter's
 * internals (#19). It fails before the first file now, naming the two settings that make the formatter run. This
 * class runs in the default test task; PalantirJavaFormatPluginTest does not, because it needs the native binary.
 */
@ParameterizedClass(name = "configuration cache {0}")
@ValueSource(booleans = {true, false})
final class FormatDiffWithoutJavacExportsTest {

    /** ./gradlew writeImplClasspath generates this file. Forward slashes: the path goes into a Groovy string. */
    private static final String CLASSPATH_FILE =
            new File("build/impl.classpath").getAbsolutePath().replace('\\', '/');

    private static final String MAIN_JAVA = "src/main/java/Main.java";

    @Parameter
    private boolean configurationCache;

    @TempDir
    private Path projectDir;

    @Test
    void formatDiff_names_both_settings_when_the_gradle_jvm_does_not_export_javac()
            throws IOException, InterruptedException {
        GradleTestProject project = new GradleTestProject(projectDir)
                .configurationCache(configurationCache)
                .plugins("java", "dev.openjavaformat.java-format")
                .buildGradle("""
                    dependencies {
                        palantirJavaFormat files(file("%s").text.split(File.pathSeparator))
                    }
                    """, CLASSPATH_FILE);

        git(project, "init");
        git(project, "config", "user.name", "Foo");
        git(project, "config", "user.email", "foo@bar.com");
        // The repository this runs in may sign commits; a throwaway one has no key to sign with.
        git(project, "config", "commit.gpgsign", "false");
        project.writeFile(MAIN_JAVA, "class Main {}\n");
        git(project, "add", ".");
        git(project, "commit", "-m", "Commit");
        project.writeFile(MAIN_JAVA, "class Main {   int x;   }\n");

        BuildResult result = project.fails("formatDiff");

        // Which of the five packages the message lists depends on what the Gradle daemon exports on its own;
        // com.sun.tools.javac.parser, the one the old error named, is never among those.
        assertThat(result.getOutput())
                .contains("open-java-format cannot run inside this Gradle JVM: module jdk.compiler does not export ")
                .contains("com.sun.tools.javac.parser")
                .contains("openjavaformat.native.formatter=true")
                .contains("org.gradle.jvmargs=--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
                        + " --add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED"
                        + " --add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED"
                        + " --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED"
                        + " --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED")
                .contains("https://openjavaformat.dev/get-started/gradle/#choose-how-the-formatter-runs")
                .doesNotContain("IllegalAccessError")
                .doesNotContain("Formatting ");
        // The check runs before the first file, so nothing was written.
        assertThat(project.readFile(MAIN_JAVA)).isEqualTo("class Main {   int x;   }\n");
    }

    private static void git(GradleTestProject project, String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(project.path().toFile()).start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("git " + String.join(" ", args) + " failed with exit code " + exitCode);
        }
    }
}
