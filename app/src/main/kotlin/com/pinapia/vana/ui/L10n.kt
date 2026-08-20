package com.pinapia.vana.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

object L10n {
    fun text(zhHans: String, english: String, locale: Locale = Locale.getDefault()): String =
        if (locale.language.equals("en", ignoreCase = true)) english else zhHans

    fun text(context: Context, zhHans: String, english: String): String {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        return text(zhHans, english, locale)
    }

    fun replyLanguage(locale: Locale = Locale.getDefault()): String =
        if (locale.language.equals("en", ignoreCase = true)) "English" else "简体中文"
}

@Composable
fun uiText(zhHans: String, english: String): String {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    return L10n.text(zhHans, english, locale)
}
