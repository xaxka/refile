# 实施任务清单（Tasks）

> 配套 `spec.md`。每项任务对应 spec 中的一个变更点。建议按 Sprint 顺序串行，Sprint 内 P0.x 可并行（无文件冲突）。

---

## Sprint 1 — Parser P0（高 ROI 防误判）

### Task P0.1 — 引入 HARD_STOPWORDS 词表

- [ ] 新建 `core/src/main/kotlin/xa/refile/core/parser/ReleaseInfoDictionary.kt`
  - `HARD_STOPWORDS: Set<String>`（来源 TMM，约 200 项；补中文场景必要 token 如 `国配`/`简繁`/`内嵌`/`双语`）
  - `SOFT_STOPWORDS: Set<String>`（complete / custom / dc / extended / proper / limited / se / ...）
  - `DELIMITER = "[\\[\\](){} _,.-]"`
- [ ] 重构 [FilenameParser.kt#cleanTitle](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt)
  - 用 `DELIMITER` 分词
  - 从尾部向前找第一个非停用词 token，标题 = `[0, firstStopIndex)`
  - 保留：年份剔除、SxE/PART/Version 剔除（这些仍是单独步骤）
- [ ] 测试：现有 36 个用例全绿 + 新增 `The.4K.Restoration.Movie.2009.mkv` 用例
- [ ] 性能验证：`parse()` 单次 < 1ms

### Task P0.2 — 多集区间上限保护

- [ ] 修改 [FilenameParser.kt#parseEpisodeList](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt) L224-L244
  - 区间展开前判断 `end - start > MAX_EPISODE_RANGE`（=5）
  - 超限则只取 `start`，丢弃 end 与中间
- [ ] 测试：`Show.S01E01-E99.mkv` → episodes=[1]；`Show.S01E01-E05.mkv` → episodes=[1,2,3,4,5]

### Task P0.3 — 多集尾号字符保护

- [ ] 修改 [FilenameParser.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt) `RANGE_EP` 匹配后处理
  - 若 `end` 数字后紧跟 `[0-9iIpP]` 字符 → 视为分辨率片段，丢弃 end
- [ ] 测试：`Show.s09e14-1080p.mkv` → episodes=[14]；`Show.S01E01-E03.720p.mkv` → episodes=[1,2,3]

### Task P0.4 — 季集号 sanity 上限

- [ ] 在 [FilenameParser.kt#parseSeasonEpisode](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt) 返回前增加 sanity 校验
  - `season == 0 || season in 1..50` 否则置 null
  - `episodes.all { it in 1..50 }` 否则置空
  - 绝对集号上限 `it in 1..1000`
- [ ] 测试：`Show.S99E99.mkv` → season=null, episodes=[]；`Show.S01E51.mkv` → episodes=[]；`Show.S00E01.mkv` → season=0（特别篇允许）

---

## Sprint 2 — Matcher P0（评分体系重构）

### Task P0.5 — 年份惩罚替代二值奖励

- [ ] 修改 [MatchEngine.kt#ConfidenceScorer](file:///workspace/core/src/main/kotlin/xa/refile/core/matcher/MatchEngine.kt)
  - 删除 `YEAR_BONUS`
  - 新增 `private fun yearPenalty(parsed, candidate): Double`
  - 公式：`sy==null → 0`；`cy==null → 0.11`；`diff>100 → 0.11`；`else 0.01 + diff/1000`
- [ ] `score` 计算改为 `titleScore * TITLE_WEIGHT - yearPenalty + popBonus`
- [ ] 测试：`year diff=1/30/100` 三个用例 + `parsed.year=null` 不惩罚用例

### Task P0.6 — 多算法相似度取 max

- [ ] 在 [MatchEngine.kt#ConfidenceScorer](file:///workspace/core/src/main/kotlin/xa/refile/core/matcher/MatchEngine.kt) 新增算法
  - `diceBigram(a, b)`：Strike-A-Match，相邻字母对 Dice 系数
  - `jaroWinkler(a, b)`：Jaro-Winkler，scalingFactor=0.1，maxPrefix=4
  - `yearStrippedSim`：搜索串去年份再比一次（取 jaccard 与 dice 的 max）
- [ ] `titleScore = max(jaccard, levenshtein, dice, jaroWinkler, yearStrippedSim, alias)`
- [ ] 测试：换序用例 + 前缀敏感用例

### Task P0.7 — 音译归一化

- [ ] 在 `ConfidenceScorer.normalize` 中追加 `Transliterator.getInstance("Any-Latin; Latin-ASCII; [:Diacritic:] remove")`
- [ ] 验证 Android API 21+ 可用（`java.text.Transliterator` 在 Android 上支持有限，需测；若不支持则降级为手写 ICU 替代或第三方库）
- [ ] 测试：`Amélie` vs `Amelie`、`Café` vs `Cafe`

### Task P0.8 — 阈值调参

- [ ] 调整 [MatchEngine.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/matcher/MatchEngine.kt) 常量
  - `TITLE_WEIGHT = 0.85`
  - `autoThreshold = 0.82`
  - `margin = 0.08`
- [ ] 跑 [MatchEngineTest.kt](file:///workspace/core/src/test/kotlin/xa/refile/core/matcher/MatchEngineTest.kt) 全部用例（含新增）
- [ ] 微调至全部用例通过

---

## Sprint 3 — Parser P1（功能补全）

### Task P1.1 — Edition 标签

- [ ] 新建 `core/src/main/kotlin/xa/refile/core/parser/EditionTags.kt`
  - `enum class Edition(val displayName: String, val pattern: Regex)`，13 项（来源 TMM `MovieEdition`）
- [ ] [ParsedFilename.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/ParsedFilename.kt) 增加 `val edition: String? = null`
- [ ] [FilenameParser.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt) 增加 `parseEdition(spaced)`，从 `cleanTitle` 中剔除识别到的 edition 标签
- [ ] [naming/TemplateEngine.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/naming/TemplateEngine.kt) 支持 `${edition}` token
- [ ] 测试：Director's Cut / IMAX / Extended 三个用例

### Task P1.2 — HDR / 3D 标签

- [ ] [ParsedFilename.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/ParsedFilename.kt) 增加 `val hdr: String? = null`、`val threeD: String? = null`
- [ ] [FilenameParser.kt#parseTech](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt) 追加 `parseHdr` + `parse3D`
  - HDR：`HDR10+` / `HDR10` / `DV|Dolby Vision` / `HLG`
  - 3D：`3d.sbs` / `3d.tab` / `hsbs` / `htab` / `mvc`（双重匹配）
- [ ] 测试：HDR10+ / DV / 3D SBS 三个用例

### Task P1.3 — Anime CRC 方括号命名

- [ ] [FilenameParser.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt) `extractBracketed` 前增加 `tryAnimePattern(baseName)`
  - 命中后填 title/episodes/isAbsoluteEpisode，跳过通用季集解析
  - 8 位 hex CRC 单独识别并丢弃
- [ ] 测试：`[Group][Series Name][12][1080p][FLAC][A1B2C3D4].mkv` 用例

### Task P1.4 — Stacking 扩展

- [ ] [FilenameParser.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt) 新增 3 个 PART 正则
  - `PART_LETTER`（CDa/PartB）
  - `PART_OF`（1of2 / 1 of 2 / 1⁄2）
  - `PART_SUFFIX`（-a 后缀，仅在末尾且非扩展名）
- [ ] `parsePart` 改为先试现有 `PART`，未命中再依次试 3 个新正则
- [ ] 字母 a-d 映射为 1-4
- [ ] 测试：`Movie.CDa.mkv` / `Movie.1of2.mkv` 两个用例

### Task P1.5 — IMDb ID 提取

- [ ] [ParsedFilename.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/ParsedFilename.kt) 增加 `val imdbId: String? = null`
- [ ] [FilenameParser.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt) 增加 `parseImdbId(raw)`，正则 `(?<![A-Za-z0-9])tt(\d{7,8})(?![A-Za-z0-9])`
- [ ] [TmdbClient.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/tmdb/TmdbClient.kt) 确认有 `findByImdbId` 方法（若无则补）
- [ ] [BatchMatchViewModel.kt](file:///workspace/app/src/main/kotlin/xa/refile/ui/match/BatchMatchViewModel.kt) 或匹配编排层：若 `parsed.imdbId != null`，跳过标题搜索直查
- [ ] 测试：`Movie.tt1234567.mkv` / `Movie.[tt1234567].mkv` 两个用例

### Task P1.6 — 流媒体来源标记

- [ ] [FilenameParser.kt#findSource](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt) L264-L279 增加 11 项流媒体映射
- [ ] `SOURCE_TOKEN` 正则追加：`AMZN|NF|ATVP|HMAX|STZ|PCOK|MA|NBC|CR|DSNP|HULU`
- [ ] 测试：`Movie.2023.2160p.WEB-DL.DDP5.1.Atmos.HDR10+.AMZN.mkv` 用例

### Task P1.7 — 字幕语言标签

- [ ] 新建 `core/src/main/kotlin/xa/refile/core/parser/SubtitleInfo.kt`
- [ ] [ParsedFilename.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/ParsedFilename.kt) 增加 `val subtitleInfo: SubtitleInfo? = null`
- [ ] [FilenameParser.kt#parse](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt) 增加分支：若扩展名属于 `subtitleExtensions`，解析语言标签
- [ ] 测试：`Movie.zh.forced.srt` / `Movie.en.srt` 两个用例

---

## Sprint 4 — P2（视情况）

### Task P2.1 — 分数量化

- [ ] [MatchEngine.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/matcher/MatchEngine.kt) 增加 `quantize(score) = floor(score * 4) / 4`
- [ ] 作为可选开关（构造参数 `quantize: Boolean = false`）
- [ ] 调整 `margin` 至 0.05
- [ ] 验证后默认开启

### Task P2.2 — SxE 互校

- [ ] [MatchEngine.kt#MatchCandidate](file:///workspace/core/src/main/kotlin/xa/refile/core/matcher/MatchEngine.kt) 增加 `val season: Int? = null`、`val episodes: List<Int> = emptyList()`
- [ ] `ConfidenceScorer.score` 增加 SxE 加分逻辑
- [ ] [BatchMatchViewModel.kt](file:///workspace/app/src/main/kotlin/xa/refile/ui/match/BatchMatchViewModel.kt) 把 episode-level 元数据映射到 MatchCandidate
- [ ] 测试：S01E02 解析 + 候选含 S01E02 → 触发 Auto

### Task P2.3 — 冠词归一化

- [ ] `ConfidenceScorer.normalize` 去除前导 `The/A/An` 后再比对
- [ ] 测试：`Matrix, The` vs `The Matrix` 高分

### Task P2.4 — ID 优先级查找

- [ ] 匹配编排层：若 `parsed.imdbId != null` 走 `findByImdbId`，得到唯一候选直接 `MatchDecision.Auto`
- [ ] 与 P1.5 配套

### Task P2.5 — Extras 识别

- [ ] 新建 `core/src/main/kotlin/xa/refile/core/parser/ExtraType.kt`
- [ ] [ParsedFilename.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/ParsedFilename.kt) 增加 `val extraType: ExtraType? = null`
- [ ] [FilenameParser.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt) 检测 trailer/sample/interview 等后缀

### Task P2.6 — 清洗链式迭代

- [ ] 把 [FilenameParser.cleanTitle](file:///workspace/core/src/main/kotlin/xa/refile/core/parser/FilenameParser.kt) 拆成多个 `cleanStep`
- [ ] 按顺序迭代应用直到结果稳定
- [ ] 与 P0.1 配套
