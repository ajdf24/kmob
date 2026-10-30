package de.kmob.platform.windows

import de.kmob.cinterop.windows.kmob_win_run_capture
import de.kmob.cinterop.windows.kmob_win_run_inherit
import de.kmob.platform.Captured
import de.kmob.platform.Platform
import de.kmob.platform.ProcessRunner
import de.kmob.platform.ProcessStartException
import de.kmob.platform.support.WindowsCommandLine
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.posix.free
import platform.posix.size_tVar

/** Win32 `CreateProcessW` über den C-Shim (design §5.9, R9.3, R9.8). */
class WindowsProcessRunner(private val platform: Platform) : ProcessRunner {

    override fun runInherit(cmd: List<String>): Int = memScoped {
        val application = resolve(cmd)
        val error = alloc<UIntVar>()
        val rc = kmob_win_run_inherit(application.wcstr.ptr, commandLine(cmd), error.ptr)
        if (rc < 0) throw startFailure(cmd[0], error.value)
        rc.toInt() // 32-Bit-Exit-Code unverändert, z. B. 0xC000013A nach Ctrl+C
    }

    override fun runCapture(cmd: List<String>): Captured = memScoped {
        val application = resolve(cmd)
        val out = alloc<CPointerVar<ByteVar>>()
        val err = alloc<CPointerVar<ByteVar>>()
        val outLen = alloc<size_tVar>()
        val errLen = alloc<size_tVar>()
        val error = alloc<UIntVar>()
        val rc = kmob_win_run_capture(application.wcstr.ptr, commandLine(cmd), out.ptr, outLen.ptr, err.ptr, errLen.ptr, error.ptr)
        try {
            if (rc < 0) throw startFailure(cmd[0], error.value)
            Captured(rc.toInt(), out.value.decode(outLen.value.convert()), err.value.decode(errLen.value.convert()))
        } finally {
            free(out.value)
            free(err.value)
        }
    }

    /** Programm selbst auflösen: `CreateProcessW` würde sonst auch im aktuellen Verzeichnis suchen (R9.2). */
    private fun resolve(cmd: List<String>): String {
        require(cmd.isNotEmpty())
        return platform.findInPath(cmd[0]) ?: throw ProcessStartException("cannot start ${cmd[0]}: not found")
    }

    /** Beschreibbarer UTF-16-Puffer, wie `CreateProcessW` ihn verlangt. */
    private fun MemScope.commandLine(cmd: List<String>) = WindowsCommandLine.quote(cmd).wcstr.ptr

    private fun startFailure(program: String, code: UInt) =
        ProcessStartException("cannot start $program: ${WindowsPlatform.errorMessage(code)}")

    private fun CPointer<ByteVar>?.decode(len: Int): String =
        if (this == null || len == 0) "" else readBytes(len).decodeToString()
}
