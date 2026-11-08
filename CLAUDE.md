# Working in this repository

Building and testing: the README's "Building and testing locally" section. Gradle runs on the Temurin 21 that
`mise install` provides, `./gradlew test` is what CI runs, and `./gradlew formatDiff` formats the files you changed.

## Conventions for new code

- **Collections.** Plain `java.util` types. `List.of`, `List.copyOf`, `Map.of` and `Stream.toList()` give
  unmodifiable collections, so new code does not need Guava's `ImmutableList` or `ImmutableMap`; use them only
  where an existing API already hands one over.
- **Nullability.** One annotation, JSpecify's `org.jspecify.annotations.Nullable`; do not add another `Nullable`,
  nor a `NotNull` or `NonNull`: what is not `@Nullable` is non-null, and NullAway checks it. It is a type-use
  annotation, so on a nested type it goes before the simple name (`Input.@Nullable Token`), and on an array before
  the brackets (`String @Nullable []`).
- **Local variables.** `var` wherever the initializer gives the type unchanged, in `for` loops too. Not where it
  would box a primitive (`for (var n : List<Integer>)`), and not without an initializer that has a type of its own
  (a lambda, `null`).
- **Immutability annotations.** Do not add Error Prone's `@Immutable` or `@SuppressWarnings("Immutable")`.
- **License header.** A new file starts with the Apache 2.0 header alone, without a copyright line:

  ```java
  /*
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
  ```

  Files inherited from google-java-format and palantir-java-format keep their original headers.
