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

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture;
import com.intellij.testFramework.fixtures.JavaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import com.intellij.ui.components.ActionLink;
import com.palantir.javaformat.intellij.EmbeddedNativeImage.Installed;
import com.palantir.javaformat.intellij.EmbeddedNativeImage.Status;
import com.palantir.javaformat.intellij.PalantirJavaFormatConfigurable.StatusLine;
import com.palantir.javaformat.intellij.PalantirJavaFormatSettings.State;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The settings page used to be assembled by the IntelliJ GUI designer, whose generated code this build never wove in:
 * every bound field stayed null, so the page rendered nothing and reset() threw as soon as it was opened. It is plain
 * Java now, and these tests fail if it goes back to a form the build does not instrument.
 */
final class PalantirJavaFormatConfigurableTest {

    @TempDir
    private Path dir;

    private JavaCodeInsightTestFixture fixture;
    private PalantirJavaFormatSettings settings;
    private PalantirJavaFormatConfigurable configurable;
    private JComponent component;

    @BeforeEach
    void setUp() throws Exception {
        TestFixtureBuilder<IdeaProjectTestFixture> projectBuilder = IdeaTestFixtureFactory.getFixtureFactory()
                .createLightFixtureBuilder(new DefaultLightProjectDescriptor(), getClass().getName());
        fixture = JavaTestFixtureFactory.getFixtureFactory().createCodeInsightFixture(projectBuilder.getFixture());
        fixture.setUp();
        settings = PalantirJavaFormatSettings.getInstance(fixture.getProject());
        settings.loadState(new State());
        // Nothing downloaded, and nowhere to download from.
        var embedded = new EmbeddedNativeImage(
                dir.resolve("native"), Optional.of(new NativePlatform("macos", "aarch64")), "http://127.0.0.1:1");
        configurable = new PalantirJavaFormatConfigurable(fixture.getProject(), embedded);
        component = requireNonNull(configurable.createComponent());
    }

    @AfterEach
    void tearDown() throws Exception {
        configurable.disposeUIResources();
        fixture.tearDown();
    }

    @Test
    void buildsAPanelWithItsControls() {
        assertThat(findCheckBox("Enable open-java-format")).isNotNull();
        assertThat(findCheckBox("Use native binary")).isNotNull();
        assertThat(findAll(component, TextFieldWithBrowseButton.class)).hasSize(1);
    }

    @Test
    void hasNoChoiceOfCodeStyle() {
        // There is one style, so the page does not offer a list of them.
        assertThat(findAll(component, ComboBox.class)).isEmpty();
    }

    @Test
    void readsAndWritesTheEnabledSetting() throws Throwable {
        State disabled = new State();
        disabled.setEnabled("false");
        settings.loadState(disabled);

        EdtTestUtil.runInEdtAndWait(() -> {
            JCheckBox enable = findCheckBox("Enable open-java-format");

            configurable.reset();
            assertThat(enable.isSelected()).isFalse();
            assertThat(configurable.isModified()).isFalse();

            enable.setSelected(true);
            assertThat(configurable.isModified()).isTrue();
            configurable.apply();
        });

        assertThat(settings.isEnabled()).isTrue();
    }

    @Test
    void showsTheVersionOfThePlugin() {
        // The "Plugin version" row: the version the platform read from the plugin's own descriptor, not "unknown".
        // Whatever the build stamped: CI checks out without tags, so there it is a commit hash rather than a
        // release-like version.
        assertThat(settings.getImplementationVersion())
                .hasValueSatisfying(version -> assertThat(version).isNotBlank());
    }

    @Test
    void showsTheNativeOptionsOnlyWithTheNativeBinaryOn() throws Throwable {
        EdtTestUtil.runInEdtAndWait(() -> {
            var useNativeImage = findCheckBox("Use native binary");
            var path = findAll(component, TextFieldWithBrowseButton.class).get(0);

            configurable.reset();
            assertThat(isDisplayed(path)).isFalse();

            useNativeImage.setSelected(true);
            assertThat(isDisplayed(path)).isTrue();
        });
    }

    @Test
    void readsAndWritesTheNativeBinarySettings() throws Throwable {
        var binary = executable("open-java-format");
        settings.setUseNativeImage(true);

        EdtTestUtil.runInEdtAndWait(() -> {
            var path = findAll(component, TextFieldWithBrowseButton.class).get(0);

            configurable.reset();
            assertThat(findCheckBox("Use native binary").isSelected()).isTrue();
            assertThat(path.getText()).isEmpty();
            assertThat(configurable.isModified()).isFalse();

            path.setText(binary.toString());
            assertThat(configurable.isModified()).isTrue();
            configurable.apply();
        });

        assertThat(settings.useNativeImage()).isTrue();
        assertThat(settings.getNativeImagePath()).hasValue(binary);
    }

    @Test
    void refusesTheNativeBinaryWithoutABinary() throws Throwable {
        EdtTestUtil.runInEdtAndWait(() -> {
            configurable.reset();
            findCheckBox("Use native binary").setSelected(true);

            assertThatThrownBy(configurable::apply)
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessage("Download the embedded version or choose a binary");
        });

        assertThat(settings.useNativeImage()).isFalse();
    }

    @Test
    void refusesAPathThatIsNotAnExecutableFile() throws Throwable {
        var missing = dir.resolve("missing");

        EdtTestUtil.runInEdtAndWait(() -> {
            configurable.reset();
            findCheckBox("Use native binary").setSelected(true);
            findAll(component, TextFieldWithBrowseButton.class).get(0).setText(missing.toString());

            assertThatThrownBy(configurable::apply)
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessage(missing + " is not an executable file");
        });
    }

    @Test
    void offersADownloadBeforeTheFirstOne() throws Throwable {
        EdtTestUtil.runInEdtAndWait(() -> {
            configurable.reset();

            assertThat(findAll(component, JLabel.class))
                    .extracting(JLabel::getText)
                    .contains("No embedded version.");
            assertThat(findAll(component, ActionLink.class)).singleElement().satisfies(link -> {
                assertThat(link.getText()).isEqualTo("Download");
                assertThat(link.isVisible()).isTrue();
            });
        });
    }

    @Test
    void wrapsTheFormatterTypeAndLetsItsPathBeCopied() throws Throwable {
        // The embedded binary lives deep in the IDE's system directory: on one line its path made the page wider than
        // the dialog, and a label would not let anyone copy it. A long path of one's own in Binary must not either.
        var path = Path.of("/Users/someone/Library/Caches/JetBrains/IntelliJIdea2026.2/open-java-format/native/"
                + "21.0.1/".repeat(40) + "open-java-format-macos_aarch64");
        settings.setUseNativeImage(true);
        settings.setNativeImagePath(Optional.of(path));

        EdtTestUtil.runInEdtAndWait(() -> {
            configurable.reset();

            var formatterType = findAll(component, JTextArea.class).stream()
                    .filter(text -> text.getText().contains(path.toString()))
                    .findFirst()
                    .orElseThrow();
            assertThat(formatterType.isEditable()).isFalse();
            assertThat(formatterType.getLineWrap()).isTrue();
            assertThat(component.getPreferredSize().width).isLessThan(1000);

            // Laid out at a dialog's width, the path takes several lines rather than one cut off.
            component.setSize(600, 800);
            layOut(component);
            layOut(component);
            var line = formatterType.getFontMetrics(formatterType.getFont()).getHeight();
            assertThat(formatterType.getHeight()).isGreaterThan(2 * line);
        });
    }

    @Test
    void describesEveryStateOfTheEmbeddedBinary() {
        var installed = new Installed("21.0.1", Path.of("/system/open-java-format/native/21.0.1/open-java-format"));

        assertThat(PalantirJavaFormatConfigurable.statusLine(new Status.NotDownloaded()))
                .isEqualTo(new StatusLine("No embedded version.", Optional.of("Download")));
        assertThat(PalantirJavaFormatConfigurable.statusLine(new Status.Downloading("21.0.2")))
                .isEqualTo(new StatusLine("Downloading embedded version 21.0.2…", Optional.empty()));
        assertThat(PalantirJavaFormatConfigurable.statusLine(new Status.DownloadFailed("21.0.2", "connection refused")))
                .isEqualTo(new StatusLine("Download failed: connection refused.", Optional.of("Retry")));
        assertThat(PalantirJavaFormatConfigurable.statusLine(new Status.Current(installed)))
                .isEqualTo(new StatusLine("Use embedded version 21.0.1", Optional.empty()));
        assertThat(PalantirJavaFormatConfigurable.statusLine(new Status.Outdated(installed, "21.0.2")))
                .isEqualTo(new StatusLine("Use embedded version 21.0.1.", Optional.of("Update to 21.0.2")));
        assertThat(PalantirJavaFormatConfigurable.statusLine(new Status.NoImageForPlatform()))
                .satisfies(line -> {
                    assertThat(line.text()).startsWith("No native binary is published for ");
                    assertThat(line.action()).isEmpty();
                });
    }

    private JCheckBox findCheckBox(String text) {
        return findAll(component, JCheckBox.class).stream()
                .filter(checkBox -> checkBox.getText().equals(text))
                .findFirst()
                .orElseThrow();
    }

    /** Whether the component and every container up to the page are visible. */
    private boolean isDisplayed(Component target) {
        for (var current = target; current != null && current != component; current = current.getParent()) {
            if (!current.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private Path executable(String name) throws IOException {
        var binary = Files.writeString(dir.resolve(name), "#!/bin/sh\n");
        assertThat(binary.toFile().setExecutable(true)).isTrue();
        return binary;
    }

    private static void layOut(Component root) {
        if (root instanceof Container container) {
            container.doLayout();
            Arrays.stream(container.getComponents()).forEach(PalantirJavaFormatConfigurableTest::layOut);
        }
    }

    private static <T extends Component> List<T> findAll(Component root, Class<T> type) {
        if (type.isInstance(root)) {
            return List.of(type.cast(root));
        }
        if (root instanceof Container container) {
            return Arrays.stream(container.getComponents())
                    .flatMap(child -> findAll(child, type).stream())
                    .toList();
        }
        return List.of();
    }
}
