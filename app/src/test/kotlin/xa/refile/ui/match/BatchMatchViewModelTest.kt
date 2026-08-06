package xa.refile.ui.match

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import xa.refile.core.model.MediaType
import xa.refile.core.naming.MediaMetadata
import xa.refile.core.parser.ParsedFilename
import xa.refile.core.tmdb.Episode
import xa.refile.core.tmdb.SeasonDetail
import xa.refile.data.prefs.SettingsRepository
import xa.refile.data.repository.TmdbDetailRepository
import xa.refile.data.repository.TmdbSearchRepository
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * BatchMatchViewModel 单元测试（Task 15）。
 *
 * 覆盖两类逻辑：
 * 1. [BatchMatchViewModel.UiState] 派生属性（slots/unboundFiles/duplicates/emptySlots/dirty/
 *    boundCount/summaryText）—— 纯数据计算，直接构造 UiState 断言。
 * 2. 绑定操作（setBinding/onDropFile/fillSequential/smartAssignFromParsed/unbindAll）——
 *    通过 mock TMDB 驱动 [load] 进入就绪态后操作。
 */
class BatchMatchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var settings: SettingsRepository
    private lateinit var tmdbSearch: TmdbSearchRepository
    private lateinit var tmdbDetail: TmdbDetailRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        settings = mockk()
        tmdbSearch = mockk(relaxed = true)
        tmdbDetail = mockk()
        every { settings.apiKey } returns flowOf("test-key")
        every { settings.language } returns flowOf("zh-CN")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- 派生属性：纯数据计算 ----

    @Test
    fun `slots groups files by binding`() {
        val ep1 = EditMatchViewModel.EpisodeInfo(1, 1, "E1", "", null, null)
        val ep2 = EditMatchViewModel.EpisodeInfo(2, 1, "E2", "", null, null)
        val f1 = fileMatch("/a.mkv", 1, 1)
        val f2 = fileMatch("/b.mkv", 1, 2)
        val state = BatchMatchViewModel.UiState(
            files = listOf(f1, f2),
            episodeList = listOf(ep1, ep2),
            bindings = mapOf("/a.mkv" to BatchMatchViewModel.SlotKey(1, 1)),
        )
        assertThat(state.slots).hasSize(2)
        assertThat(state.slots[0].files).hasSize(1)
        assertThat(state.slots[0].files.first().filePath).isEqualTo("/a.mkv")
        assertThat(state.slots[1].files).isEmpty()
    }

    @Test
    fun `unboundFiles excludes bound files`() {
        val f1 = fileMatch("/a.mkv", 1, 1)
        val f2 = fileMatch("/b.mkv", 1, 2)
        val state = BatchMatchViewModel.UiState(
            files = listOf(f1, f2),
            bindings = mapOf("/a.mkv" to BatchMatchViewModel.SlotKey(1, 1)),
        )
        assertThat(state.unboundFiles.map { it.filePath }).containsExactly("/b.mkv")
    }

    @Test
    fun `duplicates flags slots with two or more files`() {
        val ep1 = EditMatchViewModel.EpisodeInfo(1, 1, "E1", "", null, null)
        val f1 = fileMatch("/a.mkv", 1, 1)
        val f2 = fileMatch("/b.mkv", 1, 1)
        val state = BatchMatchViewModel.UiState(
            files = listOf(f1, f2),
            episodeList = listOf(ep1),
            bindings = mapOf(
                "/a.mkv" to BatchMatchViewModel.SlotKey(1, 1),
                "/b.mkv" to BatchMatchViewModel.SlotKey(1, 1),
            ),
        )
        assertThat(state.duplicates).containsExactly(BatchMatchViewModel.SlotKey(1, 1))
    }

    @Test
    fun `emptySlots lists slots without any binding`() {
        val ep1 = EditMatchViewModel.EpisodeInfo(1, 1, "E1", "", null, null)
        val ep2 = EditMatchViewModel.EpisodeInfo(2, 1, "E2", "", null, null)
        val f1 = fileMatch("/a.mkv", 1, 1)
        val state = BatchMatchViewModel.UiState(
            files = listOf(f1),
            episodeList = listOf(ep1, ep2),
            bindings = mapOf("/a.mkv" to BatchMatchViewModel.SlotKey(1, 1)),
        )
        assertThat(state.emptySlots).containsExactly(BatchMatchViewModel.SlotKey(1, 2))
    }

    @Test
    fun `dirty compares bindings to initialBindings`() {
        val state = BatchMatchViewModel.UiState(
            bindings = mapOf("/a.mkv" to BatchMatchViewModel.SlotKey(1, 1)),
            initialBindings = mapOf("/a.mkv" to BatchMatchViewModel.SlotKey(1, 1)),
        )
        assertThat(state.dirty).isFalse()

        val changed = state.copy(bindings = mapOf("/a.mkv" to BatchMatchViewModel.SlotKey(1, 2)))
        assertThat(changed.dirty).isTrue()
    }

    @Test
    fun `summaryText reports bound and unbound counts`() {
        val ep1 = EditMatchViewModel.EpisodeInfo(1, 1, "E1", "", null, null)
        val ep2 = EditMatchViewModel.EpisodeInfo(2, 1, "E2", "", null, null)
        val f1 = fileMatch("/a.mkv", 1, 1)
        val f2 = fileMatch("/b.mkv", 1, 2)
        val f3 = fileMatch("/c.mkv", 1, 3)
        val state = BatchMatchViewModel.UiState(
            files = listOf(f1, f2, f3),
            episodeList = listOf(ep1, ep2),
            bindings = mapOf(
                "/a.mkv" to BatchMatchViewModel.SlotKey(1, 1),
                "/b.mkv" to BatchMatchViewModel.SlotKey(1, 2),
            ),
        )
        assertThat(state.boundCount).isEqualTo(2)
        assertThat(state.summaryText).contains("2 个文件将绑定")
        assertThat(state.summaryText).contains("1 个保持原样")
    }

    // ---- 绑定操作：经 load 驱动后断言 ----

    @Test
    fun `load initializes episodeList and bindings from matched metadata`() = runTest(testDispatcher) {
        val vm = newViewModel()
        val files = listOf(
            fileMatch("/s01e01.mkv", season = 1, ep = 1, tvId = 100),
            fileMatch("/s01e02.mkv", season = 1, ep = 2, tvId = 100),
        )
        coEvery { tmdbDetail.getTv(100, "zh-CN") } returns tvMeta(100, 1)
        coEvery { tmdbDetail.getSeason(100, 1, "zh-CN") } returns seasonDetail(1, 3)

        vm.load(files)
        advanceUntilIdle()

        assertThat(vm.uiState.value.episodeList).hasSize(3)
        // initializeBindings：两个文件 matched 落在 episodeList 范围内 → 自动绑定
        assertThat(vm.uiState.value.bindings).hasSize(2)
        assertThat(vm.uiState.value.bindings["/s01e01.mkv"]).isEqualTo(BatchMatchViewModel.SlotKey(1, 1))
        assertThat(vm.uiState.value.bindings["/s01e02.mkv"]).isEqualTo(BatchMatchViewModel.SlotKey(1, 2))
        assertThat(vm.uiState.value.dirty).isFalse()
    }

    @Test
    fun `setBinding binds file to slot and unbind passes null`() = runTest(testDispatcher) {
        val vm = newViewModelWithLoadedState()
        val filePath = "/s01e01.mkv"

        vm.setBinding(filePath, BatchMatchViewModel.SlotKey(1, 3))
        assertThat(vm.uiState.value.bindings[filePath]).isEqualTo(BatchMatchViewModel.SlotKey(1, 3))

        vm.setBinding(filePath, null)
        assertThat(vm.uiState.value.bindings).doesNotContainKey(filePath)
    }

    @Test
    fun `onDropFile null slot unbinds`() = runTest(testDispatcher) {
        val vm = newViewModelWithLoadedState()
        val filePath = "/s01e01.mkv"
        assertThat(vm.uiState.value.bindings).containsKey(filePath)

        vm.onDropFile(filePath, null)
        assertThat(vm.uiState.value.bindings).doesNotContainKey(filePath)
    }

    @Test
    fun `onDropFile swaps files when target slot occupied`() = runTest(testDispatcher) {
        val vm = newViewModelWithLoadedState()
        // 初始：/s01e01 -> (1,1), /s01e02 -> (1,2)
        // 把 /s01e01 拖到 (1,2)（已被 /s01e02 占用）→ 交换
        vm.onDropFile("/s01e01.mkv", BatchMatchViewModel.SlotKey(1, 2))
        assertThat(vm.uiState.value.bindings["/s01e01.mkv"]).isEqualTo(BatchMatchViewModel.SlotKey(1, 2))
        assertThat(vm.uiState.value.bindings["/s01e02.mkv"]).isEqualTo(BatchMatchViewModel.SlotKey(1, 1))
    }

    @Test
    fun `fillSequential binds all files in filename order`() = runTest(testDispatcher) {
        val vm = newViewModelWithLoadedState()
        vm.unbindAll()
        assertThat(vm.uiState.value.bindings).isEmpty()

        vm.fillSequential()
        val bindings = vm.uiState.value.bindings
        assertThat(bindings).hasSize(2)
        // 按文件名排序：/s01e01 -> 第 1 槽, /s01e02 -> 第 2 槽
        assertThat(bindings["/s01e01.mkv"]).isEqualTo(BatchMatchViewModel.SlotKey(1, 1))
        assertThat(bindings["/s01e02.mkv"]).isEqualTo(BatchMatchViewModel.SlotKey(1, 2))
    }

    @Test
    fun `smartAssignFromParsed fills empty slots from parsed season episode`() = runTest(testDispatcher) {
        val vm = newViewModelWithLoadedState()
        vm.unbindAll()
        assertThat(vm.uiState.value.bindings).isEmpty()

        vm.smartAssignFromParsed()
        // parsed.season=1, episodes=[1]/[2] → 匹配槽位 (1,1)/(1,2)
        assertThat(vm.uiState.value.bindings["/s01e01.mkv"]).isEqualTo(BatchMatchViewModel.SlotKey(1, 1))
        assertThat(vm.uiState.value.bindings["/s01e02.mkv"]).isEqualTo(BatchMatchViewModel.SlotKey(1, 2))
    }

    @Test
    fun `unbindAll clears bindings but keeps files`() = runTest(testDispatcher) {
        val vm = newViewModelWithLoadedState()
        assertThat(vm.uiState.value.bindings).isNotEmpty()

        vm.unbindAll()
        assertThat(vm.uiState.value.bindings).isEmpty()
        assertThat(vm.uiState.value.files).hasSize(2)
    }

    @Test
    fun `clearError resets error to null`() = runTest(testDispatcher) {
        val vm = newViewModelWithLoadedState()
        vm.clearError()
        assertThat(vm.uiState.value.error).isNull()
    }

    // ---- 辅助构造 ----

    private fun newViewModel(): BatchMatchViewModel =
        BatchMatchViewModel(settings, tmdbSearch, tmdbDetail)

    /**
     * 构造一个已 load 完成（episodeList=3, bindings=2）的 VM，供绑定操作测试复用。
     */
    private fun TestScope.newViewModelWithLoadedState(): BatchMatchViewModel {
        val vm = BatchMatchViewModel(settings, tmdbSearch, tmdbDetail)
        val files = listOf(
            fileMatch("/s01e01.mkv", season = 1, ep = 1, tvId = 100),
            fileMatch("/s01e02.mkv", season = 1, ep = 2, tvId = 100),
        )
        coEvery { tmdbDetail.getTv(100, "zh-CN") } returns tvMeta(100, 1)
        coEvery { tmdbDetail.getSeason(100, 1, "zh-CN") } returns seasonDetail(1, 3)
        vm.load(files)
        advanceUntilIdle()
        return vm
    }

    private fun fileMatch(path: String, season: Int, ep: Int, tvId: Int = 100): MatchViewModel.FileMatch =
        MatchViewModel.FileMatch(
            filePath = path,
            parsed = ParsedFilename(title = "Test Show", season = season, episodes = listOf(ep), mediaType = MediaType.EPISODE),
            status = MatchViewModel.MatchStatus.CONFIRMED,
            matched = MediaMetadata(
                type = MediaType.EPISODE,
                id = tvId,
                name = "Test Show",
                numberOfSeasons = 1,
                seasonNumber = season,
                episodeNumbers = listOf(ep),
            ),
        )

    private fun tvMeta(id: Int, numberOfSeasons: Int): MediaMetadata = MediaMetadata(
        type = MediaType.EPISODE,
        id = id,
        name = "Test Show",
        numberOfSeasons = numberOfSeasons,
    )

    private fun seasonDetail(season: Int, episodeCount: Int): SeasonDetail = SeasonDetail(
        id = season,
        seasonNumber = season,
        name = "Season $season",
        episodes = (1..episodeCount).map { ep ->
            Episode(id = ep, episodeNumber = ep, name = "Episode $ep", seasonNumber = season)
        },
    )
}
