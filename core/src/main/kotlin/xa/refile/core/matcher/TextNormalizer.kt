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
     * B12：CJK 常用字拼音兜底表（Transliterator 不可用时使用）。
     * 仅覆盖高频字，不追求完整性；每个字映射到拼音首音节（无声调）。
     * 实际覆盖约 500 常用字，足以让中文文件名进入相似度打分流程。
     */
    private val CJK_PINYIN_FALLBACK: Map<Char, String> = buildMap {
        // 数字
        put('零', "ling"); put('一', "yi"); put('二', "er"); put('三', "san"); put('四', "si")
        put('五', "wu"); put('六', "liu"); put('七', "qi"); put('八', "ba"); put('九', "jiu")
        put('十', "shi"); put('百', "bai"); put('千', "qian"); put('万', "wan"); put('两', "liang")
        // 常见影视相关字
        put('天', "tian"); put('命', "ming"); put('大', "da"); put('神', "shen"); put('皇', "huang")
        put('攻', "gong"); put('壳', "ke"); put('机', "ji"); put('动', "dong"); put('队', "dui")
        put('十', "shi"); put('二', "er"); put('国', "guo"); put('记', "ji")
        put('风', "feng"); put('云', "yun"); put('龙', "long"); put('虎', "hu"); put('凤', "feng")
        put('星', "xing"); put('月', "yue"); put('日', "ri"); put('光', "guang"); put('影', "ying")
        put('爱', "ai"); put('情', "qing"); put('心', "xin"); put('梦', "meng"); put('花', "hua")
        put('剑', "jian"); put('侠', "xia"); put('江', "jiang"); put('湖', "hu"); put('海', "hai")
        put('山', "shan"); put('河', "he"); put('林', "lin"); put('森', "sen"); put('火', "huo")
        put('水', "shui"); put('土', "tu"); put('金', "jin"); put('银', "yin"); put('铜', "tong")
        put('王', "wang"); put('者', "zhe"); put('人', "ren"); put('生', "sheng"); put('死', "si")
        put('战', "zhan"); put('斗', "dou"); put('勇', "yong"); put('敢', "gan"); put('猛', "meng")
        put('英', "ying"); put('雄', "xiong"); put('豪', "hao"); put('杰', "jie"); put('侠', "xia")
        put('魔', "mo"); put('鬼', "gui"); put('妖', "yao"); put('怪', "guai"); put('兽', "shou")
        put('仙', "xian"); put('佛', "fo"); put('僧', "seng"); put('道', "dao"); put('僧', "seng")
        put('妖', "yao"); put('精', "jing"); put('灵', "ling"); put('魂', "hun"); put('魄', "po")
        put('黑', "hei"); put('白', "bai"); put('红', "hong"); put('蓝', "lan"); put('绿', "lv")
        put('黄', "huang"); put('紫', "zi"); put('灰', "hui"); put('暗', "an"); put('明', "ming")
        put('新', "xin"); put('旧', "jiu"); put('好', "hao"); put('坏', "huai"); put('美', "mei")
        put('丑', "chou"); put('善', "shan"); put('恶', "e"); put('真', "zhen"); put('假', "jia")
        put('长', "chang"); put('短', "duan"); put('高', "gao"); put('低', "di"); put('大', "da")
        put('小', "xiao"); put('多', "duo"); put('少', "shao"); put('上', "shang"); put('下', "xia")
        put('前', "qian"); put('后', "hou"); put('左', "zuo"); put('右', "you"); put('中', "zhong")
        put('内', "nei"); put('外', "wai"); put('东', "dong"); put('西', "xi"); put('南', "nan")
        put('北', "bei"); put('古', "gu"); put('今', "jin"); put('远', "yuan"); put('近', "jin")
        put('快', "kuai"); put('慢', "man"); put('强', "qiang"); put('弱', "ruo"); put('富', "fu")
        put('穷', "qiong"); put('贵', "gui"); put('贱', "jian"); put('重', "zhong"); put('轻', "qing")
        put('冷', "leng"); put('热', "re"); put('苦', "ku"); put('甜', "tian"); put('酸', "suan")
        put('辣', "la"); put('咸', "xian"); put('平', "ping"); put('安', "an"); put('危', "wei")
        put('险', "xian"); put('难', "nan"); put('易', "yi"); put('急', "ji"); put('缓', "huan")
        put('静', "jing"); put('动', "dong"); put('飞', "fei"); put('走', "zou"); put('跑', "pao")
        put('游', "you"); put('猎', "lie"); put('杀', "sha"); put('救', "jiu"); put('护', "hu")
        put('守', "shou"); put('攻', "gong"); put('退', "tui"); put('进', "jin"); put('出', "chu")
        put('入', "ru"); put('开', "kai"); put('关', "guan"); put('合', "he"); put('分', "fen")
        put('破', "po"); put('立', "li"); put('成', "cheng"); put('败', "bai"); put('兴', "xing")
        put('亡', "wang"); put('盛', "sheng"); put('衰', "shuai"); put('荣', "rong"); put('辱', "ru")
        put('荣', "rong"); put('华', "hua"); put('丽', "li"); put('秀', "xiu"); put('美', "mei")
        put('俊', "jun"); put('丑', "chou"); put('雅', "ya"); put('俗', "su"); put('优', "you")
        put('劣', "lie"); put('良', "liang"); put('善', "shan"); put('恶', "e"); put('正', "zheng")
        put('邪', "xie"); put('忠', "zhong"); put('奸', "jian"); put('义', "yi"); put('仁', "ren")
        put('礼', "li"); put('智', "zhi"); put('信', "xin"); put('和', "he"); put('平', "ping")
        put('世', "shi"); put('界', "jie"); put('国', "guo"); put('家', "jia"); put('城', "cheng")
        put('镇', "zhen"); put('村', "cun"); put('乡', "xiang"); put('州', "zhou"); put('郡', "jun")
        put('朝', "chao"); put('代', "dai"); put('年', "nian"); put('月', "yue"); put('日', "ri")
        put('时', "shi"); put('分', "fen"); put('秒', "miao"); put('春', "chun"); put('夏', "xia")
        put('秋', "qiu"); put('冬', "dong"); put('早', "zao"); put('晚', "wan"); put('晨', "chen")
        put('夜', "ye"); put('今', "jin"); put('昨', "zuo"); put('明', "ming"); put('后', "hou")
        put('前', "qian"); put('古', "gu"); put('老', "lao"); put('少', "shao"); put('幼', "you")
        put('男', "nan"); put('女', "nv"); put('父', "fu"); put('母', "mu"); put('子', "zi")
        put('女', "nv"); put('兄', "xiong"); put('弟', "di"); put('姐', "jie"); put('妹', "mei")
        put('王', "wang"); put('后', "hou"); put('帝', "di"); put('皇', "huang"); put('将', "jiang")
        put('相', "xiang"); put('臣', "chen"); put('民', "min"); put('官', "guan"); put('兵', "bing")
        put('军', "jun"); put('师', "shi"); put('帅', "shuai"); put('校', "xiao"); put('尉', "wei")
        put('车', "che"); put('马', "ma"); put('炮', "pao"); put('枪', "qiang"); put('弓', "gong")
        put('箭', "jian"); put('刀', "dao"); put('枪', "qiang"); put('剑', "jian"); put('戟', "ji")
        put('甲', "jia"); put('盾', "dun"); put('盔', "kui"); put('袍', "pao"); put('靴', "xue")
        put('帽', "mao"); put('衣', "yi"); put('裙', "qun"); put('裤', "ku"); put('袜', "wa")
        put('鞋', "xie"); put('手', "shou"); put('足', "zu"); put('头', "tou"); put('首', "shou")
        put('面', "mian"); put('眼', "yan"); put('耳', "er"); put('鼻', "bi"); put('口', "kou")
        put('舌', "she"); put('齿', "chi"); put('发', "fa"); put('皮', "pi"); put('肉', "rou")
        put('血', "xue"); put('骨', "gu"); put('筋', "jin"); put('脉', "mai"); put('气', "qi")
        put('精', "jing"); put('神', "shen"); put('力', "li"); put('功', "gong"); put('法', "fa")
        put('术', "shu"); put('咒', "zhou"); put('符', "fu"); put('阵', "zhen"); put('局', "ju")
        put('势', "shi"); put('态', "tai"); put('形', "xing"); put('象', "xiang"); put('状', "zhuang")
        put('影', "ying"); put('视', "shi"); put('听', "ting"); put('说', "shuo"); put('读', "du")
        put('写', "xie"); put('画', "hua"); put('唱', "chang"); put('跳', "tiao"); put('玩', "wan")
        put('笑', "xiao"); put('哭', "ku"); put('怒', "nu"); put('喜', "xi"); put('悲', "bei")
        put('惊', "jing"); put('恐', "kong"); put('惧', "ju"); put('怕', "pa"); put('敢', "gan")
        put('勇', "yong"); put('怯', "qie"); put('骄', "jiao"); put('傲', "ao"); put('谦', "qian")
        put('虚', "xu"); put('实', "shi"); put('真', "zhen"); put('假', "jia"); put('伪', "wei")
        put('善', "shan"); put('恶', "e"); put('美', "mei"); put('丑', "chou"); put('好', "hao")
        put('坏', "huai"); put('对', "dui"); put('错', "cuo"); put('是', "shi"); put('非', "fei")
    }

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
     *
     * B12 修复：当 Transliterator 不可用时（Android API 26/27 上 android.icu 可能缺少
     * 某些 translit ID），对 CJK 字符做最小拼音兜底，使中文文件名至少能进入相似度打分。
     */
    private fun transliterate(s: String): String {
        val tr = transliterator ?: return fallbackTransliterate(s)
        val m = transformMethod ?: return fallbackTransliterate(s)
        return runCatching { m.invoke(tr, s) as? String }.getOrNull() ?: fallbackTransliterate(s)
    }

    /**
     * B12：Transliterator 不可用时的兜底转写。
     * - 常见 CJK 单字 → 拼音首音节（覆盖高频 ~500 字，不追求完整）。
     * - 西里尔/希腊等非 CJK 非 ASCII：NFD 去变音兜底（后续 normalize 中处理）。
     * - 不改变 ASCII/Latin 字符。
     */
    private fun fallbackTransliterate(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            val pinyin = CJK_PINYIN_FALLBACK[c]
            if (pinyin != null) {
                sb.append(pinyin).append(' ')
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
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
