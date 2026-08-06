package xa.refile.ui.match

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * 跨匹配系列页面共享的会话 ViewModel（Task 2.4 导航接入 / Task 2.5 Edit Match 数据桥 / Task 3.4 预览数据桥）。
 *
 * 在 [xa.refile.ui.navigation.AppNavHost] 中以 `hiltViewModel()`（Activity 作用域）
 * 创建，浏览器 `onProceedToPreview` 通过 [setFiles] 写入用户选中的视频完整路径、
 * 通过 [setMatchType] 写入用户在「匹配」按钮上方选择的匹配方式，
 * [PreviewScreen] 读取后转交 [MatchViewModel] 启动匹配。
 *
 * [matches] 同时作为预览页数据源与 EditMatch/BatchMatch 编辑桥：
 * - 预览页内嵌的 MatchViewModel 匹配完成时通过 [setMatches] 写入，预览页渲染目标路径；
 * - EditMatch 通过 [updateMatch] 单条回写，BatchMatchScreen 通过 [replaceMatches] 整表回写，
 *   均置 [dirty] 通知预览页 reload。
 *
 * 用 Activity 作用域而非目的地图作用域，是为了让路径在 servers → browser → preview → edit_match
 * 的整个回退栈生命周期内保持，且无需把 List<String> 编码进导航参数（路径含特殊字符）。
 */
@HiltViewModel
class MatchSessionViewModel @Inject constructor() : ViewModel() {

    private val _selectedPaths = MutableStateFlow<List<String>>(emptyList())
    val selectedPaths: StateFlow<List<String>> = _selectedPaths.asStateFlow()

    /** 浏览器跳转预览页前写入选中文件完整路径列表。 */
    fun setFiles(paths: List<String>) {
        _selectedPaths.value = paths
    }

    // ---- 匹配类型传递：BrowserScreen 选项 → PreviewScreen 内启动匹配 ----

    private val _matchType = MutableStateFlow(MatchViewModel.MatchType.AUTO)
    val matchType: StateFlow<MatchViewModel.MatchType> = _matchType.asStateFlow()

    /** 浏览器「匹配」按钮上方 3 选 1 后写入，预览页读取后传给 MatchViewModel.startMatch。 */
    fun setMatchType(type: MatchViewModel.MatchType) {
        _matchType.value = type
    }

    // ---- 匹配结果传递：预览页数据源 + EditMatch 编辑桥 ----

    private val _matches = MutableStateFlow<List<MatchViewModel.FileMatch>>(emptyList())

    /** 已匹配（含 TMDB 元数据）的文件列表：预览页内嵌 MatchViewModel 写入，预览页渲染，EditMatch 按索引编辑回写。 */
    val matches: StateFlow<List<MatchViewModel.FileMatch>> = _matches.asStateFlow()

    /** 预览页内嵌 MatchViewModel 匹配完成时写入已匹配文件列表，供预览页渲染目标路径。 */
    fun setMatches(matches: List<MatchViewModel.FileMatch>) {
        _matches.value = matches
    }

    /**
     * 脏标记：仅当 EditMatch 回写后置 true。预览页消费后 [clearDirty]。
     */
    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    /** EditMatch 单条保存后按索引回写，置 [dirty] 通知预览页 reload。 */
    fun updateMatch(index: Int, file: MatchViewModel.FileMatch) {
        val list = _matches.value.toMutableList()
        if (index in list.indices) {
            list[index] = file
            _matches.value = list
            _dirty.value = true
        }
    }

    /** BatchMatch 批量保存后回写整表，置 [dirty] 通知预览页 reload。 */
    fun replaceMatches(files: List<MatchViewModel.FileMatch>) {
        _matches.value = files
        _dirty.value = true
    }

    /** 预览页消费完编辑结果后清除脏标记。 */
    fun clearDirty() {
        _dirty.value = false
    }

    /** 清空跨页共享的匹配结果（重新匹配/重置文件时调用），避免旧选择残留。 */
    fun clearMatchState() {
        _matches.value = emptyList()
        _dirty.value = false
    }
}
