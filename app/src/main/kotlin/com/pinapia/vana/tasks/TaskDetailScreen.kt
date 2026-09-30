package com.pinapia.vana.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import com.pinapia.vana.today.CoreToday
import com.pinapia.vana.ui.icons.VanaIcons
import com.pinapia.vana.ui.uiText
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    env: TasksEnvironment,
    taskId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** 「在侧聊里聊这个目标」。null 就不出那颗按钮。 */
    onDiscussGoal: ((Task) -> Unit)? = null,
) {
    val revision by env.store.revision.collectAsState()
    val task = remember(revision, taskId) { env.store.get(taskId) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(task?.title ?: uiText("任务", "Task"), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(VanaIcons.ArrowLeft, contentDescription = uiText("返回", "Back"))
                    }
                },
                actions = {
                    if (task != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(VanaIcons.Trash, contentDescription = uiText("删除", "Delete"))
                        }
                    }
                },
            )
        },
    ) { insets ->
        if (task == null) {
            Text(
                uiText("这一项已经不在了", "This item no longer exists"),
                modifier = Modifier.padding(insets).padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                CoreToday.statusLabel(task.status),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
            when (task.kind) {
                TaskKind.REMINDER -> ReminderSection(env, task)
                TaskKind.GOAL -> GoalSection(env, task, onDiscussGoal)
            }
        }
    }

    if (confirmDelete && task != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(uiText("删除这一项？", "Delete this item?")) },
            text = { Text(uiText("删除后无法撤销。", "This cannot be undone.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    TaskActions.delete(env, task.id)
                    onBack()
                }) { Text(uiText("删除", "Delete")) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(uiText("取消", "Cancel")) } },
        )
    }
}

@Composable
private fun ReminderSection(env: TasksEnvironment, task: Task) {
    Text(reminderLine(task, env), style = MaterialTheme.typography.titleMedium)
    if (task.isActive) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { TaskActions.complete(env, task.id) }) { Text(uiText("已完成", "Mark done")) }
            OutlinedButton(onClick = { TaskActions.cancel(env, task.id) }) { Text(uiText("取消提醒", "Cancel reminder")) }
        }
    }
    Text(
        uiText(
            "到点只发一条本地通知，不会联网，也不会调用模型。",
            "At the time it only posts a local notification — no network and no model call.",
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun GoalSection(env: TasksEnvironment, task: Task, onDiscussGoal: ((Task) -> Unit)?) {
    var newStep by remember(task.id) { mutableStateOf("") }
    var newNote by remember(task.id) { mutableStateOf("") }

    if (task.why.isNotBlank()) {
        Text(task.why, style = MaterialTheme.typography.bodyLarge)
    }

    Text(uiText("步骤", "Steps"), style = MaterialTheme.typography.titleSmall)
    if (task.plan.isEmpty()) {
        Text(
            uiText("还没有步骤。可以自己加，也可以在对话里让 Vana 帮你拆。", "No steps yet. Add some yourself or ask Vana in the chat to break it down."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    for (item in task.plan) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = task.isActive) { TaskActions.togglePlanItem(env, task.id, item.id) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = item.done,
                onCheckedChange = if (task.isActive) ({ TaskActions.togglePlanItem(env, task.id, item.id) }) else null,
                enabled = task.isActive,
            )
            Text(
                item.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    if (task.isActive) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newStep,
                onValueChange = { newStep = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(uiText("加一步", "Add a step")) },
            )
            TextButton(
                enabled = newStep.isNotBlank(),
                onClick = { TaskActions.addPlanItem(env, task.id, newStep); newStep = "" },
            ) { Text(uiText("添加", "Add")) }
        }
    }

    HorizontalDivider()
    Text(uiText("进展", "Progress notes"), style = MaterialTheme.typography.titleSmall)
    val formatter = remember { java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm", Locale.getDefault()) }
    for (note in task.notes.reversed()) {
        Column {
            Text(note.text, style = MaterialTheme.typography.bodyMedium)
            Text(
                formatter.format(java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(note.at.toEpochMilliseconds()), env.zone)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (task.isActive) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newNote,
                onValueChange = { newNote = it },
                modifier = Modifier.weight(1f),
                label = { Text(uiText("记一笔进展", "Add a note")) },
            )
            TextButton(
                enabled = newNote.isNotBlank(),
                onClick = { TaskActions.addNote(env, task.id, newNote); newNote = "" },
            ) { Text(uiText("记录", "Save")) }
        }
        // 以前是「每周回顾」——后台每七天自动请模型看一眼,写几句放进对话;子 agent 撤掉之后改成他想聊的时候
        // 自己开一条侧聊(那里的 system 段本来就带着进行中的目标)。
        onDiscussGoal?.let { discuss ->
            OutlinedButton(onClick = { discuss(task) }) { Text(uiText("在侧聊里聊这个目标", "Talk this goal through in a side chat")) }
            Text(
                uiText(
                    "开一条以这个目标命名的侧聊，回顾进展、商量接下来怎么做。那里说的不挤主对话。",
                    "Opens a side chat named after this goal to review progress and plan what's next. It doesn't crowd the main conversation.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { TaskActions.complete(env, task.id) }) { Text(uiText("完成这个目标", "Mark as achieved")) }
            OutlinedButton(onClick = { TaskActions.cancel(env, task.id) }) { Text(uiText("放弃", "Drop")) }
        }
    } else if (task.status == TaskStatus.DONE || task.status == TaskStatus.CANCELLED) {
        OutlinedButton(onClick = { TaskActions.reopen(env, task.id) }) { Text(uiText("重新开始", "Resume")) }
    }
}
