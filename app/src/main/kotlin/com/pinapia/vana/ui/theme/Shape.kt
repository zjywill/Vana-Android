package com.pinapia.vana.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 比 Material3 默认每档大一号的圆角。卡片、气泡、建议条都走 `MaterialTheme.shapes`,
 * 圆一点整个界面就软一点;别在页面里再各写各的 `RoundedCornerShape(12.dp)`。
 */
val VanaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
