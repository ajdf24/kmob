package de.kmob.domain.handover

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `.kmob/handover.json` (design §5.8): Kurzinfo zur übergebenen Session für `kmob start`. */
@Serializable
data class HandoverInfo(
    val schema: Int = 1,
    @SerialName("kmob_version") val kmobVersion: String,
    @SerialName("session_id") val sessionId: String,
    val title: String? = null,
    val freshness: String,
    @SerialName("log_entries") val logEntries: Int,
    @SerialName("artifact_bytes") val artifactBytes: Long,
    val from: String,
    @SerialName("handed_over_at") val handedOverAt: String,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun encode(info: HandoverInfo): String = json.encodeToString(serializer(), info) + "\n"

        /** Tolerant: fehlende/ungültige Datei oder Felder → null (R3.2). */
        fun decodeOrNull(text: String?): HandoverInfo? =
            text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}
