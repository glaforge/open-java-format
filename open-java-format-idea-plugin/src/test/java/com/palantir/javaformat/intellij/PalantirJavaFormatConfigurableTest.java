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

import com.intellij.openapi.ui.ComboBox;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture;
import com.intellij.testFramework.fixtures.JavaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import com.palantir.javaformat.intellij.PalantirJavaFormatSettings.State;
import java.awt.Component;
import java.awt.Container;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The settings page used to be assembled by the IntelliJ GUI designer, whose generated code this build never wove in:
 * every bound field stayed null, so the page rendered nothing and reset() threw as soon as it was opened. It is plain
 * Java now, and these tests fail if it goes back to a form the build does not instrument.
 */
final class PalantirJavaFormatConfigurableTest {

    private JavaCodeInsightTestFixture fixture;
    private PalantirJavaFormatSettings settings;

    @BeforeEach
    void setUp() throws Exception {
        TestFixtureBuilder<IdeaProjectTestFixture> projectBuilder = IdeaTestFixtureFactory.getFixtureFactory()
                .createLightFixtureBuilder(new DefaultLightProjectDescriptor(), getClass().getName());
        fixture = JavaTestFixtureFactory.getFixtureFactory().createCodeInsightFixture(projectBuilder.getFixture());
        fixture.setUp();
        settings = PalantirJavaFormatSettings.getInstance(fixture.getProject());
    }

    @AfterEach
    void tearDown() throws Exception {
        fixture.tearDown();
    }

    @Test
    void buildsAPanelWithItsControls() {
        PalantirJavaFormatConfigurable configurable = new PalantirJavaFormatConfigurable(fixture.getProject());

        JComponent component = requireNonNull(configurable.createComponent());

        assertThat(findCheckBox(component)).isPresent();
    }

    @Test
    void hasNoChoiceOfCodeStyle() {
        // There is one style, so the page does not offer a list of them.
        PalantirJavaFormatConfigurable configurable = new PalantirJavaFormatConfigurable(fixture.getProject());

        assertThat(findAll(requireNonNull(configurable.createComponent()), ComboBox.class))
                .isEmpty();
    }

    @Test
    void readsAndWritesTheEnabledSetting() throws Throwable {
        State disabled = new State();
        disabled.setEnabled("false");
        settings.loadState(disabled);

        PalantirJavaFormatConfigurable configurable = new PalantirJavaFormatConfigurable(fixture.getProject());

        EdtTestUtil.runInEdtAndWait(() -> {
            JCheckBox enable = findCheckBox(requireNonNull(configurable.createComponent()))
                    .orElseThrow();

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

    private static Optional<JCheckBox> findCheckBox(Component root) {
        return findAll(root, JCheckBox.class).stream().findFirst();
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
