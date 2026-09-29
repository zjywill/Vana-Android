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
import androidx.compose.material3.Switch
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
    jobs: JobControls = LocalJobControls.current,
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
                TaskKind.GOAL -> GoalSection(env, task)
                TaskKind.JOB -> JobSection(task, jobs)
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
                    if (task.kind == TaskKind.JOB && task.isActive) jobs.stop(task.id)
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
private fun GoalSection(env: TasksEnvironment, task: Task) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(uiText("每周回顾", "Weekly review"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    uiText(
                        "每隔一周，在后台请模型看一眼这个目标的进展，把结论放进对话。会把目标的内容发给你选的模型服务。",
                        "Once a week, a background job reviews this goal's progress and posts a summary in the chat. The goal's content is sent to your chosen model service.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = task.digestEnabled,
                onCheckedChange = { enabled -> TaskActions.setDigest(env, task.id, enabled) },
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

/** 后台任务的详情:说明、状态、做了哪些步、结果、要你拍板的提议。运行本身在 [JobControls] 后面。 */
@Composable
private fun JobSection(task: Task, jobs: JobControls) {
    if (task.brief.isNotBlank()) {
        Text(uiText("交代的事", "Brief"), style = MaterialTheme.typography.titleSmall)
        Text(task.brief, style = MaterialTheme.typography.bodyMedium)
    }

    when (task.status) {
        TaskStatus.PROPOSED -> {
            Text(
                uiText(
                    "会放到后台做，大概几分钟；期间它会把上面这段说明和用到的记忆发给模型服务。它只读，不会改动你的任何数据。",
                    "This runs in the background for a few minutes. The brief above and any memory it needs are sent to the model service. It is read-only and never changes your data.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { jobs.start(task.id) }) { Text(uiText("开始", "Start")) }
                OutlinedButton(onClick = { jobs.stop(task.id) }) { Text(uiText("不做了", "Never mind")) }
            }
        }
        TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.NEEDS_YOU ->
            OutlinedButton(onClick = { jobs.stop(task.id) }) { Text(uiText("停止", "Stop")) }
        TaskStatus.FAILED, TaskStatus.CANCELLED ->
            OutlinedButton(onClick = { jobs.start(task.id) }) { Text(uiText("再试一次", "Try again")) }
        TaskStatus.DONE -> Unit
    }

    task.error?.takeIf { it.isNotBlank() }?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }

    task.result?.let { result ->
        HorizontalDivider()
        Text(uiText("结果", "Result"), style = MaterialTheme.typography.titleSmall)
        Text(result.summary, style = MaterialTheme.typography.bodyLarge)
        if (result.body.isNotBlank()) Text(result.body, style = MaterialTheme.typography.bodyMedium)
        if (result.sources.isNotEmpty()) {
            Text(
                uiText("来源", "Sources") + "：" + result.sources.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (result.proposals.isNotEmpty()) {
            Text(uiText("它想让你做的", "Suggested actions"), style = MaterialTheme.typography.titleSmall)
            for (proposal in result.proposals) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(proposalLabel(proposal), style = MaterialTheme.typography.bodyMedium)
                    when (proposal.status) {
                        ProposalStatus.PENDING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { jobs.decide(task.id, proposal.id, true) }) { Text(uiText("照做", "Apply")) }
                            OutlinedButton(onClick = { jobs.decide(task.id, proposal.id, false) }) { Text(uiText("算了", "Skip")) }
                        }
                        ProposalStatus.ACCEPTED -> Text(uiText("已照做", "Applied"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        ProposalStatus.DISMISSED -> Text(uiText("已略过", "Skipped"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (task.steps.isNotEmpty()) {
        HorizontalDivider()
        Text(uiText("做了什么", "What it did"), style = MaterialTheme.typography.titleSmall)
        for (step in task.steps) {
            Text("· ${step.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (task.tokensUsed > 0) {
        Text(
            uiText("用了约 ${task.tokensUsed} token", "About ${task.tokensUsed} tokens used"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun proposalLabel(proposal: TaskProposal): String = when (proposal.kind) {
    "reminder" -> uiTextPlain("设个提醒：", "Set a reminder: ") + proposal.text
    "goal" -> uiTextPlain("记成目标：", "Save as a goal: ") + proposal.text
    "memory" -> uiTextPlain("记住：", "Remember: ") + proposal.text
    else -> proposal.text
}

private fun uiTextPlain(zh: String, en: String): String = com.pinapia.vana.ui.L10n.text(zh, en)
