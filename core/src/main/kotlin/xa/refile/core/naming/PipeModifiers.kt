package xa.refile.core.naming

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.regex.PatternSyntaxException

/**
 * 管道修饰符（全部自实现，可链式 `{n|upper|space('.')}`）。
 *
 * 支持的修饰符分类：
 * - 大小写：lower / upper / upperInitial / lowerTrail（含可选 pattern 参数）
 * - 数字：pad / round / roman
 * - 模式匹配：match / matchAll / matchBrackets / before / after
 * - 替换移除：replace / replaceAll / removeAll / remove / removeBrackets /
 *   removeIllegalCharacters / replaceIllegalCharacters / replacePart / replaceTrailingBrackets
 * - 清洗规范化：clean / space / colon / slash / acronym / asciiQuotes / truncate /
 *   validateFileName / ascii / transliterate（占位：无 ICU，非 ASCII 转 '?'）
 * - 路径操作：div / plus / mod / getRoot / getTail / head / tail / listPath /
 *   getRelativePathTail / toFile（远程路径字符串，用 / 分隔）
 * - 排序命名：sortName / sortInitial / initialName
 * - 列表：joining / joiningDistinct / bounds
 * - 日期：format / parseDate / toDate / zone
 * - 其他：isLatin
 *
 * 未实现（与本项目不兼容，详见各分组原因）：
 *   match(Map) 的 Map 字面量参数也需扩展模板参数解析器，暂未支持
 * - StructuredFile 结构化抽象（区分 A-Z/季/剧集文件夹层 + 文件名 + 字幕语言 + 扩展名，
 *   本项目输出纯相对路径字符串，无对应模型）：derive / deriveFolder / multiply /
 *   power / leftShift / rightShift / xor / bitwiseNegate。mod 的 StructuredFile
 *   版用例已由 mod(File) + 扩展名前插入覆盖
 * - 需查询真实文件系统可用空间（WebDAV/OpenList 无对应接口）：getDiskSpace
 * - 低优先级（无明确重命名模板用例）：compareTo / toLocale / negative / getGraphemeClusters
 */
object PipeModifiers {

    /** 应用单个修饰符到值。 */
    @OptIn(kotlin.ExperimentalStdlibApi::class)
    fun apply(value: Any?, modifier: String): Any? {
        if (value == null) return null
        val name = modifier.substringBefore('(').trim()
        val argsRaw = if ('(' in modifier) modifier.substringAfter('(').removeSuffix(")") else ""
        val args = parseArgs(argsRaw)
        return when (name) {
            // ---- 大小写 ----
            "upper" -> {
                val p = args.getOrNull(0)
                if (p == null) value.toStr().uppercase()
                else replaceMatches(value.toStr(), p) { it.uppercase() } ?: value
            }
            "lower" -> {
                val p = args.getOrNull(0)
                if (p == null) value.toStr().lowercase()
                else replaceMatches(value.toStr(), p) { it.lowercase() } ?: value
            }
            // 逐词首字母大写（"The Day a new Demon was born" → "The Day A New Demon Was Born"）
            "upperInitial" -> value.toStr().replace(Regex("\\b\\p{L}")) { it.value.uppercase() }
            // 尾随全大写词转首字母大写（"Gundam SEED" → "Gundam Seed"）
            "lowerTrail" -> value.toStr().replace(Regex("""(\p{Lu}{2,})(\s*)$""")) { m ->
                m.groupValues[1].first() + m.groupValues[1].drop(1).lowercase() + m.groupValues[2]
            }

            // ---- 数字 ----
            // 补齐串内每个数字段（"1x01" pad(2,3) → "01x001"）；单长度时全部数字段通用
            "pad" -> {
                val lengths = args.mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(2) }
                var idx = 0
                Regex("\\d+").replace(value.toStr()) { m ->
                    val n = lengths.getOrElse(idx) { lengths.last() }
                    idx++
                    m.value.padStart(n, '0')
                }
            }
            // 四舍五入到指定精度（round(0) 输出整数）
            "round" -> {
                val n = args.getOrNull(0)?.toIntOrNull() ?: 0
                val bd = value.toStr().toBigDecimalOrNull() ?: return value
                val r = bd.setScale(n, RoundingMode.HALF_UP)
                if (r.compareTo(BigDecimal.ZERO) == 0) "0" else r.stripTrailingZeros().toPlainString()
            }
            // 串内 1..12 的数字替换为罗马数字（"Star Wars: Episode 4" → "Star Wars: Episode IV"）
            "roman" -> Regex("""\b(\d{1,2})\b""").replace(value.toStr()) { m ->
                val num = m.groupValues[1].toIntOrNull()
                if (num != null && num in 1..12) toRoman(num) else m.value
            }

            // ---- 模式匹配与提取 ----
            // 忽略大小写匹配，找不到返回 null（unwind）；可选分组参数
            "match" -> {
                val p = args.getOrNull(0) ?: return value
                val gArg = args.getOrNull(1)
                try {
                    val result = Regex(p, RegexOption.IGNORE_CASE).find(value.toStr())
                    when {
                        result == null -> null
                        gArg != null -> result.groupValues.getOrNull(gArg.toIntOrNull() ?: 0)?.ifEmpty { null }
                        result.groupValues.size > 1 -> (1 until result.groupValues.size)
                            .firstOrNull { result.groupValues[it].isNotEmpty() }
                            ?.let { result.groupValues[it] }
                        else -> result.groupValues.getOrNull(0)?.ifEmpty { null }
                    }
                } catch (e: PatternSyntaxException) {
                    null
                }
            }
            "matchAll" -> {
                val p = args.getOrNull(0) ?: return value
                val gArg = args.getOrNull(1)
                try {
                    val regex = Regex(p, RegexOption.IGNORE_CASE)
                    if (gArg != null) {
                        val g = gArg.toIntOrNull() ?: 0
                        regex.findAll(value.toStr()).map { it.groupValues.getOrNull(g) ?: "" }.toList()
                    } else {
                        regex.findAll(value.toStr()).mapNotNull { result ->
                            if (result.groupValues.size > 1) {
                                (1 until result.groupValues.size)
                                    .firstOrNull { result.groupValues[it].isNotEmpty() }
                                    ?.let { result.groupValues[it] }
                            } else {
                                result.groupValues.getOrNull(0)
                            }
                        }.toList()
                    }
                } catch (e: PatternSyntaxException) {
                    emptyList<String>()
                }
            }
            // 匹配圆/方/花括号包裹的内容列表
            "matchBrackets" -> BRACKET_CONTENT.findAll(value.toStr())
                .map { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "" }
                .toList()
            // 模式之前的子串（找不到返回原值）
            "before" -> {
                val p = args.getOrNull(0) ?: return value
                try {
                    Regex("^(.*?)$p", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                        .find(value.toStr())?.groupValues?.get(1)?.trim() ?: value.toStr()
                } catch (e: PatternSyntaxException) {
                    value.toStr().substringBefore(p)
                }
            }
            // 模式之后的子串（找不到返回原值）
            "after" -> {
                val p = args.getOrNull(0) ?: return value
                try {
                    Regex("$p(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                        .find(value.toStr())?.groupValues?.get(1)?.trim() ?: value.toStr()
                } catch (e: PatternSyntaxException) {
                    value.toStr().substringAfter(p)
                }
            }

            // ---- 替换与移除 ----
            "replace" -> {
                val a = args.getOrNull(0) ?: return value
                val b = args.getOrNull(1) ?: ""
                value.toStr().replace(a, b)
            }
            "replaceAll" -> {
                // B10: 用户模板中的 pattern 可能是非法正则，构造失败时原样返回 value 而非崩溃。
                val a = args.getOrNull(0) ?: return value
                val b = args.getOrNull(1) ?: ""
                try {
                    value.toStr().replace(Regex(a), b)
                } catch (e: PatternSyntaxException) {
                    value
                }
            }
            "removeAll" -> {
                val p = args.getOrNull(0) ?: return value
                try {
                    value.toStr().replace(Regex(p), "")
                } catch (e: PatternSyntaxException) {
                    value
                }
            }
            // 移除指定字符（可多个）
            "remove" -> {
                var s = value.toStr()
                args.forEach { s = s.replace(it, "") }
                s
            }
            // 移除所有括号组（含内容），并折叠多余空格
            "removeBrackets" -> value.toStr()
                .replace(BRACKET_GROUP, "")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
            // 移除 Windows 非法字符
            "removeIllegalCharacters" -> value.toStr().replace(ILLEGAL_CHARS, "")
            //Windows 非法字符替换为相似 Unicode 字符（: → ∶，? → ？，...）
            "replaceIllegalCharacters" -> buildString {
                for (c in value.toStr()) {
                    val mapped = ILLEGAL_LOOKALIKE[c]
                    when {
                        mapped != null -> append(mapped)
                        c.isISOControl() -> append(' ')
                        else -> append(c)
                    }
                }
            }
            // 尾部圆括号（可带 $1 引用内容）替换（"The IT Crowd (UK)" → "The IT Crowd"）
            "replaceTrailingBrackets" -> {
                val rep = args.getOrNull(0) ?: ""
                val m = Regex("""\s*\(([^()]*)\)\s*$""").find(value.toStr())
                if (m == null) value.toStr()
                else value.toStr().replace(m.value, rep.replace("\$1", m.groupValues[1])).trim()
            }
            // 尾部部分编号替换（"Today Is the Day (1)" → "Today Is the Day, Part 1"）
            "replacePart" -> {
                val rep = args.getOrNull(0) ?: ", Part \$1"
                val s = value.toStr()
                val m = Regex("""\s*[(](\w{1,3})[)]$""").find(s)
                    ?: Regex("""\W+Part (\w+)\W*$""").find(s)
                if (m == null) s
                else s.replace(m.value, rep.replace("\$1", m.groupValues[1])).trim()
            }

            // ---- 清洗与规范化 ----
            // clean()：剥离括号组（含内容）+ 分隔符归一 + 移除非法字符 + 折叠空白
            // 语义：Strip brackets and other clutter patterns.
            // e.g. [ONe]_Ano_Hana_01_(1280x720) ➔ Ano Hana 01
            "clean" -> {
                var s = value.toStr()
                s = s.replace(BRACKET_GROUP, " ")   // 剥离 [...] (...) {...} 含内容，用空格占位避免相邻词粘连
                s = s.replace(Regex("[._]"), " ")   // 场景常见分隔符 _ 与 . → 空格
                s = s.replace(ILLEGAL_CHARS, "")     // 移除 Windows 非法文件名字符（: * ? 等）
                s.replace(Regex("\\s+"), " ").trim()
            }
            // space(c) 把空格替换为 c（完整字符串）；space('') 删除所有空格
            "space" -> {
                val arg = args.getOrNull(0) ?: " "
                if (arg.isEmpty()) value.toStr().replace(" ", "")
                else value.toStr().replace(" ", arg)
            }
            // 智能冒号替换（"Sissi: The Young Empress" colon('-') → "Sissi - The Young Empress"）
            "colon" -> {
                // 支持 0/1/2 参：colon() / colon(rep) / colon(rep, ratioRep)
                // 2 参版先替换比率冒号（16:9 → 16x9），再替换剩余冒号
                val colonRep = args.getOrNull(0) ?: "-"
                val ratioRep = args.getOrNull(1)
                var s = value.toStr()
                if (ratioRep != null) {
                    s = s.replace(Regex("\\d+:\\d+")) { it.value.replace(":", ratioRep) }
                }
                s.replace(Regex("\\s*:\\s+"), " $colonRep ")
                    .replace(Regex("\\s*:"), colonRep)
            }
            "slash" -> {
                val rep = args.getOrNull(0) ?: "-"
                value.toStr().replace("/", rep)
            }
            "acronym" -> value.toStr().split(Regex("[^A-Za-z0-9]"))
                .filter { it.isNotEmpty() }
                .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
                .joinToString("")
            // 弯引号转 ASCII 直引号
            "asciiQuotes" -> value.toStr().map {
                when (it) {
                    '“', '”', '„', '‟', '«', '»' -> '"'
                    '‘', '’', '‚', '‛', '‹', '›' -> '\''
                    else -> it
                }
            }.joinToString("")
            // 截断到限制长度（尾部空白/标点一并去除）
            // 1 参版：硬截断；2 参版：在 hardLimit 内找最后非词边界软截断
            "truncate" -> {
                val hardLimit = args.getOrNull(0)?.toIntOrNull() ?: return value
                val nonWordPattern = args.getOrNull(1)
                val s = value.toStr()
                val hardTruncate = {
                    s.take(hardLimit).replace(Regex("""[\s\p{Punct}]+$"""), "").trimEnd()
                }
                if (s.length <= hardLimit) {
                    s
                } else if (nonWordPattern != null) {
                    var softLimit = 0
                    try {
                        val regex = Regex(nonWordPattern, RegexOption.IGNORE_CASE)
                        for (m in regex.findAll(s)) {
                            if (m.range.first > hardLimit) break
                            softLimit = m.range.first
                        }
                    } catch (e: PatternSyntaxException) {
                        softLimit = 0
                    }
                    // P2 修复：原实现 softLimit 无匹配时保持 0，s.take(0) 输出空串——
                    // 模式串在 hardLimit 内无任何匹配（或全文无匹配）时整段文件名丢失。
                    // 修复：无软边界（或模式非法）时回退到硬截断，保证始终有输出。
                    if (softLimit > 0) {
                        s.take(softLimit).replace(Regex("""[\s\p{Punct}]+$"""), "").trimEnd()
                    } else {
                        hardTruncate()
                    }
                } else {
                    hardTruncate()
                }
            }
            // 去除非法字符与首尾点（".hack" → "hack"）
            "validateFileName" -> value.toStr()
                .replace(ILLEGAL_CHARS, "")
                .trim()
                .trimStart('.')
                .trimEnd('.', ' ')
            // Unicode 转 ASCII：NFD 去音符（é → e），剩余非 ASCII 转 '?'（无 ICU，无法音译）
            "ascii" -> Normalizer.normalize(value.toStr(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .map { if (it.code < 128) it else '?' }
                .joinToString("")
            // 占位实现：无 ICU 依赖，非 ASCII 转 '?'
            "transliterate" -> value.toStr().map { if (it.code < 128) it else '?' }.joinToString("")

            // ---- 排序与命名 ----
            // 无参：冠词移末尾；1 参：自定义模板 $1=冠词 $2=剩余名（保护尾部年份括号）
            "sortName" -> {
                val rep = args.getOrNull(0)
                if (rep == null) sortName(value.toStr())
                else sortNameWithReplacement(value.toStr(), rep)
            }
            // 排序首字母（"The Matrix" → "M"）
            "sortInitial" -> sortName(value.toStr()).firstOrNull()?.toString()?.uppercase() ?: ""
            // 仅首名缩写（"James Cameron" → "J. Cameron"）
            "initialName" -> {
                val parts = value.toStr().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (parts.size <= 1) value.toStr()
                else parts.dropLast(1).joinToString(" ") { "${it.first()}." } + " " + parts.last()
            }

            // ---- 列表 ----
            "joining" -> {
                val sep = args.getOrNull(0) ?: ","
                val prefix = args.getOrNull(1) ?: ""
                val suffix = args.getOrNull(2) ?: ""
                when (value) {
                    is List<*> -> "$prefix${value.joinToString(sep)}$suffix"
                    else -> value.toStr()
                }
            }
            // 去重后连接
            "joiningDistinct" -> {
                val sep = args.getOrNull(0) ?: ","
                val prefix = args.getOrNull(1) ?: ""
                val suffix = args.getOrNull(2) ?: ""
                when (value) {
                    is List<*> -> "$prefix${value.distinct().joinToString(sep)}$suffix"
                    else -> value.toStr()
                }
            }
            // 取列表首尾边界（{sy|bounds|joining('-')} → "2002-2003"）
            "bounds" -> when (value) {
                is List<*> -> when {
                    value.isEmpty() -> null
                    value.size == 1 -> listOf(value.first())
                    else -> listOf(value.first(), value.last())
                }
                else -> value
            }

            // ---- 路径操作（远程路径字符串，用 / 分隔；参照 ExpressionFormatMethods 源码语义）----
            // div(path...)：路径拼接 a/b/c；支持多参数
            "div" -> {
                val s = value.toStr()
                val parts = args.filter { it.isNotEmpty() }
                if (parts.isEmpty()) s else (listOf(s) + parts).joinToString("/")
            }
            // plus(suffix)：字符串拼接（concat，非路径分隔）；File+String 语义
            "plus" -> {
                val arg = args.getOrNull(0) ?: return value
                value.toStr() + arg
            }
            // mod(suffix)：扩展名前插入后缀，保持父目录
            // "Avatar (2009).mp4"|mod(' [720p]') ➔ "Avatar (2009) [720p].mp4"
            "mod" -> {
                val suffix = args.getOrNull(0) ?: return value
                val s = value.toStr()
                val slash = s.lastIndexOf('/')
                val name = if (slash >= 0) s.substring(slash + 1) else s
                val parent = if (slash >= 0) s.substring(0, slash + 1) else ""
                val dot = name.lastIndexOf('.')
                if (dot > 0) "$parent${name.substring(0, dot)}$suffix${name.substring(dot)}"
                else "$parent$name$suffix"
            }
            // getRoot()：路径第一段（相对路径首段；绝对路径返回 "/"）
            "getRoot" -> {
                val s = value.toStr()
                if (s.startsWith("/")) "/" else pathSegments(s).firstOrNull() ?: ""
            }
            // getTail()：去掉第一段（根）后的路径
            "getTail" -> {
                val segs = pathSegments(value.toStr())
                when {
                    segs.isEmpty() -> ""
                    segs.size == 1 -> ""
                    else -> segs.drop(1).joinToString("/")
                }
            }
            // head(n)：第 n 段（1-indexed）；段数不足返回原值
            "head" -> {
                val n = args.getOrNull(0)?.toIntOrNull() ?: return value
                if (n < 1) null else {
                    val segs = pathSegments(value.toStr())
                    if (segs.size < n) value.toStr() else segs[n - 1]
                }
            }
            // tail(n)：后 n 段拼接路径；段数不足返回原值
            "tail" -> {
                val n = args.getOrNull(0)?.toIntOrNull() ?: return value
                if (n < 1) null else {
                    val segs = pathSegments(value.toStr())
                    if (segs.size < n) value.toStr() else segs.takeLast(n).joinToString("/")
                }
            }
            // listPath()：所有段列表；listPath(n)：后 n 段列表
            "listPath" -> {
                val segs = pathSegments(value.toStr())
                val n = args.getOrNull(0)?.toIntOrNull()
                if (n != null) segs.takeLast(n) else segs
            }
            // getRelativePathTail(n)：后 n 段拼接路径；总段数 ≤ n 返回整个路径
            "getRelativePathTail" -> {
                val n = args.getOrNull(0)?.toIntOrNull() ?: return value
                if (n < 1) "" else {
                    val segs = pathSegments(value.toStr())
                    if (segs.size <= n) value.toStr() else segs.takeLast(n).joinToString("/")
                }
            }
            // toFile()：规范化路径（合并多余分隔符）；toFile(parent)：非绝对则拼接 parent/self
            "toFile" -> {
                val s = value.toStr()
                if (s.isEmpty()) null else {
                    val parent = args.getOrNull(0)
                    val normed = s.replace(Regex("/{2,}"), "/").trimEnd('/')
                    if (!parent.isNullOrEmpty() && !normed.startsWith("/")) "$parent/$normed" else normed
                }
            }

            // ---- 日期 ----
            // {airdate|format('yyyy.MM.dd')} → 2023.01.15
            // 默认模式 yyyy-MM-dd；支持 ISO 日期 / ISO 日期时间 / 本地日期时间
            "format" -> formatDate(value.toStr(), args.getOrNull(0) ?: "yyyy-MM-dd")
            // 按模式解析日期，输出 ISO yyyy-MM-dd（可再接 format）
            "parseDate" -> parseDate(value.toStr(), args.getOrNull(0))
            //epoch 秒/毫秒 → ISO 本地日期时间
            "toDate" -> toDate(value.toStr())
            // 转换到指定时区：输入可为 epoch（秒/毫秒）/ ISO 日期时间 / ISO 日期；
            // 输出 ISO 带时区日期时间（可再接 format）。无效 zone 原样返回（容错）。
            // {info.ts|zone('Asia/Shanghai')|format('yyyy-MM-dd')}
            "zone" -> toZonedZone(value.toStr(), args.getOrNull(0) ?: return value)

            // ---- 其他 ----
            // 是否全部为拉丁字符
            "isLatin" -> value.toStr().none { it.isLetter() && !isLatinChar(it) }

            else -> value // 未知修饰符：原样返回（容错）
        }
    }

    private fun Any.toStr(): String = when (this) {
        is List<*> -> joinToString(",")
        is Boolean -> if (this) "true" else "false"
        else -> toString()
    }

    /** Windows 非法文件名字符（含控制字符与反引号）。 */
    private val ILLEGAL_CHARS = Regex("[\\\\/:*?\"<>|`\\t\\r\\n\\f\\u0000]")

    /** 非法字符 → 相似 Unicode 全角字符。 */
    private val ILLEGAL_LOOKALIKE = mapOf(
        '\\' to '＼', '/' to '／', ':' to '∶', '*' to '＊', '?' to '？',
        '"' to '＂', '<' to '＜', '>' to '＞', '|' to '｜',
        '`' to '‵', '\t' to ' ', '\r' to ' ', '\n' to ' ', '\u000C' to ' ', '\u0000' to ' ',
    )

    /** 括号组（含内容）：圆/方/花括号，非嵌套。 */
    private val BRACKET_GROUP = Regex("""\s*[\(\[\{][^\)\]\}]*[\)\]\}]""")

    /** 括号内容提取：分别捕获圆/方/花括号内部。 */
    private val BRACKET_CONTENT = Regex("""\(([^()]*)\)|\[([^\]\[]*)\]|\{([^{}]*)\}""")

    /** 路径分段：按 / 拆分，过滤空段（兼容首尾 / 和多余分隔符）。 */
    private fun pathSegments(path: String): List<String> =
        path.split('/').filter { it.isNotEmpty() }

    /** 对 pattern 命中的每段应用变换；非法正则返回 null。 */
    private inline fun replaceMatches(s: String, pattern: String, crossinline transform: (String) -> String): String? =
        try {
            Regex(pattern).replace(s) { transform(it.value) }
        } catch (e: PatternSyntaxException) {
            null
        }

    /**
     * 解析修饰符参数列表（按逗号拆分，去除单/双引号包裹）。
     * 支持常见格式：space('_') / replaceAll(' ', '') / colon("-") 等。
     *
     * B14 修复：支持引号内转义引号（`\'` → `'`、`\"` → `"`），
     * 避免 `replaceAll('it\'s', 'ok')` 被误拆为 `it\` + `s', 'ok'`。
     * 注意：反斜杠仅在后跟同类型引号时才视为转义，其他情况（如正则 `\d`）原样保留。
     */
    private fun parseArgs(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        // 跟踪引号状态：引号内的逗号不拆分，避免 replaceAll(',', '.') 等被误拆
        // B14：反斜杠后跟同类型引号视为转义引号，不计为引号结束。其他反斜杠（如正则 \d）原样保留。
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (quote != null && c == '\\' && i + 1 < raw.length && raw[i + 1] == quote) {
                // B14：引号内 `\<quote>` → 保留反斜杠 + 引号字符（去引号时再反转义）
                current.append(c).append(raw[i + 1])
                i += 2
                continue
            }
            when {
                quote != null -> {
                    current.append(c)
                    if (c == quote) quote = null
                }
                c == '\'' || c == '"' -> {
                    quote = c
                    current.append(c)
                }
                c == ',' -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString())
        return result.map { arg ->
            val t = arg.trim()
            // B14：去引号时处理转义引号
            when {
                t.length >= 2 && t.startsWith("'") && t.endsWith("'") ->
                    unescapeQuotes(t.substring(1, t.length - 1))
                t.length >= 2 && t.startsWith("\"") && t.endsWith("\"") ->
                    unescapeQuotes(t.substring(1, t.length - 1))
                else -> t
            }
        }
    }

    /** B14：反转义 `\'` → `'`、`\"` → `"`。其他反斜杠组合（如 `\d`）原样保留。 */
    private fun unescapeQuotes(s: String): String =
        s.replace("\\'", "'").replace("\\\"", "\"")

    /** 去冠词排序名：The Walking Dead -> Walking Dead, The。 */
    private fun sortName(s: String): String {
        for (a in listOf("The ", "A ", "An ")) {
            if (s.startsWith(a, ignoreCase = true)) {
                return s.substring(a.length) + ", " + s.substring(0, a.length - 1)
            }
        }
        return s
    }

    /** 自定义模板排序名：$1=冠词 $2=剩余名，保护尾部年份括号 (1956)。 */
    private fun sortNameWithReplacement(s: String, replacement: String): String {
        val yearMatch = Regex("""\s+[(]\w+[)]$""").find(s)
        val region = if (yearMatch != null) s.substring(0, yearMatch.range.first) else s
        val tail = if (yearMatch != null) s.substring(yearMatch.range.first) else ""
        val nameMatch = Regex("""^(The|A|An)\s+(.+)""", RegexOption.IGNORE_CASE).find(region)
        return if (nameMatch != null) {
            val article = nameMatch.groupValues[1]
            val rest = nameMatch.groupValues[2]
            val replaced = region.replace(
                nameMatch.value,
                replacement.replace("\$1", article).replace("\$2", rest),
            )
            (replaced + tail).trim()
        } else {
            s
        }
    }

    private fun isLatinChar(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.BASIC_LATIN || block.toString().startsWith("LATIN")
    }

    private val romanMap = linkedMapOf(
        1000 to "M", 900 to "CM", 500 to "D", 400 to "CD",
        100 to "C", 90 to "XC", 50 to "L", 40 to "XL",
        10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I",
    )

    private fun toRoman(n: Int): String {
        if (n <= 0) return n.toString()
        val sb = StringBuilder()
        var x = n
        for ((v, sym) in romanMap) {
            while (x >= v) { sb.append(sym); x -= v }
        }
        return sb.toString()
    }

    /**
     * 日期格式化。
     *
     * 支持输入：
     * - ISO 日期 `2023-01-15`
     * - ISO 本地日期时间 `2023-01-15T10:30:00`
     * - ISO 日期时间 `2023-01-15T10:30:00Z` / `2023-01-15T10:30:00+02:00`
     *
     * 解析失败或模式串非法时原样返回 value（容错，不崩溃）。
     */
    private fun formatDate(value: String, pattern: String): String {
        val temporal = tryParseLocalDate(value)
            ?: tryParseLocalDateTime(value)
            ?: tryParseZonedDateTime(value)
            ?: return value
        return try {
            DateTimeFormatter.ofPattern(pattern).format(temporal)
        } catch (e: IllegalArgumentException) {
            value
        }
    }

    /**
     * 日期解析：按给定模式（缺省自动尝试 ISO）解析，
     * 成功输出 ISO `yyyy-MM-dd`，失败原样返回（容错）。
     */
    private fun parseDate(value: String, pattern: String?): String {
        if (pattern != null) {
            val fmt = try {
                DateTimeFormatter.ofPattern(pattern)
            } catch (e: IllegalArgumentException) {
                return value
            }
            runCatching { LocalDate.parse(value, fmt) }.getOrNull()?.let { return it.toString() }
            runCatching { LocalDateTime.parse(value, fmt) }.getOrNull()?.let { return it.toLocalDate().toString() }
            return value
        }
        tryParseLocalDate(value)?.let { return it.toString() }
        tryParseLocalDateTime(value)?.let { return it.toLocalDate().toString() }
        tryParseZonedDateTime(value)?.let { return it.toLocalDate().toString() }
        return value
    }

    /** epoch 秒（< 1e11）或毫秒 → ISO 本地日期时间字符串；非数字原样返回。 */
    private fun toDate(value: String): String {
        val epoch = value.trim().toLongOrNull() ?: return value
        val millis = if (kotlin.math.abs(epoch) < 100_000_000_000L) epoch * 1000 else epoch
        return runCatching {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        }.getOrDefault(value)
    }

    /**
     * 把日期/时间/epoch 转换到指定时区，输出 ISO 带时区日期时间。
     *
     * 输入兼容：epoch（秒/毫秒）、ISO 日期时间（带时区）、本地日期时间、ISO 日期。
     * 无效 zone 或无法解析时原样返回（容错，不崩溃）。
     */
    private fun toZonedZone(value: String, zone: String): String {
        val zoneId = runCatching { ZoneId.of(zone) }.getOrNull() ?: return value
        val epoch = value.trim().toLongOrNull()
        if (epoch != null) {
            val millis = if (kotlin.math.abs(epoch) < 100_000_000_000L) epoch * 1000 else epoch
            return runCatching {
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), zoneId).toString()
            }.getOrDefault(value)
        }
        tryParseZonedDateTime(value)?.let { return it.withZoneSameInstant(zoneId).toString() }
        tryParseLocalDateTime(value)?.let { return it.atZone(zoneId).toString() }
        tryParseLocalDate(value)?.let { return it.atStartOfDay(zoneId).toString() }
        return value
    }

    private fun tryParseLocalDate(value: String): LocalDate? =
        runCatching { LocalDate.parse(value) }.getOrNull()

    private fun tryParseLocalDateTime(value: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(value) }.getOrNull()

    private fun tryParseZonedDateTime(value: String): ZonedDateTime? =
        runCatching { ZonedDateTime.parse(value) }.getOrNull()
}
