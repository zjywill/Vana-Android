package com.pinapia.vana.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val Seed = Color(0xFF0E7C66)

/** 拿不到系统取色时的兜底。真正的色板等视觉定了再补,这里只保证到处都有个能用的值。 */
val FallbackLightColors = lightColorScheme(
    primary = Seed,
)

val FallbackDarkColors = darkColorScheme(
    primary = Color(0xFF6FD9BE),
)
