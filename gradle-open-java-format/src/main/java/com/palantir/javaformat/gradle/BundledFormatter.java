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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

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
 * <p>Builds and daemons may extract at the same time: see {@link AtomicCacheDirectory}.
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

    /** Whether the jars are all there under this Gradle user home, so that {@link #jars} would not extract them. */
    public static boolean isExtracted(File gradleUserHome) {
        var plugin = BundledFormatter.class.getClassLoader();
        return isComplete(gradleUserHome.toPath().resolve(CACHES).resolve(hash(plugin)), list(plugin));
    }

    /**
     * The jars a build declared in palantirJavaFormat, which override the formatter inside the plugin; or, when it
     * declared none, as most builds do, the plugin's own.
     */
    public static List<File> jarsUnlessOverridden(Collection<File> override, File gradleUserHome) {
        return override.isEmpty() ? jars(gradleUserHome) : List.copyOf(override);
    }

    static String hash(ClassLoader plugin) {
        return sha256(resource(plugin, LIST)).substring(0, 16);
    }

    static List<File> jars(Path gradleUserHome, ClassLoader plugin) {
        var list = list(plugin);
        var target = gradleUserHome.resolve(CACHES).resolve(hash(plugin));
        if (!isComplete(target, list)) {
            extract(plugin, list, target);
        }
        return list.stream().map(jar -> target.resolve(jar.name()).toFile()).toList();
    }

    private static List<Jar> list(ClassLoader plugin) {
        return new String(resource(plugin, LIST), UTF_8).lines().map(Jar::parse).toList();
    }

    private static boolean isComplete(Path dir, List<Jar> list) {
        return list.stream().allMatch(jar -> Files.isRegularFile(dir.resolve(jar.name())));
    }

    private static void extract(ClassLoader plugin, List<Jar> list, Path target) {
        try {
            AtomicCacheDirectory.create(
                    target,
                    dir -> {
                        for (var jar : list) {
                            copy(plugin, jar, dir.resolve(jar.name()));
                        }
                    },
                    existing -> isComplete(existing, list));
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
