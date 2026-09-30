package com.pinapia.vana.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pinapia.vana.agent.AgentError
import com.pinapia.vana.agent.CloudEngine
import com.pinapia.vana.agent.FollowUpSuggestionHook
import com.pinapia.vana.agent.OpenAICompatibleModelClient
import com.pinapia.vana.agent.UserFacingModelFailure
import com.pinapia.vana.agentruntime.AgentHookDispatcher
import com.pinapia.vana.agentruntime.AgentPendingInput
import com.pinapia.vana.agentruntime.AgentTurnEvent
import com.pinapia.vana.agentruntime.WindowPolicy
import com.pinapia.vana.agentruntime.apply
import com.pinapia.vana.ask.AskUserAnswer
import com.pinapia.vana.exercises.ExerciseLibrary
import com.pinapia.vana.location.LocationProvider
import com.pinapia.vana.location.LocationSnapshot
import com.pinapia.vana.medications.MedicationItem
import com.pinapia.vana.medications.MedicationSnapshot
import com.pinapia.vana.measurements.MeasurementSnapshot
import com.pinapia.vana.memory.MemoryHarvester
import com.pinapia.vana.memory.MemorySnapshot
import com.pinapia.vana.plugins.OtherThreadsScope
import com.pinapia.vana.plugins.PluginEnvironment
import com.pinapia.vana.recall.HistoryRecallTools
import com.pinapia.vana.recall.SideChatRecall
import com.pinapia.vana.plugins.PluginIds
import com.pinapia.vana.plugins.PluginRegistry
import com.pinapia.vana.plugins.PluginRoute
import com.pinapia.vana.plugins.SuggestionContext
import com.pinapia.vana.search.WebFetchClient
import com.pinapia.vana.search.WebSearchClient
import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.session.ChatSession
import com.pinapia.vana.settings.CloudCatalog
import com.pinapia.vana.settings.EngineSettings
import com.pinapia.vana.settings.SecureKeyStore
import com.pinapia.vana.tasks.TasksEnvironment
import com.pinapia.vana.tenant.Tenant
import com.pinapia.vana.tenant.TenantScope
import com.pinapia.vana.thread.ConversationHistory
import com.pinapia.vana.thread.SideChat
import com.pinapia.vana.thread.SideChatQuote
import com.pinapia.vana.thread.SideChatStore
import com.pinapia.vana.thread.SideChatTitle
import com.pinapia.vana.thread.ThreadWindow
import com.pinapia.vana.today.TodayCard
import com.pinapia.vana.today.TodayFeed
import com.pinapia.vana.thread.ThreadWriter
import com.pinapia.vana.ui.L10n
import com.pinapia.vana.medications.MedicationBriefer
import com.pinapia.vana.vision.AttachmentImage
import com.pinapia.vana.vision.ChatAttachment
import com.pinapia.vana.vision.DraftAttachment
import com.pinapia.vana.vision.PhotoImagePolicy
import com.pinapia.vana.vision.TextRecognizer
import com.pinapia.vana.vision.toBase64
import android.graphics.Bitmap
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import android.view.Choreographer

/**
 * 那条永远的对话。
 *
 * 没有「会话」这个用户能管理的东西了:打开就接着上次,一条时间线。内存里持有的是线程**末尾**的一段
 * ([session].messages,向上滑再往前翻),盘上是追加式的 [com.pinapia.vana.thread.ThreadStore]。
 *
 * 三件事在这里接线,都是「每一轮请求带多少历史」这个问题的一部分:
 * - **窗口**([ThreadWindow]):请求里只带窗口内的原文。窗口只在涨到高水位时一次砍到低水位,
 *   两次淘汰之间请求前缀是纯追加,prompt 缓存稳定。窗口之外的历史不丢——记忆管长期事实,档案按需检索。
 * - **持久化**:只写变了的,排在一个单消费者队列里,不会和后台写线程的主动消息抢。
 * - **收割**:记忆抽取按水位线做,和窗口解耦。
 *
 * [ephemeral] 是「不留痕」浮层:内存里聊,不读盘不写盘、不抽记忆、不启动任务,关了就没。
 *
 * 给了 `sideChat` 就是一条侧聊:同一个类型接另一条线程([SideChatStore.writer]),插话、排队、窗口、重试、
 * hook、同意闸全部原样。主对话专属的那几样(「今天」、首屏、check-in、快捷方式)只在 [isMainThread] 上有。
 */
class ChatViewModel(
    /** 这位成员的主对话。侧聊的线程不从这里来,由 [sides] 给。 */
    private val threadWriter: ThreadWriter,
    private val engineSettings: EngineSettings,
    private val secureKeyStore: SecureKeyStore,
    private val locationProvider: LocationProvider,
    private val exerciseLibrary: ExerciseLibrary,
    private val memorySnapshotProvider: () -> MemorySnapshot,
    private val medicationSnapshotProvider: () -> MedicationSnapshot,
    private val measurementSnapshotProvider: () -> MeasurementSnapshot = { MeasurementSnapshot.empty },
    private val tenantProvider: () -> Tenant = { TenantScope.current },
    /** 提醒、目标的存储和闹钟。浮层里没有。 */
    private val tasksEnvironment: TasksEnvironment? = null,
    val ephemeral: Boolean = false,
    /** 给了就是那条侧聊。 */
    sideChat: SideChat? = null,
    /** 这位成员的侧聊名单。不给就用主对话旁边那份([SideChatStore.beside]),和线程永远是同一位成员的。 */
    sides: SideChatStore? = null,
) : ViewModel() {
    /** 这位成员的侧聊名单。主对话拿它做两件事:收割时连侧聊一起收;「⋯ › 侧聊」那一页。 */
    val sides: SideChatStore = sides ?: SideChatStore.beside(threadWriter.store)

    private val _sideChat = MutableStateFlow(sideChat?.takeIf { !ephemeral })

    /** 这是哪条侧聊。名字会被他改、会拿第一句话起,所以是个流。主对话和不留痕都是 null。 */
    val sideChat: StateFlow<SideChat?> = _sideChat.asStateFlow()
    val isSideChat: Boolean = _sideChat.value != null

    /** 那条永远的对话本身。「今天」、首屏、check-in、快捷方式都只在这里。 */
    val isMainThread: Boolean get() = !ephemeral && !isSideChat

    /** 这一份对话实际读写的线程:主对话,或者这条侧聊自己的那一条(同一条永远是同一个写者)。 */
    private val writer: ThreadWriter = _sideChat.value?.let { this.sides.writer(it.id) } ?: threadWriter
    private val threadStore get() = writer.store

    private val _session = MutableStateFlow(ChatSession(id = THREAD_SESSION_ID, isPrivate = ephemeral))
    val session: StateFlow<ChatSession> = _session.asStateFlow()

    /** 盘上的历史读完了没。读完之前界面不该摆欢迎卡——不然每次打开都先闪一下「你好，我是 Vana」。 */
    private val _historyLoaded = MutableStateFlow(ephemeral)
    val isHistoryLoaded: StateFlow<Boolean> = _historyLoaded.asStateFlow()

    /** 「今天」头上的卡片。本机数据拼的,只在主对话里出(浮层、侧聊都没有)。 */
    private val todayFeed: TodayFeed? = tasksEnvironment?.takeIf { isMainThread }?.let { env ->
        TodayFeed(
            scope = viewModelScope,
            loadTasks = { env.store.all() },
            loadMemory = memorySnapshotProvider,
            loadMedications = medicationSnapshotProvider,
            isEnabled = engineSettings::isPluginEnabled,
        )
    }
    val todayCards: StateFlow<List<TodayCard>> = todayFeed?.cards ?: MutableStateFlow(emptyList())

    /**
     * 「今天」那张卡排在哪条消息下面。**每次打开 app 时定一次**([pinTodayToLatest]):那一刻它是最新的一条;
     * 之后说的话排在它下面,它不跟着往下挪。null 表示打开时线程是空的——排在最前面。
     * 它只是屏幕上的一张卡,不进线程、不进上下文。
     */
    private val _todayAfterId = MutableStateFlow<String?>(null)
    val todayAfterId: StateFlow<String?> = _todayAfterId.asStateFlow()

    /** 打开 app(读完线程、或者回到前台)时调一次。排队中的不算——那几条 Vana 还没看到。 */
    fun pinTodayToLatest() {
        if (!isMainThread) return
        _todayAfterId.value = _session.value.messages.lastOrNull { !it.isQueued }?.id
    }

    /** 顶栏「任务」上的角标:需要他看一眼的有几件。 */
    val attentionCount: StateFlow<Int> = todayFeed?.attention ?: MutableStateFlow(0)

    private val _hasOlder = MutableStateFlow(false)
    val hasOlderHistory: StateFlow<Boolean> = _hasOlder.asStateFlow()

    /** 往前翻出来了几条:界面据此把滚动位置补上,不然新塞进顶部的内容会把用户正看的那条顶下去。 */
    private val _prepended = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val prepended: SharedFlow<Int> = _prepended

    private var oldestSegment = Int.MAX_VALUE
    private var loadingOlder = false

    /** 界面这一份已经同步给盘的 id。只删这里面有、列表里没了的,后台追加、界面还没读到的不会被误删。 */
    @Volatile
    private var syncedIds: Set<String> = emptySet()
    private val dirtyIds = HashSet<String>()
    private val persistSignal = Channel<Unit>(Channel.CONFLATED)

    /** 窗口第一条消息的 id。窗口只在淘汰时前移。 */
    private var windowStartId: String? = null

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _isReplying = MutableStateFlow(false)
    val isReplying: StateFlow<Boolean> = _isReplying.asStateFlow()

    private val _engineGuidance = MutableStateFlow<String?>(null)
    val engineGuidance: StateFlow<String?> = _engineGuidance.asStateFlow()

    private val _cloudSetupRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val cloudSetupRequests: SharedFlow<Unit> = _cloudSetupRequests

    /**
     * 他按了发送,但还没同意过把数据发给当前这家 provider。存 provider id,界面拿它弹
     * 点名确认的 dialog。同意或取消都当场清掉;打的字留在输入框里,同意之后再发。
     */
    private val _pendingProviderConsent = MutableStateFlow<String?>(null)
    val pendingProviderConsent: StateFlow<String?> = _pendingProviderConsent.asStateFlow()

    private val _retryNotice = MutableStateFlow<String?>(null)
    val retryNotice: StateFlow<String?> = _retryNotice.asStateFlow()

    private val _followUps = MutableStateFlow<List<String>>(emptyList())
    val followUps: StateFlow<List<String>> = _followUps.asStateFlow()

    private val _draftAttachments = MutableStateFlow<List<DraftAttachment>>(emptyList())
    val draftAttachments: StateFlow<List<DraftAttachment>> = _draftAttachments.asStateFlow()

    /** 「问问这个药」带进来的一次性上下文:下一轮回复里带着它,回复完就撤。 */
    private val _focusMedication = MutableStateFlow<MedicationItem?>(null)
    val focusMedication: StateFlow<MedicationItem?> = _focusMedication.asStateFlow()

    private var replyJob: Job? = null
    private var replyingMessageId: String? = null
    private var followUpHooks: AgentHookDispatcher? = null
    private var idleHarvestJob: Job? = null

    /** 已经离开过这条侧聊了。返回键、删除、被快捷方式顶掉、被回收,几条路都会走到 [leaveSideChat]。 */
    private var didLeaveSideChat = false

    private val harvester = MemoryHarvester(
        writers = { if (isMainThread) listOf(writer) + this.sides.allWriters() else listOf(writer) },
        memory = TenantScope.currentStores.memory,
        settings = engineSettings,
        secureKeyStore = secureKeyStore,
        environment = ::pluginEnvironment,
    )

    val suggestedQuestions: List<String>
        get() = PluginRegistry.suggestions(
            SuggestionContext(
                isEnabled = engineSettings::isPluginEnabled,
                tenant = tenantProvider(),
                focusMedication = _focusMedication.value,
                medications = medicationSnapshotProvider,
            ),
        )

    val medicationsEnabled: Boolean get() = engineSettings.isPluginEnabled(PluginIds.HEALTH_MEDICATIONS)

    /** 欢迎语里「我能帮你……」那一段:哪些插件开着就提哪些。 */
    val welcomeBody: String
        get() = PluginRegistry.welcomeBody(engineSettings::isPluginEnabled)

    val supportsVision: Boolean get() = engineSettings.modelSupportsVision()

    val photoImagePolicy: PhotoImagePolicy get() = engineSettings.photoImagePolicy

    val canAttachMore: Boolean
        get() = _draftAttachments.value.size < ChatAttachment.MAX_ATTACHMENTS

    val isRecognizingAttachments: Boolean
        get() = _draftAttachments.value.any { it.isLoading || it.isRecognizing }

    /**
     * 他设过一档会发原图的默认，可这个模型看不了图——那一档在这条会话里静静地不生效。
     * 只在真的对不上时才有这句话。
     */
    val visionUnavailableNote: String?
        get() {
            if (supportsVision) return null
            val policy = photoImagePolicy
            if (policy == PhotoImagePolicy.TEXT_ONLY) return null
            return L10n.text(
                "你设的是「${policy.label}」，但当前模型看不了图——这一档暂时不生效。",
                "You selected \"${policy.label}\", but the current model cannot view images, so this setting is temporarily inactive.",
            )
        }

    /**
     * 输入框上方那一行要说哪几张。
     * 模型看不了图时一句话都不说——那等于摆一个按不动的按钮。
     */
    val imageSendCandidates: List<DraftAttachment>
        get() {
            if (!supportsVision) return emptyList()
            val policy = photoImagePolicy
            return _draftAttachments.value.filter { it.suggestsImage(under = policy) }
        }

    init {
        refreshEngineAvailability()
        viewModelScope.launch {
            for (signal in persistSignal) persistNow()
        }
        if (!ephemeral) {
            todayFeed?.start(
                kotlinx.coroutines.flow.merge(
                    tasksEnvironment!!.store.revision,
                    writer.revision,
                ),
            )
            loadInitialHistory()
            viewModelScope.launch {
                // 后台来的主动消息(check-in、提醒、任务结果):等这一轮回复结束再并进列表。
                writer.revision.collect { if (it > 0) mergeBackgroundMessages() }
            }
        }
        viewModelScope.launch {
            if (locationProvider.isAuthorized) {
                locationProvider.refresh()
            }
        }
    }

    // ------------------------------------------------------------------ 线程:读、持久化、往前翻

    private fun loadInitialHistory() {
        viewModelScope.launch {
            val (page, windowPos) = withContext(Dispatchers.IO) {
                writer.write { store -> store.loadTail() to store.meta().windowStartPos }
            }
            var messages = page.messages
            var oldest = page.oldestSegment
            var hasOlder = page.hasOlder
            // 窗口起点在更早的段里:往前读到能盖住它为止,不然「窗口」比读进来的还长。
            if (windowPos != null) {
                while (hasOlder && messages.isNotEmpty() &&
                    (threadStore.positionOf(messages.first().id) ?: Double.MAX_VALUE) > windowPos
                ) {
                    val older = withContext(Dispatchers.IO) { writer.write { it.loadOlder(oldest) } }
                    messages = older.messages + messages
                    oldest = older.oldestSegment
                    hasOlder = older.hasOlder
                }
            }
            windowStartId = windowPos
                ?.let { pos -> messages.firstOrNull { (threadStore.positionOf(it.id) ?: -1.0) >= pos }?.id }
                ?: messages.firstOrNull()?.id
            oldestSegment = oldest
            _hasOlder.value = hasOlder
            syncedIds = messages.mapTo(HashSet()) { it.id }
            val loaded = loadImagePayloads(_session.value.copy(messages = messages))
            // 读盘期间他要是已经发了话(极少),别把它盖掉。
            _session.update { current -> loaded.copy(messages = loaded.messages + current.messages) }
            pinTodayToLatest()
            _historyLoaded.value = true
        }
    }

    /** 滑到顶了,再往前读一页。 */
    fun loadOlder() {
        if (ephemeral || loadingOlder || !_hasOlder.value) return
        loadingOlder = true
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { writer.write { it.loadOlder(oldestSegment) } }
                oldestSegment = page.oldestSegment
                _hasOlder.value = page.hasOlder
                if (page.messages.isNotEmpty()) {
                    syncedIds = syncedIds + page.messages.map { it.id }
                    updateSession { copy(messages = page.messages + messages) }
                    _prepended.tryEmit(page.messages.size)
                }
            } finally {
                loadingOlder = false
            }
        }
    }

    /** 后台追加的新消息并进来。回答还在写的时候不动——等这一轮结束。 */
    private fun mergeBackgroundMessages() {
        if (ephemeral || _isReplying.value) return
        viewModelScope.launch {
            val lastPos = _session.value.messages.lastOrNull()?.let { threadStore.positionOf(it.id) }
            val fresh = withContext(Dispatchers.IO) {
                writer.write { it.messagesAfter(lastPos) }
            }.map { it.second }.filter { it.id !in syncedIds }
            if (fresh.isEmpty()) return@launch
            syncedIds = syncedIds + fresh.map { it.id }
            updateSession { copy(messages = messages + fresh) }
        }
    }

    /** 请求落盘。多次请求合并成一次,由一个消费者按顺序做,顺序不会乱。 */
    private fun persist() {
        if (ephemeral) return
        persistSignal.trySend(Unit)
    }

    private suspend fun persistNow() {
        // 这条侧聊已经被删了(从「设置 › 对话历史」清空全部时,它可能还在返回栈上):一个字都别写回去,
        // 不然删掉的内容会作为一个孤儿目录又落回盘上。
        _sideChat.value?.let { chat -> if (sides.get(chat.id) == null) return }
        // 还在写的助手消息如果还是个空壳,先不落盘——崩了留下一个空气泡比什么都没有更糟。
        val inFlightId = replyingMessageId.takeIf { _isReplying.value }
        val messages = _session.value.messages.filterNot { message ->
            message.id == inFlightId && !message.hasVisibleTurnContent && message.id !in syncedIds
        }
        val dirty = synchronized(dirtyIds) { dirtyIds.toSet().also { dirtyIds.clear() } }
        val known = syncedIds
        syncedIds = withContext(Dispatchers.IO) { writer.write { it.sync(messages, dirty, known) } }
    }

    /** 删掉这一条(连同它引用的、别处没在用的照片)。 */
    fun deleteMessage(id: String) {
        if (_isReplying.value) return
        updateSession { copy(messages = messages.filterNot { it.id == id }) }
        if (windowStartId == id) windowStartId = _session.value.messages.firstOrNull()?.id
        persist()
    }

    /** 删掉一条回答和它对应的那句提问。 */
    fun deleteExchange(assistantId: String) {
        if (_isReplying.value) return
        val messages = _session.value.messages
        val index = messages.indexOfFirst { it.id == assistantId }
        if (index < 0) return
        val userIndex = (index - 1 downTo 0).firstOrNull { messages[it].role == ChatMessage.Role.USER }
        val doomed = setOfNotNull(assistantId, userIndex?.let { messages[it].id })
        updateSession { copy(messages = this.messages.filterNot { it.id in doomed }) }
        if (windowStartId in doomed) windowStartId = _session.value.messages.firstOrNull()?.id
        persist()
    }

    /** 清空整条对话。主对话这一份连侧聊一起清——「清空全部对话」里的「全部」就是这个意思。 */
    fun clearHistory() {
        if (_isReplying.value) return
        stopReply()
        viewModelScope.launch {
            if (isMainThread) {
                ConversationHistory(writer, sides).clearAll()
            } else {
                withContext(Dispatchers.IO) { writer.write { it.deleteAll() } }
            }
            _session.value = ChatSession(id = THREAD_SESSION_ID, isPrivate = ephemeral)
            syncedIds = emptySet()
            dirtyIds.clear()
            windowStartId = null
            oldestSegment = Int.MAX_VALUE
            _hasOlder.value = false
            _focusMedication.value = null
            resetFollowUps()
            _draftAttachments.value = emptyList()
        }
    }

    fun setInput(value: String) {
        _input.value = value
    }

    /**
     * 点开早晚 check-in 的通知:Vana 开个场。以前只是把那句话预填进输入框——现在它是线程里一条
     * Vana 主动说的话(不调模型),模型也知道自己问过。之后他回一句,就是对这个开场的回答。
     */
    fun applyCheckIn(question: String?) {
        val text = question?.trim().orEmpty()
        // 主对话永远是家:check-in 只落在主对话里。
        if (text.isEmpty() || !isMainThread) return
        val opener = ChatMessage(role = ChatMessage.Role.ASSISTANT, text = text, origin = ChatMessage.Origin.CHECK_IN)
        updateSession { copy(messages = messages + opener) }
        persist()
    }

    /**
     * App Shortcut「问 Vana」:把问题追加进这条对话并自动发送。
     * 回答正在写的时候,`send` 会把它当成插话排队,**不会**像以前那样砍掉进行中的回复。
     */
    fun applyAskAndSend(question: String?) {
        val trimmed = question?.trim().orEmpty()
        if (trimmed.isEmpty() || !isMainThread) return
        send(trimmed)
    }

    fun refreshEngineAvailability() {
        _engineGuidance.value = currentSetupGuidance()
    }

    private fun currentSetupGuidance(): String? {
        val key = com.pinapia.vana.settings.ApiKeyNormalizer.normalize(secureKeyStore.apiKey)
        if (!key.isValid) {
            return L10n.text(
                "还没配置云端模型。请前往设置填写 API 密钥，并选择服务商和模型。",
                "The cloud model is not configured. Open Settings, enter an API key, and choose a provider and model.",
            )
        }
        if (engineSettings.providerId.isBlank() || engineSettings.model.isBlank()) {
            return L10n.text(
                "还没选好云端模型。请前往设置选择服务商和模型。",
                "No cloud model is selected. Open Settings and choose a provider and model.",
            )
        }
        return null
    }

    fun errorRecovery(assistantId: String): ErrorRecovery? {
        val message = _session.value.messages.firstOrNull { it.id == assistantId } ?: return null
        val failure = message.errorDescription ?: return null
        if (currentSetupGuidance() != null) return ErrorRecovery.OPEN_SETTINGS
        return if (UserFacingModelFailure.isAuthenticationMessage(failure)) {
            ErrorRecovery.OPEN_SETTINGS
        } else {
            ErrorRecovery.RETRY
        }
    }

    fun send(text: String? = null) {
        val trimmed = (text ?: _input.value).trim()
        val drafts = _draftAttachments.value
        if (drafts.any { it.isLoading || it.isRecognizing }) return
        val ready = drafts.filter { it.failure == null }
        if (trimmed.isEmpty() && ready.isEmpty()) {
            if (!_isReplying.value && hasQueuedInput()) {
                startReply()
            }
            return
        }
        refreshEngineAvailability()
        if (_engineGuidance.value != null) {
            if (text != null) {
                _input.value = trimmed
            }
            _cloudSetupRequests.tryEmit(Unit)
            return
        }
        // 第一次要发给这家 provider:先点名征一次同意(iOS 2026-08-29 被 5.1.2(i) 判的
        // 正是「发送之前没问过、也没点过名」)。字留在输入框里,他在 dialog 上按「同意并
        // 发送」会再回到这里,那时候这道闸已经开了。换 provider 会再问,同一家只问一次。
        val provider = engineSettings.providerId.ifBlank { EngineSettings.DEFAULT_PROVIDER }
        if (!engineSettings.hasProviderConsent(provider)) {
            if (text != null) {
                _input.value = trimmed
            }
            _pendingProviderConsent.value = provider
            return
        }
        _input.value = ""
        _followUps.value = emptyList()
        val persist = !ephemeral
        val store = TenantScope.currentStores.attachments
        val attachments = ready.map { draft ->
            draft.toChatAttachment(persist = persist, store = store)
        }
        _draftAttachments.value = emptyList()
        val user = ChatMessage(
            role = ChatMessage.Role.USER,
            text = trimmed,
            attachments = attachments,
            isQueued = true,
        )
        updateSession { copy(messages = messages + user) }
        persist()
        noteSideChatActivity(trimmed)
        if (!_isReplying.value) {
            startReply()
        }
    }

    // ------------------------------------------------------------------ 侧聊

    /**
     * 侧聊里说了一句话:名单按最近说过话排;还没起名的拿这句起名。
     *
     * 名字要在**这一轮请求发出去之前**定下来:侧聊说明块里带着它,先发一版没名字的、下一轮再换,等于白白
     * 打掉一次 prompt 缓存。所以这里当场改内存里那一份,盘上那份由名单自己按同一条规则改
     * ([SideChatStore.noteActivity]),在名单自己的作用域里做,他马上离开也不丢。
     */
    private fun noteSideChatActivity(text: String) {
        val chat = _sideChat.value ?: return
        val now = kotlinx.datetime.Clock.System.now()
        val title = if (chat.autoTitled) SideChatTitle.make(text) else null
        _sideChat.value = chat.copy(
            lastActiveAt = now,
            title = title ?: chat.title,
            autoTitled = chat.autoTitled && title == null,
        )
        sides.launch { sides.noteActivity(chat.id, text, now) }
    }

    /** 他在侧聊里改了名字。 */
    fun renameSideChat(title: String) {
        val chat = _sideChat.value ?: return
        _sideChat.value = chat.copy(title = SideChatTitle.clean(title), autoTitled = false)
        sides.launch { sides.rename(chat.id, title) }
    }

    /**
     * 放掉这条侧聊的 view model(宿主 [SideChatHost] 在它写完之后、或者要删它、换成员时调)。正在写的回复
     * 停下——等于按了停止,已经写出来的留着。离开那一页本身**不**走这里:还在写的那个留在宿主里接着写完。
     * 离开时顺手收割一次:里面刚说的那几句,主对话那边的收割要等到下一次切后台才轮得到。
     *
     * 标记是**当场**做的,落盘和收割在名单自己的作用域里做:这个 view model 马上就要被回收,它自己的
     * `viewModelScope` 等不到写完。几条路会前后脚走到这儿,只有第一条算数。
     *
     * @param harvesting 要删这条侧聊时传 false:删完之后再去读它没有意义。
     */
    fun leaveSideChat(harvesting: Boolean = true): Job? {
        if (!isSideChat || didLeaveSideChat) return null
        didLeaveSideChat = true
        stopReply()
        idleHarvestJob?.cancel()
        return sides.launch {
            persistNow()
            if (harvesting && _sideChat.value?.let { sides.get(it.id) } != null) harvester.runIfDue()
        }
    }

    // ------------------------------------------------------------------ 主对话和侧聊之间搬一段话

    private val _broughtBack = MutableStateFlow<Set<String>>(emptySet())

    /**
     * 这一次打开期间带回过主对话的那几条。只记在内存里:它的用处是让他按完看得见结果、别连按两次,
     * 不是一份要存下来的账——主对话里那一条本身才是记录。
     */
    val broughtBack: StateFlow<Set<String>> = _broughtBack.asStateFlow()

    /** 这条回复能往哪条线上搬。主对话里是「在侧聊里接着聊」,侧聊里是「带回主对话」。 */
    fun sideChatMove(message: ChatMessage): SideChatMove {
        if (isSideChat && message.id in _broughtBack.value) return SideChatMove.BROUGHT_BACK
        if (_isReplying.value || !SideChatQuote.canQuote(message)) return SideChatMove.NONE
        return when {
            isMainThread -> SideChatMove.CONTINUE_IN_SIDE_CHAT
            isSideChat -> SideChatMove.BRING_BACK
            else -> SideChatMove.NONE
        }
    }

    /**
     * 拿主对话里这一问一答开一条新侧聊。名字取那句提问(他随后能改),开头是那段回答的可见正文——不拷
     * transcript。开好了交给 [onOpened],界面接着把它打开。
     */
    fun continueInSideChat(messageId: String, onOpened: (SideChat) -> Unit) {
        val messages = _session.value.messages
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0 || sideChatMove(messages[index]) != SideChatMove.CONTINUE_IN_SIDE_CHAT) return
        val answer = messages[index]
        val question = messages.take(index).lastOrNull { it.role == ChatMessage.Role.USER && !it.isQueued }
        val seed = SideChatQuote.seed(question, answer)
        viewModelScope.launch {
            val chat = sides.create(question?.let { SideChatTitle.make(it.text) }.orEmpty())
            sides.writer(chat.id).write { it.appendAtEnd(seed) }
            onOpened(chat)
        }
    }

    /**
     * 把侧聊里这一段原样追加到主对话末尾。主对话那边照后台来的主动消息那样,在轮边界并进去(`postProactive`
     * 会拨一下它的 revision)。**不花一次调用去总结**,要带什么由他挑那一条。
     */
    fun bringBackToMain(messageId: String) {
        val chat = _sideChat.value ?: return
        val message = _session.value.messages.firstOrNull { it.id == messageId } ?: return
        if (sideChatMove(message) != SideChatMove.BRING_BACK) return
        _broughtBack.update { it + messageId }
        val note = SideChatQuote.broughtBack(message, chat.displayTitle)
        val main = threadWriter
        sides.launch { main.postProactive(note) }
    }

    override fun onCleared() {
        // 侧聊的最后一道:宿主清掉了它那一格 ViewModelStore。宿主多半已经先调过 leaveSideChat,这里是兜底。
        leaveSideChat()
        super.onCleared()
    }

    /** 他在点名确认的 dialog 上按了「同意并发送」:记下来,把刚才那句(还在输入框里)发出去。 */
    fun confirmProviderConsent() {
        val provider = _pendingProviderConsent.value ?: return
        engineSettings.recordProviderConsent(provider)
        _pendingProviderConsent.value = null
        send()
    }

    /** 按了「取消」:什么都不发,字留在输入框里,不记录任何东西——下次按发送会再问。 */
    fun declineProviderConsent() {
        _pendingProviderConsent.value = null
    }

    fun addPhoto(bitmap: Bitmap) {
        if (_draftAttachments.value.size >= ChatAttachment.MAX_ATTACHMENTS) return
        val id = UUID.randomUUID().toString()
        val draft = DraftAttachment(
            id = id,
            preview = bitmap,
            isLoading = false,
            isRecognizing = true,
            sendsImage = engineSettings.photoImagePolicy.sendsImageByDefault && supportsVision,
            imageBytes = AttachmentImage.jpegData(bitmap),
        )
        _draftAttachments.update { it + draft }
        viewModelScope.launch {
            val recognized = runCatching { TextRecognizer.recognize(bitmap) }
                .getOrElse {
                    updateDraft(id) {
                        copy(
                            isRecognizing = false,
                            failure = L10n.text(
                                "这张照片读不出来，换一张试试。",
                                "This photo could not be read. Try another one.",
                            ),
                        )
                    }
                    return@launch
                }
            updateDraft(id) {
                val policy = engineSettings.photoImagePolicy
                copy(
                    text = recognized.text,
                    droppedLines = recognized.droppedLines,
                    isRecognizing = false,
                    sendsImage = if (supportsVision && policy.sendsImageByDefault) {
                        true
                    } else {
                        sendsImage
                    },
                )
            }
        }
    }

    fun addDocument(name: String, text: String, droppedLines: Int, failure: String?) {
        if (_draftAttachments.value.size >= ChatAttachment.MAX_ATTACHMENTS) return
        _draftAttachments.update {
            it + DraftAttachment(
                preview = null,
                text = text,
                droppedLines = droppedLines,
                isLoading = false,
                isRecognizing = false,
                failure = failure,
                sendsImage = false,
                imageBytes = null,
                documentName = name,
            )
        }
    }

    /**
     * 从「文件」选进来的一份。PDF 会拆成多页照片走 OCR；Word / txt 直接取文本。
     * 隐私会话里附件仍只进草稿，发送时不落盘（见 [DraftAttachment.toChatAttachment]）。
     */
    fun importFile(context: android.content.Context, uri: android.net.Uri) {
        if (_draftAttachments.value.size >= ChatAttachment.MAX_ATTACHMENTS) return
        val placeholderId = UUID.randomUUID().toString()
        _draftAttachments.update {
            it + DraftAttachment(id = placeholderId, isLoading = true, isRecognizing = false)
        }
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) {
                runCatching { com.pinapia.vana.vision.AttachmentImporter.load(context, uri) }
                    .getOrElse {
                        listOf(
                            com.pinapia.vana.vision.ImportedAttachment.Document(
                                name = uri.lastPathSegment ?: L10n.text("文件", "File"),
                                text = "",
                                droppedLines = 0,
                                failure = L10n.text("这个文件读不出来。", "This file could not be read."),
                            ),
                        )
                    }
            }
            _draftAttachments.update { list -> list.filterNot { it.id == placeholderId } }
            for (item in imported) {
                if (_draftAttachments.value.size >= ChatAttachment.MAX_ATTACHMENTS) break
                when (item) {
                    is com.pinapia.vana.vision.ImportedAttachment.Photo -> addPhoto(item.bitmap)
                    is com.pinapia.vana.vision.ImportedAttachment.Document ->
                        addDocument(item.name, item.text, item.droppedLines, item.failure)
                }
            }
        }
    }

    fun voiceVocabulary(): List<String> =
        com.pinapia.vana.voice.VoiceVocabulary.terms(
            medications = medicationSnapshotProvider(),
            memory = memorySnapshotProvider(),
        )

    fun appendVoiceTranscript(spoken: String) {
        if (spoken.isBlank()) return
        _input.value = com.pinapia.vana.voice.VoiceTranscript.merge(_input.value, spoken)
    }

    fun removeDraft(id: String) {
        _draftAttachments.update { it.filterNot { draft -> draft.id == id } }
    }

    fun updateDraftText(id: String, text: String) {
        updateDraft(id) { copy(text = text, droppedLines = 0) }
    }

    fun setDraftSendsImage(id: String, sends: Boolean) {
        updateDraft(id) { copy(sendsImage = sends && canSendImage && supportsVision) }
    }

    /**
     * 整排一起翻。只翻输入框上方那一行提到的那几张，不该顺手把化验单也翻过去。
     */
    fun setCandidateSendsImage(sends: Boolean) {
        if (!supportsVision) return
        val policy = photoImagePolicy
        _draftAttachments.update { list ->
            list.map { draft ->
                if (draft.suggestsImage(under = policy)) draft.copy(sendsImage = sends) else draft
            }
        }
    }

    fun acceptImageOffer() {
        setCandidateSendsImage(true)
    }

    fun declineImageOffer() {
        setCandidateSendsImage(false)
    }

    fun saveMedicationFromDraft(item: MedicationItem, onSaved: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val store = TenantScope.currentStores.medications
            val saved = store.add(item) ?: return@launch
            if (saved.brief.isEmpty()) {
                MedicationBriefer.fill(saved, store, engineSettings, secureKeyStore)
            }
            withContext(Dispatchers.Main) { onSaved(saved.name) }
        }
    }

    private fun updateDraft(id: String, block: DraftAttachment.() -> DraftAttachment) {
        _draftAttachments.update { list ->
            list.map { if (it.id == id) it.block() else it }
        }
    }

    fun answerAsk(messageId: String, callId: String, answer: AskUserAnswer) {
        if (answer.isEmpty) return
        val messages = _session.value.messages
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val message = messages[index]
        val callIndex = message.toolCalls.indexOfFirst { it.id == callId }
        if (callIndex < 0) return
        if (message.toolCalls[callIndex].askAnswer != null) return
        val updatedCalls = message.toolCalls.toMutableList()
        updatedCalls[callIndex] = updatedCalls[callIndex].copy(askAnswer = answer)
        updateSession {
            copy(
                messages = messages.mapIndexed { i, m ->
                    if (i == index) m.copy(toolCalls = updatedCalls) else m
                },
            )
        }
        markDirty(messageId)
        persist()
        send(answer.messageText)
    }

    fun stopReply() {
        replyJob?.cancel()
        replyJob = null
        _isReplying.value = false
        _retryNotice.value = null
        val id = replyingMessageId ?: return
        mutateMessage(id) { markStopped() }
        persist()
    }

    fun retry(assistantId: String) {
        if (_isReplying.value) return
        val messages = _session.value.messages
        val index = messages.indexOfFirst { it.id == assistantId }
        if (index <= 0) return
        val priorUser = messages.take(index).lastOrNull { it.role == ChatMessage.Role.USER } ?: return
        updateSession {
            copy(messages = messages.take(index).map {
                if (it.id == priorUser.id) it.copy(isQueued = true) else it
            })
        }
        startReply()
    }

    /**
     * 「问问这个药」:把它挂成下一轮回复的一次性上下文。以前这会切进一条专属的「用药线」会话;
     * 现在只有一条对话,所以只是一个焦点——回复完就撤,输入框上方会露出它的名字和几个开场问题。
     */
    fun openMedication(item: MedicationItem) {
        if (!medicationsEnabled) return
        _focusMedication.value = item
        refreshEngineAvailability()
    }

    fun clearFocus() {
        _focusMedication.value = null
    }

    private fun resetFollowUps() {
        _followUps.value = emptyList()
        followUpHooks = null
    }

    private fun startReply() {
        if (_isReplying.value) return
        idleHarvestJob?.cancel()
        replyJob = viewModelScope.launch {
            _isReplying.value = true
            try {
                while (true) {
                    dequeueAll()
                    if (!hasQueuedInput() && _session.value.messages.none { it.role == ChatMessage.Role.USER }) break
                    beginAssistantMessage()
                    runTurnShrinkingOnOverflow()
                    if (!hasQueuedInput()) break
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // stopReply 已处理
            } catch (error: Throwable) {
                val wrapped = AgentError.wrapping(error)
                val message = when (wrapped) {
                    is AgentError.NeedsAPIKey -> L10n.text(
                        "需要先在设置里填写云端 API 密钥。",
                        "Enter a cloud API key in Settings first.",
                    )
                    is AgentError.NeedsModelSelection -> L10n.text(
                        "需要先在设置里选择云端模型。",
                        "Choose a cloud model in Settings first.",
                    )
                    is AgentError.InvalidAPIKey -> UserFacingModelFailure.authenticationMessage
                    else -> UserFacingModelFailure.message(wrapped)
                }.ifBlank {
                    L10n.text(
                        "云端模型配置不完整。",
                        "The cloud model configuration is incomplete.",
                    )
                }
                replyingMessageId?.let { id ->
                    mutateMessage(id) { markFailed(message) }
                }
            } finally {
                _isReplying.value = false
                replyingMessageId = null
                _retryNotice.value = null
                // 焦点只管这一轮;回复完就撤。
                _focusMedication.value = null
                persist()
                mergeBackgroundMessages()
                scheduleIdleHarvest()
                todayFeed?.refresh()
            }
        }
    }

    /**
     * 撞上模型的上下文上限:以前是让用户「开一条新对话」——现在没有新对话可开。
     * 改成强制把窗口砍到最近两轮,再原样跑一次;砍不动(本来就只剩两轮)才把错误报给用户。
     */
    private suspend fun runTurnShrinkingOnOverflow() {
        try {
            runTurn()
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            val overflow = AgentError.wrapping(error) is AgentError.ContextWindowExceeded
            if (overflow && advanceWindow(force = true, overheadTokens = 0)) runTurn() else throw error
        }
    }

    private suspend fun runTurn() {
        locationProvider.refresh()
        refreshRecallReach()
        var engine = resolveEngine()
        // 请求之前先看窗口该不该滑:固定开销(system 段、工具定义)先从预算里扣掉。
        // 滑动之后「有原文滑出去了」这件事变了,召回工具该不该挂也跟着变——所以要重新装配一次。
        if (advanceWindow(force = false, overheadTokens = engine.requestOverheadTokens())) engine = resolveEngine()
        val history = windowMessages().filterNot { it.isQueued }
        engine.reply(
            history = history,
            pendingInput = {
                val queued = _session.value.messages.filter { it.isQueued && it.role == ChatMessage.Role.USER }
                if (queued.isEmpty()) return@reply emptyList()
                updateSession {
                    copy(messages = messages.map { if (it.isQueued) it.copy(isQueued = false) else it })
                }
                queued.map { AgentPendingInput(id = it.uuid, text = it.text) }
            },
        ).collect { event ->
            applyEvent(event)
            // ViewModel 跑在 Main.immediate：单纯 yield() 不会等 Choreographer，
            // Compose 来不及上屏，delta 被合成最后一帧 → 看起来像「整段蹦出」。
            when (event) {
                is AgentTurnEvent.TextDelta,
                is AgentTurnEvent.ReasoningDelta,
                -> awaitComposeFrame()
                else -> Unit
            }
        }
    }

    private suspend fun awaitComposeFrame() {
        suspendCancellableCoroutine { cont ->
            val choreographer = Choreographer.getInstance()
            val callback = Choreographer.FrameCallback {
                if (cont.isActive) cont.resume(Unit)
            }
            choreographer.postFrameCallback(callback)
            cont.invokeOnCancellation {
                choreographer.removeFrameCallback(callback)
            }
        }
    }

    private fun applyEvent(event: AgentTurnEvent) {
        when (event) {
            is AgentTurnEvent.HistoryCompacted -> {
                mutateMessage(event.messageID.toString()) { applyCompaction(event.artifact) }
            }
            is AgentTurnEvent.RetryScheduled -> {
                _retryNotice.value = L10n.text(
                    "连接不稳定，正在重试（${event.notice.attempt}/${event.notice.maxAttempts}）",
                    "Connection is unstable. Retrying (${event.notice.attempt}/${event.notice.maxAttempts})",
                )
            }
            is AgentTurnEvent.TextDelta -> {
                _retryNotice.value = null
                replyingMessageId?.let { id -> mutateMessage(id) { apply(event) } }
            }
            is AgentTurnEvent.PendingInputAccepted -> {
                splitReplyAroundInterjection(event.inputs.map { it.id.toString() })
                replyingMessageId?.let { id -> mutateMessage(id) { apply(event) } }
            }
            else -> {
                replyingMessageId?.let { id -> mutateMessage(id) { apply(event) } }
            }
        }
    }

    private fun splitReplyAroundInterjection(acceptedIds: List<String>) {
        val id = replyingMessageId ?: return
        val messages = _session.value.messages.toMutableList()
        val index = messages.indexOfFirst { it.id == id }
        if (index < 0) return
        val current = messages[index]
        if (current.text.isBlank() && current.toolCalls.isEmpty()) {
            return
        }
        val firstHalf = current.copy(
            id = UUID.randomUUID().toString(),
            storedTurn = current.storedTurn.copy(
                inlinedMessageIDs = current.storedTurn.inlinedMessageIDs + current.id,
            ),
        )
        val secondHalf = ChatMessage(role = ChatMessage.Role.ASSISTANT, text = "")
        messages[index] = firstHalf
        val insertAt = messages.indexOfLast { it.id in acceptedIds }.let { if (it >= 0) it + 1 else messages.size }
        messages.add(insertAt.coerceAtMost(messages.size), secondHalf)
        secondHalf.storedTurn = secondHalf.storedTurn.copy(
            inlinedMessageIDs = listOf(firstHalf.id),
        )
        replyingMessageId = secondHalf.id
        updateSession { copy(messages = messages.toList()) }
    }

    private fun beginAssistantMessage() {
        val assistant = ChatMessage(role = ChatMessage.Role.ASSISTANT, text = "")
        replyingMessageId = assistant.id
        updateSession { copy(messages = messages + assistant) }
    }

    private fun dequeueAll() {
        val messages = _session.value.messages
        val firstQueuedIndex = messages.indexOfFirst { it.isQueued && it.role == ChatMessage.Role.USER }
        if (firstQueuedIndex < 0) return
        updateSession {
            copy(
                messages = messages.mapIndexed { index, message ->
                    if (index <= firstQueuedIndex && message.isQueued) message.copy(isQueued = false) else message
                },
            )
        }
    }

    private fun hasQueuedInput(): Boolean =
        _session.value.messages.any { it.isQueued && it.role == ChatMessage.Role.USER }

    /**
     * 装配要用的全部输入。聊天和抽记忆都从这里取——抽取器要遵守的「哪些话题别记」
     * 得和聊天时实际挂出去的插件是同一份,不然两边各说各话。
     */
    private fun pluginEnvironment(): PluginEnvironment {
        val stores = TenantScope.currentStores
        val location = if (locationProvider.isAuthorized) {
            locationProvider.snapshot
        } else {
            LocationSnapshot.unknown
        }
        return PluginEnvironment(
            isEnabled = engineSettings::isPluginEnabled,
            tenant = tenantProvider(),
            // 召回读整条线程的档案;只有真的有看不见的原文(自己滑出窗口的那段,加上别的线),PluginRegistry 才会把它挂上。
            archive = writer.archive,
            hiddenBeforePos = ::hiddenBeforePos,
            otherThreads = recallReach.first,
            otherThreadsScope = recallReach.second,
            memoryStore = stores.memory,
            memorySnapshot = memorySnapshotProvider,
            location = location,
            webSearch = WebSearchClient.storedKey(secureKeyStore.serperApiKey),
            webFetch = WebFetchClient.direct(),
            exerciseLibrary = exerciseLibrary,
            medicationStore = stores.medications,
            medicationSnapshot = medicationSnapshotProvider,
            focusMedication = _focusMedication.value,
            tasks = tasksEnvironment,
            noteStore = stores.notes.takeIf { engineSettings.notesEnabled },
            measurementStore = stores.measurements,
            measurementSnapshot = measurementSnapshotProvider,
        )
    }

    private fun resolveEngine(): CloudEngine {
        val plugins = PluginRegistry.agentPlugins(pluginEnvironment(), PluginRoute.FOREGROUND)
        val context = PluginRegistry.foregroundContext(
            isPrivate = ephemeral,
        )
        return CloudEngine.create(
            providerId = engineSettings.providerId,
            model = engineSettings.model,
            secureKeyStore = secureKeyStore,
            plugins = plugins,
            pluginContext = context,
            thinkingEnabled = engineSettings.thinkingEnabled,
            persona = engineSettings.persona,
            hooks = followUpHooks(),
            sideChatTitle = _sideChat.value?.title,
        )
    }

    private fun followUpHooks(): AgentHookDispatcher {
        followUpHooks?.let { return it }
        val key = secureKeyStore.apiKey?.trim().orEmpty()
        val hook = FollowUpSuggestionHook(
            providerId = engineSettings.providerId,
            model = engineSettings.model,
            apiKey = key,
            onSuggestions = { suggestions ->
                if (_isReplying.value) return@FollowUpSuggestionHook
                _followUps.value = suggestions
            },
        )
        val dispatcher = AgentHookDispatcher(listOf(hook))
        followUpHooks = dispatcher
        return dispatcher
    }

    // ------------------------------------------------------------------ 召回够得着的别的线

    /** 召回够得着的别的线,和它们统称什么。每轮请求之前现算([refreshRecallReach])。 */
    private var recallReach: Pair<List<HistoryRecallTools.Source>, OtherThreadsScope?> = emptyList<HistoryRecallTools.Source>() to null

    /** 每轮现算:侧聊刚删掉的话,下一轮就翻不到它。不留痕的那条不翻别的线。 */
    private suspend fun refreshRecallReach() {
        if (ephemeral) return
        recallReach = SideChatRecall.gather(
            main = threadWriter,
            sides = sides,
            current = _sideChat.value?.id,
        )
    }

    // ------------------------------------------------------------------ 窗口

    /**
     * 窗口起点的位置——它**之前**的才是「滑出去了」的历史。没淘汰过就没有,召回不挂。
     * 读的是线程 meta 里那个持久化的游标,所以重启之后依然对得上。
     */
    private fun hiddenBeforePos(): Double? {
        if (ephemeral) return null
        return windowStartId?.let { threadStore.positionOf(it) }?.takeIf { pos -> writer.archive.hasRowsBefore(pos) }
    }

    private fun windowStartIndex(): Int =
        windowStartId?.let { id -> _session.value.messages.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: 0

    /** 这一轮请求里带的历史:窗口起点往后的全部。窗口之外的原文不发。 */
    private fun windowMessages(): List<ChatMessage> = _session.value.messages.drop(windowStartIndex())

    /**
     * 窗口滑不滑。涨到高水位才动,一次砍到低水位;[force] 是撞上上下文上限时的救援,只留最近两轮。
     * 返回窗口起点有没有前移。淘汰只前移游标(存进线程 meta),消息本身一条不删。
     */
    private fun advanceWindow(force: Boolean, overheadTokens: Int): Boolean {
        if (ephemeral && _session.value.messages.isEmpty()) return false
        val messages = _session.value.messages
        val start = windowStartIndex()
        val newStart = if (force) {
            ThreadWindow.forceEvict(messages, start, keepTurns = FORCED_KEEP_TURNS)
        } else {
            val contextWindow = CloudCatalog.model(engineSettings.model, engineSettings.providerId)?.contextWindow
            // 历史里的思考会不会原样发回去,看当前这条协议。
            val replaysReasoning = OpenAICompatibleModelClient.replaysReasoning(
                CloudCatalog.provider(engineSettings.providerId)?.wireProtocol,
            )
            ThreadWindow.evict(messages, start, WindowPolicy.forContext(contextWindow), overheadTokens, replaysReasoning)
        }
        if (newStart == start || newStart !in messages.indices) return false
        windowStartId = messages[newStart].id
        if (!ephemeral) {
            val pos = threadStore.positionOf(messages[newStart].id)
            viewModelScope.launch { writer.write { store -> store.updateMeta { it.copy(windowStartPos = pos) } } }
            // 有原文要离开窗口了:趁这时候把还没抽过的收割一遍(不等它,也不因此阻塞这一轮)。
            harvestSoon()
        }
        return true
    }

    private fun harvestSoon() {
        if (ephemeral) return
        viewModelScope.launch { harvester.runIfDue() }
    }

    /** 一轮回复结束、他安静了半小时:抽一次记忆。切到后台那个触发点在 VanaApp 里。 */
    private fun scheduleIdleHarvest() {
        if (ephemeral) return
        idleHarvestJob?.cancel()
        idleHarvestJob = viewModelScope.launch {
            delay(IDLE_HARVEST_DELAY)
            harvester.runIfDue()
        }
    }

    /**
     * 发请求之前对一遍要发的那几张图。
     *
     * 模型换成看不了图的就把图摘掉——他可以在聊到一半时换模型，原样发过去是一个 400。
     * 摘掉之后正文自动退回那句「看不了图像本身」。
     */
    private fun loadImagePayloads(session: ChatSession): ChatSession {
        if (session.messages.none { message -> message.attachments.any { it.sendsImage } }) {
            return session
        }
        val vision = supportsVision
        val store = TenantScope.currentStores.attachments
        val messages = session.messages.map { message ->
            if (message.attachments.none { it.sendsImage }) return@map message
            message.copy(
                attachments = message.attachments.map { attachment ->
                    if (!attachment.sendsImage) return@map attachment
                    if (!vision) {
                        attachment.copy(imagePayload = null)
                    } else if (attachment.imagePayload != null) {
                        attachment
                    } else {
                        val name = attachment.imageFileName ?: return@map attachment
                        val bytes = store.data(named = name) ?: return@map attachment
                        attachment.copy(imagePayload = bytes.toBase64())
                    }
                },
            )
        }
        return session.copy(messages = messages)
    }

    private fun markDirty(id: String) {
        synchronized(dirtyIds) { dirtyIds += id }
    }

    private fun updateSession(transform: ChatSession.() -> ChatSession) {
        _session.update { it.transform() }
    }

    private fun mutateMessage(id: String, block: ChatMessage.() -> Unit) {
        markDirty(id)
        updateSession {
            copy(
                messages = messages.map { message ->
                    if (message.id != id) {
                        message
                    } else {
                        // 必须先 copy 再改：ChatMessage/ChatSession 是 data class，
                        // 若先原地改旧实例再 copy，StateFlow 会因 equals 相等而丢弃更新，
                        // Compose 收不到中间态，SSE 看起来就像「整段蹦出来」。
                        val next = message.copy(
                            attachments = message.attachments.toList(),
                            toolCalls = message.toolCalls.toList(),
                        )
                        next.block()
                        next
                    }
                },
            )
        }
    }

    class Factory(
        private val threadWriter: ThreadWriter,
        private val engineSettings: EngineSettings,
        private val secureKeyStore: SecureKeyStore,
        private val locationProvider: LocationProvider,
        private val exerciseLibrary: ExerciseLibrary,
        private val memorySnapshotProvider: () -> MemorySnapshot,
        private val medicationSnapshotProvider: () -> MedicationSnapshot,
        private val measurementSnapshotProvider: () -> MeasurementSnapshot = { MeasurementSnapshot.empty },
        private val tasksEnvironment: TasksEnvironment? = null,
        private val ephemeral: Boolean = false,
        private val sideChat: SideChat? = null,
        private val sides: SideChatStore? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(
                threadWriter = threadWriter,
                engineSettings = engineSettings,
                secureKeyStore = secureKeyStore,
                locationProvider = locationProvider,
                exerciseLibrary = exerciseLibrary,
                memorySnapshotProvider = memorySnapshotProvider,
                medicationSnapshotProvider = medicationSnapshotProvider,
                measurementSnapshotProvider = measurementSnapshotProvider,
                tasksEnvironment = tasksEnvironment,
                ephemeral = ephemeral,
                sideChat = sideChat,
                sides = sides,
            ) as T
        }
    }

    private companion object {
        /** 内存里那一份线程视图的固定 id。 */
        const val THREAD_SESSION_ID = "thread"

        /** 撞上上下文上限时强制留下的最近轮数。 */
        const val FORCED_KEEP_TURNS = 2

        val IDLE_HARVEST_DELAY = 30.minutes
    }
}

/** 一条回复能往另一条线上搬的那一步。主对话里是「在侧聊里接着聊」,侧聊里是「带回主对话」。 */
enum class SideChatMove {
    NONE,
    CONTINUE_IN_SIDE_CHAT,
    BRING_BACK,

    /** 这一次打开期间已经带回去过了:按完要看得见结果,也别让他连按两次。 */
    BROUGHT_BACK,
}

enum class ErrorRecovery {
    RETRY,
    OPEN_SETTINGS,
}
