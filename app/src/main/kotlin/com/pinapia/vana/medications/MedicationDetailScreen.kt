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
import com.pinapia.vana.ui.L10n
import com.pinapia.vana.ui.uiText
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = uiText("返回", "Back"))
                    }
                },
                actions = {
                    TextButton(onClick = onEdit) { Text(uiText("编辑", "Edit")) }
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
                uiText("关系：${current.status.label}", "Status: ${current.status.label}"),
                style = MaterialTheme.typography.titleMedium,
                color = if (current.status == MedicationItem.Status.CANNOT_TAKE) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (current.whenText.isNotBlank()) {
                Field(uiText("什么情况下吃", "When you take it"), current.whenText)
            }
            if (current.reason.isNotBlank()) {
                Field(uiText("为什么吃", "Why you take it"), current.reason)
            }
            current.startedAt?.let {
                Field(uiText("开始时间", "Started"), formatDate(it.toEpochMilliseconds()))
            }
            Text(
                "${current.originLabel} · ${relativeTime(current.updatedAt.toEpochMilliseconds())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(uiText("你自己的评价", "Your assessment"), style = MaterialTheme.typography.titleSmall)
            Text(
                current.outcome.ifBlank {
                    uiText(
                        "还没记。有没有用、有什么感觉，记一句，Vana 下次就不会再推荐一次你试过的东西。",
                        "Nothing recorded yet. Add whether it helped and how it felt so Vana does not suggest it again unnecessarily.",
                    )
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
                    if (dueNow) {
                        uiText("说好回头看的时间到了", "It is time to follow up")
                    } else {
                        uiText("${formatMonthDay(due.toEpochMilliseconds())} 回头问你一句", "Follow up on ${formatMonthDay(due.toEpochMilliseconds())}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (dueNow) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Text(uiText("一般说明", "General information"), style = MaterialTheme.typography.titleSmall)
            Text(
                current.brief.ifBlank { uiText("还没有说明。", "No information yet.") },
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
                            briefError = L10n.text(
                                "这次没写出来。检查一下网络，或者设置里的模型配置。",
                                "Vana could not generate this. Check the network and model settings.",
                            )
                        }
                    }
                },
                enabled = !regenerating && !current.briefIsUserWritten,
            ) {
                Text(if (regenerating) uiText("正在写…", "Generating…") else uiText("重新生成", "Regenerate"))
            }
            briefError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (current.briefIsUserWritten) {
                    uiText(
                        "这一段你自己改过，不会被自动覆盖。要恢复自动生成，把它清空再保存。",
                        "You edited this section, so it will not be overwritten. Clear and save it to restore automatic generation.",
                    )
                } else {
                    uiText(
                        "由 Vana 自动写的通用说明，不是给你的建议，也不含剂量和用法。",
                        "General information generated by Vana. It is not personal advice and includes no dose or directions.",
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = { onAsk(current) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(uiText("问问 Vana", "Ask Vana")) }
            Text(
                uiText(
                    "会开一条围绕「${current.name}」的对话，下次从这里进来还接着上次那条聊。",
                    "Starts a conversation about \"${current.name}\" and continues it when you return here.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TextButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(uiText("删掉这条", "Delete item"), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(uiText("删掉「${current.name}」？", "Delete \"${current.name}\"?")) },
            text = {
                Text(
                    if (current.status == MedicationItem.Status.CANNOT_TAKE) {
                        uiText(
                            "删掉之后 Vana 就不知道你不能吃它了，给建议时也不会再避开。",
                            "Vana will no longer know that you cannot take this item.",
                        )
                    } else {
                        uiText(
                            "只删除这条本地记录。",
                            "Only this local record will be deleted.",
                        )
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(current.id)
                    confirmDelete = false
                    onDeleted()
                }) { Text(uiText("删掉这条", "Delete item")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(uiText("取消", "Cancel")) }
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
    SimpleDateFormat(
        if (L10n.replyLanguage() == "English") "MMM d, yyyy" else "yyyy/M/d",
        Locale.getDefault(),
    ).format(Date(millis))

private fun formatMonthDay(millis: Long): String =
    SimpleDateFormat(if (L10n.replyLanguage() == "English") "MMM d" else "M月d日", Locale.getDefault()).format(Date(millis))

private fun relativeTime(millis: Long): String {
    val delta = System.currentTimeMillis() - millis
    val minutes = delta / 60_000
    return when {
        minutes < 1 -> L10n.text("刚刚", "Just now")
        minutes < 60 -> L10n.text("${minutes}分钟前", "$minutes minutes ago")
        minutes < 24 * 60 -> L10n.text("${minutes / 60}小时前", "${minutes / 60} hours ago")
        else -> L10n.text("${minutes / (24 * 60)}天前", "${minutes / (24 * 60)} days ago")
    }
}
