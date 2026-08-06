package xa.refile.core.naming

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PipeModifiersTest {

    private fun p(value: Any?, modifier: String): Any? = PipeModifiers.apply(value, modifier)

    // ---- 1. 大小写类（upper / lower / upperInitial / lowerTrail）----

    @Test fun `upper transforms entire string`() {
        assertThat(p("Firefly", "upper")).isEqualTo("FIREFLY")
    }

    @Test fun `lower transforms entire string`() {
        assertThat(p("Firefly", "lower")).isEqualTo("firefly")
    }

    @Test fun `upper with pattern transforms only matched segment`() {
        assertThat(p("the matrix", "upper('matrix')")).isEqualTo("the MATRIX")
    }

    @Test fun `lower with pattern transforms only matched segment`() {
        assertThat(p("THE MATRIX", "lower('MATRIX')")).isEqualTo("THE matrix")
    }

    @Test fun `upperInitial capitalizes every word`() {
        assertThat(p("the day a new demon was born", "upperInitial"))
            .isEqualTo("The Day A New Demon Was Born")
    }

    @Test fun `lowerTrail titlecases trailing uppercase word`() {
        assertThat(p("Gundam SEED", "lowerTrail")).isEqualTo("Gundam Seed")
    }

    @Test fun `upper on empty string returns empty`() {
        assertThat(p("", "upper")).isEqualTo("")
    }

    // ---- 2. 数字类（pad / round / roman）----

    @Test fun `pad with multiple lengths pads each number segment`() {
        assertThat(p("1x01", "pad(2,3)")).isEqualTo("01x001")
    }

    @Test fun `pad with single length pads all number segments`() {
        assertThat(p("5", "pad(3)")).isEqualTo("005")
        assertThat(p("1x1", "pad(2)")).isEqualTo("01x01")
    }

    @Test fun `round with one decimal place`() {
        assertThat(p(3.14, "round(1)")).isEqualTo("3.1")
    }

    @Test fun `round with zero precision returns integer`() {
        assertThat(p(3.14, "round(0)")).isEqualTo("3")
    }

    @Test fun `round half up rounds 2 point 5 up`() {
        assertThat(p(2.5, "round(0)")).isEqualTo("3")
    }

    @Test fun `roman replaces numbers 1 to 12`() {
        assertThat(p("Star Wars Episode 4", "roman")).isEqualTo("Star Wars Episode IV")
    }

    @Test fun `roman does not replace 13`() {
        assertThat(p("Episode 13", "roman")).isEqualTo("Episode 13")
    }

    @Test fun `roman does not replace 0`() {
        assertThat(p("Episode 0", "roman")).isEqualTo("Episode 0")
    }

    // ---- 3. 模式匹配类（match / matchAll / matchBrackets / before / after）----

    @Test fun `match without group returns entire match when no capturing group`() {
        assertThat(p("Firefly 2009", "match('firefly')")).isEqualTo("Firefly")
    }

    @Test fun `match without group returns first non empty capturing group`() {
        // 修复后行为：未指定分组时，若模式含捕获组则取首个非空捕获组
        assertThat(p("Year: 2024", "match('(\\d{4})')")).isEqualTo("2024")
    }

    @Test fun `match with explicit group index returns that group`() {
        assertThat(p("Firefly 2009", "match('(\\d{4})', 1)")).isEqualTo("2009")
    }

    @Test fun `match returns null when no match`() {
        assertThat(p("no digits here", "match('(\\d{4})')")).isNull()
    }

    @Test fun `matchAll returns all matches as list`() {
        assertThat(p("a1 b2 c3", "matchAll('\\d')")).isEqualTo(listOf("1", "2", "3"))
    }

    @Test fun `matchAll returns empty list when no match`() {
        assertThat(p("no digits", "matchAll('\\d')")).isEqualTo(emptyList<String>())
    }

    @Test fun `matchBrackets returns bracket contents`() {
        assertThat(p("Show (US) [2020]", "matchBrackets")).isEqualTo(listOf("US", "2020"))
    }

    @Test fun `before returns trimmed substring before pattern`() {
        // 修复后行为：匹配成功时返回 trim 后的子串
        assertThat(p("Sissi: The Young Empress", "before(':')")).isEqualTo("Sissi")
    }

    @Test fun `after returns trimmed substring after pattern`() {
        // 修复后行为：匹配成功时返回 trim 后的子串
        assertThat(p("Sissi: The Young Empress", "after(':')")).isEqualTo("The Young Empress")
    }

    @Test fun `before returns original when no match`() {
        assertThat(p("no match here", "before('xyz')")).isEqualTo("no match here")
    }

    @Test fun `after returns original when no match`() {
        assertThat(p("no match here", "after('xyz')")).isEqualTo("no match here")
    }

    // ---- 4. 替换移除类（replace / replaceAll / removeAll / remove / removeBrackets /
    //       removeIllegalCharacters / replaceIllegalCharacters / replacePart / replaceTrailingBrackets）----

    @Test fun `replace swaps substring`() {
        assertThat(p("Deep Space", "replace(' ', '.')")).isEqualTo("Deep.Space")
    }

    @Test fun `replaceAll with regex pattern`() {
        assertThat(p("Deep Space", "replaceAll(' ', '.')")).isEqualTo("Deep.Space")
    }

    @Test fun `replaceAll with single arg replaces with empty`() {
        assertThat(p("Deep Space", "replaceAll(' ')")).isEqualTo("DeepSpace")
    }

    @Test fun `removeAll with regex removes all matches`() {
        assertThat(p("A.B.C", "removeAll('\\.')")).isEqualTo("ABC")
    }

    @Test fun `remove strips given characters`() {
        assertThat(p("A.B.C", "remove('.')")).isEqualTo("ABC")
    }

    @Test fun `remove with multiple args strips all given characters`() {
        assertThat(p("A.B.C", "remove('.', '-')")).isEqualTo("ABC")
    }

    @Test fun `removeBrackets strips all bracket groups`() {
        assertThat(p("Show (US) [2020]", "removeBrackets")).isEqualTo("Show")
    }

    @Test fun `removeIllegalCharacters strips windows illegal chars`() {
        assertThat(p("A?B", "removeIllegalCharacters")).isEqualTo("AB")
    }

    @Test fun `replaceIllegalCharacters uses unicode lookalikes`() {
        assertThat(p("A?B", "replaceIllegalCharacters")).isEqualTo("A？B")
    }

    @Test fun `replacePart with default replacement appends part label`() {
        assertThat(p("Today Is the Day (1)", "replacePart")).isEqualTo("Today Is the Day, Part 1")
    }

    @Test fun `replacePart with empty replacement removes part number`() {
        assertThat(p("Today Is the Day (1)", "replacePart('')")).isEqualTo("Today Is the Day")
    }

    @Test fun `replacePart matches Part n suffix`() {
        // 修复后行为：同时匹配 (n) 与 Part n 后缀
        assertThat(p("Today Is the Day Part 1", "replacePart('')")).isEqualTo("Today Is the Day")
    }

    @Test fun `replaceTrailingBrackets removes trailing parens`() {
        assertThat(p("Show (US)", "replaceTrailingBrackets")).isEqualTo("Show")
    }

    @Test fun `replaceTrailingBrackets with replacement keeps content`() {
        assertThat(p("Show (US)", "replaceTrailingBrackets(' [\$1]')")).isEqualTo("Show [US]")
    }

    // ---- 5. 清洗规范化类（clean / space / colon / slash / acronym / asciiQuotes /
    //       truncate / validateFileName / ascii / transliterate）----

    @Test fun `clean strips brackets separators and clutter`() {
        assertThat(p("[ONe]_Ano_Hana_01_(1280x720)", "clean")).isEqualTo("Ano Hana 01")
    }

    @Test fun `space replaces spaces with given char`() {
        assertThat(p("Deep Space", "space('_')")).isEqualTo("Deep_Space")
    }

    @Test fun `space with empty arg deletes all spaces`() {
        assertThat(p("Deep Space", "space('')")).isEqualTo("DeepSpace")
    }

    @Test fun `colon replaces colon with spaced separator`() {
        assertThat(p("Sissi: The Young Empress", "colon('-')")).isEqualTo("Sissi - The Young Empress")
    }

    @Test fun `colon with ratio replaces ratio colon separately`() {
        // 16:9 → 16x9（ratio），剩余冒号 → -
        assertThat(p("16:9 Video: Title", "colon('-', 'x')")).isEqualTo("16x9 Video - Title")
    }

    @Test fun `slash replaces slashes with given char`() {
        assertThat(p("V_MPEG4/ISO/AVC", "slash('.')")).isEqualTo("V_MPEG4.ISO.AVC")
    }

    @Test fun `acronym takes first letter of each word`() {
        assertThat(p("Deep Space 9", "acronym")).isEqualTo("DS9")
    }

    @Test fun `asciiQuotes converts curly quotes to straight`() {
        assertThat(p("\u201CHello\u201D", "asciiQuotes")).isEqualTo("\"Hello\"")
    }

    @Test fun `truncate cuts at limit and strips trailing punctuation`() {
        assertThat(p("The Quick Brown Fox", "truncate(10)")).isEqualTo("The Quick")
    }

    @Test fun `truncate with nonWordPattern soft cuts at last word boundary`() {
        // hardLimit=20 内最后非词边界（空格）在索引 19，软截断保留完整单词
        assertThat(p("The Quick Brown Fox Jumps Over", "truncate(20, '\\W+')")).isEqualTo("The Quick Brown Fox")
    }

    @Test fun `truncate with nonWordPattern returns original when under hard limit`() {
        // hardLimit >= length → 原样返回
        assertThat(p("Short", "truncate(20, '\\W+')")).isEqualTo("Short")
    }

    @Test fun `validateFileName strips leading dot`() {
        assertThat(p(".hack", "validateFileName")).isEqualTo("hack")
    }

    @Test fun `ascii folds diacritics to ascii`() {
        assertThat(p("Café", "ascii")).isEqualTo("Cafe")
    }

    @Test fun `transliterate replaces non ascii with question mark`() {
        assertThat(p("中文", "transliterate('Latin')")).isEqualTo("??")
    }

    // ---- 6. 排序命名类（sortName / sortInitial / initialName）----

    @Test fun `sortName moves leading article to end`() {
        // 本实现将冠词移至末尾，与参考 API 的「移除冠词」有意分歧
        assertThat(p("The Walking Dead", "sortName")).isEqualTo("Walking Dead, The")
    }

    @Test fun `sortName returns original when no article`() {
        assertThat(p("Avatar", "sortName")).isEqualTo("Avatar")
    }

    @Test fun `sortName with replacement template uses custom pattern`() {
        // $1=冠词 The, $2=剩余名 Gabby Hayes Show；年份括号被保护
        assertThat(p("The Gabby Hayes Show (1956)", "sortName('\$2, \$1')")).isEqualTo("Gabby Hayes Show, The (1956)")
    }

    @Test fun `sortName with replacement returns original when no article`() {
        assertThat(p("Avatar", "sortName('\$2, \$1')")).isEqualTo("Avatar")
    }

    @Test fun `sortInitial returns first letter of sort name`() {
        assertThat(p("Avatar", "sortInitial")).isEqualTo("A")
    }

    @Test fun `sortInitial strips leading article first`() {
        assertThat(p("The Avatar", "sortInitial")).isEqualTo("A")
    }

    @Test fun `sortInitial returns first char for numeric start`() {
        // 本实现数字开头返回首字符，与参考 API 的「返回 0-9」有意分歧
        assertThat(p("12 Monkeys", "sortInitial")).isEqualTo("1")
    }

    @Test fun `initialName abbreviates first name only`() {
        assertThat(p("James Cameron", "initialName")).isEqualTo("J. Cameron")
    }

    @Test fun `initialName abbreviates all but last word`() {
        // 修复后行为：对除最后一个词外的所有词取首字母 + .
        assertThat(p("James Earl Jones", "initialName")).isEqualTo("J. E. Jones")
    }

    @Test fun `initialName returns single word unchanged`() {
        assertThat(p("Cameron", "initialName")).isEqualTo("Cameron")
    }

    // ---- 7. 列表类（joining / joiningDistinct / bounds）----

    @Test fun `joining concatenates list with separator`() {
        assertThat(p(listOf("Sci-Fi", "Drama"), "joining('-')")).isEqualTo("Sci-Fi-Drama")
    }

    @Test fun `joining with prefix and suffix`() {
        assertThat(p(listOf("Sci-Fi", "Drama"), "joining('-', '[', ']')")).isEqualTo("[Sci-Fi-Drama]")
    }

    @Test fun `joiningDistinct deduplicates and joins`() {
        assertThat(p(listOf("Sci-Fi", "Drama", "Sci-Fi"), "joiningDistinct(',')")).isEqualTo("Sci-Fi,Drama")
    }

    @Test fun `joiningDistinct with prefix and suffix`() {
        assertThat(p(listOf("Sci-Fi", "Drama", "Sci-Fi"), "joiningDistinct(',', '(', ')')"))
            .isEqualTo("(Sci-Fi,Drama)")
    }

    @Test fun `bounds returns first and last elements`() {
        assertThat(p(listOf(2002, 2003, 2004), "bounds")).isEqualTo(listOf(2002, 2004))
    }

    @Test fun `bounds returns single element for one item list`() {
        assertThat(p(listOf(2002), "bounds")).isEqualTo(listOf(2002))
    }

    @Test fun `bounds returns null for empty list`() {
        assertThat(p(emptyList<Int>(), "bounds")).isNull()
    }

    // ---- 8. 路径类（div / plus / mod / getRoot / getTail / head / tail /
    //       listPath / getRelativePathTail / toFile）----

    @Test fun `div concatenates path segments`() {
        assertThat(p("library", "div('Movies', '2023')")).isEqualTo("library/Movies/2023")
    }

    @Test fun `plus concatenates string suffix`() {
        assertThat(p("Avatar", "plus('.bak')")).isEqualTo("Avatar.bak")
    }

    @Test fun `mod inserts suffix before extension`() {
        assertThat(p("Avatar (2009).mp4", "mod(' [720p]')")).isEqualTo("Avatar (2009) [720p].mp4")
    }

    @Test fun `mod without extension appends suffix`() {
        assertThat(p("Avatar", "mod(' [720p]')")).isEqualTo("Avatar [720p]")
    }

    @Test fun `getRoot returns first segment`() {
        assertThat(p("library/Movies/Anime", "getRoot")).isEqualTo("library")
    }

    @Test fun `getRoot returns slash for absolute path`() {
        assertThat(p("/library/Movies", "getRoot")).isEqualTo("/")
    }

    @Test fun `getTail drops first segment`() {
        assertThat(p("library/Movies/Anime", "getTail")).isEqualTo("Movies/Anime")
    }

    @Test fun `getTail returns empty for single segment`() {
        assertThat(p("library", "getTail")).isEqualTo("")
    }

    @Test fun `head returns nth segment one indexed`() {
        assertThat(p("library/Movies/Anime", "head(2)")).isEqualTo("Movies")
    }

    @Test fun `head beyond size returns original`() {
        assertThat(p("library/Movies", "head(5)")).isEqualTo("library/Movies")
    }

    @Test fun `tail returns last n segments joined`() {
        assertThat(p("library/Movies/Anime", "tail(2)")).isEqualTo("Movies/Anime")
    }

    @Test fun `listPath returns all segments`() {
        assertThat(p("library/Movies/Anime", "listPath")).isEqualTo(listOf("library", "Movies", "Anime"))
    }

    @Test fun `listPath with n returns last n segments`() {
        assertThat(p("library/Movies/Anime", "listPath(2)")).isEqualTo(listOf("Movies", "Anime"))
    }

    @Test fun `getRelativePathTail returns last n segments`() {
        assertThat(p("library/Movies/Anime", "getRelativePathTail(1)")).isEqualTo("Anime")
    }

    @Test fun `toFile normalizes multiple slashes`() {
        assertThat(p("a//b///c", "toFile")).isEqualTo("a/b/c")
    }

    // ---- 9. 日期类（format / parseDate / toDate / zone）----

    @Test fun `format with custom pattern`() {
        assertThat(p("2023-01-15", "format('yyyy.MM.dd')")).isEqualTo("2023.01.15")
    }

    @Test fun `format handles ISO datetime input`() {
        assertThat(p("2023-01-15T10:30:00Z", "format('yyyy.MM.dd')")).isEqualTo("2023.01.15")
    }

    @Test fun `format returns original for invalid date`() {
        assertThat(p("not-a-date", "format('yyyy.MM.dd')")).isEqualTo("not-a-date")
    }

    @Test fun `parseDate with custom pattern outputs ISO`() {
        assertThat(p("20230115", "parseDate('yyyyMMdd')")).isEqualTo("2023-01-15")
    }

    @Test fun `parseDate without pattern auto parses ISO`() {
        assertThat(p("2023-01-15", "parseDate")).isEqualTo("2023-01-15")
    }

    @Test fun `toDate converts epoch seconds to local datetime`() {
        val result = p("1672617600", "toDate")
        assertThat(result as String).contains("2023-01-02")
    }

    @Test fun `zone converts epoch to given timezone`() {
        val result = p("1700000000", "zone('UTC')")
        assertThat(result as String).contains("2023-11-14")
    }

    @Test fun `zone with invalid zone id returns original`() {
        assertThat(p("1700000000", "zone('Not/AZone')")).isEqualTo("1700000000")
    }

    // ---- 10. 其他（isLatin）----

    @Test fun `isLatin returns true for ascii letters`() {
        assertThat(p("Firefly", "isLatin").toString()).isEqualTo("true")
    }

    @Test fun `isLatin returns false for non latin script`() {
        assertThat(p("权力的游戏", "isLatin").toString()).isEqualTo("false")
    }

    @Test fun `isLatin returns true for latin with diacritics`() {
        assertThat(p("Café", "isLatin").toString()).isEqualTo("true")
    }
}
