package io.mainactor.worktree.git

import io.mainactor.worktree.model.ConflictSegment
import io.mainactor.worktree.model.DiffLineType
import io.mainactor.worktree.model.Resolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FS = '\u001F'
private const val NUL = '\u0000'

class WorktreeListParserTest {

    @Test
    fun `parses main, linked, detached and bare worktrees`() {
        val output = """
            worktree /repo
            HEAD abc123def456
            branch refs/heads/main

            worktree /repo-feature
            HEAD 0011223344556677
            branch refs/heads/feature/login

            worktree /repo-detached
            HEAD deadbeefdeadbeef
            detached

            worktree /repo-bare
            bare

        """.trimIndent()

        val worktrees = GitParsers.parseWorktreeList(output)

        assertEquals(4, worktrees.size)
        assertTrue(worktrees[0].isMain)
        assertEquals("main", worktrees[0].branch)
        assertEquals("abc123d", worktrees[0].shortHead)

        assertFalse(worktrees[1].isMain)
        assertEquals("feature/login", worktrees[1].branch)
        assertEquals("feature/login", worktrees[1].label)

        assertTrue(worktrees[2].isDetached)
        assertNull(worktrees[2].branch)
        assertEquals("detached at deadbee", worktrees[2].label)

        assertTrue(worktrees[3].isBare)
        assertEquals("(bare)", worktrees[3].label)
    }

    @Test
    fun `captures lock and prune reasons`() {
        val output = """
            worktree /repo
            HEAD aaa

            worktree /repo-old
            HEAD bbb
            branch refs/heads/old
            locked on the external drive
            prunable gitdir file points to non-existent location

        """.trimIndent()

        val old = GitParsers.parseWorktreeList(output)[1]

        assertTrue(old.isLocked)
        assertEquals("on the external drive", old.lockReason)
        assertTrue(old.isPrunable)
        assertEquals("gitdir file points to non-existent location", old.prunableReason)
    }
}

class StatusParserTest {

    private fun record(vararg lines: String) = lines.joinToString(NUL.toString()) + NUL

    @Test
    fun `reads branch header including ahead and behind`() {
        val output = record(
            "# branch.oid 1122334455667788",
            "# branch.head feature/x",
            "# branch.upstream origin/feature/x",
            "# branch.ab +3 -2",
        )

        val status = GitParsers.parseStatus(output)

        assertEquals("feature/x", status.branch)
        assertEquals("origin/feature/x", status.upstream)
        assertEquals(3, status.ahead)
        assertEquals(2, status.behind)
        assertEquals("1122334455667788", status.head)
        assertFalse(status.detached)
    }

    @Test
    fun `detached head has no branch`() {
        val status = GitParsers.parseStatus(record("# branch.oid abc", "# branch.head (detached)"))

        assertTrue(status.detached)
        assertNull(status.branch)
    }

    @Test
    fun `initial commit reports no head`() {
        val status = GitParsers.parseStatus(record("# branch.oid (initial)", "# branch.head main"))

        assertNull(status.head)
        assertEquals("main", status.branch)
    }

    @Test
    fun `splits staged, unstaged and untracked files`() {
        val output = record(
            "# branch.head main",
            "1 M. N... 100644 100644 100644 aaa bbb staged.kt",
            "1 .M N... 100644 100644 100644 ccc ddd dirty.kt",
            "1 MM N... 100644 100644 100644 eee fff both.kt",
            "? new.kt",
        )

        val status = GitParsers.parseStatus(output)

        assertEquals(listOf("both.kt", "staged.kt"), status.staged.map { it.path }.sorted())
        assertEquals(listOf("both.kt", "dirty.kt", "new.kt"), status.unstaged.map { it.path }.sorted())
        assertTrue(status.files.single { it.path == "new.kt" }.untracked)
        assertEquals('?', status.files.single { it.path == "new.kt" }.badge)
    }

    @Test
    fun `rename records consume the extra path token`() {
        // The rename entry is followed by its original path in a separate NUL-delimited token;
        // getting this wrong shifts every following record by one.
        val output = record(
            "# branch.head main",
            "2 R. N... 100644 100644 100644 ggg hhh R100 new/name.kt",
            "old/name.kt",
            "1 .M N... 100644 100644 100644 iii jjj after.kt",
        )

        val status = GitParsers.parseStatus(output)

        assertEquals(2, status.files.size)
        val renamed = status.files.single { it.path == "new/name.kt" }
        assertEquals("old/name.kt", renamed.origPath)
        assertEquals('R', renamed.badge)
        assertTrue(status.files.any { it.path == "after.kt" })
    }

    @Test
    fun `conflicted entries are flagged and sorted first`() {
        val output = record(
            "# branch.head main",
            "1 .M N... 100644 100644 100644 aaa bbb zzz-normal.kt",
            "u UU N... 100644 100644 100644 100644 aaa bbb ccc conflict.kt",
        )

        val status = GitParsers.parseStatus(output)

        assertEquals("conflict.kt", status.files.first().path)
        assertTrue(status.conflicts.single().conflicted)
        assertEquals('!', status.conflicts.single().badge)
        // A conflicted file is neither staged nor a plain modification.
        assertFalse(status.conflicts.single().staged)
    }
}

class DiffParserTest {

    @Test
    fun `parses hunks with correct line numbering`() {
        val diff = """
            diff --git a/src/Main.kt b/src/Main.kt
            index 1111111..2222222 100644
            --- a/src/Main.kt
            +++ b/src/Main.kt
            @@ -10,4 +10,5 @@ fun main() {
             val a = 1
            -val b = 2
            +val b = 3
            +val c = 4
             println(a)
        """.trimIndent()

        val file = GitParsers.parseDiff(diff).single()

        assertEquals("src/Main.kt", file.path)
        assertEquals(1, file.hunks.size)
        assertEquals(2, file.added)
        assertEquals(1, file.removed)

        val lines = file.hunks.single().lines
        assertEquals(DiffLineType.CONTEXT, lines[0].type)
        assertEquals(10, lines[0].oldNumber)
        assertEquals(10, lines[0].newNumber)

        assertEquals(DiffLineType.DELETE, lines[1].type)
        assertEquals(11, lines[1].oldNumber)
        assertNull(lines[1].newNumber)

        assertEquals(DiffLineType.ADD, lines[2].type)
        assertNull(lines[2].oldNumber)
        assertEquals(11, lines[2].newNumber)

        // The trailing context line continues from both sides.
        assertEquals(12, lines.last().oldNumber)
        assertEquals(13, lines.last().newNumber)
    }

    @Test
    fun `parses several files in one diff`() {
        val diff = """
            diff --git a/one.txt b/one.txt
            --- a/one.txt
            +++ b/one.txt
            @@ -1 +1 @@
            -one
            +ONE
            diff --git a/two.txt b/two.txt
            --- a/two.txt
            +++ b/two.txt
            @@ -1 +1 @@
            -two
            +TWO
        """.trimIndent()

        val files = GitParsers.parseDiff(diff)

        assertEquals(listOf("one.txt", "two.txt"), files.map { it.path })
        assertTrue(files.all { it.hunks.size == 1 })
    }

    @Test
    fun `recognises new, deleted, renamed and binary files`() {
        val diff = """
            diff --git a/added.kt b/added.kt
            new file mode 100644
            --- /dev/null
            +++ b/added.kt
            @@ -0,0 +1 @@
            +hello
            diff --git a/gone.kt b/gone.kt
            deleted file mode 100644
            --- a/gone.kt
            +++ /dev/null
            @@ -1 +0,0 @@
            -bye
            diff --git a/from.kt b/to.kt
            similarity index 95%
            rename from from.kt
            rename to to.kt
            diff --git a/logo.png b/logo.png
            index 3333333..4444444 100644
            Binary files a/logo.png and b/logo.png differ
        """.trimIndent()

        val files = GitParsers.parseDiff(diff).associateBy { it.path }

        assertTrue(files.getValue("added.kt").isNew)
        assertTrue(files.getValue("gone.kt").isDeleted)
        assertTrue(files.getValue("to.kt").isRename)
        assertEquals("from.kt", files.getValue("to.kt").oldPath)
        assertTrue(files.getValue("logo.png").isBinary)
    }

    @Test
    fun `keeps the no-newline marker out of the added and removed counts`() {
        val diff = """
            diff --git a/a.txt b/a.txt
            --- a/a.txt
            +++ b/a.txt
            @@ -1 +1 @@
            -old
            \ No newline at end of file
            +new
            \ No newline at end of file
        """.trimIndent()

        val file = GitParsers.parseDiff(diff).single()

        assertEquals(1, file.added)
        assertEquals(1, file.removed)
        assertEquals(2, file.hunks.single().lines.count { it.type == DiffLineType.NO_NEWLINE })
    }
}

class BranchAndLogParserTest {

    @Test
    fun `parses branches with upstream and worktree occupancy`() {
        val output = buildString {
            appendLine("main${FS}origin/main$FS*$FS/repo${FS}local")
            appendLine("feature/x${FS}$FS$FS/repo-feature${FS}local")
            appendLine("spike$FS$FS$FS${FS}local")
        }

        val branches = GitParsers.parseBranches(output)

        assertEquals(3, branches.size)
        assertTrue(branches[0].isCurrent)
        assertEquals("origin/main", branches[0].upstream)
        assertEquals("/repo", branches[0].checkedOutIn)
        assertNull(branches[2].checkedOutIn)
        assertFalse(branches.any { it.isRemote })
    }

    @Test
    fun `skips the remote HEAD pointer`() {
        val output = buildString {
            appendLine("origin/HEAD$FS$FS$FS${FS}remote")
            appendLine("origin/main$FS$FS$FS${FS}remote")
        }

        val branches = GitParsers.parseBranches(output)

        assertEquals(listOf("origin/main"), branches.map { it.name })
        assertTrue(branches.single().isRemote)
        assertEquals("main", branches.single().shortName)
    }

    @Test
    fun `parses log entries with refs`() {
        val output = "abc123${FS}abc12${FS}Fix the thing${FS}Ada${FS}2 hours ago${FS}HEAD -> main, origin/main\n"

        val commit = GitParsers.parseLog(output).single()

        assertEquals("abc123", commit.hash)
        assertEquals("Fix the thing", commit.subject)
        assertEquals("Ada", commit.author)
        assertEquals(listOf("HEAD -> main", "origin/main"), commit.refs)
    }
}

class ConflictParserTest {

    private val mergeStyle = """
        fun greet() {
        <<<<<<< HEAD
            println("ours")
        =======
            println("theirs")
        >>>>>>> feature/x
        }
    """.trimIndent()

    @Test
    fun `splits a merge-style conflict into text and one region`() {
        val file = GitParsers.parseConflicts("Greet.kt", mergeStyle)

        assertEquals(3, file.segments.size)
        val conflict = file.regions.single()
        assertEquals("HEAD", conflict.region.oursLabel)
        assertEquals("feature/x", conflict.region.theirsLabel)
        assertEquals(listOf("    println(\"ours\")"), conflict.region.ours)
        assertEquals(listOf("    println(\"theirs\")"), conflict.region.theirs)
        assertNull(conflict.region.base)
        assertEquals(1, file.unresolvedCount)
        assertFalse(file.isFullyResolved)
    }

    @Test
    fun `understands the diff3 style base section`() {
        val diff3 = """
            <<<<<<< HEAD
            ours
            ||||||| merged common ancestors
            base
            =======
            theirs
            >>>>>>> other
        """.trimIndent()

        val region = GitParsers.parseConflicts("f.txt", diff3).regions.single().region

        assertEquals(listOf("ours"), region.ours)
        assertEquals(listOf("base"), region.base)
        assertEquals(listOf("theirs"), region.theirs)
    }

    @Test
    fun `handles several regions in one file`() {
        val content = """
            a
            <<<<<<< HEAD
            one-ours
            =======
            one-theirs
            >>>>>>> b
            b
            <<<<<<< HEAD
            two-ours
            =======
            two-theirs
            >>>>>>> b
            c
        """.trimIndent()

        val file = GitParsers.parseConflicts("f.txt", content)

        assertEquals(2, file.regions.size)
        assertEquals(listOf(0, 1), file.regions.map { it.id })
        assertEquals(2, file.unresolvedCount)
    }

    @Test
    fun `an unterminated marker is kept as plain text rather than dropped`() {
        val content = "start\n<<<<<<< HEAD\nours only\n"

        val file = GitParsers.parseConflicts("f.txt", content)

        assertTrue(file.regions.isEmpty())
        val text = file.segments.filterIsInstance<ConflictSegment.Text>().flatMap { it.lines }
        assertTrue("ours only" in text)
        assertTrue(text.any { it.startsWith("<<<<<<<") })
    }

    @Test
    fun `rendering a resolved file drops the markers`() {
        val file = GitParsers.parseConflicts("Greet.kt", mergeStyle)
        val resolved = file.copy(
            segments = file.segments.map {
                if (it is ConflictSegment.Conflict) it.copy(resolution = Resolution.THEIRS) else it
            },
        )

        val rendered = GitParsers.renderResolved(resolved)

        assertEquals(
            """
            fun greet() {
                println("theirs")
            }
            """.trimIndent(),
            rendered,
        )
        assertTrue(resolved.isFullyResolved)
    }

    @Test
    fun `keeping both sides preserves order`() {
        val file = GitParsers.parseConflicts("Greet.kt", mergeStyle)
        val oursFirst = GitParsers.renderResolved(
            file.copy(
                segments = file.segments.map {
                    if (it is ConflictSegment.Conflict) it.copy(resolution = Resolution.BOTH_OURS_FIRST) else it
                },
            )
        )
        val theirsFirst = GitParsers.renderResolved(
            file.copy(
                segments = file.segments.map {
                    if (it is ConflictSegment.Conflict) it.copy(resolution = Resolution.BOTH_THEIRS_FIRST) else it
                },
            )
        )

        assertTrue(oursFirst.indexOf("ours") < oursFirst.indexOf("theirs"))
        assertTrue(theirsFirst.indexOf("theirs") < theirsFirst.indexOf("ours"))
    }

    @Test
    fun `an unresolved region round-trips back to markers`() {
        val file = GitParsers.parseConflicts("Greet.kt", mergeStyle)

        assertEquals(mergeStyle, GitParsers.renderResolved(file))
    }
}
