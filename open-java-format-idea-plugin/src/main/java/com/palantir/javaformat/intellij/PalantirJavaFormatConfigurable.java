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
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.palantir.javaformat.intellij.EmbeddedNativeImage.Status;
import com.palantir.javaformat.intellij.PalantirJavaFormatSettings.EnabledState;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
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
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;
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
    private final JPanel activeFormatterRow;
    private final JTextArea activeFormatter;

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
        embeddedAction.addActionListener(event -> embeddedNativeImage.update(project, pluginVersion()));
        var pathField = new JBTextField();
        pathField.getEmptyText().setText("Leave empty to use the embedded version");
        // A fixed preferred width, which the form stretches: sized to a long path, it would widen the page instead.
        pathField.setColumns(30);
        this.nativeImagePath = new TextFieldWithBrowseButton(pathField);
        nativeImagePath.addBrowseFolderListener(new TextBrowseFolderListener(
                FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Native Binary"), project));
        this.activeFormatter = selectableText();

        var statusRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        statusRow.add(embeddedStatus);
        statusRow.add(Box.createHorizontalStrut(JBUI.scale(6)));
        statusRow.add(embeddedAction);
        this.nativeImageOptions = FormBuilder.createFormBuilder()
                .setFormLeftIndent(JBUI.scale(20))
                .addComponent(statusRow)
                .addLabeledComponent("Binary", nativeImagePath)
                .getPanel();
        // Filling its own form vertically: only then does the form put the label above the text, rather than beside
        // the middle of a wrapped path.
        this.activeFormatterRow = FormBuilder.createFormBuilder()
                .addSeparator()
                .addLabeledComponentFillVertically("Active formatter", activeFormatter)
                .getPanel();

        // The page shows what runs once it is applied, so it follows every box and the path as they change.
        enable.addItemListener(event -> showPage());
        useNativeImage.addItemListener(event -> showPage());
        nativeImagePath.getTextField().getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(DocumentEvent event) {
                showActiveFormatter();
            }
        });

        // Laid out here rather than by the IntelliJ GUI designer. A .form is woven into the class at build
        // time, and this build does not instrument bytecode (see build.gradle), which left every field
        // above null: the page rendered nothing and reset() threw the moment it was opened.
        this.panel = FormBuilder.createFormBuilder()
                .addComponent(enable)
                .addComponent(useNativeImage)
                .addComponent(nativeImageOptions)
                .addComponent(activeFormatterRow)
                // Takes the rest of the page, so the rows stay at the top whichever of them are hidden.
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
        nativeImagePath.setText(
                settings.getNativeImagePath().map(Path::toString).orElse(""));
        showPage();
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

    /**
     * The line under Use native binary, and the link next to it, which downloads the plugin's version unless that is
     * the one installed.
     */
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
            // The latest is the plugin's own version, which formats as the formatter bundled with it does.
            case Status.Current current ->
                new StatusLine(
                        "Use embedded version " + current.installed().version(), Optional.of("Update to latest"));
            case Status.Outdated outdated ->
                new StatusLine(
                        "Use embedded version " + outdated.installed().version(), Optional.of("Update to latest"));
        };
    }

    private void showPage() {
        useNativeImage.setVisible(enable.isSelected());
        nativeImageOptions.setVisible(enable.isSelected() && useNativeImage.isSelected());
        activeFormatterRow.setVisible(enable.isSelected());
        showEmbeddedNativeImage();
    }

    private void showEmbeddedNativeImage() {
        var line = statusLine(embeddedNativeImage.status(pluginVersion()));
        embeddedStatus.setText(line.text());
        embeddedAction.setText(line.action().orElse(""));
        embeddedAction.setVisible(line.action().isPresent());
        // The embedded binary can arrive while the page is open.
        showActiveFormatter();
    }

    /**
     * The formatter the page would run once applied, chosen as FormatterProvider chooses it: the path typed in Binary,
     * else the embedded binary, else the Java-based formatter.
     */
    private void showActiveFormatter() {
        var pending = new PalantirJavaFormatSettings();
        pending.setUseNativeImage(useNativeImage.isSelected());
        try {
            pending.setNativeImagePath(enteredNativeImagePath());
        } catch (ConfigurationException e) {
            // Not a path at all: shown as typed, and Apply says what is wrong with it.
            activeFormatter.setText(nativeImagePath.getText().strip());
            return;
        }
        activeFormatter.setText(
                switch (FormatterSelection.of(pending, embeddedNativeImage::installedPath)) {
                    case FormatterSelection.NativeImage nativeImage ->
                        nativeImage.path().toString();
                    case FormatterSelection.JavaBased javaBased
                    when !javaBased.nativeImageMissing() -> "Java-based formatter";
                    case FormatterSelection.JavaBased missing ->
                        embeddedNativeImage.status(pluginVersion()) instanceof Status.NoImageForPlatform
                                ? "Choose a native binary: none is published for this platform"
                                : "Need to download version " + pluginVersion() + " from Maven Central";
                });
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

    /**
     * Read-only text that wraps at the page's width and can be selected and copied: the active formatter is often a
     * path, and the embedded binary's, deep in the IDE's system directory, would otherwise widen the page past the
     * dialog.
     */
    private static JTextArea selectableText() {
        var text = new JBTextArea() {
            @Override
            public Dimension getPreferredSize() {
                // No wider than a short line: the form stretches it to the page, and once it has that width, the
                // height is that of its lines wrapped to it.
                var preferred = super.getPreferredSize();
                return new Dimension(Math.min(preferred.width, JBUI.scale(200)), preferred.height);
            }
        };
        // The height depends on the width the layout gives, which is known only after the layout.
        text.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                text.revalidate();
            }
        });
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setBorder(JBUI.Borders.empty());
        text.setFont(JBUI.Fonts.label());
        return text;
    }

}
