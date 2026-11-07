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

package com.palantir.javaformat.intellij;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A platform open-java-format-native publishes an image for, named as the image is: the classifier
 * {@code nativeImage-<os>_<arch>} on Maven Central. The Gradle plugin's NativePlatform names them the same way. This
 * one knows only the published platforms, since an IDE anywhere else has no image to download.
 */
record NativePlatform(String os, String arch) {

    private static final Set<NativePlatform> PUBLISHED = Set.of(
            new NativePlatform("linux-glibc", "x86-64"),
            new NativePlatform("linux-glibc", "aarch64"),
            new NativePlatform("macos", "x86-64"),
            new NativePlatform("macos", "aarch64"),
            new NativePlatform("windows", "x86-64"));

    /** The platform of the IDE, if an image is published for it. */
    static Optional<NativePlatform> current() {
        return of(System.getProperty("os.name"), System.getProperty("os.arch"), Path.of("/lib"));
    }

    /**
     * The platform for the given os.name and os.arch. On Linux, libDir tells glibc from musl: musl keeps its dynamic
     * loader there as ld-musl-&lt;arch&gt;.so.1, and a glibc binary does not run on it. Nothing is started to find out.
     */
    static Optional<NativePlatform> of(String osName, String osArch, Path libDir) {
        var platform = new NativePlatform(operatingSystem(osName, libDir), architecture(osArch));
        return PUBLISHED.contains(platform) ? Optional.of(platform) : Optional.empty();
    }

    /** {@code <os>_<arch>}, the part of the classifier after {@code nativeImage-}. */
    String name() {
        return os + "_" + arch;
    }

    /** native-image names the Windows binary .exe, and Maven Central has it under that extension. */
    String extension() {
        return isWindows() ? "exe" : "bin";
    }

    /** The name the GitHub release gives the binary, and the one it is kept under once downloaded. */
    String fileName() {
        return "open-java-format-" + name() + (isWindows() ? ".exe" : "");
    }

    private boolean isWindows() {
        return os.equals("windows");
    }

    private static String operatingSystem(String osName, Path libDir) {
        var name = osName.toLowerCase(Locale.ROOT);
        if (name.startsWith("mac")) {
            return "macos";
        }
        if (name.startsWith("windows")) {
            return "windows";
        }
        if (name.startsWith("linux")) {
            return isMusl(libDir) ? "linux-musl" : "linux-glibc";
        }
        return name;
    }

    private static boolean isMusl(Path libDir) {
        try (var loaders = Files.newDirectoryStream(libDir, "ld-musl-*.so.1")) {
            return loaders.iterator().hasNext();
        } catch (IOException e) {
            // No such directory: not musl, which always has one.
            return false;
        }
    }

    private static String architecture(String osArch) {
        return switch (osArch.toLowerCase(Locale.ROOT)) {
            case "x86_64", "x64", "amd64" -> "x86-64";
            case "arm", "arm64", "aarch64" -> "aarch64";
            default -> osArch;
        };
    }
}
