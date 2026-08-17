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
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            Text("云端模型", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    secureKeyStore.apiKey = it
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API 密钥") },
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
                    "API 密钥只保存在本机加密存储里。",
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
                label = "模型",
                value = CloudCatalog.modelName(modelId, providerId),
                onClick = { showModels = true },
            )
            if (!CloudCatalog.isLoaded) {
                Text(
                    "未能载入 AIKit provider 目录：${CloudCatalog.diagnostics}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    "Provider 和模型都从 AIKit 内置目录里选。API 密钥只保存在本机加密存储。",
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
                Text(if (isTestingConnection) "正在测试连接…" else "测试连接")
            }
            when (val result = connectionResult) {
                ConnectionTest.Result.Ok -> Text(
                    "连接正常，可以开始问了。",
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
            Text("网页搜索", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = serperKey,
                onValueChange = {
                    serperKey = it
                    secureKeyStore.serperApiKey = it
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("serper.dev API 密钥") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Text(
                "填了 serper.dev 的密钥，Vana 遇到自己不知道的事就能上网查一下，并给出出处。" +
                    "不填就只用它已有的知识回答。搜索词不会带上你的健康数据。密钥只保存在本机加密存储。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text("助手", style = MaterialTheme.typography.titleMedium)
            SettingsPickerRow(
                label = "说话方式",
                value = persona.label,
                onClick = { showPersonas = true },
            )
            Text(
                persona.instruction,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingSwitch("回答前先思考", thinking) {
                thinking = it
                engineSettings.thinkingEnabled = it
            }
            SettingSwitch("长期记忆", memory) {
                memory = it
                engineSettings.memoryEnabled = it
            }
            SettingSwitch("用药与补剂", medications) {
                medications = it
                engineSettings.medicationsEnabled = it
            }
            SettingSwitch("口述测量卡片", measurements) {
                measurements = it
                engineSettings.measurementsEnabled = it
            }
            Text(
                "只改语气和详略，不改数据口径——同样只引用工具返回的数字，同样不做诊断。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Vana 记住的事",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenMemory)
                    .padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "测量卡片",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenMeasurements)
                    .padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "家庭成员",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenTenants)
                    .padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            HorizontalDivider()
            Text("对话", style = MaterialTheme.typography.titleMedium)
            TextButton(
                onClick = { confirmClearChats = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("清空全部对话", color = MaterialTheme.colorScheme.error)
            }
            Text(
                "清空会删除本机保存的所有消息，无法撤销。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text("照片", style = MaterialTheme.typography.titleMedium)
            SettingsPickerRow(
                label = "照片原图",
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
                    "当前模型（$modelId）看不了图，这一项暂时不起作用——原图一张都不会发出去，换一个支持看图的模型才会生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "照片里的文字一律在本机识别，发出去的默认只有文字。这一项管的只是原图要不要跟着走，而且只是默认——发送之前点开任意一张，都能单独决定这一张发不发。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text("提醒", style = MaterialTheme.typography.titleMedium)
            SettingSwitch("每日 check-in", checkIns) { enabled ->
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
                        .semantics { contentDescription = "更改早上提醒时间" }
                        .clickable { showMorningPicker = true }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("早上", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "%02d:00".format(morningHour),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "更改晚上提醒时间" }
                        .clickable { showEveningPicker = true }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("晚上", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "%02d:00".format(eveningHour),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    "点按时间更改。已排程，每天 $morningHour:00 和 $eveningHour:00 各一条。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "早晚各一条本地通知；有到期的回访时优先提醒，否则只问一句通用的问题。" +
                    "点开通知会直接开始对应对话。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text("位置", style = MaterialTheme.typography.titleMedium)
            if (locationProvider.isAuthorized) {
                Text(
                    "当前位置：${locationPlace ?: "正在定位…"}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "已授权，只精确到城市。",
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
                    Text("允许使用大概位置")
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
                    Text("在系统设置里打开位置")
                }
                Text(
                    "还没授权，回答里不会带位置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "给了之后 Vana 每次回答都知道你大概在哪个城市，季节气候、时差、当地饮食和就医方式才答得准。" +
                    "只取到城市，不取街道地址，也不会保存在本机；不给就完全不带位置，其余功能照常。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text("语音输入", style = MaterialTheme.typography.titleMedium)
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
                "按住输入框旁的麦克风说话，松手把字填进输入框，不会直接发送。识别优先走本机；" +
                    "药名和指标名会尽量偏置识别结果。键盘上那颗麦克风照样能用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text("关于", style = MaterialTheme.typography.titleMedium)
            Text(
                "关于 Vana",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenAbout)
                    .padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "免责声明、数据去向和隐私说明。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (BuildConfig.ALLOW_SELF_UPDATE) {
                CheckForUpdatesRow()
            }
            if (BuildConfig.DEBUG) {
                HorizontalDivider()
                Text(
                    "开发",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenDeveloper)
                        .padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "种子数据、自检、测试 check-in。只在 Debug 构建里出现。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmClearChats) {
        AlertDialog(
            onDismissRequest = { confirmClearChats = false },
            title = { Text("清空全部对话？") },
            text = { Text("此操作会删除本机保存的所有消息，无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    sessionStore.deleteAll()
                    confirmClearChats = false
                    onChatsCleared()
                }) { Text("清空对话") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearChats = false }) { Text("取消") }
            },
        )
    }

    if (showMorningPicker) {
        CheckInHourPickerDialog(
            title = "早上提醒时间",
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
            title = "晚上提醒时间",
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
            title = "说话方式",
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
            title = "照片原图",
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
            TextButton(onClick = onDismiss) { Text("取消") }
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
        "可以用，识别语言 ${locale ?: "zh"}，优先本机。"
    VoiceDictation.Availability.UNSUPPORTED_LOCALE -> {
        val listing = if (supported.isEmpty()) {
            "这台设备一种语言都没读到。"
        } else {
            "这台设备支持的是：${supported.take(8).joinToString("、")}" +
                if (supported.size > 8) " 等。" else "。"
        }
        "没有可用的中文语音识别，按住说话不会出现。$listing 键盘上那颗麦克风照样能用。"
    }
    VoiceDictation.Availability.UNAVAILABLE ->
        "这台设备用不了本机语音识别。"
    VoiceDictation.Availability.UNKNOWN ->
        "正在检查…"
}
