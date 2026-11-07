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

import com.google.common.collect.Iterables;
import com.palantir.javaformat.java.FormatterService;
import java.io.File;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import org.gradle.api.GradleException;

/**
 * Loads the Java-based formatter from its jars, in a class loader of its own, so that the formatter's dependencies do
 * not meet the build's.
 *
 * <p>The formatter stays loaded for as long as its jars stay the same, across the tasks and builds of one daemon. The
 * tasks have nowhere else to keep it: a build that reuses its configuration cache entry creates no project and no
 * {@link JavaFormatExtension}, and the Spotless step comes back from the cache with nothing that does not serialize.
 */
public final class FormatterServiceLoader {
    /**
     * The javac packages the formatter reads. A plain Gradle JVM exports none of them, and the formatter then fails
     * on its first file with an IllegalAccessError that names a class and a module and nothing the user can change.
     * The check below fails before the first file instead, with the two settings that make it work.
     */
    private static final List<String> JAVAC_PACKAGES = List.of(
            "com.sun.tools.javac.api",
            "com.sun.tools.javac.file",
            "com.sun.tools.javac.parser",
            "com.sun.tools.javac.tree",
            "com.sun.tools.javac.util");

    private static final String DOCS = "https://openjavaformat.dev/get-started/gradle/#choose-how-the-formatter-runs";

    /**
     * The formatter loaded last, by the jars it came from. One at a time: a build has one formatter classpath, and a
     * daemon that outlives a change of formatter version should not keep the old one.
     */
    private static final Map<List<Jar>, FormatterService> LOADED = new HashMap<>();

    private FormatterServiceLoader() {}

    /** The formatter in these jars: the one loaded last if they have not changed since, a new one otherwise. */
    public static synchronized FormatterService load(Collection<File> jars) {
        var key = jars.stream().map(Jar::of).toList();
        if (!LOADED.containsKey(key)) {
            LOADED.clear();
        }
        return LOADED.computeIfAbsent(key, _key -> loadUncached(jars));
    }

    @SuppressWarnings("for-rollout:NullAway")
    private static FormatterService loadUncached(Collection<File> jars) {
        URL[] jarUris = jars.stream()
                .map(file -> {
                    try {
                        return file.toURI().toURL();
                    } catch (MalformedURLException e) {
                        throw new UncheckedIOException("Unable to convert URI to URL: " + file, e);
                    }
                })
                .toArray(URL[]::new);

        ClassLoader classLoader = new URLClassLoader(jarUris, FormatterService.class.getClassLoader());
        checkJavacIsExportedTo(classLoader);
        return Iterables.getOnlyElement(ServiceLoader.load(FormatterService.class, classLoader));
    }

    /**
     * Fails with the fix spelt out when this JVM does not export javac's internals to the formatter's class loader:
     * either the native binary, which runs outside the JVM, or the {@code --add-exports} flags on the Gradle JVM.
     */
    private static void checkJavacIsExportedTo(ClassLoader formatterClassLoader) {
        Module jdkCompiler = ModuleLayer.boot()
                .findModule("jdk.compiler")
                .orElseThrow(() -> new GradleException("open-java-format needs the module jdk.compiler, which this"
                        + " Gradle JVM does not have: run Gradle on a JDK, not a JRE. See " + DOCS));
        Module formatter = formatterClassLoader.getUnnamedModule();
        List<String> notExported = JAVAC_PACKAGES.stream()
                .filter(javacPackage -> !jdkCompiler.isExported(javacPackage, formatter))
                .toList();
        if (notExported.isEmpty()) {
            return;
        }
        String addExports = JAVAC_PACKAGES.stream()
                .map(javacPackage -> "--add-exports jdk.compiler/" + javacPackage + "=ALL-UNNAMED")
                .collect(Collectors.joining(" "));
        throw new GradleException("""
            open-java-format cannot run inside this Gradle JVM: module jdk.compiler does not export %s to it. \
            Set one of these in gradle.properties:

              openjavaformat.native.formatter=true
                  runs the formatter as a native binary, outside the Gradle JVM (Linux with glibc, macOS, \
            Windows on x86-64)

              org.gradle.jvmargs=%s
                  opens javac's packages to the Gradle JVM; if the file already sets org.gradle.jvmargs, add \
            the flags to that line

            See %s\
            """.formatted(String.join(", ", notExported), addExports, DOCS));
    }

    /** A jar as it was when the formatter was loaded from it: rebuilt in place, it changes size or time. */
    private record Jar(File file, long length, long lastModified) {
        static Jar of(File file) {
            return new Jar(file, file.length(), file.lastModified());
        }
    }
}
