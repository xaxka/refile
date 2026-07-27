# 验收清单（Checklist）

> 配套 `spec.md` 与 `tasks.md`。每个 Sprint 完成前需逐项确认。

---

## 通用验收

- [ ] 所有现有测试用例全绿
  - [ ] [FilenameParserTest.kt](file:///workspace/core/src/test/kotlin/xa/refile/core/parser/FilenameParserTest.kt) 36 项
  - [ ] [MatchEngineTest.kt](file:///workspace/core/src/test/kotlin/xa/refile/core/matcher/MatchEngineTest.kt) 6 项
  - [ ] 其他 core 模块测试无回归（`./gradlew :core:test`）
- [ ] 新增测试用例全绿（见 spec §5）
- [ ] 性能达标
  - [ ] `FilenameParser.parse` 单次 < 1ms（1000 次随机文件名基准）
  - [ ] `ConfidenceScorer.score` 单次 < 0.15ms（100 候选基准）
- [ ] 无新增编译警告
- [ ] 无新增依赖（除 P0.7 音译若需 ICU4J 替代）
- [ ] 代码风格符合现有约定（4 空格缩进、KDoc 注释、companion object 集中正则）

---

## Sprint 1 — Parser P0 验收

- [ ] P0.1 词表
  - [ ] `ReleaseInfoDictionary.kt` 已创建，`HARD_STOPWORDS` ≥ 150 项
  - [ ] `cleanTitle` 改用词表分词判定边界，不再依赖 `TECH_TAIL`
  - [ ] 新增用例 `The.4K.Restoration.Movie.2009.mkv` 通过
- [ ] P0.2 区间上限
  - [ ] `MAX_EPISODE_RANGE = 5` 常量已定义
  - [ ] `Show.S01E01-E99.mkv` → episodes=[1]
  - [ ] `Show.S01E01-E05.mkv` → episodes=[1,2,3,4,5]（边界）
- [ ] P0.3 尾号保护
  - [ ] `Show.s09e14-1080p.mkv` → episodes=[14]（不误判为 [14..108]）
  - [ ] `Show.S01E01-E03.720p.mkv` → episodes=[1,2,3]
- [ ] P0.4 sanity
  - [ ] season 上限 50（含 0 表示特别篇）
  - [ ] episode 上限 50
  - [ ] absolute episode 上限 1000
  - [ ] `Show.S99E99.mkv` → season=null, episodes=[]
  - [ ] `Show.S01E51.mkv` → episodes=[]

---

## Sprint 2 — Matcher P0 验收

- [ ] P0.5 年份惩罚
  - [ ] `YEAR_BONUS` 已移除
  - [ ] `yearPenalty` 函数已实现
  - [ ] `year diff=1` → 罚约 0.011
  - [ ] `year diff=30` → 罚约 0.04
  - [ ] `parsed.year=null` → 不罚
  - [ ] `candidate.year=null` → 罚 0.11
- [ ] P0.6 多算法
  - [ ] Strike-A-Match（Dice bigram）已实现
  - [ ] Jaro-Winkler 已实现（scalingFactor=0.1, maxPrefix=4）
  - [ ] 搜索串去年份再比一次已实现
  - [ ] `titleScore` 取所有算法 max
  - [ ] 换序用例通过（`Last of Us, The` vs `The Last of Us`）
- [ ] P0.7 音译
  - [ ] `Transliterator` 已接入（或替代方案）
  - [ ] `Amélie` vs `Amelie` 高分
  - [ ] `Café` vs `Cafe` 高分
- [ ] P0.8 阈值调参
  - [ ] `TITLE_WEIGHT = 0.85`、`autoThreshold = 0.82`、`margin = 0.08`
  - [ ] 现有 6 个 matcher 用例全绿
  - [ ] 新增 matcher 用例全绿

---

## Sprint 3 — Parser P1 验收

- [ ] P1.1 Edition
  - [ ] `EditionTags.kt` 已创建，13 项 edition
  - [ ] `ParsedFilename.edition` 字段已加
  - [ ] `Director's Cut` / `IMAX` / `Extended` 用例通过
  - [ ] `${edition}` 模板 token 已支持
- [ ] P1.2 HDR / 3D
  - [ ] `ParsedFilename.hdr` / `threeD` 字段已加
  - [ ] `HDR10+` / `HDR10` / `Dolby Vision` / `HLG` 识别正确
  - [ ] `3D SBS` / `3D TAB` / `3D MVC` 识别正确（双重匹配）
- [ ] P1.3 Anime CRC
  - [ ] `[Group][Series Name][12][1080p][FLAC][A1B2C3D4].mkv` 用例通过
  - [ ] CRC 8 位 hex 单独识别并丢弃
  - [ ] 不影响普通 `[Group] Movie.mkv` 解析
- [ ] P1.4 Stacking
  - [ ] `Movie.CDa.mkv` → partIndex=1
  - [ ] `Movie.1of2.mkv` → partIndex=1
  - [ ] 现有 `Movie.CD1.mkv` / `Movie.Disc2.mkv` 用例不回归
- [ ] P1.5 IMDb ID
  - [ ] `ParsedFilename.imdbId` 字段已加
  - [ ] `Movie.tt1234567.mkv` → imdbId="tt1234567"
  - [ ] `Movie.[tt1234567].mkv` → imdbId="tt1234567"
  - [ ] `TmdbClient.findByImdbId` 已具备（或补齐）
  - [ ] 匹配编排层：`parsed.imdbId != null` 时跳过标题搜索
- [ ] P1.6 流媒体来源
  - [ ] 11 项流媒体 token 已加
  - [ ] `AMZN` → `Amazon` 用例通过
  - [ ] `MA` 不与 multi-audio 混淆（边界已校验）
- [ ] P1.7 字幕语言
  - [ ] `SubtitleInfo.kt` 已创建
  - [ ] `ParsedFilename.subtitleInfo` 字段已加
  - [ ] `Movie.zh.forced.srt` → language="zh", forced=true
  - [ ] `Movie.en.srt` → language="en", forced=false

---

## Sprint 4 — P2 验收（可选）

- [ ] P2.1 量化
  - [ ] `quantize` 开关已加
  - [ ] 开启后 `margin = 0.05` 调整
  - [ ] 全部用例仍触发预期决策
- [ ] P2.2 SxE 互校
  - [ ] `MatchCandidate.season` / `episodes` 字段已加
  - [ ] SxE 匹配加分逻辑已实现
  - [ ] `parsed S01E02 + candidate S01E02` → 触发 Auto
- [ ] P2.3 冠词归一化
  - [ ] `Matrix, The` vs `The Matrix` 高分用例通过
- [ ] P2.4 ID 优先级
  - [ ] imdbId 直查路径已通
  - [ ] 性能：跳过标题搜索后单次匹配 < 100ms（含网络）
- [ ] P2.5 Extras
  - [ ] `ExtraType.kt` 已创建
  - [ ] trailer/sample/interview 后缀识别正确
- [ ] P2.6 清洗链式
  - [ ] `cleanTitle` 拆分为多个 `cleanStep`
  - [ ] 迭代至稳定
  - [ ] 现有用例无回归

---

## 文档与提交

- [ ] spec.md / tasks.md / checklist.md 三件套齐全
- [ ] 每个任务对应 1 个独立 commit（commit message 含任务编号 P0.x / P1.x）
- [ ] PR 描述引用 spec.md 章节
- [ ] 变更涉及 [TemplateEngine.kt](file:///workspace/core/src/main/kotlin/xa/refile/core/naming/TemplateEngine.kt) 时同步更新 [TemplateEngineTest.kt](file:///workspace/core/src/test/kotlin/xa/refile/core/naming/TemplateEngineTest.kt)
