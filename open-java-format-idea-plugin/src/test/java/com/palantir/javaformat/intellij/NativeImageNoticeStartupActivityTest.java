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

import com.intellij.notification.NotificationGroupManager;
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.JavaTestFixtureFactory;
import com.palantir.javaformat.intellij.EmbeddedNativeImage.Installed;
import com.palantir.javaformat.intellij.EmbeddedNativeImage.Status;
import com.palantir.javaformat.intellij.NativeImageNoticeStartupActivity.Notice;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class NativeImageNoticeStartupActivityTest {

    private static final Installed INSTALLED =
            new Installed("21.0.1", Path.of("/system/open-java-format/native/21.0.1/open-java-format"));
    private static final Status OUTDATED = new Status.Outdated(INSTALLED, "21.0.2");

    private final PalantirJavaFormatSettings settings = new PalantirJavaFormatSettings();

    @BeforeEach
    void setUp() {
        settings.setEnabled(true);
        settings.setUseNativeImage(true);
    }

    @Test
    void offersTheNewerBinary() {
        assertThat(NativeImageNoticeStartupActivity.notice(settings, OUTDATED, null))
                .hasValue(new Notice.Outdated("21.0.1", "21.0.2"));
    }

    @Test
    void offersADownloadWhenThereIsNoBinary() {
        assertThat(NativeImageNoticeStartupActivity.notice(settings, new Status.NotDownloaded(), null))
                .hasValue(new Notice.Missing());
    }

    @Test
    void staysQuietAboutAVersionTheUserIgnored() {
        assertThat(NativeImageNoticeStartupActivity.notice(settings, OUTDATED, "21.0.2"))
                .isEmpty();
        // Ignore holds for one version: the next one is offered again.
        assertThat(NativeImageNoticeStartupActivity.notice(settings, OUTDATED, "21.0.1"))
                .isPresent();
    }

    @Test
    void staysQuietWhenTheEmbeddedBinaryDoesNotRun() {
        var custom = new PalantirJavaFormatSettings();
        custom.setEnabled(true);
        custom.setUseNativeImage(true);
        custom.setNativeImagePath(Optional.of(Path.of("/opt/open-java-format")));
        var javaBased = new PalantirJavaFormatSettings();
        javaBased.setEnabled(true);
        var disabled = new PalantirJavaFormatSettings();
        disabled.setUseNativeImage(true);

        assertThat(NativeImageNoticeStartupActivity.notice(custom, OUTDATED, null))
                .isEmpty();
        assertThat(NativeImageNoticeStartupActivity.notice(javaBased, OUTDATED, null))
                .isEmpty();
        assertThat(NativeImageNoticeStartupActivity.notice(disabled, OUTDATED, null))
                .isEmpty();
    }

    @Test
    void staysQuietAboutACurrentBinary() {
        assertThat(NativeImageNoticeStartupActivity.notice(settings, new Status.Current(INSTALLED), null))
                .isEmpty();
        assertThat(NativeImageNoticeStartupActivity.notice(settings, new Status.NoImageForPlatform(), null))
                .isEmpty();
    }

    @Test
    void tellsTheSessionOnlyOnce() {
        var embedded = new EmbeddedNativeImage(Path.of("unused"), Optional.empty(), "http://127.0.0.1:1");

        assertThat(embedded.claimSessionNotice()).isTrue();
        assertThat(embedded.claimSessionNotice()).isFalse();
    }

    @Test
    void registersItsNotificationGroup() throws Exception {
        var builder = IdeaTestFixtureFactory.getFixtureFactory()
                .createLightFixtureBuilder(
                        new DefaultLightProjectDescriptor(), getClass().getName());
        var fixture = JavaTestFixtureFactory.getFixtureFactory().createCodeInsightFixture(builder.getFixture());
        fixture.setUp();
        try {
            assertThat(NotificationGroupManager.getInstance()
                            .getNotificationGroup(Notifications.NATIVE_IMAGE_NOTIFICATION_GROUP))
                    .isNotNull();
        } finally {
            fixture.tearDown();
        }
    }
}
