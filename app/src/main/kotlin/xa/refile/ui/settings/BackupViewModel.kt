package xa.refile.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import xa.refile.data.backup.ApplyResult
import xa.refile.data.backup.BackupResult
import xa.refile.data.backup.ImportResult
import xa.refile.data.backup.BackupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 备份与恢复 ViewModel（计划 §M5 SubTask 5.2）。
 *
 * 持有导出/导入 UI 状态，并通过 SAF 事件驱动 Composable 启动文档选择器。
 * - [pickExportFile] / [pickImportFile]：发出一次性事件，由 Composable 收集后启动 SAF。
 * - [export] / [importFromUri]：在 SAF 回调返回 Uri 后执行实际读写。
 * - [applyImport]：确认后落库。
 *
 * 口令与「包含密码」联动：UI 仅在口令非空时启用「包含密码」开关。
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: BackupRepository,
) : ViewModel() {

    /** 是否正在导出。 */
    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    /** 是否正在导入。 */
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** 口令（可选）。 */
    private val _passphrase = MutableStateFlow("")
    val passphrase: StateFlow<String> = _passphrase.asStateFlow()

    /** 是否在口令加密时包含服务器明文密码。 */
    private val _includePasswords = MutableStateFlow(false)
    val includePasswords: StateFlow<Boolean> = _includePasswords.asStateFlow()

    /** 导入预览（解析成功后展示，确认后落库）。 */
    private val _importPreview = MutableStateFlow<ImportResult?>(null)
    val importPreview: StateFlow<ImportResult?> = _importPreview.asStateFlow()

    /** Snackbar 文案。 */
    private val _result = MutableStateFlow<String?>(null)
    val result: StateFlow<String?> = _result.asStateFlow()

    /** 一次性 SAF 事件，由 Composable 收集后启动对应的文档选择器。 */
    private val _events = MutableSharedFlow<BackupEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<BackupEvent> = _events.asSharedFlow()

    fun setPassphrase(value: String) {
        _passphrase.value = value
        // 口令清空时关闭「包含密码」，避免无口令却勾选
        if (value.isBlank()) _includePasswords.value = false
    }

    fun toggleIncludePasswords(value: Boolean) {
        _includePasswords.value = value
    }

    /** 请求启动 SAF CreateDocument 选择导出保存位置。 */
    fun pickExportFile() {
        viewModelScope.launch { _events.emit(BackupEvent.PickExportFile) }
    }

    /** 请求启动 SAF OpenDocument 选择导入备份文件。 */
    fun pickImportFile() {
        viewModelScope.launch { _events.emit(BackupEvent.PickImportFile) }
    }

    /** SAF 回调返回 Uri 后执行导出并写入文件。 */
    fun export(uri: Uri) {
        viewModelScope.launch {
            _exporting.value = true
            when (val r = repository.export(_passphrase.value, _includePasswords.value)) {
                is BackupResult.Success -> {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(r.json.toByteArray(Charsets.UTF_8))
                        } ?: error("无法写入所选文件")
                    }.onSuccess {
                        _result.value = "导出成功"
                    }.onFailure {
                        _result.value = "写入文件失败：${it.message ?: it.javaClass.simpleName}"
                    }
                }
                is BackupResult.Failure -> _result.value = r.reason
            }
            _exporting.value = false
        }
    }

    /** SAF 回调返回 Uri 后读取并解析备份文件，生成导入预览。 */
    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            _importing.value = true
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8)
                } ?: error("无法读取所选文件")
            }.getOrElse {
                _result.value = "读取文件失败：${it.message ?: it.javaClass.simpleName}"
                _importing.value = false
                return@launch
            }
            when (val r = repository.import(text, _passphrase.value)) {
                is ImportResult.Preview -> {
                    _importPreview.value = r
                    _result.value = null
                }
                is ImportResult.Failure -> {
                    _importPreview.value = null
                    _result.value = r.reason
                }
            }
            _importing.value = false
        }
    }

    /** 确认应用导入预览。 */
    fun applyImport() {
        val preview = _importPreview.value as? ImportResult.Preview ?: return
        viewModelScope.launch {
            _importing.value = true
            when (val r = repository.applyImport(preview.payload)) {
                ApplyResult.Success -> {
                    _result.value = "导入成功"
                    _importPreview.value = null
                }
                is ApplyResult.Failure -> _result.value = r.reason
            }
            _importing.value = false
        }
    }

    /** 取消导入预览（不落库）。 */
    fun cancelImportPreview() {
        _importPreview.value = null
    }

    /** 清除 Snackbar 文案。 */
    fun clearResult() {
        _result.value = null
    }
}

/** 一次性 SAF 事件。 */
sealed interface BackupEvent {
    /** 触发 SAF CreateDocument 选择导出保存位置。 */
    object PickExportFile : BackupEvent

    /** 触发 SAF OpenDocument 选择导入备份文件。 */
    object PickImportFile : BackupEvent
}
