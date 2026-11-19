package io.github.pedrubik2000.kumapie.mobile.offline

import io.github.pedrubik2000.kumapie.data.Addons
import io.github.pedrubik2000.kumapie.data.Jimaku
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Kotlin Jimaku client against the cases of the phone's `jimaku.py` self-check, and an addon's address as pasted. */
class JimakuTest {
    private val files = listOf(
        "[Erai-raws] Sousou no Frieren - 07 [1080p][Multiple Subtitle].ja.ass",
        "Sousou.no.Frieren.S01E07.WEBRip.Netflix.ja[cc].srt",
        "Sousou.no.Frieren.S01E08.WEBRip.Netflix.ja[cc].srt",
        "Frieren BD 1-28.zip",
        "Frieren.S01E07.7z").map { it to "" }

    @Test fun theEpisodesOwnFileOfTheSameReleaseFirst() = assertEquals(listOf(files[1].first, files[0].first, "Frieren BD 1-28.zip"),
        Jimaku.rank(files, 7, "Sousou.no.Frieren.S01E07.1080p.NF.WEB-DL.mkv").map { it.first })

    @Test fun episodeNumbers() = assertEquals(listOf(1 to 7, null to 7, null to 12, null to null),
        listOf("Show.S01E07.mkv", "[Group] Show - 07 [1080p].ass", "Show EP12.srt", "Show.srt").map(Jimaku::numbers))

    @Test fun aBilingualAssKeepsWhatIsSaidInJapanese() {
        val ass = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.00,MainCN,,0,0,0,,这个时间点\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.00,MainJP,,0,0,0,,このタイミングで\n" +
            "Dialogue: 0,0:00:07.00,0:00:08.00,Signs,,0,0,0,,{\\pos(10,10)}看板\n" +
            "Dialogue: 0,0:00:05.00,0:00:06.50,Default,,0,0,0,,{\\i1}大丈夫\\Nだよ（笑い）\n"
        assertEquals(listOf(Jimaku.Line(1.0, 2.0, "このタイミングで"), Jimaku.Line(5.0, 6.5, "大丈夫 だよ")), Jimaku.cues("x.ass", ass.toByteArray()))
    }

    @Test fun srtWithoutMusicAndReadings() {
        val srt = "1\n00:00:01,000 --> 00:00:02,500\n後藤(ごとう)さん\n\n2\n00:00:03,000 --> 00:00:04,000\n♪～\n\n3\n00:01:03,250 --> 00:01:04,000\n<i>はい</i>\nそうです\n"
        assertEquals(listOf(Jimaku.Line(1.0, 2.5, "後藤さん"), Jimaku.Line(63.25, 64.0, "はい そうです")), Jimaku.cues("x.srt", srt.toByteArray()))
    }

    /** A stream's own Netflix Japanese track (Dan Da Dan 01 [Erai-raws] NF, bedroom TV 2026-11-17): what LiveLines shows. */
    @Test fun netflixCaptionsKeepOnlyWhatIsSaid() = assertEquals(listOf("", "ちょっ てめっ", "絶滅したわ そんな男", "ああ ヤダヤダ", ""),
        listOf("（男子生徒の話し声）", "（彼氏）ちょっ てめっ", "（ミーコ･ムーコ）絶滅したわ そんな男", "（モモ）ああ ヤダヤダ", "♪～").map(Jimaku::spoken))

    @Test fun linesSavedTwiceOverAreTurnedBack() = assertEquals(
        "♪ Defects… ♪\nCafé “ok”\n日本語 â stays\nplain",
        Jimaku.unmangle("â™ª Defectsâ€¦ â™ª\nCafÃ© â€œokâ€\u009D\n日本語 â stays\nplain"))

    @Test fun anAddonsAddressAsPasted() = assertEquals(List(4) { "https://a.example/cfg=1" }, listOf(
        "https://a.example/cfg=1/manifest.json", "stremio://a.example/cfg=1/manifest.json", " a.example/cfg=1/ ", "https://a.example/cfg=1").map(Addons::base))
}
