package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import android.content.SharedPreferences
import io.github.pedrubik2000.kumapie.data.AniListSync
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Settings
import io.github.pedrubik2000.kumapie.i18n.Tr
import io.github.pedrubik2000.kumapie.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SetupBundle {
    const val COPIED = "onb_copied"
    const val SYNC = "sync"
    const val SETUP = "sync_setup"
    private const val FAILED = "Could not copy the setup."
    private const val GONE = "Could not copy the setup: pair the devices again."
    private const val WINDOW_MS = 10 * 60_000L
    @Volatile private var key: KeyPair? = null
    private val ANKI = listOf("anki_decks_", "anki_word_", "anki_reading_")
    private val rules: Map<String, (String) -> Boolean> = mapOf(
        "anilist" to { k -> k in setOf("anilist_token", "anilist_user", "anilist_format", "anilist_viewer") },
        "keys" to { k -> k in setOf("rd_token", "jimaku_key", "addons") },
        "colouring" to { k -> k == "plain_words" || isAnki(k) || k.startsWith("jiten_choice_") },
        "languages" to { k -> k in setOf("ui_lang", "speaks", "learning") },
    )

    sealed interface Got {
        class Ok(val parts: Map<String, JSONObject>, val summary: Map<String, String>) : Got
        data object Old : Got
        data object Pc : Got
        class Failed(val message: String) : Got
    }

    private fun isAnki(k: String) = k == "anki_source" || ANKI.any { k.startsWith(it) }

    fun build(prefs: SharedPreferences): JSONObject {
        val all = prefs.all
        val parts = JSONObject()
        rules.forEach { (name, wants) ->
            val part = JSONObject()
            all.filter { (k, v) -> wants(k) && v != null && !(v is String && v.isBlank()) }.forEach { (k, v) -> part.put(k, if (v is Set<*>) JSONArray(v.toList()) else v) }
            val signedOut = name == "anilist" && (all["anilist_logged_out"] == true || !part.has("anilist_token"))
            if (part.length() > 0 && !signedOut) parts.put(name, part)
        }
        val summary = JSONObject().apply { parts.keys().forEach { put(it, line(it, parts.getJSONObject(it))) } }
        return JSONObject().put("parts", parts).put("summary", summary)
    }

    private fun line(part: String, o: JSONObject): String = when (part) {
        "anilist" -> o.optString("anilist_user")
        "keys" -> listOf("jimaku_key" to "Jimaku", "rd_token" to "Real-Debrid", "addons" to "addons").filter { o.has(it.first) }.joinToString(", ") { it.second }
        "languages" -> listOf(o.optString("speaks"), o.optJSONArray("learning")?.let { a -> (0 until a.length()).joinToString(",") { a.getString(it) } }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")
        else -> listOfNotNull(o.optString("anki_source").takeIf { it.isNotEmpty() && it != "none" }, "Jiten".takeIf { o.keys().asSequence().any { k -> k.startsWith("jiten_choice_") } },
            "plain words".takeIf { o.optBoolean("plain_words") }).joinToString(", ")
    }

    internal fun ephemeral(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun derive(mine: PrivateKey, theirs: ByteArray, secret: String): SecretKeySpec {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(theirs))
        val shared = KeyAgreement.getInstance("ECDH").apply { init(mine); doPhase(pub, true) }.generateSecret()
        return SecretKeySpec(MessageDigest.getInstance("SHA-256").apply { update(shared); update(secret.toByteArray()) }.digest(), "AES")
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    private fun un64(s: String) = Base64.getDecoder().decode(s)

    fun seal(secret: String, clientPub: ByteArray, plain: ByteArray): ByteArray {
        val mine = ephemeral()
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val box = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, derive(mine.private, clientPub, secret), GCMParameterSpec(128, iv)) }.doFinal(plain)
        return JSONObject().put("pub", b64(mine.public.encoded)).put("iv", b64(iv)).put("box", b64(box)).toString().toByteArray()
    }

    fun open(secret: String, mine: KeyPair, sealed: ByteArray): ByteArray {
        val o = JSONObject(String(sealed))
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, derive(mine.private, un64(o.getString("pub")), secret), GCMParameterSpec(128, un64(o.getString("iv")))) }
            .doFinal(un64(o.getString("box")))
    }

    fun clearKey() { key = null }

    fun pub(): String? = key?.let { b64(it.public.encoded) }

    private fun signed(body: ByteArray) = "/setup\n".toByteArray() + body

    fun begin(): String = ephemeral().also { key = it }.let { b64(it.public.encoded) }

    private fun store(prefs: SharedPreferences) = runCatching { JSONObject(prefs.getString(SETUP, "{}").orEmpty()) }.getOrDefault(JSONObject())

    fun grant(prefs: SharedPreferences, device: String, pub: String, now: Long) {
        if (pub.isNotEmpty()) prefs.edit().putString(SETUP, store(prefs).put(device, JSONObject().put("pub", pub).put("at", now)).toString()).commit()
    }

    private fun take(prefs: SharedPreferences, device: String, now: Long): String? {
        val all = store(prefs)
        val entry = all.optJSONObject(device) ?: return null
        prefs.edit().putString(SETUP, all.apply { remove(device) }.toString()).commit()
        return entry.optString("pub").takeIf { it.isNotEmpty() && now - entry.optLong("at") in 0..WINDOW_MS }
    }

    fun serve(server: String, secret: String?, device: String?, prefs: SharedPreferences, body: ByteArray, sig: String?, now: Long): DeviceSync.Answer {
        DeviceSync.refusal(server, secret, signed(body), sig)?.let { return it }
        val pub = device?.let { take(prefs, it, now) } ?: return DeviceSync.Answer(403, GONE.toByteArray())
        return runCatching {
            val out = seal(requireNotNull(secret), un64(pub), build(prefs).toString().toByteArray())
            DeviceSync.Answer(200, out, DeviceSync.sign(secret, signed(out)))
        }.getOrElse { DeviceSync.Answer(422, FAILED.toByteArray()) }
    }

    suspend fun fetch(context: Context): Got = withContext(Dispatchers.IO) {
        val peer = DeviceSync.with(Settings(context))
        val mine = key
        if (peer == null || mine == null) return@withContext Got.Failed(tr(GONE))
        runCatching {
            val body = "{}".toByteArray()
            val a = DeviceSync.post("http://${peer.address}/setup", body, mapOf("X-Device" to Progress.of(context).device, "X-Sig" to DeviceSync.sign(peer.secret, signed(body))))
            when (a.status) {
                200 -> if (!DeviceSync.verify(peer.secret, signed(a.body), a.sig)) Got.Failed(tr(FAILED)) else read(JSONObject(String(open(peer.secret, mine, a.body)))).also { key = null }
                404 -> Got.Old
                409 -> Got.Pc
                else -> Got.Failed(tr(GONE))
            }
        }.onFailure { if (it is CancellationException) throw it }.getOrElse { Got.Failed(tr(FAILED)) }
    }

    internal fun read(o: JSONObject): Got.Ok {
        val parts = o.getJSONObject("parts")
        val summary = o.getJSONObject("summary")
        return Got.Ok(parts.keys().asSequence().associateWith { parts.getJSONObject(it) }, parts.keys().asSequence().associateWith { summary.optString(it) })
    }

    fun apply(context: Context, got: Got.Ok, ticked: Set<String>) {
        val settings = Settings(context)
        write(settings.prefs, got, ticked, AnkiCards(context).installed().isNotEmpty())
        Tr.use(settings.menuLanguage)
        Lang.speaker = settings.speaks
        if (SYNC in ticked) DeviceSync.start(context) else DeviceSync.unpair(context)
    }

    private fun valid(part: JSONObject, wants: (String) -> Boolean) = part.keys().asSequence().filter(wants).all { k ->
        part.get(k).let { v -> v is Boolean || v is String || (v is JSONArray && (0 until v.length()).all { v.get(it) is String }) }
    }

    internal fun write(prefs: SharedPreferences, got: Got.Ok, ticked: Set<String>, anki: Boolean) {
        val edit = prefs.edit()
        val copied = prefs.getStringSet(COPIED, emptySet()).orEmpty().toMutableSet()
        got.parts.filterKeys { it in ticked && it in rules }.forEach { (name, part) ->
            val wants = rules.getValue(name)
            if (!valid(part, wants)) return@forEach
            if (name == "anilist" && !part.optString("anilist_viewer").equals(prefs.getString("anilist_viewer", ""), ignoreCase = true)) AniListSync.forget(prefs, edit, save = false)
            if (name == "anilist") edit.putBoolean("anilist_logged_out", false)
            part.keys().asSequence().filter { wants(it) && (anki || !isAnki(it)) }.forEach { k -> edit.put(k, part.get(k)) }
            if (anki || name != "colouring" || part.optString("anki_source", "none") == "none") copied += name
        }
        edit.putStringSet(COPIED, copied).commit()
    }

    private fun SharedPreferences.Editor.put(k: String, v: Any?): SharedPreferences.Editor = when (v) {
        is Boolean -> putBoolean(k, v)
        is String -> putString(k, v)
        is JSONArray -> putStringSet(k, (0 until v.length()).map { v.getString(it) }.toSet())
        else -> this
    }
}
