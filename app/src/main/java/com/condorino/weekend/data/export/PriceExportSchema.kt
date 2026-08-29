package com.condorino.weekend.data.export

import com.condorino.weekend.domain.model.PriceEntryMode
import com.condorino.weekend.domain.model.StandbyPrice
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The file format for "export my standby prices". Deliberately a small, documented JSON file
 * rather than anything tied to a specific cloud provider: exporting through Android's own
 * Storage Access Framework lets the user save it to their own Google Drive, Dropbox, local
 * storage or anywhere else they already have a document provider for — the app never needs its
 * own cloud account or API key to make that possible, and never asks the user for one either.
 */
@Serializable
data class PriceExportFile(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("exported_at") val exportedAt: String,
    val prices: List<PriceExportEntry>,
)

@Serializable
data class PriceExportEntry(
    val iata: String,
    val mode: String,
    @SerialName("economy_outbound_cents") val economyOutboundCents: Long? = null,
    @SerialName("economy_inbound_cents") val economyInboundCents: Long? = null,
    @SerialName("business_outbound_cents") val businessOutboundCents: Long? = null,
    @SerialName("business_inbound_cents") val businessInboundCents: Long? = null,
    @SerialName("taxes_cents") val taxesCents: Long? = null,
    @SerialName("updated_at_epoch_millis") val updatedAtEpochMillis: Long = 0L,
)

fun StandbyPrice.toExportEntry() = PriceExportEntry(
    iata = destinationIata,
    mode = mode.name,
    economyOutboundCents = economyOutboundCents,
    economyInboundCents = economyInboundCents,
    businessOutboundCents = businessOutboundCents,
    businessInboundCents = businessInboundCents,
    taxesCents = taxesCents,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

/** Null (not thrown) if the row is unreadable — one bad entry must not fail the whole import. */
fun PriceExportEntry.toDomainOrNull(): StandbyPrice? {
    val iataCode = iata.trim().uppercase().takeIf { it.length in 3..4 } ?: return null
    val entryMode = runCatching { PriceEntryMode.valueOf(mode) }.getOrElse { PriceEntryMode.PER_SEGMENT }
    return StandbyPrice(
        destinationIata = iataCode,
        mode = entryMode,
        economyOutboundCents = economyOutboundCents,
        economyInboundCents = economyInboundCents,
        businessOutboundCents = businessOutboundCents,
        businessInboundCents = businessInboundCents,
        taxesCents = taxesCents,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

/**
 * Pure text (de)serialisation — no Android, no file I/O — so it is unit-testable directly. Reading
 * and writing the chosen file is a thin Android-specific layer on top, in the UI.
 */
object PriceExport {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    fun write(prices: Collection<StandbyPrice>, exportedAtIso: String): String =
        json.encodeToString(
            PriceExportFile.serializer(),
            PriceExportFile(exportedAt = exportedAtIso, prices = prices.map { it.toExportEntry() }),
        )

    /** @throws Exception if the text is not a readable export file — the caller decides how to report it. */
    fun read(text: String): List<StandbyPrice> =
        json.decodeFromString(PriceExportFile.serializer(), text).prices.mapNotNull { it.toDomainOrNull() }
}
