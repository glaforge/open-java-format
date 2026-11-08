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

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * A directory of open-java-format-caches, written once and never changed after: its name says what is in it.
 *
 * <p>Builds and daemons may write it at the same time. Each writes into a directory of its own next to the target and
 * renames it into place, which fails when the target is already there; the build that loses deletes its copy and uses
 * the one that won. There is no file lock: FileChannel.lock fails in a daemon whose other build holds the same file
 * through another class loader of the plugin.
 */
final class AtomicCacheDirectory {

    /** Writes what the directory holds. */
    interface Contents {
        void writeInto(Path dir) throws IOException;
    }

    private AtomicCacheDirectory() {}

    /**
     * Creates {@code target} with these contents, unless another build has. A target that is not complete, as when
     * someone deleted a file out of it, is replaced; whoever still runs from it keeps the files it has open.
     */
    static void create(Path target, Contents contents, Predicate<Path> isComplete) throws IOException {
        var caches = target.getParent();
        Files.createDirectories(caches);
        var copy = Files.createTempDirectory(caches, target.getFileName() + "-");
        try {
            contents.writeInto(copy);
            if (Files.exists(target) && !isComplete.test(target)) {
                delete(target);
            }
            moveIntoPlace(copy, target);
        } finally {
            delete(copy);
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
}
