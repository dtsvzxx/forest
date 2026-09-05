plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

/**
 * The terminal, whole: the emulator, its pseudo-terminal and (later) its Compose view.
 *
 * A module rather than a package inside `:shared` because the boundary is the point — nothing here
 * can reach `AppState` or `Git`, which is what keeps the emulator a pure function from bytes to a
 * screen. It also makes `./gradlew :terminal:jvmTest` a seconds-long loop that never touches the UI.
 *
 * The dependency graph runs one way and stops: `:terminal` knows nothing of `:shared` (its session
 * API takes an id, a directory and a command line — not a `TerminalSession`), `:shared` knows
 * nothing of `:terminal` (`App` takes the pane as a composable slot). Only `:desktopApp` sees both.
 *
 * Shaped like `:shared` — Kotlin Multiplatform with a single `jvm()` target — so `commonMain` can
 * be depended on without surprises in the published metadata.
 */
kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            // Scaffolding, and only scaffolding: JediTerm is the reference the new emulator is
            // checked against while both exist. A test dependency is not shipped, so it creates no
            // notice obligation — and it goes when the differential test does.
            implementation(libs.jediterm.core)
            // Only so the view can be rendered off screen — which the Swing pane it replaces
            // could never be, and which is the cheapest guard there is against a layout that
            // throws at measure time.
            implementation(compose.desktop.currentOs)
        }
    }
}

// The pseudo-terminal calls libc through java.lang.foreign; see :desktopApp for why this is here.
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

/**
 * Records what real programs print, into committed fixtures.
 *
 * Deliberate rather than automatic: the suite must not depend on `vim` being installed, and the
 * recordings are the *input* to the tests rather than a product of them. Same arrangement as
 * `:desktopApp:generateIcons` — a tool in the test source set, run through a `JavaExec`.
 */
tasks.register<JavaExec>("recordFixtures") {
    group = "terminal"
    description = "Records real programs' output into src/jvmTest/resources/term."
    val testCompilation = kotlin.jvm().compilations.getByName("test")
    classpath = testCompilation.runtimeDependencyFiles + testCompilation.output.allOutputs
    mainClass.set("io.mainactor.worktree.term.tools.RecordFixtures")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    workingDir = projectDir
}
