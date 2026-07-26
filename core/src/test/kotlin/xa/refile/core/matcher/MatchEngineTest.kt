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
}
