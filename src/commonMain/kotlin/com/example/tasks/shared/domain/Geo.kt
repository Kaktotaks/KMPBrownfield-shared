package com.example.tasks.shared.domain

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS84 coordinate in degrees. */
data class GeoPoint(val lat: Double, val lng: Double)

/** A task place: the point plus a short display line, e.g. "Shevchenka St 9". */
data class Place(val point: GeoPoint, val address: String)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres. */
    fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = (b.lat - a.lat).toRadians()
        val dLng = (b.lng - a.lng).toRadians()
        val h = sin(dLat / 2).pow(2) + cos(a.lat.toRadians()) * cos(b.lat.toRadians()) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    private fun Double.toRadians() = this * PI / 180.0
}

/** The Dnipro area used for Nominatim biasing and the demo-data sanity check. */
object DniproBounds {
    const val MIN_LAT = 48.40
    const val MAX_LAT = 48.53
    const val MIN_LNG = 34.90
    const val MAX_LNG = 35.15

    fun contains(point: GeoPoint): Boolean =
        point.lat in MIN_LAT..MAX_LAT && point.lng in MIN_LNG..MAX_LNG
}
