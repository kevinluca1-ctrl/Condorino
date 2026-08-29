package com.condorino.weekend.data.reference

import android.content.Context
import com.condorino.weekend.domain.model.Airport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.ZoneId

@Serializable
private data class ReferenceFile(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val source: String = "",
    @SerialName("source_urls") val sourceUrls: List<String> = emptyList(),
    val retrieved: String = "",
    /** Compact row format: [iata, icao, name, city, countryCode, timeZone]. */
    val airports: List<List<String>> = emptyList(),
)

/**
 * The app's airport reference data: **6,400+ airports from publicly inspectable datasets.**
 *
 * Built from three public sources (see `assets/airports_reference.json` and
 * docs/CONDOR_DATA_SOURCES.md):
 *
 *  * **OurAirports** (public domain) — IATA/ICAO codes, names, cities, ISO country codes.
 *  * **OpenFlights** (ODbL) — per-airport IANA time zone.
 *  * **IANA tzdata** `zone1970.tab` — used to fill the time zone for airports OpenFlights does not
 *    cover, but *only* for countries that have exactly one zone. Istanbul, for example, resolves
 *    that way because Turkey has a single zone.
 *
 * Airports whose time zone could not be established from any of those are **not in the file at
 * all**. This catalogue never guesses a zone: a wrong zone would silently corrupt every departure
 * and arrival time the app displays, which is the one thing it must not do.
 */
class AirportReferenceCatalog(
    // Nullable purely so a data source's unit tests can construct this without loading any
    // airports, by never calling airports()/byIata()/byIcao() — production always passes a real
    // Context. (See SourceStrings for the same pattern, used for the same reason.)
    private val context: Context?,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val assetName: String = ASSET,
) {

    private val mutex = Mutex()

    @Volatile
    private var byIata: Map<String, Airport>? = null

    @Volatile
    private var byIcao: Map<String, Airport> = emptyMap()

    @Volatile
    var sourceDescription: String = ""
        private set

    suspend fun airports(): Map<String, Airport> {
        byIata?.let { return it }
        return mutex.withLock {
            byIata?.let { return it }
            val loaded = load()
            byIata = loaded
            loaded
        }
    }

    suspend fun byIata(iata: String): Airport? = airports()[iata.uppercase()]

    /** OpenSky and most ATC-derived sources speak ICAO, not IATA. */
    suspend fun byIcao(icao: String): Airport? {
        airports()
        return byIcao[icao.uppercase()]
    }

    private suspend fun load(): Map<String, Airport> = withContext(Dispatchers.IO) {
        try {
            val raw = context!!.assets.open(assetName).bufferedReader().use { it.readText() }
            val file = json.decodeFromString(ReferenceFile.serializer(), raw)
            sourceDescription = file.source

            val iataMap = LinkedHashMap<String, Airport>(file.airports.size)
            val icaoMap = LinkedHashMap<String, Airport>(file.airports.size)

            for (row in file.airports) {
                if (row.size < 6) continue
                val iata = row[0].uppercase()
                val icao = row[1].uppercase()
                val timeZone = row[5]
                // Reject anything whose zone this runtime cannot resolve rather than shipping it.
                if (runCatching { ZoneId.of(timeZone) }.isFailure) continue

                val airport = Airport(
                    iata = iata,
                    name = row[2],
                    city = row[3].ifBlank { row[2] },
                    // Display name is derived from the ISO code at render time, so it follows the
                    // device language instead of being frozen in the data file.
                    country = row[4].uppercase(),
                    countryCode = row[4].uppercase(),
                    timeZoneId = timeZone,
                )
                iataMap[iata] = airport
                if (icao.isNotBlank()) icaoMap[icao] = airport
            }

            byIcao = icaoMap
            iataMap
        } catch (e: Exception) {
            // The reference file is an optimisation, not a hard dependency: without it the app
            // still works for any source that declares its own airports.
            byIcao = emptyMap()
            emptyMap()
        }
    }

    companion object {
        const val ASSET = "airports_reference.json"
    }
}
