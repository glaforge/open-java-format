/*
 * (c) Copyright 2021 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.javaformat.intellij;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.intellij.formatting.service.FormattingService;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.palantir.javaformat.bootstrap.BootstrappingFormatterService;
import com.palantir.javaformat.bootstrap.NativeImageFormatterService;
import com.palantir.javaformat.java.FormatterService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

final class FormatterProvider {
    private static final Logger log = Logger.getInstance(FormatterProvider.class);

    // Cache to avoid resolving the formatter every time we want to format from IntelliJ
    private final LoadingCache<FormatterCacheKey, Optional<FormatterService>> implementationCache =
            Caffeine.newBuilder().maximumSize(1).build(FormatterProvider::createFormatter);

    private final Supplier<Optional<Path>> embeddedNativeImage;

    /** embeddedNativeImage: the downloaded native binary, asked again on every format since a download can add it. */
    FormatterProvider(Supplier<Optional<Path>> embeddedNativeImage) {
        this.embeddedNativeImage = embeddedNativeImage;
    }

    Optional<FormatterService> get(Project project, PalantirJavaFormatSettings settings) {
        // The selection, not the settings, is in the key: the formatter has to change when the binary appears.
        return implementationCache.get(new FormatterCacheKey(
                project,
                settings.getImplementationClassPath(),
                settings.injectedVersionIsOutdated(),
                FormatterSelection.of(settings, embeddedNativeImage)));
    }

    private static Optional<FormatterService> createFormatter(FormatterCacheKey cacheKey) {
        if (cacheKey.selection() instanceof FormatterSelection.NativeImage nativeImage) {
            log.info("open-java-format: using native binary, " + (nativeImage.embedded() ? "embedded: " : "custom: ")
                    + nativeImage.path());
            return Optional.of(new NativeImageFormatterService(nativeImage.path()));
        }
        if (cacheKey.selection() instanceof FormatterSelection.JavaBased javaBased && javaBased.nativeImageMissing()) {
            log.warn("open-java-format: the native binary is switched on but not downloaded, formatting with the"
                    + " Java-based formatter");
        }

        // The formatter runs in a JVM of its own, started with the "--add-exports" it needs to reach javac.
        // That JVM is the IDE's own runtime, never the project SDK: every IDE this plugin supports runs on Java 21
        // or later, which the formatter needs, while a project SDK can be any version.
        int jdkMajorVersion = Runtime.version().feature();
        Path jdkPath = Path.of(System.getProperty("java.home"), "bin", SystemInfo.isWindows ? "java.exe" : "java");
        List<Path> implementationClasspath =
                getImplementationUrls(cacheKey.implementationClassPath(), cacheKey.useBundledImplementation());
        var classpath = cacheKey.useBundledImplementation()
                        || cacheKey.implementationClassPath().isEmpty()
                ? "bundled"
                : "build";
        log.info("open-java-format: using Java-based formatter on " + jdkPath + " (Java " + jdkMajorVersion + ") with "
                + classpath + " classpath");
        return Optional.of(new BootstrappingFormatterService(jdkPath, jdkMajorVersion, implementationClasspath));
    }

    private static List<Path> getProvidedImplementationUrls(List<URI> implementationClasspath) {
        return implementationClasspath.stream().map(Path::of).toList();
    }

    private static List<Path> getBundledImplementationUrls() {
        // Load from the jars bundled with the plugin.
        Path implDir = ofNullable(FormattingService.EP_NAME.findExtension(PalantirJavaFormatFormattingService.class))
                .flatMap(PalantirJavaFormatFormattingService::getPluginDescriptor)
                .map(PluginDescriptor::getPluginPath)
                .orElseThrow(() -> new NoSuchElementException("The platform has not set the plugin descriptor"))
                .resolve("impl");

        log.debug("Using open-java-format implementation bundled with plugin: " + implDir);

        return listDirAsUrlsUnchecked(implDir);
    }

    private static List<Path> getImplementationUrls(
            Optional<List<URI>> implementationClassPath, boolean useBundledImplementation) {
        if (useBundledImplementation) {
            log.debug("Using open-java-format implementation bundled with plugin");
            return getBundledImplementationUrls();
        }
        return implementationClassPath
                .map(classpath -> {
                    log.debug("Using open-java-format implementation defined by URIs: " + classpath);
                    return getProvidedImplementationUrls(classpath);
                })
                .orElseGet(() -> {
                    log.debug("Using open-java-format implementation bundled with plugin");
                    return getBundledImplementationUrls();
                });
    }

    private static List<Path> listDirAsUrlsUnchecked(Path dir) {
        try (Stream<Path> list = Files.list(dir)) {
            return list.toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't list dir: " + dir, e);
        }
    }

    private record FormatterCacheKey(
            Project project,
            Optional<List<URI>> implementationClassPath,
            boolean useBundledImplementation,
            FormatterSelection selection) {}
}
