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

import com.intellij.openapi.util.JDOMUtil;
import com.intellij.util.xmlb.XmlSerializer;
import com.palantir.javaformat.intellij.PalantirJavaFormatSettings.State;
import java.nio.file.Path;
import java.util.Optional;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

/** The settings as .idea/open-java-format.xml holds them, which files written by older plugins have to keep loading. */
final class PalantirJavaFormatSettingsTest {

    @Test
    void loadsAFileThatStillHasTheCodeStyle() throws Exception {
        // The plugin used to offer a choice of one style and store it.
        var state = deserialize("""
            <component name="PalantirJavaFormatSettings">
              <option name="enabled" value="true" />
              <option name="style" value="OJF" />
            </component>
            """);

        assertThat(state.getEnabled()).isEqualTo("true");
    }

    @Test
    void doesNotWriteACodeStyle() {
        var xml = JDOMUtil.write(XmlSerializer.serialize(new State()));

        assertThat(xml).doesNotContain("style");
    }

    @Test
    void keepsTheNativeBinarySettings() throws Exception {
        var settings = new PalantirJavaFormatSettings();
        settings.setUseNativeImage(true);
        settings.setNativeImagePath(Optional.of(Path.of("/opt/open-java-format")));

        var reloaded = new PalantirJavaFormatSettings();
        reloaded.loadState(deserialize(JDOMUtil.write(XmlSerializer.serialize(settings.getState()))));

        assertThat(reloaded.useNativeImage()).isTrue();
        assertThat(reloaded.getNativeImagePath()).hasValue(Path.of("/opt/open-java-format"));
    }

    @Test
    void ignoresTheNativeImageTheGradlePluginUsedToWrite() throws Exception {
        // Gradle plugins up to 21.0.1 wrote this for openjavaformat.native.formatter=true. The native binary is a
        // setting of the IDE now, and a build no longer switches it on.
        var settings = new PalantirJavaFormatSettings();
        settings.loadState(deserialize("""
            <component name="PalantirJavaFormatSettings">
              <option name="enabled" value="true" />
              <option name="nativeImageClassPath" value="file:/home/me/.gradle/open-java-format-caches/native" />
            </component>
            """));

        assertThat(settings.useNativeImage()).isFalse();
        assertThat(settings.getNativeImagePath()).isEmpty();
    }

    @Test
    void takesABlankPathForNone() {
        var state = new State();
        state.nativeImagePath = " ";
        var settings = new PalantirJavaFormatSettings();
        settings.loadState(state);

        assertThat(settings.getNativeImagePath()).isEmpty();
    }

    private static State deserialize(@Language("xml") String xml) throws Exception {
        return XmlSerializer.deserialize(JDOMUtil.load(xml), State.class);
    }
}
