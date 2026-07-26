package xa.refile.core.tmdb

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TmdbImageUrlsTest {

    @Test fun `base url constant`() {
        assertThat(TmdbImages.BASE_URL).isEqualTo("https://image.tmdb.org/t/p/")
    }

    @Test fun `poster default size w342`() {
        val url = TmdbImages.poster(path = "/abc.jpg")
        assertThat(url).isEqualTo("https://image.tmdb.org/t/p/w342/abc.jpg")
    }

    @Test fun `poster custom size`() {
        val url = TmdbImages.poster(size = "w500", path = "/abc.jpg")
        assertThat(url).isEqualTo("https://image.tmdb.org/t/p/w500/abc.jpg")
    }

    @Test fun `backdrop default size w780`() {
        val url = TmdbImages.backdrop(path = "/xyz.jpg")
        assertThat(url).isEqualTo("https://image.tmdb.org/t/p/w780/xyz.jpg")
    }

    @Test fun `original size`() {
        val url = TmdbImages.original(path = "/xyz.jpg")
        assertThat(url).isEqualTo("https://image.tmdb.org/t/p/original/xyz.jpg")
    }

    @Test fun `null path returns null`() {
        assertThat(TmdbImages.poster(path = null)).isNull()
        assertThat(TmdbImages.backdrop(path = null)).isNull()
        assertThat(TmdbImages.original(path = null)).isNull()
        assertThat(TmdbImages.still(path = null)).isNull()
    }

    @Test fun `blank path returns null`() {
        assertThat(TmdbImages.poster(path = "")).isNull()
        assertThat(TmdbImages.poster(path = "   ")).isNull()
    }

    @Test fun `path without leading slash still works`() {
        val url = TmdbImages.poster(path = "abc.jpg")
        assertThat(url).isEqualTo("https://image.tmdb.org/t/p/w342/abc.jpg")
    }

    @Test fun `size with trailing slash trimmed`() {
        val url = TmdbImages.poster(size = "w342/", path = "/abc.jpg")
        assertThat(url).isEqualTo("https://image.tmdb.org/t/p/w342/abc.jpg")
    }

    @Test fun `still default size w300`() {
        val url = TmdbImages.still(path = "/ep.jpg")
        assertThat(url).isEqualTo("https://image.tmdb.org/t/p/w300/ep.jpg")
    }
}
