package com.example.tasks.shared.geocoding

import com.example.tasks.shared.Fixtures
import com.example.tasks.shared.domain.GeoPoint
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NominatimTest {
    @Test
    fun shortAddressIsRoadPlusHouseNumber() {
        assertEquals("Shevchenka Street 9", NominatimGeocoder.parseReverse(Fixtures.NOMINATIM_REVERSE))
    }

    @Test
    fun fallsBackToTheFirstPartOfDisplayName() {
        val json = """{"lat":"1","lon":"2","display_name":"Dnipro Zoo, Some Road, Dnipro, Ukraine","address":{"city":"Dnipro"}}"""
        assertEquals("Dnipro Zoo", NominatimGeocoder.parseReverse(json))
    }

    @Test
    fun roadWithoutHouseNumberIsJustTheRoad() {
        val json = """{"display_name":"x","address":{"road":"Sicheslavska Embankment"}}"""
        assertEquals("Sicheslavska Embankment", NominatimGeocoder.parseReverse(json))
    }

    @Test
    fun nothingFoundIsNull() {
        assertNull(NominatimGeocoder.parseReverse(Fixtures.NOMINATIM_REVERSE_ERROR))
    }

    @Test
    fun parsesSearchResultsIntoPlaces() {
        val places = NominatimGeocoder.parseSearch(Fixtures.NOMINATIM_SEARCH)
        assertEquals(4, places.size)
        assertEquals("Shevchenka Lane 9", places[0].address)
        assertEquals(GeoPoint(48.4560234, 35.0571877), places[1].point)
        assertEquals("Shevchenka Street 9", places[1].address)
    }

    @Test
    fun searchSendsTheDocumentedParametersAndUserAgent() = runTest {
        var seen: io.ktor.client.request.HttpRequestData? = null
        val engine = MockEngine { request ->
            seen = request
            respond(Fixtures.NOMINATIM_SEARCH, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val geocoder = NominatimGeocoder(NominatimGeocoder.createClient(engine), "https://nominatim.example", "en", 0)
        val places = geocoder.search("Shevchenka 9", "uk")
        assertEquals(4, places.size)
        val request = seen!!
        assertEquals("/search", request.url.encodedPath)
        val q = request.url.parameters
        assertEquals("jsonv2", q["format"])
        assertEquals("ua", q["countrycodes"])
        assertEquals("34.9,48.53,35.15,48.4", q["viewbox"])
        assertEquals("0", q["bounded"])
        assertEquals("uk", q["accept-language"])
        assertEquals("Shevchenka 9", q["q"])
        assertEquals(NominatimGeocoder.USER_AGENT, request.headers[HttpHeaders.UserAgent])
        assertTrue(NominatimGeocoder.USER_AGENT.startsWith("KMPBrownfield-Tasks/0.9"))
    }

    @Test
    fun reverseUsesTheConfiguredLanguage() = runTest {
        var seen: io.ktor.client.request.HttpRequestData? = null
        val engine = MockEngine { request ->
            seen = request
            respond(Fixtures.NOMINATIM_REVERSE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val geocoder = NominatimGeocoder(NominatimGeocoder.createClient(engine), "https://nominatim.example", "uk", 0)
        assertEquals("Shevchenka Street 9", geocoder.reverse(GeoPoint(48.4560234, 35.0571877)))
        val q = seen!!.url.parameters
        assertEquals("/reverse", seen!!.url.encodedPath)
        assertEquals("uk", q["accept-language"])
        assertEquals("48.4560234", q["lat"])
        assertEquals("35.0571877", q["lon"])
    }

    @Test
    fun serverErrorsThrow() = runTest {
        val engine = MockEngine { respond("busy", HttpStatusCode.TooManyRequests) }
        val geocoder = NominatimGeocoder(NominatimGeocoder.createClient(engine), "https://nominatim.example", "en", 0)
        assertFailsWith<GeocodingException> { geocoder.search("anything", "en") }
    }

    @Test
    fun requestsAreSpacedByTheMinimumInterval() = runTest {
        val engine = MockEngine { respond(Fixtures.NOMINATIM_SEARCH, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val geocoder = NominatimGeocoder(NominatimGeocoder.createClient(engine), "https://nominatim.example", "en", 1_000)
        geocoder.search("one", "en")
        geocoder.search("two", "en")
        geocoder.search("three", "en")
        // Virtual time advances only through the throttle's delay(): two gaps of (almost) a second.
        // (The monotonic clock is real, so the gap is 1000 ms minus the real time the mock took.)
        assertTrue(currentTime in 1_800..2_000, "currentTime=$currentTime")
    }
}
