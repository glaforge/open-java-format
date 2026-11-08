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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

final class NativeImageAtomicCopyTest {

    @TempDir
    private Path gradleUserHome;

    @TempDir
    private Path repository;

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Windows has no executable bit to set")
    void the_copy_is_executable_and_the_image_is_left_as_it_was() throws IOException {
        var image = image("open-java-format-native-1.0-linux-x86_64.bin", "an image");

        var copy = NativeImageAtomicCopy.copyToCache(image, gradleUserHome.toFile());

        assertThat(copy.getParent().getParent()).isEqualTo(gradleUserHome.resolve("open-java-format-caches"));
        assertThat(copy.getParent().getFileName().toString()).matches("[0-9a-f]{16}");
        assertThat(copy.getFileName()).isEqualTo(image.getFileName());
        assertThat(copy).hasContent("an image");
        assertThat(Files.isExecutable(copy)).isTrue();
        assertThat(Files.isExecutable(image)).isFalse();
    }

    @Test
    void the_image_is_copied_once() throws IOException {
        var image = image("open-java-format-native-1.0-linux-x86_64.bin", "an image");
        var copy = NativeImageAtomicCopy.copyToCache(image, gradleUserHome.toFile());
        var written = Files.getLastModifiedTime(copy);

        // A daemon may be running it: a second build leaves it as it is.
        assertThat(NativeImageAtomicCopy.copyToCache(image, gradleUserHome.toFile()))
                .isEqualTo(copy);
        assertThat(Files.getLastModifiedTime(copy)).isEqualTo(written);
    }

    @Test
    void another_image_is_another_directory() throws IOException {
        var copy = NativeImageAtomicCopy.copyToCache(
                image("open-java-format-native-1.0-linux-x86_64.bin", "an image"), gradleUserHome.toFile());
        var other = NativeImageAtomicCopy.copyToCache(
                image("open-java-format-native-2.0-linux-x86_64.bin", "another image"), gradleUserHome.toFile());

        assertThat(other.getParent()).isNotEqualTo(copy.getParent());
        assertThat(other).hasContent("another image");
    }

    @Test
    void a_deleted_copy_is_copied_again() throws IOException {
        var image = image("open-java-format-native-1.0-linux-x86_64.bin", "an image");
        var copy = NativeImageAtomicCopy.copyToCache(image, gradleUserHome.toFile());
        Files.delete(copy);

        assertThat(NativeImageAtomicCopy.copyToCache(image, gradleUserHome.toFile()))
                .isEqualTo(copy);
        assertThat(copy).hasContent("an image");
    }

    // Repeated, so that the builds do race to rename their copies into place.
    @RepeatedTest(5)
    void builds_that_copy_at_the_same_time_get_the_same_copy() throws Exception {
        var image = image("open-java-format-native-1.0-linux-x86_64.bin", "an image");
        var executor = Executors.newFixedThreadPool(4);
        try {
            var start = new CountDownLatch(1);
            var builds = Stream.generate(() -> CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    start.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(e);
                                }
                                return NativeImageAtomicCopy.copyToCache(image, gradleUserHome.toFile());
                            },
                            executor))
                    .limit(4)
                    .toList();
            start.countDown();

            var copies = builds.stream().map(CompletableFuture::join).toList();

            assertThat(copies)
                    .allSatisfy(
                            copy -> assertThat(copy).isEqualTo(copies.get(0)).hasContent("an image"));
            // The copies of the builds that lost are gone.
            try (Stream<Path> dirs = Files.list(gradleUserHome.resolve("open-java-format-caches"))) {
                assertThat(dirs.toList()).containsExactly(copies.get(0).getParent());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /** As Gradle hands it over: a file in a repository or in its cache, not executable where files can be. */
    private Path image(String name, String content) throws IOException {
        var image = Files.writeString(repository.resolve(name), content);
        if (image.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(image, PosixFilePermissions.fromString("rw-r--r--"));
        }
        return image;
    }
}
