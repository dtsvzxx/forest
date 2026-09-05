package io.mainactor.worktree.term.pty

import io.mainactor.worktree.term.Pty
import io.mainactor.worktree.term.WinSize
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * A pseudo-terminal on Darwin and Linux, over `java.lang.foreign`.
 *
 * The arena is [Arena.ofAuto] on purpose. A confined or shared arena would have to be closed, and
 * closing it under a reader blocked inside `read(2)` is a use-after-free the JVM turns into an
 * exception on whichever thread got there first; letting the segments live as long as they are
 * reachable costs a few kilobytes per pane and removes the race entirely.
 */
internal class UnixPty(
    private val master: Int,
    private val pid: Int,
) : Pty {

    private val arena = Arena.ofAuto()

    // One capture segment per caller: errno is per-thread, and sharing a segment between a reader
    // blocked in read(2) and a writer would let one call's failure be read as the other's.
    private val readState = Libc.errnoSegment(arena)
    private val readBuffer = arena.allocate(BUFFER_BYTES)

    private val writeLock = Any()
    private val writeState = Libc.errnoSegment(arena)
    private val writeBuffer = arena.allocate(BUFFER_BYTES)

    private val controlLock = Any()
    private val controlState = Libc.errnoSegment(arena)
    private val statusSlot = arena.allocate(ValueLayout.JAVA_INT)
    private val winSizeSlot = arena.allocate(WINSIZE_BYTES)

    @Volatile private var status: Int? = null
    @Volatile private var closed = false

    override val isAlive: Boolean
        get() {
            reap()
            return status == null
        }

    override val exitCode: Int?
        get() {
            reap()
            return status
        }

    override fun read(into: ByteArray): Int {
        val want = minOf(into.size.toLong(), BUFFER_BYTES)
        while (true) {
            val n = Libc.read(readState, master, readBuffer, want)
            if (n > 0) {
                MemorySegment.copy(readBuffer, ValueLayout.JAVA_BYTE, 0, into, 0, n.toInt())
                return n.toInt()
            }
            // 0 is a clean end of file on Darwin. Linux reports EIO instead once the last slave
            // closes, which means the same thing and must not be raised as a failure.
            if (n == 0L) return -1
            when (Libc.errno(readState)) {
                Native.EINTR -> continue
                else -> return -1
            }
        }
    }

    override fun write(bytes: ByteArray, length: Int) {
        require(length <= bytes.size) { "asked to write $length bytes of ${bytes.size}" }
        synchronized(writeLock) {
            var written = 0
            while (written < length) {
                val chunk = minOf((length - written).toLong(), BUFFER_BYTES)
                MemorySegment.copy(bytes, written, writeBuffer, ValueLayout.JAVA_BYTE, 0L, chunk.toInt())
                val n = Libc.write(writeState, master, writeBuffer, chunk)
                if (n < 0) {
                    if (Libc.errno(writeState) == Native.EINTR) continue
                    throw IOException("write to the terminal failed with errno ${Libc.errno(writeState)}")
                }
                written += n.toInt()
            }
        }
    }

    override fun resize(size: WinSize) {
        synchronized(controlLock) {
            if (closed) return
            // struct winsize { unsigned short ws_row, ws_col, ws_xpixel, ws_ypixel; }
            winSizeSlot.set(ValueLayout.JAVA_SHORT, 0, size.rows.toShort())
            winSizeSlot.set(ValueLayout.JAVA_SHORT, 2, size.columns.toShort())
            winSizeSlot.set(ValueLayout.JAVA_SHORT, 4, 0)
            winSizeSlot.set(ValueLayout.JAVA_SHORT, 6, 0)
            Libc.ioctl(controlState, master, Native.TIOCSWINSZ, winSizeSlot)
        }
    }

    /** Reads the size back out of the kernel. Only the tests need this; nothing draws from it. */
    internal fun windowSize(): WinSize = synchronized(controlLock) {
        Libc.ioctl(controlState, master, Native.TIOCGWINSZ, winSizeSlot)
        WinSize(
            rows = winSizeSlot.get(ValueLayout.JAVA_SHORT, 0).toInt(),
            columns = winSizeSlot.get(ValueLayout.JAVA_SHORT, 2).toInt(),
        )
    }

    /**
     * Hang up, then insist.
     *
     * `SIGHUP` is what a terminal closing means, and a shell passes it on to whatever it is running;
     * the wait gives that a moment to happen before `SIGKILL`, which does not. Killing before
     * closing the master matters: the child's exit is what closes the last slave, and *that* is what
     * wakes a reader blocked in `read(2)`.
     */
    override fun close() {
        synchronized(controlLock) {
            if (closed) return
            closed = true
        }
        if (isAlive) {
            Libc.kill(controlState, pid, Native.SIGHUP)
            val deadline = System.nanoTime() + GRACE_NANOS
            while (isAlive && System.nanoTime() < deadline) Thread.sleep(GRACE_STEP_MS)
            if (isAlive) Libc.kill(controlState, pid, Native.SIGKILL)
        }
        synchronized(controlLock) { Libc.close(controlState, master) }
    }

    /**
     * Collects the exit status, without blocking.
     *
     * Lazy rather than a thread per session: everything that cares about a dead pane asks
     * [isAlive] — the manager before handing a session back, the header while it is on screen — so
     * the child is reaped by the first question anyone asks about it.
     */
    private fun reap() {
        if (status != null) return
        synchronized(controlLock) {
            if (status != null) return
            val reaped = Libc.waitpid(controlState, pid, statusSlot, Native.WNOHANG)
            if (reaped != pid) return
            val raw = statusSlot.get(ValueLayout.JAVA_INT, 0)
            // WIFEXITED / WEXITSTATUS / WTERMSIG, which are macros rather than symbols.
            status = if (raw and 0x7f == 0) (raw shr 8) and 0xff else 128 + (raw and 0x7f)
        }
    }

    private companion object {
        const val BUFFER_BYTES = 8192L
        const val WINSIZE_BYTES = 8L
        const val GRACE_NANOS = 200_000_000L
        const val GRACE_STEP_MS = 5L
    }
}
