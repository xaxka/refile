package xa.refile.ui.match

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import xa.refile.core.model.MediaType
import xa.refile.core.naming.MediaMetadata
import xa.refile.core.parser.ParsedFilename
import xa.refile.data.prefs.SettingsRepository
import xa.refile.data.repository.TmdbDetailRepository
import xa.refile.data.repository.TmdbSearchRepository
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * MatchViewModel 单元测试（Task 15）。
 *
 * 覆盖状态管理与错误路径（不依赖 TMDB 网络请求的部分）：
 * - [setFiles] / [setMatchType] / [clearError] / [resetMatch] 状态更新
 * - [applyEditedResults] 按 status 分流到 results/pending
 * - [startMatch] 错误路径（空文件 / 缺 API Key）
 * - [UiState.allResolved] 派生属性
 */
class MatchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var settings: SettingsRepository
    private lateinit var tmdbSearch: TmdbSearchRepository
    private lateinit var tmdbDetail: TmdbDetailRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        settings = mockk()
        tmdbSearch = mockk(relaxed = true)
        tmdbDetail = mockk(relaxed = true)
        context = mockk()
        every { settings.apiKey } returns flowOf("test-key")
        every { settings.language } returns flowOf("zh-CN")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): MatchViewModel =
        MatchViewModel(settings, tmdbSearch, tmdbDetail, context)

    @Test
    fun `setFiles updates selectedFiles and clears previous results`() {
        val vm = newViewModel()
        vm.setFiles(listOf("/a.mkv", "/b.mkv"))
        val state = vm.uiState.value
        assertThat(state.selectedFiles).containsExactly("/a.mkv", "/b.mkv").inOrder()
        assertThat(state.results).isEmpty()
        assertThat(state.pending).isEmpty()
        assertThat(state.progress).isEqualTo(MatchViewModel.Progress.Idle)
    }

    @Test
    fun `setFiles clears session cache only when file list changes`() {
        val vm = newViewModel()
        vm.setFiles(listOf("/a.mkv"))
        // 再次设置相同列表 → 不应再次清缓存
        vm.setFiles(listOf("/a.mkv"))
        verify(exactly = 1) { tmdbSearch.clearSessionCache() }

        // 设置不同列表 → 再次清缓存
        vm.setFiles(listOf("/b.mkv"))
        verify(exactly = 2) { tmdbSearch.clearSessionCache() }
    }

    @Test
    fun `setMatchType updates matchType`() {
        val vm = newViewModel()
        assertThat(vm.uiState.value.matchType).isEqualTo(MatchViewModel.MatchType.AUTO)

        vm.setMatchType(MatchViewModel.MatchType.TV)
        assertThat(vm.uiState.value.matchType).isEqualTo(MatchViewModel.MatchType.TV)

        vm.setMatchType(MatchViewModel.MatchType.MOVIE)
        assertThat(vm.uiState.value.matchType).isEqualTo(MatchViewModel.MatchType.MOVIE)
    }

    @Test
    fun `clearError resets error to null`() {
        val vm = newViewModel()
        // 先触发一个错误（空文件 startMatch）
        vm.startMatch(MatchViewModel.MatchType.AUTO)
        assertThat(vm.uiState.value.error).isNotNull()

        vm.clearError()
        assertThat(vm.uiState.value.error).isNull()
    }

    @Test
    fun `resetMatch clears progress results and pending`() {
        val vm = newViewModel()
        vm.setFiles(listOf("/a.mkv"))
        vm.startMatch(MatchViewModel.MatchType.AUTO)

        vm.resetMatch()
        val state = vm.uiState.value
        assertThat(state.progress).isEqualTo(MatchViewModel.Progress.Idle)
        assertThat(state.results).isEmpty()
        assertThat(state.pending).isEmpty()
        assertThat(state.error).isNull()
        assertThat(state.manualSearchingPath).isNull()
    }

    @Test
    fun `applyEditedResults partitions by status`() {
        val vm = newViewModel()
        val auto = fileMatch("/auto.mkv", MatchViewModel.MatchStatus.AUTO)
        val confirmed = fileMatch("/confirmed.mkv", MatchViewModel.MatchStatus.CONFIRMED)
        val pending = fileMatch("/pending.mkv", MatchViewModel.MatchStatus.PENDING)
        val noMatch = fileMatch("/nomatch.mkv", MatchViewModel.MatchStatus.NO_MATCH)

        vm.applyEditedResults(listOf(auto, confirmed, pending, noMatch))
        val state = vm.uiState.value
        assertThat(state.results.map { it.filePath }).containsExactly("/auto.mkv", "/confirmed.mkv")
        assertThat(state.pending.map { it.filePath }).containsExactly("/pending.mkv", "/nomatch.mkv")
    }

    @Test
    fun `allResolved is true when no pending status remains`() {
        val vm = newViewModel()
        vm.applyEditedResults(listOf(fileMatch("/a.mkv", MatchViewModel.MatchStatus.CONFIRMED)))
        assertThat(vm.uiState.value.allResolved).isTrue()
    }

    @Test
    fun `allResolved is false when pending status remains`() {
        val vm = newViewModel()
        vm.applyEditedResults(listOf(fileMatch("/a.mkv", MatchViewModel.MatchStatus.PENDING)))
        assertThat(vm.uiState.value.allResolved).isFalse()
    }

    @Test
    fun `startMatch with empty files sets error without running`() = runTest(testDispatcher) {
        val vm = newViewModel()
        vm.setFiles(emptyList())
        vm.startMatch(MatchViewModel.MatchType.AUTO)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.error).isEqualTo("未选择任何文件")
        assertThat(state.progress).isEqualTo(MatchViewModel.Progress.Idle)
    }

    @Test
    fun `startMatch with blank api key sets error`() = runTest(testDispatcher) {
        every { settings.apiKey } returns flowOf("")
        val vm = newViewModel()
        vm.setFiles(listOf("/a.mkv"))
        vm.startMatch(MatchViewModel.MatchType.AUTO)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.error).isEqualTo("请先在设置中填入 TMDB API Key")
        assertThat(state.progress).isEqualTo(MatchViewModel.Progress.Idle)
    }

    private fun fileMatch(path: String, status: MatchViewModel.MatchStatus): MatchViewModel.FileMatch =
        MatchViewModel.FileMatch(
            filePath = path,
            parsed = ParsedFilename(title = "Test", mediaType = MediaType.MOVIE),
            status = status,
            matched = if (status == MatchViewModel.MatchStatus.AUTO || status == MatchViewModel.MatchStatus.CONFIRMED) {
                MediaMetadata(type = MediaType.MOVIE, id = 1, name = "Test")
            } else {
                null
            },
        )
}
