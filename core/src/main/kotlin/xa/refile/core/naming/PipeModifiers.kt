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
 * 管道修饰符（对齐 FileBot ExpressionFormatMethods，全部自实现，可链式 `{n|upper|space('.')}`）。
 *
 * 与 FileBot 的对应关系：
 * - 大小写：lower / upper / upperInitial / lowerTrail（含可选 pattern 参数）
 * - 数字：pad / round / roman
 * - 模式匹配：match / matchAll / matchBrackets / before / after
 * - 替换移除：replace / replaceAll / removeAll / remove / removeBrackets /
 *   removeIllegalCharacters / replaceIllegalCharacters / replacePart / replaceTrailingBrackets
 * - 清洗规范化：clean / space / colon / slash / acronym / asciiQuotes / truncate /
 *   validateFileName / ascii / transliterate（占位：无 ICU，非 ASCII 转 '?'）
 * - 排序命名：sortName / sortInitial / initialName
 * - 列表：joining / joiningDistinct / bounds
 * - 日期：format / parseDate / toDate
 * - 其他：isLatin
 *
 * 未实现（Groovy 闭包 / 本地 File 路径 / StructuredFile / ICU 依赖，不适用本项目）：
 * check / match(Map) / div / plus / mod / getRoot / getTail / head / listPath / getDiskSpace /
 * toFile / derive / multiply / power / leftShift / rightShift / xor / zone / compareTo /
 * toLocale / negative / getGraphemeClusters。
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
            // FileBot：逐词首字母大写（"The Day a new Demon was born" → "The Day A New Demon Was Born"）
            "upperInitial" -> value.toStr().replace(Regex("\\b\\p{L}")) { it.value.uppercase() }
            // FileBot：尾随全大写词转首字母大写（"Gundam SEED" → "Gundam Seed"）
            "lowerTrail" -> value.toStr().replace(Regex("""(\p{Lu}{2,})(\s*)$""")) { m ->
                m.groupValues[1].first() + m.groupValues[1].drop(1).lowercase() + m.groupValues[2]
            }

            // ---- 数字 ----
            // FileBot：补齐串内每个数字段（"1x01" pad(2,3) → "01x001"）；单长度时全部数字段通用
            "pad" -> {
                val lengths = args.mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(2) }
                var idx = 0
                Regex("\\d+").replace(value.toStr()) { m ->
                    val n = lengths.getOrElse(idx) { lengths.last() }
                    idx++
                    m.value.padStart(n, '0')
                }
            }
            // FileBot：四舍五入到指定精度（round(0) 输出整数）
            "round" -> {
                val n = args.getOrNull(0)?.toIntOrNull() ?: 0
                val bd = value.toStr().toBigDecimalOrNull() ?: return value
                val r = bd.setScale(n, RoundingMode.HALF_UP)
                if (r.compareTo(BigDecimal.ZERO) == 0) "0" else r.stripTrailingZeros().toPlainString()
            }
            // FileBot：串内 1..12 的数字替换为罗马数字（"Star Wars: Episode 4" → "Star Wars: Episode IV"）
            "roman" -> Regex("""\b(\d{1,2})\b""").replace(value.toStr()) { m ->
                val num = m.groupValues[1].toIntOrNull()
                if (num != null && num in 1..12) toRoman(num) else m.value
            }

            // ---- 模式匹配与提取 ----
            // FileBot：忽略大小写匹配，找不到返回 null（unwind）；可选分组参数
            "match" -> {
                val p = args.getOrNull(0) ?: return value
                val g = args.getOrNull(1)?.toIntOrNull() ?: 0
                try {
                    Regex(p, RegexOption.IGNORE_CASE).find(value.toStr())
                        ?.groupValues?.getOrNull(g)?.ifEmpty { null }
                } catch (e: PatternSyntaxException) {
                    null
                }
            }
            "matchAll" -> {
                val p = args.getOrNull(0) ?: return value
                val g = args.getOrNull(1)?.toIntOrNull() ?: 0
                try {
                    Regex(p, RegexOption.IGNORE_CASE).findAll(value.toStr())
                        .map { it.groupValues.getOrNull(g) ?: "" }.toList()
                } catch (e: PatternSyntaxException) {
                    emptyList<String>()
                }
            }
            // FileBot：匹配圆/方/花括号包裹的内容列表
            "matchBrackets" -> BRACKET_CONTENT.findAll(value.toStr())
                .map { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "" }
                .toList()
            // FileBot：模式之前的子串（找不到返回原值）
            "before" -> {
                val p = args.getOrNull(0) ?: return value
                try {
                    Regex("^(.*?)$p", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                        .find(value.toStr())?.groupValues?.get(1) ?: value.toStr()
                } catch (e: PatternSyntaxException) {
                    value.toStr().substringBefore(p)
                }
            }
            // FileBot：模式之后的子串（找不到返回原值）
            "after" -> {
                val p = args.getOrNull(0) ?: return value
                try {
                    Regex("$p(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                        .find(value.toStr())?.groupValues?.get(1) ?: value.toStr()
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
            // FileBot：移除指定字符（可多个）
            "remove" -> {
                var s = value.toStr()
                args.forEach { s = s.replace(it, "") }
                s
            }
            // FileBot：移除所有括号组（含内容），并折叠多余空格
            "removeBrackets" -> value.toStr()
                .replace(BRACKET_GROUP, "")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
            // FileBot：移除 Windows 非法字符
            "removeIllegalCharacters" -> value.toStr().replace(ILLEGAL_CHARS, "")
            // FileBot：Windows 非法字符替换为相似 Unicode 字符（: → ∶，? → ？，...）
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
            // FileBot：尾部圆括号（可带 $1 引用内容）替换（"The IT Crowd (UK)" → "The IT Crowd"）
            "replaceTrailingBrackets" -> {
                val rep = args.getOrNull(0) ?: ""
                val m = Regex("""\s*\(([^()]*)\)\s*$""").find(value.toStr())
                if (m == null) value.toStr()
                else value.toStr().replace(m.value, rep.replace("\$1", m.groupValues[1])).trim()
            }
            // FileBot：尾部部分编号替换（"Today Is the Day (1)" → "Today Is the Day, Part 1"）
            "replacePart" -> {
                val rep = args.getOrNull(0) ?: ", Part \$1"
                val m = Regex("""\s*\((\d{1,2})\)\s*$""").find(value.toStr())
                if (m == null) value.toStr()
                else value.toStr().replace(m.value, rep.replace("\$1", m.groupValues[1])).trim()
            }

            // ---- 清洗与规范化 ----
            "clean" -> value.toStr().replace(ILLEGAL_CHARS, "").trim()
            // space(c) 把空格替换为 c（完整字符串）；space('') 删除所有空格
            "space" -> {
                val arg = args.getOrNull(0) ?: " "
                if (arg.isEmpty()) value.toStr().replace(" ", "")
                else value.toStr().replace(" ", arg)
            }
            // FileBot：智能冒号替换（"Sissi: The Young Empress" colon('-') → "Sissi - The Young Empress"）
            "colon" -> {
                val rep = args.getOrNull(0) ?: "-"
                value.toStr()
                    .replace(Regex("\\s*:\\s+"), " $rep ")
                    .replace(Regex("\\s*:"), rep)
            }
            "slash" -> {
                val rep = args.getOrNull(0) ?: "-"
                value.toStr().replace("/", rep)
            }
            "acronym" -> value.toStr().split(Regex("[^A-Za-z0-9]"))
                .filter { it.isNotEmpty() }
                .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
                .joinToString("")
            // FileBot：弯引号转 ASCII 直引号
            "asciiQuotes" -> value.toStr().map {
                when (it) {
                    '“', '”', '„', '‟', '«', '»' -> '"'
                    '‘', '’', '‚', '‛', '‹', '›' -> '\''
                    else -> it
                }
            }.joinToString("")
            // FileBot：截断到限制长度（尾部空白/标点一并去除）
            "truncate" -> {
                val limit = args.getOrNull(0)?.toIntOrNull() ?: return value
                val s = value.toStr()
                if (s.length <= limit) s
                else s.take(limit).replace(Regex("""[\s\p{Punct}]+$"""), "").trimEnd()
            }
            // FileBot：去除非法字符与首尾点（".hack" → "hack"）
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
            "sortName" -> sortName(value.toStr()) // 去冠词排序名
            // FileBot：排序首字母（"The Matrix" → "M"）
            "sortInitial" -> sortName(value.toStr()).firstOrNull()?.toString()?.uppercase() ?: ""
            // FileBot：仅首名缩写（"James Cameron" → "J. Cameron"）
            "initialName" -> value.toStr().replace(Regex("""^(\p{L})\p{L}*(\s+.*)$"""), "$1.$2")

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
            // FileBot：去重后连接
            "joiningDistinct" -> {
                val sep = args.getOrNull(0) ?: ","
                val prefix = args.getOrNull(1) ?: ""
                val suffix = args.getOrNull(2) ?: ""
                when (value) {
                    is List<*> -> "$prefix${value.distinct().joinToString(sep)}$suffix"
                    else -> value.toStr()
                }
            }
            // FileBot：取列表首尾边界（{sy|bounds|joining('-')} → "2002-2003"）
            "bounds" -> when (value) {
                is List<*> -> when {
                    value.isEmpty() -> null
                    value.size == 1 -> listOf(value.first())
                    else -> listOf(value.first(), value.last())
                }
                else -> value
            }

            // ---- 日期 ----
            // FileBot：{airdate|format('yyyy.MM.dd')} → 2023.01.15
            // 默认模式 yyyy-MM-dd；支持 ISO 日期 / ISO 日期时间 / 本地日期时间
            "format" -> formatDate(value.toStr(), args.getOrNull(0) ?: "yyyy-MM-dd")
            // FileBot：按模式解析日期，输出 ISO yyyy-MM-dd（可再接 format）
            "parseDate" -> parseDate(value.toStr(), args.getOrNull(0))
            // FileBot：epoch 秒/毫秒 → ISO 本地日期时间
            "toDate" -> toDate(value.toStr())

            // ---- 其他 ----
            // FileBot：是否全部为拉丁字符
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

    /** 非法字符 → 相似 Unicode 全角字符（FileBot replaceIllegalCharacters）。 */
    private val ILLEGAL_LOOKALIKE = mapOf(
        '\\' to '＼', '/' to '／', ':' to '∶', '*' to '＊', '?' to '？',
        '"' to '＂', '<' to '＜', '>' to '＞', '|' to '｜',
        '`' to '‵', '\t' to ' ', '\r' to ' ', '\n' to ' ', '\u000C' to ' ', '\u0000' to ' ',
    )

    /** 括号组（含内容）：圆/方/花括号，非嵌套。 */
    private val BRACKET_GROUP = Regex("""\s*[\(\[\{][^\)\]\}]*[\)\]\}]""")

    /** 括号内容提取：分别捕获圆/方/花括号内部。 */
    private val BRACKET_CONTENT = Regex("""\(([^()]*)\)|\[([^\]\[]*)\]|\{([^{}]*)\}""")

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
     */
    private fun parseArgs(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        // 跟踪引号状态：引号内的逗号不拆分，避免 replaceAll(',', '.') 等被误拆
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (c in raw) {
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
        }
        result.add(current.toString())
        return result.map { arg ->
            val t = arg.trim()
            when {
                t.length >= 2 && t.startsWith("'") && t.endsWith("'") ->
                    t.substring(1, t.length - 1)
                t.length >= 2 && t.startsWith("\"") && t.endsWith("\"") ->
                    t.substring(1, t.length - 1)
                else -> t
            }
        }
    }

    /** 去冠词排序名：The Walking Dead -> Walking Dead, The。 */
    private fun sortName(s: String): String {
        for (a in listOf("The ", "A ", "An ")) {
            if (s.startsWith(a, ignoreCase = true)) {
                return s.substring(a.length) + ", " + s.substring(0, a.length - 1)
            }
        }
        return s
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
     * 日期格式化（FileBot `format`）。
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
     * 日期解析（FileBot `parseDate`）：按给定模式（缺省自动尝试 ISO）解析，
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

    private fun tryParseLocalDate(value: String): LocalDate? =
        runCatching { LocalDate.parse(value) }.getOrNull()

    private fun tryParseLocalDateTime(value: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(value) }.getOrNull()

    private fun tryParseZonedDateTime(value: String): ZonedDateTime? =
        runCatching { ZonedDateTime.parse(value) }.getOrNull()
}
