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

/**
 * Signing and notarization are read from the environment and are inert without it.
 *
 * A build with no identity still produces a working `.app` and `.dmg` — jpackage ad-hoc signs the
 * arm64 binary, which is enough to run on the machine that built it. It is *not* enough to hand to
 * anyone: a downloaded copy carries the quarantine flag and Gatekeeper refuses it. That needs a
 * Developer ID identity and notarization, and neither belongs in a repository.
 */
val appVersion = "1.0.0"

/** The Developer ID team this is signed and notarized under. */
val TEAM_ID = "7FCH84EN89"

val macSigningIdentity: Provider<String> =
    providers.gradleProperty("forest.macos.signingIdentity")
        .orElse(providers.environmentVariable("FOREST_MACOS_SIGNING_IDENTITY"))
        .orElse("")

compose.desktop {
    application {
        mainClass = "io.mainactor.worktree.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Forest"
            packageVersion = appVersion
            description = "Forest — a git worktree manager"
            vendor = "mainactor"
            copyright = "© 2026 mainactor"
            // JediTerm is LGPL and pty4j is EPL, so the binary has to carry their notices — but
            // *not* through `licenseFile`, which turns the DMG into an image with a click-through
            // agreement that has to be accepted before it will even mount. The notices ship inside
            // the bundle instead, under `Contents/app/resources`.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("packaging"))

            // pty4j + JediTerm reach for these at runtime through reflection/JNA.
            modules("java.instrument", "jdk.unsupported", "java.management")

            // One committed file per platform, all three drawn from the same code by
            // `./gradlew :desktopApp:generateIcons`.
            macOS {
                iconFile.set(project.file("icons/forest.icns"))
                bundleID = "io.mainactor.forest"
                dockName = "Forest"
                appCategory = "public.app-category.developer-tools"
                // jpackage writes 10.13 by default, which this cannot honour: the bundled runtime
                // is an arm64 JDK 21 and the app has never been built for anything older.
                minimumSystemVersion = "11.0"
                // Pinned rather than left to the plugin's default so the re-signing below applies
                // exactly the same set; the two drifting apart would change how the app behaves.
                entitlementsFile.set(project.file("packaging/entitlements.plist"))

                signing {
                    sign.set(macSigningIdentity.map { it.isNotBlank() })
                    identity.set(macSigningIdentity)
                }
                // The plugin's own `notarizeDmg` reads the three from the environment, which is
                // what a CI runner has. Locally, prefer `stapleDmg` below: it keeps the
                // app-specific password in the keychain instead of on a command line.
                notarization {
                    appleID.set(providers.environmentVariable("FOREST_APPLE_ID").orElse(""))
                    password.set(providers.environmentVariable("FOREST_APPLE_PASSWORD").orElse(""))
                    teamID.set(providers.environmentVariable("FOREST_APPLE_TEAM_ID").orElse(TEAM_ID))
                }
            }
            windows {
                iconFile.set(project.file("icons/forest.ico"))
                // Fixed for the life of the product: MSI decides what is an upgrade and what is a
                // second copy by this id, so generating one per build installs Forest beside Forest.
                upgradeUuid = "0A93B03E-AB53-44F7-96B6-A3A85D014BE9"
                menuGroup = "Forest"
                shortcut = true
            }
            linux { iconFile.set(project.file("icons/forest.png")) }
        }
    }
}

/**
 * Hardens the Mach-O binaries buried inside the bundled jars, which notarization inspects.
 *
 * The work is a shell script rather than a task class: it is entirely codesign, file, unzip and
 * zip, it only ever runs on macOS, and when Apple rejects something the script is the thing you
 * want to be able to run by hand against a built bundle. Its header explains what it is for.
 */
val hardenNatives = tasks.register<Exec>("hardenEmbeddedNatives") {
    group = "compose desktop"
    description = "Re-signs Mach-O binaries buried in the bundled jars, which notarization inspects."
    dependsOn("createDistributable")
    // Resolved here into a plain String: a lambda below that read the script's own property would
    // capture the build script itself, which the configuration cache cannot serialise.
    val identity = macSigningIdentity.get()
    // It edits the app image in place, so it is never up to date.
    outputs.upToDateWhen { false }
    // Nothing to harden in an unsigned build, and codesign would have no identity to use.
    onlyIf { identity.isNotBlank() }
    commandLine(
        project.file("packaging/harden-embedded-natives.sh").absolutePath,
        layout.buildDirectory.dir("compose/binaries/main/app/Forest.app").get().asFile.absolutePath,
        identity,
        project.file("packaging/entitlements.plist").absolutePath,
    )
}

tasks.matching { it.name == "packageDmg" }.configureEach { dependsOn(hardenNatives) }

/**
 * Notarization against credentials kept in the keychain.
 *
 * Apple will not notarize an unsigned build, and macOS will not run a *downloaded* signed build that
 * has not been notarized — so this is the last step before a DMG can be given to anyone.
 *
 * One-time setup, typed by you so the app-specific password never reaches a command line, a build
 * log or a shell history:
 *
 * ```
 * xcrun notarytool store-credentials forest-notary \
 *   --apple-id <your-apple-id> --team-id $TEAM_ID
 * ```
 *
 * Then `./gradlew :desktopApp:stapleDmg` signs, submits, waits, and staples the ticket to the image
 * so it also verifies offline.
 */
val notaryProfile: Provider<String> =
    providers.gradleProperty("forest.macos.notaryProfile")
        .orElse(providers.environmentVariable("FOREST_NOTARY_PROFILE"))
        .orElse("forest-notary")

val dmgFile = layout.buildDirectory.file("compose/binaries/main/dmg/Forest-$appVersion.dmg")

/**
 * Signs the disk image itself, not just the app inside it.
 *
 * Without this the image has "no usable signature": the app within is notarized and passes, but the
 * container the user actually double-clicks carries nothing of its own. Signing it before
 * notarization is what makes the whole download verifiable rather than only its contents.
 */
val signDmg = tasks.register<Exec>("signDmg") {
    group = "compose desktop"
    description = "Signs the disk image with the Developer ID, before it goes to Apple."
    dependsOn("packageDmg")
    val identity = macSigningIdentity.get()
    outputs.upToDateWhen { false }
    onlyIf { identity.isNotBlank() }
    commandLine(
        "codesign", "--force", "--timestamp", "-s", identity,
        dmgFile.get().asFile.absolutePath,
    )
}

val submitDmg = tasks.register<Exec>("submitDmgForNotarization") {
    group = "compose desktop"
    description = "Uploads the DMG to Apple and waits for the verdict."
    dependsOn(signDmg)
    commandLine(
        "xcrun", "notarytool", "submit",
        dmgFile.get().asFile.absolutePath,
        "--keychain-profile", notaryProfile.get(),
        "--wait",
    )
}

tasks.register<Exec>("stapleDmg") {
    group = "compose desktop"
    description = "Attaches the notarization ticket to the DMG, so it verifies without a network."
    dependsOn(submitDmg)
    commandLine("xcrun", "stapler", "staple", dmgFile.get().asFile.absolutePath)
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
