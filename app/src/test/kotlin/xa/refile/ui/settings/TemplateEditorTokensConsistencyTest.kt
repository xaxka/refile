package xa.refile.ui.settings

import com.google.common.truth.Truth.assertThat
import xa.refile.core.naming.BindingResolver
import org.junit.Test

/**
 * 模板编辑器变量清单与 [BindingResolver] 实现一致性测试（P2-7，审查报告 2026-09-25）。
 *
 * 编辑器清单（VARIABLE_TOKENS）此前纯手写约 100 项，与实际绑定无程序校验：
 * core 新增绑定后编辑器不展示，或编辑器列出已被移除的绑定（历史上 `localize`
 * 键名不一致就造成过预览与实渲染不符）。
 *
 * 断言：编辑器清单中的「顶级变量名」（不含点路径与管道修饰符的 token）与
 * [BindingResolver.SUPPORTED_TOKENS] 完全一致（点路径前缀 info/localize/order
 * 在编辑器以 `info.X` / `localize.zh-CN.n` / `order.ABSOLUTE.e` 示例形式展示，
 * 不参与顶级名比对）。
 *
 * 新增 BindingResolver 绑定 → 需同步 VARIABLE_TOKENS（本测试失败提醒）；
 * 移除绑定 → 同理。core 侧另由 BindingResolverTokenRegistryTest 保证注册表本身
 * 与 when 分支一致。
 */
class TemplateEditorTokensConsistencyTest {

    /** 编辑器中以点路径示例展示的前缀（其顶级名不在编辑器清单中）。 */
    private val dottedPathPrefixes = setOf("info", "localize", "order")

    @Test
    fun `editor plain tokens match BindingResolver registry exactly`() {
        val editorPlainTokens = TemplateEditorViewModel.VARIABLE_TOKENS
            .map { it.token }
            .filter { token -> !token.contains('.') && !token.contains('|') }
            .toSet()

        val expected = BindingResolver.SUPPORTED_TOKENS - dottedPathPrefixes

        // 完全一致：缺失（core 有、编辑器无）与多余（编辑器有、core 无）均失败
        assertThat(editorPlainTokens).isEqualTo(expected)
    }

    @Test
    fun `editor plain tokens have no duplicates`() {
        val tokens = TemplateEditorViewModel.VARIABLE_TOKENS
            .map { it.token }
            .filter { token -> !token.contains('.') && !token.contains('|') }
        assertThat(tokens.size).isEqualTo(tokens.distinct().size)
    }

    @Test
    fun `dotted path examples cover info localize and order prefixes`() {
        // 点路径示例与 core 前缀保持同步（localize 历史上出过键名不一致事故）
        val dottedExamples = TemplateEditorViewModel.VARIABLE_TOKENS
            .map { it.token }
            .filter { it.contains('.') }
        for (prefix in dottedPathPrefixes) {
            assertThat(dottedExamples.any { it.startsWith("$prefix.") }).isTrue()
        }
    }
}
