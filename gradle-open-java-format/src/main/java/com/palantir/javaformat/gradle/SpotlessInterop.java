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

import com.diffplug.gradle.spotless.JavaExtension;
import com.diffplug.spotless.FormatterStep;
import com.palantir.javaformat.gradle.spotless.PalantirJavaFormatStep;
import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import javax.inject.Inject;
import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.SourceSetContainer;

/**
 * Class that exists only to encapsulate accessing spotless classes, so that Gradle can generate a decorated class for
 * {@link com.palantir.javaformat.gradle.PalantirJavaFormatSpotlessPlugin} even if spotless is not on the classpath.
 */
public abstract class SpotlessInterop implements Action<JavaExtension> {
    private static final Logger logger = Logging.getLogger(SpotlessInterop.class);

    // Generated source directories, which are not formatted: a file is in one when its path in the project directory
    // matches **/build/generated*/**, **/src/generated*/**, **/generated_*src/**, **/generated_*Src/** or
    // **/groovy-dsl-plugins/**.
    // Note: We cannot simply exclude **/build/**/* because some repos might contain a build directory
    // within the src sourceset that should be formatted.
    // build/groovy-dsl-plugins contains Java wrapper classes for
    // https://docs.gradle.org/9.3.1/userguide/implementing_gradle_plugins_convention.html
    // and don't need to be formatted.
    private static final Pattern GENERATED = Pattern.compile(
            "(.*/)?(build/generated[^/]*|src/generated[^/]*|generated_[^/]*src|generated_[^/]*Src|groovy-dsl-plugins)/.+");

    private final File projectDir;
    private final SourceSetContainer sourceSets;
    private final NamedDomainObjectProvider<Configuration> formatterClasspath;

    @Inject
    protected abstract ObjectFactory getObjects();

    @Inject
    public SpotlessInterop(
            File projectDir,
            SourceSetContainer sourceSets,
            NamedDomainObjectProvider<Configuration> formatterClasspath) {
        this.projectDir = projectDir;
        this.sourceSets = sourceSets;
        this.formatterClasspath = formatterClasspath;
    }

    @Override
    public void execute(JavaExtension java) {
        java.targetExclude(generatedSources());
        // This is configuration cache safe as happening afterEvaluate
        java.addStep(spotlessJavaFormatStep());
    }

    /**
     * The Java files of the source sets that are in a generated directory. Not the patterns themselves: Spotless
     * matches a pattern against a file tree of the whole project directory, which every build changes, and the
     * configuration cache entry is then never reused (#193). The source sets' files are what Spotless formats by
     * default; a callable, so that source sets added after the plugin count too.
     */
    private FileCollection generatedSources() {
        return getObjects().fileCollection().from((Callable<List<FileCollection>>) () -> sourceSets.stream()
                .map(sourceSet -> sourceSet.getAllJava().filter(new InGeneratedDirectory(projectDir)))
                .toList());
    }

    /**
     * The Java-based formatter, on the Gradle JVM, whether or not {@code openjavaformat.native.formatter} is set: the
     * property is about formatDiff, and Spotless has no native step.
     */
    private FormatterStep spotlessJavaFormatStep() {
        logger.info("Using the Java-based formatter");
        return PalantirJavaFormatStep.create(formatterClasspath.get());
    }

    /**
     * Whether a file of the project is in a generated directory, see {@link #GENERATED}. A class, not a record: the
     * configuration cache stores it with the Spotless task's target, and could not create it again as a record.
     */
    private static final class InGeneratedDirectory implements Spec<File> {
        private final File projectDir;

        InGeneratedDirectory(File projectDir) {
            this.projectDir = projectDir;
        }

        @Override
        public boolean isSatisfiedBy(File file) {
            var project = projectDir.toPath();
            var path = file.toPath();
            return path.startsWith(project)
                    && GENERATED
                            .matcher(project.relativize(path).toString().replace(File.separatorChar, '/'))
                            .matches();
        }
    }
}
