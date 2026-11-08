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
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The formatter in a build of several projects, laid out as the docs recommend: the plugins declared in the root
 * project, applied to the subprojects, and the formatter resolved through the root project's palantirJavaFormat.
 *
 * <p>Every test checks the files the build leaves: a subproject whose formatter went missing is not formatted, though
 * its task may well succeed.
 */
@ParameterizedClass(name = "configuration cache {0}")
@ValueSource(booleans = {true, false})
final class MultiProjectTest {

    // Forward slashes: the path goes into a Groovy string, where a Windows backslash would start an escape.
    private static final String CLASSPATH_FILE =
            new File("build/impl.classpath").getAbsolutePath().replace('\\', '/');

    /**
     * A Maven repository with test:variants:1.0. Not in a temporary directory: the daemon keeps the formatter's jars
     * open after the build, and Windows does not delete an open file.
     */
    private static final Path VARIANTS_REPO = Path.of("build/variants-repo");

    private static final List<String> SUBPROJECTS = List.of("a", "b");
    private static final String MAIN_JAVA = "src/main/java/Main.java";
    private static final String UNFORMATTED = "class Main {\n        int x;\n}\n";
    private static final String FORMATTED = "class Main {\n    int x;\n}\n";

    /** The subprojects apply the plugins, and the formatter is the jars this build made, not a release. */
    private static final String SUBPROJECTS_AND_FORMATTER = """
        subprojects {
            apply plugin: 'java'
            apply plugin: 'dev.openjavaformat.java-format'
            apply plugin: 'com.diffplug.spotless'
        }

        // Below subprojects: applying the plugin there is what gives the root project palantirJavaFormat.
        dependencies {
            palantirJavaFormat files(file("%s").text.split(File.pathSeparator))
        }
        """;

    @Parameter
    private boolean configurationCache;

    @TempDir
    private Path projectDir;

    @Test
    void spotlessApply_formats_every_subproject_in_a_parallel_build() {
        var project = project().writeFile("a/" + MAIN_JAVA, UNFORMATTED).writeFile("b/" + MAIN_JAVA, UNFORMATTED);

        // In parallel, a subproject's task runs without the root project's lock, which resolving the root project's
        // configuration needs.
        project.succeeds("spotlessApply", "--parallel");

        assertFormatted(project, "a/" + MAIN_JAVA, "b/" + MAIN_JAVA);
    }

    @Test
    void spotlessApply_formats_the_root_project_and_every_subproject_when_the_root_has_java() {
        // The root project's own java variants must not stand in for the formatter's.
        var project = new GradleTestProject(projectDir)
                .configurationCache(configurationCache)
                .plugins("java", "dev.openjavaformat.java-format", "com.diffplug.spotless")
                .subprojects(SUBPROJECTS.toArray(String[]::new))
                .withJavacInternalExports()
                .buildGradle(SUBPROJECTS_AND_FORMATTER, CLASSPATH_FILE)
                .writeFile(MAIN_JAVA, UNFORMATTED)
                .writeFile("a/" + MAIN_JAVA, UNFORMATTED)
                .writeFile("b/" + MAIN_JAVA, UNFORMATTED);

        project.succeeds("spotlessApply", "--parallel");

        assertFormatted(project, MAIN_JAVA, "a/" + MAIN_JAVA, "b/" + MAIN_JAVA);
    }

    @Test
    void formats_with_a_formatter_dependency_that_has_variants_when_the_root_has_no_java() {
        writeLibraryWithVariants();
        // idea: updateOpenJavaFormatXml runs with every build and resolves palantirJavaFormat in the root project.
        var project = project()
                .plugins("idea")
                .buildGradle("""
                    allprojects {
                        repositories {
                            maven { url = file("%s") }
                        }
                    }
                    dependencies {
                        palantirJavaFormat 'test:variants:1.0'
                    }
                    """, VARIANTS_REPO.toAbsolutePath().toString().replace('\\', '/'))
                .writeFile("a/" + MAIN_JAVA, UNFORMATTED)
                .writeFile("b/" + MAIN_JAVA, UNFORMATTED);

        project.succeeds("spotlessApply");

        assertFormatted(project, "a/" + MAIN_JAVA, "b/" + MAIN_JAVA);
        assertThat(project.readFile(".idea/open-java-format.xml"))
                .contains("variants-1.0.jar")
                .doesNotContain("variants-1.0-android.jar");
    }

    @Test
    void nothing_is_resolved_for_a_build_that_does_not_format() {
        // A formatter that cannot be resolved fails a build only once a task needs its jars.
        var project = project().buildGradle("""
            dependencies {
                palantirJavaFormat 'does.not:exist:1.0'
            }
            """);

        project.succeeds("help");
    }

    @Test
    void formatDiff_formats_every_subproject_in_a_parallel_build() throws IOException, InterruptedException {
        var project =
                project().writeFile("a/" + MAIN_JAVA, "class Main {}\n").writeFile("b/" + MAIN_JAVA, "class Main {}\n");
        git(project, "init");
        git(project, "config", "user.name", "Foo");
        git(project, "config", "user.email", "foo@bar.com");
        // The repository this runs in may sign commits; a throwaway one has no key to sign with.
        git(project, "config", "commit.gpgsign", "false");
        git(project, "add", ".");
        git(project, "commit", "-m", "Commit");
        project.writeFile("a/" + MAIN_JAVA, UNFORMATTED).writeFile("b/" + MAIN_JAVA, UNFORMATTED);

        project.succeeds("formatDiff", "--parallel");

        assertFormatted(project, "a/" + MAIN_JAVA, "b/" + MAIN_JAVA);
    }

    /** The layout of the docs, with no java on the root project. */
    private GradleTestProject project() {
        return new GradleTestProject(projectDir)
                .configurationCache(configurationCache)
                .pluginsNotApplied("dev.openjavaformat.java-format", "com.diffplug.spotless")
                .subprojects(SUBPROJECTS.toArray(String[]::new))
                .withJavacInternalExports()
                .buildGradle(SUBPROJECTS_AND_FORMATTER, CLASSPATH_FILE);
    }

    private static void assertFormatted(GradleTestProject project, String... files) {
        for (var file : files) {
            // Spotless writes the platform's line endings, CRLF on Windows.
            assertThat(project.readFile(file)).as(file).isEqualToNormalizingNewlines(FORMATTED);
        }
    }

    /**
     * A library with two runtime variants, for the JRE and for Android, as Guava publishes them: Gradle picks one only
     * for a configuration that asks for a library of a standard JVM. The jars are empty.
     */
    private static void writeLibraryWithVariants() {
        var dir = VARIANTS_REPO.resolve("test/variants/1.0");
        try {
            Files.createDirectories(dir);
            writeEmptyJar(dir.resolve("variants-1.0.jar"));
            writeEmptyJar(dir.resolve("variants-1.0-android.jar"));
            write(dir.resolve("variants-1.0.pom"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <!-- do_not_remove: published-with-gradle-metadata -->
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId>
                  <artifactId>variants</artifactId>
                  <version>1.0</version>
                </project>
                """);
            write(dir.resolve("variants-1.0.module"), """
                {
                  "formatVersion": "1.1",
                  "component": { "group": "test", "module": "variants", "version": "1.0" },
                  "variants": [%s, %s]
                }
                """.formatted(
                            variant("jreRuntimeElements", "standard-jvm", "variants-1.0.jar"),
                            variant("androidRuntimeElements", "android", "variants-1.0-android.jar")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String variant(String name, String environment, String jar) {
        return """
            {
              "name": "%s",
              "attributes": {
                "org.gradle.category": "library",
                "org.gradle.dependency.bundling": "external",
                "org.gradle.jvm.environment": "%s",
                "org.gradle.libraryelements": "jar",
                "org.gradle.usage": "java-runtime"
              },
              "files": [{ "name": "%s", "url": "%s" }]
            }
            """.formatted(name, environment, jar, jar);
    }

    /** Written once: on Windows a daemon of an earlier test may hold the jars open, and they never change. */
    private static void writeEmptyJar(Path jar) throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (var out = new JarOutputStream(Files.newOutputStream(Files.createFile(jar)), manifest)) {
            out.flush();
        } catch (FileAlreadyExistsException e) {
            // An earlier test wrote it.
        }
    }

    private static void write(Path file, String content) throws IOException {
        if (!Files.exists(file)) {
            Files.writeString(file, content);
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
