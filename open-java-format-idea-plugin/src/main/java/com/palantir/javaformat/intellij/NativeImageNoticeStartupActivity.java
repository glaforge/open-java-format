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

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.palantir.javaformat.intellij.EmbeddedNativeImage.Status;
import java.util.Optional;
import javax.annotation.Nullable;
import org.jetbrains.annotations.NotNull;

/**
 * Tells the user, as the IDE does about outdated spelling dictionaries, when the embedded native binary is older than
 * the plugin, or missing although it is switched on. Once per IDE session, however many projects open, and never
 * again for a version the user ignored.
 */
public final class NativeImageNoticeStartupActivity implements StartupActivity.DumbAware {

    static final String IGNORED_VERSION = "open-java-format.native.ignoredVersion";

    sealed interface Notice {
        record Outdated(String installed, String available) implements Notice {}

        record Missing() implements Notice {}
    }

    @Override
    public void runActivity(@NotNull Project project) {
        var embedded = EmbeddedNativeImage.getInstance();
        embedded.removeStale();

        var settings = PalantirJavaFormatSettings.getInstance(project);
        var pluginVersion = settings.getImplementationVersion().orElse("unknown");
        var properties = PropertiesComponent.getInstance();
        var notice = notice(settings, embedded.status(pluginVersion), properties.getValue(IGNORED_VERSION));
        if (notice.isEmpty() || !embedded.claimSessionNotice()) {
            return;
        }

        var group = NotificationGroupManager.getInstance()
                .getNotificationGroup(Notifications.NATIVE_IMAGE_NOTIFICATION_GROUP);
        var notification = switch (notice.get()) {
            case Notice.Outdated outdated ->
                group.createNotification(
                                Notifications.nativeImageOutdatedMessage(outdated.installed(), outdated.available()),
                                NotificationType.INFORMATION)
                        .addAction(NotificationAction.createSimpleExpiring(
                                "Download", () -> embedded.download(project, outdated.available())))
                        .addAction(NotificationAction.createSimpleExpiring(
                                "Ignore", () -> properties.setValue(IGNORED_VERSION, outdated.available())));
            case Notice.Missing missing ->
                group.createNotification(Notifications.nativeImageMissingMessage(), NotificationType.WARNING)
                        .addAction(NotificationAction.createSimpleExpiring(
                                "Download", () -> embedded.download(project, pluginVersion)));
        };
        notification.setTitle("open-java-format").notify(project);
    }

    /**
     * What to tell the user about a project's embedded binary, if anything. Only where it runs: open-java-format on,
     * the native formatter on, and no binary of the user's own.
     */
    static Optional<Notice> notice(
            PalantirJavaFormatSettings settings, Status status, @Nullable String ignoredVersion) {
        if (!settings.isEnabled()
                || !settings.useNativeImage()
                || settings.getNativeImagePath().isPresent()) {
            return Optional.empty();
        }
        if (status instanceof Status.NotDownloaded) {
            return Optional.of(new Notice.Missing());
        }
        if (status instanceof Status.Outdated outdated && !outdated.available().equals(ignoredVersion)) {
            return Optional.of(new Notice.Outdated(outdated.installed().version(), outdated.available()));
        }
        return Optional.empty();
    }
}
