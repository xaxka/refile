package xa.refile.core.naming

/**
 * 模板引擎（计划 §5.5，非 Groovy）。
 *
 * 模板语法（计划 §5.5，非 Groovy）：
 * - 变量 `{n}` `{y}` `{s00e00}` `{t}`
 * - `.method()` 链式：`{n.clean()}`, `{t.clean().space('')}`
 * - 管道修饰符 `{n|upper}`、可链式 `{n|lower|space(_)}`（与 `.` 链式可混用）
 * - 路径分隔 `/` 表示目录层级（重命名 = MKCOL 建目录 + MOVE）
 * - 非法文件名字符 `\/:*?"<>|` 在最终输出前按 [NamingOptions] 处理
 *
 * 容错规则（简化条件块）：变量缺失时自动省略其所在的相邻括号组；
 * 渲染失败/缺失时该段留空并清理多余分隔符，不输出 `{undefined}` 字面量。
 *
 * 纯 Kotlin 无 Android 依赖。
 *
 * @property resolver 变量绑定解析器
 * @property options 命名可视化选项
 */
class TemplateEngine(
    private val resolver: BindingResolver,
    private val options: NamingOptions = NamingOptions(),
) {
    /**
     * 渲染模板为最终相对路径（相对库根）。多段以 `/` 分隔。
     * 返回 [RenderResult]，含渲染路径与警告。
     */
    fun render(template: String): RenderResult {
        val segmentWarnings = mutableListOf<String>()
        // 按目录层级切分
        val segments = template.split('/')
        val renderedSegments = segments.map { seg -> renderSegment(seg, segmentWarnings) }
        // 过滤空段（缺失变量导致的整段空）；清理每段首尾的多余分隔符
        val nonEmpty = renderedSegments
            .map { it.trim(' ', '.', '_', '-') }
            .filter { it.isNotBlank() }
        // 应用全局可视化选项（分隔符、大小写、非法字符）
        val processed = nonEmpty.map { applyGlobalOptions(it) }
        val path = processed.joinToString("/")
        // 警告在渲染过程中由 resolver 写入，结束后收集
        return RenderResult(path = path, warnings = (resolver.warnings + segmentWarnings).distinct())
    }

    /** 渲染单段（一个目录层级或文件名）。处理变量、管道、括号组容错。 */
    private fun renderSegment(segment: String, warnings: MutableList<String>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            when {
                c == '{' -> {
                    val end = findClosingBrace(segment, i)
                    if (end == -1) {
                        sb.append(c); i++; continue
                    }
                    val expr = segment.substring(i + 1, end)
                    // 条件块 {?year?}({y}){/?} 简化：跳过 ?year? / /? 标记
                    if (expr.startsWith("?") || expr == "/?") {
                        // 条件块简化处理：直接忽略标记，内部按普通括号组容错
                        i = end + 1; continue
                    }
                    val value = evalExpression(expr)
                    if (value != null) {
                        sb.append(formatValue(value))
                    }
                    i = end + 1
                }
                c == '(' || c == '[' -> {
                    // 括号组容错：括号内含变量时，若整体渲染为空则省略整个括号组
                    val close = if (c == '(') ')' else ']'
                    val end = findClosingBracket(segment, i, c, close)
                    if (end == -1) { sb.append(c); i++; continue }
                    val inner = segment.substring(i + 1, end)
                    val renderedInner = renderSegment(inner, warnings)
                    if (renderedInner.isNotBlank()) {
                        sb.append(c).append(renderedInner).append(close)
                    }
                    i = end + 1
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    /**
     * 求值表达式。兼容两种语法（可混用）：
     * - `.method()` 链式：`{n.clean()}`, `{t.clean().space('')}`
     * - 管道风格 `|`：`{n|upper|space(_)}`
     *
     * 实现方式：先把 `.method(` 的点号预处理为 `|`，再按括号外的 `|` 拆分。
     * 只替换"后跟 标识符+( 的点号"，避免误伤变量属性路径（如 `info.key`、`localize.en.n`）。
     */
    private fun evalExpression(expr: String): Any? {
        val normalized = expr.replace(Regex("\\.(?=[A-Za-z_]\\w*\\()"), "|")
        val parts = splitTopLevelPipes(normalized)
        val varPath = parts[0].trim()
        var value: Any? = resolver.resolve(varPath)
        for (mIdx in 1 until parts.size) {
            val mod = parts[mIdx].trim()
            if (mod.isEmpty()) continue
            value = PipeModifiers.apply(value, mod)
            if (value == null) break // 链中遇 null 中断
        }
        return value
    }

    /** 按括号/引号外的 `|` 拆分，避免参数内的 `|` 被误拆（如 replaceAll('a|b', '')）。 */
    private fun splitTopLevelPipes(s: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var quote: Char? = null
        for (c in s) {
            when {
                quote != null -> { current.append(c); if (c == quote) quote = null }
                c == '\'' || c == '"' -> { quote = c; current.append(c) }
                c == '(' -> { depth++; current.append(c) }
                c == ')' -> { depth--; current.append(c) }
                c == '|' && depth == 0 -> { result.add(current.toString()); current.clear() }
                else -> current.append(c)
            }
        }
        result.add(current.toString())
        return result
    }

    /** 格式化值为字符串（列表用默认逗号，布尔 true/false）。 */
    private fun formatValue(value: Any?): String? {
        if (value == null) return null
        return when (value) {
            is List<*> -> value.joinToString(", ")
            is Boolean -> if (value) "true" else "false"
            else -> value.toString()
        }
    }

    /** 应用全局可视化选项：非法字符处理 + 大小写 + 词语分隔符。 */
    private fun applyGlobalOptions(s: String): String {
        // 非法文件名字符（路径分隔 / 已用于分段，此处不再替换 /）
        val cleaned = when (options.illegalCharHandling) {
            NamingOptions.IllegalCharHandling.REPLACE_DASH ->
                s.replace(Regex("[\\\\:*?\"<>|]"), "-")
            NamingOptions.IllegalCharHandling.REPLACE_UNDERSCORE ->
                s.replace(Regex("[\\\\:*?\"<>|]"), "_")
            NamingOptions.IllegalCharHandling.REMOVE ->
                s.replace(Regex("[\\\\:*?\"<>|]"), "")
        }
        // 大小写（先于分隔符替换：TITLE 依赖空格 split，需在空格被替换为分隔符前处理）
        val cased = when (options.casing) {
            NamingOptions.Casing.AS_IS -> cleaned
            NamingOptions.Casing.LOWER -> cleaned.lowercase()
            NamingOptions.Casing.UPPER -> cleaned.uppercase()
            NamingOptions.Casing.TITLE -> cleaned.split(' ').joinToString(" ") {
                it.replaceFirstChar { ch -> ch.titlecase() }
            }
        }
        // 词语分隔符：将空格替换为指定分隔符（在大小写处理之后）
        return if (options.wordSeparator != ' ') {
            cased.replace(' ', options.wordSeparator)
        } else cased
    }

    private fun findClosingBrace(s: String, start: Int): Int {
        var depth = 0
        for (j in start until s.length) {
            when (s[j]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return j }
            }
        }
        return -1
    }

    /** 跟踪括号深度查找匹配的闭合符（支持嵌套括号，如 `({sxe} ({y}))`）。 */
    private fun findClosingBracket(s: String, start: Int, open: Char, close: Char): Int {
        var depth = 0
        for (j in start until s.length) {
            when (s[j]) {
                open -> depth++
                close -> { depth--; if (depth == 0) return j }
            }
        }
        return -1
    }
}

/** 渲染结果。 */
data class RenderResult(
    val path: String,
    val warnings: List<String>,
)
