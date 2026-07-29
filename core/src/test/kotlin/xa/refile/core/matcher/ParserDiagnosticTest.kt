package xa.refile.core.matcher

import org.junit.Test
import xa.refile.core.parser.FilenameParser

/**
 * 诊断测试：打印失败用例的实际 parsed 值，用于校准 MatchEngineE2ETest 断言。
 * 临时文件，调试完成后删除。
 */
class ParserDiagnosticTest {

    private val parser = FilenameParser()

    @Test
    fun `dump parsed values for failing samples`() {
        val samples = listOf(
            "Interstellar.2014.IMAX.2160p.UHD.BluRay.x265.HDR.10bit.Atmos.TrueHD.7.1-FraMeSToR.mkv",
            "Fight.Club.1999.1080p.BluRay.x265.10bit.HDR-LAMA.mkv",
            "Forrest.Gump.1994.1080p.BluRay.x265.10bit.HDR-LAMA.mkv",
            "Pulp.Fiction.1994.1080p.BluRay.x265.10bit.HDR-LAMA.mkv",
            "The.Shawshank.Redemption.1994.1080p.BluRay.x265.10bit.HDR-LAMA.mkv",
            "Titanic.1997.2160p.UHD.BluRay.x265.HDR.10bit.Atmos.TrueHD.7.1-HONE.mkv",
            "The.Godfather.1972.2160p.UHD.BluRay.DV.HDR.x265.10bit.Atmos.TrueHD.7.1-CHD.mkv",
            "Mad.Max.Fury.Road.2015.2160p.UHD.BluRay.HDR.x265.10bit.Atmos.TrueHD.7.1-PTer.mkv",
            "Avengers.Infinity.War.2018.2160p.UHD.BluRay.HDR.x265.10bit-PTer.mkv",
            "Spider-Man.Homecoming.2017.1080p.BluRay.x264-SPARKS.mkv",
            "Spirited.Away.2001.1080p.BluRay.x265.10bit.HDR-LAMA.mkv",
            "Your.Name.2016.1080p.BluRay.x265.10bit.HDR-LAMA.mkv",
            "Spider-Man.Into.the.Spider-Verse.2018.1080p.BluRay.x264-SPARKS.mkv",
            "Game.of.Thrones.S01E01.Winter.Is.Coming.1080p.BluRay.x264-ROVERS.mkv",
            "Breaking.Bad.S01E01.Pilot.1080p.BluRay.x264-ROVERS.mkv",
            "Stranger.Things.S01.COMPLETE.1080p.NF.WEB-DL.DDP5.1.x264-NTb.mkv",
            "One.Piece.Episode.1000.1080p.WEB-DL.x264.mkv",
            "One.Piece.S21E1000.1080p.WEB-DL.x264.mkv",
            "Spider-Man.2.2004.1080p.BluRay.x264-REVEiLLE.mkv",
            "Toy.Story.4.2019.1080p.BluRay.x264-GECKOS.mkv",
            "Blade.Runner.2049.2017.2160p.UHD.BluRay.HDR.x265.10bit.Atmos.TrueHD.7.1-FraMeSToR.mkv",
            "Spider-Man.2002.1080p.BluRay.x264-BS.mkv",
            "Westworld.S01E01.1080p.HBO.WEB-DL.DDP5.1.x264-NTb.mkv",
            "攻殻機動隊.1995.1080p.BluRay.x265.FLAC.mkv",
            "The.Matrix.1999.1080p.BluRay.x264-SPARKS.en.srt",
            "The.Dark.Knight.Trilogy.2005-2012.1080p.BluRay.x265.10bit.HDR-CMCT.mkv",
        )
        samples.forEach { fn ->
            val p = parser.parse(fn)
            println("DIAG|$fn|title=[${p.title}]|year=${p.year}|season=${p.season}|eps=${p.episodes}|res=${p.resolution}|src=${p.source}|edition=${p.edition}|mediaType=${p.mediaType}")
        }
        // 故意失败，把全部 parsed 值塞进 assertion message 让其在 CI 日志可见
        val sb = StringBuilder()
        samples.forEach { fn ->
            val p = parser.parse(fn)
            sb.append("DIAG|$fn|title=[${p.title}]|year=${p.year}|season=${p.season}|eps=${p.episodes}|res=${p.resolution}|src=${p.source}|edition=${p.edition}|mediaType=${p.mediaType}\n")
        }
        throw AssertionError(sb.toString())
    }
}
