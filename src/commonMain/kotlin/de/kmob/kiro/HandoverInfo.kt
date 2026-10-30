package de.kmob.kiro

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `.kmob/handover.json` (design §5.8). */
@Serializable
data class HandoverInfo(
    val schema: Int = 1,
    val kmob_version: String,
    val session_id: String,
    val title: String? = null,
    val freshness: String,
    val log_entries: Int,
    val artifact_bytes: Long,
    val from: String,
    val handed_over_at: String,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun encode(info: HandoverInfo): String = json.encodeToString(serializer(), info) + "\n"

        /** Tolerant: fehlende/ungültige Datei oder Felder → null (R3.2). */
        fun decodeOrNull(text: String?): HandoverInfo? =
            text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}
