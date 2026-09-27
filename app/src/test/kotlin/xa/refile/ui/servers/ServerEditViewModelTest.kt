package xa.refile.ui.servers

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import xa.refile.core.webdav.ConnectionResult
import xa.refile.data.crypto.KeystoreCrypto
import xa.refile.data.repository.ServerRepository
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * [ServerEditViewModel] OpenList OTP（两步验证）链路单元测试（P0-1，审查报告 2026-09-25）。
 *
 * 覆盖方案 A 的状态机：
 * - 登录被拒且服务器提示需要 OTP → 打开一次性验证码输入框并给出明确错误文案；
 * - 用户填码重试 → 透传 otpCode 给 [ServerRepository.testConnection]（不落库）；
 * - 连接成功 → 关闭输入框并清码；切回 WebDAV 类型 → 重置 OTP 状态。
 */
class ServerEditViewModelTest {

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newVm(repo: ServerRepository): ServerEditViewModel =
        ServerEditViewModel(repo, mockk<KeystoreCrypto>(relaxed = true))

    private fun fillOpenListForm(vm: ServerEditViewModel) {
        vm.updateName("OpenList")
        vm.updateType("openlist")
        vm.updateBaseUrl("https://ol.example.com")
        vm.updateUsername("admin")
        vm.updatePassword("pw")
    }

    @Test
    fun `auth failure needing otp opens otp input with explicit error`() = runTest {
        val repo = mockk<ServerRepository>()
        coEvery { repo.testConnection(any(), any()) } returns
            ConnectionResult.AuthFailure(400, "otp code is empty", needsOtp = true)
        val vm = newVm(repo)
        fillOpenListForm(vm)

        vm.testConnection()
        advanceUntilIdle()

        assertThat(vm.uiState.value.otpRequired).isTrue()
        val error = vm.uiState.value.testResult as ServerEditViewModel.TestResultUi.Error
        assertThat(error.message).contains("两步验证")
        // 首次测试不带 OTP 码（由服务器提示需要）
        coVerify(exactly = 1) { repo.testConnection(any(), otpCode = null) }
    }

    @Test
    fun `retry passes entered otp code and closes input on success`() = runTest {
        val repo = mockk<ServerRepository>()
        coEvery { repo.testConnection(any(), any()) } returnsMany listOf(
            ConnectionResult.AuthFailure(400, "otp code is empty", needsOtp = true),
            ConnectionResult.Success(),
        )
        val vm = newVm(repo)
        fillOpenListForm(vm)

        vm.testConnection()
        advanceUntilIdle()

        vm.updateOtpCode("123456")
        vm.testConnection()
        advanceUntilIdle()

        // 重试带一次性验证码透传（不落库：UiState 仅内存）
        coVerify(exactly = 1) { repo.testConnection(any(), "123456") }
        assertThat(vm.uiState.value.otpRequired).isFalse()
        assertThat(vm.uiState.value.otpCode).isEmpty()
        assertThat(vm.uiState.value.testResult).isInstanceOf(ServerEditViewModel.TestResultUi.Success::class.java)
    }

    @Test
    fun `plain auth failure message is surfaced to the user`() = runTest {
        val repo = mockk<ServerRepository>()
        coEvery { repo.testConnection(any(), any()) } returns
            ConnectionResult.AuthFailure(401, "wrong password")
        val vm = newVm(repo)
        fillOpenListForm(vm)

        vm.testConnection()
        advanceUntilIdle()

        // 非 OTP 的认证失败：不弹 OTP 输入框，但带出服务器原始消息便于排查
        assertThat(vm.uiState.value.otpRequired).isFalse()
        val error = vm.uiState.value.testResult as ServerEditViewModel.TestResultUi.Error
        assertThat(error.message).contains("wrong password")
    }

    @Test
    fun `switching type back to webdav resets otp state`() = runTest {
        val vm = newVm(mockk<ServerRepository>(relaxed = true))

        vm.updateType("openlist")
        vm.updateOtpCode("123456")
        vm.updateType("webdav")

        assertThat(vm.uiState.value.type).isEqualTo("webdav")
        assertThat(vm.uiState.value.otpCode).isEmpty()
        assertThat(vm.uiState.value.otpRequired).isFalse()
    }
}
