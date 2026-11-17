package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import android.util.Log
import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Settings
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/**
 * Where a person's word cards live (kumapie_plan.md "kumapie's own Anki", step 4d): kuma3 ([AnkiCards], through its
 * provider) or kumapie's own store ([SrsCards]). Every call names its app id (`pkg`, from [KnownWords.ankiApp]):
 * [SrsCards.PKG] for a person whose store came from the PC, a kuma3 app id otherwise (the parents, until step 7).
 */
interface Cards {
    fun installed(): List<String>
    fun permission(pkg: String): String
    fun hasPermission(pkg: String): Boolean
    fun notes(pkg: String, search: String): List<AnkiCards.Note>
    fun cards(pkg: String, search: String): List<AnkiCards.Card>
    fun due(pkg: String): Map<Long, String>?
    /** [watch]: where it was rated (kumapie's revlog keeps it; kuma3 doesn't). */
    fun answer(pkg: String, noteId: Long, ord: Int, ease: Int, ms: Long, watch: Srs.Watch? = null)
    fun undoLabel(pkg: String): String
    fun undo(pkg: String)
    fun sync(pkg: String): Boolean
    fun lastSync(pkg: String): Long?
    fun noteType(pkg: String, names: List<String>): Pair<Long, List<String>>?
    fun deck(pkg: String, name: String): Long
    fun addMedia(pkg: String, file: File, preferredName: String): String
    fun addNote(pkg: String, mid: Long, fields: List<String>, tags: List<String>, deck: Long): Long
    fun updateNote(pkg: String, nid: Long, tags: Set<String>, fields: List<String>?)
    fun addBundledNoteType(pkg: String, lang: Lang): Pair<Long, List<String>>?
    fun flag(pkg: String, nid: Long, ord: Int, flag: Int)

    companion object {
        @Volatile private var router: Router? = null

        /** The one router of this app: each call to kumapie's store or to kuma3 by its `pkg`. */
        fun get(context: Context): Router = router ?: synchronized(this) {
            router ?: Router(AnkiCards(context.applicationContext), SrsCards(context.applicationContext)).also { router = it }
        }
    }

    class Router(val anki: AnkiCards, val srs: SrsCards) : Cards {
        private fun of(pkg: String): Cards = if (pkg == SrsCards.PKG) srs else anki
        override fun installed() = anki.installed()
        override fun permission(pkg: String) = of(pkg).permission(pkg)
        override fun hasPermission(pkg: String) = of(pkg).hasPermission(pkg)
        override fun notes(pkg: String, search: String) = of(pkg).notes(pkg, search)
        override fun cards(pkg: String, search: String) = of(pkg).cards(pkg, search)
        override fun due(pkg: String) = of(pkg).due(pkg)
        override fun answer(pkg: String, noteId: Long, ord: Int, ease: Int, ms: Long, watch: Srs.Watch?) = of(pkg).answer(pkg, noteId, ord, ease, ms, watch)
        override fun undoLabel(pkg: String) = of(pkg).undoLabel(pkg)
        override fun undo(pkg: String) = of(pkg).undo(pkg)
        override fun sync(pkg: String) = of(pkg).sync(pkg)
        override fun lastSync(pkg: String) = of(pkg).lastSync(pkg)
        override fun noteType(pkg: String, names: List<String>) = of(pkg).noteType(pkg, names)
        override fun deck(pkg: String, name: String) = of(pkg).deck(pkg, name)
        override fun addMedia(pkg: String, file: File, preferredName: String) = of(pkg).addMedia(pkg, file, preferredName)
        override fun addNote(pkg: String, mid: Long, fields: List<String>, tags: List<String>, deck: Long) = of(pkg).addNote(pkg, mid, fields, tags, deck)
        override fun updateNote(pkg: String, nid: Long, tags: Set<String>, fields: List<String>?) = of(pkg).updateNote(pkg, nid, tags, fields)
        override fun addBundledNoteType(pkg: String, lang: Lang) = of(pkg).addBundledNoteType(pkg, lang)
        override fun flag(pkg: String, nid: Long, ord: Int, flag: Int) = of(pkg).flag(pkg, nid, ord, flag)
    }
}

/**
 * kuma3's provider calls answered from kumapie's own store ([Srs], one per language of this device's person: the
 * phone's owner, the TV's profile). Cards come out as kuma3 notes with the text fields the import kept (no media);
 * mined ones go in text only. Undo is this run's ratings, last first.
 */
class SrsCards(private val context: Context) : Cards {
    private val settings = Settings(context)
    private val stores = HashMap<String, Srs>()
    /** This run's ratings, newest last: (language, card). */
    private val rated = ArrayList<Pair<String, Long>>()
    @Volatile private var synced = 0L

    /** Whose cards: the phone's owner, else the TV's last profile. */
    fun person(): String = settings.owner.ifEmpty { settings.prefs.getString("profile", "").orEmpty() }

    fun store(lang: Lang): Srs? = person().takeIf { it.isNotEmpty() }?.let { p ->
        synchronized(stores) { stores.getOrPut("$p/${lang.code}") { Srs(context, p, lang) } }
    }

    /** The person's store came from the PC: their cards are kumapie's. */
    fun active(lang: Lang): Boolean = store(lang)?.meta("sync_seq") != null

    /** One language with the PC; false when the PC has no store for this person (they use kuma3) or is away. */
    suspend fun sync(lang: Lang): Boolean {
        val s = store(lang) ?: return false
        if (settings.server.isEmpty()) return false
        val api = Api(settings.server, person())
        return runCatching { s.sync { api.srsSync(lang.code, it) } }
            .onSuccess { Log.i("kumapie", "srs ${lang.code}: $it rows in") }
            .onFailure { Log.w("kumapie", "srs ${lang.code}: not synced: $it") }.isSuccess
    }

    private fun stores(): List<Pair<Lang, Srs>> = Lang.ALL.filter(::active).mapNotNull { l -> store(l)?.let { l to it } }

    override fun installed() = listOf(PKG)
    override fun permission(pkg: String) = ""
    override fun hasPermission(pkg: String) = true

    override fun notes(pkg: String, search: String) = find(search).map { (_, c) -> note(c) }

    override fun cards(pkg: String, search: String): List<AnkiCards.Card> = find(search).groupBy({ it.first }, { it.second }).flatMap { (s, cs) ->
        val counts = s.counts()
        cs.map { c ->
            val (reps, lapses) = counts[c.id] ?: (0 to 0)
            AnkiCards.Card(c.id, c.note, c.stability != null, c.stability, (c.due ?: 0) / 1000, reps, lapses, c.ord, if (c.stability == null) 0 else 2)
        }
    }

    /** `"deck:Deutsch Mined"` (a language's cards) or `nid:1,2` (notes), as [KnownWords] and [Miner] search. */
    private fun find(search: String): List<Pair<Srs, Srs.Card>> {
        val deck = Regex("deck:([^\"]+)").find(search)?.groupValues?.get(1)
        val nids = Regex("nid:([0-9,]+)").find(search)?.groupValues?.get(1)?.split(',')?.mapNotNull { it.toLongOrNull() }?.toSet()
        return stores().filter { (l, _) -> deck == null || l.deck == deck }.flatMap { (_, s) ->
            if (deck == null && nids == null) emptyList() else s.cards().filter { nids == null || it.note in nids }.map { s to it }
        }
    }

    override fun due(pkg: String): Map<Long, String> = HashMap<Long, String>().apply {
        val now = System.currentTimeMillis()
        for ((_, s) in stores()) for (c in s.cards()) when (Srs.state(c.due, now)) { "d" -> put(c.id, "review"); "b" -> put(c.id, "new") }
    }

    override fun answer(pkg: String, noteId: Long, ord: Int, ease: Int, ms: Long, watch: Srs.Watch?) {
        for ((l, s) in stores()) {
            val c = s.cards().firstOrNull { it.note == noteId && it.ord == ord } ?: continue
            s.rate(c.id, ease, watch ?: Srs.Watch(), ms)
            synchronized(rated) { rated += l.code to c.id }
            return
        }
    }

    override fun undoLabel(pkg: String) = synchronized(rated) { rated.lastOrNull()?.let { "srs:${it.second}" }.orEmpty() }

    override fun undo(pkg: String) {
        val (code, id) = synchronized(rated) { rated.removeLastOrNull() } ?: return
        store(Lang.of(code))?.undo(id)
    }

    val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** [sync] in the background (right after a rating or a mined card; offline it waits in the outbox). */
    fun syncSoon(lang: Lang) { background.launch { sync(lang) } }

    /** Every language with the PC (after rating or mining); answers whether it went. */
    override fun sync(pkg: String): Boolean = runBlocking { Lang.ALL.filter(::active).map { sync(it) }.all { it } }
        .also { if (it) synced = System.currentTimeMillis() }

    override fun lastSync(pkg: String): Long = synced

    // ------------------------------------------------------------------ mining (text only)

    override fun noteType(pkg: String, names: List<String>): Pair<Long, List<String>>? =
        Lang.ALL.indexOfFirst { l -> names.any { it in l.noteTypes } }.takeIf { it >= 0 }?.let { it.toLong() to fields(Lang.ALL[it]) }

    override fun addBundledNoteType(pkg: String, lang: Lang) = Lang.ALL.indexOf(lang).toLong() to fields(lang)

    override fun deck(pkg: String, name: String): Long = Lang.ALL.indexOfFirst { it.deck == name }.toLong().also { require(it >= 0) { "no deck $name" } }

    override fun addMedia(pkg: String, file: File, preferredName: String) = ""

    override fun addNote(pkg: String, mid: Long, fields: List<String>, tags: List<String>, deck: Long): Long {
        val lang = Lang.ALL[deck.toInt()]
        val f = fields(Lang.ALL[mid.toInt()]).zip(fields).toMap()
        val ctx = CONTEXT.find(f["Context"].orEmpty())
        val s = requireNotNull(store(lang)) { "no person on this device" }
        return s.addCard(text(f["Word"]), text(f["Sentence"]), text(f[KnownWords.DEF_BI]), ctx?.groupValues?.get(1)?.unescape(),
            ctx?.groupValues?.get(2)?.unescape(), ctx?.groupValues?.get(3)?.toIntOrNull(), rest(f), tags.joinToString(" ")).id.also { syncSoon(lang) }
    }

    override fun updateNote(pkg: String, nid: Long, tags: Set<String>, fields: List<String>?) {
        for ((_, s) in stores()) {
            val c = s.cards().firstOrNull { it.note == nid } ?: continue
            val f = fields?.let { names(c).zip(it).toMap() } ?: note(c).fields
            s.updateCard(c.id, text(f["Word"]), text(f["Sentence"]), text(f[KnownWords.DEF_BI]), rest(f), tags.joinToString(" "))
            syncSoon(s.lang)
            return
        }
    }

    override fun flag(pkg: String, nid: Long, ord: Int, flag: Int) = Unit

    /** A card as kuma3 would give its note: Word, Sentence, the bilingual definition, then the other kept fields. */
    private fun note(c: Srs.Card) = AnkiCards.Note(c.note, names(c).zip(values(c)).toMap(), c.tags.split(' ').filter { it.isNotEmpty() }.toSet())

    private fun names(c: Srs.Card) = listOf("Word", "Sentence", KnownWords.DEF_BI) + JSONObject(c.fields).keys().asSequence().toList()
    private fun values(c: Srs.Card) = JSONObject(c.fields).let { o -> listOf(c.word, c.sentence, c.meaning) + o.keys().asSequence().map { o.getString(it) }.toList() }

    /** The fields that aren't Word, Sentence or the bilingual definition, without media; empty ones left out. */
    private fun rest(f: Map<String, String>) = JSONObject().apply {
        for ((k, v) in f) if (k !in setOf("Word", "Sentence", KnownWords.DEF_BI)) text(v).takeIf { it.isNotEmpty() }?.let { put(k, it) }
    }.toString()

    companion object {
        /** The `pkg` of kumapie's own store. */
        const val PKG = "kumapie"
        private val MEDIA = Regex("""<img[^>]*>|\[(?:sound|audio):[^\]]*\]""", RegexOption.IGNORE_CASE)
        /** "Mined in kumapie: <show> · <episode>, scene N." as Miner writes it (and tools\srs\import_kuma3.py reads it). */
        private val CONTEXT = Regex("""(?:Mined|Rated) in kumapie: (.*?) · (.*), scene (\d+)\.""")

        private fun text(v: String?) = MEDIA.replace(v.orEmpty(), "").trim()
        private fun String.unescape() = replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

        /** Pedro's note types' fields (Miner checks them; media fields stay empty here). */
        private fun fields(lang: Lang) = if (lang == Lang.JAPANESE)
            listOf("Sentence", "Sentence Audio", "Word", "Word Audio", KnownWords.DEF_BI, KnownWords.DEF_MONO, "Definition Audio", "am-study-morphs", "Image", "Notes", "Context")
        else listOf("Sentence", "Video", "Word", "Word Audio", KnownWords.DEF_BI, KnownWords.DEF_MONO, "Definition Audio", "am-study-morphs", "Notes", "Context")
    }
}
