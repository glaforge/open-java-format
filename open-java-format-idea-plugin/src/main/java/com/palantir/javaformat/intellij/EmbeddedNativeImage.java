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

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.NioFiles;
import com.intellij.util.io.HttpRequests;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * The native binary the plugin downloads for the IDE's platform, one for the whole IDE. It is kept in the IDE's system
 * directory as {@code open-java-format/native/<version>/<file name>}, and once a version is installed the others are
 * removed: a binary is some 65 MB.
 *
 * <p>The binary comes from Maven Central rather than the GitHub release. The release workflow uploads the plugin to
 * the JetBrains Marketplace only once Maven Central serves the version's native images, while the GitHub release stays
 * a draft until someone publishes it; and Maven Central has a .sha256 next to every file.
 */
@Service(Service.Level.APP)
final class EmbeddedNativeImage {
    private static final Logger log = Logger.getInstance(EmbeddedNativeImage.class);

    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2";
    private static final String TEMPORARY_PREFIX = "download-";
    private static final Pattern RELEASE = Pattern.compile("\\d+(\\.\\d+)*");

    private final Path root;
    private final Optional<NativePlatform> platform;
    private final String repository;

    private final AtomicReference<Activity> activity = new AtomicReference<>(new Activity.Idle());
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean noticeShown = new AtomicBoolean();

    EmbeddedNativeImage() {
        this(
                PathManager.getSystemDir().resolve("open-java-format").resolve("native"),
                NativePlatform.current(),
                MAVEN_CENTRAL);
    }

    EmbeddedNativeImage(Path root, Optional<NativePlatform> platform, String repository) {
        this.root = root;
        this.platform = platform;
        this.repository = repository;
    }

    static EmbeddedNativeImage getInstance() {
        return ApplicationManager.getApplication().getService(EmbeddedNativeImage.class);
    }

    record Installed(String version, Path path) {}

    /** What the settings page says about the embedded binary. */
    sealed interface Status {
        record NoImageForPlatform() implements Status {}

        record NotDownloaded() implements Status {}

        record Downloading(String version) implements Status {}

        record DownloadFailed(String version, String reason) implements Status {}

        record Current(Installed installed) implements Status {}

        /** The binary of an earlier version, which keeps formatting until the newer one is downloaded. */
        record Outdated(Installed installed, String available) implements Status {}
    }

    private sealed interface Activity {
        record Idle() implements Activity {}

        record Downloading(String version) implements Activity {}

        record Failed(String version, String reason) implements Activity {}
    }

    /** The newest version on disk, whichever plugin version downloaded it. */
    Optional<Installed> installed() {
        if (platform.isEmpty() || !Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (var versions = Files.list(root)) {
            return versions.filter(Files::isDirectory)
                    .flatMap(dir -> FormatterVersion.parse(dir.getFileName().toString()).stream()
                            .map(version -> new Candidate(version, dir)))
                    .filter(candidate -> Files.isExecutable(candidate.binary(platform.get())))
                    .max(Comparator.comparing(Candidate::parsed))
                    .map(candidate -> new Installed(candidate.version(), candidate.binary(platform.get())));
        } catch (IOException e) {
            log.warn("open-java-format: cannot list the native binaries in " + root, e);
            return Optional.empty();
        }
    }

    Optional<Path> installedPath() {
        return installed().map(Installed::path);
    }

    /** pluginVersion: the version the plugin would download, which is its own. */
    Status status(String pluginVersion) {
        if (platform.isEmpty()) {
            return new Status.NoImageForPlatform();
        }
        var current = activity.get();
        if (current instanceof Activity.Downloading downloading) {
            return new Status.Downloading(downloading.version());
        }
        if (current instanceof Activity.Failed failed) {
            return new Status.DownloadFailed(failed.version(), failed.reason());
        }
        return installed()
                .<Status>map(installed -> isOlder(installed.version(), pluginVersion)
                        ? new Status.Outdated(installed, pluginVersion)
                        : new Status.Current(installed))
                .orElseGet(Status.NotDownloaded::new);
    }

    /**
     * Whether installed is older than available. Only a release is ever newer: a plugin built from a commit has a
     * version such as 21.0.1-3-gabc1234, or a bare hash, and Maven Central has no binary for either.
     */
    static boolean isOlder(String installed, String available) {
        var installedVersion = FormatterVersion.parse(installed);
        var availableVersion = FormatterVersion.parse(available);
        return RELEASE.matcher(available).matches()
                && installedVersion.isPresent()
                && availableVersion.isPresent()
                && installedVersion.get().compareTo(availableVersion.get()) < 0;
    }

    /**
     * Downloads the binary of the given version in the background, with the IDE's progress and cancel button. A
     * download already under way, from another project or from the notification, is the one that continues.
     */
    void download(@Nullable Project project, String version) {
        var downloading = new Activity.Downloading(version);
        var previous =
                activity.getAndUpdate(current -> current instanceof Activity.Downloading ? current : downloading);
        if (previous instanceof Activity.Downloading) {
            return;
        }
        fireChanged();
        new Task.Backgroundable(project, "Downloading the open-java-format native binary " + version, true) {
            @Override
            public void run(ProgressIndicator indicator) {
                Activity outcome = new Activity.Idle();
                try {
                    install(version, indicator);
                } catch (IOException e) {
                    log.warn("open-java-format: could not download the native binary " + version, e);
                    outcome = new Activity.Failed(
                            version,
                            Objects.requireNonNullElse(
                                    e.getMessage(), e.getClass().getSimpleName()));
                } finally {
                    // Also on cancel, which throws ProcessCanceledException through here.
                    activity.set(outcome);
                    fireChanged();
                }
            }
        }.queue();
    }

    /** Downloads, checks and installs the binary, then removes every other version. */
    Installed install(String version, ProgressIndicator indicator) throws IOException {
        var nativePlatform = platform.orElseThrow(() -> new IOException("no native binary is published for "
                + System.getProperty("os.name") + " " + System.getProperty("os.arch")));
        var url = String.format(
                "%s/dev/openjavaformat/open-java-format-native/%2$s/open-java-format-native-%2$s-nativeImage-%3$s.%4$s",
                repository, version, nativePlatform.name(), nativePlatform.extension());

        Files.createDirectories(root);
        // Next to its destination, so that the move into place is a rename.
        var download = Files.createTempFile(root, TEMPORARY_PREFIX, ".tmp");
        try {
            var expected = fetch(
                    url + ".sha256",
                    version,
                    () -> HttpRequests.request(url + ".sha256")
                            .productNameAsUserAgent()
                            .readString(indicator));
            indicator.setText("Downloading " + nativePlatform.fileName() + " " + version);
            fetch(url, version, () -> {
                HttpRequests.request(url).productNameAsUserAgent().saveToFile(download, indicator);
                return download;
            });
            if (!sha256(download).equalsIgnoreCase(expected.strip().split("\\s+")[0])) {
                throw new IOException("the checksum of the download does not match Maven Central's");
            }

            var binary = Files.createDirectories(root.resolve(version)).resolve(nativePlatform.fileName());
            Files.move(download, binary, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (!binary.toFile().setExecutable(true)) {
                throw new IOException("cannot make " + binary + " executable");
            }
            log.info("open-java-format: installed the native binary " + version + " at " + binary);
            // This download is the only one: its own file has just moved into place.
            removeAllBut(version);
            return new Installed(version, binary);
        } finally {
            Files.deleteIfExists(download);
        }
    }

    /**
     * Removes what an earlier session could not: the binaries of other versions, one of which Windows may have held
     * open while it formatted, and unfinished downloads. Never while a download is under way, whose file would go too.
     */
    void removeStale() {
        if (!(activity.get() instanceof Activity.Downloading)) {
            removeAllBut(installed().map(Installed::version).orElse(""));
        }
    }

    private void removeAllBut(String version) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var entries = Files.list(root)) {
            for (var entry : entries.toList()) {
                var name = entry.getFileName().toString();
                if (Files.isDirectory(entry) ? !name.equals(version) : name.startsWith(TEMPORARY_PREFIX)) {
                    NioFiles.deleteRecursively(entry);
                }
            }
        } catch (IOException e) {
            log.warn("open-java-format: cannot remove old native binaries from " + root, e);
        }
    }

    /** True the first time it is asked in an IDE session: the notice about the binary is shown once, not per project. */
    boolean claimSessionNotice() {
        return noticeShown.compareAndSet(false, true);
    }

    /** Calls the listener on the EDT whenever the status may have changed, until parent is disposed. */
    void addChangeListener(Runnable listener, Disposable parent) {
        listeners.add(listener);
        Disposer.register(parent, () -> listeners.remove(listener));
    }

    private void fireChanged() {
        // any(): the settings dialog that listens is modal.
        ApplicationManager.getApplication().invokeLater(() -> listeners.forEach(Runnable::run), ModalityState.any());
    }

    private interface Fetch<T> {
        T run() throws IOException;
    }

    private static <T> T fetch(String url, String version, Fetch<T> fetch) throws IOException {
        try {
            return fetch.run();
        } catch (HttpRequests.HttpStatusException e) {
            if (e.getStatusCode() == 404) {
                throw new IOException("no native binary is published for version " + version, e);
            }
            throw new IOException(url + " answered " + e.getStatusCode(), e);
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM has SHA-256", e);
        }
        try (var input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record Candidate(FormatterVersion parsed, Path dir) {
        String version() {
            return dir.getFileName().toString();
        }

        Path binary(NativePlatform platform) {
            return dir.resolve(platform.fileName());
        }
    }
}
