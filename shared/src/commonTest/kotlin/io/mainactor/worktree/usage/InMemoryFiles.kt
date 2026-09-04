package io.mainactor.worktree.usage

import io.mainactor.worktree.platform.FileSystemAccess

/** Enough of a filesystem to append to files and read them back by byte offset. */
internal class InMemoryFiles : FileSystemAccess {
    private val files = LinkedHashMap<String, ByteArray>()
    private val dirs = HashSet<String>()

    /** Every (offset, length) handed to [readFrom], so a test can prove nothing was re-read. */
    val reads = mutableListOf<Pair<Long, Int>>()

    fun write(path: String, text: String) {
        files[path] = text.encodeToByteArray()
        registerParents(path)
    }

    fun append(path: String, text: String) {
        files[path] = (files[path] ?: ByteArray(0)) + text.encodeToByteArray()
        registerParents(path)
    }

    private fun registerParents(path: String) {
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            dirs += parent
            parent = parent.substringBeforeLast('/', "")
        }
    }

    override fun fileSize(path: String): Long = files[path]?.size?.toLong() ?: 0

    override fun readFrom(path: String, offset: Long, maxBytes: Int): ByteArray {
        reads += offset to maxBytes
        val bytes = files[path] ?: return ByteArray(0)
        if (offset >= bytes.size) return ByteArray(0)
        return bytes.copyOfRange(offset.toInt(), minOf(bytes.size.toLong(), offset + maxBytes).toInt())
    }

    override fun listDirectory(path: String): List<String> {
        val prefix = "$path/"
        return (files.keys + dirs)
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('/') }
            .distinct()
    }

    override fun exists(path: String) = files.containsKey(path) || path in dirs
    override fun isDirectory(path: String) = path in dirs
    override fun readText(path: String) = files[path]?.decodeToString().orEmpty()
    override fun writeText(path: String, text: String) = write(path, text)
    override fun createDirectories(path: String) { dirs += path; registerParents("$path/x") }
    override fun homeDir() = "/home"
    override fun nameOf(path: String) = path.substringAfterLast('/')
    override fun parentOf(path: String): String? = path.substringBeforeLast('/').ifEmpty { null }
    override fun resolve(base: String, child: String) = "$base/$child"
    override fun canonicalPath(path: String) = path
    override fun findOnPath(name: String): String? = null
    override fun lastModifiedAt(path: String) = 0L
    override fun now() = 0L
}
