package com.pinapia.vana.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.pinapia.vana.ui.icons.VanaIcons
import com.pinapia.vana.ui.uiText

/**
 * 聊天顶上那条「今天」。折起来只占一行(一句话概括),展开是几张卡。
 * 没有卡片就整条不出现——一个永远空着的「今天」只会占地方。
 */
@Composable
fun TodayStrip(
    cards: List<TodayCard>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAction: (TodayAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cards.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(uiText("今天", "Today"), style = MaterialTheme.typography.titleSmall)
                Text(
                    " · " + summary(cards),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Icon(
                    VanaIcons.ChevronDown,
                    contentDescription = if (expanded) uiText("收起", "Collapse") else uiText("展开", "Expand"),
                    modifier = Modifier.graphicsLayer { rotationZ = if (expanded) 180f else 0f },
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for (card in cards) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAction(card.action) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(card.title, style = MaterialTheme.typography.bodyLarge)
                            card.body?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun summary(cards: List<TodayCard>): String {
    val needsYou = cards.count { it.priority == TodayPriority.NEEDS_YOU }
    val reminders = cards.count {
        it.priority == TodayPriority.OVERDUE_REMINDER || it.priority == TodayPriority.DUE_TODAY_REMINDER
    }
    val rest = cards.size - needsYou - reminders
    return buildList {
        if (needsYou > 0) add(uiText("$needsYou 件等你确认", "$needsYou waiting for you"))
        if (reminders > 0) add(uiText("$reminders 条提醒", if (reminders == 1) "1 reminder" else "$reminders reminders"))
        if (rest > 0) add(uiText("$rest 件其他", "$rest other"))
    }.joinToString(" · ")
}
