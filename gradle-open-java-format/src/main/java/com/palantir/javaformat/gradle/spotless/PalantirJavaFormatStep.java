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
        // earliest. The up-to-date check compares the step's signature, so that another formatter makes the task run
        // again; Spotless takes it when the task runs, and stores it in the configuration cache entry too. The
        // formatter is loaded from the jars on first use, after the plugin's own are extracted if need be.
        return FormatterStep.createLazy(
                NAME,
                () -> new Jars(List.copyOf(formatterClasspath.getFiles()), gradleUserHome),
                Jars::sign,
                PalantirJavaFormatStep::createFormat);
    }

    /** The jars as the configuration cache stores them: those the build declared, or none, for the plugin's own. */
    static final class Jars implements Serializable {
        private static final long serialVersionUID = 1L;

        private final List<File> override;
        private final File gradleUserHome;

        Jars(List<File> override, File gradleUserHome) {
            this.override = override;
            this.gradleUserHome = gradleUserHome;
        }

        Signature sign() throws IOException {
            return new Signature(
                    override.isEmpty() ? BundledFormatter.hash() : "",
                    FileSignature.signAsList(override),
                    gradleUserHome);
        }
    }

    /**
     * What the up-to-date check and the build cache key compare, never a path, so that the key holds for another
     * directory and machine. The jars a build declared go by name, size and content. The plugin's own go by the hash of
     * their list, which holds every jar's sha256: nothing on disk is read, so storing a configuration cache entry does
     * not depend on whether the jars were extracted, and a build after they were deleted still reuses the entry.
     */
    static final class Signature implements Serializable {
        private static final long serialVersionUID = 1L;

        // Spotless compares the signature in its serialized form, and reads nothing out of it.
        @SuppressWarnings("UnusedVariable")
        private final String bundled;

        private final FileSignature override;

        /** Where to extract the plugin's own jars. Spotless formats with a signature it made, never one it read back. */
        private final transient File gradleUserHome;

        Signature(String bundled, FileSignature override, File gradleUserHome) {
            this.bundled = bundled;
            this.override = override;
            this.gradleUserHome = gradleUserHome;
        }

        List<File> jars() {
            return BundledFormatter.jarsUnlessOverridden(override.files(), gradleUserHome);
        }
    }

    private static FormatterFunc createFormat(Signature signature) {
        FormatterService formatter = FormatterServiceLoader.load(signature.jars());
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
