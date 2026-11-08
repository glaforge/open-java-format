/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
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

import com.google.common.base.Suppliers;
import com.palantir.javaformat.java.FormatterService;
import java.io.File;
import java.util.function.Supplier;
import org.gradle.api.artifacts.Configuration;

public class JavaFormatExtension {
    private final Supplier<FormatterService> memoizedService;

    /** The formatter in {@code configuration} if the build declared one there, else the one inside the plugin. */
    public JavaFormatExtension(Configuration configuration, File gradleUserHome) {
        this.memoizedService = Suppliers.memoize(() -> FormatterServiceLoader.load(
                BundledFormatter.jarsUnlessOverridden(configuration.getFiles(), gradleUserHome)));
    }

    public FormatterService serviceLoad() {
        return memoizedService.get();
    }
}
