package com.pinapia.vana.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Material You 动态色只在 API 31+ 有系统资源。再低的版本硬调 [dynamicLightColorScheme]
 * 会直接 Resources$NotFoundException 崩掉——OnePlus 6 (API 30) 上就是这么挂的。
 *
 * 关掉 dynamic / 系统不够时退回 [FallbackLightColors] / [FallbackDarkColors]。
 */
@Composable
fun VanaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        useDynamic && darkTheme -> dynamicDarkColorScheme(context)
        useDynamic -> dynamicLightColorScheme(context)
        darkTheme -> FallbackDarkColors
        else -> FallbackLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VanaTypography,
        content = content,
    )
}
