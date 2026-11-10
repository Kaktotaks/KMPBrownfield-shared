package com.example.tasks.shared.routing

import com.example.tasks.shared.data.TasksJson
import com.example.tasks.shared.data.platformHttpEngine
import com.example.tasks.shared.domain.Geo
import com.example.tasks.shared.domain.GeoPoint
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable

/**
 * OSRM `route` service over Ktor. The public demo server is fine for a portfolio app; the host is
 * the single constant [DEFAULT_BASE_URL] (or pass your own `baseUrl`, e.g. a self-hosted OSRM).
 *
 * Leg geometry: one request with `overview=full`; the full geometry is split at the snapped
 * waypoint locations OSRM returns (see [splitGeometry]). Timeout 8 s.
 */
class OsrmRoutingApi internal constructor(
    private val client: HttpClient,
    private val baseUrl: String,
) : RoutingApi {

    constructor() : this(DEFAULT_BASE_URL)

    constructor(baseUrl: String) : this(createClient(platformHttpEngine()), baseUrl)

    override suspend fun route(points: List<GeoPoint>): Route {
        if (points.size < 2) return StraightLineRouter.routeNow(points)
        val coordinates = points.joinToString(";") { "${it.lng},${it.lat}" }
        val response = try {
            client.get("${baseUrl.trimEnd('/')}/route/v1/driving/$coordinates") {
                parameter("overview", "full")
                parameter("geometries", "geojson")
                parameter("steps", "false")
                parameter("annotations", "false")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RoutingException("OSRM request failed: ${e.message}", e)
        }
        val text = response.bodyAsText()
        return parseRoute(text, points)
    }

    companion object {
        /** The only place the routing host is named. */
        const val DEFAULT_BASE_URL = "https://router.project-osrm.org"
        const val TIMEOUT_MS = 8_000L

        internal fun createClient(engine: HttpClientEngine) = HttpClient(engine) {
            install(ContentNegotiation) { json(TasksJson) }
            install(HttpTimeout) {
                requestTimeoutMillis = TIMEOUT_MS
                connectTimeoutMillis = TIMEOUT_MS
                socketTimeoutMillis = TIMEOUT_MS
            }
        }

        /** Parses an OSRM `route` response. Internal for the fixture test. */
        internal fun parseRoute(json: String, requested: List<GeoPoint>): Route {
            val dto = try {
                TasksJson.decodeFromString(OsrmResponse.serializer(), json)
            } catch (e: Exception) {
                throw RoutingException("OSRM response is not valid: ${e.message}", e)
            }
            val route = dto.routes.firstOrNull()
            if (dto.code != "Ok" || route == null) throw RoutingException("OSRM code ${dto.code}")
            val geometry = route.geometry.coordinates.map { GeoPoint(lat = it[1], lng = it[0]) }
            val waypoints = dto.waypoints.map { GeoPoint(lat = it.location[1], lng = it.location[0]) }
                .ifEmpty { requested }
            val pieces = splitGeometry(geometry, waypoints)
            val legs = route.legs.mapIndexed { i, leg ->
                Leg(
                    durationSeconds = leg.duration,
                    distanceMeters = leg.distance,
                    geometry = pieces.getOrElse(i) { emptyList() },
                )
            }
            return Route(
                geometry = geometry,
                legs = legs,
                totalDurationSeconds = route.duration,
                totalDistanceMeters = route.distance,
            )
        }

        /**
         * Splits [geometry] into `waypoints.size - 1` pieces. Each intermediate waypoint is matched
         * to the nearest geometry vertex at or after the previous split (so out-and-back routes
         * through the same street split in the right place); that vertex ends one leg and starts
         * the next. The first/last pieces start/end at the first/last vertex.
         */
        internal fun splitGeometry(geometry: List<GeoPoint>, waypoints: List<GeoPoint>): List<List<GeoPoint>> {
            if (waypoints.size < 2 || geometry.size < 2) return emptyList()
            val cuts = ArrayList<Int>()
            cuts += 0
            var from = 0
            for (i in 1 until waypoints.size - 1) {
                var best = from
                var bestDistance = Double.MAX_VALUE
                for (j in from until geometry.size) {
                    val d = Geo.haversineMeters(geometry[j], waypoints[i])
                    if (d < bestDistance) {
                        bestDistance = d
                        best = j
                    }
                }
                cuts += best
                from = best
            }
            cuts += geometry.lastIndex
            return (0 until cuts.size - 1).map { geometry.subList(cuts[it], cuts[it + 1] + 1) }
        }
    }
}

@Serializable
internal data class OsrmResponse(
    val code: String,
    val routes: List<OsrmRoute> = emptyList(),
    val waypoints: List<OsrmWaypoint> = emptyList(),
)

@Serializable
internal data class OsrmRoute(
    val duration: Double,
    val distance: Double,
    val geometry: OsrmGeometry,
    val legs: List<OsrmLeg> = emptyList(),
)

@Serializable
internal data class OsrmGeometry(val coordinates: List<List<Double>>)

@Serializable
internal data class OsrmLeg(val duration: Double, val distance: Double)

@Serializable
internal data class OsrmWaypoint(val location: List<Double>)
