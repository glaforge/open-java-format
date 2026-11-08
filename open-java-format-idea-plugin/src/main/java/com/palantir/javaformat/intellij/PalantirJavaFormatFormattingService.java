/*
 * (c) Copyright 2024 Palantir Technologies Inc. All rights reserved.
 *
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Range;
import com.intellij.formatting.service.AsyncDocumentFormattingService;
import com.intellij.formatting.service.AsyncFormattingRequest;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.ide.impl.TrustedProjects;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginAware;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.palantir.javaformat.java.FormatterException;
import com.palantir.javaformat.java.FormatterService;
import com.palantir.javaformat.java.Replacement;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Comparator.comparing;
import static java.util.Optional.ofNullable;

class PalantirJavaFormatFormattingService extends AsyncDocumentFormattingService implements PluginAware {
    private static final Logger logger = Logger.getInstance(PalantirJavaFormatFormattingService.class);
    private final FormatterProvider formatterProvider =
            new FormatterProvider(() -> EmbeddedNativeImage.getInstance().installedPath());

    // The platform sets this right after creating the service from plugin.xml: the descriptor of the plugin that
    // declared it, which is how the plugin learns its own path and version (see FormatterProvider.getPluginDescriptor).
    // A service created with `new`, as the tests do, has to be given the descriptor itself.
    @Nullable
    private PluginDescriptor pluginDescriptor;

    @Override
    public void setPluginDescriptor(PluginDescriptor pluginDescriptor) {
        this.pluginDescriptor = pluginDescriptor;
    }

    Optional<PluginDescriptor> getPluginDescriptor() {
        return ofNullable(pluginDescriptor);
    }

    @Override
    protected FormattingTask createFormattingTask(AsyncFormattingRequest request) {
        Project project = request.getContext().getProject();
        // The marketplace verifier reports this class as experimental, and newer IDEs also deprecate it. It stays:
        // its replacement, com.intellij.ide.trustedProjects.TrustedProjects, is @ApiStatus.Internal — a worse thing
        // for a plugin to call.
        if (!TrustedProjects.isTrusted(project)) {
            logger.warn("open-java-format: skipping formatting for untrusted project: " + project.getName());
            request.onError(
                    Notifications.GENERIC_ERROR_NOTIFICATION_GROUP,
                    "open-java-format requires a trusted project to load the external formatter classpath.");
            return null;
        }
        PalantirJavaFormatSettings settings = PalantirJavaFormatSettings.getInstance(project);
        Optional<FormatterService> formatter = formatterProvider.get(project, settings);
        return new PalantirJavaFormatFormattingTask(request, formatter);
    }

    @Override
    protected String getNotificationGroupId() {
        return Notifications.PARSING_ERROR_NOTIFICATION_GROUP;
    }

    @Override
    protected @NlsSafe String getName() {
        return "open-java-format";
    }

    @Override
    public Set<Feature> getFeatures() {
        return Set.of(Feature.FORMAT_FRAGMENTS);
    }

    @Override
    public boolean canFormat(PsiFile file) {
        return JavaFileType.INSTANCE.equals(file.getFileType())
                && PalantirJavaFormatSettings.getInstance(file.getProject()).isEnabled();
    }

    private static final class PalantirJavaFormatFormattingTask implements FormattingTask {
        private final AsyncFormattingRequest request;
        private final Optional<FormatterService> formatterService;

        public PalantirJavaFormatFormattingTask(
                AsyncFormattingRequest request, Optional<FormatterService> formatterService) {
            this.request = request;
            this.formatterService = formatterService;
        }

        @Override
        public void run() {
            if (formatterService.isEmpty()) {
                request.onError(
                        Notifications.GENERIC_ERROR_NOTIFICATION_GROUP,
                        "Failed to format file because formatterService is not configured");
                return;
            }

            String preFormatText = request.getDocumentText();

            try {
                if (logger.isDebugEnabled()) {
                    logger.debug(String.format(
                            "Received request to format file=%s, length=%s with ranges=%s",
                            ofNullable(request.getIOFile())
                                    .map(file -> file.toPath().toString())
                                    .orElse("null"),
                            preFormatText.length(),
                            request.getFormattingRanges()));
                }

                // Note: I attempted to implement this using getFormatReplacements and elide an update when
                // there were no replacements. There is a bug in the underlying formatter where it _always_
                // returns a change. Thus we must perform a content aware diff / branching.
                String formattedText = applyReplacements(
                        preFormatText, formatterService.get().getFormatReplacements(preFormatText, toRanges(request)));

                // The Javadoc of this API says that you should set it to null when the document is unchanged
                // We should not be trying to format a document that is already formatted
                if (preFormatText.equals(formattedText)) {
                    request.onTextReady(null);
                } else {
                    request.onTextReady(formattedText);
                }
            } catch (FormatterException e) {
                // A syntax error in the file, not a fault of the plugin: an error would go to its error reports.
                logger.warn(
                        String.format(
                                "Failed to format file %s",
                                request.getContext().getContainingFile().getName()),
                        e);
                request.onError(
                        Notifications.PARSING_ERROR_TITLE,
                        Notifications.parsingErrorMessage(
                                request.getContext().getContainingFile().getName()));
            } catch (ProcessCanceledException e) {
                throw e;
            } catch (RuntimeException e) {
                // Typically the formatter's JVM failing to start or exiting with an error. Left alone, the IDE
                // swallows it and Reformat looks as if it did nothing. An error rather than a warning, so that it goes
                // to the plugin's error reports on the JetBrains Marketplace (see errorHandler in plugin.xml).
                logger.error("open-java-format could not run the formatter", e);
                request.onError(
                        Notifications.GENERIC_ERROR_NOTIFICATION_GROUP,
                        Notifications.formatterFailedMessage(
                                request.getContext().getContainingFile().getName()));
            }
        }

        public static String applyReplacements(String input, Collection<Replacement> replacementsCollection) {
            List<Replacement> replacements = new ArrayList<>(replacementsCollection);
            replacements.sort(comparing((Replacement r) -> r.getReplaceRange().lowerEndpoint())
                    .reversed());
            StringBuilder writer = new StringBuilder(input);
            for (Replacement replacement : replacements) {
                writer.replace(
                        replacement.getReplaceRange().lowerEndpoint(),
                        replacement.getReplaceRange().upperEndpoint(),
                        replacement.getReplacementString());
            }
            return writer.toString();
        }

        private static Collection<Range<Integer>> toRanges(AsyncFormattingRequest request) {
            if (isWholeFile(request)) {
                // The IDE sometimes passes invalid ranges when the file is unsaved before invoking the
                // formatter. So this is a workaround for that issue.
                return ImmutableList.of(
                        Range.closedOpen(0, request.getDocumentText().length()));
            }
            return request.getFormattingRanges().stream()
                    .map(textRange -> Range.closedOpen(textRange.getStartOffset(), textRange.getEndOffset()))
                    .collect(ImmutableList.toImmutableList());
        }

        private static boolean isWholeFile(AsyncFormattingRequest request) {
            List<TextRange> ranges = request.getFormattingRanges();
            return ranges.size() == 1
                    && ranges.get(0).getStartOffset() == 0
                    // using greater than or equal because ranges are sometimes passed inaccurately
                    && ranges.get(0).getEndOffset() >= request.getDocumentText().length();
        }

        @Override
        public boolean isRunUnderProgress() {
            return true;
        }

        @Override
        public boolean cancel() {
            return false;
        }
    }
}
