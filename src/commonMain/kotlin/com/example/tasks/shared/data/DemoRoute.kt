package com.example.tasks.shared.data

import com.example.tasks.shared.domain.GeoPoint
import com.example.tasks.shared.domain.Place
import com.example.tasks.shared.domain.Task
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/**
 * Demo data so the Map tab is not empty on first run.
 *
 * Coordinates are NOT invented: each was resolved once with Nominatim
 * (`/search?format=jsonv2&countrycodes=ua`, User-Agent `KMPBrownfield-Tasks/0.9`) on 2026-11-09.
 * The OSM element is noted next to each point. `Hlinky St` was renamed to Korolevy Elyzavety II St
 * in 2023; the name used by the design is kept as the display line.
 */
object DemoRoute {
    /** Dobrovoltsiv Lane 6 (Nominatim: way 94127679, "6, провулок Добровольців"). */
    val START = Place(GeoPoint(48.4095940, 35.0773894), "Dobrovoltsiv Ln 6")

    /** Ten addresses in central Dnipro. Index 0..4 = today's route, 5..8 = tomorrow's, 9 is spare. */
    val ADDRESSES: List<Place> = listOf(
        Place(GeoPoint(48.4613160, 35.0511356), "Yavornytskoho Ave 47"), // way 1356687262
        Place(GeoPoint(48.4674590, 35.0415924), "Yavornytskoho Ave 60"), // way 80526359
        Place(GeoPoint(48.4560234, 35.0571877), "Shevchenka St 9"), // way 48523654
        Place(GeoPoint(48.4667451, 35.0510405), "Hlinky St 2"), // way 1331908773
        Place(GeoPoint(48.4631112, 35.0393686), "Starokozatska St 33"), // way 81015395
        Place(GeoPoint(48.4571584, 35.0550305), "Shevchenka St 21"), // way 48459187
        Place(GeoPoint(48.4679033, 35.0538035), "Sicheslavska Emb 33"), // way 30447113
        Place(GeoPoint(48.4509877, 35.0708220), "Yavornytskoho Ave 1A"), // way 1394964773
        Place(GeoPoint(48.4664006, 35.0450021), "Monomakha St 5"), // way 81892759
        Place(GeoPoint(48.4702096, 35.0502986), "Sicheslavska Emb 29"), // node 10773301427
    )

    val TODAY_TIMES: List<LocalTime> = listOf(
        LocalTime(9, 30), LocalTime(11, 0), LocalTime(13, 15), LocalTime(15, 30), LocalTime(18, 0),
    )
    val TOMORROW_TIMES: List<LocalTime> = listOf(
        LocalTime(9, 30), LocalTime(11, 30), LocalTime(14, 0), LocalTime(16, 30),
    )

    const val TODAY_COUNT = 5
    const val TOMORROW_COUNT = 4

    /**
     * The tasks to write: up to 5 not-done tasks for [today] and 4 for tomorrow get a time and an
     * address. Only tasks that have neither a time nor a place are touched. If a day has fewer
     * candidates, other free not-done tasks are moved to that day. Returns the changed tasks only.
     */
    fun plan(tasks: List<Task>, today: LocalDate): List<Task> {
        val tomorrow = today.plus(1, DateTimeUnit.DAY)
        val free = tasks.filter { !it.isDone && it.time == null && it.place == null }.sortedBy { it.id }
        val used = HashSet<Int>()

        // Own tasks of both days first, so moving never steals a task that belongs to the other day.
        val todayOwn = free.filter { it.dueDate == today }.take(TODAY_COUNT)
        val tomorrowOwn = free.filter { it.dueDate == tomorrow }.take(TOMORROW_COUNT)
        used += todayOwn.map { it.id }
        used += tomorrowOwn.map { it.id }
        val todayMoved = movedTo(today, TODAY_COUNT - todayOwn.size, free, used, today, tomorrow)
        val tomorrowMoved = movedTo(tomorrow, TOMORROW_COUNT - tomorrowOwn.size, free, used, today, tomorrow)

        return assign(todayOwn + todayMoved, TODAY_TIMES, ADDRESSES.subList(0, TODAY_COUNT)) +
            assign(tomorrowOwn + tomorrowMoved, TOMORROW_TIMES, ADDRESSES.subList(TODAY_COUNT, TODAY_COUNT + TOMORROW_COUNT))
    }

    private fun movedTo(
        date: LocalDate,
        missing: Int,
        free: List<Task>,
        used: MutableSet<Int>,
        today: LocalDate,
        tomorrow: LocalDate,
    ): List<Task> {
        if (missing <= 0) return emptyList()
        val chosen = free.filter { it.id !in used && it.dueDate != today && it.dueDate != tomorrow }.take(missing)
        used += chosen.map { it.id }
        return chosen.map { it.copy(dueDate = date) }
    }

    private fun assign(tasks: List<Task>, times: List<LocalTime>, places: List<Place>): List<Task> =
        tasks.sortedBy { it.id }.mapIndexed { i, task -> task.copy(time = times[i], place = places[i]) }
}
