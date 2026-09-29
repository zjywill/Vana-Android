package com.pinapia.vana.chat

import com.pinapia.vana.ui.icons.VanaIcons
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.session.TurnSegment
import com.pinapia.vana.session.compactionSummary
import com.pinapia.vana.session.foldedSpan
import com.pinapia.vana.tasks.TaskCard
import com.pinapia.vana.tasks.taskId
import com.pinapia.vana.tenant.TenantScope
import com.pinapia.vana.today.TodayAction
import com.pinapia.vana.today.TodayStrip
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SmallFloatingActionButton
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
import com.pinapia.vana.exercises.exerciseIDs
import com.pinapia.vana.plugins.HealthTopics
import com.pinapia.vana.plugins.PluginRegistry
import com.pinapia.vana.settings.CloudCatalog
import com.pinapia.vana.ui.L10n
import com.pinapia.vana.ui.uiText
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    exerciseLibrary: ExerciseLibrary,
    onOpenSettings: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenPlugins: () -> Unit,
    /** 进「不留痕」浮层。浮层自己(ephemeral)没有这个入口。 */
    onOpenEphemeral: () -> Unit = {},
    onOpenTasks: () -> Unit = {},
    onOpenTask: (String) -> Unit = {},
    /** 「今天」卡片上指向插件入口的那种(用药到期回访)。 */
    onOpenSurface: (String) -> Unit = {},
    /** 只有浮层需要:退出并丢掉这一页里的全部内容。 */
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val historyLoaded by viewModel.isHistoryLoaded.collectAsStateWithLifecycle()
    val hasOlderHistory by viewModel.hasOlderHistory.collectAsStateWithLifecycle()
    val focusMedication by viewModel.focusMedication.collectAsStateWithLifecycle()
    val todayCards by viewModel.todayCards.collectAsStateWithLifecycle()
    val attentionCount by viewModel.attentionCount.collectAsStateWithLifecycle()
    var todayExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteAssistantId by remember { mutableStateOf<String?>(null) }
    val input by viewModel.input.collectAsStateWithLifecycle()
    val isReplying by viewModel.isReplying.collectAsStateWithLifecycle()
    val engineGuidance by viewModel.engineGuidance.collectAsStateWithLifecycle()
    val retryNotice by viewModel.retryNotice.collectAsStateWithLifecycle()
    val followUps by viewModel.followUps.collectAsStateWithLifecycle()
    val drafts by viewModel.draftAttachments.collectAsStateWithLifecycle()
    var showOverflowMenu by remember { mutableStateOf(false) }
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

    // 第一次要把数据发给这家 provider:点名征一次同意(iOS 2026-08-29 被 5.1.2(i) 判的
    // 那条,两边同一套修法)。「同意并发送」把刚才那句原样发出去;「取消」字留在输入框里。
    val pendingProviderConsent by viewModel.pendingProviderConsent.collectAsStateWithLifecycle()
    pendingProviderConsent?.let { providerId ->
        val providerName = CloudCatalog.providerName(providerId)
        AlertDialog(
            onDismissRequest = viewModel::declineProviderConsent,
            title = { Text(uiText("发送给 $providerName？", "Send to $providerName?")) },
            text = {
                Text(
                    uiText(
                        "你的问题，连同它需要用到的内容（这条对话的往来、长期记忆、你记录的用药和测量、识别出的文字），" +
                            "会发送给第三方模型服务 $providerName 来生成回答，由对方按它自己的隐私政策处理。" +
                            "这台设备上发给这家服务的请求只问这一次；换用其他服务时会再次询问。",
                        "Your question, along with what it needs (this conversation, long-term memory, the medications " +
                            "and measurements you recorded, and recognized text), will be sent to the third-party model " +
                            "service $providerName to generate the answer, handled under its own privacy policy. " +
                            "On this device you will only be asked once for this service; switching to another " +
                            "service will ask again.",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmProviderConsent) {
                    Text(uiText("同意并发送", "Agree and Send"))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::declineProviderConsent) {
                    Text(uiText("取消", "Cancel"))
                }
            },
        )
    }

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
            cameraNotice = L10n.text(
                "没有相机权限，没法拍照。可以到系统设置里打开，或从相册选取。",
                "Camera permission is off. Enable it in system settings or choose a photo from the library.",
            )
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
    }

    // ChatViewModel 在返回栈上会活着，设置里填完密钥后必须重新读，不能沿用进设置前的 hint。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshEngineAvailability()
    }

    // 新消息 / 换会话：回到底部并重新贴底。流式长高用 layout overflow 跟，
    // 不要对每个 token animateScroll，否则动画互相取消，看起来像整段蹦出。
    // 只在「最后一条」换了的时候回到底部(新消息、后台追加的消息):往前翻出更早的历史会让条数变多,
    // 那不该把用户拽回底部。打开 app 读完历史那一下直接落在末尾,不要从头一路动画滚过整条线程。
    var scrolledToEndOnce by remember { mutableStateOf(false) }
    LaunchedEffect(historyLoaded, session.messages.lastOrNull()?.id) {
        if (!historyLoaded) return@LaunchedEffect
        followOutput.value = true
        if (session.messages.isNotEmpty()) {
            if (scrolledToEndOnce) {
                listState.animateScrollToItem(session.messages.lastIndex)
            } else {
                listState.scrollToItem(session.messages.lastIndex)
            }
        }
        scrolledToEndOnce = true
    }
    // 滑到顶就再往前读一页。LazyColumn 按 key 记着第一条可见项,往前塞进内容不会把用户正看的那条顶走。
    LaunchedEffect(listState, hasOlderHistory) {
        if (!hasOlderHistory) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            if (index <= 3) viewModel.loadOlder()
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

    Box(modifier = modifier) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Vana")
                            val subtitle = buildList {
                                if (!TenantScope.current.isOwner) add(TenantScope.current.displayName)
                                if (session.isPrivate) add(uiText("不留痕 · 关掉就没", "Off the record · gone when closed"))
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
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(VanaIcons.ArrowLeft, contentDescription = uiText("关闭", "Close"))
                            }
                        }
                    },
                    actions = {
                      if (!viewModel.ephemeral) {
                        IconButton(onClick = onOpenTasks) {
                            BadgedBox(
                                badge = { if (attentionCount > 0) Badge { Text(attentionCount.toString()) } },
                            ) {
                                Icon(VanaIcons.CheckCircle, contentDescription = uiText("任务", "Tasks"))
                            }
                        }
                        // 顶栏不再替某一个插件占位(以前是心形=测量、烧瓶=用药):
                        // 插件的入口都在「插件」页里,这里只留通用的几样。
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(VanaIcons.EllipsisVertical, contentDescription = uiText("更多", "More"))
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(uiText("Vana 记住的事", "What Vana remembers")) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenMemory()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(uiText("插件", "Plugins")) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenPlugins()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(uiText("不留痕聊天", "Off-the-record chat")) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenEphemeral()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(uiText("设置", "Settings")) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenSettings()
                                    },
                                )
                            }
                        }
                      }
                    },
                )
            },
        ) { insets ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets)
                    .consumeWindowInsets(insets)
                    .imePadding(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (!viewModel.ephemeral) {
                        TodayStrip(
                            cards = todayCards,
                            expanded = todayExpanded,
                            onToggle = { todayExpanded = !todayExpanded },
                            onAction = { action ->
                                when (action) {
                                    TodayAction.OpenTasks -> onOpenTasks()
                                    TodayAction.OpenMemory -> onOpenMemory()
                                    is TodayAction.OpenTask -> onOpenTask(action.id)
                                    is TodayAction.OpenSurface -> onOpenSurface(action.surfaceId)
                                    is TodayAction.Ask -> {
                                        todayExpanded = false
                                        viewModel.send(action.prompt)
                                    }
                                }
                            },
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .nestedScroll(followScroll),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (session.isEmpty && historyLoaded) {
                                item {
                                    WelcomeCard(
                                        isOwner = TenantScope.current.isOwner,
                                        isPrivate = session.isPrivate,
                                        ownerBody = viewModel.welcomeBody,
                                        suggestions = viewModel.suggestedQuestions,
                                        onSuggestion = viewModel::send,
                                        setupGuidance = engineGuidance,
                                        onOpenSettings = onOpenSettings,
                                    )
                                }
                            }
                            itemsIndexed(session.messages, key = { _, message -> message.id }) { index, message ->
                              Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (index == 0 || !sameDay(session.messages[index - 1], message)) {
                                    DayDivider(message)
                                }
                                MessageBubble(
                                    message = message,
                                    precedingUserText = session.messages.getOrNull(index - 1)
                                        ?.takeIf { it.role == ChatMessage.Role.USER }?.text,
                                    isLiveReply = isReplying && message.id == lastAssistantId,
                                    isAskLive = !isReplying && message.id == lastAssistantId,
                                    isReplying = isReplying,
                                    exerciseLibrary = exerciseLibrary,
                                    recovery = viewModel.errorRecovery(message.id),
                                    onRetry = { viewModel.retry(message.id) },
                                    onOpenSettings = onOpenSettings,
                                    onDelete = { pendingDeleteAssistantId = message.id },
                                    onAnswerAsk = { callId, answer ->
                                        viewModel.answerAsk(message.id, callId, answer)
                                    },
                                    onOpenTask = onOpenTask,
                                )
                              }
                            }
                        }

                        if (!session.isEmpty && !followOutput.value) {
                            SmallFloatingActionButton(
                                onClick = {
                                    scope.launch {
                                        if (listState.animateToConversationBottom()) {
                                            followOutput.value = true
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 12.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ) {
                                Icon(
                                    VanaIcons.ChevronDown,
                                    contentDescription = uiText("回到最新消息", "Jump to latest message"),
                                )
                            }
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

                focusMedication?.let { medication ->
                    FocusStrip(
                        name = medication.name,
                        questions = medication.openingQuestions,
                        enabled = !isReplying,
                        onQuestion = viewModel::send,
                        onClear = viewModel::clearFocus,
                    )
                }

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
    }

    pendingDeleteAssistantId?.let { assistantId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteAssistantId = null },
            title = { Text(uiText("删除这一问一答？", "Delete this exchange?")) },
            text = {
                Text(
                    uiText(
                        "这条回答和它对应的那句提问会从这条对话里删掉，连同只用在它们里面的照片，无法撤销。",
                        "This answer and the question it replies to will be removed from the conversation, along with photos used only by them. This cannot be undone.",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteExchange(assistantId)
                    pendingDeleteAssistantId = null
                }) { Text(uiText("删除", "Delete")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteAssistantId = null }) { Text(uiText("取消", "Cancel")) }
            },
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
                    canSaveMedication = viewModel.medicationsEnabled,
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
            TextButton(onClick = onDecline) { Text(uiText("撤销", "Undo")) }
        } else {
            TextButton(onClick = onAccept) { Text(uiText("好", "Allow")) }
        }
    }
}

private fun imageSendTitle(candidates: List<DraftAttachment>, sending: Int): String {
    if (sending > 0) {
        return if (sending == 1) {
            L10n.text("原图会随这句话发出去", "The original photo will be sent with this message")
        } else {
            L10n.text("$sending 张原图会随这句话发出去", "$sending original photos will be sent with this message")
        }
    }
    val allBlank = candidates.all { !it.hasText }
    return when {
        candidates.size == 1 && allBlank -> L10n.text("这张图没有文字，让 Vana 直接看图？", "No text was found. Let Vana view the photo?")
        candidates.size == 1 -> L10n.text("让 Vana 直接看这张图？", "Let Vana view this photo?")
        allBlank -> L10n.text("有 ${candidates.size} 张没有文字，让 Vana 直接看图？", "No text was found in ${candidates.size} photos. Let Vana view them?")
        else -> L10n.text("让 Vana 直接看这 ${candidates.size} 张图？", "Let Vana view these ${candidates.size} photos?")
    }
}

private fun draftCaption(draft: DraftAttachment): String = when {
    draft.isLoading -> L10n.text("载入中…", "Loading…")
    draft.isRecognizing -> L10n.text("识别中…", "Recognizing…")
    draft.failure != null -> L10n.text("读不出来", "Could not read")
    !draft.hasText -> if (draft.isDocument) L10n.text("没有正文", "No text") else L10n.text("没有文字", "No text")
    else -> {
        val lines = draft.text.split('\n').count { it.isNotBlank() }.coerceAtLeast(1)
        if (draft.droppedLines > 0) {
            L10n.text("$lines 行·已截断", "$lines lines · truncated")
        } else {
            L10n.text("$lines 行", "$lines lines")
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
                                    contentDescription = draft.documentName ?: uiText("附件预览", "Attachment preview"),
                                    modifier = Modifier
                                        .size(68.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                            draft.isDocument -> {
                                Icon(
                                    VanaIcons.DocumentText,
                                    contentDescription = draft.documentName ?: uiText("文件附件", "File attachment"),
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
                                VanaIcons.Eye,
                                contentDescription = uiText("原图会一起发出去", "Original photo will be sent"),
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
                        VanaIcons.XMark,
                        contentDescription = uiText("不发这张", "Remove this attachment"),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** 「问问这个药」带进来的一次性上下文:露出名字和开场问题,回复完自己撤,也可以手动撤。 */
@Composable
private fun FocusStrip(
    name: String,
    questions: List<String>,
    enabled: Boolean,
    onQuestion: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                uiText("正在聊：$name", "Asking about: $name"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) { Text(uiText("不聊这个了", "Dismiss")) }
        }
        if (questions.isNotEmpty()) {
            FollowUpChips(chips = questions, enabled = enabled, onChip = onQuestion)
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
    /** 机主看到的那段话,由当前开着的插件拼出来(健康关掉就不提健康)。 */
    ownerBody: String,
    suggestions: List<String>,
    onSuggestion: (String) -> Unit,
    setupGuidance: String? = null,
    onOpenSettings: () -> Unit = {},
) {
    val title = if (isOwner) {
        uiText("你好，我是 Vana", "Hi, I'm Vana")
    } else {
        uiText(
            "从${TenantScope.current.displayName}的资料开始",
            "Start with ${TenantScope.current.displayName}'s information",
        )
    }
    val body = if (isOwner) {
        ownerBody
    } else {
        uiText(
            "拍一张${TenantScope.current.displayName}的化验单、报告或药盒，文字在本机识别后再帮你看；" +
                "也可以记录用药、测量和需要跟进的事。",
            "Photograph a report or medicine package for ${TenantScope.current.displayName}; " +
                "Vana recognizes the text on-device. You can also record medications, measurements and follow-ups.",
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
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
                        Text(uiText("去设置", "Open Settings"))
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (isPrivate) {
            Text(
                uiText(
                    "这里的对话只留在内存里：不写盘、不记进记忆，关掉这一页就没了。" +
                        "问题仍要发给你配置的模型才能回答。",
                    "This conversation lives only in memory: nothing is saved or added to memory, and it is gone when you close this page. " +
                        "Your question still has to be sent to the model you configure.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(uiText("试着问", "Try asking"), style = MaterialTheme.typography.titleSmall)
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

private suspend fun LazyListState.animateToConversationBottom(): Boolean {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return false

    animateScrollToItem(lastIndex)
    val overflow = bottomOverflowOrHidden() ?: return false
    if (overflow > 1) {
        animateScrollBy(overflow.toFloat())
    }
    return true
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    /** 这条回答对应的那句用户的话,判断它算不算健康话题用。 */
    precedingUserText: String?,
    isLiveReply: Boolean,
    isAskLive: Boolean,
    isReplying: Boolean,
    exerciseLibrary: ExerciseLibrary,
    recovery: ErrorRecovery?,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onDelete: () -> Unit,
    onAnswerAsk: (String, com.pinapia.vana.ask.AskUserAnswer) -> Unit,
    onOpenTask: (String) -> Unit = {},
) {
    val isUser = message.role == ChatMessage.Role.USER
    var openReasoningId by remember(message.id) { mutableStateOf<String?>(null) }
    var expandedToolId by remember(message.id) { mutableStateOf<String?>(null) }
    val segments = if (isUser) emptyList() else message.turnSegments
    val lastSegmentId = segments.lastOrNull()?.stableId
    // 动作卡 / 问题卡等这一轮写完再出。工具一返回卡上的数据就齐了,正文要等下一轮请求
    // 才吐出来——卡先出来、回答插在卡上面,就是这段窗口。判据是整轮结束,不是「正文开口」。
    val showsToolCards = !isLiveReply
    val showsTrailingIndicator = showsTrailingReplyIndicator(
        isLiveReply = isLiveReply,
        hasRunningToolCall = message.hasRunningToolCall,
    )
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
                if (message.isProactive) {
                    Text(
                        proactiveLabel(message.origin),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                segments.forEach { segment ->
                    key(segment.stableId) {
                        when (segment) {
                            is TurnSegment.Reasoning -> {
                                val isThinking = isLiveReply && segment.stableId == lastSegmentId
                                SuggestionChip(
                                    onClick = { openReasoningId = segment.stableId },
                                    label = {
                                        Text(
                                            if (isThinking) {
                                                uiText("正在思考…", "Thinking…")
                                            } else {
                                                uiText("思考过程", "Reasoning")
                                            },
                                        )
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
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (call.output == null) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(14.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                            }
                                            Text(
                                                PluginRegistry.toolLabel(call) +
                                                    if (call.isError) uiText("（失败）", " (failed)") else "",
                                            )
                                        }
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
                if (showsTrailingIndicator) {
                    ReplyTypingIndicator()
                }
            }
            message.foldedSpan?.let { count ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        uiText("以上 $count 条已折叠", "$count earlier messages collapsed"),
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
                    call.taskId?.let { TaskCard(taskId = it, onOpen = onOpenTask) }
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
                    text = uiText("Vana 还没看到", "Vana has not seen this yet"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!isUser && message.errorDescription != null && recovery != null) {
                TextButton(
                    onClick = if (recovery == ErrorRecovery.OPEN_SETTINGS) onOpenSettings else onRetry,
                ) {
                    Text(
                        if (recovery == ErrorRecovery.OPEN_SETTINGS) {
                            uiText("去设置", "Open Settings")
                        } else {
                            uiText("重试", "Retry")
                        },
                    )
                }
            }
            if (!isUser && !isLiveReply && !message.textIsPlaceholder && message.text.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 主动说的话不是对哪句提问的回答,「重新回答」没有可重来的那句。
                    if (!message.isProactive) {
                        TextButton(onClick = onRetry, enabled = !isReplying) {
                            Text(uiText("重新回答", "Answer again"))
                        }
                    }
                    message.refTaskId?.let { taskId ->
                        TextButton(onClick = { onOpenTask(taskId) }) {
                            Text(uiText("查看详情", "View details"))
                        }
                    }
                    TextButton(onClick = onDelete, enabled = !isReplying) {
                        Text(uiText("删除", "Delete"))
                    }
                }
                // 到点的提醒是本机写的一句话,不是模型生成的,不该挂「AI 生成」。
                if (message.origin != ChatMessage.Origin.REMINDER) Text(
                    // 「AI 生成」每条都有;医疗那半句只在话题沾上健康时才补(见 HealthTopics)。
                    text = HealthTopics.disclaimer(
                        healthRelated = HealthTopics.applies(
                            message.toolCalls.map { it.name },
                            precedingUserText,
                            message.text,
                        ),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun showsTrailingReplyIndicator(
    isLiveReply: Boolean,
    hasRunningToolCall: Boolean,
): Boolean = isLiveReply && !hasRunningToolCall

@Composable
private fun ReplyTypingIndicator() {
    val transition = rememberInfiniteTransition(label = "reply-typing")
    val replyDescription = uiText("正在回复", "Replying")
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .semantics {
                contentDescription = replyDescription
            },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 550,
                        delayMillis = index * 180,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "reply-dot-$index",
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(dotColor),
            )
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
                if (isThinking) uiText("正在思考", "Thinking") else uiText("思考过程", "Reasoning"),
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
    val fileAttachmentDescription = uiText(
        "文件附件，点按查看识别文字",
        "File attachment. Tap to review extracted text.",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        attachments.forEach { attachment ->
            val bytes = attachment.imagePayload?.let {
                android.util.Base64.decode(it, android.util.Base64.DEFAULT)
            }
            val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = uiText(
                        "附件预览，点按查看识别文字",
                        "Attachment preview. Tap to review recognized text.",
                    ),
                    modifier = Modifier
                        .size(76.dp)
                        .clickable { showText = !showText },
                )
            } else {
                Card(
                    modifier = Modifier
                        .size(76.dp)
                        .semantics {
                            contentDescription = fileAttachmentDescription
                        }
                        .clickable { showText = !showText },
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(VanaIcons.DocumentText, contentDescription = null)
                        Text(
                            attachment.documentName ?: uiText("文件", "File"),
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
                    attachments.any { it.sendsImage } -> uiText(
                        "这张图里没有识别到文字，原图发给了模型。",
                        "No text was recognized; the original photo was sent to the model.",
                    )
                    attachments.any { it.documentName != null } -> uiText(
                        "这份文件里没有取到文字。",
                        "No text could be extracted from this file.",
                    )
                    else -> uiText(
                        "这张图里没有识别到文字。",
                        "No text was recognized in this photo.",
                    )
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
                        VanaIcons.Plus,
                        contentDescription = uiText("添加照片或文件", "Add photo or file"),
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
                                    uiText("问问 Vana…", "Ask Vana…"),
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
                contentDescription = uiText("停止回答", "Stop response"),
                icon = VanaIcons.Stop,
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
                contentDescription = uiText("发送", "Send"),
                icon = VanaIcons.PaperAirplane,
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
                    uiText(
                        "照片在本机识别成文字，文件直接取文字；原图默认不发，发送之前每一张都能单独决定",
                        "Photos are recognized on-device and text is extracted from files. " +
                            "Original photos are not sent by default and can be reviewed individually.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (hasCamera) {
                    AttachSheetRow(
                        icon = VanaIcons.Camera,
                        title = uiText("拍照", "Take photo"),
                        subtitle = uiText("票据、说明书、报告", "Receipts, manuals or reports"),
                        onClick = {
                            showAttachSheet = false
                            onAddCamera()
                        },
                    )
                }
                AttachSheetRow(
                    icon = VanaIcons.Photo,
                    title = uiText("从相册选取", "Choose from photos"),
                    subtitle = uiText("已经拍过的那些", "Use an existing photo"),
                    onClick = {
                        showAttachSheet = false
                        onAddPhoto()
                    },
                )
                AttachSheetRow(
                    icon = VanaIcons.Document,
                    title = uiText("添加文件", "Add file"),
                    subtitle = uiText("PDF 或 Word", "PDF or Word"),
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

private fun sameDay(a: ChatMessage, b: ChatMessage): Boolean {
    val zone = java.time.ZoneId.systemDefault()
    fun day(m: ChatMessage) = java.time.Instant.ofEpochMilli(m.createdAt.toEpochMilliseconds()).atZone(zone).toLocalDate()
    return day(a) == day(b)
}

/** 一天的分隔:今天 / 昨天 / M月d日。一条永远的对话靠它让「哪天说的」看得见。 */
@Composable
private fun DayDivider(message: ChatMessage) {
    val zone = java.time.ZoneId.systemDefault()
    val date = java.time.Instant.ofEpochMilli(message.createdAt.toEpochMilliseconds()).atZone(zone).toLocalDate()
    val today = java.time.LocalDate.now(zone)
    val label = when (date) {
        today -> uiText("今天", "Today")
        today.minusDays(1) -> uiText("昨天", "Yesterday")
        else -> if (date.year == today.year) {
            uiText("${date.monthValue}月${date.dayOfMonth}日", "${date.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)} ${date.dayOfMonth}")
        } else {
            uiText("${date.year}年${date.monthValue}月${date.dayOfMonth}日", date.toString())
        }
    }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

private fun proactiveLabel(origin: ChatMessage.Origin): String = when (origin) {
    ChatMessage.Origin.CHECK_IN -> L10n.text("Vana 主动问 · check-in", "Vana asked · check-in")
    ChatMessage.Origin.FOLLOW_UP -> L10n.text("Vana 主动说 · 回头看了一眼", "Vana followed up")
    ChatMessage.Origin.REMINDER -> L10n.text("Vana 提醒", "Vana reminder")
    ChatMessage.Origin.TASK -> L10n.text("Vana 主动说 · 任务结果", "Vana reported · task result")
    ChatMessage.Origin.NORMAL -> ""
}
