package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.LangTotal
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.Span
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** Home's time card from one function for the TV and the phone ([Progress.homeTotal]). */
class HomeStatsTest {
    private val today = LocalDate.parse("2026-11-19")

    private fun show(id: String, lang: String, vararg eps: Int) =
        Show(id, id, "stream", "", eps.map { Episode("$id:$it", "Episode $it", 1, it, 1400.0, "", 0, 0, 0, null) }, lang)

    // 300 s today: 100 in Japanese, 50 in German, 150 from before languages were kept (split by the episodes watched).
    private fun view(vararg watched: String) = Progress.View(emptyMap(), mapOf("2026-11-19" to 300.0, "2026-11-10" to 600.0),
        emptyMap(), emptySet(), emptyMap(),
        langWatch = mapOf("ja" to mapOf("2026-11-19" to 100.0), "de" to mapOf("2026-11-19" to 50.0)),
        ranges = watched.associateWith { listOf(Span(0, 60)) })

    private val shows = listOf(show("kitsu:1", "ja", 1, 2), show("de1", "de", 1, 2))

    @Test fun bothHomesGetTheSameNumbers() {
        val v = view("kitsu:1:1", "de1:1")
        val tv = Progress.homeTotal("ja", v, shows, 7, today)
        val phone = Progress.homeTotal("ja", v, shows, 7, today)
        assertEquals(tv, phone)
        assertEquals(LangTotal(175.0, 175.0, 175.0 + 300.0, 7), tv) // half of the old 150 today, half of 600 on 11-10
    }

    @Test fun anotherLanguagesEpisodeIsNotCounted() {
        val v = view("kitsu:1:1", "de1:1", "de1:2")
        assertEquals(150.0, Progress.homeTotal("ja", v, shows, 0, today).today, 1e-9) // 100 + 150 / 3
        assertEquals(150.0, Progress.homeTotal("de", v, shows, 0, today).today, 1e-9) // 50 + 150 * 2 / 3
        assertEquals(mapOf("kitsu:1:1" to "ja", "de1:1" to "de", "de1:2" to "de"), Progress.sceneLang(v, shows))
    }

    @Test fun emptyProgress() =
        assertEquals(LangTotal(0.0, 0.0, 0.0, 3), Progress.homeTotal("ja", Progress.View(emptyMap(), emptyMap(), emptyMap(), emptySet(), emptyMap()), shows, 3, today))
}
