package com.pinapia.vana.settings

import android.content.Context
import android.content.SharedPreferences
import com.pinapia.vana.vision.PhotoImagePolicy

/**
 * 云端引擎设置。provider / model 不是秘密,走 SharedPreferences;API key 只进 [SecureKeyStore]。
 */
class EngineSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var providerId: String
        get() = prefs.getString(PROVIDER_KEY, DEFAULT_PROVIDER) ?: DEFAULT_PROVIDER
        set(value) = prefs.edit().putString(PROVIDER_KEY, value).apply()

    var model: String
        get() = prefs.getString(MODEL_KEY, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(MODEL_KEY, value).apply()

    var persona: AssistantPersona
        get() = AssistantPersona.fromRaw(prefs.getString(PERSONA_KEY, null))
        set(value) = prefs.edit().putString(PERSONA_KEY, value.raw).apply()

    var photoImagePolicy: PhotoImagePolicy
        get() = PhotoImagePolicy.fromRaw(prefs.getString(PHOTO_POLICY_KEY, null))
        set(value) = prefs.edit().putString(PHOTO_POLICY_KEY, value.raw).apply()

    var thinkingEnabled: Boolean
        get() = if (prefs.contains(THINKING_KEY)) prefs.getBoolean(THINKING_KEY, true) else true
        set(value) = prefs.edit().putBoolean(THINKING_KEY, value).apply()

    var memoryEnabled: Boolean
        get() = if (prefs.contains(MEMORY_KEY)) prefs.getBoolean(MEMORY_KEY, true) else true
        set(value) = prefs.edit().putBoolean(MEMORY_KEY, value).apply()

    var medicationsEnabled: Boolean
        get() = if (prefs.contains(MEDICATIONS_KEY)) prefs.getBoolean(MEDICATIONS_KEY, true) else true
        set(value) = prefs.edit().putBoolean(MEDICATIONS_KEY, value).apply()

    var measurementsEnabled: Boolean
        get() = if (prefs.contains(MEASUREMENTS_KEY)) prefs.getBoolean(MEASUREMENTS_KEY, true) else true
        set(value) = prefs.edit().putBoolean(MEASUREMENTS_KEY, value).apply()

    var checkInsEnabled: Boolean
        get() = prefs.getBoolean(CHECKINS_KEY, false)
        set(value) = prefs.edit().putBoolean(CHECKINS_KEY, value).apply()

    var morningCheckInHour: Int
        get() = prefs.getInt(MORNING_HOUR_KEY, DEFAULT_MORNING_HOUR)
        set(value) = prefs.edit().putInt(MORNING_HOUR_KEY, value.coerceIn(5, 11)).apply()

    var eveningCheckInHour: Int
        get() = prefs.getInt(EVENING_HOUR_KEY, DEFAULT_EVENING_HOUR)
        set(value) = prefs.edit().putInt(EVENING_HOUR_KEY, value.coerceIn(18, 23)).apply()

    var hasAcceptedDataUseNotice: Boolean
        get() = prefs.getBoolean(DATA_USE_KEY, false)
        set(value) = prefs.edit().putBoolean(DATA_USE_KEY, value).apply()

    /**
     * 「把数据发给某一家第三方模型服务」这件事,按 provider 记一次同意。
     *
     * iOS 2026-08-29 被 5.1.2(i) 判的:发给第三方 AI 服务之前必须点名对方、征得同意,
     * 「你配置的模型服务」这个代词不算。第一次真的要向某一家发送之前弹一次点名确认
     * (ChatScreen 那个 dialog),同意了记在这里;换 provider 会再问,同一家只问一次。
     * 这道闸挡的是每一条会出设备的路(聊天、后台派生、抽记忆、用药说明),不只聊天。
     * 设备级、只增不撤——要反悔就删 key 或换 provider,那两下本来就把发送整个停了。
     */
    fun hasProviderConsent(providerId: String): Boolean {
        val trimmed = providerId.trim()
        if (trimmed.isEmpty()) return false
        return prefs.getStringSet(CONSENTED_PROVIDERS_KEY, emptySet())
            .orEmpty()
            .contains(trimmed)
    }

    fun recordProviderConsent(providerId: String) {
        val trimmed = providerId.trim()
        if (trimmed.isEmpty() || hasProviderConsent(trimmed)) return
        // getStringSet 返回的集合不许原地改,必须拷一份再 put。
        val next = prefs.getStringSet(CONSENTED_PROVIDERS_KEY, emptySet()).orEmpty().toMutableSet()
        next += trimmed
        prefs.edit().putStringSet(CONSENTED_PROVIDERS_KEY, next).apply()
    }

    fun isConfigured(secureKeyStore: SecureKeyStore): Boolean {
        val key = ApiKeyNormalizer.normalize(secureKeyStore.apiKey)
        return key.isValid && providerId.isNotBlank() && model.isNotBlank()
    }

    fun modelSupportsVision(): Boolean =
        CloudCatalog.model(model, providerId)?.supportsVision == true

    companion object {
        const val PREFS_NAME = "vana_engine_settings"
        const val PROVIDER_KEY = "providerId"
        const val MODEL_KEY = "model"
        const val PERSONA_KEY = "assistantPersona"
        const val PHOTO_POLICY_KEY = "photoImagePolicy"
        const val THINKING_KEY = "thinkingEnabled"
        const val MEMORY_KEY = "memoryEnabled"
        const val MEDICATIONS_KEY = "medicationsEnabled"
        const val MEASUREMENTS_KEY = "measurementsEnabled"
        const val CHECKINS_KEY = "checkInsEnabled"
        const val MORNING_HOUR_KEY = "morningCheckInHour"
        const val EVENING_HOUR_KEY = "eveningCheckInHour"
        const val DATA_USE_KEY = "hasAcceptedDataUseNotice"
        const val CONSENTED_PROVIDERS_KEY = "consentedProviderIds"

        const val DEFAULT_PROVIDER = "deepseek"
        const val DEFAULT_MODEL = "deepseek-v4-flash"
        const val DEFAULT_MORNING_HOUR = 8
        const val DEFAULT_EVENING_HOUR = 21
    }
}
