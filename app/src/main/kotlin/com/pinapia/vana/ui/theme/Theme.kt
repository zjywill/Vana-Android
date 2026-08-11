package com.pinapia.vana.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 默认跟着系统取色(Material You)。iOS 那边整套界面是系统原生观感,Android 上的对等选择就是
 * 让配色跟着用户自己的壁纸走,而不是硬写一套品牌色——一个每天都要打开的健康 app,长得像
 * 这台手机上的其它 app 比长得像我们的 logo 重要。
 *
 * 关掉 dynamic 时退回 [FallbackLightColors] / [FallbackDarkColors]。
 */
@Composable
fun VanaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> FallbackDarkColors
        else -> FallbackLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VanaTypography,
        content = content,
    )
}
