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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class NativePlatformTest {

    @TempDir
    private Path libDir;

    // The classifiers open-java-format-native has on Maven Central, nativeImage-<name>, and the file names of the
    // binaries on the GitHub release. The Gradle plugin's NativePlatformTest pins the same names.
    @CsvSource(
            delimiter = '|',
            value = {
                "Linux      | aarch64 | linux-glibc_aarch64 | bin | open-java-format-linux-glibc_aarch64",
                "Linux      | amd64   | linux-glibc_x86-64  | bin | open-java-format-linux-glibc_x86-64",
                "Mac OS X   | aarch64 | macos_aarch64       | bin | open-java-format-macos_aarch64",
                "Mac OS X   | x86_64  | macos_x86-64        | bin | open-java-format-macos_x86-64",
                "Windows 11 | amd64   | windows_x86-64      | exe | open-java-format-windows_x86-64.exe"
            })
    @ParameterizedTest
    void namesEachPublishedImage(String osName, String osArch, String name, String extension, String fileName) {
        var platform = NativePlatform.of(osName, osArch, libDir).orElseThrow();

        assertThat(platform.name()).isEqualTo(name);
        assertThat(platform.extension()).isEqualTo(extension);
        assertThat(platform.fileName()).isEqualTo(fileName);
    }

    @CsvSource(
            delimiter = '|',
            value = {"Linux | i686", "Linux | riscv64", "Windows 11 | aarch64", "FreeBSD | amd64"})
    @ParameterizedTest
    void hasNothingForPlatformsWithoutAnImage(String osName, String osArch) {
        assertThat(NativePlatform.of(osName, osArch, libDir)).isEmpty();
    }

    @Test
    void readsEverySpellingOfTheArchitecture() {
        assertThat(NativePlatform.of("Linux", "x86_64", libDir)).isEqualTo(NativePlatform.of("Linux", "amd64", libDir));
        assertThat(NativePlatform.of("Linux", "arm64", libDir))
                .isEqualTo(NativePlatform.of("Linux", "aarch64", libDir));
    }

    @Test
    void hasNothingForMusl() throws IOException {
        // A glibc binary does not run on musl, whose dynamic loader is the one named ld-musl-<arch>.so.1.
        Files.createFile(libDir.resolve("ld-musl-x86_64.so.1"));

        assertThat(NativePlatform.of("Linux", "amd64", libDir)).isEmpty();
    }

    @Test
    void takesALinuxWithoutALibDirectoryForGlibc() {
        assertThat(NativePlatform.of("Linux", "amd64", libDir.resolve("missing")))
                .map(NativePlatform::name)
                .hasValue("linux-glibc_x86-64");
    }
}
