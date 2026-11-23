package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.i18n.tr
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

data class TitleDifficulty(val raw: Double, val deckId: Int, val words: Int?, val speechMinutes: Int?) {
    val percent: Int get() = (raw / 5 * 100).roundToInt().coerceIn(0, 100)
    val band: Int get() = bandOf(raw)
    val label: String get() = labelOf(band)
    val url: String get() = "https://jiten.moe/decks/media/$deckId/detail"

    companion object {
        private val CUTS = doubleArrayOf(1.01, 2.01, 3.01, 4.01, 4.95)

        fun credit(): String = tr("Difficulty: Jiten (CC BY-SA 4.0)")

        fun bandOf(raw: Double): Int = CUTS.count { raw >= it }

        fun labelOf(band: Int): String = when (band) {
            0 -> tr("Beginner")
            1 -> tr("Easy")
            2 -> tr("Average")
            3 -> tr("Hard")
            4 -> tr("Expert")
            else -> tr("Insane")
        }
    }
}

sealed interface JitenResult {
    class Got(val value: TitleDifficulty?) : JitenResult
    class Limited(val seconds: Int) : JitenResult
    object Refused : JitenResult
    object Failed : JitenResult
}

class JitenApi(private val transport: JitenTransport) {
    private val api = "https://api.jiten.moe/api"

    fun deck(anilistId: Int): Int? = reply("$api/media-deck/by-link-id/4/$anilistId").let { if (it.code == 200) parseDeck(it.text()) else null }

    fun lookup(anilistId: Int): JitenResult = try {
        val link = reply("$api/media-deck/by-link-id/4/$anilistId")
        failure(link) ?: if (link.code == 404) JitenResult.Got(null) else {
            val deck = parseDeck(link.text())
            if (deck == null) JitenResult.Got(null) else {
                val detail = reply("$api/media-deck/$deck/detail")
                failure(detail) ?: if (detail.code == 404) JitenResult.Got(null) else JitenResult.Got(parseDetail(deck, detail.text()))
            }
        }
    } catch (e: java.io.IOException) {
        JitenResult.Failed
    } catch (e: org.json.JSONException) {
        JitenResult.Failed
    }

    private fun reply(url: String) = transport.get(url, Jiten.userAgent())

    private fun failure(r: Reply): JitenResult? = when (r.code) {
        200, 404 -> null
        429 -> JitenResult.Limited(r.retryAfter ?: 60)
        418, 403 -> JitenResult.Refused
        else -> JitenResult.Failed
    }

    companion object {
        fun parseDeck(json: String): Int? = JSONArray(json).let { a -> (0 until a.length()).firstNotNullOfOrNull { a.optInt(it, -1).takeIf { id -> id > 0 } } }

        fun parseDetail(deckId: Int, json: String): TitleDifficulty? {
            val main = JSONObject(json).optJSONObject("data")?.optJSONObject("mainDeck") ?: return null
            val raw = main.optDouble("difficultyRaw", Double.NaN).takeIf { !it.isNaN() }
                ?: main.optDouble("difficulty", Double.NaN).takeIf { !it.isNaN() && it >= 0 } ?: return null
            val ms = main.optDouble("speechDuration", Double.NaN)
            return TitleDifficulty(
                raw, main.optInt("deckId", deckId),
                main.optInt("wordCount", -1).takeIf { it >= 0 },
                ms.takeIf { !it.isNaN() && it > 0 }?.let { (it / 60_000).roundToInt() },
            )
        }
    }
}
