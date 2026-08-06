package xa.refile.ui.preview

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import xa.refile.core.model.MediaType
import xa.refile.core.naming.PresetRepository
import xa.refile.data.prefs.SettingsRepository
import xa.refile.data.repository.ServerRepository
import xa.refile.data.repository.TmdbDetailRepository
import xa.refile.worker.RenameWorkScheduler
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * PreviewViewModel 单元测试（Task 15）。
 *
 * 覆盖不依赖网络的状态与派生逻辑：
 * - [PreviewViewModel.UiState] 派生属性（activeItems 过滤/排序、autoCount/needsConfirmCount/
 *   conflictCount）—— 纯数据计算。
 * - [setFilter] 切换/回切（同值再点回到 ALL）。
 * - [clearError] 清空错误。
 */
class PreviewViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): PreviewViewModel = PreviewViewModel(
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
    )

    // ---- 派生属性：纯数据计算 ----

    @Test
    fun `activeItems ALL filter returns all items sorted by filename`() {
        val items = listOf(
            previewItem("/shows/z.mkv", PreviewViewModel.PreviewStatus.AUTO),
            previewItem("/shows/a.mkv", PreviewViewModel.PreviewStatus.NEEDS_CONFIRM),
            previewItem("/shows/m.mkv", PreviewViewModel.PreviewStatus.CONFLICT),
        )
        val state = PreviewViewModel.UiState(
            previewItems = items,
            filter = PreviewViewModel.StatusFilter.ALL,
        )
        assertThat(state.activeItems.map { it.sourcePath })
            .containsExactly("/shows/a.mkv", "/shows/m.mkv", "/shows/z.mkv").inOrder()
    }

    @Test
    fun `activeItems UNMATCHED filter returns only needs confirm`() {
        val items = listOf(
            previewItem("/a.mkv", PreviewViewModel.PreviewStatus.AUTO),
            previewItem("/b.mkv", PreviewViewModel.PreviewStatus.NEEDS_CONFIRM),
            previewItem("/c.mkv", PreviewViewModel.PreviewStatus.CONFLICT),
        )
        val state = PreviewViewModel.UiState(
            previewItems = items,
            filter = PreviewViewModel.StatusFilter.UNMATCHED,
        )
        assertThat(state.activeItems.map { it.sourcePath }).containsExactly("/b.mkv")
    }

    @Test
    fun `activeItems CONFLICT filter returns only conflicts`() {
        val items = listOf(
            previewItem("/a.mkv", PreviewViewModel.PreviewStatus.AUTO),
            previewItem("/c.mkv", PreviewViewModel.PreviewStatus.CONFLICT),
        )
        val state = PreviewViewModel.UiState(
            previewItems = items,
            filter = PreviewViewModel.StatusFilter.CONFLICT,
        )
        assertThat(state.activeItems.map { it.sourcePath }).containsExactly("/c.mkv")
    }

    @Test
    fun `counts reflect status distribution`() {
        val items = listOf(
            previewItem("/a.mkv", PreviewViewModel.PreviewStatus.AUTO),
            previewItem("/b.mkv", PreviewViewModel.PreviewStatus.AUTO),
            previewItem("/c.mkv", PreviewViewModel.PreviewStatus.NEEDS_CONFIRM),
            previewItem("/d.mkv", PreviewViewModel.PreviewStatus.CONFLICT),
            previewItem("/e.mkv", PreviewViewModel.PreviewStatus.CONFLICT),
        )
        val state = PreviewViewModel.UiState(previewItems = items)
        assertThat(state.autoCount).isEqualTo(2)
        assertThat(state.needsConfirmCount).isEqualTo(1)
        assertThat(state.conflictCount).isEqualTo(2)
    }

    @Test
    fun `counts are zero for empty state`() {
        val state = PreviewViewModel.UiState()
        assertThat(state.autoCount).isEqualTo(0)
        assertThat(state.needsConfirmCount).isEqualTo(0)
        assertThat(state.conflictCount).isEqualTo(0)
        assertThat(state.activeItems).isEmpty()
    }

    // ---- 状态操作 ----

    @Test
    fun `setFilter toggles to filter then back to ALL on second tap`() {
        val vm = newViewModel()
        assertThat(vm.uiState.value.filter).isEqualTo(PreviewViewModel.StatusFilter.ALL)

        vm.setFilter(PreviewViewModel.StatusFilter.UNMATCHED)
        assertThat(vm.uiState.value.filter).isEqualTo(PreviewViewModel.StatusFilter.UNMATCHED)

        // 同值再点 → 回到 ALL
        vm.setFilter(PreviewViewModel.StatusFilter.UNMATCHED)
        assertThat(vm.uiState.value.filter).isEqualTo(PreviewViewModel.StatusFilter.ALL)
    }

    @Test
    fun `setFilter switches between unmatched and conflict`() {
        val vm = newViewModel()
        vm.setFilter(PreviewViewModel.StatusFilter.UNMATCHED)
        assertThat(vm.uiState.value.filter).isEqualTo(PreviewViewModel.StatusFilter.UNMATCHED)

        vm.setFilter(PreviewViewModel.StatusFilter.CONFLICT)
        assertThat(vm.uiState.value.filter).isEqualTo(PreviewViewModel.StatusFilter.CONFLICT)

        // 再点 CONFLICT → 回到 ALL
        vm.setFilter(PreviewViewModel.StatusFilter.CONFLICT)
        assertThat(vm.uiState.value.filter).isEqualTo(PreviewViewModel.StatusFilter.ALL)
    }

    @Test
    fun `clearError resets error to null`() {
        val vm = newViewModel()
        vm.clearError()
        assertThat(vm.uiState.value.error).isNull()
    }

    private fun previewItem(
        path: String,
        status: PreviewViewModel.PreviewStatus,
    ): PreviewViewModel.PreviewItem = PreviewViewModel.PreviewItem(
        sourcePath = path,
        targetPath = "/target/$path",
        companions = emptyList(),
        mediaType = MediaType.MOVIE,
        status = status,
        warnings = emptyList(),
    )
}
