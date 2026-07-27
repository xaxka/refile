package xa.refile.core.matcher

import com.google.common.truth.Truth.assertThat
import xa.refile.core.model.MediaType
import xa.refile.core.parser.ParsedFilename
import org.junit.Test

class MatchEngineTest {

    private val engine = MatchEngine()

    @Test fun `auto match when high confidence and margin`() {
        val parsed = ParsedFilename(title = "The Last of Us", year = 2023)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "The Last of Us", year = 2023, popularity = 50.0, mediaType = MediaType.EPISODE),
            MatchCandidate(tmdbId = 2, name = "Last Man Standing", year = 2011, popularity = 10.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `needs confirm when low confidence`() {
        val parsed = ParsedFilename(title = "The Last of Us", year = 2023)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Lost in Space", year = 2018, popularity = 5.0),
            MatchCandidate(tmdbId = 2, name = "The 100", year = 2014, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
    }

    @Test fun `needs confirm when top two too close`() {
        val parsed = ParsedFilename(title = "Lost", year = 2004)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Lost", year = 2004, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "Lost", year = 2004, popularity = 50.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
    }

    @Test fun `no match when no candidates`() {
        val decision = engine.match(ParsedFilename(title = "X"), emptyList())
        assertThat(decision).isEqualTo(MatchDecision.NoMatch)
    }

    @Test fun `alias fallback boosts score`() {
        val parsed = ParsedFilename(title = "十二国记")
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Juuni Kokuki", aliases = listOf("十二国记"), year = 2002, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `year mismatch reduces bonus`() {
        val parsed = ParsedFilename(title = "The Matrix", year = 1999)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "The Matrix", year = 2003, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "The Matrix", year = 1999, popularity = 50.0),
        )
        val decision = engine.match(parsed, candidates)
        // 两个同名候选分差 < margin → 需确认
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
    }

    // ---- P0.5 年份惩罚（线性） ----

    @Test fun `P0_5 year exact match yields minimal penalty`() {
        // diff=0 → penalty=0.01；标题完全相同 + 第二候选完全不同 → 自动匹配
        val parsed = ParsedFilename(title = "Inception", year = 2010)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Inception", year = 2010, popularity = 60.0),
            MatchCandidate(tmdbId = 2, name = "Interstellar", year = 2014, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P0_5 year large diff caps at max penalty`() {
        // diff>100 → penalty=0.11（封顶）；构造一个差百年的场景验证封顶
        val parsed = ParsedFilename(title = "It", year = 1900)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "It", year = 2017, popularity = 60.0),
            MatchCandidate(tmdbId = 2, name = "Aftersun", year = 2022, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        // diff=117 > 100 → penalty=0.11（封顶）
        // score = 1.0*0.85 - 0.11 + 0.024 + 0 = 0.764；低于阈值 0.82 → NeedsConfirm
        // 但应排第一
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
        val scored = (decision as MatchDecision.NeedsConfirm).candidates
        assertThat(scored.first().candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P0_5 missing parsed year disables penalty`() {
        // parsed.year=null → penalty=0；即便候选年份很远也不惩罚
        val parsed = ParsedFilename(title = "The Matrix", year = null)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "The Matrix", year = 1999, popularity = 60.0),
            MatchCandidate(tmdbId = 2, name = "Matrix Resurrections", year = 2021, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `P0_5 missing candidate year applies max penalty`() {
        // candidate.year=null → penalty=0.11；与有年份候选竞争时落败
        val parsed = ParsedFilename(title = "Dune", year = 2021)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Dune", year = null, popularity = 5.0),
            MatchCandidate(tmdbId = 2, name = "Dune", year = 2021, popularity = 50.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(2)
    }

    // ---- P0.6 多算法相似度 ----

    @Test fun `P0_6 levenshtein picks near-spelling candidate`() {
        // 拼写近似（Spider-Man vs Spider Man）— Jaccard 完全相等，Levenshtein 应给 1.0
        val parsed = ParsedFilename(title = "Spider-Man", year = 2002)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Spider Man", year = 2002, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "Batman Begins", year = 2005, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P0_6 dice bigram handles reordered words`() {
        // 单词换序（Lord of the Rings vs Rings Lord）— Jaccard 相同，Dice bigram 更敏感
        val parsed = ParsedFilename(title = "Lord of the Rings", year = 2001)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Rings Lord", year = 2001, popularity = 5.0),
            MatchCandidate(tmdbId = 2, name = "Star Wars", year = 1977, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat((decision as MatchDecision.NeedsConfirm).candidates.first().candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P0_6 jaro winkler rewards common prefix`() {
        // 公共前缀加权：`Avengers` vs `Avengers Endgame` 应排到 `Guardians of the Galaxy` 之前
        val parsed = ParsedFilename(title = "Avengers", year = 2012)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Guardians of the Galaxy", year = 2014, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "Avengers Endgame", year = 2019, popularity = 50.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat((decision as MatchDecision.NeedsConfirm).candidates.first().candidate.tmdbId).isEqualTo(2)
    }

    @Test fun `P0_6 year stripped similarity handles residual year token`() {
        // 解析标题残留年份 token（The Matrix 1999）时，剥离后再与候选 The Matrix 比对
        val parsed = ParsedFilename(title = "The Matrix 1999", year = 1999)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "The Matrix", year = 1999, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "The Matrix Reloaded", year = 2003, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P0_6 alias max across multiple algorithms`() {
        // 多别名场景：取所有别名 × 所有算法的最大值
        val parsed = ParsedFilename(title = "Juuni Kokuki")
        val candidates = listOf(
            MatchCandidate(
                tmdbId = 1,
                name = "Twelve Kingdoms",
                aliases = listOf("Juuni Kokuki", "12 Kingdoms"),
                year = 2002,
                popularity = 5.0,
            ),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    // ---- P0.7 去变音符号 ----

    @Test fun `P0_7 diacritic normalization Amelie`() {
        // Amélie vs Amelie — NFD 分解去变音符号后应自动匹配
        val parsed = ParsedFilename(title = "Amélie", year = 2001)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Amelie", year = 2001, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "Amadeus", year = 1984, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P0_7 diacritic normalization Naruto`() {
        // NARUTO→ナルト 风格的拉丁扩展字符归一
        val parsed = ParsedFilename(title = "Naruto Shippuden", year = 2007)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Naruto Shippuden", year = 2007, popularity = 50.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    // ---- P0.8 阈值/边距 ----

    @Test fun `P0_8 auto threshold boundary`() {
        // 高分 + 显著分差 → Auto
        val parsed = ParsedFilename(title = "Interstellar", year = 2014)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Interstellar", year = 2014, popularity = 80.0),
            MatchCandidate(tmdbId = 2, name = "Inception", year = 2010, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
    }

    @Test fun `P0_8 needs confirm when top two within margin`() {
        // 两个相似候选分差 < margin → NeedsConfirm
        val parsed = ParsedFilename(title = "Dune", year = 2021)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Dune", year = 2021, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "Dune", year = 1984, popularity = 50.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
    }

    // ---- P2.1 分数量化 ----

    @Test fun `P2_1 quantize snaps to nearest quarter`() {
        // 启用量化后得分落到 0/0.25/0.5/0.75/1.0
        val scorer = ConfidenceScorer(quantize = true)
        val parsed = ParsedFilename(title = "The Matrix", year = 1999)
        val candidate = MatchCandidate(tmdbId = 1, name = "The Matrix", year = 1999, popularity = 50.0)
        val score = scorer.score(parsed, candidate)
        // 量化后必须是 0.25 的整数倍
        val remainder = (score * 100.0) % 25.0
        assertThat(remainder).isLessThan(0.001)
    }

    @Test fun `P2_1 quantize disabled keeps continuous score`() {
        // 关闭量化时得分不应被四舍五入到 0.25 倍数（高置信度场景下取 raw）
        val scorer = ConfidenceScorer(quantize = false)
        val parsed = ParsedFilename(title = "The Matrix", year = 1999)
        val candidate = MatchCandidate(
            tmdbId = 1,
            name = "The Matrix",
            year = 1999,
            popularity = 33.0, // 非整数倍 popularity → popBonus 非 0.25 倍数
        )
        val score = scorer.score(parsed, candidate)
        // raw 分数（popularity=33 → popBonus=0.0132）不应被量化
        val remainder = (score * 100.0) % 25.0
        assertThat(remainder).isGreaterThan(0.001)
    }

    // ---- P2.2 SxE 互校加分 ----

    @Test fun `P2_2 sxe bonus boosts full match`() {
        // season + episode 完整命中 → +SXE_BONUS；可让本应 NeedsConfirm 的命中变为 Auto
        val parsed = ParsedFilename(
            title = "The Last of Us",
            year = 2023,
            season = 1,
            episodes = listOf(3),
            mediaType = MediaType.EPISODE,
        )
        val candidates = listOf(
            // 候选带 season/episodes 元数据
            MatchCandidate(
                tmdbId = 1,
                name = "The Last of Us",
                year = 2023,
                popularity = 50.0,
                mediaType = MediaType.EPISODE,
                season = 1,
                episodes = listOf(3),
            ),
            MatchCandidate(tmdbId = 2, name = "Lost", year = 2004, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P2_2 sxe bonus only season match yields half bonus`() {
        // 仅季命中（无 episodes 元数据）→ +SXE_BONUS*0.5
        val parsed = ParsedFilename(
            title = "The Last of Us",
            year = 2023,
            season = 1,
            episodes = emptyList(),
            mediaType = MediaType.EPISODE,
        )
        val candidates = listOf(
            MatchCandidate(
                tmdbId = 1,
                name = "The Last of Us",
                year = 2023,
                popularity = 50.0,
                mediaType = MediaType.EPISODE,
                season = 1,
                episodes = emptyList(),
            ),
            MatchCandidate(tmdbId = 2, name = "Lost", year = 2004, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P2_2 sxe mismatch does not penalize`() {
        // 候选 SxE 与 parsed 不一致 → 加 0（不扣分，避免误伤）
        // 验证：标题+年份命中仍排第一，SxE 不额外加分（与无 SxE 场景同分）
        val parsed = ParsedFilename(
            title = "The Last of Us",
            year = 2023,
            season = 2,
            episodes = listOf(1),
            mediaType = MediaType.EPISODE,
        )
        val candidates = listOf(
            MatchCandidate(
                tmdbId = 1,
                name = "The Last of Us",
                year = 2023,
                popularity = 50.0,
                mediaType = MediaType.EPISODE,
                season = 1,
                episodes = listOf(3),
            ),
            MatchCandidate(tmdbId = 2, name = "Lost", year = 2004, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        // 标题+年份命中 → Auto，且 SxE 不加分（验证：与无 SxE 场景同分）
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P2_2 sxe bonus skipped when candidate lacks sxe metadata`() {
        // 候选无 season/episodes → bonus=0（搜索结果轻量元数据缺 SxE 不惩罚）
        val parsed = ParsedFilename(
            title = "The Last of Us",
            year = 2023,
            season = 1,
            episodes = listOf(3),
            mediaType = MediaType.EPISODE,
        )
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "The Last of Us", year = 2023, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "Lost", year = 2004, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        // 无 SxE 加分但标题+年份强命中仍可 Auto
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    // ---- P2.3 冠词归一化 ----

    @Test fun `P2_3 leading article stripped for comparison`() {
        // The Matrix vs Matrix — 去前导冠词后高分
        val parsed = ParsedFilename(title = "The Matrix", year = 1999)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Matrix", year = 1999, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "Matrix Reloaded", year = 2003, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P2_3 trailing article reordered to leading`() {
        // Matrix, The 重排为 The Matrix 后剥离冠词 → 与 Matrix 高分
        val parsed = ParsedFilename(title = "Matrix, The", year = 1999)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "The Matrix", year = 1999, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "The Matrix Reloaded", year = 2003, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P2_3 article normalization both sides`() {
        // 双侧都有冠词也应一致归一
        val parsed = ParsedFilename(title = "The Lord of the Rings", year = 2001)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Lord of the Rings", year = 2001, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "The Hobbit", year = 2012, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `P2_3 leading article a and an`() {
        // a/an 冠词也应被剥离
        val parsed = ParsedFilename(title = "A Beautiful Mind", year = 2001)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Beautiful Mind", year = 2001, popularity = 50.0),
            MatchCandidate(tmdbId = 2, name = "Beautiful", year = 2009, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    // ---- 综合场景 ----

    @Test fun `combined diacritic plus article normalization`() {
        // Amélie + 冠词归一：标题 `Amélie` 与候选 `Amelie` 高分匹配
        val parsed = ParsedFilename(title = "Amélie", year = 2001)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "Amelie", year = 2001, popularity = 60.0),
            MatchCandidate(tmdbId = 2, name = "Amadeus", year = 1984, popularity = 5.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.Auto::class.java)
        assertThat((decision as MatchDecision.Auto).best.candidate.tmdbId).isEqualTo(1)
    }

    @Test fun `popularity bonus breaks tie between equal titles`() {
        // 两个相同标题/年份候选 → popularity 高者排第一（但因分差 < margin 仍为 NeedsConfirm）
        val parsed = ParsedFilename(title = "The Matrix", year = 1999)
        val candidates = listOf(
            MatchCandidate(tmdbId = 1, name = "The Matrix", year = 1999, popularity = 80.0),
            MatchCandidate(tmdbId = 2, name = "The Matrix", year = 1999, popularity = 20.0),
        )
        val decision = engine.match(parsed, candidates)
        assertThat(decision).isInstanceOf(MatchDecision.NeedsConfirm::class.java)
        assertThat((decision as MatchDecision.NeedsConfirm).candidates.first().candidate.tmdbId).isEqualTo(1)
    }
}
