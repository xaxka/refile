package xa.refile.core.naming

import java.util.regex.PatternSyntaxException

/**
 * 管道修饰符（计划 §5.5 管道修饰符表，全部自实现，可链式 `{n|upper|space(_)}`）。
 *
 * 大小写 / 补零取整 / 字符替换 / 截取匹配 / 命名变换 / 清洗转写 / 列表。
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
            // 大小写
            "upper" -> value.toStr().uppercase()
            "lower" -> value.toStr().lowercase()
            "upperInitial" -> upperInitial(value.toStr())
            "lowerTrail" -> lowerTrail(value.toStr())
            "title" -> value.toStr().split(' ').joinToString(" ") { w ->
                w.replaceFirstChar { it.titlecase() }
            }
            // 补零与取整
            "pad" -> {
                val n = args.getOrNull(0)?.toIntOrNull() ?: 2
                value.toStr().padStart(n, '0')
            }
            "round" -> {
                val n = args.getOrNull(0)?.toIntOrNull() ?: 0
                val d = value.toStr().toDoubleOrNull() ?: return value
                val f = Math.pow(10.0, n.toDouble())
                (kotlin.math.round(d * f) / f).toString()
            }
            // 字符替换
            // space(c) 把空格替换为 c；space('') 删除所有空格
            "space" -> {
                val arg = args.getOrNull(0) ?: " "
                if (arg.isEmpty()) value.toStr().replace(" ", "")
                else value.toStr().replace(' ', arg.first())
            }
            "dot" -> value.toStr().replace(' ', '.')
            "colon" -> {
                val arg = args.getOrNull(0)
                when {
                    arg == null -> value.toStr().replace(':', '-')
                    arg.isEmpty() -> value.toStr().replace(":", "")
                    else -> value.toStr().replace(':', arg.first())
                }
            }
            "slash" -> {
                val arg = args.getOrNull(0)
                when {
                    arg == null -> value.toStr().replace('/', '-')
                    arg.isEmpty() -> value.toStr().replace("/", "")
                    else -> value.toStr().replace('/', arg.first())
                }
            }
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
            // 截取与匹配
            "before" -> {
                val p = args.getOrNull(0) ?: return value
                value.toStr().substringBefore(p)
            }
            "after" -> {
                val p = args.getOrNull(0) ?: return value
                value.toStr().substringAfter(p)
            }
            "match" -> {
                // B10: 非法正则时返回 null（视作未匹配）。
                val p = args.getOrNull(0) ?: return value
                try {
                    Regex(p).find(value.toStr())?.value
                } catch (e: PatternSyntaxException) {
                    null
                }
            }
            "matchAll" -> {
                val p = args.getOrNull(0) ?: return value
                try {
                    Regex(p).findAll(value.toStr()).map { it.value }.toList()
                } catch (e: PatternSyntaxException) {
                    emptyList()
                }
            }
            // 命名变换
            "sortName" -> sortName(value.toStr()) // 去冠词排序名
            "initialName" -> value.toStr().split(' ').joinToString(" ") { w ->
                w.firstOrNull()?.toString()?.plus(".") ?: ""
            }
            "acronym" -> value.toStr().split(Regex("[^A-Za-z0-9]"))
                .filter { it.isNotEmpty() }
                .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
                .joinToString("")
            "roman" -> toRoman(value.toStr().toIntOrNull() ?: 0)
            // 清洗与转写
            "clean" -> value.toStr().replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
            // 移除尾部括号组，如 "Show (US)" -> "Show"
            "replaceTrailingBrackets" ->
                value.toStr().replace(Regex("\\s*[\\[(\\{].*?[\\])\\}]\\s*$"), "").trim()
            // 取末尾 n 个字符
            "tail" -> {
                val n = args.getOrNull(0)?.toIntOrNull() ?: 0
                if (n > 0) value.toStr().takeLast(n) else value.toStr()
            }
            "ascii" -> value.toStr().map { if (it.code < 128) it else '?' }.joinToString("")
            "transliterate" -> value.toStr().map { if (it.code < 128) it else '?' }.joinToString("")
            "validateFileName" -> value.toStr().replace(Regex("[\\\\/:*?\"<>|]"), "-").trim()
            // 列表
            "joining" -> {
                val sep = args.getOrNull(0) ?: ","
                val prefix = args.getOrNull(1) ?: ""
                val suffix = args.getOrNull(2) ?: ""
                when (value) {
                    is List<*> -> "$prefix${value.joinToString(sep)}$suffix"
                    else -> value.toStr()
                }
            }
            else -> value // 未知修饰符：原样返回（容错）
        }
    }

    private fun Any.toStr(): String = when (this) {
        is List<*> -> joinToString(",")
        is Boolean -> if (this) "true" else "false"
        else -> toString()
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

    private fun upperInitial(s: String): String {
        val trimmed = s.trimStart()
        val leading = s.length - trimmed.length
        return s.substring(0, leading) + trimmed.replaceFirstChar { it.titlecase() }
    }

    private fun lowerTrail(s: String): String {
        if (s.isEmpty()) return s
        return s.substring(0, 1) + s.substring(1).lowercase()
    }

    private fun sortName(s: String): String {
        for (a in listOf("The ", "A ", "An ")) {
            if (s.startsWith(a, ignoreCase = true)) {
                return s.substring(a.length) + ", " + s.substring(0, a.length - 1)
            }
        }
        return s
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
}
