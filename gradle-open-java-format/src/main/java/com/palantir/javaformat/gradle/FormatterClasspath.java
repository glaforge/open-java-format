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
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.attributes.java.TargetJvmEnvironment;
import org.gradle.api.model.ObjectFactory;

/**
 * What a build declares in the root project for the formatter, resolved for the tasks of one project, in that project
 * and through its repositories: palantirJavaFormat, which overrides the formatter inside the plugin, and
 * palantirJavaFormatNative, the native image. Most builds declare nothing in palantirJavaFormat, and it resolves to
 * nothing, which needs no repository.
 *
 * <p>Both are declared once, in the root project. A task of a subproject cannot resolve them there: in a parallel build
 * it runs without the root project's lock, and Gradle refuses; and the root project may have no repository at all. So
 * the root project offers each as a variant of its own, and every project that formats resolves it through a
 * dependency on the root project, which Gradle resolves in the project that asks.
 */
final class FormatterClasspath {

    /** The formatter's jars. The capability picks the root project's variant out of others, such as runtimeElements. */
    private static final Kind JARS = new Kind(
            "dev.openjavaformat.internal:formatter-classpath",
            "openJavaFormatFormatterElements",
            "openJavaFormatFormatter",
            "openJavaFormatFormatterClasspath",
            "the open-java-format implementation",
            FormatterClasspath::javaRuntime);

    /** The native image, declared in the root project's palantirJavaFormatNative. */
    private static final Kind NATIVE_IMAGE = new Kind(
            "dev.openjavaformat.internal:native-image",
            "openJavaFormatNativeElements",
            "openJavaFormatNative",
            "openJavaFormatNativeImage",
            "the open-java-format native image",
            FormatterClasspath::nativeImage);

    private static final Attribute<String> NATIVE_IMAGE_ATTRIBUTE =
            Attribute.of("dev.openjavaformat.native-image", String.class);

    private FormatterClasspath() {}

    /** Offers the root project's palantirJavaFormat to the projects that format. */
    static void offer(Project rootProject, Configuration palantirJavaFormat) {
        offer(rootProject, JARS, () -> palantirJavaFormat);
    }

    /** The formatter's jars for the tasks of this project. */
    static NamedDomainObjectProvider<Configuration> of(Project project) {
        return of(project, JARS);
    }

    /** Offers the root project's palantirJavaFormatNative to the projects that format. */
    static void offerNativeImage(
            Project rootProject, NamedDomainObjectProvider<Configuration> palantirJavaFormatNative) {
        offer(rootProject, NATIVE_IMAGE, palantirJavaFormatNative::get);
    }

    /**
     * The native image for the tasks of this project, resolved through this project's repositories: a build may
     * declare them only where it has Java code, as it may for a mirror of Maven Central (#20).
     */
    static NamedDomainObjectProvider<Configuration> nativeImageOf(Project project) {
        return of(project, NATIVE_IMAGE);
    }

    private static void offer(Project rootProject, Kind kind, Supplier<Configuration> declared) {
        rootProject.getConfigurations().consumable(kind.elements(), conf -> {
            conf.setDescription("Internal: " + kind.what() + ", for the projects that format");
            conf.extendsFrom(declared.get());
            kind.attributes().accept(conf.getAttributes(), rootProject.getObjects());
            conf.getOutgoing().capability(kind.capability() + ":1");
        });
    }

    private static NamedDomainObjectProvider<Configuration> of(Project project, Kind kind) {
        var configurations = project.getConfigurations();
        if (!configurations.getNames().contains(kind.classpath())) {
            var rootProject = (ModuleDependency) project.getDependencies().project(Map.of("path", ":"));
            rootProject.capabilities(capabilities -> capabilities.requireCapability(kind.capability()));
            var dependencies = configurations.dependencyScope(kind.dependencies(), conf -> {
                conf.setDescription(
                        "Internal: the root project's " + kind.what().substring("the ".length()));
                conf.getDependencies().add(rootProject);
            });
            configurations.resolvable(kind.classpath(), conf -> {
                conf.setDescription("Internal: resolves " + kind.what() + " for this project");
                conf.extendsFrom(dependencies.get());
                kind.attributes().accept(conf.getAttributes(), project.getObjects());
            });
        }
        return configurations.named(kind.classpath());
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

    /**
     * The image is declared as an artifact, by classifier and extension, which Gradle resolves as it is; the attribute
     * only names the variant of the root project.
     */
    private static void nativeImage(AttributeContainer attributes, ObjectFactory _objects) {
        attributes.attribute(NATIVE_IMAGE_ATTRIBUTE, "executable");
    }

    /** One thing the root project declares and offers, and every project that formats resolves. */
    private record Kind(
            String capability,
            String elements,
            String dependencies,
            String classpath,
            String what,
            BiConsumer<AttributeContainer, ObjectFactory> attributes) {}
}
