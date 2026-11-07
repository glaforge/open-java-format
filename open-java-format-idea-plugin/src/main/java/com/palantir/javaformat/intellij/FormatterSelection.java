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

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Which formatter a project's settings run. FormatterProvider starts it, and the settings page shows it as the
 * formatter type, so the page cannot claim one formatter while another runs.
 */
sealed interface FormatterSelection {

    /**
     * A path of the user's own wins over the embedded binary. Without either, the Java-based formatter runs even with
     * the native one switched on: the embedded binary may not be downloaded yet. The embedded binary is looked for
     * only when it would run, since that lists a directory and this runs for every file formatted.
     */
    static FormatterSelection of(PalantirJavaFormatSettings settings, Supplier<Optional<Path>> embeddedNativeImage) {
        if (!settings.useNativeImage()) {
            return new JavaBased(false);
        }
        return settings.getNativeImagePath()
                .<FormatterSelection>map(path -> new NativeImage(path, false))
                .or(() -> embeddedNativeImage.get().map(path -> new NativeImage(path, true)))
                .orElseGet(() -> new JavaBased(true));
    }

    String describe();

    record NativeImage(Path path, boolean embedded) implements FormatterSelection {
        @Override
        public String describe() {
            return "Native binary, " + (embedded ? "embedded" : "custom") + " (" + path + ")";
        }
    }

    /** nativeImageMissing: the native formatter is switched on, but there is no binary to run. */
    record JavaBased(boolean nativeImageMissing) implements FormatterSelection {
        @Override
        public String describe() {
            return nativeImageMissing
                    ? "Java-based formatter: the native binary is not downloaded"
                    : "Java-based formatter";
        }
    }
}
