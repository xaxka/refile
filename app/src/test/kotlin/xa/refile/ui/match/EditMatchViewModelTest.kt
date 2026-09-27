package xa.refile.ui.match

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import xa.refile.core.model.MediaType
import xa.refile.core.naming.MediaMetadata
import xa.refile.core.parser.FilenameParser
import xa.refile.data.prefs.SettingsRepository
import xa.refile.data.repository.TmdbDetailRepository
import xa.refile.data.repository.TmdbSearchRepository
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * [EditMatchViewModel] 季号无法确定时阻止保存单元测试（P1-6，审查报告 2026-09-25）。
 *
 * 旧逻辑：用户未选具体季（「全部季」）时保存遍历所有季查找包含所选集号的季；
 * numberOfSeasons 拉取失败或所有季均不含该集号时静默回退 `(1, null)`——元数据被写成
 * S01 而实际可能是 S03 的集，重命名结果错误且用户无感知。
 * 验收：保存被阻止并提示「无法确定所属季，请手动选择季号」，而非静默写 S01。
 */
class EditMatchViewModelTest {

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newVm(tmdbDetail: TmdbDetailRepository): EditMatchViewModel {
        val settings = mockk<SettingsRepository>()
        every { settings.apiKey } returns flowOf("k".repeat(32))
        every { settings.language } returns flowOf("zh-CN")
        return EditMatchViewModel(
            settings = settings,
            tmdbSearch = mockk<TmdbSearchRepository>(relaxed = true),
            tmdbDetail = tmdbDetail,
        )
    }

    private fun fileMatch(): MatchViewModel.FileMatch {
        val parsed = FilenameParser().parse("Show S01E02.mkv")
        return MatchViewModel.FileMatch(
            filePath = "/d/Show S01E02.mkv",
            parsed = parsed,
            status = MatchViewModel.MatchStatus.PENDING,
        )
    }

    @Test
    fun `applyEdit blocked with explicit error when season cannot be determined`() = runTest {
        val tmdbDetail = mockk<TmdbDetailRepository>()
        // numberOfSeasons 拉取失败场景：getTv 成功但无季数（默认 null），所有 getSeason 均 404。
        coEvery { tmdbDetail.getTv(any(), any()) } returns
            MediaMetadata(type = MediaType.TV, tmdbId = 100, name = "Show")
        coEvery { tmdbDetail.getSeason(any(), any(), any()) } throws RuntimeException("HTTP 404")

        val vm = newVm(tmdbDetail)
        vm.load(fileMatch())
        vm.selectMedia(
            EditMatchViewModel.MediaCandidate(
                tmdbId = 100,
                name = "Show",
                year = 2024,
                overview = null,
                posterUrl = null,
                mediaType = MediaType.EPISODE,
            ),
        )
        advanceUntilIdle()
        // 「全部季」（seasonNumber=null）+ numberOfSeasons 未知
        assertThat(vm.uiState.value.seasonNumber).isNull()
        assertThat(vm.uiState.value.selectedEpisodeNumbers).isNotEmpty()

        vm.applyEdit()
        advanceUntilIdle()

        // P1-6：保存被阻止并给出明确错误，而非静默写 S01
        assertThat(vm.uiState.value.saved).isNull()
        assertThat(vm.uiState.value.error).isNotNull()
        assertThat(vm.uiState.value.error).contains("无法确定所属季")
        assertThat(vm.uiState.value.error).contains("请手动选择季号")
        assertThat(vm.uiState.value.loading).isFalse()
    }

    @Test
    fun `applyEdit succeeds when episodes fall back to explicit season`() = runTest {
        // 用户显式选季（seasonNumber=3）：即使 getSeason 失败也按用户选择落盘（旧行为不变）
        val tmdbDetail = mockk<TmdbDetailRepository>()
        val tv = MediaMetadata(type = MediaType.TV, tmdbId = 100, name = "Show")
        coEvery { tmdbDetail.getTv(any(), any()) } returns tv
        coEvery { tmdbDetail.getSeason(100, 3, any()) } throws RuntimeException("HTTP 404")

        val vm = newVm(tmdbDetail)
        vm.load(fileMatch())
        vm.selectMedia(
            EditMatchViewModel.MediaCandidate(
                tmdbId = 100,
                name = "Show",
                year = 2024,
                overview = null,
                posterUrl = null,
                mediaType = MediaType.EPISODE,
            ),
        )
        advanceUntilIdle()
        vm.setSeason(3)
        advanceUntilIdle()
        assertThat(vm.uiState.value.seasonNumber).isEqualTo(3)

        vm.applyEdit()
        advanceUntilIdle()

        // 显式选季：保存成功，元数据季号 = 用户所选（3），不回退 1
        val saved = vm.uiState.value.saved
        assertThat(saved).isNotNull()
        assertThat(saved!!.matched?.seasonNumber).isEqualTo(3)
        assertThat(saved.matched?.episodeNumbers).containsExactly(2)
    }
}
