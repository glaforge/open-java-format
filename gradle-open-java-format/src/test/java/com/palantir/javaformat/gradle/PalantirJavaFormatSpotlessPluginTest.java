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
final class PalantirJavaFormatSpotlessPluginTest {

    /** ./gradlew writeImplClasspath generates this file. */
    // Forward slashes: the path goes into a Groovy string, where a Windows backslash would start an escape.
    private static final String CLASSPATH_FILE =
            new File("build/impl.classpath").getAbsolutePath().replace('\\', '/');

    private static final String NATIVE_IMAGE_FILE =
            new File("build/nativeImage.path").getAbsolutePath().replace('\\', '/');

    private static final String NATIVE_CONFIG =
            "palantirJavaFormatNative files(file(\"" + NATIVE_IMAGE_FILE + "\").text)";

    private static final String MAIN_JAVA = "src/main/java/Main.java";

    @Parameter
    private boolean configurationCache;

    @TempDir
    private Path projectDir;

    // native.formatter=true too formats on the Gradle JVM: Spotless has no native step.
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "                                     | 21 | Using the Java-based formatter",
                "openjavaformat.native.formatter=true | 21 | Using the Java-based formatter"
            })
    void formats_with_spotless_when_spotless_is_applied(
            String extraGradleProperties, String javaVersion, String expectedOutput) {

        String extraDependencies = extraGradleProperties == null ? "" : NATIVE_CONFIG;

        GradleTestProject project = new GradleTestProject(projectDir)
                .configurationCache(configurationCache)
                // The spotless plugin dependency is already brought in by open-java-format
                .plugins("java", "dev.openjavaformat.java-format", "com.diffplug.spotless")
                .withJavacInternalExports()
                .gradleProperties(extraGradleProperties == null ? "" : extraGradleProperties)
                // The generated project resolves this through Gradle's own toolchain detection; this
                // repository no longer provisions JDKs itself (palantir/gradle-jdks was removed).
                .buildGradle("""
                    java {
                        toolchain {
                            languageVersion = JavaLanguageVersion.of(%s)
                        }
                    }
                    """, javaVersion)
                .buildGradle("""
                    dependencies {
                        palantirJavaFormat files(file("%s").text.split(File.pathSeparator))
                        %s
                    }
                    """, CLASSPATH_FILE, extraDependencies)
                .writeFile(MAIN_JAVA, invalidJavaFile());

        BuildResult result = project.succeeds("spotlessApply", "--info");

        // Spotless writes the platform's line endings, CRLF on Windows.
        assertThat(project.readFile(MAIN_JAVA)).isEqualToNormalizingNewlines(validJavaFile());
        assertThat(result.getOutput()).contains(expectedOutput);
    }

    private String validJavaFile() {
        return """
            package test;

            public class Test {
                void test() {
                    int x = 1;
                    System.out.println("Hello");
                    Optional.of("hello").orElseGet(() -> {
                        return "Hello World";
                    });
                }
            }
            """;
    }

    private String invalidJavaFile() {
        return """
            package test;
            import com.java.unused;
            public class Test { void test() {int x = 1;
                System.out.println(
                    "Hello"
                );
                Optional.of("hello").orElseGet(() -> {
                    return "Hello World";
                });
            } }
            """;
    }
}
