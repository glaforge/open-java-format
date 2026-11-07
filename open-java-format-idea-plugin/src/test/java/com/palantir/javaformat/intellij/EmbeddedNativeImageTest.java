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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture;
import com.intellij.testFramework.fixtures.JavaTestFixtureFactory;
import com.palantir.javaformat.intellij.EmbeddedNativeImage.Installed;
import com.palantir.javaformat.intellij.EmbeddedNativeImage.Status;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EmbeddedNativeImageTest {

    private static final NativePlatform MACOS = new NativePlatform("macos", "aarch64");
    private static final byte[] BINARY = "#!/bin/sh\necho formatted\n".getBytes(UTF_8);
    private static final String ARTIFACT = "/maven2/dev/openjavaformat/open-java-format-native/21.0.1/"
            + "open-java-format-native-21.0.1-nativeImage-macos_aarch64.bin";

    @TempDir
    private Path root;

    // HttpRequests reads the IDE's proxy settings, so it needs the application a fixture starts.
    private JavaCodeInsightTestFixture fixture;
    private HttpServer server;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private EmbeddedNativeImage embedded;

    @BeforeEach
    void setUp() throws Exception {
        var projectBuilder = IdeaTestFixtureFactory.getFixtureFactory()
                .createLightFixtureBuilder(
                        new DefaultLightProjectDescriptor(), getClass().getName());
        fixture = JavaTestFixtureFactory.getFixtureFactory().createCodeInsightFixture(projectBuilder.getFixture());
        fixture.setUp();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var body = files.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        var repository = "http://127.0.0.1:" + server.getAddress().getPort() + "/maven2";
        embedded = new EmbeddedNativeImage(root, Optional.of(MACOS), repository);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop(0);
        fixture.tearDown();
    }

    @Test
    void installsABinaryWhoseChecksumMatches() throws Exception {
        publish(BINARY, sha256(BINARY));

        var installed = embedded.install("21.0.1", new EmptyProgressIndicator());

        var binary = root.resolve("21.0.1/open-java-format-macos_aarch64");
        assertThat(installed).isEqualTo(new Installed("21.0.1", binary));
        assertThat(binary).hasBinaryContent(BINARY).isExecutable();
        assertThat(embedded.installed()).hasValue(installed);
    }

    @Test
    void refusesABinaryWhoseChecksumDoesNotMatch() throws Exception {
        publish(BINARY, sha256("something else".getBytes(UTF_8)));

        assertThatThrownBy(() -> embedded.install("21.0.1", new EmptyProgressIndicator()))
                .isInstanceOf(IOException.class)
                .hasMessage("the checksum of the download does not match Maven Central's");
        assertThat(entries()).isEmpty();
    }

    @Test
    void saysWhenAVersionHasNoBinary() {
        // A build of the plugin from a commit has a version Maven Central never saw.
        assertThatThrownBy(() -> embedded.install("2b233feb", new EmptyProgressIndicator()))
                .isInstanceOf(IOException.class)
                .hasMessage("no native binary is published for version 2b233feb");
        assertThat(entries()).isEmpty();
    }

    @Test
    void keepsOnlyTheVersionItInstalled() throws Exception {
        // A binary is some 65 MB, and the one of the plugin's previous version is no longer needed.
        installByHand("21.0.0");
        Files.writeString(root.resolve("download-123.tmp"), "half a binary");
        publish(BINARY, sha256(BINARY));

        embedded.install("21.0.1", new EmptyProgressIndicator());

        assertThat(entries()).containsExactly(root.resolve("21.0.1"));
    }

    @Test
    void runsTheNewestVersionThereIs() throws Exception {
        installByHand("21.0.2");
        installByHand("21.0.10");
        Files.createDirectories(root.resolve("21.0.11"));

        assertThat(embedded.installed()).map(Installed::version).hasValue("21.0.10");
    }

    @Test
    void hasNoneBeforeTheFirstDownload() {
        assertThat(embedded.installed()).isEmpty();
        assertThat(embedded.status("21.0.1")).isEqualTo(new Status.NotDownloaded());
    }

    @Test
    void offersThePluginsVersionWhenTheBinaryIsAnother() throws Exception {
        var installed = installByHand("21.0.1");

        assertThat(embedded.status("21.0.1")).isEqualTo(new Status.Current(installed));
        assertThat(embedded.status("21.0.2")).isEqualTo(new Status.OtherVersion(installed, "21.0.2"));
        // A plugin rolled back to an earlier version wants that version's binary too.
        assertThat(embedded.status("21.0.0")).isEqualTo(new Status.OtherVersion(installed, "21.0.0"));
        // A plugin built from a commit cannot download its own version, so nothing is on offer.
        assertThat(embedded.status("2b233feb")).isEqualTo(new Status.Current(installed));
        assertThat(embedded.status("21.0.1-15-gcd4ee30.dirty")).isEqualTo(new Status.Current(installed));
    }

    @Test
    void reportsAFailedDownload() {
        embedded.download(null, "2b233feb");

        assertThat(embedded.status("2b233feb"))
                .isEqualTo(new Status.DownloadFailed("2b233feb", "no native binary is published for version 2b233feb"));
    }

    @Test
    void hasNothingToOfferWhereNoImageIsPublished() {
        var elsewhere = new EmbeddedNativeImage(root, Optional.empty(), "http://127.0.0.1:1/maven2");

        assertThat(elsewhere.status("21.0.1")).isEqualTo(new Status.NoImageForPlatform());
        assertThat(elsewhere.installed()).isEmpty();
    }

    private void publish(byte[] binary, String sha256) {
        files.put(ARTIFACT, binary);
        files.put(ARTIFACT + ".sha256", sha256.getBytes(UTF_8));
    }

    private Installed installByHand(String version) throws IOException {
        var binary = Files.createDirectories(root.resolve(version)).resolve("open-java-format-macos_aarch64");
        Files.write(binary, BINARY);
        assertThat(binary.toFile().setExecutable(true)).isTrue();
        return new Installed(version, binary);
    }

    private List<Path> entries() {
        try (var entries = Files.list(root)) {
            return entries.toList();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
