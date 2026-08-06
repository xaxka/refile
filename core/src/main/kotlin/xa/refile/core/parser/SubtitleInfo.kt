package xa.refile.core.parser

/**
 * 字幕文件信息（计划 §5.3 P1.7）。
 *
 * 仅对字幕扩展名（`.srt`/`.ass`/`.ssa`/`.sub`/`.idx`）的文件名解析。
 *
 * - [language]：ISO 639-1 双字母语言码（zh/en/ja/...），无法识别时为 null
 * - [forced]：强制字幕（`.forced.srt`）
 * - [default]：默认字幕（`.default.srt`）
 * - [hearingImpaired]：听障字幕（`.sdh.srt` / `.cc.srt` / `.hi.srt`）
 */
data class SubtitleInfo(
    val language: String?,
    val forced: Boolean,
    val default: Boolean,
    val hearingImpaired: Boolean,
)

/**
 * 字幕语言标签解析器（P1.7）。
 *
 * 命名约定：`Movie.zh.forced.srt` / `Movie.en.srt` / `Movie.sdh.srt`。
 * 解析顺序：取最后一个语言 token + 修饰符 token。
 */
object SubtitleLanguageParser {

    private val LANG_TAG = Regex("(?i)(?<=[._-])([a-z]{2,3})(?=([._-](forced|default|cc|hi|sdh))?[._-]?$)")
    private val MODIFIERS = mapOf(
        "forced" to "forced",
        "default" to "default",
        "cc" to "hi",
        "hi" to "hi",
        "sdh" to "hi",
    )

    /** 解析字幕文件 baseName（不含扩展名）。 */
    fun parse(baseName: String): SubtitleInfo {
        var forced = false
        var default = false
        var hearingImpaired = false
        MODIFIERS.forEach { (token, _) ->
            val re = Regex("(?i)(?:^|[._-])$token(?=[._-]|$)")
            if (re.containsMatchIn(baseName)) {
                when (MODIFIERS[token]) {
                    "forced" -> forced = true
                    "default" -> default = true
                    "hi" -> hearingImpaired = true
                }
            }
        }
        val language = LANG_TAG.find(baseName)?.groupValues?.get(1)?.lowercase()
        return SubtitleInfo(language, forced, default, hearingImpaired)
    }
}
