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

import com.palantir.javaformat.intellij.FormatterSelection.JavaBased;
import com.palantir.javaformat.intellij.FormatterSelection.NativeImage;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class FormatterSelectionTest {

    private static final Path CUSTOM = Path.of("/opt/open-java-format");
    private static final Path EMBEDDED = Path.of("/ide/system/open-java-format/native/21.0.1/open-java-format");

    private final PalantirJavaFormatSettings settings = new PalantirJavaFormatSettings();

    @Test
    void runsTheBinaryTheUserChose() {
        settings.setUseNativeImage(true);
        settings.setNativeImagePath(Optional.of(CUSTOM));

        assertThat(FormatterSelection.of(settings, () -> Optional.of(EMBEDDED)))
                .isEqualTo(new NativeImage(CUSTOM, false));
    }

    @Test
    void runsTheEmbeddedBinaryWithoutAPathOfTheUsersOwn() {
        settings.setUseNativeImage(true);

        assertThat(FormatterSelection.of(settings, () -> Optional.of(EMBEDDED)))
                .isEqualTo(new NativeImage(EMBEDDED, true));
    }

    @Test
    void fallsBackToTheJavaBasedFormatterWithoutABinary() {
        settings.setUseNativeImage(true);

        assertThat(FormatterSelection.of(settings, Optional::empty)).isEqualTo(new JavaBased(true));
    }

    @Test
    void runsTheJavaBasedFormatterUnlessTheNativeOneIsOn() {
        settings.setNativeImagePath(Optional.of(CUSTOM));

        assertThat(FormatterSelection.of(settings, () -> Optional.of(EMBEDDED))).isEqualTo(new JavaBased(false));
    }

    @Test
    void looksForTheEmbeddedBinaryOnlyWhenItWouldRun() {
        // It is asked for every file formatted, and most projects run the Java-based formatter.
        Supplier<Optional<Path>> unasked = () -> {
            throw new AssertionError("the embedded binary was looked for");
        };
        settings.setNativeImagePath(Optional.of(CUSTOM));

        assertThat(FormatterSelection.of(settings, unasked)).isEqualTo(new JavaBased(false));
        settings.setUseNativeImage(true);
        assertThat(FormatterSelection.of(settings, unasked)).isEqualTo(new NativeImage(CUSTOM, false));
    }
}
