package de.kmob.platform.windows

import de.kmob.cinterop.windows.kmob_win_command_line
import de.kmob.cinterop.windows.kmob_win_error_message
import de.kmob.cinterop.windows.kmob_win_free_argv
import de.kmob.cinterop.windows.kmob_win_split_command_line
import de.kmob.cinterop.windows.kmob_win_final_path
import de.kmob.cinterop.windows.kmob_win_is_console
import de.kmob.cinterop.windows.kmob_win_read_byte
import de.kmob.cinterop.windows.kmob_win_read_console_line
import de.kmob.cinterop.windows.kmob_win_write
import de.kmob.platform.Platform
import de.kmob.platform.support.ExecutableSearch
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.wcstr
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.windows.GetEnvironmentVariableW
import platform.windows.STD_ERROR_HANDLE
import platform.windows.STD_INPUT_HANDLE
import platform.windows.STD_OUTPUT_HANDLE
import kotlin.time.Clock
import kotlin.time.Instant

private const val MAX_PATH_CHARS = 32_768
private const val MAX_LINE_CHARS = 4_096

/** Windows über Win32-Unicode-APIs (design §5.9, R9). */
class WindowsPlatform : Platform {

    override val pathSeparator = '\\'

    override fun env(name: String): String? = memScoped {
        val size = GetEnvironmentVariableW(name, null, 0u)
        if (size == 0u) return@memScoped null
        val buffer = allocArray<UShortVar>(size.toInt())
        val length = GetEnvironmentVariableW(name, buffer, size)
        if (length == 0u && size > 1u) null else buffer.toKStringFromUtf16()
    }

    override fun realPath(path: String): String? = memScoped {
        val buffer = allocArray<UShortVar>(MAX_PATH_CHARS)
        val length = kmob_win_final_path(path.wcstr.ptr, buffer, MAX_PATH_CHARS.convert())
        if (length == 0u) null else withoutExtendedPrefix(buffer.toKStringFromUtf16())
    }

    override fun isInteractive(): Boolean =
        kmob_win_is_console(STD_INPUT_HANDLE) == 1 && kmob_win_is_console(STD_ERROR_HANDLE) == 1

    override fun readLine(): String? =
        if (kmob_win_is_console(STD_INPUT_HANDLE) == 1) readConsoleLine() else readRedirectedLine()

    override fun findInPath(binary: String): String? =
        ExecutableSearch.windows(env("PATHEXT")).find(binary, env("PATH"), ::isExecutable)

    override fun isExecutable(path: String): Boolean {
        if (!ExecutableSearch.hasWindowsExecutableExtension(path)) return false
        val meta = runCatching { FileSystem.SYSTEM.metadataOrNull(path.toPath()) }.getOrNull() ?: return false
        return meta.isRegularFile
    }

    override fun homeDir(): String =
        env("USERPROFILE")?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(env("HOMEDRIVE"), env("HOMEPATH")).joinToString("").takeIf { it.isNotEmpty() }
            ?: "C:\\"

    override fun now(): Instant = Clock.System.now()

    override fun printOut(line: String) = write(STD_OUTPUT_HANDLE, line + "\n")

    override fun printErr(line: String) = write(STD_ERROR_HANDLE, line + "\n")

    private fun write(handle: UInt, text: String) {
        val utf8 = text.encodeToByteArray()
        memScoped {
            utf8.usePinned { pinned ->
                kmob_win_write(handle, text.wcstr.ptr, text.length, pinned.addressOf(0), utf8.size)
            }
        }
    }

    private fun readConsoleLine(): String? = memScoped {
        val buffer = allocArray<UShortVar>(MAX_LINE_CHARS)
        if (kmob_win_read_console_line(buffer, MAX_LINE_CHARS) < 0) null else buffer.toKStringFromUtf16()
    }

    /** Bytes bis `\n` als UTF-8, `\r` am Ende entfernt; null bei EOF ohne Daten. */
    private fun readRedirectedLine(): String? {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val b = kmob_win_read_byte()
            if (b < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().decodeToString().trimEnd('\r')
            if (b == '\n'.code) return bytes.toByteArray().decodeToString().trimEnd('\r')
            bytes += b.toByte()
        }
    }

    companion object {
        /** `\\?\C:\x` → `C:\x`, `\\?\UNC\server\share` → `\\server\share`. */
        fun withoutExtendedPrefix(path: String): String = when {
            path.startsWith("\\\\?\\UNC\\") -> "\\\\" + path.removePrefix("\\\\?\\UNC\\")
            path.startsWith("\\\\?\\") -> path.removePrefix("\\\\?\\")
            else -> path
        }

        /** Zerlegung einer Kommandozeile mit `CommandLineToArgvW` (wie die C-Runtime, R9.3). */
        fun splitCommandLine(line: String): List<String> = memScoped {
            val argc = alloc<IntVar>()
            val argv = kmob_win_split_command_line(line.wcstr.ptr, argc.ptr) ?: return@memScoped emptyList()
            try {
                (0 until argc.value).map { argv[it]?.toKStringFromUtf16().orEmpty() }
            } finally {
                kmob_win_free_argv(argv)
            }
        }

        /** Argumente von kmob ohne Programmnamen, direkt aus der UTF-16-Kommandozeile (R9.3, R9.9). */
        fun programArguments(): List<String> =
            splitCommandLine(kmob_win_command_line()?.toKStringFromUtf16().orEmpty()).drop(1)

        /** Systemmeldung zu einem Win32-Fehlercode. */
        fun errorMessage(code: UInt): String = memScoped {
            val buffer = allocArray<UShortVar>(1024)
            val length = kmob_win_error_message(code, buffer, 1024u)
            if (length == 0u) "error $code" else buffer.toKStringFromUtf16()
        }
    }
}
