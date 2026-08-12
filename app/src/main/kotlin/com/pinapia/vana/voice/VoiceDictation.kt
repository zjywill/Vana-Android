package com.pinapia.vana.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 按住说话。**本机优先识别，录音不落盘，出来的是一条和打字一模一样的文本消息。**
 *
 * 用 `SpeechRecognizer` + `EXTRA_PREFER_OFFLINE`（有就开），对应 iOS 的
 * `SpeechAnalyzer` / `SpeechTranscriber` 本机路径。
 *
 * 几条不要破坏的：
 * - **松手只填输入框，不自动发送。**
 * - **不做常驻监听。** 引擎只活在手指按着的那几秒里。
 * - **中文不可用要说得出口。** 设置页那一段显示的就是这里的状态。
 *
 * 词表走 `EXTRA_BIASING_STRINGS`（API 33+）。偏置对中文是否真正生效因厂商识别服务而异；
 * 无效时仍保留按住说话——用户仍可用输入法自带语音（见 CLAUDE.md）。
 */
class VoiceDictation private constructor(private val appContext: Context) {
    enum class Availability {
        UNKNOWN,
        READY,
        UNSUPPORTED_LOCALE,
        UNAVAILABLE,
        ;

        val isReady: Boolean get() = this == READY
    }

    enum class Status {
        IDLE,
        STARTING,
        LISTENING,
    }

    private val _availability = MutableStateFlow(Availability.UNKNOWN)
    val availability: StateFlow<Availability> = _availability.asStateFlow()

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _resolvedLocale = MutableStateFlow<String?>(null)
    val resolvedLocale: StateFlow<String?> = _resolvedLocale.asStateFlow()

    private val _supportedLocaleIdentifiers = MutableStateFlow<List<String>>(emptyList())
    val supportedLocaleIdentifiers: StateFlow<List<String>> = _supportedLocaleIdentifiers.asStateFlow()

    val isListening: Boolean get() = _status.value == Status.LISTENING
    val isEnabled: Boolean
        get() = _availability.value != Availability.UNSUPPORTED_LOCALE &&
            _availability.value != Availability.UNAVAILABLE

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var vocabulary: List<String> = emptyList()
    private var token = 0
    private var noticeRunnable: Runnable? = null
    private var finalized = ""
    private var volatileText = ""

    fun refresh() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            _availability.value = Availability.UNAVAILABLE
            _resolvedLocale.value = null
            return
        }
        val chinese = resolveChineseLocale()
        _supportedLocaleIdentifiers.value = listOfNotNull(
            chinese?.toLanguageTag(),
            Locale.SIMPLIFIED_CHINESE.toLanguageTag(),
            Locale.TRADITIONAL_CHINESE.toLanguageTag(),
        ).distinct()
        if (chinese == null) {
            _availability.value = Availability.UNSUPPORTED_LOCALE
            _resolvedLocale.value = null
            return
        }
        _resolvedLocale.value = chinese.toLanguageTag()
        // 有识别服务且挑到了中文就算可用。离线模型是否已装要到按下那一刻才知道；
        // 没装时 onError 会落到提示「先用键盘」。
        _availability.value = Availability.READY
    }

    /**
     * 开始听。返回 false 表示这一按没有录起来——调用方该把键盘调出来，屏幕上会有 notice。
     */
    fun start(vocabulary: List<String>): Boolean {
        if (_status.value != Status.IDLE) return false
        token += 1
        val token = token
        _status.value = Status.STARTING
        this.vocabulary = vocabulary

        if (_availability.value != Availability.READY) {
            refresh()
        }
        when (_availability.value) {
            Availability.READY -> Unit
            Availability.UNSUPPORTED_LOCALE, Availability.UNAVAILABLE, Availability.UNKNOWN ->
                return abort(token, "这台设备还不支持中文语音识别，键盘上那颗麦克风可以用。")
        }

        val localeTag = _resolvedLocale.value
            ?: return abort(token, "这台设备还不支持中文语音识别，键盘上那颗麦克风可以用。")

        return try {
            val speech = SpeechRecognizer.createSpeechRecognizer(appContext)
            recognizer = speech
            speech.setRecognitionListener(listener(token))
            speech.startListening(recognitionIntent(localeTag, vocabulary))
            if (token != this.token) {
                teardown()
                return false
            }
            _status.value = Status.LISTENING
            true
        } catch (_: Exception) {
            teardown()
            abort(token, "麦克风打不开，先用键盘吧。")
        }
    }

    /** 松手。返回这一次说出来的整句话（已 trim），空字符串表示什么都没认出来。 */
    fun stop(): String {
        if (_status.value == Status.IDLE) return ""
        token += 1
        if (_status.value != Status.LISTENING) {
            teardown()
            _status.value = Status.IDLE
            return ""
        }
        // stopListening 会再回调一次 onResults；这里直接取当前 transcript。
        runCatching { recognizer?.stopListening() }
        val text = _transcript.value.trim()
        teardown()
        _status.value = Status.IDLE
        return text
    }

    /** 手指划开了：这一段不要了。 */
    fun cancel() {
        if (_status.value == Status.IDLE) return
        token += 1
        runCatching { recognizer?.cancel() }
        teardown()
        _status.value = Status.IDLE
    }

    private fun abort(token: Int, notice: String?): Boolean {
        if (notice != null) show(notice)
        if (token != this.token) return false
        _status.value = Status.IDLE
        return false
    }

    private fun listener(sessionToken: Int) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (sessionToken != token) return
            _status.value = Status.LISTENING
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            if (sessionToken != token) return
            // 典型说话大约 -2…10 dB；压到 0…1 给波形。
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            _level.value = maxOf(normalized, _level.value * 0.7f)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            if (sessionToken != token) return
            val message = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    "要用按住说话，得先在系统设置里允许录音。"
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                ->
                    "本机中文语音模型还没准备好，先用键盘吧。"
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> null
                else -> if (_transcript.value.isBlank()) "没听清，再说一次或用键盘。" else null
            }
            if (message != null) show(message)
            // 已经有字的话交给 stop 去收；还在 starting 就收回 idle。
            if (_status.value == Status.STARTING) {
                teardown()
                _status.value = Status.IDLE
            }
        }

        override fun onResults(results: Bundle?) {
            if (sessionToken != token) return
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = texts?.firstOrNull().orEmpty()
            if (best.isNotEmpty()) {
                finalized = best
                volatileText = ""
                _transcript.value = finalized
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (sessionToken != token) return
            val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = texts?.firstOrNull().orEmpty()
            if (best.isNotEmpty()) {
                volatileText = best
                _transcript.value = finalized.ifEmpty { volatileText }.let {
                    // 部分结果通常是整句最新猜测，不是追加。
                    if (finalized.isEmpty()) volatileText else finalized
                }
                if (finalized.isEmpty()) {
                    _transcript.value = volatileText
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun recognitionIntent(localeTag: String, vocabulary: List<String>): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // 本机优先；没装离线包时识别服务会报错，由 onError 提示用键盘。
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // API 33+ 的上下文偏置。对中文是否生效因厂商而异；无效也照样带上。
            if (vocabulary.isNotEmpty()) {
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_BIASING_STRINGS,
                    ArrayList(vocabulary.take(VoiceVocabulary.MAX_TERMS)),
                )
            }
        }

    private fun teardown() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        _transcript.value = ""
        finalized = ""
        volatileText = ""
        _level.value = 0f
    }

    private fun show(message: String) {
        _notice.value = message
        noticeRunnable?.let { mainHandler.removeCallbacks(it) }
        val clear = Runnable { _notice.value = null }
        noticeRunnable = clear
        mainHandler.postDelayed(clear, NOTICE_DURATION_MS)
    }

    /**
     * 只认中文。系统语言在这里不是判据——界面是中文的，词表也是中文的。
     */
    private fun resolveChineseLocale(): Locale? {
        val preferred = Locale.getDefault().let { listOf(it) } +
            listOf(Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE, Locale.CHINA, Locale.TAIWAN)
        for (candidate in preferred) {
            if (candidate.language == "zh") return candidate
        }
        return Locale.SIMPLIFIED_CHINESE
    }

    companion object {
        private const val NOTICE_DURATION_MS = 5_000L

        @Volatile
        private var instance: VoiceDictation? = null

        fun shared(context: Context): VoiceDictation {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: VoiceDictation(context.applicationContext).also {
                    it.refresh()
                    instance = it
                }
            }
        }
    }
}
