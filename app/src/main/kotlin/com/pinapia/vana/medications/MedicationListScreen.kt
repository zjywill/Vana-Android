package com.pinapia.vana.medications

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.pinapia.vana.settings.EngineSettings
import com.pinapia.vana.settings.SecureKeyStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationListScreen(
    store: MedicationStore,
    engineSettings: EngineSettings,
    secureKeyStore: SecureKeyStore,
    onBack: () -> Unit,
    onAskMedication: (MedicationItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var items by remember { mutableStateOf(store.load()) }
    var enabled by remember { mutableStateOf(engineSettings.medicationsEnabled) }
    var detail by remember { mutableStateOf<MedicationItem?>(null) }
    var editing by remember { mutableStateOf<MedicationDraft?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        items = store.load()
    }

    when {
        editing != null -> {
            MedicationEditScreen(
                draft = editing!!,
                onCancel = { editing = null },
                onSave = { item ->
                    if (item.id.let { id -> store.load().any { it.id == id } }) {
                        store.update(item)
                    } else {
                        store.add(item)
                    }
                    val saved = store.load().firstOrNull { it.id == item.id } ?: item
                    editing = null
                    reload()
                    if (saved.brief.isBlank() && !saved.briefIsUserWritten) {
                        scope.launch {
                            MedicationBriefer.fill(saved, store, engineSettings, secureKeyStore)
                            reload()
                            detail = store.load().firstOrNull { it.id == saved.id }
                        }
                    } else {
                        detail = saved
                    }
                },
            )
        }
        detail != null -> {
            MedicationDetailScreen(
                item = detail!!,
                store = store,
                engineSettings = engineSettings,
                secureKeyStore = secureKeyStore,
                onBack = { detail = null; reload() },
                onEdit = { editing = MedicationDraft.from(detail!!) },
                onAsk = { med ->
                    detail = null
                    onAskMedication(med)
                },
                onDeleted = { detail = null; reload() },
            )
        }
        else -> {
            Scaffold(
                modifier = modifier.fillMaxSize(),
                topBar = {
                    TopAppBar(
                        title = { Text("用药与补剂") },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        },
                        actions = {
                            IconButton(onClick = { editing = MedicationDraft() }) {
                                Icon(Icons.Default.Add, contentDescription = "加一条")
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
                            Text("让 Vana 看到这份清单", modifier = Modifier.weight(1f))
                            Switch(
                                checked = enabled,
                                onCheckedChange = {
                                    enabled = it
                                    engineSettings.medicationsEnabled = it
                                },
                            )
                        }
                        Text(
                            "开着时，这份清单会随每次提问一起发给你选的模型 provider，Vana 给建议之前会先看你不能吃什么、试过什么没用。关掉只是先不用，下面的内容还在。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    }
                    if (items.isEmpty()) {
                        item {
                            Text("还没记下什么", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "你吃过、在吃、不能吃的东西。这里不做用药提醒和打卡——那些在「健康」App 里管更合适。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                            )
                        }
                    } else {
                        for (status in MedicationSnapshot.StatusOrder) {
                            val group = items.filter { it.status == status }
                            if (group.isEmpty()) continue
                            item(key = "header-${status.name}") {
                                Row(
                                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = status.icon(),
                                        contentDescription = null,
                                        tint = if (status == MedicationItem.Status.CANNOT_TAKE) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                    )
                                    Text(
                                        status.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (status == MedicationItem.Status.CANNOT_TAKE) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                }
                                Text(
                                    status.hint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                            items(group, key = { it.id }) { med ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { detail = med }
                                        .padding(vertical = 10.dp),
                                ) {
                                    Text(
                                        med.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (status == MedicationItem.Status.CANNOT_TAKE) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    med.subtitle.takeIf { it.isNotBlank() }?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun MedicationItem.Status.icon(): ImageVector = when (this) {
    MedicationItem.Status.CANNOT_TAKE -> Icons.Default.Block
    MedicationItem.Status.ONGOING -> Icons.Default.Medication
    MedicationItem.Status.AS_NEEDED -> Icons.Default.LocalHospital
    MedicationItem.Status.TRIED -> Icons.Default.CheckCircle
}
