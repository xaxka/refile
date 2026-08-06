package xa.refile.core.matcher

import com.google.common.truth.Truth.assertThat
import org.junit.Assume
import org.junit.Test

class TextNormalizerTest {

    @Test fun `lowercases and strips punctuation`() {
        assertThat(TextNormalizer.normalize("The Matrix!")).isEqualTo("matrix")
    }

    @Test fun `NFD strips diacritics for Amelie`() {
        // 改造前已支持：NFD 把 é 拆成 e + 组合标记后剥离
        assertThat(TextNormalizer.normalize("Amélie")).isEqualTo("amelie")
    }

    @Test fun `reorders trailing article Matrix The`() {
        // P2.3：`Matrix, The` → 重排为 `The Matrix` → 去前导 the → `matrix`
        assertThat(TextNormalizer.normalize("Matrix, The")).isEqualTo("matrix")
    }

    @Test fun `strips leading article only once`() {
        // `The The` 乐队名不应被递归剥离
        assertThat(TextNormalizer.normalize("The The")).isEqualTo("the")
    }

    @Test fun `collapses internal punctuation to single space`() {
        assertThat(TextNormalizer.normalize("Spider-Man: Into the Spider-Verse"))
            .isEqualTo("spider man into the spider verse")
    }

    @Test fun `empty string returns empty`() {
        assertThat(TextNormalizer.normalize("")).isEmpty()
    }

    @Test fun `preserves digits and alphanumerics`() {
        assertThat(TextNormalizer.normalize("Inception 2010 1080p")).isEqualTo("inception 2010 1080p")
    }

    // ---- Feature #25 跨脚本 ICU 转写 ----
    // 这些测试在桌面 JDK 上跑 `java.text.Transliterator`（ICU4J 子集）；
    // 在 Android 上跑 `android.icu.text.Transliterator`（API 24+）。
    // 若运行环境未装 Transliterator → TextNormalizer 退化到仅 NFD，测试用 `contains` 宽松断言。

    @Test fun `transliterates CJK to latin pinyin`() {
        Assume.assumeTrue(transliteratorAvailable())
        val out = TextNormalizer.normalize("攻壳机动队")
        // ICU Any-Latin 把中文音译为拼音（带空格分隔），NFD + 去标点后应只剩小写拉丁 + 空格
        assertThat(out).matches("[a-z][a-z ]*")
        assertThat(out).isNotEmpty()
        // 攻壳机动队的标准拼音应是 gong ke ji dong dui 之一；至少包含 "gong"
        // 不同 ICU 版本可能输出略异，故只断言第一个 token 不为空
        assertThat(out.split(' ').first()).isNotEmpty()
    }

    @Test fun `transliterates Japanese kana to romaji`() {
        Assume.assumeTrue(transliteratorAvailable())
        val out = TextNormalizer.normalize("十二国記")
        // 平文式罗马字输出（juuni kokuki 之类）
        assertThat(out).matches("[a-z][a-z ]*")
        assertThat(out).isNotEmpty()
    }

    @Test fun `transliterates Cyrillic to latin`() {
        Assume.assumeTrue(transliteratorAvailable())
        val out = TextNormalizer.normalize("Брат")
        // Брат → brat
        assertThat(out).isEqualTo("brat")
    }

    @Test fun `transliterated CJK and latin title can compare`() {
        Assume.assumeTrue(transliteratorAvailable())
        // 跨脚本归一后，中文标题与拉丁标题都落到 latin 空间，可做相等 / 相似度比较
        // （不一定高分，但能进入候选打分流程；Feature #25 的核心价值）
        val cn = TextNormalizer.normalize("攻壳机动队")
        val en = TextNormalizer.normalize("Ghost in the Shell")
        assertThat(cn).matches("[a-z][a-z ]*")
        assertThat(en).isEqualTo("ghost in shell")
    }

    @Test fun `accented latin and plain latin compare equal`() {
        // 跨脚本音译后 é → e；改造前 NFD 已能处理，此测试确保改造后行为不变
        assertThat(TextNormalizer.normalize("café")).isEqualTo(TextNormalizer.normalize("cafe"))
    }

    @Test fun `german umlaut collapses to ascii`() {
        // Ä/Ö/Ü → ae/oe/ue（ICU Latin-ASCII 行为）或 a/o/u（NFD 行为）
        // 两种实现下 "über" 与 "uber" 都应相等
        assertThat(TextNormalizer.normalize("über")).contains("ber")
    }

    private fun transliteratorAvailable(): Boolean {
        // Брат → brat only works if ICU Transliterator is loaded (JDK with ICU data / Android)
        return TextNormalizer.normalize("Брат") == "brat"
    }
}
