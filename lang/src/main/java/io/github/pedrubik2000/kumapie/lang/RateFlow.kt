package io.github.pedrubik2000.kumapie.lang

/**
 * A word card's rating, the TV's and the phone's (core plan step 5): [rate] answers the form's own cards with where it was
 * watched ([Srs.Watch]) and keeps the line it was rated on; [undo] takes it back and reads the due queue again (the card
 * is due again); [sceneLeft] reads the queue once after a scene rated in; [leave] (the player closing, the stream
 * stopped) moves every rated card to its line ([Miner.recontext], kumapie_plan.md 37b).
 */
class RateFlow(
    private val answer: suspend (form: String, ease: Int, ms: Long, watch: Srs.Watch?) -> KnownWords.Rated?,
    private val takeBack: suspend (KnownWords.Rated) -> Boolean,
    private val refreshDue: suspend () -> Unit,
    private val recontext: suspend (notes: List<Long>, at: Miner.Request) -> Unit,
    private val elsewhere: (form: String) -> Boolean = { false },
    private val mineHere: suspend (at: Miner.Request) -> Unit = {},
) {
    constructor(known: KnownWords, miner: Miner) :
        this(known::rate, known::undo, { known.refreshDue(force = true) }, { notes, at -> miner.recontext(notes, at) },
            { known.cardIndex.cards(it).isEmpty() && WordSync.state(known.lang.code, it.lowercase()) != null },
            { at ->
                WordSync.insist = true
                try { miner.mine(at) {} } finally { WordSync.insist = false }
                DeviceFiles(known.context, emptyList()).share()
                known.addNote(miner.lastNote)
            })

    private val moves = ArrayList<Pair<KnownWords.Rated, Miner.Request>>()

    suspend fun rate(form: String, ease: Int, ms: Long, watch: Srs.Watch?, at: Miner.Request?): KnownWords.Rated? {
        val done = answer(form, ease, ms, watch)
            ?: at?.takeIf { elsewhere(form) }?.let { mineHere(it); answer(form, ease, ms, watch) } ?: return null
        if (at != null) synchronized(moves) { moves += done to at }
        return done
    }

    suspend fun undo(r: KnownWords.Rated): Boolean {
        if (!takeBack(r)) return false
        synchronized(moves) { moves.removeAll { it.first === r } }
        refreshDue()
        return true
    }

    suspend fun sceneLeft() = refreshDue()

    suspend fun leave() {
        val all = synchronized(moves) { moves.toList().also { moves.clear() } }
        for ((r, at) in all) runCatching { recontext(r.notes, at) }.onFailure { System.err.println("kumapie recontext: $it") }
    }
}
