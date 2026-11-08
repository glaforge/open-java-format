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
package com.palantir.javaformat.gradle.spotless;

import com.diffplug.spotless.FileSignature;
import com.diffplug.spotless.FormatterFunc;
import com.diffplug.spotless.FormatterStep;
import com.palantir.javaformat.gradle.FormatterServiceLoader;
import com.palantir.javaformat.java.FormatterService;
import org.gradle.api.artifacts.Configuration;

public final class PalantirJavaFormatStep {

    private static final String IMPL_CLASS = "com.palantir.javaformat.java.Formatter";

    private PalantirJavaFormatStep() {}

    private static final String NAME = "open-java-format";

    /** Creates a step which formats everything - code, import order, and unused imports. */
    public static FormatterStep create(Configuration palantirJavaFormat) {
        ensureImplementationNotDirectlyLoadable();
        // Spotless keeps a step in two forms, and neither can hold the configuration or a loaded formatter, which do
        // not serialize. The configuration cache stores the jars, resolved when the entry is written at the earliest.
        // The up-to-date check compares their signature, so that another formatter version makes the task run again;
        // Spotless takes it when the task runs, or with the configuration cache when the entry is written. The
        // formatter is loaded from the jars on first use.
        return FormatterStep.createLazy(
                NAME,
                () -> FileSignature.promise(palantirJavaFormat.getFiles()),
                FileSignature.Promised::get,
                PalantirJavaFormatStep::createFormat);
    }

    private static FormatterFunc createFormat(FileSignature jars) {
        FormatterService formatter = FormatterServiceLoader.load(jars.files());
        return formatter::formatSourceReflowStringsAndFixImports;
    }

    private static void ensureImplementationNotDirectlyLoadable() {
        try {
            PalantirJavaFormatStep.class.getClassLoader().loadClass(IMPL_CLASS);
        } catch (ClassNotFoundException e) {
            // expected
            return;
        }
        throw new RuntimeException("Expected not be be able to load "
                + IMPL_CLASS
                + " via main class loader but was able to. Please ensure that `buildscript.configurations.classpath`"
                + " doesn't depend on `dev.openjavaformat:open-java-format`.");
    }
}
