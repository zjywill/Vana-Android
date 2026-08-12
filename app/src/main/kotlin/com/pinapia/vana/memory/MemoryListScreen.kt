package com.pinapia.vana.memory

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pinapia.vana.settings.EngineSettings
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.until

private val FollowUpDayOptions = listOf(3, 7, 14, 30, 60, 90)

data class MemoryDraft(
    val id: String? = null,
    var text: String = "",
    var kind: MemoryItem.Kind = MemoryItem.Kind.PROFILE,
    var days: Int = 14,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryListScreen(
    store: MemoryStore,
    engineSettings: EngineSettings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var items by remember { mutableStateOf(store.load()) }
    var enabled by remember { mutableStateOf(engineSettings.memoryEnabled) }
    var editing by remember { mutableStateOf<MemoryDraft?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    fun reload() {
        items = store.load()
    }

    if (editing != null) {
        MemoryEditorSheet(
            draft = editing!!,
            onCancel = { editing = null },
            onSave = { draft ->
                val now = Clock.System.now()
                val dueAt = if (draft.kind == MemoryItem.Kind.FOLLOW_UP) {
                    now.plus(draft.days.coerceIn(1, 180), DateTimeUnit.DAY, TimeZone.currentSystemDefault())
                } else {
                    null
                }
                if (draft.id != null) {
                    val existing = items.firstOrNull { it.id == draft.id }
                    if (existing != null) {
                        store.update(
                            existing.copy(
                                text = draft.text.trim(),
                                kind = draft.kind,
                                dueAt = dueAt,
                                origin = MemoryItem.Origin.MANUAL,
                            ),
                            now,
                        )
                    }
                } else {
                    store.remember(
                        text = draft.text,
                        kind = draft.kind,
                        origin = MemoryItem.Origin.MANUAL,
                        days = if (draft.kind == MemoryItem.Kind.FOLLOW_UP) draft.days else null,
                        now = now,
                    )
                }
                editing = null
                reload()
            },
        )
        return
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Vana 记住的事") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { editing = MemoryDraft() }) {
                        Icon(Icons.Default.Add, contentDescription = "添加一条")
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
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("记住我说过的事", modifier = Modifier.weight(1f))
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            engineSettings.memoryEnabled = it
                        },
                    )
                }
                Text(
                    "开着时记下长期情况/偏好并带入提问；关掉只是先不用，已记的还在，删有单独按钮。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }
            if (items.isEmpty()) {
                item {
                    Text("还没有记住什么", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "多聊几次，Vana 会把长期成立的事记下来；也可以现在就自己加一条。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                    )
                }
            } else {
                for (kind in MemorySnapshot.KindOrder) {
                    val group = items.filter { it.kind == kind }
                    if (group.isEmpty()) continue
                    item(key = "kind-${kind.name}") {
                        Text(
                            kind.label,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                        )
                        Text(
                            kind.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    items(group, key = { it.id }) { mem ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val days = mem.dueAt?.let { due ->
                                        Clock.System.now()
                                            .until(due, DateTimeUnit.DAY, TimeZone.currentSystemDefault())
                                            .toInt()
                                            .coerceAtLeast(1)
                                    } ?: 14
                                    editing = MemoryDraft(
                                        id = mem.id,
                                        text = mem.text,
                                        kind = mem.kind,
                                        days = days,
                                    )
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(mem.text, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                buildString {
                                    append(mem.originLabel)
                                    append(" · ")
                                    append(relativeTime(mem.updatedAt.toEpochMilliseconds()))
                                    if (mem.kind == MemoryItem.Kind.FOLLOW_UP && mem.dueAt != null) {
                                        append(" · ")
                                        append(
                                            if (mem.isDue()) {
                                                "该回头看了"
                                            } else {
                                                val d = Clock.System.now()
                                                    .until(mem.dueAt!!, DateTimeUnit.DAY, TimeZone.currentSystemDefault())
                                                    .toInt()
                                                    .coerceAtLeast(1)
                                                "${d} 天后回头看"
                                            },
                                        )
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    Text(
                        "已记 ${items.size}/${MemorySnapshot.MAX_ITEMS} 条。这里只记查不到的事；对话时这些内容会随问题一起发给你选的模型 provider。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    TextButton(
                        onClick = { confirmClear = true },
                        enabled = items.isNotEmpty(),
                    ) {
                        Text("忘掉全部", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("忘掉全部记忆？") },
            text = { Text("包括你自己添加的那些，无法撤销。健康数据本身不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    store.removeAll()
                    confirmClear = false
                    reload()
                }) { Text("忘掉全部") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryEditorSheet(
    draft: MemoryDraft,
    onCancel: () -> Unit,
    onSave: (MemoryDraft) -> Unit,
) {
    var text by remember { mutableStateOf(draft.text) }
    var kind by remember { mutableStateOf(draft.kind) }
    var days by remember { mutableIntStateOf(draft.days) }
    var showKinds by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (draft.id == null) "添加记忆" else "编辑记忆") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "取消")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            onSave(draft.copy(text = text, kind = kind, days = days))
                        },
                        enabled = text.isNotBlank(),
                    ) { Text("保存") }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("比如：他上夜班，白天补觉") },
            )
            Text(
                "一句话说清就行。不要写具体数字——那些每次都会重新查。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "类别：${kind.label}",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showKinds = !showKinds }
                    .padding(vertical = 8.dp),
                style = MaterialTheme.typography.titleSmall,
            )
            if (showKinds) {
                MemoryItem.Kind.entries.forEach { option ->
                    Text(
                        option.label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                kind = option
                                showKinds = false
                            }
                            .padding(vertical = 6.dp),
                        color = if (option == kind) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
            if (kind == MemoryItem.Kind.FOLLOW_UP) {
                Text("多久后回头看", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FollowUpDayOptions.forEach { option ->
                        FilterChip(
                            selected = days == option,
                            onClick = { days = option },
                            label = { Text("${option}天") },
                        )
                    }
                }
            }
        }
    }
}

private fun relativeTime(millis: Long): String {
    val minutes = (System.currentTimeMillis() - millis) / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        minutes < 24 * 60 -> "${minutes / 60}小时前"
        else -> "${minutes / (24 * 60)}天前"
    }
}
