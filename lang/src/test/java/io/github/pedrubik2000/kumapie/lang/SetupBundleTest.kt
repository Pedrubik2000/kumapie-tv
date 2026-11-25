package io.github.pedrubik2000.kumapie.lang

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupBundleTest {
    private val secret = "4f".repeat(32)

    private class Prefs(start: Map<String, Any?> = emptyMap()) : SharedPreferences {
        val map = start.toMutableMap()
        override fun getAll(): Map<String, *> = map.toMap()
        override fun getString(k: String, d: String?) = map[k] as String? ?: d
        @Suppress("UNCHECKED_CAST") override fun getStringSet(k: String, d: Set<String>?) = map[k] as Set<String>? ?: d
        override fun getInt(k: String, d: Int) = map[k] as Int? ?: d
        override fun getLong(k: String, d: Long) = map[k] as Long? ?: d
        override fun getFloat(k: String, d: Float) = map[k] as Float? ?: d
        override fun getBoolean(k: String, d: Boolean) = map[k] as Boolean? ?: d
        override fun contains(k: String) = k in map
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            fun put(k: String, v: Any?) = apply { pending[k] = v }
            override fun putString(k: String, v: String?) = put(k, v)
            override fun putStringSet(k: String, v: Set<String>?) = put(k, v)
            override fun putInt(k: String, v: Int) = put(k, v)
            override fun putLong(k: String, v: Long) = put(k, v)
            override fun putFloat(k: String, v: Float) = put(k, v)
            override fun putBoolean(k: String, v: Boolean) = put(k, v)
            override fun remove(k: String) = put(k, null)
            override fun clear() = this
            override fun commit(): Boolean { pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }; return true }
            override fun apply() { commit() }
        }
    }

    private fun hub() = Prefs(mapOf(
        "anilist_token" to "TOKEN", "anilist_user" to "pedro", "anilist_format" to "POINT_10", "anilist_viewer" to "pedro",
        "rd_token" to "RD", "jimaku_key" to "JK", "addons" to "https://x/realdebrid=RD/manifest.json",
        "plain_words" to true, "anki_source" to "kuma3", "anki_decks_ja" to "Core", "jiten_choice_ja" to "1000|a,b",
        "ui_lang" to "es", "speaks" to "es", "learning" to setOf("ja", "de"),
        "alone_saved" to "x", "alone_pos_1" to 5.toString(), "sync_answers" to true, "owner" to "pedro", "server" to "",
    ))

    private fun ctx(prefs: SharedPreferences) = object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private fun got(p: Prefs) = SetupBundle.read(SetupBundle.build(p))

    @Test fun theBundleHoldsOnlyTheFourPartsAndNoAloneKeyOrRole() {
        val b = SetupBundle.build(hub())
        assertEquals(setOf("anilist", "keys", "colouring", "languages"), b.getJSONObject("parts").keys().asSequence().toSet())
        val keys = b.getJSONObject("parts").let { p -> p.keys().asSequence().flatMap { n -> p.getJSONObject(n).keys().asSequence() }.toList() }
        assertTrue(keys.none { it.startsWith("alone_") || it == "sync_answers" || it == "owner" || it == "server" })
        assertEquals("pedro", b.getJSONObject("summary").getString("anilist"))
        assertEquals("Jimaku, Real-Debrid, addons", b.getJSONObject("summary").getString("keys"))
    }

    @Test fun aLoggedOutAniListIsLeftOut() {
        val p = hub().apply { map["anilist_logged_out"] = true }
        assertFalse(SetupBundle.build(p).getJSONObject("parts").has("anilist"))
    }

    @Test fun sealedBundleOpensWithTheSameSecretAndNotWithAnother() {
        val client = SetupBundle.ephemeral()
        val body = JSONObject().put("pub", java.util.Base64.getEncoder().encodeToString(client.public.encoded)).toString().toByteArray()
        val a = SetupBundle.serve("", secret, hub(), body, DeviceSync.sign(secret, body))
        assertEquals(200, a.status)
        assertTrue(DeviceSync.verify(secret, a.body, a.sig))
        assertFalse("TOKEN" in String(a.body))
        assertEquals(SetupBundle.build(hub()).toString(), String(SetupBundle.open(secret, client, a.body)))
        assertFalse(runCatching { SetupBundle.open("50".repeat(32), client, a.body) }.isSuccess)
        assertFalse(DeviceSync.verify("50".repeat(32), a.body, a.sig))
    }

    @Test fun onlyAPairedSignedPeerIsAnswered() {
        val body = JSONObject().put("pub", "AAAA").toString().toByteArray()
        assertEquals(403, SetupBundle.serve("", secret, hub(), body, null).status)
        assertEquals(403, SetupBundle.serve("", secret, hub(), body, DeviceSync.sign("50".repeat(32), body)).status)
        assertEquals(403, SetupBundle.serve("", null, hub(), body, DeviceSync.sign(secret, body)).status)
        assertEquals(409, SetupBundle.serve("http://pc:8769", secret, hub(), body, DeviceSync.sign(secret, body)).status)
        assertEquals(422, SetupBundle.serve("", secret, hub(), body, DeviceSync.sign(secret, body)).status)
        assertEquals("Could not copy the setup.", String(SetupBundle.serve("", secret, hub(), body, DeviceSync.sign(secret, body)).body))
    }

    @Test fun theHubRoutesSetupAndKeepsUnknownPaths404() {
        val prefs = hub().apply { map["sync_peers"] = """{"tab":"$secret"}""" }
        val c: Context = ctx(prefs)
        assertEquals(404, DeviceSync.answer(c, "/nope", "tab", null, ByteArray(0)).status)
        val body = "{}".toByteArray()
        assertEquals(403, DeviceSync.answer(c, "/setup", "tab", null, body).status)
        assertEquals(403, DeviceSync.answer(c, "/setup", "stranger", DeviceSync.sign(secret, body), body).status)
        assertEquals(422, DeviceSync.answer(c, "/setup", "tab", DeviceSync.sign(secret, body), body).status)
    }

    @Test fun ticksApplyOnlyTheirPartsInOneCommitAndRememberThem() {
        val client = Prefs(mapOf("anilist_viewer" to "pedro", "ui_lang" to "en"))
        SetupBundle.write(client, got(hub()), setOf("keys", "languages"), anki = true)
        assertEquals("RD", client.getString("rd_token", null))
        assertEquals("es", client.getString("ui_lang", null))
        assertEquals(setOf("ja", "de"), client.getStringSet("learning", null))
        assertNull(client.getString("anilist_token", null))
        assertFalse(client.contains("plain_words"))
        assertEquals(setOf("keys", "languages"), client.getStringSet("onb_copied", null))
        assertFalse(client.contains("alone_saved"))
    }

    @Test fun ankiPrefsNeedTheAppAndLeaveTheColouringStepOtherwise() {
        val without = Prefs()
        SetupBundle.write(without, got(hub()), setOf("colouring"), anki = false)
        assertEquals(true, without.getBoolean("plain_words", false))
        assertEquals("1000|a,b", without.getString("jiten_choice_ja", null))
        assertFalse(without.contains("anki_source") || without.contains("anki_decks_ja"))
        assertEquals(emptySet<String>(), without.getStringSet("onb_copied", null))
        val with = Prefs()
        SetupBundle.write(with, got(hub()), setOf("colouring"), anki = true)
        assertEquals("kuma3", with.getString("anki_source", null))
        assertEquals(setOf("colouring"), with.getStringSet("onb_copied", null))
    }

    @Test fun aHubCannotWriteKeysOutsideTheParts() {
        val sneaky = SetupBundle.Got.Ok(mapOf("keys" to JSONObject().put("server", "http://evil").put("rd_token", "RD")), emptyMap())
        val client = Prefs()
        SetupBundle.write(client, sneaky, setOf("keys"), anki = true)
        assertFalse(client.contains("server"))
        assertEquals("RD", client.getString("rd_token", null))
    }
}
