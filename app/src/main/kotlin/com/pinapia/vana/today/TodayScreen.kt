package com.pinapia.vana.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pinapia.vana.tasks.AddGoalDialog
import com.pinapia.vana.tasks.AddReminderDialog
import com.pinapia.vana.tasks.NotificationBanner
import com.pinapia.vana.tasks.Task
import com.pinapia.vana.tasks.TaskActions
import com.pinapia.vana.tasks.TaskKind
import com.pinapia.vana.tasks.TasksEnvironment
import com.pinapia.vana.tasks.TasksTools
import com.pinapia.vana.tasks.goalProgress
import com.pinapia.vana.tasks.reminderLine
import com.pinapia.vana.ui.icons.VanaIcons
import com.pinapia.vana.ui.uiText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** 「最近完成」最多列几条。 */
private const val FINISHED_LIMIT = 15

/**
 * 「今天」:今天有什么要做、之后排着什么、在推进什么。对应 Muse 独立于对话的那几个标签。
 *
 * 以前「今天」是对话那一列里的一张卡(iOS 那边一天之内换了四个位置),哪个位置都不对:它说的是**现在**,
 * 而对话那一列是**发生过的事**,一条线性的时间线上没有它的位置——放上面,回头客一打开就被滚到底、看不见它;
 * 放在最新那条下面,它一直压在输入框上,新的回复又排到它下面去。所以拿出来成一页,和原来的任务页合在一起:
 * 那张卡里的东西本来大半就是从任务算出来的。
 *
 * 入口是主对话顶栏那颗带角标的按钮,不是底部导航栏:只有「对话」和「今天」两个地方时一条导航栏撑不起来,
 * 而且它会一直压在输入框底下。有了第三个同级的地方再说,这一页不用改。
 *
 * iOS 那一页最上面还有一节「现在」(健康状况那一行);Android 不读设备健康数据,没有那一行,也就没有那一节。
 *
 * 手动添加和模型工具走同一批上限([TaskActions] 对 [TasksTools]):两条路进来的东西在盘上长得一样。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    env: TasksEnvironment,
    /** 各插件贡献的那几行:主对话手里那份 [TodayFeed],顶栏角标数的也是它。 */
    cards: List<TodayCard>,
    /** 重拼一次:上次拼的时候还没到点的提醒,现在可能已经过点了。 */
    onRefresh: () -> Unit,
    /** 点了一行。任务详情、插件入口在这一页上面推;替他问一句要回到对话去发。 */
    onAction: (TodayAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val revision by env.store.revision.collectAsState()
    val tasks = remember(revision) { env.store.all() }
    var addMenu by remember { mutableStateOf(false) }
    var addingReminder by remember { mutableStateOf(false) }
    var addingGoal by remember { mutableStateOf(false) }
    var showFinished by remember { mutableStateOf(false) }

    // 打开这一页、从任务详情退回来、回到前台,都走这儿。
    LifecycleEventEffect(Lifecycle.Event.ON_START) { onRefresh() }

    val later = TodayCompute.later(cards, tasks)
    val goals = tasks.filter { it.kind == TaskKind.GOAL && it.isActive }
    val finished = tasks.filter { !it.isActive }.sortedByDescending { it.updatedAt }.take(FINISHED_LIMIT)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(uiText("今天", "Today"))
                        Text(
                            dateLabel(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(VanaIcons.ArrowLeft, contentDescription = uiText("返回", "Back"))
                    }
                },
                actions = {
                    Column {
                        IconButton(onClick = { addMenu = true }) {
                            Icon(VanaIcons.Plus, contentDescription = uiText("添加", "Add"))
                        }
                        DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(uiText("新提醒", "New reminder")) },
                                onClick = { addMenu = false; addingReminder = true },
                            )
                            DropdownMenuItem(
                                text = { Text(uiText("新目标", "New goal")) },
                                onClick = { addMenu = false; addingGoal = true },
                            )
                        }
                    }
                },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 16.dp),
        ) {
            item(key = "notifications") {
                NotificationBanner(hasReminders = tasks.any { it.kind == TaskKind.REMINDER && it.isActive })
            }

            // 只剩「最近完成」的一页看不出提醒和目标该从哪儿来,说一句。
            if (cards.isEmpty() && later.isEmpty() && goals.isEmpty()) {
                item(key = "empty") {
                    Text(
                        uiText("今天没有要做的事", "Nothing to do today"),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(
                        uiText(
                            "在对话里说「明天早上八点提醒我带伞」，或者点右上角自己加一条。",
                            "Say “Remind me to bring an umbrella at 8 tomorrow morning” in the chat, or tap + at the top right to add one yourself.",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            if (cards.isNotEmpty()) {
                item(key = "due") {
                    TodaySection(uiText("今天要做", "To do today")) {
                        cards.forEachIndexed { index, card ->
                            if (index > 0) RowDivider()
                            TodayRow(
                                icon = card.kind.icon(),
                                tint = card.kind.tint(),
                                caption = card.kind.label(),
                                title = card.title,
                                detail = card.body,
                                onClick = { onAction(card.action) },
                                // 今天到点的提醒可以当场划掉,和「之后」那几条一样。
                                onComplete = card.taskId?.let { id -> { TaskActions.complete(env, id) } },
                            )
                        }
                    }
                }
            }

            if (later.isNotEmpty()) {
                item(key = "later") {
                    TodaySection(uiText("之后", "Later")) {
                        later.forEachIndexed { index, task ->
                            if (index > 0) RowDivider()
                            TaskRow(task, env) { onAction(TodayAction.OpenTask(task.id)) }
                        }
                    }
                }
            }

            if (goals.isNotEmpty()) {
                item(key = "goals") {
                    TodaySection(
                        title = uiText("目标", "Goals"),
                        footer = uiText(
                            "进行中的目标最多 ${TasksTools.MAX_ACTIVE_GOALS} 个。Vana 回答时会把它们记在心上。",
                            "Up to ${TasksTools.MAX_ACTIVE_GOALS} active goals. Vana keeps them in mind when answering.",
                        ),
                    ) {
                        goals.forEachIndexed { index, task ->
                            if (index > 0) RowDivider()
                            TaskRow(task, env) { onAction(TodayAction.OpenTask(task.id)) }
                        }
                    }
                }
            }

            if (finished.isNotEmpty()) {
                item(key = "finished") {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        TextButton(onClick = { showFinished = !showFinished }) {
                            Text(
                                if (showFinished) uiText("收起最近完成的", "Hide recent") else uiText("最近完成的（${finished.size}）", "Recently finished (${finished.size})"),
                            )
                        }
                        if (showFinished) {
                            TodayGroup {
                                finished.forEachIndexed { index, task ->
                                    if (index > 0) RowDivider()
                                    TaskRow(task, env) { onAction(TodayAction.OpenTask(task.id)) }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "footer") {
                // 提醒走的是非精确闹钟(不申请精确闹钟的权限),界面上要照实说可能晚几分钟。
                Text(
                    uiText(
                        "这一页由本机数据拼出来，不调用模型。提醒到点只在本机发一条通知，也不花钱；系统为了省电，可能比设定的时间晚几分钟。",
                        "This page is put together from data on this device—no model call. Reminders only send a local notification when due, at no cost; to save battery, Android may deliver them a few minutes late.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            }
        }
    }

    if (addingReminder) {
        AddReminderDialog(env = env, onDismiss = { addingReminder = false })
    }
    if (addingGoal) {
        AddGoalDialog(env = env, onDismiss = { addingGoal = false })
    }
}

/** 页头底下那行日期:「9月30日 星期三」。 */
@Composable
private fun dateLabel(): String {
    val date = LocalDate.now()
    return uiText(
        "${date.monthValue}月${date.dayOfMonth}日 " + date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE),
        date.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.ENGLISH)),
    )
}

/** 一节:标题、一组圆角的行、可选的一句脚注。 */
@Composable
private fun TodaySection(title: String, footer: String? = null, rows: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
        )
        TodayGroup(rows)
        footer?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }
    }
}

@Composable
private fun TodayGroup(rows: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp), content = rows)
    }
}

/** 分隔线从文字那一列起,不从图标底下起。 */
@Composable
private fun RowDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = 54.dp))
}

/** 一条任务:之后的提醒、进行中的目标、最近完成的。和插件贡献的那几行长一个样子。 */
@Composable
private fun TaskRow(task: Task, env: TasksEnvironment, onClick: () -> Unit) {
    val isReminder = task.kind == TaskKind.REMINDER
    TodayRow(
        icon = when {
            !task.isActive -> VanaIcons.Check
            isReminder -> VanaIcons.Bell
            else -> VanaIcons.Flag
        },
        tint = when {
            !task.isActive -> MaterialTheme.colorScheme.outline
            isReminder -> ReminderOrange
            else -> GoalGreen
        },
        caption = null,
        title = task.title,
        detail = when {
            !task.isActive -> CoreToday.statusLabel(task.status)
            isReminder -> reminderLine(task, env)
            else -> goalProgress(task)
        },
        muted = !task.isActive,
        onClick = onClick,
        onComplete = if (task.isActive && isReminder) {
            { TaskActions.complete(env, task.id) }
        } else {
            null
        },
    )
}

/**
 * 「今天」页上的一行:左边一颗按类别上色的圆,右边「类别」小字、标题、一行进展。插件贡献的和任务表里的
 * 长一个样子——同一页里两种排法,读起来像是拼起来的两页。能当场做完的(提醒)右边是一颗「完成」,其余是箭头。
 */
@Composable
private fun TodayRow(
    icon: ImageVector,
    tint: Color,
    caption: String?,
    title: String,
    detail: String?,
    onClick: () -> Unit,
    muted: Boolean = false,
    onComplete: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = if (onComplete != null) 4.dp else 14.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(tint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            caption?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.takeIf { it.isNotEmpty() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onComplete != null) {
            IconButton(onClick = onComplete) {
                Icon(VanaIcons.CheckCircle, contentDescription = uiText("完成", "Done"))
            }
        } else {
            Icon(
                VanaIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 和 iOS 同一套颜色:过点红、提醒橙、目标绿、回头看青、用药紫。 */
private val OverdueRed = Color(0xFFFF3B30)
private val ReminderOrange = Color(0xFFFF9500)
private val GoalGreen = Color(0xFF34C759)
private val FollowUpTeal = Color(0xFF30B0C7)
private val MedicationPurple = Color(0xFFAF52DE)

private fun TodayKind.tint(): Color = when (this) {
    TodayKind.OVERDUE -> OverdueRed
    TodayKind.REMINDER -> ReminderOrange
    TodayKind.FOLLOW_UP -> FollowUpTeal
    TodayKind.MEDICATION -> MedicationPurple
}

/** 提醒和「之后」那几条同一颗铃,别的按它是什么。 */
private fun TodayKind.icon(): ImageVector = when (this) {
    TodayKind.OVERDUE, TodayKind.REMINDER -> VanaIcons.Bell
    TodayKind.FOLLOW_UP -> VanaIcons.Clock
    TodayKind.MEDICATION -> VanaIcons.Beaker
}

@Composable
private fun TodayKind.label(): String = when (this) {
    TodayKind.OVERDUE -> uiText("已过点", "Overdue")
    TodayKind.REMINDER -> uiText("提醒事项", "Reminder")
    TodayKind.FOLLOW_UP -> uiText("回头看", "Follow-up")
    TodayKind.MEDICATION -> uiText("用药回访", "Medication check-in")
}
