package de.kmob.platform.posix

import de.kmob.platform.Platform
import de.kmob.platform.support.ExecutableSearch
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.PATH_MAX
import platform.posix.X_OK
import platform.posix.access
import platform.posix.getenv
import platform.posix.isatty
import platform.posix.realpath
import platform.posix.write
import kotlin.time.Clock
import kotlin.time.Instant

/** macOS und Linux über platform.posix (design §5.9). */
class PosixPlatform : Platform {

    override val pathSeparator = '/'

    override fun env(name: String): String? = getenv(name)?.toKString()

    override fun realPath(path: String): String? = memScoped {
        val buf = allocArray<ByteVar>(PATH_MAX + 1)
        realpath(path, buf)?.toKString()
    }

    override fun isInteractive(): Boolean = isatty(0) == 1 && isatty(2) == 1

    override fun readLine(): String? = readlnOrNull()

    override fun findInPath(binary: String): String? = ExecutableSearch.POSIX.find(binary, env("PATH"), ::isExecutable)

    override fun isExecutable(path: String): Boolean {
        // Symlinks auflösen (z. B. Homebrew: /opt/homebrew/bin/mob -> ../Cellar/…), Okio folgt ihnen nicht
        val resolved = realPath(path) ?: return false
        val meta = runCatching { FileSystem.SYSTEM.metadataOrNull(resolved.toPath()) }.getOrNull() ?: return false
        return meta.isRegularFile && access(path, X_OK) == 0
    }

    override fun homeDir(): String = env("HOME")?.takeIf { it.isNotEmpty() } ?: "/"

    override fun now(): Instant = Clock.System.now()

    override fun printOut(line: String) = writeFd(1, line + "\n")

    override fun printErr(line: String) = writeFd(2, line + "\n")

    private fun writeFd(fd: Int, text: String) {
        val bytes = text.encodeToByteArray()
        var off = 0
        bytes.usePinned { pinned ->
            while (off < bytes.size) {
                val n = write(fd, pinned.addressOf(off), (bytes.size - off).convert())
                if (n <= 0) return
                off += n.toInt()
            }
        }
    }
}
