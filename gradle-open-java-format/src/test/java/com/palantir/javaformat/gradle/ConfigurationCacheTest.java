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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs each task twice with the configuration cache, as a build that turns the cache on does: the first run stores the
 * entry, and already runs its tasks from it, the second run reuses it. formatDiff used to call Task.project when it
 * ran, and the Spotless step came back from the cache without its jars and its formatter (#193).
 */
final class ConfigurationCacheTest {

    /** ./gradlew writeImplClasspath generates this file. */
    private static final Path CLASSPATH_FILE = Path.of("build/impl.classpath");

    private static final String STORED = "Configuration cache entry stored.";
    private static final String REUSED = "Configuration cache entry reused.";

    private static final String MAIN_JAVA = "src/main/java/Main.java";
    private static final String UNFORMATTED = "class Main {\n        int x;\n}\n";
    private static final String FORMATTED = "class Main {\n    int x;\n}\n";

    @TempDir
    private Path projectDir;

    @Test
    void formatDiff_formats_from_a_reused_entry() throws IOException, InterruptedException {
        var project = project("java", "dev.openjavaformat.java-format");
        git(project, "init");
        git(project, "config", "user.name", "Foo");
        git(project, "config", "user.email", "foo@bar.com");
        // The repository this runs in may sign commits; a throwaway one has no key to sign with.
        git(project, "config", "commit.gpgsign", "false");
        project.writeFile(MAIN_JAVA, "class Main {}\n");
        git(project, "add", ".");
        git(project, "commit", "-m", "Commit");

        for (var entry : List.of(STORED, REUSED)) {
            project.writeFile(MAIN_JAVA, UNFORMATTED);

            assertThat(project.succeeds("formatDiff").getOutput()).contains(entry);
            assertThat(project.readFile(MAIN_JAVA)).isEqualTo(FORMATTED);
        }
    }

    @Test
    void spotlessApply_formats_from_a_reused_entry() throws IOException {
        var project = project("java", "dev.openjavaformat.java-format", "com.diffplug.spotless");

        for (var entry : List.of(STORED, REUSED)) {
            project.writeFile(MAIN_JAVA, UNFORMATTED);

            assertThat(project.succeeds("spotlessApply").getOutput()).contains(entry);
            // Spotless writes the platform's line endings, CRLF on Windows.
            assertThat(project.readFile(MAIN_JAVA)).isEqualToNormalizingNewlines(FORMATTED);
        }
    }

    @Test
    void spotlessCheck_reports_the_violation_from_a_reused_entry() throws IOException {
        var project = project("java", "dev.openjavaformat.java-format", "com.diffplug.spotless")
                .writeFile(MAIN_JAVA, UNFORMATTED);

        for (var entry : List.of(STORED, REUSED)) {
            // A formatter that fails reports a lint error instead, which is how the lost formatter showed.
            assertThat(project.fails("spotlessCheck").getOutput())
                    .contains(entry)
                    .contains("to fix all violations")
                    .doesNotContain("lint error");
        }
    }

    @Test
    void spotlessJava_is_up_to_date_until_the_formatter_jars_change() throws IOException {
        var jars = Files.readString(CLASSPATH_FILE).trim();
        var project = new GradleTestProject(projectDir)
                .configurationCache(true)
                .plugins("java", "dev.openjavaformat.java-format", "com.diffplug.spotless")
                .withJavacInternalExports()
                .writeFile("formatter.classpath", jars)
                // By default Spotless checks for the platform's line endings, CRLF on Windows; PRESERVE keeps the
                // file's own.
                .buildGradle("""
                    dependencies {
                        palantirJavaFormat files(file("formatter.classpath").text.split(File.pathSeparator))
                    }
                    spotless {
                        lineEndings = 'PRESERVE'
                    }
                    """)
                .writeFile(MAIN_JAVA, FORMATTED);

        assertThat(spotlessJava(project.succeeds("spotlessCheck"))).isEqualTo(TaskOutcome.SUCCESS);
        var unchanged = project.succeeds("spotlessCheck");
        assertThat(unchanged.getOutput()).contains(REUSED);
        assertThat(spotlessJava(unchanged)).isEqualTo(TaskOutcome.UP_TO_DATE);

        // Spotless signs the jars by name, size and content, as another formatter version changes them.
        var extraJar = projectDir.resolve("extra.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(extraJar))) {
            jar.putNextEntry(new JarEntry("extra.txt"));
        }
        project.writeFile("formatter.classpath", jars + File.pathSeparator + extraJar);

        assertThat(spotlessJava(project.succeeds("spotlessCheck"))).isEqualTo(TaskOutcome.SUCCESS);
    }

    @Test
    void idea_tasks_run_from_a_reused_entry() {
        var project = project("java", "dev.openjavaformat.java-format", "idea");

        for (var entry : List.of(STORED, REUSED)) {
            project.file(".idea/open-java-format.xml").toFile().delete();

            // Not `idea`: Gradle's own idea tasks are not compatible with the configuration cache, and running
            // them turns it off. The plugin appends its tasks to every build, so `help` runs them too.
            var result = project.succeeds("help");

            assertThat(result.getOutput()).contains(entry);
            assertThat(result.task(":updateOpenJavaFormatXml"))
                    .isNotNull()
                    .extracting(BuildTask::getOutcome)
                    .isEqualTo(TaskOutcome.SUCCESS);
            assertThat(project.file(".idea/open-java-format.xml")).exists();
        }
    }

    @Test
    void required_idea_plugin_is_listed_from_a_reused_entry() {
        var project = project("java", "dev.openjavaformat.java-format");

        for (var entry : List.of(STORED, REUSED)) {
            project.file(".idea/externalDependencies.xml").toFile().delete();

            assertThat(project.succeeds("help", "-Didea.active=true").getOutput())
                    .contains(entry);
            assertThat(project.file(".idea/externalDependencies.xml")).exists();
        }
    }

    private GradleTestProject project(String... plugins) {
        // Forward slashes: the path goes into a Groovy string, where a Windows backslash would start an escape.
        return new GradleTestProject(projectDir)
                .configurationCache(true)
                .plugins(plugins)
                .withJavacInternalExports()
                .buildGradle("""
                    dependencies {
                        palantirJavaFormat files(file("%s").text.split(File.pathSeparator))
                    }
                    """, CLASSPATH_FILE.toAbsolutePath().toString().replace('\\', '/'));
    }

    private static TaskOutcome spotlessJava(BuildResult result) {
        var task = result.task(":spotlessJava");
        assertThat(task).isNotNull();
        return task.getOutcome();
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
