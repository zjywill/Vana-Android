package com.pinapia.vana.medications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pinapia.vana.settings.EngineSettings
import com.pinapia.vana.settings.SecureKeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationDetailScreen(
    item: MedicationItem,
    store: MedicationStore,
    engineSettings: EngineSettings,
    secureKeyStore: SecureKeyStore,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onAsk: (MedicationItem) -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var current by remember(item.id) { mutableStateOf(item) }
    var regenerating by remember { mutableStateOf(false) }
    var briefError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(current.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = onEdit) { Text("编辑") }
                },
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "关系：${current.status.label}",
                style = MaterialTheme.typography.titleMedium,
                color = if (current.status == MedicationItem.Status.CANNOT_TAKE) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (current.whenText.isNotBlank()) {
                Field("什么情况下吃", current.whenText)
            }
            if (current.reason.isNotBlank()) {
                Field("为什么吃", current.reason)
            }
            current.startedAt?.let {
                Field("开始时间", formatDate(it.toEpochMilliseconds()))
            }
            Text(
                "${current.originLabel} · ${relativeTime(current.updatedAt.toEpochMilliseconds())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("你自己的评价", style = MaterialTheme.typography.titleSmall)
            Text(
                current.outcome.ifBlank {
                    "还没记。有没有用、有什么感觉，记一句，Vana 下次就不会再推荐一次你试过的东西。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (current.outcome.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            current.followUpAt?.let { due ->
                val dueNow = due <= Clock.System.now()
                Text(
                    if (dueNow) "说好回头看的时间到了" else "${formatMonthDay(due.toEpochMilliseconds())} 回头问你一句",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (dueNow) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Text("一般说明", style = MaterialTheme.typography.titleSmall)
            Text(
                current.brief.ifBlank { "还没有说明。" },
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = {
                    regenerating = true
                    briefError = null
                    scope.launch {
                        val wrote = MedicationBriefer.fill(
                            item = current,
                            store = store,
                            engineSettings = engineSettings,
                            secureKeyStore = secureKeyStore,
                        )
                        regenerating = false
                        if (wrote) {
                            current = store.load().firstOrNull { it.id == current.id } ?: current
                        } else {
                            briefError = "这次没写出来。检查一下网络，或者设置里的模型配置。"
                        }
                    }
                },
                enabled = !regenerating && !current.briefIsUserWritten,
            ) {
                Text(if (regenerating) "正在写…" else "重新生成")
            }
            briefError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (current.briefIsUserWritten) {
                    "这一段你自己改过，不会被自动覆盖。要恢复自动生成，把它清空再保存。"
                } else {
                    "由 Vana 自动写的通用说明，不是给你的建议，也不含剂量和用法。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = { onAsk(current) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("问问 Vana") }
            Text(
                "会开一条围绕「${current.name}」的对话，下次从这里进来还接着上次那条聊。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TextButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("删掉这条", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删掉「${current.name}」？") },
            text = {
                Text(
                    if (current.status == MedicationItem.Status.CANNOT_TAKE) {
                        "删掉之后 Vana 就不知道你不能吃它了，给建议时也不会再避开。"
                    } else {
                        "只删这条记录，不影响「健康」App 里的任何数据。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(current.id)
                    confirmDelete = false
                    onDeleted()
                }) { Text("删掉这条") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("yyyy/M/d", Locale.CHINA).format(Date(millis))

private fun formatMonthDay(millis: Long): String =
    SimpleDateFormat("M月d日", Locale.CHINA).format(Date(millis))

private fun relativeTime(millis: Long): String {
    val delta = System.currentTimeMillis() - millis
    val minutes = delta / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        minutes < 24 * 60 -> "${minutes / 60}小时前"
        else -> "${minutes / (24 * 60)}天前"
    }
}
