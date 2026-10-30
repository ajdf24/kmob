package de.kmob.domain.session

private const val MAX_TITLE_LENGTH = 80

/**
 * Titel für Meldungen: erste Zeile, höchstens 80 Zeichen, mit führendem Leerzeichen in Anführungszeichen.
 * Ohne Titel leer, damit die Meldung ohne Lücke weiterläuft (R2.7, R3.1).
 */
fun quotedTitle(title: String?): String =
    title?.let { " \"${it.lineSequence().first().take(MAX_TITLE_LENGTH)}\"" } ?: ""
