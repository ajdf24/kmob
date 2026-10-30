package de.kmob.domain

/** Fehler, der kmob mit Meldung und Exit-Code beendet (design §4.2, §7). */
open class KmobException(message: String, val exitCode: Int = 1) : RuntimeException(message)

/** Falsche Aufrufparameter → Exit 2. */
class UsageException(message: String) : KmobException("$message. See kmob help.", 2)
