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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * The Java-based formatter that comes inside the plugin: the jars of its runtime classpath, which the build puts under
 * {@code open-java-format/formatter/} with a list of them, {@code jars.txt}.
 *
 * <p>A class loader cannot load a jar inside a jar, and the IDE plugin needs files it can find after a clean, so the
 * jars are extracted on first use into {@code <Gradle user home>/open-java-format-caches/<hash>/}. The hash is of the
 * list, which holds every jar's sha256: another formatter is another directory, and a directory, once there, never
 * changes. That is also why a daemon holding the jars open, as Windows does not let a file be replaced, gets in nobody's
 * way.
 *
 * <p>Builds and daemons may extract at the same time. Each extracts into a directory of its own next to the target and
 * renames it into place, which fails when the target is already there; the build that loses deletes its copy and uses
 * the one that won.
 */
public final class BundledFormatter {

    private static final String BUNDLE = "open-java-format/formatter/";
    private static final String LIST = BUNDLE + "jars.txt";
    static final String CACHES = "open-java-format-caches";

    private BundledFormatter() {}

    /** Names the directory the jars go in, and changes with every jar: the first 16 hex digits of the list's sha256. */
    public static String hash() {
        return hash(BundledFormatter.class.getClassLoader());
    }

    /** The formatter's jars, in the order of its classpath, extracted under this Gradle user home if not there yet. */
    public static List<File> jars(File gradleUserHome) {
        return jars(gradleUserHome.toPath(), BundledFormatter.class.getClassLoader());
    }

    static String hash(ClassLoader plugin) {
        return sha256(resource(plugin, LIST)).substring(0, 16);
    }

    static List<File> jars(Path gradleUserHome, ClassLoader plugin) {
        var list = new String(resource(plugin, LIST), StandardCharsets.UTF_8)
                .lines()
                .map(Jar::parse)
                .toList();
        var caches = gradleUserHome.resolve(CACHES);
        var target = caches.resolve(hash(plugin));
        if (!isComplete(target, list)) {
            extract(plugin, list, caches, target);
        }
        return list.stream().map(jar -> target.resolve(jar.name()).toFile()).toList();
    }

    private static boolean isComplete(Path dir, List<Jar> list) {
        return list.stream().allMatch(jar -> Files.isRegularFile(dir.resolve(jar.name())));
    }

    private static void extract(ClassLoader plugin, List<Jar> list, Path caches, Path target) {
        try {
            Files.createDirectories(caches);
            var copy = Files.createTempDirectory(caches, target.getFileName() + "-");
            try {
                for (var jar : list) {
                    copy(plugin, jar, copy.resolve(jar.name()));
                }
                if (Files.exists(target) && !isComplete(target, list)) {
                    // Someone deleted a jar out of it. Whoever still runs from it keeps the files it has open.
                    delete(target);
                }
                moveIntoPlace(copy, target);
            } finally {
                delete(copy);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not extract the formatter's jars into " + target, e);
        }
    }

    private static void copy(ClassLoader plugin, Jar jar, Path file) throws IOException {
        var digest = sha256Digest();
        try (InputStream in = new DigestInputStream(open(plugin, BUNDLE + jar.name()), digest)) {
            Files.copy(in, file);
        }
        var actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equals(jar.sha256())) {
            throw new IllegalStateException(String.format(
                    "%s in the open-java-format Gradle plugin has sha256 %s, but %s lists %s: the plugin's jar is"
                            + " damaged",
                    jar.name(), actual, LIST, jar.sha256()));
        }
    }

    /** Renames the copy into place, unless another build got there first: then the copy is left to be deleted. */
    private static void moveIntoPlace(Path copy, Path target) throws IOException {
        try {
            Files.move(copy, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Both are in the same directory, so the move is a rename all the same.
            moveUnlessThere(copy, target);
        } catch (IOException e) {
            if (!Files.isDirectory(target)) {
                throw e;
            }
        }
    }

    private static void moveUnlessThere(Path copy, Path target) throws IOException {
        try {
            Files.move(copy, target);
        } catch (IOException e) {
            if (!Files.isDirectory(target)) {
                throw e;
            }
        }
    }

    private static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static byte[] resource(ClassLoader plugin, String name) {
        try (InputStream in = open(plugin, name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + name + " from the open-java-format Gradle plugin", e);
        }
    }

    private static InputStream open(ClassLoader plugin, String name) throws IOException {
        var in = plugin.getResourceAsStream(name);
        if (in == null) {
            throw new IOException(name + " is not in the open-java-format Gradle plugin");
        }
        return in;
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM has SHA-256", e);
        }
    }

    /** A line of jars.txt: the jar's sha256 and its name. */
    private record Jar(String sha256, String name) {
        static Jar parse(String line) {
            var space = line.indexOf(' ');
            return new Jar(line.substring(0, space), line.substring(space + 1));
        }
    }
}
