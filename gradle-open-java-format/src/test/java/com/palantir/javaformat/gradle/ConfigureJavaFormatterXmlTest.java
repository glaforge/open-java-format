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

import groovy.util.Node;
import groovy.xml.XmlNodePrinter;
import groovy.xml.XmlParser;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.util.List;
import java.util.stream.Stream;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

final class ConfigureJavaFormatterXmlTest {

    @Language("xml")
    private static final String EXISTING_CLASS_PATH = """
        <root>
            <component name="PalantirJavaFormatSettings">
                <option name="enabled" value="true"/>
                <option name="implementationClassPath">
                  <list>
                    <option value="foo" />
                    <option value="aldfjh://barz" />
                  </list>
                </option>
                <option name="nativeImageClassPath" value="nativeFoo"/>
            </component>
        </root>
        """;

    @Language("xml")
    private static final String MISSING_CLASS_PATH = """
        <root>
            <component name="PalantirJavaFormatSettings">
                <option name="style" value="OJF"/>
            </component>
        </root>
        """;

    @Language("xml")
    private static final String MISSING_ENTIRE_BLOCK = """
        <root>
        </root>
        """;

    @Language("xml")
    private static final String EXPECTED = """
        <root>
          <component name="PalantirJavaFormatSettings">
            <option name="enabled" value="true"/>
            <option name="implementationClassPath">
              <list>
                <option value="foo"/>
                <option value="bar"/>
              </list>
            </option>
          </component>
        </root>
        """;

    static Stream<String> actionsOnSave() {
        return Stream.of("Format", "Optimize");
    }

    @Test
    void configure_missingEntireBlock_added() {
        Node node = parseXml(MISSING_ENTIRE_BLOCK);

        ConfigureJavaFormatterXml.configureJavaFormat(node, List.of(URI.create("foo"), URI.create("bar")));

        assertThat(xmlToString(node)).isEqualTo(EXPECTED);
    }

    @Test
    void configure_missingClassPath_added() {
        Node node = parseXml(MISSING_CLASS_PATH);

        ConfigureJavaFormatterXml.configureJavaFormat(node, List.of(URI.create("foo"), URI.create("bar")));

        String expected = """
            <root>
              <component name="PalantirJavaFormatSettings">
                <option name="style" value="OJF"/>
                <option name="enabled" value="true"/>
                <option name="implementationClassPath">
                  <list>
                    <option value="foo"/>
                    <option value="bar"/>
                  </list>
                </option>
              </component>
            </root>
            """;

        assertThat(xmlToString(node)).isEqualTo(expected);
    }

    @Test
    void configure_existingClassPath_modified_andTheNativeImageOfEarlierVersionsRemoved() {
        // Up to 21.0.1 the plugin wrote nativeImageClassPath for openjavaformat.native.formatter=true. The IDE plugin
        // switches the native binary on by a setting of its own now, and no longer reads it.
        Node node = parseXml(EXISTING_CLASS_PATH);

        ConfigureJavaFormatterXml.configureJavaFormat(node, List.of(URI.create("foo"), URI.create("bar")));

        assertThat(xmlToString(node)).isEqualTo(EXPECTED);
    }

    @Test
    void configure_leavesTheIdePluginsNativeBinarySettingsAlone() {
        Node node = parseXml("""
            <root>
                <component name="PalantirJavaFormatSettings">
                    <option name="useNativeImage" value="true"/>
                    <option name="nativeImagePath" value="/opt/open-java-format"/>
                </component>
            </root>
            """);

        ConfigureJavaFormatterXml.configureJavaFormat(node, List.of(URI.create("foo")));

        assertThat(xmlToString(node))
                .contains("<option name=\"useNativeImage\" value=\"true\"/>")
                .contains("<option name=\"nativeImagePath\" value=\"/opt/open-java-format\"/>");
    }

    @ParameterizedTest
    @MethodSource("actionsOnSave")
    void adds_action_OnSave_block_where_none_exists(String action) {
        Node node = parseXml("""
            <root>
            </root>
            """);

        ConfigureJavaFormatterXml.configureWorkspaceXml(node);

        String newXml = xmlSubcomponentToString(node, action + "OnSaveOptions").strip();

        String expected = """
            <component name="%sOnSaveOptions">
              <option name="myRunOnSave" value="true"/>
              <option name="myAllFileTypesSelected" value="false"/>
              <option name="mySelectedFileTypes">
                <set>
                  <option value="JAVA"/>
                </set>
              </option>
            </component>
            """.formatted(action).strip();

        assertThat(newXml).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("actionsOnSave")
    void adds_Java_to_existing_action_OnSave_block(String action) {
        Node node = parseXml("""
            <root>
              <component name="%sOnSaveOptions">
                <option name="mySelectedFileTypes">
                  <set>
                    <option value="Go"/>
                  </set>
                </option>
              </component>
            </root>
            """.formatted(action));

        ConfigureJavaFormatterXml.configureWorkspaceXml(node);
        String newXml = xmlSubcomponentToString(node, action + "OnSaveOptions");

        String expected = """
            <component name="%sOnSaveOptions">
              <option name="mySelectedFileTypes">
                <set>
                  <option value="Go"/>
                  <option value="JAVA"/>
                </set>
              </option>
              <option name="myRunOnSave" value="true"/>
              <option name="myAllFileTypesSelected" value="false"/>
            </component>
            """.formatted(action).strip();

        assertThat(newXml).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("actionsOnSave")
    void if_all_file_types_are_already_configured_to_action_on_save_dont_change_anything(String action) {
        Node node = parseXml("""
            <root>
              <component name="%sOnSaveOptions">
                <option name="myAllFileTypesSelected" value="true"/>
              </component>
            </root>
            """.formatted(action));

        ConfigureJavaFormatterXml.configureWorkspaceXml(node);
        String newXml = xmlSubcomponentToString(node, action + "OnSaveOptions").strip();

        String expected = """
            <component name="%sOnSaveOptions">
              <option name="myAllFileTypesSelected" value="true"/>
              <option name="myRunOnSave" value="true"/>
              <option name="mySelectedFileTypes">
                <set>
                  <option value="JAVA"/>
                </set>
              </option>
            </component>
            """.formatted(action).strip();

        assertThat(newXml).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("actionsOnSave")
    void if_the_myRunOnSave_for_action_on_save_is_explicitly_disabled_turn_it_on(String action) {
        Node node = parseXml("""
            <root>
              <component name="%sOnSaveOptions">
                <option name="myRunOnSave" value="false"/>
                <option name="myAllFileTypesSelected" value="false"/>
                <option name="mySelectedFileTypes">
                  <set>
                    <option value="JAVA"/>
                  </set>
                </option>
              </component>
            </root>
            """.formatted(action));

        ConfigureJavaFormatterXml.configureWorkspaceXml(node);
        String newXml = xmlSubcomponentToString(node, action + "OnSaveOptions");

        String expected = """
            <component name="%sOnSaveOptions">
              <option name="myRunOnSave" value="true"/>
              <option name="myAllFileTypesSelected" value="false"/>
              <option name="mySelectedFileTypes">
                <set>
                  <option value="JAVA"/>
                </set>
              </option>
            </component>
            """.formatted(action).strip();

        assertThat(newXml).isEqualTo(expected);
    }

    @Test
    void lists_the_required_plugin_in_a_new_file() {
        Node node = parseXml("<project version=\"4\"/>");

        ConfigureJavaFormatterXml.configureExternalDependencies(node, "open-java-format", "2.57.0");

        assertThat(xmlToString(node)).isEqualTo("""
            <project version="4">
              <component name="ExternalDependencies">
                <plugin id="open-java-format" min-version="2.57.0"/>
              </component>
            </project>
            """);
    }

    @Test
    void keeps_the_other_required_plugins() {
        Node node = parseXml("""
            <project version="4">
              <component name="ExternalDependencies">
                <plugin id="CheckStyle-IDEA" min-version="5.0"/>
              </component>
            </project>
            """);

        ConfigureJavaFormatterXml.configureExternalDependencies(node, "open-java-format", "2.57.0");

        assertThat(xmlToString(node)).isEqualTo("""
            <project version="4">
              <component name="ExternalDependencies">
                <plugin id="CheckStyle-IDEA" min-version="5.0"/>
                <plugin id="open-java-format" min-version="2.57.0"/>
              </component>
            </project>
            """);
    }

    // An empty cell is a plugin listed without a min-version. 2.100.0 is higher than 2.57.0: versions compare
    // numerically, not as text.
    @CsvSource({",         2.57.0", "2.100.0,  2.100.0", "2.50.0,   2.57.0", "2.57.0,   2.57.0", "2.98.0.5, 2.98.0.5"})
    @ParameterizedTest
    void raises_a_lower_min_version_and_keeps_a_higher_one(String existing, String expected) {
        Node node = parseXml("""
            <project version="4">
              <component name="ExternalDependencies">
                <plugin id="open-java-format"%s/>
              </component>
            </project>
            """.formatted(existing == null ? "" : " min-version=\"" + existing + "\""));

        ConfigureJavaFormatterXml.configureExternalDependencies(node, "open-java-format", "2.57.0");

        assertThat(xmlToString(node))
                .contains("<plugin id=\"open-java-format\" min-version=\"" + expected + "\"/>")
                .containsOnlyOnce("<plugin ");
    }

    private static Node parseXml(@Language("xml") String xml) {
        try {
            return new XmlParser().parseText(xml);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse XML", e);
        }
    }

    private static String xmlSubcomponentToString(Node node, String name) {
        List<?> children = node.children();
        return children.stream()
                .map(Node.class::cast)
                .filter(child -> name.equals(child.attribute("name")))
                .findFirst()
                .map(ConfigureJavaFormatterXmlTest::xmlToString)
                .map(String::strip)
                .orElseThrow(() -> new IllegalArgumentException("Component not found: " + name));
    }

    private static String xmlToString(Node node) {
        StringWriter sw = new StringWriter();
        XmlNodePrinter nodePrinter = new XmlNodePrinter(new PrintWriter(sw));
        nodePrinter.setPreserveWhitespace(true);
        nodePrinter.print(node);
        return sw.toString();
    }
}
