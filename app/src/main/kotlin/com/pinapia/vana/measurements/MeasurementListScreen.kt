package com.pinapia.vana.measurements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pinapia.vana.settings.EngineSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementListScreen(
    store: MeasurementStore,
    engineSettings: EngineSettings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var cards by remember { mutableStateOf(store.load()) }
    var enabled by remember { mutableStateOf(engineSettings.measurementsEnabled) }
    var pendingDelete by remember { mutableStateOf<MeasurementCard?>(null) }

    fun reload() {
        cards = store.load()
    }

    val groups = remember(cards) {
        cards
            .groupBy { MeasurementCard.normalize(it.name) }
            .map { (_, group) ->
                val sorted = group.sortedByDescending { it.observedAt }
                MeasurementGroup(
                    title = sorted.first().name,
                    cards = sorted,
                    rank = MeasurementPriority.rank(sorted.first().name),
                )
            }
            .sortedWith(
                compareBy<MeasurementGroup> { it.rank }
                    .thenByDescending { it.cards.first().observedAt },
            )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("测量卡片") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                    Text("让 Vana 看到这些测量", modifier = Modifier.weight(1f))
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            engineSettings.measurementsEnabled = it
                        },
                    )
                }
                Text(
                    "和 Vana 说身高、体重、心率或你拿到的化验项，会记成带时间的卡片。" +
                        "同名旧记录不会被覆盖，方便看出变化。关掉只是先不发给模型，下面的内容还在。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }

            if (groups.isEmpty()) {
                item {
                    Text("还没有测量", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "在对话里说「今天体重 68」或「静息心率 60」，Vana 会记下来。" +
                            "时间说不清时它会先问你。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                    )
                }
            } else {
                groups.forEach { group ->
                    item(key = "header-${group.title}") {
                        Text(
                            group.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(group.cards, key = { it.id }) { card ->
                        MeasurementHistoryRow(
                            card = card,
                            onDelete = { pendingDelete = card },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { card ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条测量？") },
            text = {
                Text("「${card.name} ${card.displayValue}（${card.observedLabel}）」会被删掉。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.delete(card.id)
                        pendingDelete = null
                        reload()
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

private data class MeasurementGroup(
    val title: String,
    val cards: List<MeasurementCard>,
    val rank: Int,
)

@Composable
private fun MeasurementHistoryRow(
    card: MeasurementCard,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(card.displayValue, style = MaterialTheme.typography.bodyLarge)
            Text(
                card.observedLabel + if (card.note.isNotBlank()) " · ${card.note}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除 ${card.name} ${card.observedLabel}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
