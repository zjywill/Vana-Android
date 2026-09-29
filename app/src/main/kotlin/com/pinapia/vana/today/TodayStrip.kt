package com.pinapia.vana.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pinapia.vana.ui.icons.VanaIcons
import com.pinapia.vana.ui.uiText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** 一张卡里最多列几件。再多就是把对话写成一份日报,剩下的在任务页里。 */
private const val MAX_ROWS = 5

/**
 * 「今天」:一张普通的卡。头上一行「今天 · 日期」,下面一件事一行,图标按类别上色。
 *
 * **不折叠**——它每次打开只出现一次,就排在最新的位置上(`ChatViewModel.todayAfterId`),没有需要收起来腾
 * 地方的时候。它是对话那一列里的一项,**不悬浮**:浮在顶上时对话从它底下穿过去,两层字叠在一起。也只是屏幕上
 * 的一张卡,不进线程、不进给模型的上下文。没有卡片就整张不出现。
 */
@Composable
fun TodayStrip(
    cards: List<TodayCard>,
    onAction: (TodayAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cards.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(bottom = 6.dp)) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(uiText("今天", "Today"), style = MaterialTheme.typography.titleSmall)
                Text(
                    "  " + LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val shown = cards.take(MAX_ROWS)
            shown.forEachIndexed { index, card ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                TodayRow(card) { onAction(card.action) }
            }
            if (cards.size > MAX_ROWS) {
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                Text(
                    uiText("还有 ${cards.size - MAX_ROWS} 件，去任务页看", "${cards.size - MAX_ROWS} more on the Tasks page"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAction(TodayAction.OpenTasks) }
                        .padding(start = 56.dp, top = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun TodayRow(card: TodayCard, onClick: () -> Unit) {
    val tint = card.kind.tint()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(tint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(card.kind.icon(), contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                card.kind.label(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
            Text(
                card.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            card.body?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            VanaIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp).size(16.dp),
        )
    }
}

/** 和 iOS 同一套颜色:过点红、到点橙、等你确认蓝、后台靛、目标绿、回头看青、用药紫。 */
private fun TodayKind.tint(): Color = when (this) {
    TodayKind.OVERDUE -> Color(0xFFFF3B30)
    TodayKind.REMINDER -> Color(0xFFFF9500)
    TodayKind.NEEDS_YOU -> Color(0xFF007AFF)
    TodayKind.RUNNING -> Color(0xFF5856D6)
    TodayKind.GOAL -> Color(0xFF34C759)
    TodayKind.FOLLOW_UP -> Color(0xFF30B0C7)
    TodayKind.MEDICATION -> Color(0xFFAF52DE)
}

private fun TodayKind.icon(): ImageVector = when (this) {
    TodayKind.OVERDUE, TodayKind.REMINDER, TodayKind.FOLLOW_UP -> VanaIcons.Clock
    TodayKind.NEEDS_YOU, TodayKind.RUNNING, TodayKind.GOAL -> VanaIcons.CheckCircle
    TodayKind.MEDICATION -> VanaIcons.Beaker
}

@Composable
private fun TodayKind.label(): String = when (this) {
    TodayKind.OVERDUE -> uiText("已过点", "Overdue")
    TodayKind.REMINDER -> uiText("提醒事项", "Reminder")
    TodayKind.NEEDS_YOU -> uiText("等你确认", "Waiting for you")
    TodayKind.RUNNING -> uiText("后台在做", "Background task")
    TodayKind.GOAL -> uiText("在推进的目标", "Goal")
    TodayKind.FOLLOW_UP -> uiText("回头看", "Follow-up")
    TodayKind.MEDICATION -> uiText("用药回访", "Medication check-in")
}
