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

import java.io.File;
import java.net.URI;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Writes the developer's local .idea configuration; nothing to cache")
public abstract class UpdatePalantirJavaFormatIdeaXmlFile extends DefaultTask {

    /**
     * The jars a build declared for the formatter. Most builds declare none, and the IDE then gets the formatter inside
     * the plugin.
     */
    @InputFiles
    @Classpath
    public abstract ConfigurableFileCollection getImplementationConfig();

    /** The hash of the formatter inside the plugin, which names the directory its jars are extracted into. */
    @Input
    public abstract Property<String> getBundledFormatter();

    /** Where the formatter inside the plugin is extracted to. */
    @Internal
    public abstract DirectoryProperty getGradleUserHome();

    public UpdatePalantirJavaFormatIdeaXmlFile() {
        // The IDE loads the jars from where the XML says: while they are gone, the task is not up to date.
        getOutputs().upToDateWhen(new BundledFormatterIsExtracted());
    }

    @org.gradle.api.tasks.Optional
    @OutputFile
    public abstract RegularFileProperty getXmlOutputFile();

    @TaskAction
    public final void updateXml() {
        List<URI> uris = BundledFormatter.jarsUnlessOverridden(
                        getImplementationConfig().getFiles(),
                        getGradleUserHome().get().getAsFile())
                .stream()
                .map(File::toURI)
                .toList();
        XmlUtils.updateIdeaXmlFile(
                getXmlOutputFile().getAsFile().get(),
                node -> ConfigureJavaFormatterXml.configureJavaFormat(node, uris));
    }

    /** A class, not a lambda: the configuration cache stores it with the task. */
    private static final class BundledFormatterIsExtracted implements Spec<Task> {
        @Override
        public boolean isSatisfiedBy(Task task) {
            var xml = (UpdatePalantirJavaFormatIdeaXmlFile) task;
            return !xml.getImplementationConfig().isEmpty()
                    || BundledFormatter.isExtracted(
                            xml.getGradleUserHome().get().getAsFile());
        }
    }
}
