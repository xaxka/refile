# AGENTS.md — refile AI 代理协作指南

## 项目简介

Android 媒体文件重命名与整理工具（Vibe Coding 开发，个人自用，不承诺稳定维护）：通过 TMDB
元数据自动识别电影/电视剧并生成规范化文件名，支持 WebDAV 与 OpenList 协议远程重命名，多线程批量处理。

## 技术栈与结构

- 语言/框架：Kotlin 2.1.20 + Jetpack Compose（BOM 2025.03.01，Material 3）；AGP 8.7.3，JDK 17，minSdk 26，compile/targetSdk 35。
- 关键库：Hilt（DI）、Room 2.7.0、DataStore、WorkManager、Retrofit + OkHttp + kotlinx.serialization、Coil 3、dav4jvm（WebDAV）。
- Gradle 多模块，依赖统一由 `gradle/libs.versions.toml`（Version Catalog）管理：
  - `app/`：Android 应用。`ui/`（browser/match/preview/progress/history/servers/settings/navigation/theme/common 各 Compose 页面 + ViewModel）、`data/`（repository、db=Room、prefs=DataStore、backup、crypto=Keystore 加密）、`worker/`（WorkManager 后台任务）。
  - `core/`：纯 Kotlin JVM 公共模块，无 Android 依赖（parser 文件名解析、matcher 匹配引擎、tmdb 客户端、rename 执行器、naming 模板引擎、webdav/openlist 客户端、model、util）。
- 架构：MVVM，ViewModel 暴露 `StateFlow<UiState>`（`MutableStateFlow` 私有 + `asStateFlow()`），依赖注入统一 Hilt。

## 构建与 CI（仅作参考，本地禁止执行）

- `.github/workflows/android-build.yml`：push main 触发（同分支连推取消旧 run）。JDK 17（temurin），执行
  `./gradlew :core:test :app:testReleaseUnitTest :app:lintVitalRelease :app:assembleRelease --build-cache --no-configuration-cache --parallel`；
  签名经 secrets 注入（KEYSTORE_BASE64 / KEYSTORE_PASSWORD 等），release APK 自动发布到 GitHub Releases（dev tag）。
- `.github/workflows/static-analysis.yml`：仅 workflow_dispatch 手动触发，执行 `./gradlew ktlintCheck` 与 `./gradlew detekt`。
- 改动是否可用以 GitHub CI 编译通过为准。

## 硬性工作规则（用户长期要求，必须逐条遵守）

1. 禁止本地编译：不要在本地运行任何构建/编译/测试/依赖安装命令（如 gradlew、npm 等）；改动是否可用以 GitHub CI 编译通过为准。
2. 每完成一个改动立即 commit 并 push，再进行下一项改动。
3. 所有提交使用 xaxka 身份（本 clone 已配置 user.name=xaxka，user.email=73456104+xaxka@users.noreply.github.com，不要改动）。
4. 任务结束后清理本地 clone。

## 代码约定

- 注释与文档以中文为主，KDoc 常引用计划章节（如 §5.3）与任务/增量编号（P0.1/P1.2、Task 22、B20 等），沿用该风格标注改动。
- ktlint + detekt 已启用（detekt 配置在 `config/detekt/detekt.yml`）；`.editorconfig`：行长上限 140、允许通配导入（Compose icons 场景）、LF、文件末尾换行。
- Kotlin 代码普遍使用尾随逗号，多行参数与集合换行排版保持既有风格。
- 日志用 `android.util.Log`（仅 app 层；core 保持纯 JVM，不得引入 Android 依赖）。
- 测试栈：core 用 JUnit4 + Truth + MockWebServer；app 单测用 Robolectric/MockK/Turbine。
- 包名统一 `xa.refile.*`；UI 文件按 `XxxScreen.kt` / `XxxViewModel.kt` 划分。

## 关键文档 / 敏感区

- 动手前先读 `README.md`（含完整目录职责说明）。
- 安全红线：服务器密码仅可在 `data/crypto/`（Keystore）内解密用于构造 client，绝不进入日志/UI 状态。
- `app/schemas/` 是 Room 导出的 schema（提交到 VCS 供迁移校验）：修改 Room Entity/DAO 必须同步更新 schema 并在 `data/db/Migrations.kt` 写迁移。
- `versionCode`/`versionName` 在 app/build.gradle.kts 中按构建时间（Asia/Shanghai）自动生成，勿手改。
- `core/` 的 OpenList 包有 ≥97% 行覆盖率校验（见 core/build.gradle.kts），改动该包需同步补测试。
