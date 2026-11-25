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

    private fun sig(key: String, body: ByteArray) = DeviceSync.sign(key, "/setup\n".toByteArray() + body)

    private fun pubOf(k: java.security.KeyPair) = java.util.Base64.getEncoder().encodeToString(k.public.encoded)

    private val other = "50".repeat(32)
    private val none = "{}".toByteArray()

    private fun paired(pub: String, now: Long = 1_000L) = hub().also { SetupBundle.grant(it, "tab", pub, now) }

    private fun fresh() { DeviceSync.asking.value = emptyList(); DeviceSync.conflicts.value.forEach { DeviceSync.dismissConflict(it) } }

    @Test fun sealedBundleOpensWithTheSameSecretAndNotWithAnother() {
        val client = SetupBundle.ephemeral()
        val prefs = paired(pubOf(client))
        val a = SetupBundle.serve("", secret, "tab", prefs, none, sig(secret, none), 2_000L)
        assertEquals(200, a.status)
        assertTrue(DeviceSync.verify(secret, "/setup\n".toByteArray() + a.body, a.sig))
        assertFalse(DeviceSync.verify(secret, a.body, a.sig))
        assertFalse("TOKEN" in String(a.body))
        assertEquals(SetupBundle.build(hub()).toString(), String(SetupBundle.open(secret, client, a.body)))
        assertFalse(runCatching { SetupBundle.open(other, client, a.body) }.isSuccess)
    }

    @Test fun aSniffedSecretAndAnotherKeyOpenNothing() {
        val real = SetupBundle.ephemeral()
        val thief = SetupBundle.ephemeral()
        val body = JSONObject().put("pub", pubOf(thief)).toString().toByteArray()
        val a = SetupBundle.serve("", secret, "tab", paired(pubOf(real)), body, sig(secret, body), 2_000L)
        assertEquals(200, a.status)
        assertFalse(runCatching { SetupBundle.open(secret, thief, a.body) }.isSuccess)
        assertTrue(runCatching { SetupBundle.open(secret, real, a.body) }.isSuccess)
        assertEquals(403, SetupBundle.serve("", secret, "tab", hub(), none, sig(secret, none), 2_000L).status)
    }

    @Test fun onlyAPairedSignedPeerIsAnswered() {
        val prefs = paired("AAAA")
        assertEquals(403, SetupBundle.serve("", secret, "tab", prefs, none, null, 2_000L).status)
        assertEquals(403, SetupBundle.serve("", secret, "tab", prefs, none, sig(other, none), 2_000L).status)
        assertEquals(403, SetupBundle.serve("", null, "tab", prefs, none, sig(secret, none), 2_000L).status)
        assertEquals(403, SetupBundle.serve("", secret, "tab", prefs, none, DeviceSync.sign(secret, none), 2_000L).status)
        assertEquals(409, SetupBundle.serve("http://pc:8769", secret, "tab", prefs, none, sig(secret, none), 2_000L).status)
        val bad = SetupBundle.serve("", secret, "tab", prefs, none, sig(secret, none), 2_000L)
        assertEquals(422, bad.status)
        assertEquals("Could not copy the setup.", String(bad.body))
    }

    @Test fun setupIsServedOnceAndOnlyWithinTenMinutesOfAllow() {
        val pub = pubOf(SetupBundle.ephemeral())
        val prefs = paired(pub)
        assertEquals(200, SetupBundle.serve("", secret, "tab", prefs, none, sig(secret, none), 1_000L + 600_000L).status)
        assertEquals(403, SetupBundle.serve("", secret, "tab", prefs, none, sig(secret, none), 1_500L).status)
        val late = paired(pub)
        assertEquals(403, SetupBundle.serve("", secret, "tab", late, none, sig(secret, none), 1_000L + 600_001L).status)
        assertEquals(403, SetupBundle.serve("", secret, "tab", late, none, sig(secret, none), 1_500L).status)
    }

    @Test fun anOldPairWithoutAPubStillPairsButGetsNoSetup() {
        fresh()
        val prefs = hub()
        assertEquals(202, DeviceSync.requested(prefs, "tab", secret, "", "me").status)
        val a = DeviceSync.asking.value.single()
        assertEquals(DeviceSync.code(secret), a.code)
        DeviceSync.allow(prefs, a, 1_000L)
        assertEquals(200, DeviceSync.requested(prefs, "tab", secret, "", "me").status)
        assertEquals(403, SetupBundle.serve("", secret, "tab", prefs, none, sig(secret, none), 2_000L).status)
    }

    @Test fun theCodeDependsOnThePub() {
        assertTrue(DeviceSync.code(secret, "A") != DeviceSync.code(secret, "B"))
        assertTrue(DeviceSync.code(secret, "A") != DeviceSync.code(secret))
        assertEquals(DeviceSync.code(secret, "A"), DeviceSync.code(secret, "A"))
    }

    @Test fun aSwappedPendingRequestDropsBothAndMarksAConflict() {
        fresh()
        val prefs = Prefs()
        assertEquals(202, DeviceSync.requested(prefs, "tab", secret, "A", "me").status)
        assertEquals(202, DeviceSync.requested(prefs, "tab", secret, "A", "me").status)
        assertEquals(1, DeviceSync.asking.value.size)
        assertEquals(409, DeviceSync.requested(prefs, "tab", secret, "B", "me").status)
        assertTrue(DeviceSync.asking.value.isEmpty())
        assertEquals(setOf("tab"), DeviceSync.conflicts.value)
        assertNull(prefs.getString("sync_peers", null))
        DeviceSync.requested(prefs, "x", secret, "A", "me")
        assertEquals(409, DeviceSync.requested(prefs, "x", other, "A", "me").status)
        fresh()
    }

    @Test fun theClientsOwnPollKeepsItsRequestAndAllowAnswers200() {
        fresh()
        val prefs = Prefs()
        val pub = SetupBundle.begin()
        assertEquals(pub, SetupBundle.pub())
        assertEquals(202, DeviceSync.requested(prefs, "tab", secret, pub, "me").status)
        assertEquals(202, DeviceSync.requested(prefs, "tab", secret, SetupBundle.pub().orEmpty(), "me").status)
        assertTrue(DeviceSync.conflicts.value.isEmpty())
        DeviceSync.allow(prefs, DeviceSync.asking.value.single(), 1_000L)
        assertEquals(200, DeviceSync.requested(prefs, "tab", secret, pub, "me").status)
    }

    @Test fun aConflictStaysUntilDismissedOrTenMinutes() {
        fresh()
        val prefs = Prefs()
        DeviceSync.requested(prefs, "tab", secret, "A", "me", 1_000L)
        assertEquals(409, DeviceSync.requested(prefs, "tab", other, "B", "me", 2_000L).status)
        assertEquals(409, DeviceSync.requested(prefs, "tab", other, "B", "me", 3_000L).status)
        assertTrue(DeviceSync.asking.value.isEmpty())
        DeviceSync.dismissConflict("tab")
        assertEquals(202, DeviceSync.requested(prefs, "tab", secret, "A", "me", 4_000L).status)
        fresh()
        DeviceSync.requested(prefs, "tab", secret, "A", "me", 1_000L)
        DeviceSync.requested(prefs, "tab", other, "B", "me", 2_000L)
        assertEquals(409, DeviceSync.requested(prefs, "tab", secret, "A", "me", 2_000L + 600_000L).status)
        assertEquals(202, DeviceSync.requested(prefs, "tab", secret, "A", "me", 2_001L + 600_000L).status)
        fresh()
    }

    @Test fun theCodeMatchesTheHubsGenerationOldHubsShowTheOldCode() {
        val pub = "A"
        val new = JSONObject(String(DeviceSync.requested(Prefs(), "tab", secret, pub, "me").also { fresh() }.body))
        assertEquals(DeviceSync.code(secret, pub), DeviceSync.shown(secret, pub, new))
        val old = JSONObject().put("device", "hub")
        assertEquals(DeviceSync.code(secret), DeviceSync.shown(secret, pub, old))
    }

    @Test fun aPollTellsAConflictFromWaiting() {
        assertEquals(DeviceSync.Poll.ALLOWED, DeviceSync.polled(DeviceSync.Answer(200, ByteArray(0))))
        assertEquals(DeviceSync.Poll.WAITING, DeviceSync.polled(DeviceSync.Answer(202, ByteArray(0))))
        assertEquals(DeviceSync.Poll.WAITING, DeviceSync.polled(DeviceSync.Answer(409, "This device syncs with its PC.".toByteArray())))
        fresh()
        val prefs = Prefs()
        DeviceSync.requested(prefs, "tab", secret, "A", "me")
        val swap = DeviceSync.requested(prefs, "tab", other, "B", "me")
        assertEquals(DeviceSync.Poll.CONFLICT, DeviceSync.polled(swap))
        fresh()
        SetupBundle.begin()
        SetupBundle.clearKey()
        assertNull(SetupBundle.pub())
        try {
            io.github.pedrubik2000.kumapie.i18n.Tr.use("es")
            assertEquals("Dos dispositivos pidieron como tab. Empareja otra vez.", DeviceSync.conflictLine("tab"))
        } finally { io.github.pedrubik2000.kumapie.i18n.Tr.use("en") }
    }

    @Test fun theHubRoutesSetupAndKeepsUnknownPaths404() {
        val prefs = paired(pubOf(SetupBundle.ephemeral()), System.currentTimeMillis()).apply { map["sync_peers"] = """{"tab":"$secret"}""" }
        val c: Context = ctx(prefs)
        assertEquals(404, DeviceSync.answer(c, "/nope", "tab", null, ByteArray(0)).status)
        assertEquals(403, DeviceSync.answer(c, "/setup", "tab", null, none).status)
        assertEquals(403, DeviceSync.answer(c, "/setup", "stranger", sig(secret, none), none).status)
        assertEquals(200, DeviceSync.answer(c, "/setup", "tab", sig(secret, none), none).status)
        assertEquals(403, DeviceSync.answer(c, "/setup", "tab", sig(secret, none), none).status)
    }

    @Test fun aPartWithAnUnknownTypeIsNotCopied() {
        val odd = SetupBundle.Got.Ok(mapOf("keys" to JSONObject().put("rd_token", 5), "languages" to JSONObject().put("speaks", "es")), emptyMap())
        val client = Prefs()
        SetupBundle.write(client, odd, setOf("keys", "languages"), anki = true)
        assertFalse(client.contains("rd_token"))
        assertEquals(setOf("languages"), client.getStringSet("onb_copied", null))
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
