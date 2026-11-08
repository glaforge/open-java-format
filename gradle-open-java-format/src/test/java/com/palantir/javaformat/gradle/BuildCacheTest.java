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
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * spotlessJava is cacheable, and the open-java-format step is part of its cache key: the key changes with the
 * formatter, the one inside the plugin or the one a build declares, and not with the directory the project is in.
 *
 * <p>Every build here is spotlessApply, after the source is written again. The formatted file is what shows that
 * spotlessJava's output, whether it ran, was up to date or came from the cache, holds the source formatted: a step that
 * leaves the source as it is passes a spotlessCheck of a formatted file just as well. Writing the same source again is
 * the change that must not count, as only its time changes.
 */
@ParameterizedClass(name = "configuration cache {0}")
@ValueSource(booleans = {true, false})
final class BuildCacheTest {

    /** ./gradlew writeImplClasspath generates this file: the jars this build made, to declare as an override. */
    private static final Path CLASSPATH_FILE = Path.of("build/impl.classpath");

    private static final String MAIN_JAVA = "src/main/java/Main.java";
    private static final String UNFORMATTED = "class Main {\n        int x;\n}\n";
    private static final String FORMATTED = "class Main {\n    int x;\n}\n";
    private static final String OTHER_UNFORMATTED = "class Main {\n        int y;\n}\n";
    private static final String OTHER_FORMATTED = "class Main {\n    int y;\n}\n";

    @Parameter
    private boolean configurationCache;

    @TempDir
    private Path projectDir;

    @TempDir
    private Path otherProjectDir;

    @TempDir
    private Path buildCacheDir;

    @Test
    void spotlessJava_is_up_to_date_when_the_source_is_written_again_unchanged() {
        var project = project(projectDir);
        assertThat(spotlessApply(project, UNFORMATTED)).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(project.readFile(MAIN_JAVA)).isEqualToNormalizingNewlines(FORMATTED);

        assertThat(spotlessApply(project, UNFORMATTED)).isEqualTo(TaskOutcome.UP_TO_DATE);
        assertThat(project.readFile(MAIN_JAVA)).isEqualToNormalizingNewlines(FORMATTED);
    }

    @Test
    void spotlessJava_runs_again_when_the_source_changes() {
        var project = project(projectDir);
        assertThat(spotlessApply(project, UNFORMATTED)).isEqualTo(TaskOutcome.SUCCESS);

        assertThat(spotlessApply(project, OTHER_UNFORMATTED)).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(project.readFile(MAIN_JAVA)).isEqualToNormalizingNewlines(OTHER_FORMATTED);
    }

    @Test
    void spotlessJava_comes_from_the_cache_when_the_source_changes_back() {
        var project = project(projectDir);
        assertThat(spotlessApply(project, UNFORMATTED)).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(spotlessApply(project, OTHER_UNFORMATTED)).isEqualTo(TaskOutcome.SUCCESS);

        assertThat(spotlessApply(project, UNFORMATTED)).isEqualTo(TaskOutcome.FROM_CACHE);
        assertThat(project.readFile(MAIN_JAVA)).isEqualToNormalizingNewlines(FORMATTED);
    }

    @Test
    void spotlessJava_comes_from_the_cache_after_the_build_directory_is_deleted() {
        var project = project(projectDir);
        assertThat(spotlessApply(project, UNFORMATTED)).isEqualTo(TaskOutcome.SUCCESS);

        project.delete("build");

        assertThat(spotlessApply(project, UNFORMATTED)).isEqualTo(TaskOutcome.FROM_CACHE);
        assertThat(project.readFile(MAIN_JAVA)).isEqualToNormalizingNewlines(FORMATTED);
    }

    @Test
    void spotlessJava_comes_from_the_cache_in_another_directory() {
        assertThat(spotlessApply(project(projectDir), UNFORMATTED)).isEqualTo(TaskOutcome.SUCCESS);

        // The same project somewhere else, as another checkout or another CI machine has it.
        var moved = project(otherProjectDir);

        assertThat(spotlessApply(moved, UNFORMATTED)).isEqualTo(TaskOutcome.FROM_CACHE);
        assertThat(moved.readFile(MAIN_JAVA)).isEqualToNormalizingNewlines(FORMATTED);
    }

    @Test
    void spotlessJava_runs_again_when_the_build_declares_a_formatter() throws IOException {
        var project = project(projectDir);
        assertThat(spotlessApply(project, UNFORMATTED)).isEqualTo(TaskOutcome.SUCCESS);

        // Without the build directory, only the cache could skip the task.
        project.writeFile("formatter.classpath", formatterClasspath()).delete("build");

        assertThat(spotlessApply(project, UNFORMATTED)).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(project.readFile(MAIN_JAVA)).isEqualToNormalizingNewlines(FORMATTED);
    }

    @Test
    void spotlessJava_runs_again_when_the_declared_jars_change() throws IOException, URISyntaxException {
        var project = project(projectDir).writeFile("formatter.classpath", formatterClasspath());
        assertThat(spotlessApply(project, UNFORMATTED)).isEqualTo(TaskOutcome.SUCCESS);

        // Spotless signs the jars by name, size and content, as another formatter version changes them. The extra jar
        // is JUnit's, not one written into projectDir: the daemon keeps the formatter's jars open after the build, and
        // Windows does not delete an open file.
        var extraJar = Path.of(
                Test.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        project.writeFile("formatter.classpath", formatterClasspath() + File.pathSeparator + extraJar)
                .delete("build");

        assertThat(spotlessApply(project, UNFORMATTED)).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(project.readFile(MAIN_JAVA)).isEqualToNormalizingNewlines(FORMATTED);
    }

    /** The formatter inside the plugin, unless the test writes formatter.classpath, which the build then declares. */
    private GradleTestProject project(Path dir) {
        return new GradleTestProject(dir)
                .configurationCache(configurationCache)
                .withBuildCache(buildCacheDir)
                .plugins("java", "dev.openjavaformat.java-format", "com.diffplug.spotless")
                .withJavacInternalExports()
                .buildGradle("""
                    if (file("formatter.classpath").exists()) {
                        dependencies {
                            palantirJavaFormat files(file("formatter.classpath").text.split(File.pathSeparator))
                        }
                    }
                    """);
    }

    private static String formatterClasspath() throws IOException {
        return Files.readString(CLASSPATH_FILE).trim();
    }

    /** Writes the source and runs spotlessApply; returns what spotlessJava did for it. */
    private static TaskOutcome spotlessApply(GradleTestProject project, String source) {
        var task =
                project.writeFile(MAIN_JAVA, source).succeeds("spotlessApply").task(":spotlessJava");
        assertThat(task).isNotNull();
        return task.getOutcome();
    }
}
