package xa.refile.core.matcher

import java.lang.reflect.Method
import java.text.Normalizer

/**
 * 跨脚本归一化（Feature #25）。
 *
 * 流程：lowercase → 重排尾随冠词（`Matrix, The` → `The Matrix`）→
 *      ICU `Any-Latin; Latin-ASCII` 转写（中文→拼音、日文→罗马字、西里尔/希腊→拉丁、
 *      日耳曼变音 → ASCII 等）→ NFD 分解去变音符号（兜底，覆盖 ICU 未转写的 Mn）→
 *      去非字母数字 → 折叠空格 → 去前导冠词 the/a/an。
 *
 * 替换原 [ConfidenceScorer.normalize] 仅 NFD 去变音的局限：
 * - `Amélie` → `amelie`（NFD 即可，新旧等价）
 * - `攻壳机动队` → `gong ke ji dong dui`（ICU Any-Latin 拼音化）
 * - `十二国記` → `juuni kokuki`（ICU 平文式罗马字）
 * - `Брат` → `brat`（西里尔→拉丁）
 *
 * 这样 `攻壳机动队` 与 TMDB 候选 `Ghost in the Shell` 仍能通过拼音 `gong ke ji dong dui`
 * 与 `ghost in the shell` 算相似度（不一定高分，但能进入候选打分），而不是直接判 0 分。
 *
 * ## Transliterator 加载策略
 *
 * [java.text.Transliterator] 是 JDK 自带的 ICU4J 子集，桌面 JVM 与 Android 上 API 形态一致，
 * 但 Android 历史上把 `java.text.Transliterator` 从 `java.text` 包中剔除（仅保留 `java.text.Normalizer`），
 * 改由 `android.icu.text.Transliterator`（API 24+）提供。core 模块是纯 Kotlin/JVM，编译期
 * 看不到 `android.icu` 类型；故通过反射按以下顺序查找可用实现：
 *
 * 1. `android.icu.text.Transliterator`（Android 运行时，minSdk 26+ 满足）
 * 2. `java.text.Transliterator`（桌面 JDK）
 * 3. `com.ibm.icu.text.Transliterator`（部分应用打包独立 ICU4J）
 *
 * 全部失败 → 退化为仅 NFD 去变音（与改造前等价），不影响功能仅损失跨脚本能力。
 *
 * ## 线程安全
 *
 * [Transliterator] 实例本身线程安全（ICU 文档明确），[transform] 内部加锁无必要；
 * 反射 [Method] 调用对并发安全。`normalize` 整体无共享可变状态，可被并发调用。
 */
object TextNormalizer {

    /** ICU 规则 ID：先把任意脚本转拉丁，再把拉丁中的变音符号压成 ASCII。 */
    private const val TRANSLIT_RULES = "Any-Latin; Latin-ASCII"

    /** Transliterator 候选类全限定名（按优先级；须在 [transliterator] 之前声明，初始化顺序依赖）。 */
    private val CANDIDATE_CLASSES = listOf(
        "android.icu.text.Transliterator",  // Android 运行时（API 24+，minSdk 26 满足）
        "java.text.Transliterator",         // 桌面 JDK
        "com.ibm.icu.text.Transliterator",  // 独立打包的 ICU4J
    )

    /**
     * 反射加载的 Transliterator 实例（任一可用即止）。`null` 表示全部加载失败，
     * 退化为仅 NFD 去变音。
     */
    private val transliterator: Any? = loadTransliterator()

    /** 反射缓存：`transliterate(String) → String` 方法引用。 */
    private val transformMethod: Method? = transliterator?.let { obj ->
        runCatching { obj.javaClass.getMethod("transliterate", String::class.java) }.getOrNull()
    }

    // P2.3：前导冠词（仅去除一次，避免循环影响 `The The` 这种乐队名）
    private val LEADING_ARTICLE = Regex("^(?:the|a|an)\\s+", RegexOption.IGNORE_CASE)
    // P2.3：尾随冠词排序式命名 `Matrix, The` / `Lord of the Rings, The`（输入已 lowercase）
    private val TRAILING_ARTICLE = Regex("^(.+?),\\s*(the|a|an)\\s*\$")

    /**
     * 归一化入口：跨脚本音译 + NFD 去变音 + 字符清洗。
     *
     * 输入空串返回空串；纯 ASCII 输入与改造前行为一致（NFD 仅在含变音时才生效）。
     */
    fun normalize(s: String): String {
        if (s.isEmpty()) return s
        val lower = reorderTrailingArticle(s.lowercase())
        val latin = transliterate(lower)
        val nfd = Normalizer.normalize(latin, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}"), "")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return stripLeadingArticle(nfd)
    }

    /**
     * 调用 ICU Transliterator 完成 `Any-Latin; Latin-ASCII` 转写。
     * 失败回退到原文（后续 NFD 兜底）。
     */
    private fun transliterate(s: String): String {
        val tr = transliterator ?: return s
        val m = transformMethod ?: return s
        return runCatching { m.invoke(tr, s) as? String }.getOrNull() ?: s
    }

    /** P2.3：将 `Matrix, The` 重排为 `The Matrix`，再走常规归一。 */
    private fun reorderTrailingArticle(lowerCased: String): String {
        val m = TRAILING_ARTICLE.find(lowerCased) ?: return lowerCased
        val name = m.groupValues[1].trim()
        val article = m.groupValues[2]
        return "$article $name"
    }

    /** P2.3：去除前导冠词 the/a/an（仅一次）。 */
    private fun stripLeadingArticle(s: String): String {
        val m = LEADING_ARTICLE.find(s) ?: return s
        return s.substring(m.range.last + 1).trimStart()
    }

    /**
     * 按优先级反射加载可用 Transliterator。任一加载成功即返回，全失败返回 null。
     *
     * 加载策略保守：[Transliterator.getInstance] 可能因规则 ID 不存在抛异常，
     * 故即便类存在也要 try-catch 实例化步骤。
     */
    private fun loadTransliterator(): Any? {
        for (className in CANDIDATE_CLASSES) {
            val instance = runCatching {
                val cls = Class.forName(className)
                val getInstance = cls.getMethod("getInstance", String::class.java)
                getInstance.invoke(null, TRANSLIT_RULES)
            }.getOrNull()
            if (instance != null) return instance
        }
        return null
    }
}
