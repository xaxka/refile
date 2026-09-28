package xa.refile.core.naming

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [BindingResolver.SUPPORTED_TOKENS] 注册表一致性测试（P2-7，审查报告 2026-09-25）。
 *
 * 注册表与 [BindingResolver.resolve] 的 when 分支应一一对应：
 * - 注册表内每个 token 解析不产生「未知变量/排除绑定」警告（裸名解析安全）；
 * - 注册表外的未知变量名必须产生警告（检测机制有效，而非永远静默）；
 * - 注册表与排除清单（excluded）无交集（排除项应警告而非静默解析）。
 *
 * 该注册表是模板编辑器变量清单（app 层 VARIABLE_TOKENS）一致性测试的权威来源：
 * core 新增绑定未同步登记时本测试与编辑器清单测试双双失败提醒。
 */
class BindingResolverTokenRegistryTest {

    private fun newResolver(): BindingResolver = BindingResolver(
        media = MediaMetadata(),
        file = FileContext(),
        batch = BatchContext(),
    )

    @Test
    fun `every registry token resolves without unknown-variable warning`() {
        val offenders = BindingResolver.SUPPORTED_TOKENS.filter { token ->
            val resolver = newResolver()
            resolver.resolve(token)
            resolver.warnings.isNotEmpty()
        }
        // 若失败：把 offender 从注册表移除，或为 when 分支补实现
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `unknown token outside registry produces warning`() {
        val resolver = newResolver()
        resolver.resolve("definitelyNotAToken")
        assertThat(resolver.warnings).isNotEmpty()
    }

    @Test
    fun `excluded token produces warning not silent resolve`() {
        // excluded 中的变量（如需读取文件内容的媒体流信息）应警告而非进注册表
        val resolver = newResolver()
        resolver.resolve("resolution")
        assertThat(resolver.warnings).isNotEmpty()
    }

    @Test
    fun `registry covers known variable groups`() {
        // 健全性：注册表覆盖 A–G 组的代表性 token（防止整体为空的退化）
        val representative = setOf("n", "s00e00", "collection", "pi", "fn", "vf", "self")
        assertThat(BindingResolver.SUPPORTED_TOKENS).containsAtLeastElementsIn(representative)
    }
}
