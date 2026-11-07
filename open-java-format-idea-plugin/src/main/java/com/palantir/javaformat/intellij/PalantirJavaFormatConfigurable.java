/*
 * Copyright 2016 Google Inc. All Rights Reserved.
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

import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.BaseConfigurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextBrowseFolderListener;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.palantir.javaformat.intellij.EmbeddedNativeImage.Status;
import com.palantir.javaformat.intellij.PalantirJavaFormatSettings.EnabledState;
import java.awt.FlowLayout;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.swing.Box;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

class PalantirJavaFormatConfigurable extends BaseConfigurable implements SearchableConfigurable {

    private final Project project;
    private final EmbeddedNativeImage embeddedNativeImage;
    private final JPanel panel;
    private final JCheckBox enable;
    private final JCheckBox useNativeImage;
    private final JPanel nativeImageOptions;
    private final JLabel embeddedStatus;
    private final ActionLink embeddedAction;
    private final TextFieldWithBrowseButton nativeImagePath;
    private final JLabel formatterType;
    private final JLabel formatterVersion;
    private final JLabel pluginVersion;

    // Lives from createComponent() to disposeUIResources(): the download reports its progress to the page.
    @Nullable
    private Disposable uiDisposable;

    public PalantirJavaFormatConfigurable(Project project) {
        this(project, EmbeddedNativeImage.getInstance());
    }

    PalantirJavaFormatConfigurable(Project project, EmbeddedNativeImage embeddedNativeImage) {
        this.project = project;
        this.embeddedNativeImage = embeddedNativeImage;
        this.enable = new JCheckBox("Enable open-java-format");
        this.useNativeImage = new JCheckBox("Use native binary");
        this.embeddedStatus = new JLabel();
        this.embeddedAction = new ActionLink();
        embeddedAction.addActionListener(event -> embeddedNativeImage.download(project, pluginVersion()));
        var pathField = new JBTextField();
        pathField.getEmptyText().setText("Leave empty to use the embedded version");
        this.nativeImagePath = new TextFieldWithBrowseButton(pathField);
        nativeImagePath.addBrowseFolderListener(new TextBrowseFolderListener(
                FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Native Binary"), project));
        this.pluginVersion = new JLabel();
        this.formatterVersion = new JLabel();
        this.formatterType = new JLabel();

        var statusRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        statusRow.add(embeddedStatus);
        statusRow.add(Box.createHorizontalStrut(JBUI.scale(6)));
        statusRow.add(embeddedAction);
        this.nativeImageOptions = FormBuilder.createFormBuilder()
                .setFormLeftIndent(JBUI.scale(20))
                .addComponent(statusRow)
                .addLabeledComponent("Binary", nativeImagePath)
                .getPanel();
        useNativeImage.addItemListener(event -> nativeImageOptions.setVisible(useNativeImage.isSelected()));

        // Laid out here rather than by the IntelliJ GUI designer. A .form is woven into the class at build
        // time, and this build does not instrument bytecode (see build.gradle), which left every field
        // above null: the page rendered nothing and reset() threw the moment it was opened.
        this.panel = FormBuilder.createFormBuilder()
                .addComponent(enable)
                .addComponent(useNativeImage)
                .addComponent(nativeImageOptions)
                .addLabeledComponent("Plugin version", pluginVersion)
                .addLabeledComponent("Implementation version", formatterVersion)
                .addLabeledComponent("Formatter type", formatterType)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    @NotNull
    @Override
    public String getId() {
        return "open-java-format.settings";
    }

    @Nullable
    @Override
    public Runnable enableSearch(String option) {
        return null;
    }

    @Nls
    @Override
    public String getDisplayName() {
        return "open-java-format Settings";
    }

    @Nullable
    @Override
    public String getHelpTopic() {
        return null;
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        if (uiDisposable == null) {
            uiDisposable = Disposer.newDisposable("open-java-format settings page");
            embeddedNativeImage.addChangeListener(this::showEmbeddedNativeImage, uiDisposable);
        }
        return panel;
    }

    @Override
    public void apply() throws ConfigurationException {
        PalantirJavaFormatSettings settings = PalantirJavaFormatSettings.getInstance(project);
        var path = enteredNativeImagePath();
        if (useNativeImage.isSelected()) {
            if (path.isPresent() && !(Files.isRegularFile(path.get()) && Files.isExecutable(path.get()))) {
                throw new ConfigurationException(path.get() + " is not an executable file");
            }
            if (path.isEmpty() && embeddedNativeImage.installed().isEmpty()) {
                throw new ConfigurationException("Download the embedded version or choose a binary");
            }
        }
        settings.setEnabled(enable.isSelected() ? EnabledState.ENABLED : getDisabledState());
        settings.setUseNativeImage(useNativeImage.isSelected());
        settings.setNativeImagePath(path);
        showEmbeddedNativeImage();
    }

    private EnabledState getDisabledState() {
        // The default settings (inherited by new projects) are either 'enabled' or
        // 'show notification'. There's no way to default new projects to disabled. If someone wants
        // that, we can add another checkbox, I suppose.
        return project.isDefault() ? EnabledState.UNKNOWN : EnabledState.DISABLED;
    }

    @Override
    public void reset() {
        PalantirJavaFormatSettings settings = PalantirJavaFormatSettings.getInstance(project);
        enable.setSelected(settings.isEnabled());
        useNativeImage.setSelected(settings.useNativeImage());
        nativeImageOptions.setVisible(settings.useNativeImage());
        nativeImagePath.setText(
                settings.getNativeImagePath().map(Path::toString).orElse(""));
        pluginVersion.setText(settings.getImplementationVersion().orElse("unknown"));
        formatterVersion.setText(getFormatterVersionText(settings));
        showEmbeddedNativeImage();
    }

    @Override
    public boolean isModified() {
        PalantirJavaFormatSettings settings = PalantirJavaFormatSettings.getInstance(project);
        Optional<Path> path;
        try {
            path = enteredNativeImagePath();
        } catch (ConfigurationException e) {
            return true;
        }
        return enable.isSelected() != settings.isEnabled()
                || useNativeImage.isSelected() != settings.useNativeImage()
                || !path.equals(settings.getNativeImagePath());
    }

    @Override
    public void disposeUIResources() {
        if (uiDisposable != null) {
            Disposer.dispose(uiDisposable);
            uiDisposable = null;
        }
    }

    record StatusLine(String text, Optional<String> action) {}

    /** The line under Use native binary, and the link next to it, which downloads the plugin's version. */
    static StatusLine statusLine(Status status) {
        return switch (status) {
            case Status.NoImageForPlatform noImage ->
                new StatusLine(
                        "No native binary is published for " + System.getProperty("os.name") + " "
                                + System.getProperty("os.arch") + ".",
                        Optional.empty());
            case Status.NotDownloaded notDownloaded -> new StatusLine("No embedded version.", Optional.of("Download"));
            case Status.Downloading downloading ->
                new StatusLine("Downloading embedded version " + downloading.version() + "…", Optional.empty());
            case Status.DownloadFailed failed ->
                new StatusLine("Download failed: " + failed.reason() + ".", Optional.of("Retry"));
            case Status.Current current ->
                new StatusLine("Use embedded version " + current.installed().version(), Optional.empty());
            case Status.Outdated outdated ->
                new StatusLine(
                        "Use embedded version " + outdated.installed().version() + ".",
                        Optional.of("Update to " + outdated.available()));
        };
    }

    private void showEmbeddedNativeImage() {
        var line = statusLine(embeddedNativeImage.status(pluginVersion()));
        embeddedStatus.setText(line.text());
        embeddedAction.setText(line.action().orElse(""));
        embeddedAction.setVisible(line.action().isPresent());
        // What runs with the settings as applied: the embedded binary can arrive while the page is open.
        formatterType.setText(FormatterSelection.of(
                        PalantirJavaFormatSettings.getInstance(project), embeddedNativeImage.installedPath())
                .describe());
    }

    private String pluginVersion() {
        return PalantirJavaFormatSettings.getInstance(project)
                .getImplementationVersion()
                .orElse("unknown");
    }

    private Optional<Path> enteredNativeImagePath() throws ConfigurationException {
        var text = nativeImagePath.getText().strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(text));
        } catch (InvalidPathException e) {
            throw new ConfigurationException(text + " is not a path: " + e.getReason());
        }
    }

    private static String getFormatterVersionText(PalantirJavaFormatSettings settings) {
        String suffix = settings.injectedVersionIsOutdated() ? " (using bundled)" : " (using injected)";
        return settings.computeFormatterVersion().orElse("(bundled)") + suffix;
    }
}
