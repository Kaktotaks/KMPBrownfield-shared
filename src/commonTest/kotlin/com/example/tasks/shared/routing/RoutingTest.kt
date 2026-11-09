package com.example.tasks.shared.routing

import com.example.tasks.shared.Fixtures
import com.example.tasks.shared.data.DemoRoute
import com.example.tasks.shared.domain.Geo
import com.example.tasks.shared.domain.GeoPoint
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val START = DemoRoute.START.point
private val SHEVCHENKA = GeoPoint(48.4560234, 35.0571877)
private val YAVORNYTSKOHO = GeoPoint(48.4613160, 35.0511356)

class OsrmParsingTest {
    private val points = listOf(START, SHEVCHENKA, YAVORNYTSKOHO)

    @Test
    fun parsesTotalsAndLegsFromTheRecordedResponse() {
        val route = OsrmRoutingApi.parseRoute(Fixtures.OSRM_THREE_POINTS, points)
        assertEquals(964.5, route.totalDurationSeconds)
        assertEquals(9148.6, route.totalDistanceMeters)
        assertEquals(2, route.legs.size)
        assertEquals(798.2, route.legs[0].durationSeconds)
        assertEquals(8018.2, route.legs[0].distanceMeters)
        assertEquals(166.3, route.legs[1].durationSeconds)
        assertEquals(false, route.isFallback)
    }

    @Test
    fun geometryIsLatLngNotLngLat() {
        val route = OsrmRoutingApi.parseRoute(Fixtures.OSRM_THREE_POINTS, points)
        assertEquals(265, route.geometry.size)
        assertEquals(48.409662, route.geometry.first().lat)
        assertEquals(35.077164, route.geometry.first().lng)
    }

    @Test
    fun splitsTheGeometryAtTheWaypointLocations() {
        val route = OsrmRoutingApi.parseRoute(Fixtures.OSRM_THREE_POINTS, points)
        val (first, second) = route.legs.map { it.geometry }
        // The legs are contiguous and together cover the whole geometry exactly once (they share the split vertex).
        assertEquals(route.geometry.size + 1, first.size + second.size)
        assertEquals(first.last(), second.first())
        assertEquals(route.geometry.first(), first.first())
        assertEquals(route.geometry.last(), second.last())
        // The split vertex is the snapped Shevchenka St 9 location OSRM reported.
        assertTrue(Geo.haversineMeters(first.last(), SHEVCHENKA) < 30.0)
        assertTrue(first.size > second.size)
    }

    @Test
    fun noRouteBecomesAnException() {
        assertFailsWith<RoutingException> { OsrmRoutingApi.parseRoute(Fixtures.OSRM_NO_ROUTE, points) }
    }

    @Test
    fun garbageBecomesAnException() {
        assertFailsWith<RoutingException> { OsrmRoutingApi.parseRoute("<html>502 Bad Gateway</html>", points) }
    }

    @Test
    fun splitGeometryFindsTheNearestVertexAfterThePreviousSplit() {
        // An out-and-back along one street: A -> B -> A -> C. The second waypoint equals the start
        // location but must be matched to the later vertex.
        val a = GeoPoint(48.45, 35.05)
        val b = GeoPoint(48.46, 35.05)
        val c = GeoPoint(48.46, 35.06)
        val geometry = listOf(a, b, a, c)
        val pieces = OsrmRoutingApi.splitGeometry(geometry, listOf(a, b, a, c))
        assertEquals(listOf(listOf(a, b), listOf(b, a), listOf(a, c)), pieces)
    }

    @Test
    fun requestsTheDocumentedUrl() = runTest {
        var url: String? = null
        val engine = MockEngine { request ->
            url = request.url.toString()
            respond(Fixtures.OSRM_THREE_POINTS, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = OsrmRoutingApi(OsrmRoutingApi.createClient(engine), "https://osrm.example")
        val route = api.route(points)
        assertEquals(2, route.legs.size)
        val u = assertNotNull(url)
        assertTrue(u.startsWith("https://osrm.example/route/v1/driving/35.0773894,48.409594;35.0571877,48.4560234;35.0511356,48.461316?"), u)
        assertTrue("overview=full" in u && "geometries=geojson" in u && "steps=false" in u && "annotations=false" in u, u)
    }

    @Test
    fun networkErrorsBecomeRoutingExceptions() = runTest {
        val engine = MockEngine { throw RuntimeException("no host") }
        val api = OsrmRoutingApi(OsrmRoutingApi.createClient(engine), "https://invalid.invalid")
        assertFailsWith<RoutingException> { api.route(points) }
    }
}

class StraightLineRouterTest {
    @Test
    fun buildsStraightLegsWithoutDurations() = runTest {
        val route = StraightLineRouter.route(listOf(START, SHEVCHENKA, YAVORNYTSKOHO))
        assertTrue(route.isFallback)
        assertNull(route.totalDurationSeconds)
        assertEquals(2, route.legs.size)
        route.legs.forEach {
            assertNull(it.durationSeconds)
            assertEquals(2, it.geometry.size)
        }
        assertEquals(listOf(START, SHEVCHENKA, YAVORNYTSKOHO), route.geometry)
        assertEquals(route.legs.sumOf { it.distanceMeters }, route.totalDistanceMeters)
    }

    @Test
    fun haversineMatchesAKnownDistance() {
        // Start -> Shevchenka St 9: OSRM says 8 km by road; the straight line must be shorter, ~5.2 km.
        val d = Geo.haversineMeters(START, SHEVCHENKA)
        assertTrue(d in 5_000.0..5_500.0, "$d")
        assertEquals(0.0, Geo.haversineMeters(START, START))
    }
}
