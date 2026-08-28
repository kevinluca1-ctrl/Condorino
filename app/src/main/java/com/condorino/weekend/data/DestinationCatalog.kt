package com.condorino.weekend.data

import android.content.Context
import com.condorino.weekend.domain.model.DestinationProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class ProfileFile(val profiles: List<ProfileDto> = emptyList())

@Serializable
private data class ProfileDto(
    val iata: String,
    @SerialName("city_trip") val cityTrip: Int = 5,
    val nightlife: Int = 5,
    val culture: Int = 5,
    val beach: Int = 5,
    val food: Int = 5,
    val nature: Int = 5,
    @SerialName("transfer_minutes") val transferMinutes: Int = 45,
    @SerialName("distance_to_center_km") val distanceToCenterKm: Double? = null,
    val note: String? = null,
)

/**
 * Editorial destination metadata, loaded from `assets/destination_profiles.json`.
 *
 * This file holds *opinions* (nightlife factor, transfer time, …), never flight data — which
 * airports are actually reachable is always derived from the flight source. A destination without
 * a profile still works; it is simply scored neutrally on the destination-quality component.
 */
class DestinationCatalog(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    @Volatile
    private var cache: Map<String, DestinationProfile>? = null

    suspend fun profiles(): Map<String, DestinationProfile> {
        cache?.let { return it }
        return withContext(Dispatchers.IO) {
            val loaded = try {
                val raw = context.assets.open(ASSET).bufferedReader().use { it.readText() }
                json.decodeFromString(ProfileFile.serializer(), raw).profiles.associate { dto ->
                    dto.iata.uppercase() to DestinationProfile(
                        iata = dto.iata.uppercase(),
                        cityTrip = dto.cityTrip,
                        nightlife = dto.nightlife,
                        culture = dto.culture,
                        beach = dto.beach,
                        food = dto.food,
                        nature = dto.nature,
                        transferMinutes = dto.transferMinutes,
                        distanceToCenterKm = dto.distanceToCenterKm,
                        note = dto.note,
                    )
                }
            } catch (e: Exception) {
                // Missing or broken metadata must never break the flight search.
                emptyMap()
            }
            cache = loaded
            loaded
        }
    }

    companion object {
        const val ASSET = "destination_profiles.json"
    }
}
