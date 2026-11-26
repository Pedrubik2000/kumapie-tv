package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import io.github.pedrubik2000.kumapie.data.Addons
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object DeviceFile {
    const val FORMAT = "kumapie-device-1"
    const val PREFIX = "kumapie-export-"

    private const val SAVED = "alone_saved"
    private const val SEEN = "alone_seen"
    private const val LAST = "alone_last_"

    data class Count(val what: String, val add: Int, val change: Int, val keep: Int)

    class Mine(val device: String, val parts: JSONObject, val known: Map<String, Pair<Long, Set<String>>>,
               val personal: List<PersonalDictionary.Entry>, val streams: Map<String, Any?>, val events: JSONArray, val backup: JSONObject?,
               val carded: JSONObject = JSONObject(), val person: String = SrsCards.LOCAL)

    class Plan(val from: String, val person: String, val counts: List<Count>, val others: JSONObject,
               val known: Map<String, Pair<Long, Set<String>>>, val personal: List<PersonalDictionary.Entry>,
               val streams: Map<String, Any>, val events: JSONArray, val backup: Boolean, val carded: JSONObject = JSONObject()) {
        val changes: Boolean get() = counts.any { it.add + it.change > 0 }
    }

    fun wordKey(lang: String, word: String, fields: String): String? {
        val f = runCatching { JSONObject(fields) }.getOrNull()
        val key = if (lang == Lang.JAPANESE.code) {
            val all = buildMap { f?.keys()?.forEach { put(it, f.optString(it)) }; put("Word", word) }
            CardIndex.japaneseKeys(AnkiCards.Note(0, all, emptySet())).firstOrNull()
        } else CardIndex.form(word).takeIf { it.isNotEmpty() }
        return key?.let { "$lang|$it" }
    }

    fun events(lang: String, dump: JSONObject): List<JSONObject> {
        val keys = dump.optJSONArray("cards").objects().associate { it.getLong("id") to wordKey(lang, it.getString("word"), it.optString("fields", "{}")) }
        return dump.optJSONArray("revlog").objects().mapNotNull { r ->
            keys[r.getLong("card")]?.let { k ->
                JSONObject().put("id", r.getLong("id")).put("key", k).put("time", r.getLong("time")).put("ease", r.getInt("rating")).put("device", r.optString("device"))
            }
        }.sortedBy { it.getLong("id") }
    }

    fun union(a: JSONArray, b: List<JSONObject>): JSONArray =
        JSONArray((a.objects() + b).associateBy { it.getLong("id") }.toSortedMap().values.toList())

    fun replay(events: JSONArray, fsrs: Fsrs6): Map<String, Srs.Replayed?> = events.objects()
        .groupBy({ it.getString("key") }, { it.getLong("time") to it.getInt("ease") })
        .mapValues { (_, rows) -> Srs.replay(rows.sortedBy { it.first }, fsrs) }

    fun plan(mine: Mine, file: JSONObject): Plan {
        require(file.optString("format") == FORMAT) { tr("Not a kumapie export file") }
        val from = file.getString("device")
        val person = file.optString("person", mine.person)
        require(person == mine.person) { tr("This file is %1\$s's and this device is %2\$s's: not merged.", person, mine.person) }
        val theirs = file.optJSONObject("progress") ?: JSONObject()
        val others = JSONObject(mine.parts.toString()).apply { remove(mine.device) }
        for (k in theirs.keys()) {
            val t = theirs.getJSONObject(k)
            if (k != mine.device && others.optJSONObject(k)?.let { newer(t, it) } != false) others.put(k, t)
        }
        val before = Progress.merge(values(mine.parts))
        val after = Progress.merge(values(others) + listOfNotNull(mine.parts.optJSONObject(mine.device)))

        val known = HashMap<String, Pair<Long, Set<String>>>()
        var knownCount = Count("", 0, 0, 0)
        file.optJSONObject("known")?.let { o ->
            for (l in o.keys()) {
                val t = o.getJSONObject(l)
                val had = mine.known[l] ?: (0L to emptySet())
                val words = t.optJSONArray("words").strings().toSet()
                val now = if (t.getLong("time") > had.first) (t.getLong("time") to words).also { known[l] = it }.second else had.second
                knownCount = knownCount.copy(add = knownCount.add + (now - had.second).size, change = knownCount.change + (had.second - now).size,
                    keep = knownCount.keep + (had.second intersect now).size)
            }
        }

        val incoming = PersonalDictionary.fromJson(file.optJSONArray("personal") ?: JSONArray())
        val personal = PersonalDictionary.merge(mine.personal, incoming)
        val old = mine.personal.associateBy { it.lang to it.text }
        val streams = streams(mine.streams, file.optJSONObject("streams") ?: JSONObject())
        val events = union(mine.events, file.optJSONArray("events").objects())
        val previous = mine.backup?.optJSONObject("cards")?.let(::cardTimes).orEmpty()
        val cards = cardTimes(file.optJSONObject("cards") ?: JSONObject())
        val backup = mine.backup == null || file.optLong("made") >= mine.backup.optLong("made")
        val savedIds = { v: Any? -> JSONArray((v as? String) ?: "[]").objects().map { it.optString("id") }.toSet() }
        val titles = savedIds(mine.streams[SAVED])
        val lastKeys = mine.streams.keys.filter { it.startsWith(LAST) }.toSet()
        val lastTaken = streams.keys.filter { it.startsWith(LAST) }
        val carded = WordSync.carded(mine.carded, file.optJSONObject("carded"), mine.device)

        return Plan(from, person, listOf(
            Count("Cards backed up", if (backup) (cards.keys - previous.keys).size else 0,
                if (backup) cards.count { (id, m) -> previous[id]?.let { it != m } == true } else 0,
                if (backup) cards.count { (id, m) -> previous[id] == m } else previous.size),
            Count("Review events", events.length() - mine.events.length(), 0, mine.events.length()),
            knownCount.copy(what = "Known words"),
            sets("Marks", before.marked, after.marked),
            Count("Places", (after.posAt.keys - before.posAt.keys).size + lastTaken.count { it !in lastKeys },
                before.posAt.keys.count { after.posAt[it] != before.posAt[it] || after.pos[it] != before.pos[it] } + lastTaken.count { it in lastKeys },
                before.posAt.keys.count { after.posAt[it] == before.posAt[it] && after.pos[it] == before.pos[it] } + lastKeys.count { it !in streams }),
            Count("Personal dictionary", personal.count { (it.lang to it.text) !in old },
                personal.count { e -> old[e.lang to e.text]?.let { it != e } == true }, personal.count { e -> old[e.lang to e.text] == e }),
            sets("Kept titles", titles, streams[SAVED]?.let(savedIds) ?: titles),
            WordSync.count(mine.carded, carded, mine.device),
        ), others, known, personal, streams, events, backup, carded)
    }

    fun streams(mine: Map<String, Any?>, theirs: JSONObject): Map<String, Any> {
        val out = HashMap<String, Any>()
        fun at(v: Any?) = (v as? String)?.let { runCatching { JSONObject(it).optLong("at") }.getOrNull() } ?: -1L
        val newer = theirs.keys().asSequence().filter { it.startsWith(LAST) && at(theirs.get(it)) > at(mine[it]) }.toList()
        newer.forEach { out[it] = theirs.getString(it) }
        val titles = newer.map { it.removePrefix(LAST) }.toSet()
        for (k in theirs.keys()) {
            val v = theirs.get(k)
            when {
                k.startsWith(LAST) -> Unit
                k == SAVED -> {
                    val had = JSONArray((mine[k] as? String) ?: "[]").objects()
                    val ids = had.map { it.optString("id") }.toSet()
                    val add = JSONArray(v.toString()).objects().filter { it.optString("id") !in ids }
                    if (add.isNotEmpty()) out[k] = JSONArray(had + add).toString()
                }
                k == SEEN -> {
                    val had = (mine[k] as? Set<*>).orEmpty().map { it.toString() }.toSet()
                    val all = had + (v as? JSONArray).strings()
                    if (all != had) out[k] = all
                }
                else -> {
                    val value: Any = when (v) { is Number -> v.toFloat(); is JSONArray -> v.strings().toSet(); else -> v.toString() }
                    val place = k.startsWith("alone_pos_") || k.startsWith("alone_len_")
                    if (mine[k] == null || (place && Addons.titleOf(k.drop(10)) in titles && mine[k] != value)) out[k] = value
                }
            }
        }
        return out
    }

    private fun newer(a: JSONObject, b: JSONObject) = age(a).let { x -> age(b).let { y -> x.first > y.first || (x.first == y.first && x.second > y.second) } }

    private fun age(p: JSONObject): Pair<Long, Double> {
        var t = 0L
        fun each(name: String, time: (Any) -> Long) { p.optJSONObject(name)?.let { o -> for (k in o.keys()) t = maxOf(t, runCatching { time(o.get(k)) }.getOrDefault(0)) } }
        each("pos") { (it as JSONArray).optLong(1) }
        each("marked") { (it as JSONArray).optLong(1) }
        each("ranges") { (it as JSONArray).optLong(0) }
        each("done") { (it as Number).toLong() }
        each("unwatched") { (it as Number).toLong() }
        val watch = p.optJSONObject("watch")?.let { o -> o.keys().asSequence().sumOf { o.optDouble(it, 0.0) } } ?: 0.0
        return t to watch
    }

    private fun sets(what: String, before: Set<String>, after: Set<String>) =
        Count(what, (after - before).size, (before - after).size, (before intersect after).size)

    private fun cardTimes(cards: JSONObject): Map<String, Long> = cards.keys().asSequence().flatMap { l ->
        cards.getJSONObject(l).optJSONArray("cards").objects().map { "$l/${it.getLong("id")}" to it.optLong("modified") }
    }.toMap()

    private fun values(o: JSONObject) = o.keys().asSequence().mapNotNull { o.optJSONObject(it) }.toList()

    internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }
}

class DeviceFiles(private val context: Context, private val languages: List<Language>) {
    private val settings = Settings(context)
    private val srs = Cards.get(context).srs
    private val progress = Progress.of(context)
    private val personal = PersonalDictionary.get(context)
    private val dir = File(context.filesDir, "devices").apply { mkdirs() }
    private val eventsFile = File(dir, "events.json")
    private val cardedFile = File(dir, "carded.json")
    private val shared: File? get() = context.getExternalFilesDir(null)

    class Exported(val file: File, val cards: Int, val reviews: Int, val mediaFiles: Int, val mediaBytes: Long) {
        fun line() = tr("Saved %1\$s: %2\$d cards, %3\$d reviews. Card pictures and audio: %4\$d files, %5\$.1f MB (not in the file).",
            file.path, cards, reviews, mediaFiles, mediaBytes / 1e6)
    }

    fun export(): Exported {
        val o = payload().put("counted", JSONObject().apply { languages.forEach { l ->
            kotlinx.coroutines.runBlocking { if (!l.known.loadCards()) runCatching { l.known.readCards() } }
            put(l.lang.code, JSONArray(l.known.knownList()))
        } })
        val out = File(requireNotNull(shared) { "no shared storage" }, "${DeviceFile.PREFIX}${safe(progress.device)}.json")
        File(out.path + ".part").apply { writeText(o.toString()) }.renameTo(out)
        val cards = o.getJSONObject("cards")
        val n = cards.keys().asSequence().map { cards.getJSONObject(it) }.toList()
        val media = o.getJSONObject("media")
        return Exported(out, n.sumOf { it.getJSONArray("cards").length() }, n.sumOf { it.getJSONArray("revlog").length() }, media.getInt("files"), media.getLong("bytes"))
    }

    fun payload(): JSONObject {
        val cards = JSONObject()
        for (l in Lang.ALL) if (File(context.filesDir, "srs/${srs.person()}/${l.code}.db").exists()) srs.store(l)?.let { cards.put(l.code, it.dump()) }
        val own = cards.keys().asSequence().flatMap { DeviceFile.events(it, cards.getJSONObject(it)) }.toList()
        val media = CardMedia.dir(context).listFiles().orEmpty().filter { it.isFile }
        return JSONObject().put("format", DeviceFile.FORMAT).put("device", progress.device).put("model", android.os.Build.MODEL)
            .put("person", srs.person()).put("made", System.currentTimeMillis())
            .put("progress", progress.parts())
            .put("known", JSONObject().apply { languages.forEach { put(it.lang.code, JSONObject().put("time", it.known.importedTime()).put("words", JSONArray(it.known.importedWords.sorted()))) } })
            .put("personal", personal.entries())
            .put("streams", JSONObject().apply { streams().forEach { (k, v) -> put(k, if (v is Set<*>) JSONArray(v) else v) } })
            .put("events", DeviceFile.union(storedEvents(), own))
            .put("carded", carded()).put("fsrs", fsrs())
            .put("cards", cards)
            .put("media", JSONObject().put("files", media.size).put("bytes", media.sumOf { it.length() }))
    }

    fun incoming(): File? = shared?.listFiles { f -> f.name.startsWith(DeviceFile.PREFIX) && f.name.endsWith(".json") && f.name != "${DeviceFile.PREFIX}${safe(progress.device)}.json" }
        ?.maxByOrNull { it.lastModified() }

    fun plan(file: File): DeviceFile.Plan = plan(JSONObject(file.readText()))

    fun plan(o: JSONObject): DeviceFile.Plan {
        val previous = File(dir, safe(o.optString("device")) + ".json").takeIf { it.exists() }?.let { JSONObject(it.readText()) }
        val mine = DeviceFile.Mine(progress.device, progress.parts(), languages.associate { it.lang.code to (it.known.importedTime() to it.known.importedWords) },
            PersonalDictionary.fromJson(personal.entries()), streams(), DeviceFile.union(storedEvents(), ownEvents()), previous, carded(), srs.person())
        return DeviceFile.plan(mine, o)
    }

    fun apply(plan: DeviceFile.Plan, file: File): File = apply(plan, JSONObject(file.readText()), true)!!

    fun isNew(device: String) = !File(dir, safe(device) + ".json").exists()

    fun apply(plan: DeviceFile.Plan, o: JSONObject, backup: Boolean): File? {
        val copy = if (!backup) null else File(context.filesDir, "backup/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))).apply { mkdirs() }
        val prefs = File(context.filesDir.parentFile, "shared_prefs/kumapie.xml")
        if (copy != null) (context.filesDir.listFiles { f -> f.name == "srs" || f.name == "devices" || f.name.startsWith("known") || f.name.startsWith("progress") || f.name == "personal-dictionary.json" }.orEmpty().toList() + prefs)
            .filter { it.exists() }.forEach { it.copyRecursively(File(copy, it.name), overwrite = true) }
        copy?.parentFile?.listFiles()?.sortedByDescending { it.name }?.drop(BACKUPS)?.forEach { it.deleteRecursively() }
        progress.useOthers(plan.others)
        languages.forEach { l -> plan.known[l.lang.code]?.let { (time, words) -> l.known.setImported(ImportedSources.PEERS, words, time) } }
        languages.forEach { l -> l.known.useMarked(progress.merged.marked.mapNotNull(l::wordOfMark).toSet()) }
        personal.take(plan.personal)
        settings.prefs.edit().apply {
            plan.streams.forEach { (k, v) ->
                @Suppress("UNCHECKED_CAST")
                when (v) { is Float -> putFloat(k, v); is Set<*> -> putStringSet(k, v as Set<String>); else -> putString(k, v.toString()) }
            }
        }.apply()
        File(eventsFile.path + ".part").apply { writeText(plan.events.toString()) }.renameTo(eventsFile)
        if (plan.backup) File(dir, safe(plan.from) + ".json").let { f -> File(f.path + ".part").apply { writeText(o.toString()) }.renameTo(f) }
        File(cardedFile.path + ".part").apply { writeText(plan.carded.toString()) }.renameTo(cardedFile)
        share()
        return copy
    }

    private fun streams(): Map<String, Any?> = settings.prefs.all.filterKeys { it.startsWith("alone_") }

    fun carded(): JSONObject {
        val keys = HashSet<String>()
        val forms = HashMap<String, MutableSet<String>>()
        for ((l, s) in stores()) {
            val index = languages.firstOrNull { it.lang == l }?.known?.cardForms().orEmpty()
            for (c in s.cards()) {
                val k = DeviceFile.wordKey(l.code, c.word, c.fields) ?: continue
                keys += k
                val main = k.substringAfter('|').lowercase().let { setOf(it, it.substringBefore('\t')) }
                index[c.note]?.map { it.lowercase() }?.filter { '\t' !in it && it !in main }?.takeIf { it.isNotEmpty() }?.let { forms.getOrPut(k) { HashSet() } += it }
            }
        }
        return JSONObject(runCatching { cardedFile.readText() }.getOrDefault("{}"))
            .put(progress.device, WordSync.own(progress.device, System.currentTimeMillis(), keys, forms).getJSONObject(progress.device))
    }

    fun share() {
        val events = storedEvents()
        var took = false
        for ((l, s) in stores()) {
            val keys = s.cards().associate { it.id to DeviceFile.wordKey(l.code, it.word, it.fields) }
            WordSync.toAdd(events, keys, s.revlogIds()).takeIf { it.isNotEmpty() }?.let { s.take(it); took = true }
        }
        if (took) cardsChanged()
        elsewhere()
    }

    /** Other devices' ratings landed in the cards: colours ask again at once (the provider's one-minute wait, the open app's cards). */
    private fun cardsChanged() {
        WordSync.changedAt = System.currentTimeMillis()
        languages.filter { it.known.ready }.forEach { l -> srs.background.launch { runCatching { l.known.reloadCards() } } }
    }

    fun elsewhere() {
        val carded = carded()
        val here = carded.getJSONObject(progress.device).getJSONArray("keys").let { a -> List(a.length()) { a.getString(it) }.toSet() }
        WordSync.words = WordSync.elsewhere(storedEvents(), carded, progress.device, here, { srs.store(Lang.of(it))?.fsrs ?: Fsrs6() })
    }

    fun fsrs(): JSONObject = JSONObject().apply { for ((l, s) in stores()) put(l.code, JSONObject().put("params", s.meta("params")).put("retention", s.meta("retention"))) }

    fun takeFsrs(o: JSONObject?) {
        for ((l, s) in stores()) o?.optJSONObject(l.code)?.let { s.useFsrs(it.optString("params").ifEmpty { null }, it.optString("retention").ifEmpty { null }) }
    }

    private fun stores(): List<Pair<Lang, Srs>> = Lang.ALL.filter { File(context.filesDir, "srs/${srs.person()}/${it.code}.db").exists() }.mapNotNull { l -> srs.store(l)?.let { l to it } }


    private fun storedEvents(): JSONArray = runCatching { JSONArray(eventsFile.readText()) }.getOrDefault(JSONArray())

    private fun ownEvents(): List<JSONObject> = Lang.ALL.filter { File(context.filesDir, "srs/${srs.person()}/${it.code}.db").exists() }
        .flatMap { l -> srs.store(l)?.let { DeviceFile.events(l.code, it.dump()) }.orEmpty() }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        const val BACKUPS = 5
    }
}
