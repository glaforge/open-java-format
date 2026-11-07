<p align="center">
<a href="https://github.com/openjavaformat/open-java-format/actions/workflows/ci.yml"><img src="https://github.com/openjavaformat/open-java-format/actions/workflows/ci.yml/badge.svg" alt="CI"/></a>
<a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="License"/></a>
<a href="https://central.sonatype.com/artifact/dev.openjavaformat/open-java-format"><img src="https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fdev%2Fopenjavaformat%2Fopen-java-format%2Fmaven-metadata.xml&label=Maven%20Central" alt="Maven Central"/></a>
<a href="https://plugins.gradle.org/plugin/dev.openjavaformat.java-format"><img src="https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Fplugins.gradle.org%2Fm2%2Fdev%2Fopenjavaformat%2Fjava-format%2Fdev.openjavaformat.java-format.gradle.plugin%2Fmaven-metadata.xml&label=Gradle%20Plugin%20Portal" alt="Gradle Plugin Portal"/></a>
<a href="https://plugins.jetbrains.com/plugin/34359-open-java-format"><img src="https://img.shields.io/jetbrains/plugin/v/34359?label=JetBrains%20Marketplace" alt="JetBrains Marketplace"/></a>
<a href="https://openjavaformat.dev/manifesto/"><img src="https://img.shields.io/badge/read-the%20manifesto-informational" alt="Manifesto"/></a>
</p>

# open-java-format

_A modern, lambda-friendly, 120-character Java formatter — built in the open._

**Documentation: [openjavaformat.dev](https://openjavaformat.dev)**

open-java-format is released and developed independently. It began as a community fork of
[palantir-java-format](https://github.com/palantir/palantir-java-format), which is itself a fork of
[google-java-format](https://github.com/google/google-java-format), and every artifact is now built
and published from this repository.

| Where | What |
|---|---|
| [Maven Central](https://central.sonatype.com/namespace/dev.openjavaformat) | `dev.openjavaformat:open-java-format`, with `-spi`, `-native` and `-jdk-bootstrap`, and the Maven plugin `dev.openjavaformat:fmt-maven-plugin` from [its own repository](https://github.com/openjavaformat/fmt-maven-plugin) |
| [Gradle Plugin Portal](https://plugins.gradle.org/plugin/dev.openjavaformat.java-format) | `dev.openjavaformat.java-format` |
| [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/34359-open-java-format) | the IntelliJ IDEA plugin |
| [GitHub Releases](https://github.com/openjavaformat/open-java-format/releases/latest) | native binaries, a runnable jar, the Gradle, IntelliJ and Eclipse plugins, the WebAssembly module of [the playground](https://openjavaformat.dev/playground/), every file signed |

Why the project exists is in the [manifesto](https://openjavaformat.dev/manifesto/).

## Get started

Each page is short and every command on it was run against the published artifacts.

- [Gradle plugin](https://openjavaformat.dev/get-started/gradle/)
- [Maven plugin](https://openjavaformat.dev/get-started/maven/)
- [Command line](https://openjavaformat.dev/get-started/command-line/): a native binary, or a runnable jar
- [IntelliJ IDEA](https://openjavaformat.dev/get-started/intellij-idea/)
- [Eclipse](https://openjavaformat.dev/get-started/eclipse/)
- [GitHub Action and pre-commit hook](https://openjavaformat.dev/get-started/github-actions/)

## What the output looks like

Lines are up to 120 characters wide. A lambda stays on the line where it starts, and a long call
chain breaks into one call per line.

google-java-format:

```java
private static void configureResolvedVersionsWithVersionMapping(Project project) {
    project.getPluginManager()
            .withPlugin(
                    "maven-publish",
                    plugin -> {
                        project.getExtensions()
                                .getByType(PublishingExtension.class)
                                .getPublications()
                                .withType(MavenPublication.class)
                                .configureEach(
                                        publication ->
                                                publication.versionMapping(
                                                        mapping -> {
                                                            mapping.allVariants(
                                                                    VariantVersionMappingStrategy
                                                                            ::fromResolutionResult);
                                                        }));
                    });
}
```

open-java-format:

```java
private static void configureResolvedVersionsWithVersionMapping(Project project) {
    project.getPluginManager().withPlugin("maven-publish", plugin -> {
        project.getExtensions()
                .getByType(PublishingExtension.class)
                .getPublications()
                .withType(MavenPublication.class)
                .configureEach(publication -> publication.versionMapping(mapping -> {
                    mapping.allVariants(VariantVersionMappingStrategy::fromResolutionResult);
                }));
    });
}
```

## Coming from palantir-java-format

- **Same output, except for bug fixes.** The formatter is the code of palantir-java-format 2.98.0,
  renamed and rebuilt in the open. Its output is the same except where it fixes a bug, and
  [Migrate](https://openjavaformat.dev/migrate/) lists those fixes. Any later change to the output
  comes with a new minor version, see [Versions](#versions).
- **Identical Java packages.** `com.palantir.javaformat.*` stays, so your code and the SPI keep
  working.
- **One-line migration.** Change the coordinates below. Nothing else.

| | palantir-java-format | open-java-format |
|---|---|---|
| Maven group | `com.palantir.javaformat` | `dev.openjavaformat` |
| Core | `palantir-java-format` | `open-java-format` |
| SPI | `palantir-java-format-spi` | `open-java-format-spi` |
| Native image | `palantir-java-format-native` | `open-java-format-native` |
| JDK bootstrap | `palantir-java-format-jdk-bootstrap` | `open-java-format-jdk-bootstrap` |
| Gradle plugin artifact | `gradle-palantir-java-format` | `gradle-open-java-format` |
| Gradle plugin IDs | `com.palantir.java-format`, `-idea`, `-spotless`, `-provider` | `dev.openjavaformat.java-format`, `-idea`, `-spotless`, `-provider` |
| IntelliJ plugin ID | `palantir-java-format` | `open-java-format` |
| Eclipse plugin bundle | `palantir-java-format-eclipse-plugin` | `open-java-format-eclipse-plugin` |
| CLI style flag | `--palantir`, `-palantir` | none: there is one style |
| Formatter style | `PALANTIR` | `OJF` |
| Native formatter Gradle property | `palantir.native.formatter` | `openjavaformat.native.formatter` |
| Java packages | `com.palantir.javaformat.*` | **unchanged** |
| Version numbers | `2.98.0` | `21.<minor>.<build>`, see [Versions](#versions) |

## Versions

A version is three numbers, `<java>.<minor>.<build>`, and each one answers a question of its own.

- **The first is the Java version the formatter is built for**, 21 today. It moves when the minimum
  Java version moves.
- **The second moves with every change to the output**: a fix in wrapping or alignment, a new rule,
  a new feature. A newer minor version can reformat code that the previous one left alone.
- **The third moves with fixes that leave the output alone**: a crash, a regression, an internal
  bug, a dependency update. A newer build formats every file exactly as the previous one did.

The line between the last two numbers is the output, not the kind of change: a fix that changes
the output raises the second number, a fix that does not raises the third.

Up to 2.98.0.6 the version was palantir-java-format's with a build number appended. Once the bug
fixes made the output differ from upstream, that numbering lost its meaning.

## Building and testing locally

[mise](https://mise.jdx.dev/) pins the tooling: `mise.toml` installs Temurin 21 and
[act](https://github.com/nektos/act).

```bash
mise trust && mise install
./gradlew test      # what the CI build job runs
```

`-PjavaRuntime=25` runs the same tests on JDK 25, as CI's `jdk` jobs do for 25, 26 and 27, and for
the early-access builds of 28, where value classes are a preview feature and the `value` modifier
tests run. The code is compiled for Java 21 either way. Gradle has to find that JDK: installed with
mise, or named with `-Porg.gradle.java.installations.paths=/path/to/jdk`.

The Gradle plugins' tests build their projects with Gradle 9.3.1, the oldest the plugins support.
`-PtestkitGradleVersion=9.8.0` builds them with another, as CI's JDK 27 leg does: 9.3.1 does not run
on Java 27, and 9.8.0 is the oldest release that does.

Nothing inside the build downloads a JDK. `gradle.properties` turns toolchain auto-download off and
reads the installations from `JDK21_HOME` and `GRAALVM_HOME`, so a missing JDK is an error you can
read rather than a silent download.

The native image needs a GraalVM 25 behind `GRAALVM_HOME`. CI installs it with
`graalvm/setup-graalvm` and builds the image in a job of its own:

```bash
./gradlew -PnativeImage=true :open-java-format-native:nativeCompile
```

GraalVM has to come from outside Gradle: unpacking a JDK that Gradle downloaded itself does not
preserve the symlink GraalVM ships at `bin/native-image`, and `nativeCompile` then fails on an empty,
unexecutable file.

### WebAssembly module

The playground on openjavaformat.dev runs the formatter in the browser, compiled to WebAssembly by
GraalVM's Web Image backend. Only the release workflow builds it, in its `wasm` job, and the zip goes
on the GitHub release; `./gradlew build` leaves it out. Web Image ships in Oracle GraalVM only, so the
module needs Oracle GraalVM 25 behind `GRAALVM_HOME`, Binaryen 119 or later (`wasm-opt`) on the
`PATH`, and Node.js 24 or later for the tests:

```bash
./gradlew -Pwasm=true :open-java-format-wasm:wasmTest   # build, then test in Node against the runnable jar
./gradlew -Pwasm=true :open-java-format-wasm:wasmDev    # a bare page next to the module, to try it
jwebserver -d "$PWD/open-java-format-wasm/build/wasm-dev"
```

Without `-Pwasm=true` the subproject is not part of the build at all: it compiles against
`org.graalvm.webimage.api`, a module of Oracle GraalVM that no other JDK has.

### Running CI locally

The workflows run under act in Docker, so a change to `.github/workflows` can be checked before it
is pushed:

```bash
mise run ci:list     # what jobs exist
mise run ci:lint     # dry-run every job: resolves images, actions and steps, executes nothing
mise run ci:build    # actually run the linux jar+native build job
mise run ci:native   # actually run the linux-aarch64 native job
```

Two caveats:

- **act cannot containerise macOS**, so the `macos-aarch64` leg has to be checked by building the
  native image on a Mac.
- **On an Apple Silicon host** the `ubuntu-24.04` container runs as arm64, so the job labelled
  `linux-x86-64` produces an aarch64 binary locally. Fine for exercising the workflow, not a
  substitute for real CI.

## License and attribution

This project is a fork of [palantir-java-format](https://github.com/palantir/palantir-java-format),
which is itself a fork of [google-java-format](https://github.com/google/google-java-format). It is
distributed under the same [Apache 2.0 License](./LICENSE), and it keeps every upstream copyright
notice — from both projects — permanently.

Neither Palantir Technologies Inc. nor Google LLC endorses, sponsors or is affiliated with this
fork. "Palantir" and "Google" are trademarks of their respective owners, and Apache 2.0 §6 grants no
rights to them; the rename described in this README exists in part for that reason.

```text
(c) Copyright 2019 Palantir Technologies Inc. All rights reserved.

Licensed under the Apache License, Version 2.0 (the "License"); you may not
use this file except in compliance with the License. You may obtain a copy of
the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
License for the specific language governing permissions and limitations under
the License.
```

Original work copyrighted by Google under the same license:

```text
Copyright 2015 Google Inc.

Licensed under the Apache License, Version 2.0 (the "License"); you may not
use this file except in compliance with the License. You may obtain a copy of
the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
License for the specific language governing permissions and limitations under
the License.
```
