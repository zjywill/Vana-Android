package com.pinapia.vana.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.pinapia.vana.Features
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.ContextCompat
import com.pinapia.vana.agent.FollowUpSuggester
import com.pinapia.vana.ask.AskUserCard
import com.pinapia.vana.ask.AskUserTools
import com.pinapia.vana.exercises.ExerciseCards
import com.pinapia.vana.exercises.ExerciseLibrary
import com.pinapia.vana.exercises.ExerciseTools
import com.pinapia.vana.health.HealthSituation
import com.pinapia.vana.health.HealthStatusScreen
import com.pinapia.vana.health.HealthStore
import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.session.GoalSummary
import com.pinapia.vana.session.SessionSummary
import com.pinapia.vana.session.TurnSegment
import com.pinapia.vana.session.compactionSummary
import com.pinapia.vana.session.foldedSpan
import com.pinapia.vana.tenant.TenantScope
import com.pinapia.vana.vision.AttachmentImporter
import com.pinapia.vana.vision.ChatAttachment
import com.pinapia.vana.vision.DraftAttachment
import com.pinapia.vana.voice.VoiceDictation
import com.pinapia.vana.voice.VoiceInputButton
import com.pinapia.vana.voice.VoiceLevelStrip
import kotlinx.coroutines.launch
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import com.pinapia.vana.vision.AttachmentReviewScreen
import com.pinapia.vana.vision.CapturePhoto
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    healthStore: HealthStore,
    exerciseLibrary: ExerciseLibrary,
    onOpenSettings: () -> Unit,
    onOpenMedications: () -> Unit,
    onOpenMeasurements: () -> Unit = {},
    onOpenTenants: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val summaries by viewModel.summaries.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val isReplying by viewModel.isReplying.collectAsStateWithLifecycle()
    val engineGuidance by viewModel.engineGuidance.collectAsStateWithLifecycle()
    val retryNotice by viewModel.retryNotice.collectAsStateWithLifecycle()
    val followUps by viewModel.followUps.collectAsStateWithLifecycle()
    val quickSummary by viewModel.quickSummary.collectAsStateWithLifecycle()
    val situation by viewModel.situation.collectAsStateWithLifecycle()
    val isWritingSummary by viewModel.isWritingSummary.collectAsStateWithLifecycle()
    val drafts by viewModel.draftAttachments.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // SSE 贴底：用户没滑开时跟着最后一条长高；一旦手势离开底部就停在用户位置。
    val followOutput = remember { mutableStateOf(true) }
    val followScroll = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0.5f) {
                    followOutput.value = false
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput && !listState.canScrollForward) {
                    followOutput.value = true
                }
                return Offset.Zero
            }
        }
    }
    val context = LocalContext.current
    var showHealthStatus by remember { mutableStateOf(false) }
    var reviewingId by remember { mutableStateOf<String?>(null) }
    var captureUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var cameraNotice by remember { mutableStateOf<String?>(null) }
    val hasCamera = remember { CapturePhoto.isAvailable(context) }
    val voice = remember { VoiceDictation.shared(context) }
    val voiceAvailability by voice.availability.collectAsStateWithLifecycle()
    val voiceStatus by voice.status.collectAsStateWithLifecycle()
    val voiceLevel by voice.level.collectAsStateWithLifecycle()
    val voiceNotice by voice.notice.collectAsStateWithLifecycle()
    var voiceCancelling by remember { mutableStateOf(false) }
    val isVoiceListening = voiceStatus == VoiceDictation.Status.LISTENING ||
        voiceStatus == VoiceDictation.Status.STARTING
    val currentOpenSettings by rememberUpdatedState(onOpenSettings)

    LaunchedEffect(viewModel) {
        viewModel.cloudSetupRequests.collect {
            currentOpenSettings()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = healthStore.permissionContract(),
    ) { }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voice.start(viewModel.voiceVocabulary())
        } else {
            // 权限拒了：下次按住还会再问；notice 由 VoiceDictation 在 ERROR 时补。
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(6),
    ) { uris ->
        uris.forEach { uri ->
            CapturePhoto.decode(context, uri)?.let(viewModel::addPhoto)
        }
    }

    val takePicture = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = captureUri
        captureUri = null
        if (uri == null) return@rememberLauncherForActivityResult
        if (success) {
            CapturePhoto.decode(context, uri)?.let(viewModel::addPhoto)
        }
        CapturePhoto.cleanup(context, uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            cameraNotice = null
            val uri = CapturePhoto.createUri(context)
            captureUri = uri
            takePicture.launch(uri)
        } else {
            cameraNotice = "没有相机权限，没法拍照。可以到系统设置里打开，或从相册选取。"
        }
    }

    fun launchCamera() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            cameraNotice = null
            val uri = CapturePhoto.createUri(context)
            captureUri = uri
            takePicture.launch(uri)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        uris.forEach { uri -> viewModel.importFile(context, uri) }
    }

    LaunchedEffect(Unit) {
        voice.refresh()
        if (Features.HEALTH_CONNECT && TenantScope.isOwnerActive) {
            when (healthStore.sdkStatus()) {
                HealthStore.SdkStatus.AVAILABLE -> {
                    if (!healthStore.hasAllPermissions()) {
                        permissionLauncher.launch(healthStore.permissions)
                    }
                }
                HealthStore.SdkStatus.UPDATE_REQUIRED,
                HealthStore.SdkStatus.UNAVAILABLE,
                -> Unit
            }
        }
        viewModel.refreshSituation()
    }

    // ChatViewModel 在返回栈上会活着，设置里填完密钥后必须重新读，不能沿用进设置前的 hint。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshEngineAvailability()
    }

    // 新消息 / 换会话：回到底部并重新贴底。流式长高用 layout overflow 跟，
    // 不要对每个 token animateScroll，否则动画互相取消，看起来像整段蹦出。
    LaunchedEffect(session.id, session.messages.size) {
        followOutput.value = true
        if (session.messages.isNotEmpty()) {
            listState.animateScrollToItem(session.messages.lastIndex)
        }
    }
    LaunchedEffect(isReplying, followOutput.value) {
        if (!isReplying || !followOutput.value) return@LaunchedEffect
        snapshotFlow { listState.bottomOverflowOrHidden() }.collect { overflow ->
            if (!followOutput.value) return@collect
            if (overflow == null) {
                val lastIndex = listState.layoutInfo.totalItemsCount - 1
                if (lastIndex >= 0) listState.scrollToItem(lastIndex)
            } else if (overflow > 1) {
                listState.scrollBy(overflow.toFloat())
            }
        }
    }

    val lastAssistantId = session.messages.lastOrNull { it.role == ChatMessage.Role.ASSISTANT }?.id
    val imageSendCandidates = viewModel.imageSendCandidates
    val isRecognizingAttachments = drafts.any { it.isLoading || it.isRecognizing }
    val canSend = (input.isNotBlank() || drafts.any { it.failure == null }) &&
        !isRecognizingAttachments &&
        drafts.none { it.isLoading }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // ModalDrawerSheet 默认 maxWidth=360dp，会话列表按全屏页处理。
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RectangleShape,
                color = MaterialTheme.colorScheme.surface,
            ) {
                val goals by viewModel.goals.collectAsStateWithLifecycle()
                SessionDrawer(
                    summaries = summaries,
                    goals = goals,
                    currentId = session.id,
                    onClose = { scope.launch { drawerState.close() } },
                    onNew = {
                        viewModel.startNewSession(isPrivate = false)
                        scope.launch { drawerState.close() }
                    },
                    onNewPrivate = {
                        viewModel.startNewSession(isPrivate = true)
                        scope.launch { drawerState.close() }
                    },
                    onNewGoal = { name ->
                        viewModel.startGoal(name)
                        scope.launch { drawerState.close() }
                    },
                    onOpen = {
                        viewModel.openSession(it)
                        scope.launch { drawerState.close() }
                    },
                    onOpenGoal = {
                        viewModel.openGoal(it)
                        scope.launch { drawerState.close() }
                    },
                    onDelete = viewModel::deleteSession,
                    onDeleteGoal = viewModel::deleteGoal,
                    onOpenTenants = {
                        scope.launch { drawerState.close() }
                        onOpenTenants()
                    },
                )
            }
        },
        modifier = modifier,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Vana")
                            val subtitle = buildList {
                                if (!TenantScope.current.isOwner) add(TenantScope.current.displayName)
                                if (session.isPrivate) add("隐私对话 · 不保存")
                            }.joinToString(" · ")
                            if (subtitle.isNotEmpty()) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "会话列表")
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenMeasurements) {
                            Icon(Icons.Default.MonitorHeart, contentDescription = "测量卡片")
                        }
                        IconButton(onClick = onOpenMedications) {
                            Icon(Icons.Default.Medication, contentDescription = "用药与补剂")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                    },
                )
            },
        ) { insets ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets)
                    .consumeWindowInsets(insets)
                    .imePadding(),
            ) {
                if (Features.HEALTH_CONNECT && TenantScope.current.isOwner) {
                    HealthConnectBanner(healthStore = healthStore)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .nestedScroll(followScroll),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (session.isEmpty) {
                        item {
                            val selectedTopic by viewModel.selectedTopic.collectAsStateWithLifecycle()
                            WelcomeCard(
                                isOwner = TenantScope.current.isOwner,
                                isPrivate = session.isPrivate,
                                healthConnectEnabled = Features.HEALTH_CONNECT,
                                quickSummary = if (Features.HEALTH_CONNECT) {
                                    quickSummary
                                        ?: if (TenantScope.current.isOwner) {
                                            healthStore.emptyDataHint().takeIf {
                                                healthStore.sdkStatus() != HealthStore.SdkStatus.AVAILABLE
                                            } ?: HealthSituation.CALM_SUMMARY
                                        } else {
                                            null
                                        }
                                } else {
                                    null
                                },
                                onTogglePrivate = { viewModel.setPrivate(!session.isPrivate) },
                                onOpenHealthStatus = {
                                    if (Features.HEALTH_CONNECT && TenantScope.current.isOwner) {
                                        showHealthStatus = true
                                    }
                                },
                                selectedTopic = selectedTopic,
                                onSelectTopic = viewModel::selectTopic,
                                suggestions = viewModel.suggestedQuestions,
                                onSuggestion = viewModel::send,
                                setupGuidance = engineGuidance,
                                onOpenSettings = onOpenSettings,
                            )
                        }
                    }
                    items(session.messages, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            isLiveReply = isReplying && message.id == lastAssistantId,
                            isAskLive = !isReplying && message.id == lastAssistantId,
                            isReplying = isReplying,
                            exerciseLibrary = exerciseLibrary,
                            onRetry = { viewModel.retry(message.id) },
                            onBranch = { viewModel.branch(message.id) },
                            onAnswerAsk = { callId, answer ->
                                viewModel.answerAsk(message.id, callId, answer)
                            },
                        )
                    }
                }

                // 空会话时配置提示已经嵌进欢迎卡,别在输入框上方再刷一行红字。
                if (!session.isEmpty) {
                    engineGuidance?.let {
                        Text(
                            it,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                retryNotice?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                if (drafts.isNotEmpty()) {
                    DraftStrip(
                        drafts = drafts,
                        onOpen = { id -> reviewingId = id },
                        onRemove = viewModel::removeDraft,
                    )
                }

                if (imageSendCandidates.isNotEmpty()) {
                    ImageSendOffer(
                        candidates = imageSendCandidates,
                        onAccept = { viewModel.setCandidateSendsImage(true) },
                        onDecline = { viewModel.setCandidateSendsImage(false) },
                    )
                }

                cameraNotice?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                voiceNotice?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                VoiceLevelStrip(
                    level = voiceLevel,
                    isCancelling = voiceCancelling,
                    visible = isVoiceListening,
                )

                if (!session.isEmpty) {
                    FollowUpChips(
                        chips = FollowUpSuggester.displayChips(followUps),
                        enabled = !isReplying,
                        onChip = viewModel::send,
                    )
                }

                ComposerBar(
                    input = input,
                    isReplying = isReplying,
                    canSend = canSend,
                    isRecognizing = isRecognizingAttachments,
                    canAttachMore = drafts.size < ChatAttachment.MAX_ATTACHMENTS,
                    hasCamera = hasCamera,
                    voiceEnabled = voiceAvailability != VoiceDictation.Availability.UNSUPPORTED_LOCALE &&
                        voiceAvailability != VoiceDictation.Availability.UNAVAILABLE,
                    isVoiceListening = isVoiceListening,
                    voiceCancelling = voiceCancelling,
                    onInputChange = viewModel::setInput,
                    onSend = { viewModel.send() },
                    onStop = viewModel::stopReply,
                    onAddCamera = { launchCamera() },
                    onAddPhoto = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onAddFile = {
                        filePicker.launch(AttachmentImporter.MIME_TYPES)
                    },
                    onVoicePress = {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            voice.start(viewModel.voiceVocabulary())
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onVoiceRelease = { cancelled ->
                        if (cancelled) {
                            voice.cancel()
                        } else {
                            viewModel.appendVoiceTranscript(voice.stop())
                        }
                        voiceCancelling = false
                    },
                    onVoiceCancellingChange = { voiceCancelling = it },
                )
            }
        }
    }

    if (Features.HEALTH_CONNECT && showHealthStatus && TenantScope.current.isOwner) {
        HealthStatusScreen(
            summary = quickSummary ?: HealthSituation.CALM_SUMMARY,
            situation = situation,
            isWriting = isWritingSummary,
            canGenerate = engineGuidance == null,
            onRefresh = viewModel::regenerateQuickSummary,
            onDismiss = { showHealthStatus = false },
        )
    }

    val reviewing = drafts.firstOrNull { it.id == reviewingId && !it.isLoading }
    LaunchedEffect(reviewingId, drafts) {
        if (reviewingId != null && drafts.none { it.id == reviewingId }) {
            reviewingId = null
        }
    }
    if (reviewing != null) {
        Dialog(
            onDismissRequest = { reviewingId = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = true,
            ),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                AttachmentReviewScreen(
                    draft = reviewing,
                    supportsVision = viewModel.supportsVision,
                    visionUnavailableNote = viewModel.visionUnavailableNote,
                    onChangeText = { viewModel.updateDraftText(reviewing.id, it) },
                    onChangeSendsImage = if (viewModel.supportsVision) {
                        { sends -> viewModel.setDraftSendsImage(reviewing.id, sends) }
                    } else {
                        null
                    },
                    onRemove = {
                        viewModel.removeDraft(reviewing.id)
                        reviewingId = null
                    },
                    onSaveMedication = viewModel::saveMedicationFromDraft,
                    onDismiss = { reviewingId = null },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun ImageSendOffer(
    candidates: List<DraftAttachment>,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val sending = candidates.count { it.sendsImage }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            imageSendTitle(candidates, sending),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
        )
        if (sending > 0) {
            TextButton(onClick = onDecline) { Text("撤销") }
        } else {
            TextButton(onClick = onAccept) { Text("好") }
        }
    }
}

private fun imageSendTitle(candidates: List<DraftAttachment>, sending: Int): String {
    if (sending > 0) {
        return if (sending == 1) "原图会随这句话发出去" else "$sending 张原图会随这句话发出去"
    }
    val allBlank = candidates.all { !it.hasText }
    return when {
        candidates.size == 1 && allBlank -> "这张图没有文字，让 Vana 直接看图？"
        candidates.size == 1 -> "让 Vana 直接看这张图？"
        allBlank -> "有 ${candidates.size} 张没有文字，让 Vana 直接看图？"
        else -> "让 Vana 直接看这 ${candidates.size} 张图？"
    }
}

private fun draftCaption(draft: DraftAttachment): String = when {
    draft.isLoading -> "载入中…"
    draft.isRecognizing -> "识别中…"
    draft.failure != null -> "读不出来"
    !draft.hasText -> if (draft.isDocument) "没有正文" else "没有文字"
    else -> {
        val lines = draft.text.split('\n').count { it.isNotBlank() }.coerceAtLeast(1)
        if (draft.droppedLines > 0) "$lines 行·已截断" else "$lines 行"
    }
}

@Composable
private fun HealthConnectBanner(
    healthStore: HealthStore,
    modifier: Modifier = Modifier,
) {
    val status = healthStore.sdkStatus()
    if (status == HealthStore.SdkStatus.AVAILABLE) return
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                healthStore.emptyDataHint(),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (status == HealthStore.SdkStatus.UPDATE_REQUIRED) {
                TextButton(onClick = { healthStore.openProviderInstall() }) {
                    Text("去安装 Health Connect")
                }
            }
        }
    }
}
@Composable
private fun DraftStrip(
    drafts: List<DraftAttachment>,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        drafts.forEach { draft ->
            Box {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !draft.isLoading) { onOpen(draft.id) }
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(modifier = Modifier.size(68.dp)) {
                        when {
                            draft.preview != null -> {
                                Image(
                                    bitmap = draft.preview!!.asImageBitmap(),
                                    contentDescription = draft.documentName ?: "附件预览",
                                    modifier = Modifier
                                        .size(68.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                            draft.isDocument -> {
                                Icon(
                                    Icons.Default.Description,
                                    contentDescription = draft.documentName ?: "文件附件",
                                    modifier = Modifier
                                        .size(68.dp)
                                        .padding(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            else -> {
                                Box(
                                    modifier = Modifier
                                        .size(68.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                )
                            }
                        }
                        if (draft.isLoading || draft.isRecognizing) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Black.copy(alpha = 0.35f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White,
                                )
                            }
                        }
                        if (draft.sendsImage) {
                            Icon(
                                Icons.Default.Visibility,
                                contentDescription = "原图会一起发出去",
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .padding(2.dp),
                                tint = Color.White,
                            )
                        }
                    }
                    Text(
                        draftCaption(draft),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (draft.failure != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        modifier = Modifier.width(68.dp),
                    )
                }
                IconButton(
                    onClick = { onRemove(draft.id) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-8).dp)
                        .size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "不发这张",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FollowUpChips(
    chips: List<String>,
    enabled: Boolean,
    onChip: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { chip ->
            SuggestionChip(
                onClick = { onChip(chip) },
                label = { Text(chip) },
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun WelcomeCard(
    isOwner: Boolean,
    isPrivate: Boolean,
    healthConnectEnabled: Boolean,
    quickSummary: String?,
    onTogglePrivate: () -> Unit,
    onOpenHealthStatus: () -> Unit = {},
    selectedTopic: ChatTopic?,
    onSelectTopic: (ChatTopic?) -> Unit,
    suggestions: List<String>,
    onSuggestion: (String) -> Unit,
    setupGuidance: String? = null,
    onOpenSettings: () -> Unit = {},
) {
    val title = when {
        !isOwner -> "从${TenantScope.current.displayName}的化验单和用药开始"
        healthConnectEnabled -> "从你的健康数据开始"
        else -> "你好，我是 Vana"
    }
    val body = when {
        !isOwner ->
            "拍一张${TenantScope.current.displayName}的化验单、报告或药盒，文字在本机识别后再帮你看。" +
                "这位成员没有本机健康数据。"
        healthConnectEnabled ->
            "可以直接问步数、睡眠、心率、锻炼和体重。Vana 只读取你授权的 Health Connect 数据，不会修改记录。"
        else ->
            "拍化验单或药盒、聊症状与用药习惯，或记下你想跟进的事。" +
                "文字识别在本机完成；要回答问题时才会把必要内容发给你配置的模型。"
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (healthConnectEnabled && !quickSummary.isNullOrBlank()) {
            Text(
                quickSummary,
                style = MaterialTheme.typography.bodyLarge,
                modifier = if (isOwner) {
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenHealthStatus)
                        .padding(vertical = 4.dp)
                } else {
                    Modifier
                },
            )
        }

        if (!setupGuidance.isNullOrBlank()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(setupGuidance, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onOpenSettings) {
                        Text("去设置")
                    }
                }
            }
        }

        if (isPrivate) {
            Text(
                "这条对话不会被保存。不进会话列表，不写进记忆。" +
                    "问题仍要发给你配置的模型才能回答。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FilterChip(
            selected = isPrivate,
            onClick = onTogglePrivate,
            label = { Text(if (isPrivate) "隐私对话（不保存）" else "普通对话") },
            modifier = Modifier.semantics {
                contentDescription = if (isPrivate) {
                    "当前为隐私对话，不会保存。点按切换为普通对话"
                } else {
                    "当前为普通对话。点按切换为隐私对话，不会保存"
                }
            },
        )

        // 话题格子指向健康工具;HC 关掉时摆着只会点出空结果。
        if (isOwner && healthConnectEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("想聊什么", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedTopic == null,
                        onClick = { onSelectTopic(null) },
                        label = { Text("不限话题") },
                    )
                    ChatTopics.all.forEach { topic ->
                        FilterChip(
                            selected = selectedTopic?.id == topic.id,
                            onClick = { onSelectTopic(topic) },
                            label = { Text(topic.name) },
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("试着问", style = MaterialTheme.typography.titleSmall)
            suggestions.forEach { question ->
                Surface(
                    onClick = { onSuggestion(question) },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        question,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        Text(
            "健康分析仅供参考，不能替代专业医疗建议。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 最后一条底部超出视口的像素；null 表示最后一条还不在视口里。 */
private fun LazyListState.bottomOverflowOrHidden(): Int? {
    val info = layoutInfo
    val lastIndex = info.totalItemsCount - 1
    if (lastIndex < 0) return 0
    val last = info.visibleItemsInfo.lastOrNull() ?: return null
    if (last.index != lastIndex) return null
    val viewportBottom = info.viewportEndOffset - info.afterContentPadding
    return (last.offset + last.size) - viewportBottom
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    isLiveReply: Boolean,
    isAskLive: Boolean,
    isReplying: Boolean,
    exerciseLibrary: ExerciseLibrary,
    onRetry: () -> Unit,
    onBranch: () -> Unit,
    onAnswerAsk: (String, com.pinapia.vana.ask.AskUserAnswer) -> Unit,
) {
    val isUser = message.role == ChatMessage.Role.USER
    var openReasoningId by remember(message.id) { mutableStateOf<String?>(null) }
    var expandedToolId by remember(message.id) { mutableStateOf<String?>(null) }
    val segments = if (isUser) emptyList() else message.turnSegments
    val lastSegmentId = segments.lastOrNull()?.stableId
    // 动作卡 / 问题卡等这一轮写完再出。工具一返回卡上的数据就齐了,正文要等下一轮请求
    // 才吐出来——卡先出来、回答插在卡上面,就是这段窗口。判据是整轮结束,不是「正文开口」。
    val showsToolCards = !isLiveReply
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .then(if (isUser) Modifier.widthIn(max = 340.dp) else Modifier.fillMaxWidth())
                .alpha(if (message.isQueued) 0.55f else 1f),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (isUser && message.attachments.isNotEmpty()) {
                MessageAttachments(message.attachments)
            }
            if (isUser) {
                if (message.text.isNotBlank()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        Text(
                            text = message.text,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            } else {
                segments.forEach { segment ->
                    key(segment.stableId) {
                        when (segment) {
                            is TurnSegment.Reasoning -> {
                                val isThinking = isLiveReply && segment.stableId == lastSegmentId
                                SuggestionChip(
                                    onClick = { openReasoningId = segment.stableId },
                                    label = {
                                        Text(if (isThinking) "正在思考…" else "思考过程")
                                    },
                                )
                                if (openReasoningId == segment.stableId) {
                                    ReasoningSheet(
                                        text = segment.text,
                                        isThinking = isThinking,
                                        onDismiss = { openReasoningId = null },
                                    )
                                }
                            }
                            is TurnSegment.Tool -> {
                                val call = segment.call
                                SuggestionChip(
                                    onClick = {
                                        if (call.output != null &&
                                            call.name != AskUserTools.ASK_TOOL_NAME &&
                                            call.name != "remember" &&
                                            call.exerciseIDs.isEmpty()
                                        ) {
                                            expandedToolId =
                                                if (expandedToolId == call.id) null else call.id
                                        }
                                    },
                                    label = {
                                        Text(toolCallLabel(call) + if (call.isError) "（失败）" else "")
                                    },
                                )
                                if (expandedToolId == call.id && !call.output.isNullOrBlank()) {
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surface,
                                        ),
                                    ) {
                                        Text(
                                            call.output.orEmpty(),
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(12.dp),
                                        )
                                    }
                                }
                            }
                            is TurnSegment.Text -> {
                                MarkdownText(
                                    markdown = segment.text,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
                if (!isLiveReply && !message.hasVisibleTurnContent) {
                    MarkdownText(markdown = "…", modifier = Modifier.fillMaxWidth())
                } else if (message.textIsPlaceholder && message.text.isNotBlank() && segments.none { it is TurnSegment.Text }) {
                    MarkdownText(markdown = message.text, modifier = Modifier.fillMaxWidth())
                }
            }
            message.foldedSpan?.let { count ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        "以上 $count 条已折叠",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    message.compactionSummary?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (!isUser && showsToolCards) {
                val exerciseIds = remember(message.toolCalls) {
                    val seen = linkedSetOf<String>()
                    message.toolCalls.forEach { call ->
                        call.exerciseIDs.forEach { seen.add(it) }
                    }
                    seen.toList()
                }
                if (exerciseIds.isNotEmpty()) {
                    ExerciseCards(moves = exerciseLibrary.moves(exerciseIds))
                }
                message.toolCalls.forEach { call ->
                    val question = call.askQuestion ?: return@forEach
                    AskUserCard(
                        question = question,
                        answer = call.askAnswer,
                        isLive = isAskLive,
                        onAnswer = { onAnswerAsk(call.id, it) },
                    )
                }
            }
            if (message.isQueued) {
                Text(
                    text = "Vana 还没看到",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!isUser && message.errorDescription != null) {
                TextButton(onClick = onRetry) { Text("重试") }
            }
            if (!isUser && !message.textIsPlaceholder && message.text.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onBranch, enabled = !isReplying) {
                        Text("在新对话里分支")
                    }
                    TextButton(onClick = onRetry, enabled = !isReplying) {
                        Text("重新回答")
                    }
                }
                Text(
                    text = "以上由 AI 生成，可能有误。不构成诊断或用药建议，关键数值请对照原始记录核对。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReasoningSheet(
    text: String,
    isThinking: Boolean,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scroll = rememberScrollState()
    LaunchedEffect(text.length) {
        if (isThinking) {
            scroll.animateScrollTo(scroll.maxValue)
        }
    }
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                if (isThinking) "正在思考" else "思考过程",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(scroll),
            )
        }
    }
}

@Composable
private fun MessageAttachments(attachments: List<ChatAttachment>) {
    var showText by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        attachments.forEach { attachment ->
            val bytes = attachment.imagePayload?.let {
                android.util.Base64.decode(it, android.util.Base64.DEFAULT)
            }
            val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "附件预览，点按查看识别文字",
                    modifier = Modifier
                        .size(76.dp)
                        .clickable { showText = !showText },
                )
            } else {
                Card(
                    modifier = Modifier
                        .size(76.dp)
                        .semantics { contentDescription = "文件附件，点按查看识别文字" }
                        .clickable { showText = !showText },
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null)
                        Text(
                            attachment.documentName ?: "文件",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
    if (showText) {
        val combined = attachments.joinToString("\n\n") { it.text.trim() }.trim()
        Text(
            combined.ifBlank {
                when {
                    attachments.any { it.sendsImage } -> "这张图里没有识别到文字，原图发给了模型。"
                    attachments.any { it.documentName != null } -> "这份文件里没有取到文字。"
                    else -> "这张图里没有识别到文字。"
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun toolCallLabel(call: com.pinapia.vana.session.ToolCallRecord): String = when (call.name) {
    "daily_steps" -> "查询了活动量"
    "sleep_summary" -> "查询了睡眠"
    "heart_rate_summary" -> "查询了静息心率与 HRV"
    "workouts" -> "查询了锻炼"
    "body_metrics" -> "查询了体重"
    "remember" -> "记住了"
    "list_medications" -> "查看了用药表"
    "log_medication", "update_medication" -> "更新了用药表"
    "list_measurements" -> "查看了测量卡片"
    "log_measurement" -> "记下了测量"
    AskUserTools.ASK_TOOL_NAME -> "问了你一句"
    "web_search" -> "搜索了网页"
    "search_sessions" -> "查找了过往对话"
    "read_session" -> "读了一次过往对话"
    ExerciseTools.SUGGEST_TOOL_NAME ->
        if (call.exerciseIDs.isEmpty()) "没找到合适的动作" else "挑了 ${call.exerciseIDs.size} 个动作"
    else -> "调用了 ${call.name}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerBar(
    input: String,
    isReplying: Boolean,
    canSend: Boolean,
    isRecognizing: Boolean,
    canAttachMore: Boolean,
    hasCamera: Boolean,
    voiceEnabled: Boolean,
    isVoiceListening: Boolean,
    voiceCancelling: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAddCamera: () -> Unit,
    onAddPhoto: () -> Unit,
    onAddFile: () -> Unit,
    onVoicePress: () -> Unit,
    onVoiceRelease: (Boolean) -> Unit,
    onVoiceCancellingChange: (Boolean) -> Unit,
) {
    var showAttachSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurface
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .padding(start = 2.dp, end = 4.dp),
            ) {
                IconButton(
                    onClick = { showAttachSheet = true },
                    enabled = canAttachMore,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "添加照片或文件",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (canAttachMore) 1f else 0.38f,
                        ),
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 12.dp),
                    textStyle = textStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 5,
                    decorationBox = { inner ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (input.isEmpty()) {
                                Text(
                                    "问问 Vana…",
                                    style = textStyle,
                                    color = placeholderColor,
                                )
                            }
                            inner()
                        }
                    },
                )
                if (voiceEnabled) {
                    VoiceInputButton(
                        isListening = isVoiceListening,
                        isCancelling = voiceCancelling,
                        enabled = true,
                        onPress = onVoicePress,
                        onRelease = onVoiceRelease,
                        onCancellingChange = onVoiceCancellingChange,
                    )
                }
            }
        }

        if (isRecognizing) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                )
            }
        } else if (isReplying) {
            ComposerCircleButton(
                onClick = onStop,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                enabled = true,
                contentDescription = "停止回答",
                icon = Icons.Default.Stop,
            )
        } else {
            ComposerCircleButton(
                onClick = onSend,
                containerColor = if (canSend) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (canSend) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                enabled = canSend,
                contentDescription = "发送",
                icon = Icons.AutoMirrored.Filled.Send,
                iconAlpha = if (canSend) 1f else 0.45f,
            )
        }
    }

    if (showAttachSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachSheet = false },
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
                    "照片在本机识别成文字，文件直接取文字；原图默认不发，发送之前每一张都能单独决定",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (hasCamera) {
                    AttachSheetRow(
                        icon = Icons.Default.PhotoCamera,
                        title = "拍照",
                        subtitle = "化验单、药盒、报告",
                        onClick = {
                            showAttachSheet = false
                            onAddCamera()
                        },
                    )
                }
                AttachSheetRow(
                    icon = Icons.Default.PhotoLibrary,
                    title = "从相册选取",
                    subtitle = "已经拍过的那些",
                    onClick = {
                        showAttachSheet = false
                        onAddPhoto()
                    },
                )
                AttachSheetRow(
                    icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                    title = "添加文件",
                    subtitle = "PDF 或 Word",
                    onClick = {
                        showAttachSheet = false
                        onAddFile()
                    },
                )
            }
        }
    }
}

@Composable
private fun AttachSheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ComposerCircleButton(
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    enabled: Boolean,
    contentDescription: String,
    icon: ImageVector,
    iconAlpha: Float = 1f,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(containerColor)
            .semantics { this.contentDescription = contentDescription }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.alpha(iconAlpha),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDrawer(
    summaries: List<SessionSummary>,
    goals: List<GoalSummary>,
    currentId: String,
    onClose: () -> Unit,
    onNew: () -> Unit,
    onNewPrivate: () -> Unit,
    onNewGoal: (String) -> Unit,
    onOpen: (String) -> Unit,
    onOpenGoal: (GoalSummary) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteGoal: (GoalSummary) -> Unit,
    onOpenTenants: () -> Unit,
) {
    var showGoalDialog by remember { mutableStateOf(false) }
    var showNewMenu by remember { mutableStateOf(false) }
    var goalName by remember { mutableStateOf("") }
    var pendingDeleteSessionId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteGoal by remember { mutableStateOf<GoalSummary?>(null) }

    // 目标线已在上方单独列出，时间分组里再出现同一条会当成两条。
    val goalThreads = remember(goals) { goals.map { it.threadId }.toSet() }
    val looseSummaries = remember(summaries, goalThreads) {
        summaries.filter { summary ->
            summary.threadId == null || summary.threadId !in goalThreads
        }
    }
    val groups = remember(looseSummaries) { SessionTimeSection.group(looseSummaries) }
    val currentThreadId = remember(currentId, summaries) {
        summaries.firstOrNull { it.id == currentId }?.threadId
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("会话") },
            navigationIcon = {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.semantics { contentDescription = "关闭会话列表" },
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            actions = {
                Box {
                    IconButton(
                        onClick = { showNewMenu = true },
                        modifier = Modifier.semantics { contentDescription = "新建" },
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = showNewMenu,
                        onDismissRequest = { showNewMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("新对话") },
                            onClick = {
                                showNewMenu = false
                                onNew()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Edit, contentDescription = null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("隐私对话（不保存）") },
                            onClick = {
                                showNewMenu = false
                                onNewPrivate()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.VisibilityOff, contentDescription = null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("新目标") },
                            onClick = {
                                showNewMenu = false
                                showGoalDialog = true
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Add, contentDescription = null)
                            },
                        )
                    }
                }
            },
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (TenantScope.isolationAvailable) {
                item(key = "tenant") {
                    ListItem(
                        headlineContent = { Text("家庭成员") },
                        supportingContent = {
                            Text(
                                TenantScope.current.displayName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenTenants)
                            .semantics {
                                contentDescription =
                                    "家庭成员，当前 ${TenantScope.current.displayName}"
                            },
                    )
                    HorizontalDivider()
                }
            }

            if (goals.isNotEmpty()) {
                item(key = "section-goals") { SessionSectionLabel("目标") }
                items(goals, key = { "goal-${it.threadId}" }) { goal ->
                    val selected = goal.threadId == currentThreadId
                    SessionListRow(
                        title = goal.title,
                        subtitle = buildString {
                            append(SessionTimeSection.rowTimeLabel(goal.updatedAt.toEpochMilliseconds()))
                            append(" · ${goal.messageCount} 条")
                            if (goal.segmentCount > 1) append(" · ${goal.segmentCount} 段")
                        },
                        selected = selected,
                        pendingDelete = pendingDeleteGoal?.threadId == goal.threadId,
                        onOpen = { onOpenGoal(goal) },
                        onRequestDelete = { pendingDeleteGoal = goal },
                        deleteLabel = "左滑删除目标 ${goal.title}",
                    )
                }
            }

            if (looseSummaries.isEmpty() && goals.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 48.dp),
                    ) {
                        Text(
                            "还没有会话",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "问一个健康问题，这里就会出现记录。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                groups.forEach { group ->
                    item(key = "section-${group.bucket}") {
                        SessionSectionLabel(group.bucket.title)
                    }
                    items(group.sessions, key = { it.id }) { summary ->
                        val selected = summary.id == currentId
                        SessionListRow(
                            title = summary.title,
                            subtitle = "${SessionTimeSection.rowTimeLabel(summary.updatedAt.toEpochMilliseconds())} · ${summary.messageCount} 条",
                            selected = selected,
                            pendingDelete = pendingDeleteSessionId == summary.id,
                            onOpen = { onOpen(summary.id) },
                            onRequestDelete = { pendingDeleteSessionId = summary.id },
                            deleteLabel = "左滑删除对话 ${summary.title}",
                        )
                    }
                }
            }

            item(key = "bottom-spacer") {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showGoalDialog) {
        AlertDialog(
            onDismissRequest = { showGoalDialog = false },
            title = { Text("新目标") },
            text = {
                Column {
                    Text("目标是一件要聊很久的事。之后每次回到它，都接着上次说。")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = goalName,
                        onValueChange = { goalName = it },
                        label = { Text("目标名称") },
                        placeholder = { Text("比如：减脂、备半马、把作息掰回来") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onNewGoal(goalName)
                        goalName = ""
                        showGoalDialog = false
                    },
                    enabled = goalName.isNotBlank(),
                ) { Text("开始") }
            },
            dismissButton = {
                TextButton(onClick = { showGoalDialog = false }) { Text("取消") }
            },
        )
    }
    pendingDeleteSessionId?.let { sessionId ->
        val title = summaries.firstOrNull { it.id == sessionId }?.title ?: "此对话"
        AlertDialog(
            onDismissRequest = { pendingDeleteSessionId = null },
            title = { Text("删除此对话？") },
            text = { Text("「$title」会被删掉，无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(sessionId)
                        pendingDeleteSessionId = null
                    },
                ) { Text("删除对话") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSessionId = null }) { Text("取消") }
            },
        )
    }
    pendingDeleteGoal?.let { goal ->
        AlertDialog(
            onDismissRequest = { pendingDeleteGoal = null },
            title = { Text("删除此目标？") },
            text = { Text("「${goal.title}」会被删掉，无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteGoal(goal)
                        pendingDeleteGoal = null
                    },
                ) { Text("删除目标") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteGoal = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SessionSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionListRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    pendingDelete: Boolean,
    onOpen: () -> Unit,
    onRequestDelete: () -> Unit,
    deleteLabel: String,
) {
    val requestDelete by rememberUpdatedState(onRequestDelete)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                requestDelete()
                true
            } else {
                false
            }
        },
        positionalThreshold = { distance -> distance * 0.35f },
    )

    // 取消确认后把行滑回来；确认删除则条目会从列表消失。
    LaunchedEffect(pendingDelete) {
        if (!pendingDelete && dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            dismissState.reset()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.error)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onError,
                )
            }
        },
        content = {
            ListItem(
                headlineContent = {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Text(
                        subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = if (selected) {
                    {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    null
                },
                colors = ListItemDefaults.colors(
                    containerColor = if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    headlineColor = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .semantics(mergeDescendants = true) {
                        this.selected = selected
                        contentDescription = deleteLabel
                    },
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
