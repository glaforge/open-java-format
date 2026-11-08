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
import java.io.IOException;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Where the plugin is applied, it formats: with no repository in the build and nothing declared, the formatter is the
 * one inside the plugin (#20).
 *
 * <p>Every test checks the files the build leaves, as a task that finds no formatter may still succeed.
 */
@ParameterizedClass(name = "configuration cache {0}")
@ValueSource(booleans = {true, false})
final class WithoutRepositoriesTest {

    private static final String MAIN_JAVA = "src/main/java/Main.java";
    private static final String UNFORMATTED = "class Main {\n        int x;\n}\n";
    private static final String FORMATTED = "class Main {\n    int x;\n}\n";

    /** The layout of the docs, minus the repositories: the plugins declared in the root project, applied below. */
    private static final String SUBPROJECTS = """
        subprojects {
            apply plugin: 'java'
            apply plugin: 'dev.openjavaformat.java-format'
            apply plugin: 'com.diffplug.spotless'
        }
        """;

    @Parameter
    private boolean configurationCache;

    @TempDir
    private Path projectDir;

    @Test
    void spotlessApply_formats_with_the_formatter_inside_the_plugin() {
        var project = project()
                .plugins("java", "dev.openjavaformat.java-format", "com.diffplug.spotless")
                .writeFile(MAIN_JAVA, UNFORMATTED);

        project.succeeds("spotlessApply");

        assertFormatted(project, MAIN_JAVA);
    }

    @Test
    void formatDiff_formats_with_the_formatter_inside_the_plugin() throws IOException, InterruptedException {
        var project = project().plugins("java", "dev.openjavaformat.java-format");
        commitThenChange(project, MAIN_JAVA);

        project.succeeds("formatDiff");

        assertFormatted(project, MAIN_JAVA);
    }

    @Test
    void spotlessApply_formats_every_subproject_in_a_parallel_build() {
        var project = subprojects().writeFile("a/" + MAIN_JAVA, UNFORMATTED).writeFile("b/" + MAIN_JAVA, UNFORMATTED);

        project.succeeds("spotlessApply", "--parallel");

        assertFormatted(project, "a/" + MAIN_JAVA, "b/" + MAIN_JAVA);
    }

    @Test
    void formatDiff_formats_every_subproject_in_a_parallel_build() throws IOException, InterruptedException {
        var project = subprojects();
        commitThenChange(project, "a/" + MAIN_JAVA, "b/" + MAIN_JAVA);

        project.succeeds("formatDiff", "--parallel");

        assertFormatted(project, "a/" + MAIN_JAVA, "b/" + MAIN_JAVA);
    }

    @Test
    void the_idea_settings_list_the_formatter_inside_the_plugin() {
        // updateOpenJavaFormatXml runs with every build of a root project that applies idea.
        var project = ideaProject();

        var result = project.succeeds("help");

        var jars = BundledFormatter.jars(gradleUserHome(result).toFile());
        assertThat(project.readFile(".idea/open-java-format.xml"))
                .contains(jars.stream().map(jar -> jar.toURI().toString()).toList());
    }

    @Test
    @DisabledOnOs(
            value = OS.WINDOWS,
            disabledReason =
                    "A daemon of another test may hold the extracted jars open, and Windows does not delete it")
    void the_idea_settings_are_written_again_once_the_extracted_formatter_is_deleted() {
        var project = ideaProject();
        var gradleUserHome = gradleUserHome(project.succeeds("help"));
        new GradleTestProject(gradleUserHome).delete("open-java-format-caches/" + BundledFormatter.hash());

        var result = project.succeeds("help");

        // The IDE reads the jars from there: the task must not be up to date while they are gone.
        assertThat(result.task(":updateOpenJavaFormatXml"))
                .isNotNull()
                .extracting(BuildTask::getOutcome)
                .isEqualTo(TaskOutcome.SUCCESS);
        assertThat(BundledFormatter.jars(gradleUserHome.toFile()))
                .allSatisfy(jar -> assertThat(jar).isFile());
    }

    /** Prints the Gradle user home, where the formatter inside the plugin is extracted. */
    private GradleTestProject ideaProject() {
        return project()
                .plugins("java", "dev.openjavaformat.java-format", "idea")
                .buildGradle("println \"gradleUserHome=${gradle.gradleUserHomeDir}\"");
    }

    private static Path gradleUserHome(BuildResult result) {
        return result.getOutput()
                .lines()
                .filter(line -> line.startsWith("gradleUserHome="))
                .map(line -> Path.of(line.substring("gradleUserHome=".length())))
                .findFirst()
                .orElseThrow();
    }

    private GradleTestProject project() {
        return new GradleTestProject(projectDir)
                .configurationCache(configurationCache)
                .withoutRepositories()
                .withJavacInternalExports();
    }

    private GradleTestProject subprojects() {
        return project()
                .pluginsNotApplied("dev.openjavaformat.java-format", "com.diffplug.spotless")
                .subprojects("a", "b")
                .buildGradle(SUBPROJECTS);
    }

    /** Commits the files in a formatted form, then writes them unformatted: formatDiff formats what git shows changed. */
    private static void commitThenChange(GradleTestProject project, String... files)
            throws IOException, InterruptedException {
        for (var file : files) {
            project.writeFile(file, "class Main {}\n");
        }
        git(project, "init");
        git(project, "config", "user.name", "Foo");
        git(project, "config", "user.email", "foo@bar.com");
        // The repository this runs in may sign commits; a throwaway one has no key to sign with.
        git(project, "config", "commit.gpgsign", "false");
        git(project, "add", ".");
        git(project, "commit", "-m", "Commit");
        for (var file : files) {
            project.writeFile(file, UNFORMATTED);
        }
    }

    private static void assertFormatted(GradleTestProject project, String... files) {
        for (var file : files) {
            // Spotless writes the platform's line endings, CRLF on Windows.
            assertThat(project.readFile(file)).as(file).isEqualToNormalizingNewlines(FORMATTED);
        }
    }

    private static void git(GradleTestProject project, String... args) throws IOException, InterruptedException {
        var command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        var process =
                new ProcessBuilder(command).directory(project.path().toFile()).start();
        var exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("git " + String.join(" ", args) + " failed with exit code " + exitCode);
        }
    }
}
