package io.github.pedrubik2000.kumapie.lang

import io.github.pedrubik2000.kumapie.data.Api
import io.github.pedrubik2000.kumapie.data.Episode
import io.github.pedrubik2000.kumapie.data.Lang
import io.github.pedrubik2000.kumapie.data.Show
import io.github.pedrubik2000.kumapie.data.streamQuery
import org.junit.Assert.assertEquals
import org.junit.Test

class LangDefaultTest {
    @Test fun anUnknownOrMissingLanguageIsJapanese() {
        assertEquals(Lang.JAPANESE, Lang.of(null))
        assertEquals(Lang.JAPANESE, Lang.of(""))
        assertEquals(Lang.JAPANESE, Lang.of("xx"))
        assertEquals(Lang.GERMAN, Lang.of("de"))
        assertEquals(Lang.ENGLISH, Lang.of("en"))
    }

    @Test fun everyWayAJapaneseEpisodeIsMadeCarriesItsLanguage() {
        val built = EpisodeBuilder.build("ep", "Show", "1", 30.0, "", emptyList(), emptyList(), emptyList())
        assertEquals("ja", built.getString("lang"))
        val stream = Show("s", "Show", "stream", "", emptyList(), "ja")
        val episode = Episode("e", "1", 1, 1, 0.0, "", 0, 0, 0, null)
        assertEquals("ja", streamQuery(stream, episode)["lang"])
    }

    @Test fun theServersShowsSayTheirLanguage() {
        val json = """{"shows":[{"id":"a","title":"A","poster":"/p","lang":"ja","episodes":[]}]}"""
        assertEquals("ja", Api("http://127.0.0.1:1", "pedro").parseShows(json).single().lang)
    }
}
