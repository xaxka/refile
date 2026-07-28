package xa.refile.core.naming

import java.util.concurrent.ConcurrentHashMap

/**
 * 模板引擎（计划 §5.5，非 Groovy）。
 *
 * 模板语法（计划 §5.5，非 Groovy）：
 * - 变量 `{n}` `{y}` `{s00e00}` `{t}`
 * - `.method()` 链式：`{n.clean()}`, `{t.clean().space('')}`
 * - 管道修饰符 `{n|upper}`、可链式 `{n|lower|space(_)}`（与 `.` 链式可混用）
 * - 路径分隔 `/` 表示目录层级（重命名 = MKCOL 建目录 + MOVE）
 * - 非法文件名字符 `\/:*?"<>|` 在最终输出前按 [NamingOptions] 处理
 * - Feature #9：条件块 `{?cond}content{/?}` — cond 为真时渲染 content，否则整段省略；
 *   支持 else 分支 `{?cond}yes{:}no{/?}` 和嵌套；支持取反 `{?!cond}`。
 * - Feature #21：管道表达式编译缓存 — 同一表达式字符串只解析一次，后续渲染直接复用解析结果。
 *
 * 容错规则：变量缺失时自动省略其所在的相邻括号组；
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
     * Feature #21：管道表达式编译缓存（参考 FB-Mod ExpressionFormat.scriptletCache）。
     *
     * 同一模板被多次渲染时（批量重命名场景），表达式解析结果（变量路径 + 修饰符列表）
     * 可复用，避免每次重复 normalize + splitTopLevelPipes。
     *
     * [ConcurrentHashMap] 保证并发安全；key 为原始表达式字符串，value 为编译后的管道链。
     */
    private val expressionCache: ConcurrentHashMap<String, CompiledExpression> = ConcurrentHashMap()

    /**
     * 渲染模板为最终相对路径（相对库根）。多段以 `/` 分隔。
     * 返回 [RenderResult]，含渲染路径与警告。
     */
    fun render(template: String): RenderResult {
        val segmentWarnings = mutableListOf<String>()
        // 按目录层级切分（但不在 {} 内部切分，保护 {?...}{/?} 条件块标记和 dateFormat(dd/MM/yyyy) 等参数）
        val segments = splitByPathSeparator(template)
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

    /** 渲染单段（一个目录层级或文件名）。处理变量、管道、括号组容错、条件块。 */
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

                    // Feature #9：条件块 {?cond}content{/?} 和 {?cond}yes{:}no{/?}
                    if (expr.startsWith("?") && expr != "/?" && !expr.startsWith("/")) {
                        val condVar = expr.removePrefix("?").removeSuffix("?").trim()
                        val negate = condVar.startsWith("!")
                        val varName = if (negate) condVar.removePrefix("!").trim() else condVar
                        // 查找匹配的 {/?}（跟踪嵌套），以及可选的 {:} else 分隔符
                        val (closePos, elsePos) = findConditionalEnd(segment, end + 1)
                        if (closePos == -1) {
                            // 未找到匹配的 {/?}：回退到旧行为（跳过标记）
                            i = end + 1; continue
                        }
                        val condValue = resolver.resolve(varName)
                        val truthy = isTruthy(condValue) != negate
                        val contentStart = end + 1
                        val contentEnd = elsePos ?: closePos
                        val branch = if (truthy) {
                            segment.substring(contentStart, contentEnd)
                        } else {
                            if (elsePos != null) segment.substring(elsePos + 3, closePos) else ""
                        }
                        val rendered = renderSegment(branch, warnings)
                        if (rendered.isNotBlank()) {
                            sb.append(rendered)
                        }
                        // 跳过到 {/?} 之后
                        val closeEnd = findClosingBrace(segment, closePos)
                        i = closeEnd + 1
                    }
                    // 条件块结束标记 {/?} 或 else 标记 {:} — 单独出现时跳过（已被 start 匹配消费）
                    else if (expr == "/?" || expr == ":") {
                        i = end + 1; continue
                    }
                    else {
                        val value = evalExpression(expr)
                        if (value != null) {
                            sb.append(formatValue(value))
                        }
                        i = end + 1
                    }
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
     * Feature #9：查找条件块的匹配结束标记 `{/?}` 和可选的 else 分隔符 `{:}`。
     *
     * 从 [start] 位置扫描，跟踪嵌套的 `{?...}` 块深度。返回：
     * - first：`{/?}` 标记的 `{` 位置（未找到 → -1）
     * - second：`{:}` else 标记的 `{` 位置（无 else → null）
     */
    private fun findConditionalEnd(segment: String, start: Int): Pair<Int, Int?> {
        var depth = 1
        var i = start
        var elsePos: Int? = null
        while (i < segment.length) {
            if (segment[i] == '{') {
                val end = findClosingBrace(segment, i)
                if (end == -1) break
                val expr = segment.substring(i + 1, end)
                when {
                    // 嵌套条件块开始
                    expr.startsWith("?") && expr != "/?" && !expr.startsWith("/") -> depth++
                    // 条件块结束
                    expr == "/?" -> {
                        depth--
                        if (depth == 0) return Pair(i, elsePos)
                    }
                    // else 分隔符（仅在最外层 depth==1 时记录第一个）
                    expr == ":" && depth == 1 && elsePos == null -> elsePos = i
                }
                i = end + 1
            } else {
                i++
            }
        }
        return Pair(-1, null)
    }

    /**
     * Feature #9：判断解析值是否为真（truthy）。
     *
     * - null → false
     * - 空字符串 / 纯空白 → false
     * - 空列表 → false
     * - 布尔 false → false
     * - 其它（含 0、非空字符串、非空列表）→ true
     */
    private fun isTruthy(value: Any?): Boolean {
        return when (value) {
            null -> false
            is String -> value.isNotBlank()
            is List<*> -> value.isNotEmpty()
            is Boolean -> value
            is Number -> true // 数字 0 仍视为真（年份=0 是有效值）
            else -> value.toString().isNotBlank()
        }
    }

    /**
     * 求值表达式。兼容两种语法（可混用）：
     * - `.method()` 链式：`{n.clean()}`, `{t.clean().space('')}`
     * - 管道风格 `|`：`{n|upper|space(_)}`
     *
     * Feature #21：使用 [expressionCache] 缓存编译结果。同一表达式字符串只解析一次，
     * 后续渲染直接复用 [CompiledExpression]（变量路径 + 修饰符列表）。
     *
     * 实现方式：先把 `.method(` 的点号预处理为 `|`，再按括号外的 `|` 拆分。
     * 只替换"后跟 标识符+( 的点号"，避免误伤变量属性路径（如 `info.key`、`localize.en.n`）。
     */
    private fun evalExpression(expr: String): Any? {
        val compiled = expressionCache.getOrPut(expr) {
            val normalized = expr.replace(Regex("\\.(?=[A-Za-z_]\\w*\\()"), "|")
            val parts = splitTopLevelPipes(normalized)
            val varPath = parts[0].trim()
            val modifiers = parts.subList(1, parts.size)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            CompiledExpression(varPath, modifiers)
        }
        var value: Any? = resolver.resolve(compiled.varPath)
        for (mod in compiled.modifiers) {
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
                s.replace(Regex("[\\\\:*?\"<>|`\\t\\r\\n\\f\\u0000]"), "-")
            NamingOptions.IllegalCharHandling.REPLACE_UNDERSCORE ->
                s.replace(Regex("[\\\\:*?\"<>|`\\t\\r\\n\\f\\u0000]"), "_")
            NamingOptions.IllegalCharHandling.REMOVE ->
                s.replace(Regex("[\\\\:*?\"<>|`\\t\\r\\n\\f\\u0000]"), "")
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

    /**
     * 按路径分隔符 `/` 切分模板为目录段，但忽略 `{}` 内部的 `/`。
     *
     * 保护场景：
     * - 条件块标记 `{/?}`、`{:}` 内部的 `/` 不被切分
     * - 修饰符参数如 `dateFormat(dd/MM/yyyy)` 内部的 `/` 不被切分
     *
     * 跟踪 `{}` 深度：仅在 `braceDepth == 0` 时把 `/` 当作路径分隔符。
     */
    private fun splitByPathSeparator(template: String): List<String> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var braceDepth = 0
        for (c in template) {
            when {
                c == '{' -> { braceDepth++; current.append(c) }
                c == '}' -> { if (braceDepth > 0) braceDepth--; current.append(c) }
                c == '/' && braceDepth == 0 -> {
                    segments.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
        }
        segments.add(current.toString())
        return segments
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

/**
 * Feature #21：编译后的管道表达式（缓存条目）。
 *
 * 将原始表达式字符串（如 `n.clean()|upper`）预编译为变量路径 + 修饰符列表，
 * 避免每次渲染重复 normalize + split。
 */
private data class CompiledExpression(
    val varPath: String,
    val modifiers: List<String>,
)

/** 渲染结果。 */
data class RenderResult(
    val path: String,
    val warnings: List<String>,
)
