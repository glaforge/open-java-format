/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@ParameterizedClass(name = "configuration cache {0}")
@ValueSource(booleans = {true, false})
final class PalantirJavaFormatPluginTest {

    // Forward slashes: the path goes into a Groovy string, where a Windows backslash would start an escape.
    private static final String NATIVE_IMAGE_FILE =
            new File("build/nativeImage.path").getAbsolutePath().replace('\\', '/');

    private static final String NATIVE_CONFIG =
            "palantirJavaFormatNative files(file(\"" + NATIVE_IMAGE_FILE + "\").text)";

    private static final String MAIN_JAVA = "src/main/java/Main.java";

    @Parameter
    private boolean configurationCache;

    @TempDir
    private Path projectDir;

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                " | Using the Java-based formatter",
                "openjavaformat.native.formatter=true | Using the native-image formatter"
            })
    void formatDiff_updates_only_lines_changed_in_git_diff(String extraGradleProperties, String expectedOutput)
            throws IOException, InterruptedException {
        boolean nativeFormatter = extraGradleProperties != null && !extraGradleProperties.isBlank();

        GradleTestProject project = new GradleTestProject(projectDir)
                .configurationCache(configurationCache)
                .plugins("java", "dev.openjavaformat.java-format", "idea")
                .withJavacInternalExports()
                .gradleProperties(nativeFormatter ? extraGradleProperties : "")
                .buildGradle("""
                    dependencies {
                        %s
                    }
                    """, nativeFormatter ? NATIVE_CONFIG : "");

        executeGitCommand(project, "git", "init");
        executeGitCommand(project, "git", "config", "user.name", "Foo");
        executeGitCommand(project, "git", "config", "user.email", "foo@bar.com");
        // The repository this runs in may sign commits; a throwaway one has no key to sign with.
        executeGitCommand(project, "git", "config", "commit.gpgsign", "false");

        project.writeFile(MAIN_JAVA, """
            class Main {
                public static void crazyExistingFormatting  (  String... args) {

                }
            }
            """);

        executeGitCommand(project, "git", "add", ".");
        executeGitCommand(project, "git", "commit", "-m", "Commit");

        project.writeFile(MAIN_JAVA, """
            class Main {
                public static void crazyExistingFormatting  (  String... args) {
                                            System.out.println("Reformat me please");
                    // some comments
                                                    System.out.println("Reformat me again please");
                }
            }
            """);

        BuildResult result = project.succeeds("formatDiff", "--info");

        assertThat(result.getOutput()).contains(expectedOutput);

        assertThat(project.readFile(MAIN_JAVA)).isEqualTo("""
            class Main {
                public static void crazyExistingFormatting  (  String... args) {
                    System.out.println("Reformat me please");
                    // some comments
                    System.out.println("Reformat me again please");
                }
            }
            """);
    }

    private void executeGitCommand(GradleTestProject project, String... command)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(project.path().toFile());
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Git command failed with exit code " + exitCode);
        }
    }
}
