package io.github.pedrubik2000.kumapie.data

import android.content.SharedPreferences
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import io.github.pedrubik2000.kumapie.i18n.Tr
import java.util.Locale

object ContinueHidden {
    private fun key(profile: String) = "continue_hidden_$profile"
    private const val WEEK_S = 7 * 86400L

    fun read(prefs: SharedPreferences, profile: String): Map<String, Long> = runCatching {
        JSONObject(prefs.getString(key(profile), "{}").orEmpty()).let { o -> o.keys().asSequence().associateWith { o.getLong(it) } }
    }.getOrDefault(emptyMap())

    fun hide(prefs: SharedPreferences, profile: String, show: Show, episode: Episode): Map<String, Long> = save(prefs, profile, read(prefs, profile) + (show.id to episode.resumedAt))

    fun unhide(prefs: SharedPreferences, profile: String, id: String): Map<String, Long> = save(prefs, profile, read(prefs, profile) - id)

    fun shown(row: List<Pair<Show, Episode>>, hidden: Map<String, Long>): List<Pair<Show, Episode>> =
        if (hidden.isEmpty()) row else row.filter { (s, e) -> hidden[s.id]?.let { e.resumedAt > it } ?: true }

    private fun save(prefs: SharedPreferences, profile: String, map: Map<String, Long>): Map<String, Long> {
        prefs.edit().putString(key(profile), JSONObject(map).toString()).apply()
        return map
    }

    /** Only a stream can be unaired: a file on the server has aired. */
    fun airsAt(mine: Mine?, s: Show, e: Episode, now: Long): Long? =
        if (s.kind != "stream") null else
        mine?.takeIf { it.nextEp > 0 && it.nextAt > now && (e.number ?: 0) >= it.nextEp }?.nextAt

    fun mineOf(byKitsu: Map<String, Mine>, s: Show): Mine? = s.id.takeIf { it.startsWith("kitsu:") }?.let { byKitsu[it.removePrefix("kitsu:")] }

    fun soonLast(row: List<Pair<Show, Episode>>, byKitsu: Map<String, Mine>, now: Long): List<Pair<Show, Episode>> =
        row.partition { (s, e) -> airsAt(mineOf(byKitsu, s), s, e, now) == null }.let { it.first + it.second }

    fun airsText(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Tr.locale): String =
        DateTimeFormatter.ofPattern(if (at - now < WEEK_S) "EEE HH:mm" else "d MMM HH:mm", locale).format(Instant.ofEpochSecond(at).atZone(zone))
}
