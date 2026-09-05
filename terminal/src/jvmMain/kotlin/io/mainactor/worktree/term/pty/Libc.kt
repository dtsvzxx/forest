package io.mainactor.worktree.term.pty

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.StructLayout
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * The libc calls a pseudo-terminal needs, through `java.lang.foreign`.
 *
 * No JNA and no native library of our own: every symbol here is in libc, which the default lookup
 * finds, so the terminal adds nothing to the bundle that has to be signed, hardened or notarized.
 *
 * Two of these bindings are easy to get silently wrong:
 *
 * - **`ioctl` is variadic.** Without [Linker.Option.firstVariadicArg] the arguments go in registers
 *   on arm64 macOS where the callee reads them off the stack, and the call quietly does the wrong
 *   thing rather than failing.
 * - **`posix_spawn` returns its errno** instead of setting it, unlike everything else here.
 */
internal object Libc {

    private val linker: Linker = Linker.nativeLinker()
    private val libc = linker.defaultLookup()

    val errnoLayout: StructLayout = Linker.Option.captureStateLayout()
    private val errnoHandle = errnoLayout.varHandle(MemoryLayout.PathElement.groupElement("errno"))
    private val captureErrno = Linker.Option.captureCallState("errno")

    /** Reads the errno a call captured into [state]. */
    fun errno(state: MemorySegment): Int = errnoHandle.get(state, 0L) as Int

    /** A segment to capture errno into. One per thread that makes calls, never shared. */
    fun errnoSegment(arena: Arena): MemorySegment = arena.allocate(errnoLayout)

    private fun handle(
        name: String,
        descriptor: FunctionDescriptor,
        vararg options: Linker.Option,
    ): MethodHandle = linker.downcallHandle(
        libc.find(name).orElseThrow { UnsatisfiedLinkError("libc has no $name") },
        descriptor,
        *options,
    )

    private val INT = ValueLayout.JAVA_INT
    private val LONG = ValueLayout.JAVA_LONG
    private val SHORT = ValueLayout.JAVA_SHORT
    private val PTR = ValueLayout.ADDRESS

    private val posixOpenpt = handle("posix_openpt", FunctionDescriptor.of(INT, INT), captureErrno)
    private val grantpt = handle("grantpt", FunctionDescriptor.of(INT, INT), captureErrno)
    private val unlockpt = handle("unlockpt", FunctionDescriptor.of(INT, INT), captureErrno)
    private val ptsnameR = handle("ptsname_r", FunctionDescriptor.of(INT, INT, PTR, LONG), captureErrno)
    private val openFile = handle(
        "open",
        FunctionDescriptor.of(INT, PTR, INT),
        captureErrno,
        Linker.Option.firstVariadicArg(2),
    )
    private val closeFd = handle("close", FunctionDescriptor.of(INT, INT), captureErrno)
    private val readFd = handle("read", FunctionDescriptor.of(LONG, INT, PTR, LONG), captureErrno)
    private val writeFd = handle("write", FunctionDescriptor.of(LONG, INT, PTR, LONG), captureErrno)
    private val ioctlFd = handle(
        "ioctl",
        FunctionDescriptor.of(INT, INT, LONG, PTR),
        captureErrno,
        Linker.Option.firstVariadicArg(2),
    )
    private val killPid = handle("kill", FunctionDescriptor.of(INT, INT, INT), captureErrno)
    private val waitpidCall = handle("waitpid", FunctionDescriptor.of(INT, INT, PTR, INT), captureErrno)

    private val faInit = handle("posix_spawn_file_actions_init", FunctionDescriptor.of(INT, PTR))
    private val faDestroy = handle("posix_spawn_file_actions_destroy", FunctionDescriptor.of(INT, PTR))
    private val faAddOpen = handle(
        "posix_spawn_file_actions_addopen",
        FunctionDescriptor.of(INT, PTR, INT, PTR, INT, SHORT),
    )
    /**
     * The child's working directory, as a file action.
     *
     * `posix_spawn` has no cwd argument, and the alternative — wrapping every command in
     * `sh -c 'cd … && exec …'` — would rewrite the command line a pane was asked to run. The `_np`
     * spelling is the one that exists everywhere we ship (Darwin since 10.15, glibc since 2.29);
     * Darwin 26 renamed it, so both are looked up and whichever is present wins.
     */
    private val faAddChdir: MethodHandle? =
        listOf("posix_spawn_file_actions_addchdir_np", "posix_spawn_file_actions_addchdir")
            .firstNotNullOfOrNull { name ->
                libc.find(name).map { linker.downcallHandle(it, FunctionDescriptor.of(INT, PTR, PTR)) }.orElse(null)
            }

    private val attrInit = handle("posix_spawnattr_init", FunctionDescriptor.of(INT, PTR))
    private val attrDestroy = handle("posix_spawnattr_destroy", FunctionDescriptor.of(INT, PTR))
    private val attrSetFlags = handle("posix_spawnattr_setflags", FunctionDescriptor.of(INT, PTR, SHORT))
    private val spawn = handle("posix_spawn", FunctionDescriptor.of(INT, PTR, PTR, PTR, PTR, PTR, PTR))

    fun posixOpenpt(state: MemorySegment, flags: Int): Int = posixOpenpt.invokeExact(state, flags) as Int
    fun grantpt(state: MemorySegment, fd: Int): Int = grantpt.invokeExact(state, fd) as Int
    fun unlockpt(state: MemorySegment, fd: Int): Int = unlockpt.invokeExact(state, fd) as Int

    fun ptsnameR(state: MemorySegment, fd: Int, buffer: MemorySegment, size: Long): Int =
        ptsnameR.invokeExact(state, fd, buffer, size) as Int

    fun open(state: MemorySegment, path: MemorySegment, flags: Int): Int =
        openFile.invokeExact(state, path, flags) as Int

    fun close(state: MemorySegment, fd: Int): Int = closeFd.invokeExact(state, fd) as Int

    fun read(state: MemorySegment, fd: Int, buffer: MemorySegment, count: Long): Long =
        readFd.invokeExact(state, fd, buffer, count) as Long

    fun write(state: MemorySegment, fd: Int, buffer: MemorySegment, count: Long): Long =
        writeFd.invokeExact(state, fd, buffer, count) as Long

    fun ioctl(state: MemorySegment, fd: Int, request: Long, argument: MemorySegment): Int =
        ioctlFd.invokeExact(state, fd, request, argument) as Int

    fun kill(state: MemorySegment, pid: Int, signal: Int): Int = killPid.invokeExact(state, pid, signal) as Int

    fun waitpid(state: MemorySegment, pid: Int, status: MemorySegment, options: Int): Int =
        waitpidCall.invokeExact(state, pid, status, options) as Int

    fun fileActionsInit(actions: MemorySegment): Int = faInit.invokeExact(actions) as Int
    fun fileActionsDestroy(actions: MemorySegment): Int = faDestroy.invokeExact(actions) as Int

    fun fileActionsAddOpen(actions: MemorySegment, fd: Int, path: MemorySegment, flags: Int, mode: Short): Int =
        faAddOpen.invokeExact(actions, fd, path, flags, mode) as Int

    /** Null when libc is older than every spelling of the call — nowhere we support. */
    fun fileActionsAddChdir(actions: MemorySegment, path: MemorySegment): Int? =
        faAddChdir?.let { it.invokeExact(actions, path) as Int }

    fun spawnAttrInit(attr: MemorySegment): Int = attrInit.invokeExact(attr) as Int
    fun spawnAttrDestroy(attr: MemorySegment): Int = attrDestroy.invokeExact(attr) as Int
    fun spawnAttrSetFlags(attr: MemorySegment, flags: Short): Int = attrSetFlags.invokeExact(attr, flags) as Int

    /** Returns 0, or the errno itself — this one does not set it. */
    fun posixSpawn(
        pid: MemorySegment,
        path: MemorySegment,
        fileActions: MemorySegment,
        attributes: MemorySegment,
        argv: MemorySegment,
        envp: MemorySegment,
    ): Int = spawn.invokeExact(pid, path, fileActions, attributes, argv, envp) as Int
}

/**
 * The constants that differ between the two Unixes we run on.
 *
 * Numbers copied from a system header are exactly the kind of thing that is wrong on the other
 * platform and nobody notices for a year, so [PtyTest] sets a size and reads it back rather than
 * trusting the pair below.
 */
internal object Native {
    private val osName = System.getProperty("os.name").orEmpty().lowercase()
    val isMac: Boolean = "mac" in osName || "darwin" in osName

    const val O_RDWR = 0x0002
    val O_NOCTTY: Int = if (isMac) 0x20000 else 0x100

    /** `POSIX_SPAWN_SETSID`: 0x0400 on Darwin, 0x80 on glibc (since 2.26). */
    val POSIX_SPAWN_SETSID: Short = if (isMac) 0x0400 else 0x80

    val TIOCSWINSZ: Long = if (isMac) 0x80087467L else 0x5414L
    val TIOCGWINSZ: Long = if (isMac) 0x40087468L else 0x5413L

    const val SIGHUP = 1
    const val SIGKILL = 9
    const val WNOHANG = 1
    const val EIO = 5
    const val EINTR = 4

    /**
     * `posix_spawn_file_actions_t` and `posix_spawnattr_t` are an opaque pointer on Darwin and a
     * struct on glibc. Over-allocating costs nothing and spares us a per-platform size.
     */
    const val OPAQUE_STRUCT_BYTES = 1024L
}
