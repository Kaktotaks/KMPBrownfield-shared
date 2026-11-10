package com.example.tasks.shared.geocoding

import com.example.tasks.shared.domain.GeoPoint
import com.example.tasks.shared.domain.Place

interface Geocoder {
    /** Short address ("Shevchenka St 9") of [point], or null when nothing is there. Throws on network errors. */
    suspend fun reverse(point: GeoPoint): String?

    /** Address search, biased to Dnipro, results in [language] ("en", "uk"). Throws on network errors. */
    suspend fun search(query: String, language: String): List<Place>
}
