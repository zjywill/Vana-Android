package com.pinapia.vana.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API key 只进这里。对应 iOS 的 Keychain。
 */
class SecureKeyStore(context: Context) {
    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun get(account: String): String? =
        prefs.getString(account, null)?.takeIf { it.isNotBlank() }

    fun set(account: String, value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove(account) else putString(account, value.trim())
        }.apply()
    }

    var apiKey: String?
        get() = get(API_KEY_ACCOUNT)
        set(value) = set(API_KEY_ACCOUNT, value)

    var serperApiKey: String?
        get() = get(SERPER_KEY_ACCOUNT)
        set(value) = set(SERPER_KEY_ACCOUNT, value)

    companion object {
        const val FILE_NAME = "vana_secure_prefs"
        const val API_KEY_ACCOUNT = "aikit-api-key"
        const val SERPER_KEY_ACCOUNT = "serper-api-key"
    }
}
