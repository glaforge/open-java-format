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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BundledFormatterTest {

    /**
     * ./gradlew writeImplClasspath generates this file: the formatter's runtime classpath, as bundleFormatter takes it.
     */
    private static final Path CLASSPATH_FILE = Path.of("build/impl.classpath");

    private static final String BUNDLE = "open-java-format/formatter/";

    @TempDir
    private Path gradleUserHome;

    @Test
    void the_plugin_carries_the_jars_of_the_formatter_runtime_classpath() throws IOException {
        var classpath = Arrays.stream(Files.readString(CLASSPATH_FILE).trim().split(File.pathSeparator))
                .map(Path::of)
                .toList();

        var expected = classpath.stream()
                .map(jar -> sha256(read(jar)) + " " + jar.getFileName())
                .toList();

        assertThat(bundledList()).containsExactlyElementsOf(expected);
    }

    @Test
    void every_jar_in_the_list_is_in_the_plugin_as_listed() {
        for (var line : bundledList()) {
            var hash = line.substring(0, line.indexOf(' '));
            var name = line.substring(line.indexOf(' ') + 1);

            assertThat(sha256(resource(BUNDLE + name))).as(name).isEqualTo(hash);
        }
    }

    @Test
    void the_jars_are_extracted_into_a_directory_named_by_the_hash_once() throws IOException {
        var jars = BundledFormatter.jars(gradleUserHome.toFile());

        var dir = gradleUserHome.resolve("open-java-format-caches").resolve(BundledFormatter.hash());
        assertThat(BundledFormatter.hash()).matches("[0-9a-f]{16}");
        assertThat(jars).extracting(File::getName).containsExactlyElementsOf(bundledNames());
        assertThat(jars).allSatisfy(jar -> assertThat(jar.toPath().getParent()).isEqualTo(dir));
        for (var line : bundledList()) {
            var name = line.substring(line.indexOf(' ') + 1);
            assertThat(sha256(read(dir.resolve(name)))).as(name).isEqualTo(line.substring(0, line.indexOf(' ')));
        }

        // A second build finds them there and leaves them as they are: a daemon may have them open.
        var written = lastModified(jars);
        assertThat(BundledFormatter.jars(gradleUserHome.toFile())).isEqualTo(jars);
        assertThat(lastModified(jars)).isEqualTo(written);
    }

    // Repeated, so that the builds do race to rename their copies into place.
    @RepeatedTest(5)
    void builds_that_extract_at_the_same_time_get_the_same_jars() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        try {
            var start = new CountDownLatch(1);
            var builds = Stream.generate(() -> CompletableFuture.supplyAsync(
                            () -> {
                                await(start);
                                return BundledFormatter.jars(gradleUserHome.toFile());
                            },
                            executor))
                    .limit(4)
                    .toList();
            start.countDown();

            var results = builds.stream().map(CompletableFuture::join).toList();

            assertThat(results).allSatisfy(jars -> assertThat(jars).isEqualTo(results.get(0)));
            assertThat(results.get(0)).allSatisfy(jar -> assertThat(jar).isFile());
            // The copies of the builds that lost are gone.
            assertThat(list(gradleUserHome.resolve("open-java-format-caches")))
                    .containsExactly(BundledFormatter.hash());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void a_deleted_directory_is_extracted_again() throws IOException {
        var jars = BundledFormatter.jars(gradleUserHome.toFile());
        delete(gradleUserHome.resolve("open-java-format-caches"));

        assertThat(BundledFormatter.jars(gradleUserHome.toFile())).isEqualTo(jars);
        assertThat(jars).allSatisfy(jar -> assertThat(jar).isFile());
    }

    @Test
    void a_directory_that_lost_a_jar_is_extracted_again() throws IOException {
        var jars = BundledFormatter.jars(gradleUserHome.toFile());
        Files.delete(jars.get(0).toPath());

        assertThat(BundledFormatter.jars(gradleUserHome.toFile())).isEqualTo(jars);
        assertThat(jars).allSatisfy(jar -> assertThat(jar).isFile());
    }

    @Test
    void a_jar_that_does_not_match_the_list_fails_and_leaves_nothing_behind(@TempDir Path plugin) throws IOException {
        var bundle = Files.createDirectories(plugin.resolve(BUNDLE));
        Files.writeString(bundle.resolve("formatter.jar"), "not what the list says");
        Files.writeString(
                bundle.resolve("jars.txt"),
                sha256("what the list says".getBytes(StandardCharsets.UTF_8)) + " formatter.jar\n");

        try (var loader = new URLClassLoader(new URL[] {plugin.toUri().toURL()}, null)) {
            assertThatThrownBy(() -> BundledFormatter.jars(gradleUserHome, loader))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("formatter.jar")
                    .hasMessageContaining("damaged");
        }
        assertThat(list(gradleUserHome.resolve("open-java-format-caches"))).isEmpty();
    }

    private static List<String> bundledList() {
        return new String(resource(BUNDLE + "jars.txt"), StandardCharsets.UTF_8)
                .lines()
                .toList();
    }

    private static List<String> bundledNames() {
        return bundledList().stream()
                .map(line -> line.substring(line.indexOf(' ') + 1))
                .toList();
    }

    private static List<FileTime> lastModified(List<File> jars) throws IOException {
        var times = new ArrayList<FileTime>();
        for (var jar : jars) {
            times.add(Files.getLastModifiedTime(jar.toPath()));
        }
        return times;
    }

    private static List<String> list(Path dir) throws IOException {
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.map(path -> path.getFileName().toString()).toList();
        }
    }

    private static void delete(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static byte[] resource(String name) {
        try (InputStream in = BundledFormatterTest.class.getClassLoader().getResourceAsStream(name)) {
            return Objects.requireNonNull(in, name + " is not on the classpath").readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
