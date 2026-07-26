package xa.refile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import xa.refile.ui.browser.BrowserScreen
import xa.refile.ui.history.HistoryScreen
import xa.refile.ui.match.BatchMatchScreen
import xa.refile.ui.match.EditMatchScreen
import xa.refile.ui.match.MatchSessionViewModel
import xa.refile.ui.preview.PreviewScreen
import xa.refile.ui.progress.ProgressScreen
import xa.refile.ui.servers.ServerEditScreen
import xa.refile.ui.servers.ServerListScreen
import xa.refile.ui.settings.BackupScreen
import xa.refile.ui.settings.HistorySettingsScreen
import xa.refile.ui.settings.HostsSettingsScreen
import xa.refile.ui.settings.SettingsScreen
import xa.refile.ui.settings.TemplateEditorScreen
import xa.refile.ui.settings.TmdbConfigScreen

/**
 * 应用导航图（计划 §M1 SubTask 1.4 导航接入 / §M2 Task 2.4 匹配流程接入 / §M3 Task 3.4 预览接入）。
 *
 * 起始目的地为服务器列表。编辑路由用可选 query 参数 `id`（<=0 或缺省表示新增）。
 * 浏览器路由接 [xa.refile.ui.browser.BrowserScreen]；预览路由接 [PreviewScreen]；
 * 进度路由为占位（Task 4.3 实现完整页面后替换）。
 *
 * selectedPaths 传递：浏览器 `onProceedToPreview` 把选中视频完整路径 + 匹配类型写入 Activity 作用域的
 * [MatchSessionViewModel]，预览页读取后转交 [xa.refile.ui.match.MatchViewModel] 启动匹配。
 * 避免把含特殊字符的 List<String> 编码进导航参数。
 *
 * matches 传递（Task 3.4）：预览页内嵌的 MatchViewModel 匹配完成时把结果写入会话 VM 的
 * `matches`，预览页读取后渲染目标路径，同样避免把复杂对象编码进导航参数。
 */
object Routes {
    const val SERVERS = "servers"

    const val SERVER_EDIT_ROUTE = "servers/edit?id={id}"
    private const val SERVER_EDIT_BASE = "servers/edit"

    const val BROWSER_ROUTE = "browser/{serverId}"
    private const val BROWSER_BASE = "browser"

    /** Edit Match 路由（Task 2.5）：`edit_match/{matchIndex}`，索引指向会话 VM 的 matches。 */
    const val EDIT_MATCH = "edit_match/{matchIndex}"
    private const val EDIT_MATCH_BASE = "edit_match"

    /** 批量匹配编辑路由：集位槽模型整批次编辑，数据从会话 VM 取，无需路径参数。 */
    const val BATCH_MATCH = "batch_match"

    /** 预览页路由（Task 3.4 接入）。 */
    const val PREVIEW = "preview/{serverId}"
    private const val PREVIEW_BASE = "preview"

    /** 执行进度页路由（Task 4.3 实现完整页面，此处先占位接通预览页跳转）。 */
    const val PROGRESS = "progress/{workId}"
    private const val PROGRESS_BASE = "progress"

    /** 模板编辑器路由（Task 3.3）。从设置页跳转。 */
    const val TEMPLATE_EDITOR = "template_editor"

    /** Hosts 设置路由（Task 5.3.4/5.3.5）。从设置页跳转。 */
    const val HOSTS_SETTINGS = "hosts_settings"

    /** 历史记录设置路由。从设置页跳转。 */
    const val HISTORY_SETTINGS = "history_settings"

    /** 历史记录路由（Task 5.1.3）。单页承载列表 + 详情展开/折叠。 */
    const val HISTORY = "history"

    /** 备份与恢复路由（Task 5.2）。从服务器列表页跳转。 */
    const val BACKUP = "backup"

    /** 设置中心路由（Task 5.4）。作为所有子设置功能的统一入口，从服务器列表页齿轮图标跳转。 */
    const val SETTINGS = "settings"

    /** TMDB 配置子页路由（Task 7）。从设置页跳转。 */
    const val TMDB_CONFIG = "tmdb_config"

    /** 编辑页跳转串。id 为 null 或 <=0 表示新增。 */
    fun serverEdit(id: Long?): String =
        "$SERVER_EDIT_BASE?id=${id ?: 0L}"

    /** 文件浏览器跳转串。 */
    fun browser(serverId: Long): String = "$BROWSER_BASE/$serverId"

    /** Edit Match 跳转串（Task 2.5）。 */
    fun editMatch(matchIndex: Int): String = "$EDIT_MATCH_BASE/$matchIndex"

    /** 批量匹配编辑跳转串。 */
    fun batchMatch(): String = BATCH_MATCH

    /** 预览页跳转串（Task 3.4 接入）。 */
    fun preview(serverId: Long): String = "$PREVIEW_BASE/$serverId"

    /** 执行进度页跳转串（Task 3.4 预览页入队后跳转；workId 为 WorkManager UUID 字符串）。 */
    fun progress(workId: String): String = "$PROGRESS_BASE/$workId"

    /** 模板编辑器跳转串。 */
    fun templateEditor(): String = TEMPLATE_EDITOR

    /** Hosts 设置页跳转串。 */
    fun hostsSettings(): String = HOSTS_SETTINGS

    /** 历史记录设置页跳转串。 */
    fun historySettings(): String = HISTORY_SETTINGS

    /** 历史记录页跳转串。 */
    fun history(): String = HISTORY

    /** 备份与恢复页跳转串。 */
    fun backup(): String = BACKUP

    /** 设置中心页跳转串。 */
    fun settings(): String = SETTINGS

    /** TMDB 配置子页跳转串。 */
    fun tmdbConfig(): String = TMDB_CONFIG
}

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    // Activity 作用域：servers → browser → preview → edit_match 整个回退栈生命周期内共享。
    val sessionVm: MatchSessionViewModel = hiltViewModel()

    NavHost(navController = navController, startDestination = Routes.SERVERS) {

        composable(Routes.SERVERS) {
            ServerListScreen(
                onAddServer = { navController.navigate(Routes.serverEdit(null)) },
                onEditServer = { id -> navController.navigate(Routes.serverEdit(id)) },
                onOpenBrowser = { id -> navController.navigate(Routes.browser(id)) },
                onOpenHistory = { navController.navigate(Routes.history()) },
                onOpenSettings = { navController.navigate(Routes.settings()) },
            )
        }

        composable(
            route = Routes.SERVER_EDIT_ROUTE,
            arguments = listOf(
                navArgument("id") {
                    type = NavType.LongType
                    defaultValue = 0L
                },
            ),
        ) { backStackEntry ->
            val idArg = backStackEntry.arguments?.getLong("id") ?: 0L
            val serverId = if (idArg > 0L) idArg else null
            ServerEditScreen(
                serverId = serverId,
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.BROWSER_ROUTE,
            arguments = listOf(
                navArgument("serverId") { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("serverId") ?: 0L
            BrowserScreen(
                serverId = id,
                onBack = { navController.popBackStack() },
                onProceedToPreview = { sid, selectedPaths, matchType ->
                    // 写入选中文件路径 + 用户选择的匹配类型，并清空旧 matches 触发预览页重新跑匹配
                    sessionVm.setFiles(selectedPaths)
                    sessionVm.setMatchType(matchType)
                    sessionVm.clearMatchState()
                    navController.navigate(Routes.preview(sid))
                },
            )
        }

        composable(
            route = Routes.EDIT_MATCH,
            arguments = listOf(
                navArgument("matchIndex") { type = NavType.IntType },
            ),
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("matchIndex") ?: 0
            EditMatchScreen(
                matchIndex = index,
                matchSessionVm = sessionVm,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.BATCH_MATCH) {
            BatchMatchScreen(
                matchSessionVm = sessionVm,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.PREVIEW,
            arguments = listOf(
                navArgument("serverId") { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("serverId") ?: 0L
            // 从会话 VM 读取已匹配结果（首次进入为空，预览页内嵌 MatchViewModel 跑完后写入）
            val matches by sessionVm.matches.collectAsStateWithLifecycle()
            val selectedPaths by sessionVm.selectedPaths.collectAsStateWithLifecycle()
            val matchType by sessionVm.matchType.collectAsStateWithLifecycle()
            PreviewScreen(
                serverId = id,
                matches = matches,
                selectedPaths = selectedPaths,
                matchType = matchType,
                matchSessionVm = sessionVm,
                onBack = {
                    // 返回直接退到文件管理页（browser）。
                    // BrowserViewModel 在 NavBackStackEntry 作用域内，状态（选中文件/目录缓存）自动保留。
                    // 若 browser 不在回退栈（如进程被杀恢复后），回退到服务器列表兜底，避免返回键失灵。
                    if (!navController.popBackStack(Routes.BROWSER_ROUTE, inclusive = false)) {
                        navController.popBackStack(Routes.SERVERS, inclusive = false)
                    }
                },
                onProceedToProgress = { workId -> navController.navigate(Routes.progress(workId)) },
                onEditMatch = { filePath ->
                    val index = sessionVm.matches.value.indexOfFirst { it.filePath == filePath }
                    if (index >= 0) navController.navigate(Routes.editMatch(index))
                },
                onOpenBatchMatch = { navController.navigate(Routes.batchMatch()) },
            )
        }

        composable(
            route = Routes.PROGRESS,
            arguments = listOf(
                navArgument("workId") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            ProgressScreen(
                onBackHome = { navController.popBackStack(Routes.SERVERS, inclusive = false) },
            )
        }

        composable(Routes.TEMPLATE_EDITOR) {
            TemplateEditorScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.HOSTS_SETTINGS) {
            HostsSettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.HISTORY_SETTINGS) {
            HistorySettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.HISTORY) {
            HistoryScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.BACKUP) {
            BackupScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenTmdbConfig = { navController.navigate(Routes.tmdbConfig()) },
                onOpenTemplateEditor = { navController.navigate(Routes.templateEditor()) },
                onOpenBackup = { navController.navigate(Routes.backup()) },
                onOpenHostsSettings = { navController.navigate(Routes.hostsSettings()) },
                onOpenHistorySettings = { navController.navigate(Routes.historySettings()) },
            )
        }

        composable(Routes.TMDB_CONFIG) {
            TmdbConfigScreen(onBack = { navController.popBackStack() })
        }
    }
}
