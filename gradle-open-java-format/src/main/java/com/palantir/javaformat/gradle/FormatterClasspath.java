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

import java.util.Map;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.attributes.java.TargetJvmEnvironment;
import org.gradle.api.model.ObjectFactory;

/**
 * The formatter's jars for the tasks of one project, resolved in that project.
 *
 * <p>The formatter is declared once, in the root project's palantirJavaFormat. A task of a subproject cannot resolve
 * that configuration itself: in a parallel build it runs without the root project's lock, and Gradle refuses. So the
 * root project offers palantirJavaFormat as a variant of its own, and every project that formats resolves it through a
 * dependency on the root project, which Gradle resolves in the project that asks.
 */
final class FormatterClasspath {

    /** Picks the root project's variant out of its others, such as runtimeElements when it has java too. */
    private static final String CAPABILITY = "dev.openjavaformat.internal:formatter-classpath";

    private static final String ELEMENTS = "openJavaFormatFormatterElements";
    private static final String DEPENDENCIES = "openJavaFormatFormatter";
    private static final String CLASSPATH = "openJavaFormatFormatterClasspath";

    private FormatterClasspath() {}

    /** Offers the root project's palantirJavaFormat to the projects that format. */
    static void offer(Project rootProject, Configuration palantirJavaFormat) {
        rootProject.getConfigurations().consumable(ELEMENTS, conf -> {
            conf.setDescription("Internal: the open-java-format implementation, for the projects that format");
            conf.extendsFrom(palantirJavaFormat);
            javaRuntime(conf.getAttributes(), rootProject.getObjects());
            conf.getOutgoing().capability(CAPABILITY + ":1");
        });
    }

    /** The formatter's jars for the tasks of this project. */
    static NamedDomainObjectProvider<Configuration> of(Project project) {
        var configurations = project.getConfigurations();
        if (!configurations.getNames().contains(CLASSPATH)) {
            var rootProject = (ModuleDependency) project.getDependencies().project(Map.of("path", ":"));
            rootProject.capabilities(capabilities -> capabilities.requireCapability(CAPABILITY));
            var dependencies = configurations.dependencyScope(DEPENDENCIES, conf -> {
                conf.setDescription("Internal: the root project's open-java-format implementation");
                conf.getDependencies().add(rootProject);
            });
            configurations.resolvable(CLASSPATH, conf -> {
                conf.setDescription("Internal: resolves the open-java-format implementation for this project");
                conf.extendsFrom(dependencies.get());
                javaRuntime(conf.getAttributes(), project.getObjects());
            });
        }
        return configurations.named(CLASSPATH);
    }

    /**
     * What a Java library's runtime jars are asked for with. Without them Gradle cannot pick between the variants of a
     * library that has one for the JRE and one for Android, as Guava does, which the formatter depends on.
     */
    static void javaRuntime(AttributeContainer attributes, ObjectFactory objects) {
        attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.JAVA_RUNTIME));
        attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
        attributes.attribute(
                LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, LibraryElements.JAR));
        attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.class, Bundling.EXTERNAL));
        attributes.attribute(
                TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                objects.named(TargetJvmEnvironment.class, TargetJvmEnvironment.STANDARD_JVM));
    }
}
