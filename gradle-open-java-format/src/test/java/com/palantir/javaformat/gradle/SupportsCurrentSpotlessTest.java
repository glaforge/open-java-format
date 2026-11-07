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

import com.palantir.javaformat.gradle.testing.GradleTestProject;
import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spotless has historically shipped tasks that misbehave under the configuration cache, and the {@code
 * palantirJavaFormat} configuration was once <a
 * href="https://github.com/palantir/palantir-java-format/blob/b7b5995df3be690780939c0d0cb2ec49b99c68c8/gradle-palantir-java-format/src/main/java/com/palantir/javaformat/gradle/spotless/NativePalantirJavaFormatStep.java#L45">resolved
 * eagerly</a>.
 *
 * <p>This test forces the spotless steps to be created and runs the generated build <em>with</em> the configuration
 * cache, which is what now catches eager resolution. gradle-consistent-versions used to play that role; it is gone,
 * because applying it is precisely what Gradle 9 rejects.
 */
final class SupportsCurrentSpotlessTest {

    // Forward slashes: the path goes into a Groovy string, where a Windows backslash would start an escape.
    private static final String CLASSPATH_FILE =
            new File("build/impl.classpath").getAbsolutePath().replace('\\', '/');

    @TempDir
    private Path projectDir;

    @Test
    void palantirjavaformatplugin_works_with_current_spotless() {
        new GradleTestProject(projectDir)
                .configurationCache(true)
                .plugins("java", "dev.openjavaformat.java-format", "com.diffplug.spotless")
                .withJavacInternalExports()
                .buildGradle("""
                    dependencies {
                        palantirJavaFormat files(file("%s").text.split(File.pathSeparator))
                    }

                    // Forces realization of the spotlessJava task, creating the spotless steps. Any
                    // configuration resolved eagerly in them fails the configuration cache here.
                    project.getTasks().getByName("spotlessJava")
                    """, CLASSPATH_FILE)
                .succeeds("classes", "--info");
    }
}
