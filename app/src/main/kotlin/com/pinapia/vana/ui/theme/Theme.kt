package com.pinapia.vana.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 默认走 [FallbackLightColors] / [FallbackDarkColors] 这套品牌色板,**不跟壁纸取色**。
 * 图标是那朵蓝云,界面就该是同一个蓝;Material You 会把它换成随便哪个壁纸的颜色,
 * 而且饱和度经常比我们想要的高得多——「柔和」这件事就守不住了。
 *
 * [dynamicColor] 留着是给以后设置里加开关用的。真开的话只在 API 31+ 有系统资源;
 * 再低的版本硬调 [dynamicLightColorScheme] 会 Resources$NotFoundException 崩掉——
 * OnePlus 6 (API 30) 上就是这么挂的。
 */
@Composable
fun VanaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
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
        shapes = VanaShapes,
        content = content,
    )
}
