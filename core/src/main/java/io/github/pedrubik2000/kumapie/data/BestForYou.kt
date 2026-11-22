package io.github.pedrubik2000.kumapie.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

object BestForYou {
    class Item(val id: Int, val anime: Boolean, val status: String, val score: Double, val published: String, val average: Int?, val features: List<String>)

    private val RATED = setOf("COMPLETED", "REPEATING", "DROPPED")
    private val OPEN = setOf("PLANNING", "CURRENT", "PAUSED")

    fun items(data: JSONObject): List<Item> {
        val seen = HashSet<Int>()
        return listOf("anime", "manga").flatMap { key -> data.optJSONObject(key)?.optJSONArray("lists")?.objects().orEmpty() }
            .flatMap { it.optJSONArray("entries")?.objects().orEmpty() }.mapNotNull { e ->
                val m = e.getJSONObject("media")
                if (!seen.add(m.getInt("id"))) return@mapNotNull null
                val tags = m.optJSONArray("tags")?.objects().orEmpty().filter { it.optInt("rank") >= 60 && !it.optBoolean("isMediaSpoiler") }
                    .map { it.getString("name") }.take(6)
                Item(m.getInt("id"), m.optString("type") == "ANIME", e.optString("status"), e.optDouble("score", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                    m.optString("status"), m.optInt("averageScore").takeIf { !m.isNull("averageScore") }, m.optJSONArray("genres").strings() + tags)
            }
    }

    fun planning(items: List<Item>): List<Int> {
        val rank = rank(items)
        return items.filter { it.anime && it.status == "PLANNING" && it.id in rank }.sortedByDescending { rank.getValue(it.id) }.map { it.id }
    }

    fun rank(items: List<Item>): Map<Int, Double> {
        val rated = items.filter { it.status in RATED }
        val scores = rated.map { it.score }.filter { it > 0 }
        val mean = scores.sum() / maxOf(scores.size, 1)
        val sd = sqrt(scores.sumOf { (it - mean) * (it - mean) } / maxOf(scores.size, 1)).takeIf { it != 0.0 } ?: 1.0
        val w = HashMap<String, Double>()
        val n = HashMap<String, Int>()
        for (d in rated) {
            val v = (if (d.score > 0) (d.score - mean) / sd else 0.0) + (if (d.status == "DROPPED") -1.2 else 0.3)
            for (f in d.features) { w[f] = (w[f] ?: 0.0) + v; n[f] = (n[f] ?: 0) + 1 }
        }
        for (f in w.keys) w[f] = w.getValue(f) / (n.getValue(f) + 2)
        val cands = items.filter { it.status in OPEN && it.published != "CANCELLED" }
        val fit = cands.map { d -> d.features.filter { it in w }.let { fs -> if (fs.isEmpty()) 0.0 else fs.fold(0.0) { a, f -> a + w.getValue(f) } / sqrt(fs.size.toDouble()) } }
        val zf = z(fit, fit)
        val za = z(cands.map { it.average?.toDouble() }, cands.map { (it.average ?: 0).toDouble() })
        return cands.indices.associate { cands[it].id to 0.6 * zf[it] + 0.4 * za[it] }
    }

    private fun z(values: List<Double?>, forMean: List<Double>): List<Double> {
        val m = forMean.sum() / maxOf(forMean.size, 1)
        val s = sqrt(forMean.sumOf { (it - m) * (it - m) } / maxOf(forMean.size, 1)).takeIf { it != 0.0 } ?: 1.0
        return values.map { ((it ?: m) - m) / s }
    }

    private fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
}
