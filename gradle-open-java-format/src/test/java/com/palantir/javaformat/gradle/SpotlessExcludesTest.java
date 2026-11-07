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
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ParameterizedClass(name = "configuration cache {0}")
@ValueSource(booleans = {true, false})
final class SpotlessExcludesTest {

    // Forward slashes: the path goes into a Groovy string, where a Windows backslash would start an escape.
    private static final String CLASSPATH_FILE =
            new File("build/impl.classpath").getAbsolutePath().replace('\\', '/');

    private static final String SOURCE_FILE = """
        package test;
        import java.lang.Void;
        public class Test { Void test() { return null; } }
        """;

    @Parameter
    private boolean configurationCache;

    @TempDir
    private Path projectDir;

    private GradleTestProject project;

    @BeforeEach
    void setup() {
        project = new GradleTestProject(projectDir)
                .configurationCache(configurationCache)
                .plugins("java", "dev.openjavaformat.java-format", "com.diffplug.spotless")
                .withJavacInternalExports()
                .buildGradle("""
                    dependencies {
                        palantirJavaFormat files(file("%s").text.split(File.pathSeparator))
                    }
                    """, CLASSPATH_FILE);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "build/generated/java",
                "src/generated/java",
                "src/main/generated_testsrc",
                "src/main/generated_foo_testsrc",
                "generated_testSrc",
                "build/groovy-dsl-plugins/output"
            })
    void format_ignores_excluded_directories(String srcDir) {
        project.buildGradle("""
            sourceSets {
                main {
                    java { srcDir '%s' }
                }
            }
            """, srcDir).writeFile(srcDir + "/test/Test.java", SOURCE_FILE);

        BuildResult result = project.succeeds("spotlessJavaCheck");

        assertThat(result.task(":spotlessJava")).isNotNull().extracting(task -> task.getOutcome())
                .isEqualTo(TaskOutcome.SUCCESS);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "foo/generated_foo/src/bar",
                "src/main/java/test/generatedNamespace",
            })
    void format_checks_non_generated_files(String srcDir) {
        project.buildGradle("""
            sourceSets {
                main {
                    java { srcDir '%s' }
                }
            }
            """, srcDir).writeFile(srcDir + "/Test.java", SOURCE_FILE);

        BuildResult result = project.fails("spotlessJavaCheck");

        assertThat(result.task(":spotlessJava")).isNotNull().extracting(task -> task.getOutcome())
                .isEqualTo(TaskOutcome.SUCCESS);
    }
}
