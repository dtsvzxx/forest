package io.mainactor.worktree.term.pty

import io.mainactor.worktree.term.Pty
import io.mainactor.worktree.term.PtyLauncher
import io.mainactor.worktree.term.WinSize
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Starts a process on a pseudo-terminal without a helper binary.
 *
 * The usual way to do this is `fork`, `setsid`, open the slave to take it as the controlling
 * terminal, then `exec` — and because `fork` in a threaded runtime is a trap, every JVM library
 * that does it ships a small native executable to do the forking. That helper is the reason
 * Apple rejected this app's first notarization, so it is worth not having one:
 *
 * - `POSIX_SPAWN_SETSID` makes the child a session leader with no controlling terminal, and
 * - a file action that *opens the slave by path* onto fd 0 is then what gives it one, because a
 *   session leader opening a terminal without `O_NOCTTY` acquires it.
 *
 * Two orderings here are not free choices, and both were established by probing rather than by
 * reading a manual page:
 *
 * - **Darwin gives the pty no tty structure until a slave is open**, so `TIOCSWINSZ` on the master
 *   fails with `ENOTTY` unless the parent opens a slave first. It does, and closes it immediately
 *   after spawning — while the parent holds a slave open, the master never sees end of file.
 * - **A pty discards buffered output when its last slave closes**, so the caller must read before
 *   reaping. [UnixPty] reaps lazily for exactly that reason.
 */
class FfmPtyLauncher : PtyLauncher {

    override fun start(
        command: List<String>,
        workDir: String,
        env: Map<String, String>,
        size: WinSize,
    ): Pty {
        require(command.isNotEmpty()) { "no command to run" }
        require('/' in command[0]) {
            // posix_spawn does not search PATH, and posix_spawnp would search *ours* rather than
            // the child's — the shell is resolved before it gets here, so this should never fire.
            "the command must be an absolute path, not ${command[0]}"
        }

        Arena.ofConfined().use { arena ->
            val state = Libc.errnoSegment(arena)

            val master = Libc.posixOpenpt(state, Native.O_RDWR or Native.O_NOCTTY)
            if (master < 0) throw IOException("cannot open a pseudo-terminal: errno ${Libc.errno(state)}")

            var ok = false
            try {
                if (Libc.grantpt(state, master) != 0) fail(state, "grantpt")
                if (Libc.unlockpt(state, master) != 0) fail(state, "unlockpt")

                val nameBuffer = arena.allocate(SLAVE_NAME_BYTES)
                if (Libc.ptsnameR(state, master, nameBuffer, SLAVE_NAME_BYTES) != 0) fail(state, "ptsname_r")
                val slavePath = arena.allocateFrom(nameBuffer.getString(0))

                // See the class KDoc: without this the window size cannot be set on Darwin.
                val parentSlave = Libc.open(state, slavePath, Native.O_RDWR or Native.O_NOCTTY)
                if (parentSlave < 0) fail(state, "open(slave)")

                try {
                    setWindowSize(arena, state, master, size)
                    val pid = spawn(arena, state, command, workDir, env, slavePath)
                    ok = true
                    return UnixPty(master, pid)
                } finally {
                    Libc.close(state, parentSlave)
                }
            } finally {
                if (!ok) Libc.close(state, master)
            }
        }
    }

    private fun setWindowSize(arena: Arena, state: MemorySegment, master: Int, size: WinSize) {
        val winSize = arena.allocate(WINSIZE_BYTES)
        winSize.set(ValueLayout.JAVA_SHORT, 0, size.rows.toShort())
        winSize.set(ValueLayout.JAVA_SHORT, 2, size.columns.toShort())
        winSize.set(ValueLayout.JAVA_SHORT, 4, 0)
        winSize.set(ValueLayout.JAVA_SHORT, 6, 0)
        if (Libc.ioctl(state, master, Native.TIOCSWINSZ, winSize) != 0) fail(state, "TIOCSWINSZ")
    }

    private fun spawn(
        arena: Arena,
        state: MemorySegment,
        command: List<String>,
        workDir: String,
        env: Map<String, String>,
        slavePath: MemorySegment,
    ): Int {
        val actions = arena.allocate(Native.OPAQUE_STRUCT_BYTES)
        val attributes = arena.allocate(Native.OPAQUE_STRUCT_BYTES)
        if (Libc.fileActionsInit(actions) != 0) throw IOException("posix_spawn_file_actions_init failed")
        try {
            if (Libc.spawnAttrInit(attributes) != 0) throw IOException("posix_spawnattr_init failed")
            try {
                for (fd in 0..2) {
                    // Deliberately no O_NOCTTY: this open is what takes the controlling terminal.
                    val rc = Libc.fileActionsAddOpen(actions, fd, slavePath, Native.O_RDWR, 0)
                    if (rc != 0) throw IOException("cannot attach fd $fd to the terminal: errno $rc")
                }
                val chdir = Libc.fileActionsAddChdir(actions, arena.allocateFrom(workDir))
                    ?: throw IOException("libc cannot set a spawned process's directory")
                if (chdir != 0) throw IOException("cannot start in $workDir: errno $chdir")

                if (Libc.spawnAttrSetFlags(attributes, Native.POSIX_SPAWN_SETSID) != 0) {
                    throw IOException("posix_spawnattr_setflags failed")
                }

                val pidSlot = arena.allocate(ValueLayout.JAVA_INT)
                val rc = Libc.posixSpawn(
                    pidSlot,
                    arena.allocateFrom(command[0]),
                    actions,
                    attributes,
                    nullTerminated(arena, command),
                    nullTerminated(arena, env.map { (name, value) -> "$name=$value" }),
                )
                // This one hands back the errno rather than setting it.
                if (rc != 0) throw IOException("cannot run ${command[0]}: errno $rc")
                return pidSlot.get(ValueLayout.JAVA_INT, 0)
            } finally {
                Libc.spawnAttrDestroy(attributes)
            }
        } finally {
            Libc.fileActionsDestroy(actions)
        }
    }

    /** A `char *const[]` — the array C expects, closed with a null pointer. */
    private fun nullTerminated(arena: Arena, values: List<String>): MemorySegment {
        val array = arena.allocate(ValueLayout.ADDRESS, (values.size + 1).toLong())
        values.forEachIndexed { index, value ->
            array.setAtIndex(ValueLayout.ADDRESS, index.toLong(), arena.allocateFrom(value))
        }
        array.setAtIndex(ValueLayout.ADDRESS, values.size.toLong(), MemorySegment.NULL)
        return array
    }

    private fun fail(state: MemorySegment, what: String): Nothing =
        throw IOException("$what failed with errno ${Libc.errno(state)}")

    private companion object {
        const val SLAVE_NAME_BYTES = 128L
        const val WINSIZE_BYTES = 8L
    }
}
