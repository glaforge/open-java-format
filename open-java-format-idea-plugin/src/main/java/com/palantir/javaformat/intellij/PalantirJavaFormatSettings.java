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

import com.intellij.formatting.service.FormattingService;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.project.Project;
import com.palantir.javaformat.java.FormatterService;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

@SuppressWarnings("for-rollout:SameNameButDifferent")
@State(
        name = "PalantirJavaFormatSettings",
        storages = {@Storage("open-java-format.xml")})
public class PalantirJavaFormatSettings implements PersistentStateComponent<PalantirJavaFormatSettings.State> {

    @SuppressWarnings("for-rollout:SameNameButDifferent")
    private State state = new State();

    static PalantirJavaFormatSettings getInstance(Project project) {
        return project.getService(PalantirJavaFormatSettings.class);
    }

    @SuppressWarnings("for-rollout:SameNameButDifferent")
    @Nullable
    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@SuppressWarnings("for-rollout:SameNameButDifferent") State state) {
        this.state = state;
    }

    boolean isEnabled() {
        return state.enabled.equals(EnabledState.ENABLED);
    }

    void setEnabled(boolean enabled) {
        setEnabled(enabled ? EnabledState.ENABLED : EnabledState.DISABLED);
    }

    void setEnabled(EnabledState enabled) {
        state.enabled = enabled;
    }

    boolean isUninitialized() {
        return state.enabled.equals(EnabledState.UNKNOWN);
    }

    /**
     * The paths to jars that provide an alternative implementation of the formatter. If set, this implementation will
     * be used instead of the bundled version.
     */
    Optional<List<URI>> getImplementationClassPath() {
        return state.implementationClassPath;
    }

    /** Whether the native binary formats this project rather than the Java-based formatter. */
    boolean useNativeImage() {
        return state.useNativeImage;
    }

    void setUseNativeImage(boolean useNativeImage) {
        state.useNativeImage = useNativeImage;
    }

    /** A native binary of the user's own, run instead of the embedded one. */
    Optional<Path> getNativeImagePath() {
        return Optional.ofNullable(state.nativeImagePath)
                .filter(path -> !path.isBlank())
                .map(Path::of);
    }

    void setNativeImagePath(Optional<Path> nativeImagePath) {
        state.nativeImagePath = nativeImagePath.map(Path::toString).orElse(null);
    }

    boolean injectedVersionIsOutdated() {
        Optional<FormatterVersion> formatterVersion = computeFormatterVersion().flatMap(FormatterVersion::parse);
        Optional<FormatterVersion> implementationVersion =
                getImplementationVersion().flatMap(FormatterVersion::parse);

        if (formatterVersion.isEmpty() || implementationVersion.isEmpty()) {
            return true;
        }

        return formatterVersion.get().compareTo(implementationVersion.get()) < 0;
    }

    Optional<String> getImplementationVersion() {
        return ofNullable(FormattingService.EP_NAME.findExtension(PalantirJavaFormatFormattingService.class))
                .flatMap(PalantirJavaFormatFormattingService::getPluginDescriptor)
                .map(PluginDescriptor::getVersion);
    }

    Optional<String> computeFormatterVersion() {
        return getImplementationClassPath()
                .map(classpath -> classpath.stream()
                        .flatMap(uri -> {
                            try {
                                JarFile jar = new JarFile(uri.getPath());
                                // Identify the implementation jar by the service it produces.
                                if (jar.getEntry("META-INF/services/" + FormatterService.class.getName()) != null) {
                                    String implementationVersion = jar.getManifest()
                                            .getMainAttributes()
                                            .getValue("Implementation-Version");
                                    return Stream.of(implementationVersion);
                                }
                                return Stream.empty();
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        })
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Couldn't find implementation JAR")));
    }

    enum EnabledState {
        UNKNOWN,
        ENABLED,
        DISABLED
    }

    static class State {

        private EnabledState enabled = EnabledState.UNKNOWN;
        private Optional<List<URI>> implementationClassPath = Optional.empty();

        public boolean useNativeImage = false;

        @Nullable
        public String nativeImagePath;

        public void setImplementationClassPath(@Nullable List<String> value) {
            implementationClassPath = Optional.ofNullable(value)
                    .map(strings -> strings.stream().map(URI::create).collect(Collectors.toList()));
        }

        @SuppressWarnings("for-rollout:NullAway")
        public List<String> getImplementationClassPath() {
            return implementationClassPath
                    .map(paths -> paths.stream().map(URI::toString).collect(Collectors.toList()))
                    .orElse(null);
        }

        // enabled used to be a boolean so we use bean property methods for backwards compatibility
        public void setEnabled(@Nullable String enabledStr) {
            if (enabledStr == null) {
                enabled = EnabledState.UNKNOWN;
            } else if (Boolean.valueOf(enabledStr)) {
                enabled = EnabledState.ENABLED;
            } else {
                enabled = EnabledState.DISABLED;
            }
        }

        @SuppressWarnings("for-rollout:NullAway")
        public String getEnabled() {
            return switch (enabled) {
                case ENABLED -> "true";
                case DISABLED -> "false";
                default -> null;
            };
        }

        @Override
        public String toString() {
            return "PalantirJavaFormatSettings{"
                    + "enabled="
                    + enabled
                    + ", implementationClassPath="
                    + implementationClassPath
                    + ", useNativeImage="
                    + useNativeImage
                    + ", nativeImagePath="
                    + nativeImagePath
                    + '}';
        }
    }
}
