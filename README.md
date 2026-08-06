# refile

一款基于 Vibe Coding 开发的 Android 媒体文件重命名与整理工具，通过 TMDB 元数据自动识别电影、电视剧信息并生成规范化文件名，支持 WebDAV 和 OpenList 协议进行远程文件重命名，采用多线程处理提升批量整理效率。项目主要用于个人自用场景，不承诺稳定维护。

## 项目结构

```
refile/
├── app/          # Android 应用模块
│   └── src/main/kotlin/xa/refile/
│       ├── ui/browser/     # WebDAV 文件浏览器
│       ├── ui/match/       # 匹配（单文件 + 批量）
│       ├── ui/preview/     # 重命名预览
│       ├── ui/progress/    # 执行进度
│       ├── ui/history/     # 历史记录与撤销
│       ├── ui/servers/     # 服务器管理
│       ├── ui/settings/    # 设置与模板编辑
│       ├── ui/navigation/  # 导航路由
│       ├── ui/theme/       # Material 3 主题
│       ├── ui/common/      # 通用 UI 组件
│       ├── data/repository/ # Repository 层
│       ├── data/db/        # Room 数据库
│       ├── data/prefs/     # DataStore 偏好设置
│       ├── data/backup/    # 备份与恢复
│       ├── data/crypto/    # Keystore 加密
│       └── worker/         # WorkManager 后台任务
├── core/         # 纯 Kotlin 公共模块
│   └── src/main/kotlin/xa/refile/core/
│       ├── parser/         # 文件名解析
│       ├── matcher/        # 名称匹配引擎
│       ├── tmdb/           # TMDB API 客户端
│       ├── rename/         # 重命名执行器
│       ├── naming/         # 模板引擎
│       ├── webdav/         # WebDAV 客户端
│       ├── openlist/       # OpenList API 客户端
│       ├── model/          # 数据模型
│       └── util/           # 工具类
```

## 构建

```bash
./gradlew :app:assembleRelease
```

## 许可

[MIT](LICENSE)
