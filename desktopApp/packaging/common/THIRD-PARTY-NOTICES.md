# Third-party notices

Forest bundles a Java runtime and the libraries below. Every licence here was read from the
artifact's own POM at the version shipped, except SLF4J, whose POM declares none and whose licence
was taken from https://www.slf4j.org/license.html.

Regenerate this list against the built distribution before a release — the app's dependency set is
what decides it, not this file.

## LGPL 3.0

- `org.jetbrains.jediterm:jediterm-core`
- `org.jetbrains.jediterm:jediterm-ui`

The embedded terminal. Linked as unmodified jars and shipped as such, which is what the LGPL asks
for in return: nothing here is a derived work of JediTerm, and replacing those jars with your own
build of the same version is enough to relink. Sources: https://github.com/JetBrains/jediterm

## LGPL-2.1-or-later, or Apache-2.0 (dual licensed)

- `net.java.dev.jna:jna`
- `net.java.dev.jna:jna-platform`

Native access for pty4j. Shipped unmodified. Sources: https://github.com/java-native-access/jna

## Eclipse Public License 1.0

- `org.jetbrains.pty4j:pty4j`

The pseudo-terminal behind every pane. Shipped unmodified, native helper binaries included.
Sources: https://github.com/JetBrains/pty4j

## MIT

- `org.slf4j:slf4j-api` — Copyright (c) 2004-2025 QOS.ch All rights reserved.
- `io.github.bonede:tree-sitter` and its grammars: `tree-sitter-kotlin`, `tree-sitter-java`,
  `tree-sitter-javascript`, `tree-sitter-typescript`, `tree-sitter-python`, `tree-sitter-go`,
  `tree-sitter-rust`, `tree-sitter-c`, `tree-sitter-bash`, `tree-sitter-json`, `tree-sitter-yaml`,
  `tree-sitter-markdown`

The parsers that colour a diff. Shipped unmodified, native parser libraries included — they live
inside the jars and are re-signed by `hardenEmbeddedNatives` like every other buried binary.
Sources: https://github.com/bonede/tree-sitter-ng, which packages the grammars from
https://github.com/tree-sitter

## Apache License 2.0

Kotlin, Compose Multiplatform, AndroidX, Skiko and the rest:

- \`androidx.annotation:annotation-jvm\`
- \`androidx.arch.core:core-common\`
- \`androidx.collection:collection-jvm\`
- \`androidx.compose.runtime:runtime-annotation-jvm\`
- \`androidx.compose.runtime:runtime-desktop\`
- \`androidx.compose.runtime:runtime-retain-desktop\`
- \`androidx.compose.runtime:runtime-saveable-desktop\`
- \`androidx.graphics:graphics-shapes-desktop\`
- \`androidx.lifecycle:lifecycle-common-jvm\`
- \`androidx.lifecycle:lifecycle-runtime-desktop\`
- \`androidx.lifecycle:lifecycle-viewmodel-desktop\`
- \`androidx.lifecycle:lifecycle-viewmodel-savedstate-desktop\`
- \`androidx.navigationevent:navigationevent-desktop\`
- \`androidx.savedstate:savedstate-compose-desktop\`
- \`androidx.savedstate:savedstate-desktop\`
- \`net.java.dev.jna:jna\`
- \`net.java.dev.jna:jna-platform\`
- \`org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose-desktop\`
- \`org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose-desktop\`
- \`org.jetbrains.compose.animation:animation-core-desktop\`
- \`org.jetbrains.compose.animation:animation-desktop\`
- \`org.jetbrains.compose.desktop:desktop-jvm\`
- \`org.jetbrains.compose.foundation:foundation-desktop\`
- \`org.jetbrains.compose.foundation:foundation-layout-desktop\`
- \`org.jetbrains.compose.material3:material3-desktop\`
- \`org.jetbrains.compose.material:material-desktop\`
- \`org.jetbrains.compose.material:material-ripple-desktop\`
- \`org.jetbrains.compose.ui:ui-backhandler-desktop\`
- \`org.jetbrains.compose.ui:ui-desktop\`
- \`org.jetbrains.compose.ui:ui-geometry-desktop\`
- \`org.jetbrains.compose.ui:ui-graphics-desktop\`
- \`org.jetbrains.compose.ui:ui-text-desktop\`
- \`org.jetbrains.compose.ui:ui-tooling-preview-desktop\`
- \`org.jetbrains.compose.ui:ui-unit-desktop\`
- \`org.jetbrains.compose.ui:ui-util-desktop\`
- \`org.jetbrains.kotlin:kotlin-stdlib\`
- \`org.jetbrains.kotlinx:atomicfu-jvm\`
- \`org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm\`
- \`org.jetbrains.kotlinx:kotlinx-coroutines-swing\`
- \`org.jetbrains.kotlinx:kotlinx-datetime-jvm\`
- \`org.jetbrains.kotlinx:kotlinx-serialization-core-jvm\`
- \`org.jetbrains.kotlinx:kotlinx-serialization-json-jvm\`
- \`org.jetbrains.runtime:jbr-api\`
- \`org.jetbrains.skiko:skiko-awt\`
- \`org.jetbrains.skiko:skiko-awt-runtime-macos-arm64\`
- \`org.jetbrains:annotations\`
- \`org.jspecify:jspecify\`

## The Java runtime

The bundled runtime is built from the JDK the build used (Azul Zulu 21) with `jlink`, and carries
the GPLv2-with-Classpath-Exception that OpenJDK is published under.
