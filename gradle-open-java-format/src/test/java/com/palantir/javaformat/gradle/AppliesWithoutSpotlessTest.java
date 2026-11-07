/*
 * (c) Copyright 2026 Palantir Technologies Inc. All rights reserved.
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
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Most projects that apply the formatter never apply Spotless, so there are no Spotless classes on their buildscript
 * classpath. The Spotless integration has to stay dormant there instead of failing the whole plugin.
 */
@ParameterizedClass(name = "configuration cache {0}")
@ValueSource(booleans = {true, false})
final class AppliesWithoutSpotlessTest {

    @Parameter
    private boolean configurationCache;

    @TempDir
    private Path projectDir;

    @Test
    void applies_without_spotless_on_the_classpath() {
        GradleTestProject project = new GradleTestProject(projectDir)
                .configurationCache(configurationCache)
                .plugins("java", "dev.openjavaformat.java-format")
                .withoutSpotlessOnClasspath();

        BuildResult result = project.succeeds("tasks", "--all");

        assertThat(result.getOutput()).contains("formatDiff");
    }
}
