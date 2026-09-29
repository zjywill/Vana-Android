package com.pinapia.vana.memory

import com.pinapia.vana.ui.icons.VanaIcons
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
import com.pinapia.vana.plugins.PluginRegistry
import com.pinapia.vana.settings.EngineSettings
import com.pinapia.vana.ui.L10n
import com.pinapia.vana.ui.uiText
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.until

private val FollowUpDayOptions = listOf(3, 7, 14, 30, 60, 90)

/** 条数到这个数才出现搜索框。 */
private const val SEARCH_THRESHOLD = 8

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
    var query by remember { mutableStateOf("") }

    fun reload() {
        items = store.load()
    }

    /** 这一类记忆的主人(插件)现在关着吗。关着的不会带进对话,但数据还在,所以照样列出来。 */
    fun isDormant(kind: MemoryItem.Kind): Boolean =
        !PluginRegistry.isMemoryVisible(kind, engineSettings::isPluginEnabled)

    if (editing != null) {
        MemoryEditorSheet(
            draft = editing!!,
            // 已经是某个种类的条目要能留在原种类里;新选的种类只列现在生效的。
            kinds = MemoryItem.Kind.entries.filter { !isDormant(it) || it == editing!!.kind },
            onCancel = { editing = null },
            onDelete = editing!!.id?.let { id ->
                {
                    store.delete(id)
                    editing = null
                    reload()
                }
            },
            onSave = { draft ->
                val now = Clock.System.now()
                val dueAt = MemoryItem.dueFor(draft.kind, draft.days, now)
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
                        days = if (MemoryItem.expires(draft.kind)) draft.days else null,
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
                title = { Text(uiText("Vana 记住的事", "What Vana remembers")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(VanaIcons.ArrowLeft, contentDescription = uiText("返回", "Back"))
                    }
                },
                actions = {
                    IconButton(onClick = { editing = MemoryDraft() }) {
                        Icon(VanaIcons.Plus, contentDescription = uiText("添加一条", "Add memory"))
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
                    Text(uiText("记住我说过的事", "Remember what I say"), modifier = Modifier.weight(1f))
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            engineSettings.memoryEnabled = it
                        },
                    )
                }
                Text(
                    uiText(
                        "开着时记下长期情况/偏好并带入提问；关掉只是先不用，已记的还在，删有单独按钮。",
                        "When enabled, Vana remembers long-term context and preferences and includes relevant items with questions. Turning it off keeps existing memories on this device.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                // 条数多了才要搜:十来条一眼看得完,搜索框只会占地方。
                if (items.size >= SEARCH_THRESHOLD) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(uiText("搜索记忆", "Search memories")) },
                    )
                }
            }
            if (items.isEmpty()) {
                item {
                    Text(uiText("还没有记住什么", "No memories yet"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        uiText(
                            "多聊几次，Vana 会把长期成立的事记下来；也可以现在就自己加一条。",
                            "Vana can remember durable context from conversations, or you can add an item yourself.",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                    )
                }
            } else {
                val shown = if (query.isBlank()) items else items.filter { it.text.contains(query.trim(), ignoreCase = true) }
                if (shown.isEmpty()) {
                    item(key = "no-match") {
                        Text(
                            uiText("没有匹配的记忆", "No matching memories"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                for (kind in MemorySnapshot.KindOrder) {
                    val group = shown.filter { it.kind == kind }
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
                                    if (mem.kind == MemoryItem.Kind.EPISODE && mem.dueAt != null) {
                                        append(" · ")
                                        val d = Clock.System.now()
                                            .until(mem.dueAt!!, DateTimeUnit.DAY, TimeZone.currentSystemDefault())
                                            .toInt()
                                            .coerceAtLeast(1)
                                        append(uiText("${d} 天后淡出", "Fades out in $d days"))
                                    }
                                    if (isDormant(mem.kind)) {
                                        append(" · ")
                                        append(uiText("对应插件已关，暂不使用", "Its plugin is off; not used for now"))
                                    }
                                    if (mem.kind == MemoryItem.Kind.FOLLOW_UP && mem.dueAt != null) {
                                        append(" · ")
                                        append(
                                            if (mem.isDue()) {
                                                uiText("该回头看了", "Follow-up due")
                                            } else {
                                                val d = Clock.System.now()
                                                    .until(mem.dueAt!!, DateTimeUnit.DAY, TimeZone.currentSystemDefault())
                                                    .toInt()
                                                    .coerceAtLeast(1)
                                                uiText("${d} 天后回头看", "Follow up in $d days")
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
                        uiText(
                            "已记 ${items.size}/${MemorySnapshot.MAX_ITEMS} 条。这里只记查不到的事；对话时这些内容会随问题一起发给你选的模型 provider。",
                            "${items.size}/${MemorySnapshot.MAX_ITEMS} memories saved. Relevant items are sent with questions to your selected model provider.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    TextButton(
                        onClick = { confirmClear = true },
                        enabled = items.isNotEmpty(),
                    ) {
                        Text(uiText("忘掉全部", "Forget all"), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(uiText("忘掉全部记忆？", "Forget all memories?")) },
            text = {
                Text(
                    uiText(
                        "包括你自己添加的那些，无法撤销。对话、用药和测量记录不会被删除。",
                        "This includes memories you added yourself and cannot be undone. Conversations, medications and measurements are not deleted.",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.removeAll()
                    confirmClear = false
                    reload()
                }) { Text(uiText("忘掉全部", "Forget all")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(uiText("取消", "Cancel")) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryEditorSheet(
    draft: MemoryDraft,
    kinds: List<MemoryItem.Kind>,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (MemoryDraft) -> Unit,
) {
    var text by remember { mutableStateOf(draft.text) }
    var kind by remember { mutableStateOf(draft.kind) }
    var days by remember { mutableIntStateOf(draft.days) }
    var showKinds by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (draft.id == null) uiText("添加记忆", "Add memory") else uiText("编辑记忆", "Edit memory"))
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(VanaIcons.ArrowLeft, contentDescription = uiText("取消", "Cancel"))
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            onSave(draft.copy(text = text, kind = kind, days = days))
                        },
                        enabled = text.isNotBlank(),
                    ) { Text(uiText("保存", "Save")) }
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
                label = { Text(uiText("比如：他上夜班，白天补觉", "For example: works nights and sleeps during the day")) },
            )
            Text(
                uiText(
                    "一句话说清就行。不要写具体数字——那些每次都会重新查。",
                    "Keep it to one clear sentence. Do not store measurements here; Vana checks those separately.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                uiText("类别：${kind.label}", "Category: ${kind.label}"),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showKinds = !showKinds }
                    .padding(vertical = 8.dp),
                style = MaterialTheme.typography.titleSmall,
            )
            if (showKinds) {
                kinds.forEach { option ->
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
            if (MemoryItem.expires(kind)) {
                Text(
                    if (kind == MemoryItem.Kind.EPISODE) uiText("多久后淡出", "Fade out after") else uiText("多久后回头看", "Follow up after"),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FollowUpDayOptions.forEach { option ->
                        FilterChip(
                            selected = days == option,
                            onClick = { days = option },
                            label = { Text(uiText("${option}天", "$option days")) },
                        )
                    }
                }
            }
            if (onDelete != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                TextButton(onClick = { confirmDelete = true }) {
                    Text(uiText("删除这条", "Delete this memory"), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(uiText("删除这条记忆？", "Delete this memory?")) },
            text = {
                Text(
                    uiText(
                        "删掉之后 Vana 不会再带着这句话回答你，无法撤销。",
                        "Vana will stop using it in answers. This cannot be undone.",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(uiText("删除", "Delete")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(uiText("取消", "Cancel")) }
            },
        )
    }
}

private fun relativeTime(millis: Long): String {
    val minutes = (System.currentTimeMillis() - millis) / 60_000
    return when {
        minutes < 1 -> L10n.text("刚刚", "Just now")
        minutes < 60 -> L10n.text("${minutes}分钟前", "$minutes minutes ago")
        minutes < 24 * 60 -> L10n.text("${minutes / 60}小时前", "${minutes / 60} hours ago")
        else -> L10n.text("${minutes / (24 * 60)}天前", "${minutes / (24 * 60)} days ago")
    }
}
