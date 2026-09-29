package com.pinapia.vana.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pinapia.vana.today.CoreToday
import com.pinapia.vana.ui.uiText

/**
 * 聊天里 `start_task` 留下的那张卡:这件事是什么、现在到哪一步、能点什么。
 * 它读的是任务本身(不是工具调用那一刻的快照),所以点了「开始」之后同一张卡会一路变成
 * 「进行中」「做完了」,不用再往对话里多塞一条。
 */
@Composable
fun TaskCard(
    taskId: String,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    env: TasksEnvironment? = LocalTasksEnvironment.current,
    jobs: JobControls = LocalJobControls.current,
) {
    env ?: return
    val revision by env.store.revision.collectAsState()
    val task = remember(revision, taskId) { env.store.get(taskId) }

    OutlinedCard(modifier = modifier.fillMaxWidth().clickable(enabled = task != null) { onOpen(taskId) }) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (task == null) {
                Text(uiText("这件事已经不在了", "This task no longer exists"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Text(task.title, style = MaterialTheme.typography.titleSmall)
            when (task.status) {
                TaskStatus.PROPOSED -> {
                    Text(
                        task.brief,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        uiText(
                            "会放到后台做，大概几分钟；期间它会把上面这段说明和用到的记忆发给模型服务。",
                            "This runs in the background for a few minutes. The brief above and any memory it needs are sent to the model service.",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { jobs.start(task.id) }) { Text(uiText("开始", "Start")) }
                        OutlinedButton(onClick = { jobs.stop(task.id) }) { Text(uiText("不做了", "Never mind")) }
                    }
                }
                TaskStatus.QUEUED, TaskStatus.RUNNING -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            task.steps.lastOrNull()?.label
                                ?: if (task.status == TaskStatus.QUEUED) uiText("排队中", "Queued") else uiText("进行中", "In progress"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    TextButton(onClick = { jobs.stop(task.id) }) { Text(uiText("停止", "Stop")) }
                }
                TaskStatus.DONE -> {
                    Text(task.result?.summary.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Text(uiText("查看详情", "View details"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                TaskStatus.FAILED, TaskStatus.CANCELLED -> {
                    Text(
                        task.error ?: CoreToday.statusLabel(task.status),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (task.status == TaskStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { jobs.start(task.id) }) { Text(uiText("再试一次", "Try again")) }
                }
                TaskStatus.NEEDS_YOU -> Text(CoreToday.statusLabel(task.status))
            }
            // 上一次尝试留下的原因(比如「还没配置模型」)要让人看得见。
            if (task.status == TaskStatus.PROPOSED) {
                task.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
