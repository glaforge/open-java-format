/*
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
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Spotless turns whatever a step throws into a lint of the file, and spotlessJava succeeds, with that lint as its
 * output. That is right for a file the formatter cannot parse, and wrong for a build the formatter cannot run in: the
 * lint goes into the build cache, the build cache hands it back after the build is fixed, and spotlessApply has
 * nothing to apply. The formatter's failures for the build, rather than for a file, fail spotlessJava itself.
 */
@ParameterizedClass(name = "configuration cache {0}")
@ValueSource(booleans = {true, false})
final class SpotlessWithoutJavacExportsTest {

    private static final String MAIN_JAVA = "src/main/java/Main.java";
    private static final String UNFORMATTED = "class Main {\n        int x;\n}\n";
    private static final String FORMATTED = "class Main {\n    int x;\n}\n";

    @Parameter
    private boolean configurationCache;

    @TempDir
    private Path projectDir;

    @TempDir
    private Path buildCacheDir;

    @Test
    void spotlessApply_fails_and_names_both_settings_when_the_gradle_jvm_does_not_export_javac() {
        var project = project().writeFile(MAIN_JAVA, UNFORMATTED);

        var result = project.fails("spotlessApply");

        assertThat(spotlessJava(result)).isEqualTo(TaskOutcome.FAILED);
        assertThat(result.getOutput())
                .contains("open-java-format cannot run inside this Gradle JVM: module jdk.compiler does not export ")
                .contains("openjavaformat.native.formatter=true")
                .contains("org.gradle.jvmargs=--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED")
                .doesNotContain("lint error");
        assertThat(project.readFile(MAIN_JAVA)).isEqualTo(UNFORMATTED);
    }

    @Test
    void spotlessApply_formats_once_the_gradle_jvm_exports_javac() {
        var project = project().writeFile(MAIN_JAVA, UNFORMATTED);
        project.fails("spotlessApply");

        // Another daemon, with the flags. The same cache: what the failed build left there must not stand in for it.
        project.withJavacInternalExports().writeFile(MAIN_JAVA, UNFORMATTED);
        var result = project.succeeds("spotlessApply");

        assertThat(spotlessJava(result)).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(project.readFile(MAIN_JAVA)).isEqualTo(FORMATTED);
    }

    @Test
    void spotlessApply_fails_when_the_declared_formatter_does_not_resolve() {
        var project = project().withJavacInternalExports().buildGradle("""
            dependencies {
                palantirJavaFormat 'does.not:exist:1.0'
            }
            """).writeFile(MAIN_JAVA, UNFORMATTED);

        var result = project.fails("spotlessApply");

        // With the configuration cache the build fails as it stores the entry, before any task runs.
        if (result.task(":spotlessJava") != null) {
            assertThat(spotlessJava(result)).isEqualTo(TaskOutcome.FAILED);
        }
        assertThat(result.getOutput()).contains("does.not:exist:1.0").doesNotContain("lint error");
        assertThat(project.readFile(MAIN_JAVA)).isEqualTo(UNFORMATTED);
    }

    @Test
    void a_file_the_formatter_cannot_parse_is_still_a_lint_of_that_file() {
        var project = project().withJavacInternalExports().writeFile(MAIN_JAVA, "class Main {\n    int x =\n");

        var result = project.fails("spotlessCheck");

        assertThat(spotlessJava(result)).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.getOutput()).contains("lint error").contains("Main.java");
    }

    /** No --add-exports, unless a test adds them: a Gradle JVM as it comes. */
    private GradleTestProject project() {
        return new GradleTestProject(projectDir)
                .configurationCache(configurationCache)
                .withBuildCache(buildCacheDir)
                .plugins("java", "dev.openjavaformat.java-format", "com.diffplug.spotless");
    }

    private static TaskOutcome spotlessJava(BuildResult result) {
        BuildTask task = result.task(":spotlessJava");
        assertThat(task).isNotNull();
        return task.getOutcome();
    }
}
