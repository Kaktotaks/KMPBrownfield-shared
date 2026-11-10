package com.example.tasks.shared.geocoding

import com.example.tasks.shared.data.TasksJson
import com.example.tasks.shared.data.platformHttpEngine
import com.example.tasks.shared.domain.DniproBounds
import com.example.tasks.shared.domain.GeoPoint
import com.example.tasks.shared.domain.Place
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.TimeSource

/**
 * Nominatim over Ktor (`/reverse`, `/search`, `format=jsonv2`, `countrycodes=ua`).
 * The OSM usage policy applies: a real User-Agent and at most one request per second, which this
 * class enforces itself (requests are serialized and spaced by [minIntervalMs]).
 * Swap the host with `NominatimGeocoder(baseUrl = ...)` (e.g. a self-hosted instance).
 *
 * [language] is the `accept-language` of [reverse]; [search] takes its own language.
 */
class NominatimGeocoder internal constructor(
    private val client: HttpClient,
    private val baseUrl: String,
    private val language: String,
    private val minIntervalMs: Long,
) : Geocoder {

    constructor() : this("en")

    constructor(language: String) : this(DEFAULT_BASE_URL, language)

    constructor(baseUrl: String, language: String) :
        this(createClient(platformHttpEngine()), baseUrl, language, MIN_INTERVAL_MS)

    private val gate = Mutex()
    private var lastRequest: TimeSource.Monotonic.ValueTimeMark? = null

    override suspend fun reverse(point: GeoPoint): String? {
        val response = request("reverse") {
            parameter("lat", point.lat)
            parameter("lon", point.lng)
            parameter("zoom", 18)
            parameter("addressdetails", 1)
            parameter("accept-language", language)
        }
        return parseReverse(response.bodyAsText())
    }

    override suspend fun search(query: String, language: String): List<Place> {
        val response = request("search") {
            parameter("q", query)
            parameter("countrycodes", "ua")
            // viewbox = left,top,right,bottom; bounded=0 only biases the ranking.
            parameter("viewbox", "${DniproBounds.MIN_LNG},${DniproBounds.MAX_LAT},${DniproBounds.MAX_LNG},${DniproBounds.MIN_LAT}")
            parameter("bounded", 0)
            parameter("addressdetails", 1)
            parameter("limit", SEARCH_LIMIT)
            parameter("accept-language", language)
        }
        return parseSearch(response.bodyAsText())
    }

    private suspend fun request(path: String, params: io.ktor.client.request.HttpRequestBuilder.() -> Unit): HttpResponse =
        gate.withLock {
            lastRequest?.let { mark ->
                val wait = minIntervalMs - mark.elapsedNow().inWholeMilliseconds
                if (wait > 0) delay(wait)
            }
            try {
                client.get("${baseUrl.trimEnd('/')}/$path") {
                    parameter("format", "jsonv2")
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    params()
                }.also { if (!it.status.isSuccess()) throw GeocodingException("Nominatim HTTP ${it.status.value}") }
            } finally {
                lastRequest = TimeSource.Monotonic.markNow()
            }
        }

    companion object {
        /** The only place the geocoding host is named. */
        const val DEFAULT_BASE_URL = "https://nominatim.openstreetmap.org"
        const val USER_AGENT = "KMPBrownfield-Tasks/0.9 (+https://github.com/Kaktotaks/KMPBrownfield-shared)"
        const val MIN_INTERVAL_MS = 1_000L
        const val SEARCH_LIMIT = 8

        internal fun createClient(engine: HttpClientEngine) = HttpClient(engine) {
            install(ContentNegotiation) { json(TasksJson) }
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 10_000
            }
        }

        /** `road + " " + house_number`, else the first part of `display_name`. */
        internal fun shortAddress(address: NominatimAddress?, displayName: String?): String? {
            val road = address?.road ?: address?.pedestrian
            val house = address?.houseNumber
            if (!road.isNullOrBlank()) return if (house.isNullOrBlank()) road else "$road $house"
            return displayName?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
        }

        internal fun parseReverse(json: String): String? {
            val dto = TasksJson.decodeFromString(NominatimResult.serializer(), json)
            if (dto.error != null) return null
            return shortAddress(dto.address, dto.displayName)
        }

        internal fun parseSearch(json: String): List<Place> =
            TasksJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(NominatimResult.serializer()), json)
                .mapNotNull { dto ->
                    val lat = dto.lat?.toDoubleOrNull() ?: return@mapNotNull null
                    val lng = dto.lon?.toDoubleOrNull() ?: return@mapNotNull null
                    val address = shortAddress(dto.address, dto.displayName) ?: return@mapNotNull null
                    Place(GeoPoint(lat, lng), address)
                }
    }
}

class GeocodingException(message: String) : Exception(message)

@Serializable
internal data class NominatimResult(
    val lat: String? = null,
    val lon: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val address: NominatimAddress? = null,
    val error: String? = null,
)

@Serializable
internal data class NominatimAddress(
    val road: String? = null,
    val pedestrian: String? = null,
    @SerialName("house_number") val houseNumber: String? = null,
)
