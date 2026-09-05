plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm()

    // Same version as :desktopApp — see the note on its own toolchain block.
    jvmToolchain(25)

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutinesCore)
            // Only the runtime: usage transcripts are read with Json.parseToJsonElement, so there
            // are no @Serializable classes and the serialization compiler plugin is not needed.
            implementation(libs.kotlinx.serializationJson)
        }
        jvmMain.dependencies {
            // Syntax highlighting for the diff view. Real parsers rather than regular expressions,
            // which is what makes a `//` inside a string stay a string — see `TreeSitterHighlighter`
            // for what that costs and what it buys.
            implementation(libs.treesitter.core)
            implementation(libs.treesitter.kotlin)
            implementation(libs.treesitter.java)
            implementation(libs.treesitter.javascript)
            implementation(libs.treesitter.typescript)
            implementation(libs.treesitter.python)
            implementation(libs.treesitter.go)
            implementation(libs.treesitter.rust)
            implementation(libs.treesitter.c)
            implementation(libs.treesitter.bash)
            implementation(libs.treesitter.json)
            implementation(libs.treesitter.yaml)
            implementation(libs.treesitter.markdown)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// The pseudo-terminal's tests call libc through java.lang.foreign; see :desktopApp for why.
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
