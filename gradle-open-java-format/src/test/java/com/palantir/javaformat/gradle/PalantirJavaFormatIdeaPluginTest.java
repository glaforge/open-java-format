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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.palantir.javaformat.gradle.testing.GradleTestProject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ParameterizedClass(name = "configuration cache {0}")
@ValueSource(booleans = {true, false})
final class PalantirJavaFormatIdeaPluginTest {

    private static final ObjectMapper XML_MAPPER = new XmlMapper();

    @Parameter
    private boolean configurationCache;

    @TempDir
    private Path projectDir;

    // openjavaformat.native.formatter is about the build alone: the IDE plugin switches the native binary on by a
    // setting of its own, so the IDE configuration is the same either way, and no native image is needed here.
    @ParameterizedTest(name = "extraGradleProperties={0}")
    @ValueSource(strings = {"", "openjavaformat.native.formatter=true"})
    void idea_configures_xml_files(String extraGradleProperties) throws IOException {
        GradleTestProject project = new GradleTestProject(projectDir)
                .configurationCache(configurationCache)
                .plugins("dev.openjavaformat.java-format-idea", "idea")
                .gradleProperties(extraGradleProperties)
                .buildGradle("""
                    dependencies {
                        palantirJavaFormat project.files() // no need to store the real thing in here
                        palantirJavaFormatNative project.files()
                    }
                    """);

        BuildResult result = project.succeeds("idea");

        // Not requested on the command line: the plugin appends it to the tasks of every build.
        assertThat(result.task(":updateOpenJavaFormatXml"))
                .isNotNull()
                .extracting(BuildTask::getOutcome)
                .isEqualTo(TaskOutcome.SUCCESS);

        assertThat(project.file(".idea/open-java-format.xml")).exists();

        Project xmlContent =
                XML_MAPPER.readValue(project.file(".idea/open-java-format.xml").toFile(), Project.class);

        assertThat(xmlContent.components()).anyMatch(c -> "PalantirJavaFormatSettings".equals(c.name()));

        List<Option> allOptions = xmlContent.components().stream()
                .flatMap(c -> Optional.ofNullable(c.options()).stream().flatMap(List::stream))
                .toList();

        assertThat(allOptions).anyMatch(o -> "implementationClassPath".equals(o.name()));
        assertThat(allOptions).noneMatch(o -> "nativeImageClassPath".equals(o.name()));

        assertThat(project.file(".idea/workspace.xml")).exists();

        Project workspaceContent = XML_MAPPER.readValue(project.file(".idea/workspace.xml").toFile(), Project.class);

        assertThat(workspaceContent.components()).anyMatch(c -> "FormatOnSaveOptions".equals(c.name()));
        assertThat(workspaceContent.components()).anyMatch(c -> "OptimizeOnSaveOptions".equals(c.name()));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Project(
            @JacksonXmlProperty(localName = "component") @JacksonXmlElementWrapper(useWrapping = false)
                    List<Component> components) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Component(
            @JacksonXmlProperty(isAttribute = true) String name,
            @JacksonXmlProperty(localName = "option") @JacksonXmlElementWrapper(useWrapping = false)
                    List<Option> options) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Option(@JacksonXmlProperty(isAttribute = true) String name) {}
}
