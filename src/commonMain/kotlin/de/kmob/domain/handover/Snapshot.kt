package de.kmob.domain.handover

import de.kmob.domain.KmobException
import okio.Path

/**
 * Konsistente Kopie einer Session (design §5.5): Scan und Artefakt sehen denselben Inhalt.
 * Zeile n in [log] ist Log-Eintrag n.
 */
data class Snapshot(val metadata: Path, val log: Path, val entries: Int)

class ExportException(sessionId: String, reason: String) :
    KmobException("could not export session $sessionId: $reason. Use --no-session to hand over without it.")
