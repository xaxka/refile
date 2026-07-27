# 解析匹配引擎优化规格（Spec）

> 目标：参照 FB-Mod（实为 FileBot 4.8.5 fork）、Jellyfin、tinyMediaManager (TMM) 三个开源项目的解析 / 匹配引擎源码，对当前 `core/` 模块下的 `FilenameParser` 与 `MatchEngine` 进行定向优化。
>
> 范围：仅 `core/src/main/kotlin/xa/refile/core/parser/` 与 `core/src/main/kotlin/xa/refile/core/matcher/` 两个包。`tmdb/`、`naming/`、`rename/` 仅在必要时配合改动。**不读取文件二进制内容**（红线：与 TMM 不同，本项目不用 MediaInfo）。

---

## 1. 当前实现盘点

### 1.1 [FilenameParser.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt)

10 步管线：去扩展名 → 提方括号 → 归一化分隔符 → 解析技术标签 → 年份 → 季集 → 分片 → 版本 → 标题清洗 → 类型推断。

**强项**：
- 中文数字与阿拉伯数字双轨季集解析（`第X季第X集` / `第二季`）
- 多集区间与混合表达 `S01E01-E03E05`（B24 修复）
- 绝对集号（无年份时尾随 1-3 位数字，含 `Show-12`、`Show_12`）
- ` - ` 集名副标题分隔（`雀骨 S01E01 - 集名` → 只取主标识）
- 旧年份 + 新发行年同时出现时取最后一个有效年份（`Cold.War.1994.2026`）

**弱项**（对照三个参考项目）：
- W1. 标题清洗依赖 `TECH_TAIL` 正则（从首个技术词到结尾截断），遇到 `Movie.720p.Source.x264-GROUP.mkv` 这种「技术词夹在中间」的命名会过早截断或漏截断
- W2. 多集区间 `01-E03` 无上限保护，`E01-E99` 这种「季包」会被展开成 99 集
- W3. 无 `endingepnumber` 后跟字符保护，`s09e14-1080p` 会被误判为 E14→E108
- W4. 季集号无 sanity 上限（理论上 `S9999E9999` 会被接受）
- W5. 不识别版本/发行版（Director's Cut / Extended / IMAX / Remastered / Hybrid 等 edition 标签）
- W6. 不识别 HDR / HDR10+ / Dolby Vision / 3D 标签
- W7. Anime CRC 哈希方括号命名（`[Group][Series][12][1080p][FLAC][A1B2C3D4]`）走通用方括号剥离，丢失集号
- W8. Stacking 只支持 `CD1/Disc1/Part1/PT1`，不识别 `CDa`/`PartB`/`1of2`/`-a` 后缀
- W9. 不提取 IMDb ID `tt\d{7}` 用于 TMDB `find/imdb` 精确查找
- W10. 不识别 trailer/sample/interview 等 extras
- W11. 流媒体来源标记缺失（AMZN/NF/ATVP/HMAX/STZ/PCOK 等常见于 2020+ 资源）
- W12. 字幕语言标签 `.en.forced.srt` 不解析（影响伴随文件配对）

### 1.2 [MatchEngine.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/matcher/MatchEngine.kt)

`ConfidenceScorer` 现状：标题相似度 = `max(Jaccard, Levenshtein, alias)`；年份一致 +0.06 二值奖励；流行度归一化后 ×0.04。`MatchEngine` 在 `score ≥ 0.85` 且 `次名分差 ≥ 0.1` 时自动匹配。

**弱项**：
- M1. 年份用「一致 +0.06 / 不一致 +0」二值，差距 1 年与差距 30 年惩罚相同
- M2. 只有 Levenshtein 与 Jaccard 两个相似度算法，对换序鲁棒性差（`Us Last of The` 与 `The Last of Us` 几乎 0 分）
- M3. 不做音译归一化（`Café` vs `Cafe`、`Amélie` vs `Amelie` 会被视为不同）
- M4. 不去除冠词（`The Matrix` vs `Matrix, The` 在 TMDB 常见别名格式下匹配失败）
- M5. 不做 SxE 互校：当解析出 S01E02 时，候选若是剧集且有 SxE 信息应强加分；当前 `MatchCandidate` 没有这层信息
- M6. 不做 ID 优先级查找（tmdbId/imdbId/tvdbId 已在 [TmdbMapper](file:///workspace/core/src/main/kotlin/xa/refile/core/tmdb/TmdbMapper.kt) 中保留，但 matcher 没用）
- M7. 不做分数量化（0.91 vs 0.92 这种噪声可能导致排序抖动）

---

## 2. 参考项目要点提炼

### 2.1 FB-Mod（FileBot 4.8.5）

仓库性质：barry-allen07/FB-Mod 是 FileBot 4.8.5 的纯镜像 fork（单次提交，无定制），因此以下要点等同于 FileBot 4.8.5 引擎。

| 要点 | 现状对应 | 借鉴价值 |
|---|---|---|
| 三档 sanity（LENIENT/DEFAULT/STRICT），季≤50、每季集≤50、绝对集≤1000、季年范围 1970–2100 | W4 | **高**：直接照搬 DEFAULT_SANITY 作上限 |
| 11 个 SxE 正则按优先级链尝试，首个命中即止；STRICT 模式仅用前 6 个 | 现有 6 种模式无显式优先级档 | 中：现有「中文 > SxxExx > NxN > E02 > [02] > 日期 > 绝对号」顺序已合理，可不重构 |
| `EpisodeBalancer` 互校：SxE 满分则 Title 视为 1；SxE 与 Title 互相印证 | M5 | **高**：扩展 MatchCandidate 携带 SxE 后用于加分 |
| `floor(x*4)/4` 量化（4 档） | M7 | 中：低成本，减少排序噪声 |
| `SubstringFields` 主字段加权 `(2-sqrt((i+j)/(n1+n2)))` | 当前 alias 是平权 maxOf | 中 |
| Transliterator `Any-Latin;Latin-ASCII;[:Diacritic:]remove` | M3 | **高**：JDK 自带 `java.text.Transliterator`，Android API 21+ 可用 |
| `range()` 无季区间 `max−min≥9` 视为季包丢弃 | W2 | **高**：一行判断 |
| AniDB `SortOrder.AbsoluteAirdate` 把集号编码为 `年*10000+月*100+日` | 无 | 低：项目无 AniDB 通道 |
| Caffeine 缓存 + xattr 持久化 | TmdbCacheRepository 已有缓存 | 低：现有 DB 缓存足够 |
| IMDb ID grep `tt\d{7}` | W9 | **高**：解析到 ID 直接走 TMDB `find/imdb` 精确查找 |

### 2.2 Jellyfin（master 分支，Emby.Naming + MediaBrowser.Providers）

| 要点 | 现状对应 | 借鉴价值 |
|---|---|---|
| **NamingOptions.cs 正则集中配置 + 标志位过滤**（`IsNamed`/`IsOptimistic`/`IsByDate`/`SupportsAbsoluteEpisodeNumbers`） | 当前正则散落于 companion 字段 | 中：可重构为 `data class EpisodeExpression(val regex, val flags)`，但工作量与收益比一般 |
| `CleanStrings` 6 条链式迭代（每轮输出作下轮输入） | 当前单轮 `cleanTitle` | **高**：迭代式清洗比单次正则替换更鲁棒 |
| **多集尾号保护**：`endingepnumber` 后跟 `[0-9iIpP]` 视为分辨率丢弃 | W3 | **高**：5 行代码避免 `s09e14-1080p` 误判 |
| 季号合法性校验：`200 ≤ season < 1928 || season > 2500` 判废 | W4 | **高**：与 FileBot sanity 互补 |
| 堆叠双正则（数字 / 字母 a-d）+ PartType 一致性 + 文件/目录类型一致性 | W8 | **高** |
| `IsEligibleForMultiVersion`：同文件夹、同年、文件名以文件夹名开头、剩余清洗后为空或 `[-_.]` 或 `[...]` → 视为同片多版本 | 无 | 中：可用于"重复版本去重"场景 |
| 40 条 Extras 规则（trailer/sample/interview/behindthescenes/...） | W10 | 中 |
| 3D 格式 `Format3DParser`：precedingToken + token 双重匹配（`3d.sbs` 才算 SBS） | W6 | 中 |
| Anime 风格正则 `[\[Group\]][\[Series\]][\[12\]][\[1080p\]][\[FLAC\]][\[HASH\]]` | W7 | **高** |
| 年份优先级：`year = info.Year ?? parsedName.Year ?? 0` | 现有直接用 parsed.year | 中：在匹配阶段优先用 NFO/local 元数据（本项目暂无 NFO 解析） |
| 远程搜索「取 API 首个结果」 | 现有走打分 | **不借鉴**：Jellyfin 信任远程 relevance，但本项目无网络直连，必须本地打分 |

### 2.3 tinyMediaManager（gitlab.com/tinyMediaManager，devel 分支）

| 要点 | 现状对应 | 借鉴价值 |
|---|---|---|
| **`HARD_STOPWORDS` 词表（200+ 项）**，要求前后必须有分隔符才删除 | W1 | **极高**：直接复刻，替代 `TECH_TAIL` 启发式 |
| **标题边界 = 第一个停用词位置**（分词后找最早 stopword index，标题 = `[0, min(firstStop, yearPos))`） | W1 | **极高**：核心算法替换 |
| `SOFT_STOPWORDS`（complete/custom/dc/extended/proper/limited/se/...）条件性清洗 | W5 部分覆盖 | **高** |
| `MovieEdition` 13 个版本标签正则（Director's Cut / Extended / IMAX / Remastered / Criterion / Open Matte / ...） | W5 | **高**：直接移植 |
| `calculateYearPenalty`：`0.01 + diff/1000` 平滑惩罚（1 年扣 0.011，10 年扣 0.02，封顶 0.11） | M1 | **极高**：替代二值奖励 |
| 多算法取 max：Strike-A-Match（Dice bigram）+ Jaro-Winkler + 搜索串去年份再比一次 | M2 | **极高** |
| Stacking 4 pattern 组合（`CD1`/`discA`/`-a`/`1of2`），区分文件 stacking 与文件夹 stacking | W8 | **高** |
| 多集区间上限保护：`S01E05-07` 仅当 `end>start` 展开，且 `end-start>5` 丢弃 | W2 | **高**：与 FileBot `max-min≥9` 取更保守的 5 |
| Anime 单独通道（在通用 SxxExx 之前先跑），`[crc8hex]` 作锚点，`Special/OVA` 强制 season=0 | W7 | 中（与 Jellyfin Anime 正则二选一） |
| Edition 用 DynaEnum + 正则 + 文件名显式 `{edition-X}` | W5 | 中（项目暂不支持 edition 注入语法） |
| `morphTemplate` token 边界正则 `\$\{key([^a-zA-Z0-9])` | 命名引擎 [TemplateEngine.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/naming/TemplateEngine.kt) | 低：与本次优化无关 |
| HDR / 3D / DV **从 MediaInfo 读**而非文件名 | 红线冲突 | **不借鉴**：本项目不读文件二进制 |
| `parseNumbers1/2/3/4` 兜底猜季集 | 绝对集号已有 | **不借鉴**：TMM 自承认误报率高 |

---

## 3. 提议变更（按优先级）

### P0 — 高 ROI、低风险，第一批落地

#### P0.1 引入 `HARD_STOPWORDS` 词表替代 `TECH_TAIL` 启发式

**动机**：当前 [FilenameParser.kt#L424](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt#L424-L424) 的 `TECH_TAIL` 用单个正则「从首个技术词到结尾」截断，遇到技术词出现在标题中间（如 `Blue.Meridian.720p.Source.x264-GROUP.mkv`，标题就是 `Blue Meridian`）能工作，但遇到 `The.4K.Restoration.Movie.2009.mkv` 这种「4K 在前」就会过早截断。

**做法**：
1. 在 `parser/` 新增 `ReleaseInfoDictionary.kt`，集中托管 `HARD_STOPWORDS`（来源：TMM `ParserUtils.HARD_STOPWORDS`，补中文场景必要的几个）和 `SOFT_STOPWORDS`。
2. `cleanTitle` 改为：先用 `DELIMITER` 分词 → 从尾部向前找第一个非停用词 token → 标题 = 拼接 `[0, firstStopFromTail)`。年份 / SxE / PART 等位置仍按现有逻辑剔除。
3. 保留 `HARD_STOPWORDS` 的「前后必须有分隔符」约束（避免误删 `720p` 子串匹配到 `720pMovie` 这类拼接词）。

**预期效果**：标题边界判定准确率提升，且对历史 36 个测试用例不产生回归（需在 [FilenameParserTest.kt](file:///workspace/core/src/test/kotlin/xa/refile/core/parser/FilenameParserTest.kt) 全绿后合入）。

#### P0.2 多集区间上限保护

**动机**：W2。`Show.S01E01-E99.mkv` 会被展开为 99 集，明显是季包误判。

**做法**：在 [FilenameParser.kt#L236-L244](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt#L236-L244) `parseEpisodeList` 中，区间展开前判断 `end - start > MAX_EPISODE_RANGE`（取 5，比 TMM 的 5 一致、比 FileBot 的 9 更保守）则**只保留首尾两个集号**，不展开中间。或更保守：直接丢弃整个区间只取 `start`（与 TMM 「>5 集丢弃」语义一致）。**采用「只取 start」**。

#### P0.3 多集尾号字符保护

**动机**：W3。`s09e14-1080p` 会被 `RANGE_EP` 匹配 `14-108` 区间。

**做法**：在 [FilenameParser.kt#L387](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt#L387-L387) `RANGE_EP` 后增加后置校验——若 `end` 后紧跟 `[0-9iIpP]` 字符则视为分辨率片段，丢弃 `end`，只保留 `start`。这是 Jellyfin `EpisodePathParser.ParseSingle` 的核心防误报逻辑。

#### P0.4 季集号 sanity 上限

**动机**：W4。

**做法**：在 [FilenameParser.kt#L139-L195](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt#L139-L195) `parseSeasonEpisode` 返回前统一校验：
- `season != null && (season == 0 || season in 1..50)` — season=0 表示特别篇（与 TMM 一致）
- `episodes.all { it in 1..50 }` — 单季集号上限
- 绝对集号上限 `it in 1..1000`

超出范围视为误判，丢弃该字段。**注意**：年份判断已有 `1900..2099`，无需重复。

#### P0.5 年份惩罚替代二值奖励

**动机**：M1。TMM `calculateYearPenalty` 是久经验证的平滑函数。

**做法**：在 [MatchEngine.kt#L56-L58](file:///workspace/core/src/main/kotlin/xa/refile/core/matcher/MatchEngine.kt#L56-L58) 替换 `YEAR_BONUS` 逻辑为：

```kotlin
// 搜索没给年 → 不罚；结果没年 → 最大罚 0.11；其余按 0.01 + diff/1000 平滑
private fun yearPenalty(parsed: ParsedFilename, candidate: MatchCandidate): Double {
    val sy = parsed.year ?: return 0.0
    val cy = candidate.year ?: return MAX_YEAR_PENALTY
    val diff = kotlin.math.abs(sy - cy)
    if (diff > 100) return MAX_YEAR_PENALTY
    return 0.01 + diff / 1000.0
}
```

`MAX_YEAR_PENALTY = 0.11`。注意：原本 `+0.06` 奖励现在是「罚 0」；原本「+0」现在是「罚 0.01~0.11」。为保持 `score` 总体在 `[0,1]`，需要把 `TITLE_WEIGHT` 从 0.9 调到 0.95（或重新调参 `autoThreshold`）。**调参方案见 §4**。

#### P0.6 多算法相似度取 max

**动机**：M2。

**做法**：在 [MatchEngine.kt#L46-L62](file:///workspace/core/src/main/kotlin/xa/refile/core/matcher/MatchEngine.kt#L46-L62) `ConfidenceScorer.score` 中保留 Jaccard 与 Levenshtein，新增：
- **Strike-A-Match（Dice bigram）**：对换序鲁棒，`The Last of Us` ↔ `Last of Us, The` 仍能高分
- **Jaro-Winkler**：对前缀敏感，处理 `Lost` ↔ `Lost Girl` 这类前缀完全相同的场景
- **搜索串去年份再比一次**：`parsed.title + " " + parsed.year` 与 `candidate.name` 比对，处理 TMDB 返回名带年份的场景

最终 `titleScore = max(jaccard, levenshtein, dice, jaroWinkler, yearStripped)`。

#### P0.7 音译归一化

**动机**：M3。

**做法**：在 `ConfidenceScorer.normalize` 中追加 `java.text.Transliterator.getInstance("Any-Latin; Latin-ASCII; [:Diacritic:] remove")`。Android API 21+ 支持。注意：归一化只用于相似度计算，不影响展示用 `candidate.name`。

### P1 — 中等 ROI，第二批落地

#### P1.1 Edition 标签识别

**动机**：W5。TMM `MovieEdition` 13 个标签 + `TvShowEpisodeEdition` 4 个标签。

**做法**：
1. 在 `parser/` 新增 `EditionTags.kt`，定义 `enum class Edition(val pattern: Regex)`，覆盖：Director's Cut / Extended / Theatrical / Unrated / Uncut / IMAX / Remastered / Collector's / Ultimate / Final Cut / Special / Criterion / Open Matte。
2. 在 [ParsedFilename.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/ParsedFilename.kt) 新增 `val edition: String? = null` 字段。
3. `FilenameParser.parse` 增加一步 `parseEdition(spaced)`，在 `parseVersion` 之后调用。识别到的 edition 标签也从 `cleanTitle` 中剔除（避免污染标题相似度）。
4. 在 [naming/TemplateEngine.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/naming/TemplateEngine.kt) 模板支持 `${edition}` token，供用户在重命名时保留版本信息。

#### P1.2 HDR / 3D / DV 标签识别（仅从文件名）

**动机**：W6。**红线遵守**：不读文件二进制，仅解析文件名中明文出现的标签。

**做法**：
1. 在 [ParsedFilename.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/ParsedFilename.kt) 新增字段 `val hdr: String? = null`（HDR10 / HDR10+ / Dolby Vision / HLG）、`val threeD: String? = null`（3D SBS / 3D TAB / 3D MVC）。
2. 在 [FilenameParser.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt) `parseTech` 中追加对应正则。参考 TMM `MediaFileHelper.detectHdrFormat` 的 token 映射（但来源从 MediaInfo 改为文件名子串）：
   - `HDR10+` ← `(?i)(?<![A-Za-z0-9])(HDR10\+|HDR10Plus)(?![A-Za-z0-9])`
   - `HDR10` ← `(?i)(?<![A-Za-z0-9])HDR10(?!\+)(?![A-Za-z0-9])`
   - `Dolby Vision` ← `(?i)(?<![A-Za-z0-9])(DV|Dolby\.?Vision)(?![A-Za-z0-9])`
   - `HLG` ← `(?i)(?<![A-Za-z0-9])HLG(?![A-Za-z0-9])`
3. 3D 格式按 Jellyfin `Format3DRules` 双重匹配：`3d.sbs` / `3d.tab` / `hsbs` / `htab` / `mvc`。

#### P1.3 Anime CRC 方括号命名识别

**动机**：W7。

**做法**：在 [FilenameParser.kt#L87-L116](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt#L87-L116) `extractBracketed` 之前增加一步 `tryAnimePattern(baseName)`，使用 Jellyfin 表达式 26：

```kotlin
private val ANIME_BRACKET = Regex(
    """(?:\[(?:[^\]]+)\]\s*)?(?<seriesname>\[[^\]]+\]|[^[\]]+)\s*\[(?<epnumber>\d+)\]"""
)
```

命中后：`title = seriesname`、`episodes = [epnumber]`、`isAbsoluteEpisode = true`、跳过通用季集解析。8 位 hex CRC 也单独识别并丢弃（不污染标题）。

#### P1.4 Stacking 扩展

**动机**：W8。

**做法**：在 [FilenameParser.kt#L422](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt#L422-L422) `PART` 正则扩展，覆盖 TMM 4 个 pattern：

```kotlin
// 现有：CD1/Disc1/Part1/PT1
// 新增：CDa/PartB（字母 a-d）
private val PART_LETTER = Regex("""(?i)(?:^|\s)(?:CD|DISC|PART|PT)\s?([a-d])(?:$|\s)""")
// 新增：1of2 / 1 of 2 / 1⁄2（Unicode 斜杠）
private val PART_OF = Regex("""(?i)(?:^|\s)(\d{1,2})\s?(?:of|⁄|∕|/)\s?\d{1,2}(?:$|\s)""")
// 新增：-a 后缀（仅当文件名末尾的连字符+单字母）
private val PART_SUFFIX = Regex("""[-_\.]([a-d])$""")
```

`partIndex` 字段语义调整：字母 a-d 映射为 1-4，`1of2` 取被除数。注意 `PART_SUFFIX` 必须在确认非扩展名后才应用，避免误伤 `.mkv.a` 这种边界（实际不会出现，但需测试）。

#### P1.5 IMDb ID 提取

**动机**：W9。TMDB `find/imdb` 接口可精确查找，无需标题相似度。

**做法**：
1. 在 [ParsedFilename.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/ParsedFilename.kt) 新增 `val imdbId: String? = null`。
2. 在 [FilenameParser.kt](file:///workspace/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt) `parse` 第 1 步后增加 `parseImdbId(raw)`：
   ```kotlin
   private val IMDB_ID = Regex("""(?<![A-Za-z0-9])tt(\d{7,8})(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)
   ```
3. 在 [BatchMatchViewModel.kt](file:///workspace/app/src/main/kotlin/xa/refile/ui/match/BatchMatchViewModel.kt) 或匹配编排层：若 `parsed.imdbId != null`，跳过标题搜索，直接调用 `TmdbClient.findByImdbId(imdbId)`，返回的条目作为唯一候选（`MatchDecision.Auto`）。这是 FileBot 与 Jellyfin 共用的优化路径。

#### P1.6 流媒体来源标记扩展

**动机**：W11。2020+ 资源常带 `AMZN`/`NF`/`ATVP`/`HMAX`/`STZ`/`PCOK`/`MA`/`NBC` 等流媒体来源标记。

**做法**：在 [FilenameParser.kt#L417](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt#L417-L417) `SOURCE_TOKEN` 追加：

```kotlin
"AMZN" → "Amazon"
"NF" → "Netflix"
"ATVP" → "Apple TV+"
"HMAX" → "HBO Max"
"STZ" → "Starz"
"PCOK" → "Peacock"
"MA" → "Movies Anywhere"
"NBC" → "NBC"
"CR" → "Crunchyroll"
"DSNP" → "Disney+"
"HULU" → "Hulu"
```

注意：`MA` 与 `MA`（多音频 multi-audio）易混淆，需在边界外额外校验前后是否为非字母数字（已有 `(?<![A-Za-z0-9])` 边界）。

#### P1.7 字幕语言标签解析

**动机**：W12。`Movie.zh.forced.srt` 需要解析出 `language=zh`、`forced=true` 才能正确配对视频。

**做法**：在 `parser/` 新增 `SubtitleInfo.kt`（`data class SubtitleInfo(val language: String?, val forced: Boolean, val default: Boolean, val hearingImpaired: Boolean)`）。`FilenameParser.parse` 增加分支：若扩展名属于 `subtitleExtensions`，则解析语言标签。正则参考 FileBot `getSubtitleLanguageTagPattern`：

```kotlin
private val SUBTITLE_LANG_TAG = Regex("""(?<=[._-])([a-z]{2,3})(?=([._-](forced|default|cc|hi|sdh))?$)""", RegexOption.IGNORE_CASE)
```

### P2 — 较低 ROI 或需上层配合

#### P2.1 量化分数（4 档）

**动机**：M7。FileBot `floor(x*4)/4`。

**做法**：在 `ScoredCandidate.score` 上加 `floor(score * 4) / 4` 量化。注意：量化后 `margin` 判定需调小（原本 0.1 现在可能改成 0.05）。**风险**：量化会丢失「完全相同 vs 99% 相似」的区分，需观察测试用例是否仍能正确触发 `Auto`。**建议先作为可选开关，验证后再默认开启**。

#### P2.2 SxE 互校

**动机**：M5。FileBot `EpisodeBalancer`。

**做法**：
1. 扩展 [MatchEngine.kt#L9-L17](file:///workspace/core/src/main/kotlin/xa/refile/core/matcher/MatchEngine.kt#L9-L17) `MatchCandidate` 增加 `val season: Int? = null`、`val episodes: List<Int> = emptyList()`（仅当候选是 episode 且携带 SxE 时填，否则为 null/空）。
2. 在 `ConfidenceScorer.score` 增加：若 `parsed.season != null && candidate.season != null && parsed.season == candidate.season`，且集号也有交集 → `+ SXE_BONUS`（建议 0.1，仅次于标题权重）。
3. **数据来源**：当前 `TmdbMapper.toMediaMetadata(tv, season, episodeNumbers, ...)` 已经持有 episodeNumbers，但未传到 MatchCandidate。需要在 [BatchMatchViewModel.kt](file:///workspace/app/src/main/kotlin/xa/refile/ui/match/BatchMatchViewModel.kt) 把 episode-level 元数据映射到 MatchCandidate。**工作量大，建议 P2 后期做**。

#### P2.3 冠词归一化

**动机**：M4。

**做法**：在 `ConfidenceScorer.normalize` 中追加：去除前导 `The/A/An` 后再比对。注意：**只在比对时归一化**，不修改 `parsed.title` 本身（保留展示用原名）。

#### P2.4 ID 优先级查找

**动机**：M6。

**做法**：在匹配编排层（不在 `MatchEngine` 内部）：若 `parsed.imdbId != null` 或 NFO 提供的 ID 不为空，跳过标题搜索走 `TmdbClient.findByImdbId/findByTvdbId`，得到的结果作为唯一候选，直接 `MatchDecision.Auto`。与 P1.5 配套。

#### P2.5 Extras 识别

**动机**：W10。

**做法**：在 `parser/` 新增 `ExtraType.kt`（`enum class ExtraType { TRAILER, SAMPLE, INTERVIEW, BEHIND_THE_SCENES, DELETED_SCENE, FEATURETTE, SHORT, CLIP, OTHER }`）。`FilenameParser.parse` 检测后缀模式 `-trailer` / `-sample` / `-interview` 等（参考 Jellyfin `VideoExtraRules` 后缀类），命中则填入 `ParsedFilename.extraType`。重命名器可选择跳过或单独命名。

#### P2.6 清洗链式迭代

**动机**：Jellyfin `CleanStringParser` 6 条规则链式。

**做法**：把 [FilenameParser.cleanTitle](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt#L301-L332) 拆成多个独立 `cleanStep` 函数，按顺序迭代应用直到结果稳定（每轮输出作下轮输入）。**与 P0.1 配套**：词表替换本身就是其中一步。

---

## 4. 调参与测试

### 4.1 阈值调整

P0.5/P0.6 实施后，权重体系从：

```
TITLE_WEIGHT = 0.9, YEAR_BONUS = 0.06, POP_WEIGHT = 0.04, autoThreshold = 0.85, margin = 0.1
```

调整为：

```
TITLE_WEIGHT = 0.85          // 多算法 max 后整体偏高，略降
YEAR_PENALTY_MAX = 0.11      // 替代 YEAR_BONUS
POP_WEIGHT = 0.04            // 不变
autoThreshold = 0.82         // 因年份从「+0」变「-0.11」，整体分数下移，阈值下调
margin = 0.08                // 多算法后噪声降低，margin 可收紧
```

**校准方法**：在 [MatchEngineTest.kt](file:///workspace/core/src/test/kotlin/xa/refile/core/matcher/MatchEngineTest.kt) 现有 6 个用例基础上，增加至少 8 个新用例（见 §5），跑全绿后微调。

### 4.2 回归测试

- [FilenameParserTest.kt](file:///workspace/core/src/test/kotlin/xa/refile/core/parser/FilenameParserTest.kt) 现有 36 个用例必须全绿
- [MatchEngineTest.kt](file:///workspace/core/src/test/kotlin/xa/refile/core/matcher/MatchEngineTest.kt) 现有 6 个用例必须全绿
- 新增用例覆盖每个 P0/P1 项的边界（见 §5）

### 4.3 性能

- `FilenameParser.parse` 单次 < 1ms（现状 < 0.5ms，引入词表分词后预计 < 1ms，可接受）
- `ConfidenceScorer.score` 单次 < 0.1ms（现状 < 0.05ms，新增 Strike-A-Match + Jaro-Winkler 后预计 < 0.15ms，可接受；候选数通常 < 10，影响可忽略）

---

## 5. 新增测试用例（清单）

### Parser 测试（追加到 [FilenameParserTest.kt](file:///workspace/core/src/test/kotlin/xa/refile/core/parser/FilenameParserTest.kt)）

```
// P0.2 多集区间上限
- `Show.S01E01-E99.mkv` → episodes=[1]（仅 start，区间被丢弃）
- `Show.S01E01-E05.mkv` → episodes=[1,2,3,4,5]（边界，仍展开）

// P0.3 多集尾号保护
- `Show.s09e14-1080p.mkv` → episodes=[14]，不误判为 [14..108]
- `Show.S01E01-E03.720p.mkv` → episodes=[1,2,3]，720p 不被吃进区间

// P0.4 sanity 上限
- `Show.S99E99.mkv` → season=null, episodes=[]（季号超限丢弃）
- `Show.S01E51.mkv` → episodes=[]（集号超限丢弃）

// P0.1 词表清洗
- `The.4K.Restoration.Movie.2009.mkv` → title="The 4K Restoration Movie"（4K 在前不被截断）

// P1.1 Edition
- `Movie.2009.Directors.Cut.1080p.mkv` → edition="Director's Cut"
- `Movie.2009.IMAX.mkv` → edition="IMAX"
- `Movie.Extended.Edition.2009.mkv` → edition="Extended"

// P1.2 HDR / 3D
- `Movie.2023.2160p.UHD.HDR10+.mkv` → hdr="HDR10+"
- `Movie.2023.2160p.UHD.DV.mkv` → hdr="Dolby Vision"
- `Movie.2010.3D.SBS.mkv` → threeD="3D SBS"

// P1.3 Anime CRC
- `[Group][Series Name][12][1080p][FLAC][A1B2C3D4].mkv` → title="Series Name", episodes=[12], isAbsoluteEpisode=true

// P1.4 Stacking 扩展
- `Movie.CDa.mkv` → partIndex=1
- `Movie.1of2.mkv` → partIndex=1

// P1.5 IMDb ID
- `Movie.tt1234567.mkv` → imdbId="tt1234567"
- `Movie.[tt1234567].mkv` → imdbId="tt1234567"

// P1.6 流媒体来源
- `Movie.2023.2160p.WEB-DL.DDP5.1.Atmos.HDR10+.AMZN.mkv` → source 包含 "Amazon"

// P1.7 字幕语言
- `Movie.zh.forced.srt` → 扩展名识别为字幕，language="zh", forced=true
- `Movie.en.srt` → language="en", forced=false
```

### Matcher 测试（追加到 [MatchEngineTest.kt](file:///workspace/core/src/test/kotlin/xa/refile/core/matcher/MatchEngineTest.kt)）

```
// P0.5 年份惩罚
- `year diff=1` → 候选 score 比 year match 低约 0.011
- `year diff=30` → 候选 score 比 year match 低约 0.04
- `parsed.year=null` → 不惩罚（保持原分）

// P0.6 多算法取 max
- `parsed.title="Last of Us, The"` vs `candidate.name="The Last of Us"` → Strike-A-Match 高分，触发 Auto
- `parsed.title="Café"` vs `candidate.name="Cafe"` → 经 P0.7 音译后高分

// P0.7 音译
- `parsed.title="Amélie"` vs `candidate.name="Amelie"` → 触发 Auto

// P2.3 冠词归一化
- `parsed.title="Matrix, The"` vs `candidate.name="The Matrix"` → 高分
```

---

## 6. 不在本期范围

明确**不做**的事项（避免范围蔓延）：

- ❌ NFO 文件解析（FileBot/Jellyfin/TMM 都支持，但本项目架构暂未规划）
- ❌ MediaInfo / FFprobe 读取文件二进制（红线）
- ❌ AniDB 客户端（FileBot 特色，但项目只用 TMDB）
- ❌ TVDB 客户端（同上）
- ❌ JMTE 模板引擎替换（TMM 用，本项目 [TemplateEngine.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/naming/TemplateEngine.kt) 已够用）
- ❌ xattr 持久化（FileBot 用，本项目 [TmdbCacheRepository](file:///workspace/core/src/main/kotlin/xa/refile/core/repository/TmdbCacheRepository.kt) DB 缓存已足够）
- ❌ 完整重构成 `EpisodeExpression` 数据类 + 标志位过滤（Jellyfin 风格），工作量过大且现有正则顺序已合理
- ❌ Edition `{edition-X}` 注入语法（TMM 文件名显式标记，需要上层 UI 配合，推迟）

---

## 7. 落地顺序

1. **Sprint 1（P0）**：P0.1 词表 → P0.2 区间上限 → P0.3 尾号保护 → P0.4 sanity → 配套测试。Parser 改动告一段落。
2. **Sprint 2（P0 续）**：P0.5 年份惩罚 → P0.6 多算法 → P0.7 音译 → 调参 → 配套测试。Matcher 改动告一段落。
3. **Sprint 3（P1）**：P1.1 Edition → P1.2 HDR/3D → P1.3 Anime CRC → P1.4 Stacking → P1.5 IMDb ID → P1.6 流媒体来源 → P1.7 字幕语言。每项独立可单独合入。
4. **Sprint 4（P2 视情况）**：P2.1 量化 → P2.2 SxE 互校 → P2.3 冠词 → P2.4 ID 优先级 → P2.5 Extras → P2.6 清洗迭代。

---

## 8. 参考文档

- FB-Mod / FileBot 4.8.5 源码：https://github.com/barry-allen07/FB-Mod
- Jellyfin 源码：https://github.com/jellyfin/jellyfin （`Emby.Naming/` + `MediaBrowser.Providers/`）
- tinyMediaManager 源码：https://gitlab.com/tinyMediaManager/tinyMediaManager （`org.tinymediamanager.scraper.util.ParserUtils` / `MetadataUtil` / `core.tvshow.TvShowEpisodeAndSeasonParser`）
- 详细研究分报告见同目录 `research-fbmod.md`、`research-jellyfin.md`、`research-tmm.md`（如需保留可单独生成；当前已并入本 spec 的 §2）
