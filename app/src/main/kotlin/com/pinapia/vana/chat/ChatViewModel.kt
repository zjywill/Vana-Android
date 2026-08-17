package com.pinapia.vana.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pinapia.vana.agent.AgentError
import com.pinapia.vana.agent.CloudEngine
import com.pinapia.vana.agent.FollowUpSuggestionHook
import com.pinapia.vana.agent.UserFacingModelFailure
import com.pinapia.vana.agent.healthChat
import com.pinapia.vana.agentruntime.AgentHookDispatcher
import com.pinapia.vana.agentruntime.AgentPendingInput
import com.pinapia.vana.agentruntime.AgentTurnEvent
import com.pinapia.vana.agentruntime.CapabilityRegistry
import com.pinapia.vana.agentruntime.apply
import com.pinapia.vana.ask.AskUserAnswer
import com.pinapia.vana.exercises.ExerciseLibrary
import com.pinapia.vana.location.LocationProvider
import com.pinapia.vana.location.LocationSnapshot
import com.pinapia.vana.medications.MedicationItem
import com.pinapia.vana.medications.MedicationSnapshot
import com.pinapia.vana.measurements.MeasurementSnapshot
import com.pinapia.vana.memory.MemoryExtractor
import com.pinapia.vana.memory.MemoryHarvest
import com.pinapia.vana.memory.MemorySnapshot
import com.pinapia.vana.memory.apply
import com.pinapia.vana.recall.SessionRecallTrigger
import com.pinapia.vana.search.WebSearchClient
import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.session.ChatSession
import com.pinapia.vana.session.GoalSummary
import com.pinapia.vana.session.SessionStore
import com.pinapia.vana.session.SessionSummary
import com.pinapia.vana.session.SessionThread
import com.pinapia.vana.settings.EngineSettings
import com.pinapia.vana.settings.SecureKeyStore
import com.pinapia.vana.tenant.Tenant
import com.pinapia.vana.tenant.TenantOpening
import com.pinapia.vana.tenant.TenantScope
import com.pinapia.vana.medications.MedicationBriefer
import com.pinapia.vana.vision.AttachmentImage
import com.pinapia.vana.vision.ChatAttachment
import com.pinapia.vana.vision.DraftAttachment
import com.pinapia.vana.vision.PhotoImagePolicy
import com.pinapia.vana.vision.TextRecognizer
import com.pinapia.vana.vision.toBase64
import android.graphics.Bitmap
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.datetime.Clock

class ChatViewModel(
    private val sessionStore: SessionStore,
    private val engineSettings: EngineSettings,
    private val secureKeyStore: SecureKeyStore,
    private val locationProvider: LocationProvider,
    private val exerciseLibrary: ExerciseLibrary,
    private val memorySnapshotProvider: () -> MemorySnapshot,
    private val medicationSnapshotProvider: () -> MedicationSnapshot,
    private val measurementSnapshotProvider: () -> MeasurementSnapshot = { MeasurementSnapshot.empty },
    private val tenantProvider: () -> Tenant = { TenantScope.current },
) : ViewModel() {
    private val _session = MutableStateFlow(ChatSession())
    val session: StateFlow<ChatSession> = _session.asStateFlow()

    private val _summaries = MutableStateFlow<List<SessionSummary>>(emptyList())
    val summaries: StateFlow<List<SessionSummary>> = _summaries.asStateFlow()

    private val _goals = MutableStateFlow<List<GoalSummary>>(emptyList())
    val goals: StateFlow<List<GoalSummary>> = _goals.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _isReplying = MutableStateFlow(false)
    val isReplying: StateFlow<Boolean> = _isReplying.asStateFlow()

    private val _engineGuidance = MutableStateFlow<String?>(null)
    val engineGuidance: StateFlow<String?> = _engineGuidance.asStateFlow()

    private val _cloudSetupRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val cloudSetupRequests: SharedFlow<Unit> = _cloudSetupRequests

    private val _retryNotice = MutableStateFlow<String?>(null)
    val retryNotice: StateFlow<String?> = _retryNotice.asStateFlow()

    private val _followUps = MutableStateFlow<List<String>>(emptyList())
    val followUps: StateFlow<List<String>> = _followUps.asStateFlow()

    private val _draftAttachments = MutableStateFlow<List<DraftAttachment>>(emptyList())
    val draftAttachments: StateFlow<List<DraftAttachment>> = _draftAttachments.asStateFlow()

    private val _focusMedication = MutableStateFlow<MedicationItem?>(null)
    val focusMedication: StateFlow<MedicationItem?> = _focusMedication.asStateFlow()

    private var replyJob: Job? = null
    private var replyingMessageId: String? = null
    private var followUpHooks: AgentHookDispatcher? = null
    private var followUpSessionId: String? = null
    private var harvestJob: Job? = null
    val suggestedQuestions: List<String>
        get() {
            _focusMedication.value?.openingQuestions?.let { return it }
            val tenant = tenantProvider()
            if (!tenant.isOwner) {
                return TenantOpening.questions(tenant, medicationSnapshotProvider())
            }
            return DefaultQuestions
        }

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
            return "你设的是「${policy.label}」，但当前模型看不了图——这一档暂时不生效。"
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
        refreshSummaries()
        refreshEngineAvailability()
        viewModelScope.launch {
            if (locationProvider.isAuthorized) {
                locationProvider.refresh()
            }
        }
    }

    fun setInput(value: String) {
        _input.value = value
    }

    fun applyCheckIn(question: String?) {
        if (!question.isNullOrBlank()) {
            _input.value = question
        }
    }

    /** App Shortcut「问 Vana」:把问题带进新会话并自动发送。 */
    fun applyAskAndSend(question: String?) {
        val trimmed = question?.trim().orEmpty()
        if (trimmed.isEmpty()) return
        if (!_session.value.isEmpty || _isReplying.value) {
            startNewSession()
        }
        send(trimmed)
    }

    fun refreshEngineAvailability() {
        _engineGuidance.value = if (engineSettings.isConfigured(secureKeyStore)) {
            null
        } else {
            "还没配置云端模型。请前往设置填写 API 密钥，并选择服务商和模型。"
        }
    }

    fun refreshSummaries() {
        viewModelScope.launch {
            _summaries.value = sessionStore.listSummaries()
            _goals.value = sessionStore.goals()
            refreshFocusMedication()
        }
    }

    private fun refreshFocusMedication() {
        if (!engineSettings.medicationsEnabled) {
            _focusMedication.value = null
            return
        }
        val focusId = (_session.value.threadId)
            ?.let { SessionThread.parse(it) }
            ?.let { thread -> (thread as? SessionThread.Medication)?.medicationId }
        _focusMedication.value = focusId?.let { id ->
            medicationSnapshotProvider().items.firstOrNull { it.id == id }
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
        _input.value = ""
        _followUps.value = emptyList()
        val persist = !_session.value.isPrivate
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
        if (!_isReplying.value) {
            startReply()
        }
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
                        copy(isRecognizing = false, failure = "这张照片读不出来，换一张试试。")
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
                                name = uri.lastPathSegment ?: "文件",
                                text = "",
                                droppedLines = 0,
                                failure = "这个文件读不出来。",
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
        send(answer.messageText)
    }

    fun stopReply() {
        replyJob?.cancel()
        replyJob = null
        _isReplying.value = false
        _retryNotice.value = null
        val id = replyingMessageId ?: return
        mutateMessage(id) { markStopped() }
        saveSession()
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

    fun startNewSession(isPrivate: Boolean = false) {
        stopReply()
        harvestIfNeeded(_session.value)
        resetFollowUps()
        _draftAttachments.value = emptyList()
        _focusMedication.value = null
        _session.value = ChatSession(isPrivate = isPrivate)
        refreshEngineAvailability()
    }

    fun openSession(id: String) {
        stopReply()
        harvestIfNeeded(_session.value)
        resetFollowUps()
        _draftAttachments.value = emptyList()
        viewModelScope.launch {
            val loaded = sessionStore.load(id) ?: return@launch
            _session.value = loadImagePayloads(loaded)
            refreshFocusMedication()
            refreshEngineAvailability()
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            sessionStore.delete(id)
            if (_session.value.id == id) {
                startNewSession()
            }
            refreshSummaries()
        }
    }

    fun clearAllChats() {
        if (_isReplying.value) return
        stopReply()
        viewModelScope.launch {
            sessionStore.deleteAll()
            _session.value = ChatSession()
            _focusMedication.value = null
            resetFollowUps()
            _draftAttachments.value = emptyList()
            refreshSummaries()
        }
    }

    fun setPrivate(isPrivate: Boolean) {
        if (!_session.value.isEmpty) return
        updateSession { copy(isPrivate = isPrivate) }
    }

    fun openMedication(item: MedicationItem) {
        if (_isReplying.value) return
        val thread = SessionThread.medication(item.id)
        stopReply()
        harvestIfNeeded(_session.value)
        resetFollowUps()
        _draftAttachments.value = emptyList()
        viewModelScope.launch {
            val continued = sessionStore.openThread(thread)
            _session.value = continued?.let { loadImagePayloads(it) }
                ?: ChatSession(threadId = thread.id, threadTitle = item.name)
            _focusMedication.value = item
            refreshEngineAvailability()
            refreshSummaries()
        }
    }

    fun startGoal(named: String) {
        if (_isReplying.value) return
        val title = named.trim()
        if (title.isEmpty()) return
        val thread = SessionThread.goal()
        stopReply()
        harvestIfNeeded(_session.value)
        resetFollowUps()
        _draftAttachments.value = emptyList()
        _focusMedication.value = null
        _session.value = ChatSession(threadId = thread.id, threadTitle = title)
        refreshEngineAvailability()
    }

    fun openGoal(goal: GoalSummary) {
        if (_isReplying.value) return
        val thread = goal.thread ?: return
        stopReply()
        harvestIfNeeded(_session.value)
        resetFollowUps()
        _draftAttachments.value = emptyList()
        _focusMedication.value = null
        viewModelScope.launch {
            val continued = sessionStore.openThread(thread)
            _session.value = continued?.let { loadImagePayloads(it) }
                ?: ChatSession(threadId = thread.id, threadTitle = goal.title)
            refreshEngineAvailability()
            refreshSummaries()
        }
    }

    fun renameGoal(goal: GoalSummary, title: String) {
        val thread = goal.thread ?: return
        viewModelScope.launch {
            sessionStore.renameThread(thread, title)
            if (_session.value.threadId == thread.id) {
                updateSession { copy(threadTitle = title.trim()) }
            }
            refreshSummaries()
        }
    }

    fun deleteGoal(goal: GoalSummary) {
        val thread = goal.thread ?: return
        viewModelScope.launch {
            sessionStore.deleteThread(thread)
            if (_session.value.threadId == thread.id) {
                startNewSession()
            }
            refreshSummaries()
        }
    }

    fun branch(fromMessageId: String) {
        if (_isReplying.value) return
        val messages = _session.value.messages
        val index = messages.indexOfFirst { it.id == fromMessageId }
        if (index < 0) return
        stopReply()
        harvestIfNeeded(_session.value)
        resetFollowUps()
        val source = _session.value
        val branched = ChatSession(
            messages = messages.take(index + 1).map { it.copy(isQueued = false) },
            isPrivate = source.isPrivate,
            memoryHarvestedMessageCount = source.memoryHarvestedMessageCount
                .coerceAtMost(index + 1),
            // 分支不带走 thread——目标/用药线不能拆成两条
        )
        _session.value = branched
        _focusMedication.value = null
        saveSession()
        refreshSummaries()
    }

    private fun resetFollowUps() {
        _followUps.value = emptyList()
        followUpHooks = null
        followUpSessionId = null
    }

    private fun startReply() {
        if (_isReplying.value) return
        replyJob = viewModelScope.launch {
            _isReplying.value = true
            try {
                while (true) {
                    dequeueAll()
                    if (!hasQueuedInput() && _session.value.messages.none { it.role == ChatMessage.Role.USER }) break
                    beginAssistantMessage()
                    runTurn()
                    if (!hasQueuedInput()) break
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // stopReply 已处理
            } catch (error: Throwable) {
                val wrapped = AgentError.wrapping(error)
                val message = when (wrapped) {
                    is AgentError.NeedsAPIKey,
                    is AgentError.NeedsModelSelection,
                    is AgentError.InvalidAPIKey,
                    -> wrapped.message ?: "云端模型配置不完整。"
                    else -> UserFacingModelFailure.message(wrapped)
                }
                replyingMessageId?.let { id ->
                    mutateMessage(id) { markFailed(message) }
                }
            } finally {
                _isReplying.value = false
                replyingMessageId = null
                _retryNotice.value = null
                saveSession()
                refreshSummaries()
            }
        }
    }

    private suspend fun runTurn() {
        locationProvider.refresh()
        val engine = resolveEngine()
        val history = _session.value.messages.filterNot { it.isQueued }
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
                _retryNotice.value = "连接不稳定，正在重试（${event.notice.attempt}/${event.notice.maxAttempts}）"
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

    private fun resolveEngine(): CloudEngine {
        val tenant = tenantProvider()
        val stores = TenantScope.currentStores
        val webSearch = WebSearchClient.storedKey(secureKeyStore.serperApiKey)
        val registry = CapabilityRegistry.healthChat(
            allowsMemoryWrites = !_session.value.isPrivate,
            allowsMedicationWrites = !_session.value.isPrivate,
            allowsMeasurementWrites = !_session.value.isPrivate,
            allowsRecall = SessionRecallTrigger.unlocksRecall(inMessages = _session.value.messages),
            asksUser = true,
            memoryStore = stores.memory,
            medicationStore = stores.medications,
            measurementStore = stores.measurements,
            sessionStore = sessionStore,
            currentSessionId = _session.value.id,
            webSearch = webSearch,
            exerciseLibrary = exerciseLibrary,
            memoryEnabled = engineSettings.memoryEnabled,
            medicationsEnabled = engineSettings.medicationsEnabled,
            measurementsEnabled = engineSettings.measurementsEnabled,
        )
        val location = if (locationProvider.isAuthorized) {
            locationProvider.snapshot
        } else {
            LocationSnapshot.unknown
        }
        val thread = SessionThread.parse(_session.value.threadId)
        val goalTitle = if (thread?.isGoal == true) _session.value.threadTitle else null
        return CloudEngine.create(
            providerId = engineSettings.providerId,
            model = engineSettings.model,
            secureKeyStore = secureKeyStore,
            tenant = tenant,
            memory = if (engineSettings.memoryEnabled) memorySnapshotProvider() else MemorySnapshot.empty,
            medications = if (engineSettings.medicationsEnabled) medicationSnapshotProvider() else MedicationSnapshot.empty,
            measurements = if (engineSettings.measurementsEnabled) {
                measurementSnapshotProvider()
            } else {
                MeasurementSnapshot.empty
            },
            location = location,
            capabilityRegistry = registry,
            thinkingEnabled = engineSettings.thinkingEnabled,
            persona = engineSettings.persona,
            hooks = followUpHooks(),
            goal = goalTitle,
            focusMedication = _focusMedication.value,
        )
    }

    private fun followUpHooks(): AgentHookDispatcher {
        val sessionId = _session.value.id
        followUpHooks?.let { existing ->
            if (followUpSessionId == sessionId) return existing
        }
        val key = secureKeyStore.apiKey?.trim().orEmpty()
        val hook = FollowUpSuggestionHook(
            providerId = engineSettings.providerId,
            model = engineSettings.model,
            apiKey = key,
            onSuggestions = { suggestions ->
                if (_session.value.id != sessionId || _isReplying.value) return@FollowUpSuggestionHook
                _followUps.value = suggestions
            },
        )
        val dispatcher = AgentHookDispatcher(listOf(hook))
        followUpHooks = dispatcher
        followUpSessionId = sessionId
        return dispatcher
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

    private fun harvestIfNeeded(session: ChatSession) {
        if (!MemoryHarvest.shouldHarvest(session, engineSettings.memoryEnabled)) return
        val key = secureKeyStore.apiKey?.trim().orEmpty()
        if (key.isEmpty()) return
        val snapshot = memorySnapshotProvider()
        val messageCount = session.messages.size
        val sessionId = session.id
        harvestJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val ops = MemoryExtractor(
                    providerId = engineSettings.providerId,
                    model = engineSettings.model,
                    apiKey = key,
                    snapshot = snapshot,
                ).operations(from = session)
                TenantScope.currentStores.memory.apply(ops)
                withContext(Dispatchers.Main) {
                    if (_session.value.id == sessionId) {
                        updateSession { copy(memoryHarvestedMessageCount = messageCount) }
                        saveSession()
                    } else {
                        sessionStore.load(sessionId)?.let { loaded ->
                            sessionStore.save(loaded.copy(memoryHarvestedMessageCount = messageCount))
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        harvestIfNeeded(_session.value)
        super.onCleared()
    }

    private fun saveSession() {
        val session = _session.value
        if (session.isPrivate || session.isEmpty) return
        val updated = session.copy(updatedAt = Clock.System.now())
        _session.value = updated
        sessionStore.save(updated)
    }

    private fun updateSession(transform: ChatSession.() -> ChatSession) {
        _session.update { it.transform() }
    }

    private fun mutateMessage(id: String, block: ChatMessage.() -> Unit) {
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
        private val sessionStore: SessionStore,
        private val engineSettings: EngineSettings,
        private val secureKeyStore: SecureKeyStore,
        private val locationProvider: LocationProvider,
        private val exerciseLibrary: ExerciseLibrary,
        private val memorySnapshotProvider: () -> MemorySnapshot,
        private val medicationSnapshotProvider: () -> MedicationSnapshot,
        private val measurementSnapshotProvider: () -> MeasurementSnapshot = { MeasurementSnapshot.empty },
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(
                sessionStore = sessionStore,
                engineSettings = engineSettings,
                secureKeyStore = secureKeyStore,
                locationProvider = locationProvider,
                exerciseLibrary = exerciseLibrary,
                memorySnapshotProvider = memorySnapshotProvider,
                medicationSnapshotProvider = medicationSnapshotProvider,
                measurementSnapshotProvider = measurementSnapshotProvider,
            ) as T
        }
    }

    companion object {
        private val DefaultQuestions = listOf(
            "帮我看看这张化验单",
            "最近总感觉不舒服是怎么回事？",
            "帮我记下今天的体重",
        )
    }
}
