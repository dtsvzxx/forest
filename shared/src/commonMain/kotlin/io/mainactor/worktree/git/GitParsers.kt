package io.mainactor.worktree.git

import io.mainactor.worktree.model.Branch
import io.mainactor.worktree.model.ChangedFile
import io.mainactor.worktree.model.CommitInfo
import io.mainactor.worktree.model.ConflictRegion
import io.mainactor.worktree.model.ConflictSegment
import io.mainactor.worktree.model.ConflictedFile
import io.mainactor.worktree.model.DiffHunk
import io.mainactor.worktree.model.DiffLine
import io.mainactor.worktree.model.DiffLineType
import io.mainactor.worktree.model.FileDiff
import io.mainactor.worktree.model.RepoStatus
import io.mainactor.worktree.model.Worktree

/**
 * Parsers for git's machine-readable output formats. Pure functions, no I/O — the interesting
 * edge cases (renames, conflicts, detached heads) are covered by tests rather than by trying
 * things against a live repository.
 */
object GitParsers {

    /** Unit separator, used as the field delimiter in our `--format` strings (git's `%x1f`). */
    const val FS = '\u001F'

    /** NUL, the record separator git uses under `-z`. */
    const val NUL = '\u0000'

    /** `git worktree list --porcelain`: blank-line separated records of `key value` lines. */
    fun parseWorktreeList(output: String): List<Worktree> {
        val result = mutableListOf<Worktree>()
        var path: String? = null
        var head: String? = null
        var branch: String? = null
        var bare = false
        var detached = false
        var locked = false
        var lockReason: String? = null
        var prunable = false
        var prunableReason: String? = null

        fun flush() {
            val p = path ?: return
            result += Worktree(
                path = p,
                head = head,
                branch = branch,
                isBare = bare,
                isDetached = detached,
                isLocked = locked,
                lockReason = lockReason,
                isPrunable = prunable,
                prunableReason = prunableReason,
                isMain = result.isEmpty(),
            )
            path = null; head = null; branch = null
            bare = false; detached = false
            locked = false; lockReason = null
            prunable = false; prunableReason = null
        }

        for (raw in output.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) {
                flush()
                continue
            }
            val key = line.substringBefore(' ')
            val value = line.substringAfter(' ', "").trim()
            when (key) {
                "worktree" -> { flush(); path = value }
                "HEAD" -> head = value
                "branch" -> branch = value.removePrefix("refs/heads/")
                "bare" -> bare = true
                "detached" -> detached = true
                "locked" -> { locked = true; lockReason = value.ifEmpty { null } }
                "prunable" -> { prunable = true; prunableReason = value.ifEmpty { null } }
            }
        }
        flush()
        return result
    }

    /**
     * `git status --porcelain=v2 --branch --untracked-files=all -z`.
     *
     * Under `-z` every record is NUL-terminated. Rename records (`2`) carry a *second*
     * NUL-separated token holding the original path, so those consume an extra token.
     */
    fun parseStatus(output: String): RepoStatus {
        var branch: String? = null
        var upstream: String? = null
        var ahead = 0
        var behind = 0
        var head: String? = null
        var detached = false
        val files = mutableListOf<ChangedFile>()

        val records = output.split(NUL)
        var i = 0
        while (i < records.size) {
            val entry = records[i]
            i++
            if (entry.isEmpty()) continue
            when (entry[0]) {
                '#' -> {
                    // "# <key> <value>" — the key itself contains a dot, so split on spaces by index.
                    val parts = entry.split(' ', limit = 3)
                    val key = parts.getOrNull(1).orEmpty()
                    val value = parts.getOrNull(2).orEmpty()
                    when (key) {
                        "branch.oid" -> head = value.takeUnless { it == "(initial)" }
                        "branch.head" -> if (value == "(detached)") detached = true else branch = value
                        "branch.upstream" -> upstream = value.takeIf { it.isNotBlank() }
                        "branch.ab" -> for (part in value.split(' ')) {
                            val n = part.drop(1).toIntOrNull() ?: 0
                            when (part.firstOrNull()) {
                                '+' -> ahead = n
                                '-' -> behind = n
                            }
                        }
                    }
                }
                '1' -> {
                    // 1 <XY> <sub> <mH> <mI> <mW> <hH> <hI> <path>
                    val parts = entry.split(' ', limit = 9)
                    if (parts.size >= 9) {
                        files += ChangedFile(
                            path = parts[8],
                            index = parts[1].getOrElse(0) { '.' },
                            worktree = parts[1].getOrElse(1) { '.' },
                        )
                    }
                }
                '2' -> {
                    // 2 <XY> <sub> <mH> <mI> <mW> <hH> <hI> <X><score> <path> NUL <origPath>
                    val parts = entry.split(' ', limit = 10)
                    val orig = records.getOrNull(i)
                    i++
                    if (parts.size >= 10) {
                        files += ChangedFile(
                            path = parts[9],
                            origPath = orig?.takeIf { it.isNotEmpty() },
                            index = parts[1].getOrElse(0) { '.' },
                            worktree = parts[1].getOrElse(1) { '.' },
                        )
                    }
                }
                'u' -> {
                    // u <XY> <sub> <m1> <m2> <m3> <mW> <h1> <h2> <h3> <path>
                    val parts = entry.split(' ', limit = 11)
                    if (parts.size >= 11) {
                        files += ChangedFile(
                            path = parts[10],
                            index = parts[1].getOrElse(0) { 'U' },
                            worktree = parts[1].getOrElse(1) { 'U' },
                            conflicted = true,
                        )
                    }
                }
                '?' -> files += ChangedFile(path = entry.substring(2), untracked = true, worktree = '?')
                '!' -> files += ChangedFile(path = entry.substring(2), ignored = true)
            }
        }

        return RepoStatus(
            branch = branch,
            upstream = upstream,
            ahead = ahead,
            behind = behind,
            head = head,
            detached = detached,
            files = files.sortedWith(compareBy({ !it.conflicted }, { it.path })),
        )
    }

    /**
     * `git for-each-ref` formatted as
     * `%(refname:short)FS%(upstream:short)FS%(HEAD)FS%(worktreepath)FS<local|remote>`.
     */
    fun parseBranches(output: String): List<Branch> = output.lineSequence()
        .map { it.trimEnd('\r') }
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val f = line.split(FS)
            val name = f.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (name.endsWith("/HEAD")) return@mapNotNull null
            Branch(
                name = name,
                isRemote = f.getOrNull(4) == "remote",
                isCurrent = f.getOrNull(2) == "*",
                upstream = f.getOrNull(1)?.takeIf { it.isNotBlank() },
                checkedOutIn = f.getOrNull(3)?.takeIf { it.isNotBlank() },
            )
        }
        .toList()

    /** `git log --format=%HFS%hFS%sFS%anFS%arFS%D`. */
    fun parseLog(output: String): List<CommitInfo> = output.lineSequence()
        .map { it.trimEnd('\r') }
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val f = line.split(FS)
            if (f.size < 5) return@mapNotNull null
            CommitInfo(
                hash = f[0],
                shortHash = f[1],
                subject = f[2],
                author = f[3],
                relativeDate = f[4],
                refs = f.getOrNull(5)
                    ?.split(", ")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    .orEmpty(),
            )
        }
        .toList()

    /** Unified diff produced by `git diff`, possibly covering several files. */
    fun parseDiff(output: String): List<FileDiff> {
        val files = mutableListOf<FileDiff>()

        var path: String? = null
        var oldPath: String? = null
        var isNew = false
        var isDeleted = false
        var isRename = false
        var isBinary = false
        var mode: String? = null
        var hunks = mutableListOf<DiffHunk>()

        var hunkHeader: String? = null
        var hunkOldStart = 0
        var hunkOldCount = 0
        var hunkNewStart = 0
        var hunkNewCount = 0
        var hunkLines = mutableListOf<DiffLine>()
        var oldNo = 0
        var newNo = 0

        fun flushHunk() {
            val header = hunkHeader ?: return
            hunks += DiffHunk(header, hunkOldStart, hunkOldCount, hunkNewStart, hunkNewCount, hunkLines.toList())
            hunkHeader = null
            hunkLines = mutableListOf()
        }

        fun flushFile() {
            flushHunk()
            val p = path ?: return
            files += FileDiff(
                path = p,
                oldPath = oldPath?.takeIf { it != p },
                hunks = hunks.toList(),
                isBinary = isBinary,
                isNew = isNew,
                isDeleted = isDeleted,
                isRename = isRename,
                mode = mode,
            )
            path = null; oldPath = null
            isNew = false; isDeleted = false; isRename = false; isBinary = false; mode = null
            hunks = mutableListOf()
        }

        for (raw in output.split('\n')) {
            val line = raw.trimEnd('\r')
            when {
                line.startsWith("diff --git ") -> {
                    flushFile()
                    val (a, b) = splitDiffGitPaths(line)
                    oldPath = a
                    path = b ?: a
                }
                line.startsWith("new file mode ") -> {
                    isNew = true; mode = line.removePrefix("new file mode ").trim()
                }
                line.startsWith("deleted file mode ") -> {
                    isDeleted = true; mode = line.removePrefix("deleted file mode ").trim()
                }
                line.startsWith("rename from ") -> {
                    isRename = true; oldPath = unquotePath(line.removePrefix("rename from "))
                }
                line.startsWith("rename to ") -> {
                    isRename = true; path = unquotePath(line.removePrefix("rename to "))
                }
                line.startsWith("Binary files ") || line.startsWith("GIT binary patch") -> isBinary = true
                line.startsWith("--- ") -> {
                    val v = line.removePrefix("--- ")
                    if (v != "/dev/null") oldPath = stripPrefix(unquotePath(v))
                }
                line.startsWith("+++ ") -> {
                    val v = line.removePrefix("+++ ")
                    if (v != "/dev/null") path = stripPrefix(unquotePath(v))
                }
                line.startsWith("@@") -> {
                    flushHunk()
                    val range = line.removePrefix("@@").substringBefore("@@").trim()
                    val (o, n) = parseHunkRanges(range)
                    hunkOldStart = o.first; hunkOldCount = o.second
                    hunkNewStart = n.first; hunkNewCount = n.second
                    oldNo = hunkOldStart
                    newNo = hunkNewStart
                    hunkHeader = line
                }
                hunkHeader != null -> when {
                    line.startsWith("+") -> {
                        hunkLines += DiffLine(DiffLineType.ADD, line.substring(1), null, newNo); newNo++
                    }
                    line.startsWith("-") -> {
                        hunkLines += DiffLine(DiffLineType.DELETE, line.substring(1), oldNo, null); oldNo++
                    }
                    line.startsWith("\\") ->
                        hunkLines += DiffLine(DiffLineType.NO_NEWLINE, line.substring(1).trim())
                    line.startsWith(" ") -> {
                        hunkLines += DiffLine(DiffLineType.CONTEXT, line.substring(1), oldNo, newNo); oldNo++; newNo++
                    }
                    // An empty line inside a hunk is a context line whose single space git dropped.
                    line.isEmpty() && hunkLines.size < hunkOldCount + hunkNewCount -> {
                        hunkLines += DiffLine(DiffLineType.CONTEXT, "", oldNo, newNo); oldNo++; newNo++
                    }
                    else -> flushHunk()
                }
            }
        }
        flushFile()
        return files
    }

    private fun splitDiffGitPaths(line: String): Pair<String, String?> {
        val rest = line.removePrefix("diff --git ")
        // Paths get quoted when they contain unusual bytes; the common case is plain "a/x b/x".
        if (rest.startsWith("\"")) {
            val end = rest.indexOf("\" ", 1)
            if (end > 0) {
                val a = unquotePath(rest.substring(0, end + 1))
                val b = unquotePath(rest.substring(end + 2))
                return stripPrefix(a) to stripPrefix(b)
            }
        }
        val bIdx = rest.lastIndexOf(" b/")
        if (rest.startsWith("a/") && bIdx > 0) {
            return rest.substring(2, bIdx) to rest.substring(bIdx + 3)
        }
        val half = rest.split(' ')
        return stripPrefix(half.firstOrNull().orEmpty()) to half.getOrNull(1)?.let { stripPrefix(it) }
    }

    private fun stripPrefix(p: String): String =
        if (p.length > 2 && p[1] == '/' && p[0] in "abiwco") p.substring(2) else p

    private fun unquotePath(p: String): String {
        val t = p.trim().substringBefore('\t')
        if (!t.startsWith("\"") || !t.endsWith("\"") || t.length < 2) return t
        val body = t.substring(1, t.length - 1)
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                i++
                when (val e = body[i]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    else -> sb.append(e)
                }
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun parseHunkRanges(range: String): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        var old = 0 to 1
        var new = 0 to 1
        for (part in range.split(' ')) {
            val nums = part.drop(1).split(',')
            val start = nums.getOrNull(0)?.toIntOrNull() ?: continue
            val count = nums.getOrNull(1)?.toIntOrNull() ?: 1
            when (part.firstOrNull()) {
                '-' -> old = start to count
                '+' -> new = start to count
            }
        }
        return old to new
    }

    private const val OURS_MARKER = "<<<<<<<"
    private const val BASE_MARKER = "|||||||"
    private const val SPLIT_MARKER = "======="
    private const val THEIRS_MARKER = ">>>>>>>"

    private fun String.isMarker(marker: String) = this == marker || startsWith("$marker ")

    private fun String.markerLabel(marker: String, fallback: String) =
        removePrefix(marker).trim().ifEmpty { fallback }

    /**
     * Splits a file that git left conflict markers in into plain text and conflict regions.
     * Handles both the `merge` and `diff3` conflict styles (the latter adds a `|||||||` base section).
     */
    fun parseConflicts(path: String, content: String): ConflictedFile {
        val segments = mutableListOf<ConflictSegment>()
        val lines = content.split('\n')
        val plain = mutableListOf<String>()
        var i = 0
        var nextId = 0

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                segments += ConflictSegment.Text(plain.toList())
                plain.clear()
            }
        }

        while (i < lines.size) {
            val line = lines[i]
            if (!line.isMarker(OURS_MARKER)) {
                plain += line
                i++
                continue
            }
            val oursLabel = line.markerLabel(OURS_MARKER, "ours")
            val ours = mutableListOf<String>()
            val base = mutableListOf<String>()
            val theirs = mutableListOf<String>()
            var section = 0 // 0 = ours, 1 = base, 2 = theirs
            var sawBase = false
            var theirsLabel = "theirs"
            var closed = false
            i++
            while (i < lines.size) {
                val l = lines[i]
                when {
                    l.isMarker(BASE_MARKER) -> { section = 1; sawBase = true; i++ }
                    l.isMarker(SPLIT_MARKER) -> { section = 2; i++ }
                    l.isMarker(THEIRS_MARKER) -> {
                        theirsLabel = l.markerLabel(THEIRS_MARKER, "theirs")
                        closed = true
                        i++
                        break
                    }
                    else -> {
                        when (section) {
                            0 -> ours += l
                            1 -> base += l
                            else -> theirs += l
                        }
                        i++
                    }
                }
            }
            if (!closed) {
                // Unterminated marker: keep the raw text rather than silently losing content.
                plain += line
                plain += ours
                if (sawBase) { plain += BASE_MARKER; plain += base }
                if (section >= 2) { plain += SPLIT_MARKER; plain += theirs }
                continue
            }
            flushPlain()
            segments += ConflictSegment.Conflict(
                id = nextId++,
                region = ConflictRegion(
                    oursLabel = oursLabel,
                    theirsLabel = theirsLabel,
                    ours = ours,
                    base = if (sawBase) base else null,
                    theirs = theirs,
                ),
            )
        }
        flushPlain()
        return ConflictedFile(path, segments)
    }

    /** Rebuilds file content from resolved segments; unresolved regions keep their markers. */
    fun renderResolved(file: ConflictedFile): String {
        val out = mutableListOf<String>()
        for (segment in file.segments) {
            when (segment) {
                is ConflictSegment.Text -> out += segment.lines
                is ConflictSegment.Conflict -> {
                    val r = segment.region
                    when (segment.resolution) {
                        io.mainactor.worktree.model.Resolution.OURS -> out += r.ours
                        io.mainactor.worktree.model.Resolution.THEIRS -> out += r.theirs
                        io.mainactor.worktree.model.Resolution.BOTH_OURS_FIRST -> { out += r.ours; out += r.theirs }
                        io.mainactor.worktree.model.Resolution.BOTH_THEIRS_FIRST -> { out += r.theirs; out += r.ours }
                        io.mainactor.worktree.model.Resolution.UNRESOLVED -> {
                            out += "$OURS_MARKER ${r.oursLabel}"
                            out += r.ours
                            if (r.base != null) {
                                out += "$BASE_MARKER base"
                                out += r.base
                            }
                            out += SPLIT_MARKER
                            out += r.theirs
                            out += "$THEIRS_MARKER ${r.theirsLabel}"
                        }
                    }
                }
            }
        }
        return out.joinToString("\n")
    }
}
