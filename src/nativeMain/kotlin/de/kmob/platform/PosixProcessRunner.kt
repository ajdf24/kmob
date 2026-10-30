package de.kmob.platform

import de.kmob.native.kmob_run_capture
import de.kmob.native.kmob_run_inherit
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.free
import platform.posix.size_tVar
import platform.posix.strerror

/** Startfehler (Programm nicht gefunden o. ä.) */
class SpawnException(val program: String, val errno: Int) :
    RuntimeException("cannot start $program: ${strerror(errno)?.toKString() ?: "errno $errno"}")

/** posix_spawn-basierte Implementierung über den C-Shim (design §5.9). */
class PosixProcessRunner : ProcessRunner {

    override fun runInherit(cmd: List<String>): Int = memScoped {
        require(cmd.isNotEmpty())
        val argv = allocArrayOf(cmd.map { it.cstr.ptr } + null)
        val rc = kmob_run_inherit(argv)
        if (rc < 0) throw SpawnException(cmd[0], -rc)
        rc
    }

    override fun runCapture(cmd: List<String>): Captured = memScoped {
        require(cmd.isNotEmpty())
        val argv = allocArrayOf(cmd.map { it.cstr.ptr } + null)
        val out = alloc<CPointerVar<ByteVar>>()
        val err = alloc<CPointerVar<ByteVar>>()
        val outLen = alloc<size_tVar>()
        val errLen = alloc<size_tVar>()
        val rc = kmob_run_capture(argv, out.ptr, outLen.ptr, err.ptr, errLen.ptr)
        try {
            if (rc < 0) throw SpawnException(cmd[0], -rc)
            Captured(rc, out.value.decode(outLen.value.convert()), err.value.decode(errLen.value.convert()))
        } finally {
            free(out.value)
            free(err.value)
        }
    }

    private fun CPointer<ByteVar>?.decode(len: Int): String =
        if (this == null || len == 0) "" else readBytes(len).decodeToString()
}
