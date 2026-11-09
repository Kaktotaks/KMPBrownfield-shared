package com.example.tasks.shared.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Map style tokens of one theme (MAP_HANDOFF §4). Colours are `#RRGGBB`. */
data class MapPalette(
    val land: String,
    val water: String,
    val park: String,
    val roadMinor: String,
    val roadMajor: String,
    val roadCasing: String,
    val label: String,
    val labelHalo: String,
    val waterLabel: String,
    val parkLabel: String,
)

/** Route overlay tokens (MAP_HANDOFF §3.2); the apps draw the route layers with these. */
data class RoutePalette(
    val casing: String,
    val casingWidth: Float,
    val line: String,
    val lineWidth: Float,
    /** Loading: dashed straight segments. */
    val pending: String,
    val pendingWidth: Float,
    val pendingDash: List<Float>,
    /** Fallback: straight lines, same colours as ready. */
    val fallbackDash: List<Float>,
    /** Past legs: casing + this grey line at [pastOpacity] layer opacity. */
    val pastLine: String,
    val pastOpacity: Float,
)

/**
 * The single MapLibre style for both apps, generated from the token table. Shipped as code, not a
 * resource: iOS writes [json] to Caches and uses the file URL as `styleURL`; Android calls
 * `Style.Builder().fromJson(...)`.
 *
 * Source / layer / class names and the glyph URL are copied from OpenFreeMap's `liberty` style
 * (OpenMapTiles schema), fetched 2026-11-09. Inter has no glyphs, so labels use Noto Sans.
 * The route, start marker and pins are NOT in the style: the apps add them at runtime, the route
 * above [ROUTE_BELOW_LAYER] (the first label layer, so the line stays under the labels).
 */
object MapStyle {
    const val TILE_SOURCE_ID = "openmaptiles"

    /** Host of the vector tiles and glyphs. To self-host, change these two constants. */
    const val TILES_URL = "https://tiles.openfreemap.org/planet"
    const val GLYPHS_URL = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"

    /** Insert route layers below this one. */
    const val ROUTE_BELOW_LAYER = "highway-name-minor"

    private const val FONT_REGULAR = "Noto Sans Regular"
    private const val FONT_ITALIC = "Noto Sans Italic"

    /** Layer ids in draw order. */
    val LAYER_IDS: List<String> = listOf(
        "background",
        "landcover_grass",
        "park",
        "water",
        "waterway",
        "road_minor_casing",
        "road_secondary_casing",
        "road_major_casing",
        "road_minor",
        "road_secondary",
        "road_major",
        "highway-name-minor",
        "highway-name-major",
        "water_name_label",
        "park_label",
        "place_label",
    )

    private val light = MapPalette(
        land = "#F5F5F0", water = "#D6E0E1", park = "#E3E8D4",
        roadMinor = "#FFFFFF", roadMajor = "#FFFFFF", roadCasing = "#E2E2DA",
        label = "#4F4F4A", labelHalo = "#F5F5F0", waterLabel = "#55737B", parkLabel = "#5E6B4F",
    )
    private val dark = MapPalette(
        land = "#151515", water = "#0D1B20", park = "#17201A",
        roadMinor = "#232323", roadMajor = "#2F2F2F", roadCasing = "#2A2A2A",
        label = "#8A8A84", labelHalo = "#151515", waterLabel = "#5D8591", parkLabel = "#6F8A72",
    )

    fun palette(dark: Boolean): MapPalette = if (dark) this.dark else light

    fun routePalette(dark: Boolean): RoutePalette =
        if (dark) {
            RoutePalette("#000000", 8f, "#E3F140", 5f, "#B3B3AD", 2.5f, listOf(6f, 6f), listOf(10f, 8f), "#4A4A46", 0.45f)
        } else {
            RoutePalette("#0A0A0A", 9f, "#E3F140", 5f, "#4F4F4A", 2.5f, listOf(6f, 6f), listOf(10f, 8f), "#8C8C85", 0.45f)
        }

    /** MapLibre style spec (version 8) as a JSON string. */
    fun json(dark: Boolean): String = Json.encodeToString(JsonObject.serializer(), build(dark))

    internal fun build(dark: Boolean): JsonObject {
        val p = palette(dark)
        return buildJsonObject {
            put("version", 8)
            put("name", if (dark) "Tasks Map Dark" else "Tasks Map Light")
            put("glyphs", GLYPHS_URL)
            putJsonObject("sources") {
                putJsonObject(TILE_SOURCE_ID) {
                    put("type", "vector")
                    put("url", TILES_URL)
                }
            }
            put(
                "layers",
                JsonArray(
                    listOf(
                        background(p),
                        fill("landcover_grass", "landcover", p.park, filter = eq("class", "grass")),
                        fill("park", "park", p.park),
                        fill("water", "water", p.water, filter = notEq("brunnel", "tunnel")),
                        line("waterway", "waterway", p.water, widthAt15 = 2f),
                        road("road_minor_casing", p.roadCasing, widthAt15 = 4f + 2f, classes = listOf("minor", "service", "track"), minZoom = 12f),
                        road("road_secondary_casing", p.roadCasing, widthAt15 = 7f + 2f, classes = listOf("secondary", "tertiary")),
                        road("road_major_casing", p.roadCasing, widthAt15 = 10f + 3f, classes = listOf("primary", "trunk", "motorway")),
                        road("road_minor", p.roadMinor, widthAt15 = 4f, classes = listOf("minor", "service", "track"), minZoom = 13f),
                        road("road_secondary", p.roadMinor, widthAt15 = 7f, classes = listOf("secondary", "tertiary")),
                        road("road_major", p.roadMajor, widthAt15 = 10f, classes = listOf("primary", "trunk", "motorway")),
                        streetLabel("highway-name-minor", p, classes = listOf("minor", "service", "track"), minZoom = 15f),
                        streetLabel("highway-name-major", p, classes = listOf("primary", "secondary", "tertiary", "trunk"), minZoom = 12.2f),
                        waterLabel(p),
                        parkLabel(p),
                        placeLabel(p),
                    ),
                ),
            )
        }
    }

    // ---------------------------------------------------------------- layer builders

    private fun background(p: MapPalette) = buildJsonObject {
        put("id", "background")
        put("type", "background")
        putJsonObject("paint") { put("background-color", p.land) }
    }

    private fun fill(id: String, sourceLayer: String, color: String, filter: JsonElement? = null) = buildJsonObject {
        put("id", id)
        put("type", "fill")
        put("source", TILE_SOURCE_ID)
        put("source-layer", sourceLayer)
        if (filter != null) put("filter", filter)
        putJsonObject("paint") { put("fill-color", color) }
    }

    private fun line(id: String, sourceLayer: String, color: String, widthAt15: Float) = buildJsonObject {
        put("id", id)
        put("type", "line")
        put("source", TILE_SOURCE_ID)
        put("source-layer", sourceLayer)
        putJsonObject("layout") {
            put("line-cap", "round")
            put("line-join", "round")
        }
        putJsonObject("paint") {
            put("line-color", color)
            put("line-width", zoomWidth(widthAt15, from = 10, to = 20))
        }
    }

    private fun road(
        id: String,
        color: String,
        widthAt15: Float,
        classes: List<String>,
        minZoom: Float? = null,
    ) = buildJsonObject {
        put("id", id)
        put("type", "line")
        put("source", TILE_SOURCE_ID)
        put("source-layer", "transportation")
        if (minZoom != null) put("minzoom", minZoom)
        put(
            "filter",
            all(
                // Bridges and tunnels are left out (no building / extrusion layers either).
                arr("match", arr("get", "brunnel"), arr("bridge", "tunnel"), false, true),
                arr("match", arr("get", "class"), JsonArray(classes.map { JsonPrimitive(it) }), true, false),
                arr("!=", arr("get", "ramp"), 1),
            ),
        )
        putJsonObject("layout") {
            put("line-cap", "round")
            put("line-join", "round")
        }
        putJsonObject("paint") {
            put("line-color", color)
            put("line-width", zoomWidth(widthAt15, from = 10, to = 20))
        }
    }

    private fun textField(): JsonElement =
        arr("coalesce", arr("get", "name_en"), arr("get", "name"))

    private fun streetLabel(id: String, p: MapPalette, classes: List<String>, minZoom: Float) = buildJsonObject {
        put("id", id)
        put("type", "symbol")
        put("source", TILE_SOURCE_ID)
        put("source-layer", "transportation_name")
        put("minzoom", minZoom)
        put("filter", arr("match", arr("get", "class"), JsonArray(classes.map { JsonPrimitive(it) }), true, false))
        putJsonObject("layout") {
            put("symbol-placement", "line")
            put("text-field", textField())
            putJsonArray("text-font") { add(JsonPrimitive(FONT_REGULAR)) }
            put("text-rotation-alignment", "map")
            put("text-size", 11)
        }
        putJsonObject("paint") { labelPaint(p.label, p.labelHalo) }
    }

    private fun waterLabel(p: MapPalette) = buildJsonObject {
        put("id", "water_name_label")
        put("type", "symbol")
        put("source", TILE_SOURCE_ID)
        put("source-layer", "water_name")
        putJsonObject("layout") {
            put("text-field", textField())
            putJsonArray("text-font") { add(JsonPrimitive(FONT_ITALIC)) }
            put("text-letter-spacing", 0.2)
            put("text-max-width", 5)
            put("text-size", 11)
        }
        putJsonObject("paint") { labelPaint(p.waterLabel, p.labelHalo) }
    }

    private fun parkLabel(p: MapPalette) = buildJsonObject {
        put("id", "park_label")
        put("type", "symbol")
        put("source", TILE_SOURCE_ID)
        put("source-layer", "park")
        put("minzoom", 14)
        putJsonObject("layout") {
            put("text-field", textField())
            putJsonArray("text-font") { add(JsonPrimitive(FONT_REGULAR)) }
            put("text-max-width", 7)
            put("text-size", 10)
        }
        putJsonObject("paint") { labelPaint(p.parkLabel, p.labelHalo) }
    }

    private fun placeLabel(p: MapPalette) = buildJsonObject {
        put("id", "place_label")
        put("type", "symbol")
        put("source", TILE_SOURCE_ID)
        put("source-layer", "place")
        put("minzoom", 8)
        put("filter", arr("match", arr("get", "class"), arr("city", "town", "village", "suburb", "neighbourhood", "quarter"), true, false))
        putJsonObject("layout") {
            put("text-field", textField())
            putJsonArray("text-font") { add(JsonPrimitive(FONT_REGULAR)) }
            put("text-max-width", 8)
            put("text-size", 11)
        }
        putJsonObject("paint") { labelPaint(p.label, p.labelHalo) }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.labelPaint(color: String, halo: String) {
        put("text-color", color)
        put("text-halo-color", halo)
        put("text-halo-width", 1.5)
    }

    // ---------------------------------------------------------------- expression helpers

    /** `width(z) = widthAt15 * 1.2^(z - 15)`, one stop per integer zoom: "scale with zoom". */
    private fun zoomWidth(widthAt15: Float, from: Int, to: Int): JsonElement = buildJsonArray {
        add(JsonPrimitive("interpolate"))
        add(arr("linear"))
        add(arr("zoom"))
        for (z in from..to) {
            add(JsonPrimitive(z))
            var factor = 1.0
            repeat(kotlin.math.abs(z - 15)) { factor *= 1.2 }
            val w = if (z >= 15) widthAt15 * factor else widthAt15 / factor
            add(JsonPrimitive((w * 100).toInt() / 100.0))
        }
    }

    private fun arr(vararg items: Any): JsonArray = JsonArray(items.map(::element))

    private fun element(item: Any): JsonElement = when (item) {
        is JsonElement -> item
        is String -> JsonPrimitive(item)
        is Boolean -> JsonPrimitive(item)
        is Int -> JsonPrimitive(item)
        is Double -> JsonPrimitive(item)
        else -> error("unsupported $item")
    }

    private fun all(vararg conditions: JsonElement): JsonArray = JsonArray(listOf(JsonPrimitive("all")) + conditions)
    private fun eq(key: String, value: String): JsonElement = arr("==", arr("get", key), value)
    private fun notEq(key: String, value: String): JsonElement = arr("!=", arr("get", key), value)
}
