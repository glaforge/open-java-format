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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.palantir.javaformat.gradle.testing.GradleTestProject;
import com.palantir.javaformat.java.Replacement;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The native image resolves through the repositories of the project that formats, as #20 asks: a build that declares
 * its repositories in the subprojects only, as it may for a mirror of Maven Central, formats with it.
 *
 * <p>The image is a shell script that stands in for open-java-format-native: it answers formatDiff with the
 * replacements that format Main.java. The build has no --add-exports, so the formatter inside the plugin could not
 * have formatted it; the native jobs of CI run the real image through PalantirJavaFormatPluginTest.
 */
@ParameterizedClass(name = "configuration cache {0}")
@ValueSource(booleans = {true, false})
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "The stand-in for the native image is a shell script")
final class NativeImageResolutionTest {

    /**
     * A Maven repository with dev.openjavaformat:open-java-format-native:1.0 for this platform. Under build/, as the
     * other repositories of the tests are: the daemon may still run the image after a test.
     */
    private static final Path NATIVE_REPO = Path.of("build/native-repo");

    private static final String VERSION = "1.0";
    private static final String MAIN_JAVA = "src/main/java/Main.java";
    private static final String UNFORMATTED = "class Main {\n        int x;\n}\n";
    private static final String FORMATTED = "class Main {\n    int x;\n}\n";

    @Parameter
    private boolean configurationCache;

    @TempDir
    private Path projectDir;

    @Test
    void formatDiff_runs_the_native_image_from_the_repositories_of_the_subprojects() throws Exception {
        var project = project();
        commitThenChange(project, "a/" + MAIN_JAVA, "b/" + MAIN_JAVA);

        var result = project.succeeds("formatDiff", "--info");

        assertThat(result.getOutput()).contains("Using the native-image formatter");
        assertFormatted(project, "a/" + MAIN_JAVA, "b/" + MAIN_JAVA);
        assertRunFromTheCaches(result);
    }

    @Test
    void formatDiff_runs_the_native_image_in_a_parallel_build() throws Exception {
        var project = project();
        commitThenChange(project, "a/" + MAIN_JAVA, "b/" + MAIN_JAVA);

        project.succeeds("formatDiff", "--parallel");

        assertFormatted(project, "a/" + MAIN_JAVA, "b/" + MAIN_JAVA);
    }

    /**
     * The layout of the docs, with the repositories in the subprojects only, and the image declared in the root project
     * as a release's would be by default. Prints the Gradle user home, where the image is copied to.
     */
    private GradleTestProject project() throws IOException {
        var platform = currentPlatform();
        writeNativeImage(platform);
        return new GradleTestProject(projectDir)
                .configurationCache(configurationCache)
                .withoutRepositories()
                .pluginsNotApplied("dev.openjavaformat.java-format")
                .subprojects("a", "b")
                .gradleProperties("openjavaformat.native.formatter=true")
                .buildGradle(
                        """
                        subprojects {
                            apply plugin: 'java'
                            apply plugin: 'dev.openjavaformat.java-format'
                            repositories {
                                maven { url = rootProject.file("%s") }
                            }
                        }
                        dependencies {
                            palantirJavaFormatNative 'dev.openjavaformat:open-java-format-native:%s:%s@%s'
                        }
                        println "gradleUserHome=${gradle.gradleUserHomeDir}"
                        """,
                        NATIVE_REPO.toAbsolutePath().toString().replace('\\', '/'),
                        VERSION,
                        platform.classifier(),
                        platform.extension());
    }

    private static void assertFormatted(GradleTestProject project, String... files) {
        for (var file : files) {
            assertThat(project.readFile(file)).as(file).isEqualTo(FORMATTED);
        }
    }

    /** The build runs the image from a copy of its own, made executable: the repository's file may not be. */
    private static void assertRunFromTheCaches(BuildResult result) throws IOException {
        var gradleUserHome = result.getOutput()
                .lines()
                .filter(line -> line.startsWith("gradleUserHome="))
                .map(line -> Path.of(line.substring("gradleUserHome=".length())))
                .findFirst()
                .orElseThrow();
        try (Stream<Path> files = Files.walk(gradleUserHome.resolve(BundledFormatter.CACHES))) {
            assertThat(files.filter(file -> file.getFileName().toString().startsWith("open-java-format-native-"))
                            .toList())
                    .isNotEmpty()
                    .allSatisfy(image -> assertThat(Files.isExecutable(image)).isTrue());
        }
    }

    private static NativePlatform currentPlatform() {
        return NativePlatform.of(
                System.getProperty("os.name"), System.getProperty("os.arch"), NativeImageResolutionTest::lddVersion);
    }

    private static String lddVersion() {
        try {
            var process = new ProcessBuilder("ldd", "--version")
                    .redirectErrorStream(true)
                    .start();
            var output = new String(process.getInputStream().readAllBytes(), UTF_8);
            process.waitFor();
            return output.lines().findFirst().orElse("");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * open-java-format-native as Maven Central has it, a POM and the image under the platform's classifier, with a
     * script for the image: it reads the source formatDiff sends, and answers with the replacements that format it.
     */
    private static void writeNativeImage(NativePlatform platform) throws IOException {
        var dir = Files.createDirectories(NATIVE_REPO.resolve("dev/openjavaformat/open-java-format-native/" + VERSION));
        Files.writeString(dir.resolve("open-java-format-native-" + VERSION + ".pom"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>dev.openjavaformat</groupId>
              <artifactId>open-java-format-native</artifactId>
              <version>%s</version>
            </project>
            """.formatted(VERSION));
        var replacements = JsonMapper.builder()
                .addModule(new GuavaModule())
                .build()
                .writeValueAsString(List.of(Replacement.create(0, UNFORMATTED.length(), FORMATTED)));
        var image = dir.resolve(
                "open-java-format-native-" + VERSION + "-" + platform.classifier() + "." + platform.extension());
        Files.writeString(image, """
            #!/bin/sh
            cat > /dev/null
            cat <<'JSON'
            %s
            JSON
            """.formatted(replacements));
        Files.setPosixFilePermissions(image, PosixFilePermissions.fromString("rw-r--r--"));
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
