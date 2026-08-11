package com.pinapia.vana.ui.theme

import androidx.compose.material3.Typography

/**
 * 用 Material3 的默认字阶,不自己造一套。
 *
 * 这个 app 的用户里有相当一部分是把系统字号调大了的人——默认字阶跟着系统缩放走,
 * 自己写死 sp 值的那一刻就把这件事弄坏了。要改也只改 lineHeight 和字重,不动尺寸。
 */
val VanaTypography = Typography()
