package xa.refile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xa.refile.ui.theme.ErrorRed
import xa.refile.ui.theme.SuccessGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TmdbConfigScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val apiKeyValid by viewModel.apiKeyValid.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val tmdbProxyUrl by viewModel.tmdbProxyUrl.collectAsStateWithLifecycle()
    val cacheCleared by viewModel.cacheCleared.collectAsStateWithLifecycle()

    var apiKeyInput by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(apiKey) {
        if (apiKeyInput.isEmpty() && apiKey.isNotEmpty()) apiKeyInput = apiKey
    }
    // B17: 500ms debounce 持久化 API Key，避免每个按键都触发 DataStore 写入。
    // apiKeyInput != apiKey 守卫防止初始回填（上方 LaunchedEffect）触发空写入或重复写入。
    LaunchedEffect(apiKeyInput) {
        if (apiKeyInput.isNotBlank() && apiKeyInput != apiKey) {
            kotlinx.coroutines.delay(500)
            viewModel.setApiKey(apiKeyInput)
        }
    }
    var showApiKey by rememberSaveable { mutableStateOf(false) }

    // 反代地址输入：初始回填已保存值，500ms debounce 持久化（与 API Key 同模式）。
    var proxyUrlInput by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(tmdbProxyUrl) {
        if (proxyUrlInput.isEmpty()) proxyUrlInput = tmdbProxyUrl
    }
    LaunchedEffect(proxyUrlInput) {
        if (proxyUrlInput != tmdbProxyUrl) {
            kotlinx.coroutines.delay(500)
            viewModel.setTmdbProxyUrl(proxyUrlInput)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(cacheCleared) {
        cacheCleared?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearCacheClearedResult()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TMDB 配置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = {
                    // B17: 旧实现每个字符都调 viewModel.setApiKey 触发一次异步 IO。
                    // 改为只更新本地输入态，由下方 LaunchedEffect(apiKeyInput) 做 500ms
                    // debounce 后再持久化，避免高频 DataStore 写入。
                    apiKeyInput = it
                },
                label = { Text("API Key") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showApiKey) "隐藏 Key" else "显示 Key",
                        )
                    }
                },
                visualTransformation = if (showApiKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (apiKeyValid) Icons.Default.Check else Icons.Default.Close,
                    contentDescription = null,
                    tint = if (apiKeyValid) SuccessGreen else ErrorRed,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (apiKeyValid) "Key 已配置" else "未配置",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (apiKeyValid) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.size(4.dp))

            LanguageDropdown(
                selectedCode = language,
                options = viewModel.availableLanguages,
                onSelect = viewModel::setLanguage,
            )

            HorizontalDivider()

            // 反代地址：绕过 DNS 污染。空串直连官方，填写后 API 与图片请求都走该反代。
            Text(
                text = "反代地址",
                style = MaterialTheme.typography.labelLarge,
            )
            OutlinedTextField(
                value = proxyUrlInput,
                onValueChange = { proxyUrlInput = it },
                label = { Text("如 https://your-worker.workers.dev/") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "留空则直连官方 api.themoviedb.org / image.tmdb.org。" +
                    "可基于 Cloudflare Workers Proxy 部署反代（https://github.com/ymyuuu/Cloudflare-Workers-Proxy），" +
                    "只需填 Workers 根地址，API 与图片请求会自动经它代理，用于绕过国内 DNS 污染。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            // 清空 TMDB 缓存：清空详情数据库缓存（7 天 TTL）+ 会话搜索内存缓存。
            OutlinedButton(
                onClick = viewModel::clearTmdbCache,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.CleaningServices, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("清空 TMDB 缓存")
            }
            Text(
                text = "清除已缓存的 TMDB 详情与搜索结果，下次匹配重新走网络。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
    selectedCode: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedCode }?.second
        ?: options.firstOrNull()?.second
        ?: selectedCode

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "语言偏好",
            style = MaterialTheme.typography.labelLarge,
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { (code, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onSelect(code)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
