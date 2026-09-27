package xa.refile.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [TmdbApiKeyValidator] 单元测试（P1-3，审查报告 2026-09-25）。
 *
 * 旧校验仅判 `length >= 32`：v4 "API Read Access Token"（eyJ 开头的 JWT）被误判为
 * 有效 Key，UI 显示「已配置」但客户端以 v3 api_key 参数发送，全部请求 401。
 * 验收：粘贴 v4 token 时校验不通过（UI 明确提示不可用）；合法 v3 key 行为不变。
 */
class TmdbApiKeyValidatorTest {

    private val validV3 = "0123456789abcdef0123456789abcdef"

    @Test
    fun `valid lowercase v3 key passes`() {
        assertThat(TmdbApiKeyValidator.isValidV3Key(validV3)).isTrue()
    }

    @Test
    fun `valid uppercase v3 key passes`() {
        assertThat(TmdbApiKeyValidator.isValidV3Key(validV3.uppercase())).isTrue()
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertThat(TmdbApiKeyValidator.isValidV3Key("  $validV3  ")).isTrue()
    }

    @Test
    fun `v4 read access token is rejected`() {
        val v4Token = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJhYmMifQ.longer_than_32_chars"
        assertThat(TmdbApiKeyValidator.isValidV3Key(v4Token)).isFalse()
        assertThat(TmdbApiKeyValidator.looksLikeV4Token(v4Token)).isTrue()
    }

    @Test
    fun `32 chars but non hex is rejected`() {
        assertThat(TmdbApiKeyValidator.isValidV3Key("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz")).isFalse()
        assertThat(TmdbApiKeyValidator.looksLikeV4Token("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz")).isFalse()
    }

    @Test
    fun `too short or too long hex is rejected`() {
        assertThat(TmdbApiKeyValidator.isValidV3Key(validV3.dropLast(1))).isFalse()
        assertThat(TmdbApiKeyValidator.isValidV3Key(validV3 + "ab")).isFalse()
    }

    @Test
    fun `empty key is not valid and not v4`() {
        assertThat(TmdbApiKeyValidator.isValidV3Key("")).isFalse()
        assertThat(TmdbApiKeyValidator.looksLikeV4Token("")).isFalse()
    }
}
