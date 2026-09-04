import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)

    // Embedded terminal: JediTerm (the IntelliJ terminal emulator, LGPL 3.0) on a pty4j PTY.
    implementation(libs.jediterm.core)
    implementation(libs.jediterm.ui)
    implementation(libs.pty4j)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutinesCore)
}

compose.desktop {
    application {
        mainClass = "io.mainactor.worktree.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Forest"
            packageVersion = "1.0.0"
            description = "Forest — a git worktree manager"

            // pty4j + JediTerm reach for these at runtime through reflection/JNA.
            modules("java.instrument", "jdk.unsupported", "java.management")

            // One committed file per platform, all three drawn from the same code by
            // `./gradlew :desktopApp:generateIcons`.
            macOS {
                iconFile.set(project.file("icons/forest.icns"))
                bundleID = "io.mainactor.forest"
            }
            windows { iconFile.set(project.file("icons/forest.ico")) }
            linux { iconFile.set(project.file("icons/forest.png")) }
        }
    }
}

/**
 * Redraws the application icons from `drawForestIcon`.
 *
 * The results are committed, so this only needs running when the mark itself changes. It uses the
 * test runtime classpath because the generator is a tool, not part of the app.
 */
tasks.register<JavaExec>("generateIcons") {
    group = "build"
    description = "Renders desktopApp/icons/forest.{png,ico,icns} from the app's own drawing code."
    mainClass.set("io.mainactor.worktree.tools.GenerateIcons")
    classpath = sourceSets.test.get().runtimeClasspath
    args(project.file("icons").path)
}
