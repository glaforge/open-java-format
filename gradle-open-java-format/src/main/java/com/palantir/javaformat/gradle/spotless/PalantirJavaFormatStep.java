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
import com.palantir.javaformat.gradle.BundledFormatter;
import com.palantir.javaformat.gradle.FormatterServiceLoader;
import com.palantir.javaformat.java.FormatterService;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import org.gradle.api.artifacts.Configuration;

public final class PalantirJavaFormatStep {

    private static final String IMPL_CLASS = "com.palantir.javaformat.java.Formatter";

    private PalantirJavaFormatStep() {}

    private static final String NAME = "open-java-format";

    /**
     * Creates a step which formats everything - code, import order, and unused imports - with the formatter inside the
     * plugin, or with the jars in {@code formatterClasspath} if the build declared any. A configuration of the project
     * the Spotless task belongs to: resolving another project's needs that project's lock, which a parallel build does
     * not hold. The plugin's own formatter is extracted under {@code gradleUserHome}.
     */
    public static FormatterStep create(Configuration formatterClasspath, File gradleUserHome) {
        ensureImplementationNotDirectlyLoadable();
        // Spotless keeps a step in two forms, and neither can hold the configuration or a loaded formatter, which do
        // not serialize. The configuration cache stores the declared jars, resolved when the entry is written at the
        // earliest. The up-to-date check compares the signature of the jars the step formats with, so that another
        // formatter makes the task run again; Spotless takes it when the task runs. The formatter is loaded from the
        // jars on first use.
        return FormatterStep.createLazy(
                NAME,
                () -> new Jars(List.copyOf(formatterClasspath.getFiles()), gradleUserHome),
                Jars::sign,
                PalantirJavaFormatStep::createFormat);
    }

    /**
     * The jars as the configuration cache stores them: those the build declared, or none, for the formatter inside the
     * plugin. That one's jars are found, or extracted again, when the step is signed: the directory they were extracted
     * into may be gone by the time a build reuses the entry.
     */
    static final class Jars implements Serializable {
        private static final long serialVersionUID = 1L;

        private final List<File> override;
        private final File gradleUserHome;

        Jars(List<File> override, File gradleUserHome) {
            this.override = override;
            this.gradleUserHome = gradleUserHome;
        }

        /** By name, size and content, not path: the build cache key holds for another directory and machine. */
        FileSignature sign() throws IOException {
            return FileSignature.signAsList(BundledFormatter.jarsUnlessOverridden(override, gradleUserHome));
        }
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
