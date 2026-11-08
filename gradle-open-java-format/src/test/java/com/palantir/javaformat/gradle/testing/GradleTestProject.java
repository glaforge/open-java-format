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

package com.palantir.javaformat.gradle.testing;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;

/**
 * A generated Gradle project driven through plain Gradle TestKit.
 *
 * <p>Replaces {@code com.palantir.gradle-plugin-testing}, which could not run under Gradle 9: it is built against
 * gradle-consistent-versions and dragged that plugin onto the build's classpath purely to be instantiable.
 *
 * <p>The plugins block is kept apart from the rest of the script because it has to be rendered first, while tests add
 * to both in whatever order reads best; nothing is written to disk until a build is actually run.
 */
public final class GradleTestProject {

    /**
     * The Gradle the generated projects build with: the oldest Gradle 9 we claim to support, the interesting end of
     * the range to test against. There is no version matrix any more: Gradle 8 was dropped when the build itself moved
     * to 9. On a JDK that old a Gradle cannot run on, the build names the oldest one that can in
     * {@code testkit.gradleVersion}; CI's jdk legs take it from Gradle's compatibility table.
     */
    private static final String GRADLE_VERSION = System.getProperty("testkit.gradleVersion", "9.3.1");

    /** Written by java-gradle-plugin: the classpath {@link GradleRunner#withPluginClasspath()} injects. */
    private static final String PLUGIN_METADATA = "plugin-under-test-metadata.properties";

    private final Path projectDir;
    private final Set<String> plugins = new LinkedHashSet<>();
    private final Set<String> pluginsNotApplied = new LinkedHashSet<>();
    private final Set<String> subprojects = new LinkedHashSet<>();
    private final StringBuilder buildGradle = new StringBuilder();
    private final StringBuilder gradleProperties = new StringBuilder();
    private boolean configurationCache = true;
    private boolean spotlessOnClasspath = true;
    private boolean repositories = true;
    private Optional<Path> buildCacheDir = Optional.empty();

    public GradleTestProject(Path projectDir) {
        this.projectDir = projectDir;
    }

    public Path path() {
        return projectDir;
    }

    public GradleTestProject plugins(String... ids) {
        plugins.addAll(Arrays.asList(ids));
        return this;
    }

    /**
     * Declares these plugins in the plugins block with {@code apply false}, as a multi-project build does: on the build's
     * classpath, for the subprojects to apply.
     */
    public GradleTestProject pluginsNotApplied(String... ids) {
        pluginsNotApplied.addAll(Arrays.asList(ids));
        return this;
    }

    /** Includes these subprojects, in directories of their names. A test writes their files with {@link #writeFile}. */
    public GradleTestProject subprojects(String... names) {
        subprojects.addAll(Arrays.asList(names));
        return this;
    }

    /** Appends to build.gradle, below the plugins block. {@code %s} placeholders are substituted as in printf. */
    public GradleTestProject buildGradle(String format, Object... args) {
        buildGradle.append(args.length == 0 ? format : String.format(format, args)).append('\n');
        return this;
    }

    public GradleTestProject gradleProperties(String line) {
        if (!line.isBlank()) {
            gradleProperties.append(line).append('\n');
        }
        return this;
    }

    /**
     * The exports the formatter needs to reach javac internals. Every generated build that actually runs the formatter
     * needs these, exactly as the real one does.
     */
    public GradleTestProject withJavacInternalExports() {
        return gradleProperties("org.gradle.jvmargs=--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED "
                + "--add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED "
                + "--add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED "
                + "--add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED "
                + "--add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED");
    }

    /**
     * Whether the builds run with the configuration cache, on unless a test turns it off. The TestKit test classes run
     * both ways: a plugin has to work with the cache and without it, and the two run its tasks differently.
     */
    public GradleTestProject configurationCache(boolean enabled) {
        this.configurationCache = enabled;
        return this;
    }

    /**
     * Runs the builds with the build cache, kept in {@code directory}. Not the default local cache: TestKit builds share
     * one Gradle user home, and with it that cache, so a test would read what another test stored.
     */
    public GradleTestProject withBuildCache(Path directory) {
        this.buildCacheDir = Optional.of(directory);
        return this;
    }

    /** Leaves out the root project's {@code repositories { mavenCentral() } }: the build declares no repository at all. */
    public GradleTestProject withoutRepositories() {
        this.repositories = false;
        return this;
    }

    /**
     * Leaves the Spotless jars out of the classpath injected next to the plugin under test. build.gradle puts them there
     * so that generated projects can apply Spotless, which gives every other test Spotless classes whether it applies
     * Spotless or not — unlike a real project that never does.
     */
    public GradleTestProject withoutSpotlessOnClasspath() {
        this.spotlessOnClasspath = false;
        return this;
    }

    public Path file(String relativePath) {
        return projectDir.resolve(relativePath);
    }

    public GradleTestProject writeFile(String relativePath, String content) {
        Path target = file(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + relativePath, e);
        }
        return this;
    }

    /** Deletes a file, or a directory with everything in it, if it is there. */
    public GradleTestProject delete(String relativePath) {
        Path target = file(relativePath);
        if (!Files.exists(target)) {
            return this;
        }
        try (Stream<Path> paths = Files.walk(target)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + relativePath, e);
        }
        return this;
    }

    public String readFile(String relativePath) {
        try {
            return Files.readString(file(relativePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + relativePath, e);
        }
    }

    public BuildResult succeeds(String... arguments) {
        return runner(arguments).build();
    }

    public BuildResult fails(String... arguments) {
        return runner(arguments).buildAndFail();
    }

    private GradleRunner runner(String... arguments) {
        write();
        List<String> allArguments = new ArrayList<>(Arrays.asList(arguments));
        allArguments.add("--stacktrace");
        allArguments.add(configurationCache ? "--configuration-cache" : "--no-configuration-cache");
        // Every build looks at the files afresh. A test changes files between two builds faster than file system
        // watching reports it, and the second build then took a deleted output for one still there.
        allArguments.add("--no-watch-fs");
        if (buildCacheDir.isPresent()) {
            allArguments.add("--build-cache");
        }
        GradleRunner runner = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withGradleVersion(GRADLE_VERSION)
                .withArguments(allArguments)
                .forwardOutput();
        return spotlessOnClasspath
                ? runner.withPluginClasspath()
                : runner.withPluginClasspath(pluginClasspathWithoutSpotless());
    }

    private static List<File> pluginClasspathWithoutSpotless() {
        Properties metadata = new Properties();
        try (InputStream in = GradleTestProject.class.getClassLoader().getResourceAsStream(PLUGIN_METADATA)) {
            metadata.load(Objects.requireNonNull(in, PLUGIN_METADATA + " is not on the test classpath"));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + PLUGIN_METADATA, e);
        }
        return Arrays.stream(metadata.getProperty("implementation-classpath").split(File.pathSeparator))
                .map(File::new)
                .filter(file -> !file.getName().startsWith("spotless-"))
                .toList();
    }

    private void write() {
        StringBuilder script = new StringBuilder("plugins {\n");
        plugins.forEach(id -> script.append("    id '").append(id).append("'\n"));
        pluginsNotApplied.forEach(id -> script.append("    id '").append(id).append("' apply false\n"));
        script.append("}\n\n");
        if (repositories) {
            script.append("repositories {\n    mavenCentral()\n}\n\n");
        }
        script.append(buildGradle);

        StringBuilder settings = new StringBuilder("rootProject.name = 'test-project'\n");
        for (var name : subprojects) {
            settings.append("include '").append(name).append("'\n");
            // Gradle refuses a project whose directory is missing.
            try {
                Files.createDirectories(file(name));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not create " + name, e);
            }
        }
        // Forward slashes: the path goes into a Groovy string, where a Windows backslash would start an escape.
        buildCacheDir.ifPresent(dir -> settings.append("\nbuildCache {\n    local {\n        directory = file('")
                .append(dir.toAbsolutePath().toString().replace('\\', '/'))
                .append("')\n    }\n}\n"));
        writeFile("settings.gradle", settings.toString());
        writeFile("build.gradle", script.toString());
        writeFile("gradle.properties", gradleProperties.toString());
    }
}
