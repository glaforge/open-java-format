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

import com.palantir.javaformat.bootstrap.NativeImageFormatterService;
import java.io.File;
import java.io.IOException;
import org.gradle.api.DefaultTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

public abstract class PalantirJavaFormatPlugin implements Plugin<Project> {

    @Nested
    protected abstract NativeImageSupport getNativeImageSupport();

    @Override
    public void apply(Project project) {
        project.getRootProject().getPlugins().apply(PalantirJavaFormatProviderPlugin.class);
        project.getRootProject().getPlugins().apply(NativeImageFormatProviderPlugin.class);
        project.getRootProject().getPlugins().apply(PalantirJavaFormatIdeaPlugin.class);

        project.getPlugins().apply(PalantirJavaFormatSpotlessPlugin.class);

        project.getPlugins().withId("java", p -> {
            var formatterClasspath = FormatterClasspath.of(project);

            // TODO(dfox): in the future we may want to offer a simple 'format' task so people don't need to use
            // spotless to try out our formatter
            project.getTasks().register("formatDiff", FormatDiffTask.class, task -> {
                task.getProjectDirectory().set(project.getLayout().getProjectDirectory());
                if (getNativeImageSupport().isNativeImageConfigured()) {
                    task.getNativeImage().fileProvider(getNativeImplConfiguration(project));
                } else {
                    task.getFormatterClasspath().from(formatterClasspath);
                    task.getGradleUserHome().set(project.getGradle().getGradleUserHomeDir());
                }
            });
        });
    }

    private static Provider<File> getNativeImplConfiguration(Project project) {
        return (project.getRootProject()
                .getConfigurations()
                .named(NativeImageFormatProviderPlugin.NATIVE_CONFIGURATION_NAME)
                .map(FileCollection::getSingleFile));
    }

    @DisableCachingByDefault(
            because = "Reformats whatever the working tree currently differs by; there is no reusable output")
    public abstract static class FormatDiffTask extends DefaultTask {

        private static Logger log = Logging.getLogger(FormatDiffTask.class);

        @org.gradle.api.tasks.Optional
        @InputFile
        @PathSensitive(PathSensitivity.NAME_ONLY)
        abstract RegularFileProperty getNativeImage();

        /**
         * The jars a build declared for the Java-based formatter, which runs when the native image is not configured.
         * Most builds declare none, and the task then runs the one inside the plugin.
         */
        @Classpath
        abstract ConfigurableFileCollection getFormatterClasspath();

        /** Where the formatter inside the plugin is extracted to. */
        @Internal
        abstract DirectoryProperty getGradleUserHome();

        /**
         * The project whose changed lines are formatted. The task reads it here rather than from {@code getProject()},
         * which a task must not call when it runs with the configuration cache.
         */
        @Internal
        abstract DirectoryProperty getProjectDirectory();

        public FormatDiffTask() {
            setDescription("Format only chunks of files that appear in git diff");
            setGroup("Formatting");
        }

        @TaskAction
        public final void formatDiff() throws IOException, InterruptedException {
            var projectDir = getProjectDirectory().get().getAsFile().toPath();
            if (getNativeImage().isPresent()) {
                log.info("Using the native-image formatter");
                FormatDiff.formatDiff(
                        projectDir,
                        new NativeImageFormatterService(
                                getNativeImage().get().getAsFile().toPath()));
            } else {
                log.info("Using the Java-based formatter");
                FormatDiff.formatDiff(
                        projectDir,
                        FormatterServiceLoader.load(BundledFormatter.jarsUnlessOverridden(
                                getFormatterClasspath().getFiles(),
                                getGradleUserHome().get().getAsFile())));
            }
        }
    }
}
