package com.example.tasks.shared.routing

import com.example.tasks.shared.domain.Geo
import com.example.tasks.shared.domain.GeoPoint

/** One piece of a route: from one waypoint to the next. Durations are null on straight-line fallbacks. */
data class Leg(
    val durationSeconds: Double?,
    val distanceMeters: Double,
    val geometry: List<GeoPoint>,
)

/**
 * A driving route through all requested points. [legs] has `points.size - 1` entries.
 * [isFallback] = straight lines from [StraightLineRouter] (no durations, distances are haversine).
 */
data class Route(
    val geometry: List<GeoPoint>,
    val legs: List<Leg>,
    val totalDurationSeconds: Double?,
    val totalDistanceMeters: Double,
    val isFallback: Boolean = false,
)

class RoutingException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface RoutingApi {
    /** Route through [points] in order. Throws on network / server errors. */
    suspend fun route(points: List<GeoPoint>): Route
}

/** The fallback when the routing service is unavailable: straight segments, no durations. */
object StraightLineRouter : RoutingApi {
    override suspend fun route(points: List<GeoPoint>): Route = routeNow(points)

    fun routeNow(points: List<GeoPoint>): Route {
        val legs = points.zipWithNext { a, b ->
            Leg(durationSeconds = null, distanceMeters = Geo.haversineMeters(a, b), geometry = listOf(a, b))
        }
        return Route(
            geometry = points,
            legs = legs,
            totalDurationSeconds = null,
            totalDistanceMeters = legs.sumOf { it.distanceMeters },
            isFallback = true,
        )
    }
}
