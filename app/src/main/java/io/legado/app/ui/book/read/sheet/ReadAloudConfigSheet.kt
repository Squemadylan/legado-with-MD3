package io.legado.app.ui.book.read.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.HttpTTS
import io.legado.app.ui.book.read.ReadBookIntent
import io.legado.app.ui.book.read.ReadBookTtsEngineItem
import io.legado.app.ui.book.read.ReadBookUiState
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.series.SmallTonalButton
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.settingItem.SliderSettingItem
import io.legado.app.ui.widget.components.settingItem.TinyClickableSettingItem
import io.legado.app.ui.widget.components.settingItem.TinySwitchSettingItem
import io.legado.app.utils.GSON

@Composable
fun ReadAloudConfigSheet(
    show: Boolean,
    state: ReadBookUiState,
    onIntent: (ReadBookIntent) -> Unit,
    onDismissRequest: () -> Unit,
) {
    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.aloud_config),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            TinySwitchSettingItem(
                title = stringResource(R.string.ignore_audio_focus_title),
                description = stringResource(R.string.ignore_audio_focus_summary),
                checked = state.readAloudIgnoreAudioFocus,
                onCheckedChange = {
                    onIntent(ReadBookIntent.SetReadAloudIgnoreAudioFocus(it))
                },
            )
            TinySwitchSettingItem(
                title = stringResource(R.string.pause_read_aloud_while_phone_calls_title),
                description = stringResource(R.string.pause_read_aloud_while_phone_calls_summary),
                checked = state.readAloudPauseOnPhoneCall,
                enabled = state.readAloudIgnoreAudioFocus,
                onCheckedChange = {
                    onIntent(ReadBookIntent.SetReadAloudPauseOnPhoneCall(it))
                },
            )
            TinySwitchSettingItem(
                title = stringResource(R.string.read_aloud_wake_lock),
                description = stringResource(R.string.read_aloud_wake_lock_summary),
                checked = state.readAloudWakeLock,
                onCheckedChange = {
                    onIntent(ReadBookIntent.SetReadAloudWakeLock(it))
                },
            )
            TinySwitchSettingItem(
                title = stringResource(R.string.pref_media_button_per_next),
                description = stringResource(R.string.pref_media_button_per_next_summary),
                checked = state.readAloudMediaButtonPerNext,
                onCheckedChange = {
                    onIntent(ReadBookIntent.SetReadAloudMediaButtonPerNext(it))
                },
            )
            TinySwitchSettingItem(
                title = stringResource(R.string.read_aloud_by_page),
                description = stringResource(R.string.read_aloud_by_page_summary),
                checked = state.readAloudByPage,
                onCheckedChange = {
                    onIntent(ReadBookIntent.SetReadAloudByPage(it))
                },
            )
            TinySwitchSettingItem(
                title = stringResource(R.string.system_media_control_compatibility_change),
                description = stringResource(R.string.system_media_control_compatibility_change_summary),
                checked = state.readAloudSystemMediaCompat,
                onCheckedChange = {
                    onIntent(ReadBookIntent.SetReadAloudSystemMediaCompat(it))
                },
            )
            TinySwitchSettingItem(
                title = stringResource(R.string.stream_read_aloud_audio),
                description = stringResource(R.string.stream_read_aloud_audio_summary),
                checked = state.readAloudStreamAudio,
                onCheckedChange = {
                    onIntent(ReadBookIntent.SetReadAloudStreamAudio(it))
                },
            )
            TinyClickableSettingItem(
                title = stringResource(R.string.speak_engine),
                description = state.speakEngineName.ifEmpty {
                    stringResource(R.string.system_tts)
                },
                onClick = { onIntent(ReadBookIntent.SelectSpeakEngine) },
            )
            TinyClickableSettingItem(
                title = stringResource(R.string.sys_tts_config),
                onClick = { onIntent(ReadBookIntent.OpenSystemTtsSettings) },
            )
            TinyClickableSettingItem(
                title = stringResource(R.string.read_aloud_preload),
                onClick = { onIntent(ReadBookIntent.OpenPreDownloadNumPicker) },
            )
            TinyClickableSettingItem(
                title = stringResource(R.string.audio_cache_clean_time),
                onClick = { onIntent(ReadBookIntent.OpenCacheCleanTimePicker) },
            )
            TinyClickableSettingItem(
                title = stringResource(R.string.clear_cache),
                onClick = { onIntent(ReadBookIntent.ClearTtsCache) },
            )
        }
    }
}

@Composable
fun SpeakEngineConfigSheet(
    show: Boolean,
    state: ReadBookUiState,
    onIntent: (ReadBookIntent) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val items = state.ttsEngineItems
    val selectedValue = state.selectedTtsEngine
    var pendingEngineValue by remember { mutableStateOf<String?>(null) }

    AppAlertDialog(
        show = pendingEngineValue != null,
        onDismissRequest = { pendingEngineValue = null },
        title = stringResource(R.string.speak_engine),
        text = stringResource(R.string.speak_engine_apply_scope),
        confirmText = stringResource(R.string.general),
        onConfirm = {
            onIntent(ReadBookIntent.ApplySpeakEngine(pendingEngineValue))
            pendingEngineValue = null
        },
        dismissText = stringResource(R.string.book),
        onDismiss = {
            onIntent(ReadBookIntent.ApplySpeakEnginePerBook(pendingEngineValue))
            pendingEngineValue = null
        },
    )

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.speak_engine),
        startAction = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                var showAddMenu by remember { mutableStateOf(false) }
                Box {
                    SmallTonalButton(
                        onClick = { showAddMenu = true },
                        icon = Icons.Default.Add
                    )
                    DropdownMenu(
                        expanded = showAddMenu,
                        onDismissRequest = { showAddMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("通用 HTTP TTS") },
                            onClick = {
                                showAddMenu = false
                                onIntent(ReadBookIntent.EditHttpTts())
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("豆包 TTS") },
                            onClick = {
                                showAddMenu = false
                                onIntent(ReadBookIntent.EditDoubaoTts)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Mimo TTS") },
                            onClick = {
                                showAddMenu = false
                                onIntent(ReadBookIntent.EditMimoTts)
                            }
                        )
                    }
                }
                SmallTonalButton(
                    onClick = {
                        clipboardManager.getText()?.text?.let { text ->
                            if (text.isNotBlank()) {
                                onIntent(ReadBookIntent.ImportHttpTtsJson(text))
                            }
                        }
                    },
                    icon = Icons.Default.Description
                )
                SmallTonalButton(
                    onClick = { onIntent(ReadBookIntent.ExportAllHttpTts) },
                    icon = Icons.Default.FileDownload
                )
            }
        },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .padding(bottom = 8.dp),
        ) {
            items(items) { item ->
                val isHttpTts = item.value != null
                val isSelected = item.value == selectedValue
                TinyClickableSettingItem(
                    title = item.title,
                    description = if (isSelected) {
                        stringResource(R.string.default_version)
                    } else {
                        null
                    },
                    onClick = { pendingEngineValue = item.value },
                    onLongClick = if (isHttpTts && !item.loginUrl.isNullOrBlank()) {
                        { onIntent(ReadBookIntent.OpenHttpTtsLogin(item.value!!.toLong())) }
                    } else null,
                    trailingContent = if (isHttpTts) {
                        {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                SmallTonalButton(
                                    onClick = { onIntent(ReadBookIntent.EditHttpTts(item.value!!.toLong())) },
                                    icon = Icons.Default.Edit,
                                )
                                SmallTonalButton(
                                    onClick = { onIntent(ReadBookIntent.DeleteHttpTts(item.value!!.toLong())) },
                                    icon = Icons.Default.Delete,
                                )
                            }
                        }
                    } else null,
                )
            }
        }
    }
}

@Composable
fun HttpTtsEditSheet(
    show: Boolean,
    httpTTS: HttpTTS?,
    onIntent: (ReadBookIntent) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val tts = httpTTS ?: return
    val clipboardManager = LocalClipboardManager.current
    var name by remember(httpTTS) { mutableStateOf(tts.name) }
    var url by remember(httpTTS) { mutableStateOf(tts.url) }
    var contentType by remember(httpTTS) { mutableStateOf(tts.contentType ?: "") }
    var concurrentRate by remember(httpTTS) { mutableStateOf(tts.concurrentRate ?: "0") }
    var header by remember(httpTTS) { mutableStateOf(tts.header ?: "") }
    var loginUrl by remember(httpTTS) { mutableStateOf(tts.loginUrl ?: "") }
    var loginUi by remember(httpTTS) { mutableStateOf(tts.loginUi ?: "") }
    var loginCheckJs by remember(httpTTS) { mutableStateOf(tts.loginCheckJs ?: "") }
    var jsLib by remember(httpTTS) { mutableStateOf(tts.jsLib ?: "") }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.speak_engine),
        startAction = {
            var expanded by remember { mutableStateOf(false) }
            Box {
                SmallTonalButton(
                    onClick = { expanded = true },
                    icon = Icons.Default.MoreVert
                )
                RoundDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    RoundDropdownMenuItem(
                        text = stringResource(R.string.copy_text),
                        onClick = {
                            expanded = false
                            val json = GSON.toJson(
                                tts.copy(
                                    name = name, url = url, contentType = contentType,
                                    concurrentRate = concurrentRate, header = header,
                                    loginUrl = loginUrl, loginUi = loginUi,
                                    loginCheckJs = loginCheckJs, jsLib = jsLib,
                                )
                            )
                            clipboardManager.setText(AnnotatedString(json))
                        },
                    )
                    RoundDropdownMenuItem(
                        text = stringResource(R.string.paste_source),
                        onClick = {
                            expanded = false
                            clipboardManager.getText()?.text?.let { text ->
                                HttpTTS.fromJson(text).getOrNull()?.let { imported ->
                                    name = imported.name
                                    url = imported.url
                                    contentType = imported.contentType ?: ""
                                    concurrentRate = imported.concurrentRate ?: "0"
                                    header = imported.header ?: ""
                                    loginUrl = imported.loginUrl ?: ""
                                    loginUi = imported.loginUi ?: ""
                                    loginCheckJs = imported.loginCheckJs ?: ""
                                    jsLib = imported.jsLib ?: ""
                                }
                            }
                        },
                    )
                }
            }
        },
        endAction = {
            SmallTonalButton(
                icon = Icons.Default.Save,
                onClick = {
                    onIntent(
                        ReadBookIntent.SaveHttpTts(
                            tts.copy(
                                name = name, url = url,
                                contentType = contentType.ifBlank { null },
                                concurrentRate = concurrentRate,
                                header = header.ifBlank { null },
                                loginUrl = loginUrl.ifBlank { null },
                                loginUi = loginUi.ifBlank { null },
                                loginCheckJs = loginCheckJs.ifBlank { null },
                                jsLib = jsLib.ifBlank { null },
                            )
                        )
                    )
                },
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.name),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                AppTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = "URL",
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                AppTextField(
                    value = contentType,
                    onValueChange = { contentType = it },
                    label = "Content-Type",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                AppTextField(
                    value = concurrentRate,
                    onValueChange = { concurrentRate = it },
                    label = stringResource(R.string.concurrent_rate),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                AppTextField(
                    value = header,
                    onValueChange = { header = it },
                    label = stringResource(R.string.source_http_header),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                AppTextField(
                    value = loginUrl,
                    onValueChange = { loginUrl = it },
                    label = stringResource(R.string.login_url),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                AppTextField(
                    value = loginUi,
                    onValueChange = { loginUi = it },
                    label = stringResource(R.string.login_ui),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                AppTextField(
                    value = loginCheckJs,
                    onValueChange = { loginCheckJs = it },
                    label = stringResource(R.string.login_check_js),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                AppTextField(
                    value = jsLib,
                    onValueChange = { jsLib = it },
                    label = "jsLib",
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun ReadAloudNumberConfigSheet(
    show: Boolean,
    title: String,
    description: String,
    value: Int,
    defaultValue: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Int) -> Unit,
    onDismissRequest: () -> Unit,
) {
    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = title,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            SliderSettingItem(
                title = title,
                description = description,
                value = value.toFloat(),
                defaultValue = defaultValue.toFloat(),
                valueRange = valueRange,
                onValueChange = { onValueChange(it.toInt()) },
            )
        }
    }
}

/**
 * 豆包 TTS 已购音色列表
 */
private val doubaoVoices = listOf(
    // 默认
    DoubaoVoice("灿灿", "BV700_streaming", "通用场景", "中文"),
    // 免费
    DoubaoVoice("通用女声", "BV001_streaming", "通用场景", "中文"),
    DoubaoVoice("通用男声", "BV002_streaming", "通用场景", "中文"),
    // 男声阅读
    DoubaoVoice("擎苍", "BV701_streaming", "有声阅读", "中文"),
    DoubaoVoice("儒雅青年", "BV102_streaming", "有声阅读", "中文"),
    DoubaoVoice("通用赘婿", "BV119_streaming", "有声阅读", "中文"),
    // 女声阅读
    DoubaoVoice("甜宠少御", "BV113_streaming", "有声阅读", "中文"),
    DoubaoVoice("古风少御", "BV115_streaming", "有声阅读", "中文"),
    DoubaoVoice("温柔小哥", "BV033_streaming", "教育场景", "中文"),
    // 特色
    DoubaoVoice("奶气萌娃", "BV051_streaming", "特色音色", "中文"),
    DoubaoVoice("活泼女声", "BV005_streaming", "视频配音", "中文"),
    DoubaoVoice("炀炀", "BV705_streaming", "通用场景", "中文"),
    DoubaoVoice("阳光男声", "BV056_streaming", "视频配音", "中文"),
    DoubaoVoice("亲切女声", "BV007_streaming", "客服场景", "中文"),
    DoubaoVoice("知性姐姐-双语", "BV034_streaming", "教育场景", "中文"),
    // 方言
    DoubaoVoice("东北老铁", "BV021_streaming", "方言", "东北话"),
    DoubaoVoice("重庆小伙", "BV019_streaming", "方言", "重庆话"),
    DoubaoVoice("广西表哥", "BV213_streaming", "方言", "广西普通话"),
    // 外语
    DoubaoVoice("活力女声-Ariana", "BV503_streaming", "美式发音", "英语"),
    DoubaoVoice("活力男声-Jackson", "BV504_streaming", "美式发音", "英语"),
    DoubaoVoice("气质女生", "BV522_streaming", "多语种", "日语"),
    DoubaoVoice("日语男声", "BV524_streaming", "多语种", "日语"),
)

data class DoubaoVoice(
    val name: String,
    val voiceType: String,
    val scene: String,
    val language: String,
)

@Composable
fun DoubaoTtsEditSheet(
    show: Boolean,
    httpTTS: HttpTTS?,
    onIntent: (ReadBookIntent) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val tts = httpTTS ?: return
    var name by remember(httpTTS) { mutableStateOf(tts.name) }
    var appId by remember(httpTTS) { mutableStateOf(tts.doubaoAppId ?: "") }
    var accessToken by remember(httpTTS) { mutableStateOf(tts.doubaoAccessToken ?: "") }
    var voiceType by remember(httpTTS) { mutableStateOf(tts.doubaoVoiceType) }
    var speedRatio by remember(httpTTS) { mutableFloatStateOf(tts.doubaoSpeedRatio) }
    var pitchRatio by remember(httpTTS) { mutableFloatStateOf(tts.doubaoPitchRatio) }
    var volumeRatio by remember(httpTTS) { mutableFloatStateOf(tts.doubaoVolumeRatio) }
    var showVoiceSelect by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    // 音色选择弹窗
    if (showVoiceSelect) {
        DoubaoVoiceSelectDialog(
            selectedVoiceType = voiceType,
            onSelect = { selected ->
                voiceType = selected.voiceType
                if (name.isBlank()) name = selected.name
                showVoiceSelect = false
            },
            onDismiss = { showVoiceSelect = false }
        )
    }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = "豆包 TTS 配置",
        startAction = {
            var expanded by remember { mutableStateOf(false) }
            Box {
                SmallTonalButton(
                    onClick = { expanded = true },
                    icon = Icons.Default.MoreVert
                )
                RoundDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    RoundDropdownMenuItem(
                        text = stringResource(R.string.copy_text),
                        onClick = {
                            expanded = false
                            val json = GSON.toJson(
                                tts.copy(
                                    name = name, ttsType = "doubao",
                                    doubaoAppId = appId, doubaoAccessToken = accessToken,
                                    doubaoVoiceType = voiceType,
                                    doubaoSpeedRatio = speedRatio,
                                    doubaoPitchRatio = pitchRatio,
                                    doubaoVolumeRatio = volumeRatio,
                                )
                            )
                            clipboardManager.setText(AnnotatedString(json))
                        },
                    )
                    RoundDropdownMenuItem(
                        text = stringResource(R.string.paste_source),
                        onClick = {
                            expanded = false
                            clipboardManager.getText()?.text?.let { text ->
                                HttpTTS.fromJson(text).getOrNull()?.let { imported ->
                                    name = imported.name
                                    appId = imported.doubaoAppId ?: ""
                                    accessToken = imported.doubaoAccessToken ?: ""
                                    voiceType = imported.doubaoVoiceType
                                    speedRatio = imported.doubaoSpeedRatio
                                    pitchRatio = imported.doubaoPitchRatio
                                    volumeRatio = imported.doubaoVolumeRatio
                                }
                            }
                        },
                    )
                }
            }
        },
        endAction = {
            SmallTonalButton(
                icon = Icons.Default.Save,
                onClick = {
                    onIntent(
                        ReadBookIntent.SaveHttpTts(
                            tts.copy(
                                name = name.ifBlank { "豆包TTS" },
                                ttsType = "doubao",
                                doubaoAppId = appId.ifBlank { null },
                                doubaoAccessToken = accessToken.ifBlank { null },
                                doubaoVoiceType = voiceType,
                                doubaoSpeedRatio = speedRatio,
                                doubaoPitchRatio = pitchRatio,
                                doubaoVolumeRatio = volumeRatio,
                            )
                        )
                    )
                },
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.name),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                AppTextField(
                    value = appId,
                    onValueChange = { appId = it },
                    label = "AppID",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                AppTextField(
                    value = accessToken,
                    onValueChange = { accessToken = it },
                    label = "Access Token",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 音色选择
            item {
                val voiceName = doubaoVoices.find { it.voiceType == voiceType }?.name ?: voiceType
                TinyClickableSettingItem(
                    title = "音色",
                    description = voiceName,
                    onClick = { showVoiceSelect = true },
                )
            }
            // 语速
            item {
                SliderSettingItem(
                    title = "语速",
                    description = String.format("%.1f", speedRatio),
                    value = speedRatio,
                    defaultValue = 1.0f,
                    valueRange = 0.5f..2.0f,
                    onValueChange = { speedRatio = it },
                )
            }
            // 音调
            item {
                SliderSettingItem(
                    title = "音调",
                    description = String.format("%.1f", pitchRatio),
                    value = pitchRatio,
                    defaultValue = 1.0f,
                    valueRange = 0.5f..2.0f,
                    onValueChange = { pitchRatio = it },
                )
            }
            // 音量
            item {
                SliderSettingItem(
                    title = "音量",
                    description = String.format("%.1f", volumeRatio),
                    value = volumeRatio,
                    defaultValue = 1.0f,
                    valueRange = 0.5f..2.0f,
                    onValueChange = { volumeRatio = it },
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun DoubaoVoiceSelectDialog(
    selectedVoiceType: String,
    onSelect: (DoubaoVoice) -> Unit,
    onDismiss: () -> Unit,
) {
    AppAlertDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = "选择音色",
        content = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(horizontal = 16.dp),
            ) {
                val grouped = doubaoVoices.groupBy { it.scene }
                grouped.forEach { (scene, voices) ->
                    item {
                        Text(
                            text = scene,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(voices) { voice ->
                        TinyClickableSettingItem(
                            title = voice.name,
                            description = "${voice.voiceType} · ${voice.language}",
                            onClick = { onSelect(voice) },
                        )
                    }
                }
            }
        }
    )
}

/**
 * MiMo TTS 预置音色列表
 */
private val mimoVoices = listOf(
    MimoVoice("冰糖", "冰糖", "中文", "女", "通用场景、有声阅读"),
    MimoVoice("茉莉", "茉莉", "中文", "女", "温柔叙事、情感朗读"),
    MimoVoice("苏打", "苏打", "中文", "男", "通用场景、有声阅读"),
    MimoVoice("白桦", "白桦", "中文", "男", "深沉叙事、纪录片"),
    MimoVoice("Mia", "Mia", "英文", "女", "英文朗读、配音"),
    MimoVoice("Chloe", "Chloe", "英文", "女", "英文朗读、配音"),
    MimoVoice("Milo", "Milo", "英文", "男", "英文朗读、配音"),
    MimoVoice("Dean", "Dean", "英文", "男", "英文朗读、配音"),
)

data class MimoVoice(
    val name: String,
    val voiceId: String,
    val language: String,
    val gender: String,
    val scene: String,
)

/**
 * MiMo TTS 风格快捷标签
 */
private val mimoStyleTags = listOf(
    "温柔", "磁性", "悲伤", "开心", "愤怒", "慵懒",
    "严肃", "活泼", "深沉", "甜美", "沙哑", "高冷",
    "东北话", "四川话", "粤语", "台湾腔",
    "御姐音", "大叔音", "正太音",
)

@Composable
fun MimoTtsEditSheet(
    show: Boolean,
    httpTTS: HttpTTS?,
    onIntent: (ReadBookIntent) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val tts = httpTTS ?: return
    var name by remember(httpTTS) { mutableStateOf(tts.name) }
    var apiKey by remember(httpTTS) { mutableStateOf(tts.mimoApiKey ?: "") }
    var voice by remember(httpTTS) { mutableStateOf(tts.mimoVoice) }
    var model by remember(httpTTS) { mutableStateOf(tts.mimoModel) }
    var style by remember(httpTTS) { mutableStateOf(tts.mimoStyle ?: "") }
    var voiceDesign by remember(httpTTS) { mutableStateOf(tts.mimoVoiceDesign ?: "") }
    var showVoiceSelect by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    if (showVoiceSelect) {
        MimoVoiceSelectDialog(
            selectedVoice = voice,
            onSelect = { selected ->
                voice = selected.voiceId
                if (name.isBlank()) name = selected.name
                showVoiceSelect = false
            },
            onDismiss = { showVoiceSelect = false }
        )
    }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = "Mimo TTS 配置",
        startAction = {
            var expanded by remember { mutableStateOf(false) }
            Box {
                SmallTonalButton(
                    onClick = { expanded = true },
                    icon = Icons.Default.MoreVert
                )
                RoundDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    RoundDropdownMenuItem(
                        text = stringResource(R.string.copy_text),
                        onClick = {
                            expanded = false
                            val json = GSON.toJson(
                                tts.copy(
                                    name = name, ttsType = "mimo",
                                    mimoApiKey = apiKey, mimoVoice = voice,
                                    mimoModel = model, mimoStyle = style.ifBlank { null },
                                    mimoVoiceDesign = voiceDesign.ifBlank { null },
                                )
                            )
                            clipboardManager.setText(AnnotatedString(json))
                        },
                    )
                    RoundDropdownMenuItem(
                        text = stringResource(R.string.paste_source),
                        onClick = {
                            expanded = false
                            clipboardManager.getText()?.text?.let { text ->
                                HttpTTS.fromJson(text).getOrNull()?.let { imported ->
                                    name = imported.name
                                    apiKey = imported.mimoApiKey ?: ""
                                    voice = imported.mimoVoice
                                    model = imported.mimoModel
                                    style = imported.mimoStyle ?: ""
                                    voiceDesign = imported.mimoVoiceDesign ?: ""
                                }
                            }
                        },
                    )
                }
            }
        },
        endAction = {
            SmallTonalButton(
                icon = Icons.Default.Save,
                onClick = {
                    onIntent(
                        ReadBookIntent.SaveHttpTts(
                            tts.copy(
                                name = name.ifBlank { "Mimo TTS" },
                                ttsType = "mimo",
                                mimoApiKey = apiKey.ifBlank { null },
                                mimoVoice = voice,
                                mimoModel = model,
                                mimoStyle = style.ifBlank { null },
                                mimoVoiceDesign = voiceDesign.ifBlank { null },
                            )
                        )
                    )
                },
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.name),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                AppTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = "API Key",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 模型选择
            item {
                val isPreset = model == "mimo-v2.5-tts"
                TinyClickableSettingItem(
                    title = "模型",
                    description = if (isPreset) "预置音色" else "音色设计",
                    onClick = {
                        model = if (isPreset) "mimo-v2.5-tts-voicedesign" else "mimo-v2.5-tts"
                    },
                )
            }
            // 预置音色模式
            if (model == "mimo-v2.5-tts") {
                item {
                    TinyClickableSettingItem(
                        title = "音色",
                        description = voice,
                        onClick = { showVoiceSelect = true },
                    )
                }
            }
            // 音色设计模式
            if (model == "mimo-v2.5-tts-voicedesign") {
                item {
                    AppTextField(
                        value = voiceDesign,
                        onValueChange = { voiceDesign = it },
                        label = "音色描述",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            // 风格指令
            item {
                AppTextField(
                    value = style,
                    onValueChange = { style = it },
                    label = "风格指令（可选）",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 风格快捷标签
            item {
                Column {
                    Text(
                        text = "快捷标签",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        mimoStyleTags.forEach { tag ->
                            SmallTonalButton(
                                onClick = {
                                    style = if (style.isBlank()) "($tag)" else "$style($tag)"
                                },
                                text = tag,
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun MimoVoiceSelectDialog(
    selectedVoice: String,
    onSelect: (MimoVoice) -> Unit,
    onDismiss: () -> Unit,
) {
    AppAlertDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = "选择音色",
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(horizontal = 16.dp),
            ) {
                val grouped = mimoVoices.groupBy { it.language }
                grouped.forEach { (lang, voices) ->
                    Text(
                        text = lang,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    voices.forEach { mimoVoice ->
                        TinyClickableSettingItem(
                            title = "${mimoVoice.name}（${mimoVoice.gender}）",
                            description = mimoVoice.scene,
                            onClick = { onSelect(mimoVoice) },
                        )
                    }
                }
            }
        }
    )
}
