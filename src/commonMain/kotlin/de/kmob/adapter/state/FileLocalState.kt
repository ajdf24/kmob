package de.kmob.adapter.state

import de.kmob.application.port.LocalState
import okio.FileSystem
import okio.Path

/** Merker als leere Dateien in [dir] (`<git-common-dir>/kmob/`, nie committet, design §5.10). */
class FileLocalState(private val fs: FileSystem, private val dir: Path) : LocalState {

    override fun has(name: String): Boolean = fs.exists(dir / name)

    override fun set(name: String) {
        fs.createDirectories(dir)
        fs.write(dir / name) { writeUtf8("") }
    }
}
