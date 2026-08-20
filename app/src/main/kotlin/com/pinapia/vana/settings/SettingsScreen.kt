package com.pinapia.vana.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pinapia.vana.BuildConfig
import com.pinapia.vana.checkin.CheckInScheduler
import com.pinapia.vana.location.LocationProvider
import com.pinapia.vana.session.SessionStore
import com.pinapia.vana.update.CheckForUpdatesRow
import com.pinapia.vana.vision.PhotoImagePolicy
import com.pinapia.vana.voice.VoiceDictation
import com.pinapia.vana.ui.L10n
import com.pinapia.vana.ui.uiText
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    engineSettings: EngineSettings,
    secureKeyStore: SecureKeyStore,
    locationProvider: LocationProvider,
    sessionStore: SessionStore,
    onBack: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenMeasurements: () -> Unit = {},
    onOpenTenants: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenDeveloper: () -> Unit = {},
    onChatsCleared: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var apiKey by remember { mutableStateOf(secureKeyStore.apiKey.orEmpty()) }
    var serperKey by remember { mutableStateOf(secureKeyStore.serperApiKey.orEmpty()) }
    var providerId by remember { mutableStateOf(engineSettings.providerId) }
    var modelId by remember { mutableStateOf(engineSettings.model) }
    var persona by remember { mutableStateOf(engineSettings.persona) }
    var photoPolicy by remember { mutableStateOf(engineSettings.photoImagePolicy) }
    var thinking by remember { mutableStateOf(engineSettings.thinkingEnabled) }
    var memory by remember { mutableStateOf(engineSettings.memoryEnabled) }
    var medications by remember { mutableStateOf(engineSettings.medicationsEnabled) }
    var measurements by remember { mutableStateOf(engineSettings.measurementsEnabled) }
    var checkIns by remember { mutableStateOf(engineSettings.checkInsEnabled) }
    var morningHour by remember { mutableStateOf(engineSettings.morningCheckInHour) }
    var eveningHour by remember { mutableStateOf(engineSettings.eveningCheckInHour) }
    var confirmClearChats by remember { mutableStateOf(false) }
    var showProviders by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var showPersonas by remember { mutableStateOf(false) }
    var showPhotoPolicies by remember { mutableStateOf(false) }
    var showMorningPicker by remember { mutableStateOf(false) }
    var showEveningPicker by remember { mutableStateOf(false) }
    var locationPlace by remember { mutableStateOf(locationProvider.snapshot.place) }
    var isTestingConnection by remember { mutableStateOf(false) }
    var connectionResult by remember { mutableStateOf<ConnectionTest.Result?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voice = remember { VoiceDictation.shared(context) }
    val voiceAvailability by voice.availability.collectAsStateWithLifecycle()
    val voiceLocale by voice.resolvedLocale.collectAsStateWithLifecycle()
    val voiceSupported by voice.supportedLocaleIdentifiers.collectAsStateWithLifecycle()
    val morningReminderDescription = uiText("更改早上提醒时间", "Change morning reminder time")
    val eveningReminderDescription = uiText("更改晚上提醒时间", "Change evening reminder time")
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            engineSettings.checkInsEnabled = true
            checkIns = true
            CheckInScheduler.reschedule(context)
        } else {
            engineSettings.checkInsEnabled = false
            checkIns = false
        }
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scope.launch {
                locationProvider.refresh(force = true)
                locationPlace = locationProvider.snapshot.place
            }
        } else {
            locationProvider.clear()
            locationPlace = null
        }
    }

    LaunchedEffect(Unit) {
        voice.refresh()
        if (locationProvider.isAuthorized) {
            locationProvider.refresh()
            locationPlace = locationProvider.snapshot.place
        }
    }
    LaunchedEffect(apiKey, providerId, modelId) {
        connectionResult = null
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(uiText("设置", "Settings")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = uiText("返回", "Back"))
                    }
                },
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(uiText("云端模型", "Cloud model"), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    secureKeyStore.apiKey = it
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(uiText("API 密钥", "API key")) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                isError = apiKey.isNotBlank() && !ApiKeyNormalizer.normalize(apiKey).isValid,
            )
            val keyError = ApiKeyNormalizer.normalize(apiKey).error
                ?.takeIf { apiKey.isNotBlank() && it.contains("非法字符") }
            if (keyError != null) {
                Text(
                    keyError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    uiText("API 密钥只保存在本机加密存储里。", "The API key is stored only in encrypted storage on this device."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsPickerRow(
                label = "Provider",
                value = CloudCatalog.providerName(providerId),
                onClick = { showProviders = true },
            )
            SettingsPickerRow(
                label = uiText("模型", "Model"),
                value = CloudCatalog.modelName(modelId, providerId),
                onClick = { showModels = true },
            )
            if (!CloudCatalog.isLoaded) {
                Text(
                    uiText(
                        "未能载入 AIKit provider 目录：${CloudCatalog.diagnostics}",
                        "Could not load the AIKit provider catalog: ${CloudCatalog.diagnostics}",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    uiText(
                        "Provider 和模型都从 AIKit 内置目录里选。API 密钥只保存在本机加密存储。",
                        "Choose the provider and model from the built-in AIKit catalog. The API key stays in encrypted storage.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = {
                    isTestingConnection = true
                    connectionResult = null
                    scope.launch {
                        connectionResult = ConnectionTest.run(providerId, modelId, apiKey)
                        isTestingConnection = false
                    }
                },
                enabled = !isTestingConnection &&
                    ApiKeyNormalizer.normalize(apiKey).isValid &&
                    providerId.isNotBlank() &&
                    modelId.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (isTestingConnection) uiText("正在测试连接…", "Testing connection…")
                    else uiText("测试连接", "Test connection"),
                )
            }
            when (val result = connectionResult) {
                ConnectionTest.Result.Ok -> Text(
                    uiText("连接正常，可以开始问了。", "Connection works. You can start asking questions."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                is ConnectionTest.Result.Failed -> Text(
                    result.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                null -> Unit
            }

            HorizontalDivider()
            Text(uiText("网页搜索", "Web search"), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = serperKey,
                onValueChange = {
                    serperKey = it
                    secureKeyStore.serperApiKey = it
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(uiText("serper.dev API 密钥", "serper.dev API key")) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Text(
                uiText(
                    "填了 serper.dev 的密钥，Vana 遇到自己不知道的事就能上网查一下，并给出出处。" +
                        "不填就只用它已有的知识回答。搜索词不会带上你的健康数据。密钥只保存在本机加密存储。",
                    "Add a serper.dev key to let Vana search for current information and cite sources. " +
                        "Without one, it answers from existing knowledge. Search terms exclude your personal situation and measurements.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text(uiText("助手", "Assistant"), style = MaterialTheme.typography.titleMedium)
            SettingsPickerRow(
                label = uiText("说话方式", "Response style"),
                value = persona.label,
                onClick = { showPersonas = true },
            )
            Text(
                persona.instruction,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingSwitch(uiText("回答前先思考", "Think before answering"), thinking) {
                thinking = it
                engineSettings.thinkingEnabled = it
            }
            SettingSwitch(uiText("长期记忆", "Long-term memory"), memory) {
                memory = it
                engineSettings.memoryEnabled = it
            }
            SettingSwitch(uiText("用药与补剂", "Medications and supplements"), medications) {
                medications = it
                engineSettings.medicationsEnabled = it
            }
            SettingSwitch(uiText("口述测量卡片", "Spoken measurement cards"), measurements) {
                measurements = it
                engineSettings.measurementsEnabled = it
            }
            Text(
                uiText(
                    "只改语气和详略，不改数据口径——同样只引用工具返回的数字，同样不做诊断。",
                    "This changes tone and detail only. Recorded values are still quoted as-is, and Vana still does not diagnose.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                uiText("Vana 记住的事", "What Vana remembers"),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenMemory)
                    .padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                uiText("测量卡片", "Measurement cards"),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenMeasurements)
                    .padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                uiText("家庭成员", "Family members"),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenTenants)
                    .padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            HorizontalDivider()
            Text(uiText("对话", "Conversations"), style = MaterialTheme.typography.titleMedium)
            TextButton(
                onClick = { confirmClearChats = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(uiText("清空全部对话", "Clear all conversations"), color = MaterialTheme.colorScheme.error)
            }
            Text(
                uiText("清空会删除本机保存的所有消息，无法撤销。", "This permanently deletes all messages saved on this device."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text(uiText("照片", "Photos"), style = MaterialTheme.typography.titleMedium)
            SettingsPickerRow(
                label = uiText("照片原图", "Original photos"),
                value = photoPolicy.label,
                onClick = { showPhotoPolicies = true },
            )
            Text(
                photoPolicy.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!engineSettings.modelSupportsVision()) {
                Text(
                    uiText(
                        "当前模型（$modelId）看不了图，这一项暂时不起作用——原图一张都不会发出去，换一个支持看图的模型才会生效。",
                        "The current model ($modelId) cannot view images, so no original photos will be sent. " +
                            "Choose a vision-capable model to use this setting.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                uiText(
                    "照片里的文字一律在本机识别，发出去的默认只有文字。这一项管的只是原图要不要跟着走，而且只是默认——发送之前点开任意一张，都能单独决定这一张发不发。",
                    "Text recognition always runs on-device. This setting controls only the default for original photos; " +
                        "you can review and change each photo before sending.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text(uiText("提醒", "Reminders"), style = MaterialTheme.typography.titleMedium)
            SettingSwitch(uiText("每日 check-in", "Daily check-ins"), checkIns) { enabled ->
                if (enabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        checkIns = true
                        engineSettings.checkInsEnabled = true
                        CheckInScheduler.reschedule(context)
                    }
                } else {
                    checkIns = false
                    engineSettings.checkInsEnabled = false
                    CheckInScheduler.cancel(context)
                }
            }
            if (checkIns) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = morningReminderDescription }
                        .clickable { showMorningPicker = true }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(uiText("早上", "Morning"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "%02d:00".format(morningHour),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = eveningReminderDescription }
                        .clickable { showEveningPicker = true }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(uiText("晚上", "Evening"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "%02d:00".format(eveningHour),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    uiText(
                        "点按时间更改。已排程，每天 $morningHour:00 和 $eveningHour:00 各一条。",
                        "Tap a time to change it. Reminders are scheduled daily at $morningHour:00 and $eveningHour:00.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                uiText(
                    "早晚各一条本地通知；有到期的回访时优先提醒，否则只问一句通用的问题。" +
                        "点开通知会直接开始对应对话。",
                    "Morning and evening notifications are local. Due follow-ups take priority; otherwise Vana asks a brief general question. " +
                        "Opening the notification starts that conversation.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text(uiText("位置", "Location"), style = MaterialTheme.typography.titleMedium)
            if (locationProvider.isAuthorized) {
                Text(
                    uiText(
                        "当前位置：${locationPlace ?: "正在定位…"}",
                        "Current location: ${locationPlace ?: "Locating…"}",
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    uiText("已授权，只精确到城市。", "Allowed. Vana uses city-level location only."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Button(
                    onClick = {
                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(uiText("允许使用大概位置", "Allow approximate location"))
                }
                TextButton(
                    onClick = {
                        val intent = Intent(
                            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                        context.startActivity(intent)
                    },
                ) {
                    Text(uiText("在系统设置里打开位置", "Open location in system settings"))
                }
                Text(
                    uiText("还没授权，回答里不会带位置。", "Location is not allowed, so answers contain no location."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                uiText(
                    "给了之后 Vana 每次回答都知道你大概在哪个城市，季节气候、时差、当地饮食和就医方式才答得准。" +
                        "只取到城市，不取街道地址，也不会保存在本机；不给就完全不带位置，其余功能照常。",
                    "Approximate location lets Vana account for season, climate, time zone and local care. " +
                        "Only the city is used, never a street address, and it is not saved. Everything else works without it.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text(uiText("语音输入", "Voice input"), style = MaterialTheme.typography.titleMedium)
            Text(
                voiceStatusMessage(voiceAvailability, voiceLocale, voiceSupported),
                style = MaterialTheme.typography.bodyLarge,
                color = if (
                    voiceAvailability == VoiceDictation.Availability.UNSUPPORTED_LOCALE ||
                    voiceAvailability == VoiceDictation.Availability.UNAVAILABLE
                ) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                uiText(
                    "按住输入框旁的麦克风说话，松手把字填进输入框，不会直接发送。识别优先走本机；" +
                        "药名和指标名会尽量偏置识别结果。键盘上那颗麦克风照样能用。",
                    "Hold the microphone beside the input field and release to place recognized text in the field; it is not sent automatically. " +
                        "Vana prefers on-device recognition and biases medication and metric names when supported.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text(uiText("关于", "About"), style = MaterialTheme.typography.titleMedium)
            Text(
                uiText("关于 Vana", "About Vana"),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenAbout)
                    .padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                uiText("免责声明、数据去向和隐私说明。", "Disclaimer, data use and privacy policy."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                uiText("版本", "Version") + " ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (BuildConfig.ALLOW_SELF_UPDATE) {
                CheckForUpdatesRow()
            }
            if (BuildConfig.DEBUG) {
                HorizontalDivider()
                Text(
                    uiText("开发", "Developer"),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenDeveloper)
                        .padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    uiText(
                        "种子数据、自检、测试 check-in。只在 Debug 构建里出现。",
                        "Debug tools and test check-ins. Visible only in debug builds.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmClearChats) {
        AlertDialog(
            onDismissRequest = { confirmClearChats = false },
            title = { Text(uiText("清空全部对话？", "Clear all conversations?")) },
            text = {
                Text(uiText("此操作会删除本机保存的所有消息，无法撤销。", "This permanently deletes every message saved on this device."))
            },
            confirmButton = {
                TextButton(onClick = {
                    sessionStore.deleteAll()
                    confirmClearChats = false
                    onChatsCleared()
                }) { Text(uiText("清空对话", "Clear conversations")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearChats = false }) { Text(uiText("取消", "Cancel")) }
            },
        )
    }

    if (showMorningPicker) {
        CheckInHourPickerDialog(
            title = uiText("早上提醒时间", "Morning reminder time"),
            hours = 5..11,
            selected = morningHour,
            onSelect = { hour ->
                morningHour = hour
                engineSettings.morningCheckInHour = hour
                CheckInScheduler.reschedule(context)
                showMorningPicker = false
            },
            onDismiss = { showMorningPicker = false },
        )
    }
    if (showEveningPicker) {
        CheckInHourPickerDialog(
            title = uiText("晚上提醒时间", "Evening reminder time"),
            hours = 18..23,
            selected = eveningHour,
            onSelect = { hour ->
                eveningHour = hour
                engineSettings.eveningCheckInHour = hour
                CheckInScheduler.reschedule(context)
                showEveningPicker = false
            },
            onDismiss = { showEveningPicker = false },
        )
    }

    if (showProviders) {
        ProviderPickerSheet(
            selectedId = providerId,
            onSelect = { id ->
                providerId = id
                engineSettings.providerId = id
                CloudCatalog.defaultModel(id)?.let {
                    modelId = it
                    engineSettings.model = it
                }
            },
            onDismiss = { showProviders = false },
        )
    }

    if (showModels) {
        ModelPickerSheet(
            providerId = providerId,
            selectedId = modelId,
            apiKey = apiKey,
            onSelect = { id ->
                modelId = id
                engineSettings.model = id
            },
            onDismiss = { showModels = false },
        )
    }

    if (showPersonas) {
        SettingsOptionSheet(
            title = uiText("说话方式", "Response style"),
            onDismiss = { showPersonas = false },
        ) {
            AssistantPersona.entries.forEach { option ->
                SettingsOptionRow(
                    title = option.label,
                    selected = option == persona,
                    subtitle = option.instruction,
                    onClick = {
                        persona = option
                        engineSettings.persona = option
                        showPersonas = false
                    },
                )
            }
        }
    }

    if (showPhotoPolicies) {
        SettingsOptionSheet(
            title = uiText("照片原图", "Original photos"),
            onDismiss = { showPhotoPolicies = false },
        ) {
            PhotoImagePolicy.entries.forEach { option ->
                SettingsOptionRow(
                    title = option.label,
                    selected = option == photoPolicy,
                    subtitle = option.summary,
                    onClick = {
                        photoPolicy = option
                        engineSettings.photoImagePolicy = option
                        showPhotoPolicies = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsOptionSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 8.dp, end = 8.dp, bottom = 28.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            content()
        }
    }
}

@Composable
private fun SettingsPickerRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsOptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        trailing?.invoke()
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun CheckInHourPickerDialog(
    title: String,
    hours: IntRange,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                hours.forEach { hour ->
                    Text(
                        "%02d:00".format(hour),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(hour) }
                            .padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (hour == selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(uiText("取消", "Cancel")) }
        },
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun voiceStatusMessage(
    availability: VoiceDictation.Availability,
    locale: String?,
    supported: List<String>,
): String = when (availability) {
    VoiceDictation.Availability.READY ->
        L10n.text(
            "可以用，识别语言 ${locale ?: "zh"}，优先本机。",
            "Available. Recognition language: ${locale ?: "en"}; on-device recognition is preferred.",
        )
    VoiceDictation.Availability.UNSUPPORTED_LOCALE -> {
        val listing = if (supported.isEmpty()) {
            L10n.text("这台设备一种语言都没读到。", "No recognition languages were reported by this device.")
        } else {
            L10n.text(
                "这台设备支持的是：${supported.take(8).joinToString("、")}" +
                    if (supported.size > 8) " 等。" else "。",
                "Supported languages: ${supported.take(8).joinToString(", ")}" +
                    if (supported.size > 8) ", and others." else ".",
            )
        }
        L10n.text(
            "没有可用的中文语音识别，按住说话不会出现。$listing 键盘上那颗麦克风照样能用。",
            "The preferred speech language is unavailable, so hold-to-talk is hidden. $listing You can still use the keyboard microphone.",
        )
    }
    VoiceDictation.Availability.UNAVAILABLE ->
        L10n.text("这台设备用不了本机语音识别。", "Speech recognition is unavailable on this device.")
    VoiceDictation.Availability.UNKNOWN ->
        L10n.text("正在检查…", "Checking…")
}
