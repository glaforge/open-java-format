/*
 * (c) Copyright 2025 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.javaformat.gradle;

import com.google.common.base.Preconditions;
import java.util.Collections;
import javax.inject.Inject;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;

public abstract class NativeImageFormatProviderPlugin implements Plugin<Project> {

    @Inject
    protected abstract ProviderFactory getProviderFactory();

    static final String NATIVE_CONFIGURATION_NAME = "palantirJavaFormatNative";

    @Override
    public void apply(Project rootProject) {
        Preconditions.checkState(
                rootProject == rootProject.getRootProject(),
                "May only apply dev.openjavaformat.java-format-provider to the root project");

        Provider<NativePlatform> platform = NativePlatform.current(getProviderFactory());
        String implementationVersion = JavaFormatExtension.class.getPackage().getImplementationVersion();
        var nativeImage = rootProject.getConfigurations().register(NATIVE_CONFIGURATION_NAME, conf -> {
            conf.setDescription("Internal configuration for resolving the open-java-format native image");
            conf.setCanBeConsumed(false);
            conf.setCanBeResolved(true);
            conf.defaultDependencies(deps -> {
                deps.addAllLater(platform.map(p -> Collections.singletonList(rootProject
                        .getDependencies()
                        .create(String.format(
                                "dev.openjavaformat:open-java-format-native:%s:%s@%s",
                                implementationVersion, p.classifier(), p.extension())))));
            });
        });
        // Resolved in each project that formats, through its own repositories; formatDiff copies the image it gets
        // into open-java-format-caches, executable, and runs it from there.
        FormatterClasspath.offerNativeImage(rootProject, nativeImage);
    }
}
