package com.pinapia.vana.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 品牌色板:从 app 图标那朵蓝云取色,再往低饱和、低对比方向压一档。
 *
 * 这是个健康 app,用户打开它的时候多半不太舒服。色板的目标是「柔和、不躁动」:
 * - 主色 [BrandBlue] 比图标本身灰一点,只给按钮、光标、少量强调;
 * - 背景不是纯白 / 纯黑,是带一点蓝灰的米白和深海军蓝,眼睛不累;
 * - 文字用深板岩色而不是 #000,深色模式的浅字不到 #FFF;
 * - 各种容器色都是很淡的蓝灰粉彩,层级靠明度差,不靠色相跳;
 * - 错误色压成偏珊瑚的红,提示归提示,不像警报。
 *
 * 浅色 primary 对白底约 4.8:1,深色 primary 对底色约 8:1,正文对比都过 AA。
 */
val BrandBlue = Color(0xFF3570CF)

/** 「思考」能力标签用的薰衣草紫,两个模式共用,盖在 12% 透明底上。 */
val ReasoningLavender = Color(0xFF8F7FD9)

val FallbackLightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE8FB),
    onPrimaryContainer = Color(0xFF123A78),
    inversePrimary = Color(0xFFA8C6F5),
    secondary = Color(0xFF5A6E8C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4ECF8),
    onSecondaryContainer = Color(0xFF1C2C45),
    tertiary = Color(0xFF4F7F79),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD5EEE9),
    onTertiaryContainer = Color(0xFF0B2F2B),
    error = Color(0xFFB8433F),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFADCDA),
    onErrorContainer = Color(0xFF4A0F0F),
    background = Color(0xFFF7F9FD),
    onBackground = Color(0xFF1E2635),
    surface = Color(0xFFF7F9FD),
    onSurface = Color(0xFF1E2635),
    surfaceVariant = Color(0xFFE6EDF8),
    onSurfaceVariant = Color(0xFF4B5565),
    surfaceTint = BrandBlue,
    inverseSurface = Color(0xFF2C3442),
    inverseOnSurface = Color(0xFFEEF2F8),
    outline = Color(0xFF7A8598),
    outlineVariant = Color(0xFFCBD5E3),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF7F9FD),
    surfaceDim = Color(0xFFD9E0EA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5FB),
    surfaceContainer = Color(0xFFEBF0F8),
    surfaceContainerHigh = Color(0xFFE4EAF4),
    surfaceContainerHighest = Color(0xFFDEE5F0),
)

val FallbackDarkColors = darkColorScheme(
    primary = Color(0xFF9DBFF5),
    onPrimary = Color(0xFF0B2E66),
    primaryContainer = Color(0xFF2B5399),
    onPrimaryContainer = Color(0xFFDCE8FB),
    inversePrimary = BrandBlue,
    secondary = Color(0xFFB3C2DA),
    onSecondary = Color(0xFF1F2E45),
    secondaryContainer = Color(0xFF34455E),
    onSecondaryContainer = Color(0xFFE4ECF8),
    tertiary = Color(0xFFA6D3CB),
    onTertiary = Color(0xFF0F3833),
    tertiaryContainer = Color(0xFF2C5651),
    onTertiaryContainer = Color(0xFFD5EEE9),
    error = Color(0xFFF2A9A3),
    onError = Color(0xFF5A1414),
    errorContainer = Color(0xFF7E2A28),
    onErrorContainer = Color(0xFFFADCDA),
    background = Color(0xFF141A24),
    onBackground = Color(0xFFDDE4EE),
    surface = Color(0xFF141A24),
    onSurface = Color(0xFFDDE4EE),
    surfaceVariant = Color(0xFF2A3444),
    onSurfaceVariant = Color(0xFFB6C0D0),
    surfaceTint = Color(0xFF9DBFF5),
    inverseSurface = Color(0xFFDDE4EE),
    inverseOnSurface = Color(0xFF2C3442),
    outline = Color(0xFF8C97A9),
    outlineVariant = Color(0xFF3A4657),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3A4453),
    surfaceDim = Color(0xFF141A24),
    surfaceContainerLowest = Color(0xFF0F141C),
    surfaceContainerLow = Color(0xFF1A212C),
    surfaceContainer = Color(0xFF1E2632),
    surfaceContainerHigh = Color(0xFF28313E),
    surfaceContainerHighest = Color(0xFF323C4A),
)
