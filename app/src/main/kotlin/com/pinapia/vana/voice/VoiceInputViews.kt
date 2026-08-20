package com.pinapia.vana.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import com.pinapia.vana.ui.uiText
import kotlin.math.hypot

/**
 * 输入框旁边那颗「按住说话」。
 *
 * **按住，不是点一下切换。** 松手就结束；手指划开就是取消，和微信一样。
 */
@Composable
fun VoiceInputButton(
    isListening: Boolean,
    isCancelling: Boolean,
    enabled: Boolean,
    onPress: () -> Unit,
    onRelease: (cancelled: Boolean) -> Unit,
    onCancellingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    var isPressing by remember { mutableStateOf(false) }
    val holdToTalkDescription = uiText("按住说话", "Hold to talk")

    val background = when {
        isCancelling -> MaterialTheme.colorScheme.error
        isListening -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val tint = when {
        isListening || isCancelling -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .size(44.dp)
            .semantics { contentDescription = holdToTalkDescription }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val origin = down.position
                        if (!isPressing) {
                            isPressing = true
                            ViewCompat.performHapticFeedback(
                                view,
                                HapticFeedbackConstantsCompat.KEYBOARD_TAP,
                            )
                            onPress()
                        }
                        var cancelled = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            val distance = hypot(
                                change.position.x - origin.x,
                                change.position.y - origin.y,
                            )
                            val nowCancelling = distance > CANCEL_DISTANCE_PX
                            if (nowCancelling != cancelled) {
                                cancelled = nowCancelling
                                onCancellingChange(cancelled)
                                ViewCompat.performHapticFeedback(
                                    view,
                                    HapticFeedbackConstantsCompat.CLOCK_TICK,
                                )
                            }
                            if (change.changedToUp()) {
                                isPressing = false
                                onCancellingChange(false)
                                onRelease(cancelled)
                                break
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .scale(if (isListening) 1.12f else 1f)
                .clip(CircleShape)
                .background(background),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isCancelling) Icons.Default.Close else Icons.Default.Mic,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
fun VoiceLevelStrip(
    level: Float,
    isCancelling: Boolean,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val history = remember { mutableStateListOf(*Array(BAR_COUNT) { 0f }) }
    LaunchedEffect(level, visible) {
        if (!visible) return@LaunchedEffect
        if (history.isNotEmpty()) history.removeAt(0)
        history.add(level)
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(24.dp),
            ) {
                history.forEach { value ->
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height((4 + value * 20).dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (isCancelling) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            ),
                    )
                }
            }
            Text(
                if (isCancelling) {
                    uiText("松开取消", "Release to cancel")
                } else {
                    uiText("松开填进输入框，不会直接发送", "Release to insert text without sending")
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (isCancelling) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
        }
    }
}

private const val CANCEL_DISTANCE_PX = 180f
private const val BAR_COUNT = 20
