package com.pinapia.vana.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 首屏那张卡点开之后的一页:整段话,以及底下那几行读数。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthStatusScreen(
    summary: String,
    situation: HealthSituation?,
    isWriting: Boolean,
    canGenerate: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var justRefreshed by remember { mutableStateOf(false) }

    LaunchedEffect(justRefreshed) {
        if (!justRefreshed) return@LaunchedEffect
        delay(6_000)
        justRefreshed = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "现在的状况",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        onRefresh()
                        justRefreshed = true
                    },
                    enabled = !isWriting && situation != null,
                ) {
                    if (isWriting) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "重新生成")
                    }
                }
                TextButton(onClick = onDismiss) { Text("完成") }
            }

            Text(summary, style = MaterialTheme.typography.bodyLarge)
            Text(
                footnote(isWriting, canGenerate, justRefreshed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            situation?.vitals?.takeIf { it.items.isNotEmpty() }?.let { vitals ->
                HorizontalDivider()
                Text("现在是多少", style = MaterialTheme.typography.titleMedium)
                vitals.items.forEach { item ->
                    VitalRow(item)
                }
            }

            situation?.notableTriggers?.takeIf { it.isNotEmpty() }?.let { triggers ->
                HorizontalDivider()
                Text("这几天变了什么", style = MaterialTheme.typography.titleMedium)
                triggers.forEach { trigger ->
                    Text(
                        trigger.brief,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "数据来自 Health Connect，只读取，不会修改你的健康记录。缺少的项目多半是那几天没有戴设备。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VitalRow(item: VitalItem) {
    val muted = item.value == null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (muted) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Column(horizontalAlignment = Alignment.End) {
            item.value?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            item.note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun footnote(isWriting: Boolean, canGenerate: Boolean, justRefreshed: Boolean): String {
    if (isWriting) return "正在重新写…"
    if (!canGenerate) {
        return if (justRefreshed) {
            "已重新读取健康数据。还没配置云端模型，这段话是本机按下面的读数拼的。"
        } else {
            "还没配置云端模型，这段话是本机按下面的读数拼的。"
        }
    }
    return if (justRefreshed) "已重新读取健康数据。" else "根据下面这些读数写的。"
}
