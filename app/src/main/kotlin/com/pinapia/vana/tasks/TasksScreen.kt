package com.pinapia.vana.tasks

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pinapia.vana.today.CoreToday
import com.pinapia.vana.ui.L10n
import com.pinapia.vana.ui.icons.VanaIcons
import com.pinapia.vana.ui.uiText
import java.time.Instant as JInstant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.datetime.Instant

private fun english(): Boolean = Locale.getDefault().language.equals("en", ignoreCase = true)

private fun Instant.local(zone: ZoneId): LocalDateTime =
    LocalDateTime.ofInstant(JInstant.ofEpochMilli(toEpochMilliseconds()), zone)

private fun LocalDateTime.toKotlin(zone: ZoneId): Instant =
    Instant.fromEpochMilliseconds(atZone(zone).toInstant().toEpochMilli())

/** 提醒一行的第二行字:「今天 20:00 · 每天」。 */
fun reminderLine(task: Task, env: TasksEnvironment): String {
    val due = task.dueAt ?: return ""
    val en = english()
    val when_ = ReminderRules.describe(due, env.now(), env.zone, en)
    val every = ReminderRules.describeRepeat(task.repeat, due, env.zone, en)
    return if (every.isEmpty()) when_ else "$when_ · $every"
}

/**
 * 「任务」页:进行中的提醒、目标,加上最近做完的。
 * 提醒只发本地通知、不联网;目标是他自己长期在做的事。以前还有「后台任务」一节,2026-09-30 连同子 agent 撤掉了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    env: TasksEnvironment,
    onOpenTask: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val revision by env.store.revision.collectAsState()
    val tasks = remember(revision) { env.store.all() }
    var addMenu by remember { mutableStateOf(false) }
    var addingReminder by remember { mutableStateOf(false) }
    var addingGoal by remember { mutableStateOf(false) }
    var showFinished by remember { mutableStateOf(false) }

    val reminders = tasks.filter { it.kind == TaskKind.REMINDER && it.isActive }.sortedBy { it.dueAt }
    val goals = tasks.filter { it.kind == TaskKind.GOAL && it.isActive }
    val finished = tasks.filter { !it.isActive }.sortedByDescending { it.updatedAt }.take(FINISHED_LIMIT)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(uiText("任务", "Tasks")) },
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { NotificationBanner(hasReminders = reminders.isNotEmpty()) }

            if (reminders.isEmpty() && goals.isEmpty()) {
                item {
                    Text(
                        uiText("还没有要做的事", "Nothing on your list"),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        uiText(
                            "在对话里说「明天早上八点提醒我带伞」或「我想备战半马」，Vana 会替你记在这里；也可以点右上角的加号自己添加。",
                            "Say “remind me to bring an umbrella tomorrow at 8” or “I'm training for a half marathon” in the chat, or tap + to add one yourself.",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                    )
                }
            }

            if (reminders.isNotEmpty()) {
                item { SectionTitle(uiText("提醒", "Reminders")) }
                items(reminders, key = { "reminder-${it.id}" }) { task ->
                    TaskRow(
                        title = task.title,
                        subtitle = reminderLine(task, env),
                        onClick = { onOpenTask(task.id) },
                        trailing = {
                            IconButton(onClick = { TaskActions.complete(env, task.id) }) {
                                Icon(VanaIcons.CheckCircle, contentDescription = uiText("完成", "Done"))
                            }
                        },
                    )
                }
            }

            if (goals.isNotEmpty()) {
                item { SectionTitle(uiText("目标", "Goals")) }
                items(goals, key = { "goal-${it.id}" }) { task ->
                    TaskRow(
                        title = task.title,
                        subtitle = if (task.plan.isEmpty()) {
                            uiText("还没有步骤", "No steps yet")
                        } else {
                            uiText(
                                "步骤 ${task.plan.count { it.done }}/${task.plan.size}",
                                "Steps ${task.plan.count { it.done }}/${task.plan.size}",
                            )
                        },
                        onClick = { onOpenTask(task.id) },
                    )
                }
            }

            if (finished.isNotEmpty()) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    TextButton(onClick = { showFinished = !showFinished }) {
                        Text(
                            if (showFinished) uiText("收起最近完成的", "Hide recent") else uiText("最近完成的（${finished.size}）", "Recently finished (${finished.size})"),
                        )
                    }
                }
                if (showFinished) {
                    items(finished, key = { "done-${it.id}" }) { task ->
                        TaskRow(
                            title = task.title,
                            subtitle = CoreToday.statusLabel(task.status),
                            muted = true,
                            onClick = { onOpenTask(task.id) },
                        )
                    }
                }
            }

            item {
                Text(
                    uiText(
                        "提醒只在本机发通知，不联网；系统为了省电，可能比设定的时间晚几分钟。",
                        "Reminders are local notifications only. To save battery, Android may deliver them a few minutes late.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
    }

    if (addingReminder) {
        AddReminderDialog(
            env = env,
            onDismiss = { addingReminder = false },
        )
    }
    if (addingGoal) {
        AddGoalDialog(
            env = env,
            onDismiss = { addingGoal = false },
        )
    }
}

private const val FINISHED_LIMIT = 15

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
    )
}

@Composable
private fun TaskRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    muted: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/** 通知被关着,提醒就是设了也看不到——要在他设提醒的地方就说清,而不是等它没响。 */
@Composable
private fun NotificationBanner(hasReminders: Boolean) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    if (enabled) return
    val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (hasReminders) uiText("通知是关着的，提醒到点你看不到", "Notifications are off, so you won't see reminders")
                else uiText("要收到提醒，需要允许通知", "Allow notifications to receive reminders"),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (canAsk) {
                TextButton(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text(uiText("允许通知", "Allow notifications"))
                }
            } else {
                Text(
                    uiText("请在系统设置里打开 Vana 的通知。", "Turn on Vana's notifications in system settings."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private enum class QuickTime { IN_HOUR, TONIGHT, TOMORROW_MORNING, CUSTOM }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddReminderDialog(env: TasksEnvironment, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var quick by remember { mutableStateOf(QuickTime.TONIGHT) }
    var custom by remember { mutableStateOf<LocalDateTime?>(null) }
    var repeat by remember { mutableStateOf(Repeat.NONE) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickingCustom by remember { mutableStateOf(false) }
    val zone = env.zone

    fun resolve(): Instant? {
        val now = env.now().local(zone)
        return when (quick) {
            QuickTime.IN_HOUR -> now.plusHours(1).toKotlin(zone)
            QuickTime.TONIGHT -> {
                val tonight = now.toLocalDate().atTime(20, 0)
                // 已经过了晚上八点就顺延到明晚,不然这个选项永远是「已过」。
                (if (tonight.isAfter(now.plusMinutes(1))) tonight else tonight.plusDays(1)).toKotlin(zone)
            }
            QuickTime.TOMORROW_MORNING -> now.toLocalDate().plusDays(1).atTime(9, 0).toKotlin(zone)
            QuickTime.CUSTOM -> custom?.toKotlin(zone)
        }
    }

    val preview = resolve()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiText("新提醒", "New reminder")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(uiText("提醒我……", "Remind me to…")) },
                    singleLine = false,
                )
                Text(uiText("什么时候", "When"), style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = quick == QuickTime.IN_HOUR, onClick = { quick = QuickTime.IN_HOUR }, label = { Text(uiText("1 小时后", "In 1 hour")) })
                        FilterChip(selected = quick == QuickTime.TONIGHT, onClick = { quick = QuickTime.TONIGHT }, label = { Text(uiText("今晚 8 点", "Tonight 8pm")) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = quick == QuickTime.TOMORROW_MORNING, onClick = { quick = QuickTime.TOMORROW_MORNING }, label = { Text(uiText("明早 9 点", "Tomorrow 9am")) })
                        FilterChip(
                            selected = quick == QuickTime.CUSTOM,
                            onClick = { pickingCustom = true },
                            label = { Text(custom?.let { ReminderRules.describe(it.toKotlin(zone), env.now(), zone, english()) } ?: uiText("自己选", "Pick a time")) },
                        )
                    }
                }
                preview?.let {
                    Text(
                        ReminderRules.describe(it, env.now(), zone, english()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(uiText("重复", "Repeat"), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = repeat == Repeat.NONE, onClick = { repeat = Repeat.NONE }, label = { Text(uiText("不重复", "Once")) })
                    FilterChip(selected = repeat == Repeat.DAILY, onClick = { repeat = Repeat.DAILY }, label = { Text(uiText("每天", "Daily")) })
                    FilterChip(selected = repeat == Repeat.WEEKLY, onClick = { repeat = Repeat.WEEKLY }, label = { Text(uiText("每周", "Weekly")) })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val due = resolve()
                if (due == null) {
                    error = L10n.text("选一个时间", "Pick a time")
                    return@TextButton
                }
                val problem = TaskActions.addReminder(env, title, due, repeat)
                if (problem == null) onDismiss() else error = problem
            }) { Text(uiText("添加", "Add")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiText("取消", "Cancel")) } },
    )

    if (pickingCustom) {
        DateTimePickerDialog(
            initial = custom ?: env.now().local(zone).plusHours(2).withMinute(0).withSecond(0).withNano(0),
            zone = zone,
            onDismiss = { pickingCustom = false },
            onPicked = {
                custom = it
                quick = QuickTime.CUSTOM
                pickingCustom = false
            },
        )
    }
}

/** 先选日期,再选时间。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimePickerDialog(
    initial: LocalDateTime,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onPicked: (LocalDateTime) -> Unit,
) {
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
    if (pickedDate == null) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial.toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        AlertDialog(
            onDismissRequest = onDismiss,
            text = { DatePicker(state = state) },
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        state.selectedDateMillis?.let {
                            pickedDate = JInstant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        }
                    },
                ) { Text(uiText("下一步", "Next")) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(uiText("取消", "Cancel")) } },
        )
    } else {
        val time = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = onDismiss,
            text = { TimePicker(state = time) },
            confirmButton = {
                TextButton(onClick = {
                    onPicked(LocalDateTime.of(pickedDate!!, LocalTime.of(time.hour, time.minute)))
                }) { Text(uiText("确定", "OK")) }
            },
            dismissButton = { TextButton(onClick = { pickedDate = null }) { Text(uiText("上一步", "Back")) } },
        )
    }
}

@Composable
private fun AddGoalDialog(env: TasksEnvironment, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var why by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiText("新目标", "New goal")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(uiText("想长期做的一件事", "Something you're working toward")) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = why,
                    onValueChange = { why = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(uiText("为什么（可不填）", "Why (optional)")) },
                )
                Text(
                    uiText(
                        "记成目标之后，Vana 聊天时会记得它，你说进展它会帮你更新。",
                        "Vana keeps goals in mind while chatting and can update them when you share progress.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val problem = TaskActions.addGoal(env, title, why)
                if (problem == null) onDismiss() else error = problem
            }) { Text(uiText("添加", "Add")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiText("取消", "Cancel")) } },
    )
}
