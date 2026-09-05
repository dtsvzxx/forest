package io.mainactor.worktree.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The notices that ship inside the bundle.
 *
 * `nativeDistributions.appResourcesRootDir` points at `packaging/`, so this file is copied into
 * `Forest.app/Contents/app/resources` — a build only notices it is gone when someone opens the
 * bundle looking for it, which is to say never.
 *
 * It deliberately does not check the Apache-2.0 crowd: those impose no notice obligation worth a
 * failing build, and the list changes with every dependency bump. What has to be there is the
 * copyleft.
 */
class PackagingTest {

    private val notices = File("packaging/common/THIRD-PARTY-NOTICES.md")

    @Test
    fun `the bundle ships notices for everything copyleft in it`() {
        assertTrue(notices.isFile, "${notices.path} is missing and the packaged app would ship without it")
        val text = notices.readText()

        // JediTerm is LGPL 3.0, pty4j is EPL 1.0, JNA is LGPL/Apache dual, SLF4J is MIT — each
        // read from the artifact's own POM, except SLF4J's, which declares none.
        listOf("jediterm", "pty4j", "jna", "slf4j", "tree-sitter").forEach { module ->
            assertTrue(module in text, "$module is bundled but not mentioned in the notices")
        }
        listOf("LGPL", "Eclipse Public License", "MIT").forEach { licence ->
            assertTrue(licence in text, "$licence is not named in the notices")
        }

        // The LGPL's practical ask: say where the sources are.
        assertTrue("github.com/JetBrains/jediterm" in text)
        assertTrue("github.com/JetBrains/pty4j" in text)
    }
}
