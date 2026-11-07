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
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;

// Apart from PalantirJavaFormatIdeaPluginTest, which needs the native image and so runs in CI's native jobs only.
@ParameterizedClass(name = "configuration cache {0}")
@ValueSource(booleans = {true, false})
final class RequiredIdeaPluginTest {

    @Parameter
    private boolean configurationCache;

    @TempDir
    private Path projectDir;

    @Test
    void lists_the_idea_plugin_as_required_when_intellij_syncs() {
        GradleTestProject project = new GradleTestProject(projectDir)
                .configurationCache(configurationCache)
                .plugins("dev.openjavaformat.java-format-idea");

        project.succeeds("help");

        assertThat(project.file(".idea/externalDependencies.xml")).doesNotExist();

        // IntelliJ's Gradle sync runs the build with idea.active set.
        BuildResult result = project.succeeds("help", "-Didea.active=true");

        assertThat(result.task(":updateExternalDependenciesXml"))
                .isNotNull()
                .extracting(BuildTask::getOutcome)
                .isEqualTo(TaskOutcome.SUCCESS);
        assertThat(project.readFile(".idea/externalDependencies.xml"))
                .contains("<plugin id=\"open-java-format\" min-version=\"2.57.0\"/>");
    }
}
