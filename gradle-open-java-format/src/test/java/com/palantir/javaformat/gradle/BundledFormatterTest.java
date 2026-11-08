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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

final class BundledFormatterTest {

    /** ./gradlew writeImplClasspath generates this file: the formatter's runtime classpath, as bundleFormatter takes it. */
    private static final Path CLASSPATH_FILE = Path.of("build/impl.classpath");

    private static final String BUNDLE = "open-java-format/formatter/";

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

    private static List<String> bundledList() {
        return new String(resource(BUNDLE + "jars.txt"), StandardCharsets.UTF_8)
                .lines()
                .toList();
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
