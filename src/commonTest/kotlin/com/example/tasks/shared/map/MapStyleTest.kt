package com.example.tasks.shared.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapStyleTest {
    private fun style(dark: Boolean): JsonObject = Json.parseToJsonElement(MapStyle.json(dark)).jsonObject

    private fun layers(dark: Boolean): Map<String, JsonObject> =
        style(dark)["layers"]!!.jsonArray.map { it.jsonObject }.associateBy { it["id"]!!.jsonPrimitive.content }

    private fun paint(dark: Boolean, id: String, key: String): String =
        layers(dark)[id]!!["paint"]!!.jsonObject[key]!!.jsonPrimitive.content

    @Test
    fun isValidStyleSpecJson() {
        for (dark in listOf(false, true)) {
            val s = style(dark)
            assertEquals("8", s["version"]!!.jsonPrimitive.content)
            assertTrue(s["glyphs"]!!.jsonPrimitive.content.contains("{fontstack}") && s["glyphs"]!!.jsonPrimitive.content.contains("{range}"))
            val source = s["sources"]!!.jsonObject[MapStyle.TILE_SOURCE_ID]!!.jsonObject
            assertEquals("vector", source["type"]!!.jsonPrimitive.content)
            assertEquals("https://tiles.openfreemap.org/planet", source["url"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun hasTheExpectedLayersInOrder() {
        for (dark in listOf(false, true)) {
            val ids = style(dark)["layers"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertEquals(MapStyle.LAYER_IDS, ids)
            assertEquals(ids.size, ids.toSet().size)
        }
    }

    @Test
    fun hasNoPoiBuildingOrExtrusionLayers() {
        val types = layers(false).values.map { it["type"]!!.jsonPrimitive.content }
        assertFalse("fill-extrusion" in types)
        assertFalse(layers(false).values.any { it["source-layer"]?.jsonPrimitive?.content in setOf("poi", "building") })
        assertFalse(layers(false).values.any { it["layout"]?.jsonObject?.containsKey("icon-image") == true })
    }

    @Test
    fun routeLayersCanBeInsertedBelowTheFirstLabelLayer() {
        val ids = MapStyle.LAYER_IDS
        val labelStart = ids.indexOf(MapStyle.ROUTE_BELOW_LAYER)
        assertTrue(labelStart > ids.indexOf("road_major"))
        assertTrue(ids.drop(labelStart).all { layers(false)[it]!!["type"]!!.jsonPrimitive.content == "symbol" })
    }

    @Test
    fun lightColoursMatchTheTokenTable() {
        assertEquals("#F5F5F0", paint(false, "background", "background-color"))
        assertEquals("#D6E0E1", paint(false, "water", "fill-color"))
        assertEquals("#E3E8D4", paint(false, "park", "fill-color"))
        assertEquals("#FFFFFF", paint(false, "road_minor", "line-color"))
        assertEquals("#FFFFFF", paint(false, "road_secondary", "line-color"))
        assertEquals("#FFFFFF", paint(false, "road_major", "line-color"))
        assertEquals("#E2E2DA", paint(false, "road_minor_casing", "line-color"))
        assertEquals("#4F4F4A", paint(false, "highway-name-minor", "text-color"))
        assertEquals("#F5F5F0", paint(false, "highway-name-minor", "text-halo-color"))
        assertEquals("#55737B", paint(false, "water_name_label", "text-color"))
        assertEquals("#5E6B4F", paint(false, "park_label", "text-color"))
    }

    @Test
    fun darkColoursMatchTheTokenTable() {
        assertEquals("#151515", paint(true, "background", "background-color"))
        assertEquals("#0D1B20", paint(true, "water", "fill-color"))
        assertEquals("#17201A", paint(true, "park", "fill-color"))
        assertEquals("#232323", paint(true, "road_minor", "line-color"))
        assertEquals("#232323", paint(true, "road_secondary", "line-color"))
        assertEquals("#2F2F2F", paint(true, "road_major", "line-color"))
        assertEquals("#2A2A2A", paint(true, "road_major_casing", "line-color"))
        assertEquals("#8A8A84", paint(true, "highway-name-major", "text-color"))
        assertEquals("#151515", paint(true, "highway-name-major", "text-halo-color"))
        assertEquals("#5D8591", paint(true, "water_name_label", "text-color"))
        assertEquals("#6F8A72", paint(true, "park_label", "text-color"))
    }

    @Test
    fun labelsUseNotoSansWithTheSpecSizes() {
        val l = layers(false)
        assertEquals("Noto Sans Regular", l["highway-name-minor"]!!["layout"]!!.jsonObject["text-font"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("Noto Sans Italic", l["water_name_label"]!!["layout"]!!.jsonObject["text-font"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("11", l["highway-name-major"]!!["layout"]!!.jsonObject["text-size"]!!.jsonPrimitive.content)
        assertEquals("10", l["park_label"]!!["layout"]!!.jsonObject["text-size"]!!.jsonPrimitive.content)
        assertEquals("1.5", l["highway-name-major"]!!["paint"]!!.jsonObject["text-halo-width"]!!.jsonPrimitive.content)
    }

    @Test
    fun roadWidthsAtZoom15FollowTheTokenTable() {
        fun widthAt15(id: String): Double {
            val expr = layers(false)[id]!!["paint"]!!.jsonObject["line-width"]!!.jsonArray
            // ["interpolate", ["linear"], ["zoom"], z, w, z, w, ...]
            val stops = expr.drop(3)
            val i = stops.indexOfFirst { it is kotlinx.serialization.json.JsonPrimitive && it.jsonPrimitive.content == "15" }
            return stops[i + 1].jsonPrimitive.content.toDouble()
        }
        assertEquals(4.0, widthAt15("road_minor"))
        assertEquals(7.0, widthAt15("road_secondary"))
        assertEquals(10.0, widthAt15("road_major"))
        assertEquals(6.0, widthAt15("road_minor_casing")) // +2
        assertEquals(13.0, widthAt15("road_major_casing")) // +3
    }

    @Test
    fun darkAndLightDifferOnlyInTheTokens() {
        assertFalse(MapStyle.json(true) == MapStyle.json(false))
        assertEquals(layers(true).keys, layers(false).keys)
    }

    @Test
    fun routePaletteMatchesSection32() {
        val light = MapStyle.routePalette(false)
        assertEquals("#0A0A0A", light.casing)
        assertEquals(9f, light.casingWidth)
        assertEquals("#E3F140", light.line)
        assertEquals(5f, light.lineWidth)
        assertEquals("#4F4F4A", light.pending)
        assertEquals("#8C8C85", light.pastLine)
        val dark = MapStyle.routePalette(true)
        assertEquals("#000000", dark.casing)
        assertEquals(8f, dark.casingWidth)
        assertEquals("#B3B3AD", dark.pending)
        assertEquals("#4A4A46", dark.pastLine)
        assertEquals(listOf(6f, 6f), dark.pendingDash)
        assertEquals(listOf(10f, 8f), dark.fallbackDash)
        assertEquals(0.45f, dark.pastOpacity)
    }
}
