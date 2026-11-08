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
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The native image as formatDiff runs it: a copy in {@code <Gradle user home>/open-java-format-caches/<hash>/}, next
 * to the jars of the formatter inside the plugin, made executable. Gradle hands over the image as it resolved it, from
 * its own cache or from a repository on disk, and neither keeps it executable. The hash is of the image's contents:
 * another image is another directory, and see {@link AtomicCacheDirectory} for builds that copy at the same time.
 */
public final class NativeImageAtomicCopy {

    /**
     * The hash of an image by its path, size and time. The image is some 60 MB, and a daemon runs formatDiff build after
     * build with the same one.
     */
    private static final Map<Image, String> HASHES = new ConcurrentHashMap<>();

    private NativeImageAtomicCopy() {}

    /** The executable copy of this image, copied under this Gradle user home if not there yet. */
    public static Path copyToCache(Path image, File gradleUserHome) {
        var name = image.getFileName();
        var dir = gradleUserHome.toPath().resolve(BundledFormatter.CACHES).resolve(hash(image));
        var copy = dir.resolve(name);
        if (!Files.isRegularFile(copy)) {
            try {
                AtomicCacheDirectory.create(
                        dir,
                        into -> {
                            Files.copy(image, into.resolve(name));
                            makeExecutable(into.resolve(name));
                        },
                        existing -> Files.isRegularFile(existing.resolve(name)));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not copy the native image into " + dir, e);
            }
        }
        return copy;
    }

    private static String hash(Path image) {
        try {
            var key = new Image(
                    image.toAbsolutePath(),
                    Files.size(image),
                    Files.getLastModifiedTime(image).toMillis());
            return HASHES.computeIfAbsent(key, _key -> sha256(image).substring(0, 16));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the native image " + image, e);
        }
    }

    private static String sha256(Path image) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(image), digest)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the native image " + image, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM has SHA-256", e);
        }
    }

    private static void makeExecutable(Path file) throws IOException {
        // Windows file systems have no POSIX permissions, and an .exe needs none to run.
        if (!file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Files.setPosixFilePermissions(
                file,
                Stream.concat(
                                Files.getPosixFilePermissions(file).stream(),
                                Stream.of(
                                        PosixFilePermission.OWNER_EXECUTE,
                                        PosixFilePermission.GROUP_EXECUTE,
                                        PosixFilePermission.OTHERS_EXECUTE))
                        .collect(Collectors.toUnmodifiableSet()));
    }

    /** An image as it was when it was hashed: replaced in place, it changes size or time. */
    private record Image(Path path, long size, long lastModified) {}
}
