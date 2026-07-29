package xa.refile.core.matcher

import org.junit.Test
import xa.refile.core.model.MediaType
import xa.refile.core.parser.FilenameParser

/**
 * 诊断测试：打印失败用例的实际 parsed 值 + 决策，用于校准 MatchEngineE2ETest 断言。
 * 临时文件，调试完成后删除。
 */
class ParserDiagnosticTest {

    private val parser = FilenameParser()
    private val engine = MatchEngine()

    private fun movie(id: Int, name: String, year: Int) =
        MatchCandidate(tmdbId = id, name = name, year = year, mediaType = MediaType.MOVIE)

    private fun episode(id: Int, name: String, year: Int) =
        MatchCandidate(tmdbId = id, name = name, year = year, mediaType = MediaType.EPISODE)

    @Test
    fun `dump parsed values for failing samples`() {
        data class Case(val fn: String, val cands: List<MatchCandidate>)
        val cases = listOf(
            Case("Interstellar.2014.IMAX.2160p.UHD.BluRay.x265.HDR.10bit.Atmos.TrueHD.7.1-FraMeSToR.mkv", listOf(movie(157336, "Interstellar", 2014))),
            Case("Fight.Club.1999.1080p.BluRay.x265.10bit.HDR-LAMA.mkv", listOf(movie(550, "Fight Club", 1999))),
            Case("Spirited.Away.2001.1080p.BluRay.x265.10bit.HDR-LAMA.mkv", listOf(movie(129, "Spirited Away", 2001))),
            Case("Spider-Man.Into.the.Spider-Verse.2018.1080p.BluRay.x264-SPARKS.mkv", listOf(movie(324857, "Spider-Man: Into the Spider-Verse", 2018))),
            Case("Game.of.Thrones.S01E01.Winter.Is.Coming.1080p.BluRay.x264-ROVERS.mkv", listOf(episode(1399, "Game of Thrones", 2011))),
            Case("Stranger.Things.S01.COMPLETE.1080p.NF.WEB-DL.DDP5.1.x264-NTb.mkv", listOf(episode(66732, "Stranger Things", 2016))),
            Case("One.Piece.Episode.1000.1080p.WEB-DL.x264.mkv", listOf(episode(37854, "One Piece", 1999))),
            Case("One.Piece.S21E1000.1080p.WEB-DL.x264.mkv", listOf(episode(37854, "One Piece", 1999))),
            Case("Spider-Man.2.2004.1080p.BluRay.x264-REVEiLLE.mkv", listOf(movie(558, "Spider-Man 2", 2004))),
            Case("Toy.Story.4.2019.1080p.BluRay.x264-GECKOS.mkv", listOf(movie(301528, "Toy Story 4", 2019))),
            Case("Blade.Runner.2049.2017.2160p.UHD.BluRay.HDR.x265.10bit.Atmos.TrueHD.7.1-FraMeSToR.mkv", listOf(movie(335984, "Blade Runner 2049", 2017))),
            Case("Spider-Man.2002.1080p.BluRay.x264-BS.mkv", listOf(movie(557, "Spider-Man", 2002), movie(315635, "Spider-Man: Homecoming", 2017))),
            Case("Westworld.S01E01.1080p.HBO.WEB-DL.DDP5.1.x264-NTb.mkv", listOf(episode(0, "Westworld", 2016), movie(0, "Westworld", 1973))),
            Case("攻殻機動隊.1995.1080p.BluRay.x265.FLAC.mkv", listOf(movie(126, "Ghost in the Shell", 1995), movie(0, "Ghost in the Shell", 2017))),
            Case("The.Matrix.1999.1080p.BluRay.x264-SPARKS.en.srt", listOf(movie(603, "The Matrix", 1999))),
            Case("The.Dark.Knight.Trilogy.2005-2012.1080p.BluRay.x265.10bit.HDR-CMCT.mkv", listOf(movie(272, "Batman Begins", 2005), movie(155, "The Dark Knight", 2008), movie(49026, "The Dark Knight Rises", 2012))),
        )
        val sb = StringBuilder()
        cases.forEach { (fn, cands) ->
            val p = parser.parse(fn)
            val d = engine.match(p, cands)
            val dStr = when (d) {
                is MatchDecision.Auto -> "Auto(score=${d.best.score}, id=${d.best.candidate.tmdbId})"
                is MatchDecision.NeedsConfirm -> "NeedsConfirm(top=${d.candidates.firstOrNull()?.score}, ids=${d.candidates.take(3).map { it.candidate.tmdbId }})"
                MatchDecision.NoMatch -> "NoMatch"
            }
            sb.append("DIAG2|$fn|title=[${p.title}]|year=${p.year}|s=${p.season}|eps=${p.episodes}|res=${p.resolution}|edition=${p.edition}|dec=$dStr\n")
        }
        throw AssertionError(sb.toString())
    }
}
