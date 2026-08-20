package com.pinapia.vana.tenant

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.pinapia.vana.ui.uiText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TenantListScreen(
    store: TenantStore,
    onBack: () -> Unit,
    onSwitched: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tenants by remember { mutableStateOf(store.all()) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Tenant?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reload() {
        tenants = store.all()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(uiText("家庭成员", "Family members")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = uiText("返回", "Back"))
                    }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.Add, contentDescription = uiText("添加成员", "Add family member"))
                    }
                },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(tenants, key = { it.id }) { tenant ->
                TenantRow(
                    tenant = tenant,
                    selected = tenant.id == TenantScope.current.id,
                    onSelect = {
                        TenantScope.select(tenant)
                        onSwitched()
                    },
                    onEdit = { editing = tenant },
                    onDelete = if (tenant.isOwner) {
                        null
                    } else {
                        {
                            runCatching {
                                store.remove(tenant.id)
                                TenantScope.fallBackToOwnerIfNeeded(tenant.id)
                                reload()
                                if (TenantScope.current.id != tenant.id) {
                                    // already fell back if needed
                                }
                                onSwitched()
                            }.onFailure { error = it.message }
                        }
                    },
                )
                HorizontalDivider()
            }
            item {
                Text(
                    uiText(
                        "每位成员的会话、用药清单、记忆和照片各存一份，互相看不到。" +
                            "成员情况来自你记下的用药、拍给 Vana 的化验单，和你们聊过的内容。",
                        "Each family member has separate local conversations, medication lists, memories and photos. " +
                            "Their context comes only from what you record, attach and discuss.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            error?.let {
                item {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showAdd) {
        TenantEditDialog(
            title = uiText("添加成员", "Add family member"),
            initial = null,
            onDismiss = { showAdd = false },
            onSave = { name, ageBand ->
                runCatching {
                    val created = store.addManaged(name, ageBand)
                    TenantScope.select(created)
                    reload()
                    showAdd = false
                    onSwitched()
                }.onFailure {
                    error = it.message
                    showAdd = false
                }
            },
        )
    }
    editing?.let { tenant ->
        TenantEditDialog(
            title = uiText("编辑成员", "Edit family member"),
            initial = tenant,
            onDismiss = { editing = null },
            onSave = { name, ageBand ->
                val updated = tenant.copy(name = name, ageBand = ageBand)
                store.update(updated)
                TenantScope.refresh(updated)
                reload()
                editing = null
            },
        )
    }
}

@Composable
private fun TenantRow(
    tenant: Tenant,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(tenant.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                buildString {
                    tenant.ageBand?.let { append(it.label); append(" · ") }
                    append(
                        if (tenant.isOwner) {
                            uiText("本人", "You")
                        } else {
                            uiText("只有你记下的内容", "Only what you record")
                        },
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = onEdit) { Text(uiText("编辑", "Edit")) }
                onDelete?.let { TextButton(onClick = it) { Text(uiText("删除", "Delete")) } }
            }
        }
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = uiText("当前", "Current"), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun TenantEditDialog(
    title: String,
    initial: Tenant?,
    onDismiss: () -> Unit,
    onSave: (String, Tenant.AgeBand?) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var ageBand by remember { mutableStateOf(initial?.ageBand) }
    var showAgePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(Tenant.MAX_NAME_LENGTH) },
                    label = { Text(uiText("称呼，比如：妈妈、爸爸、女儿", "Label, for example Mom, Dad or daughter")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    uiText(
                        "只写称呼就行。Vana 不需要真实姓名、生日或证件信息，也不要填进来。",
                        "A familiar label is enough. Do not enter a legal name, birth date or identity-document information.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    uiText(
                        "年龄段：${ageBand?.label ?: "不说"}",
                        "Age group: ${ageBand?.label ?: "Not specified"}",
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAgePicker = !showAgePicker }
                        .padding(vertical = 8.dp),
                )
                if (showAgePicker) {
                    Text(
                        uiText("不说", "Not specified"),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                ageBand = null
                                showAgePicker = false
                            }
                            .padding(vertical = 6.dp),
                    )
                    Tenant.AgeBand.entries.forEach { band ->
                        Text(
                            band.label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    ageBand = band
                                    showAgePicker = false
                                }
                                .padding(vertical = 6.dp),
                            color = if (band == ageBand) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
                Text(
                    uiText(
                        "儿童的用量、老人的参考范围和风险判断都不一样，说一句能让回答准不少。具体用药和剂量仍然要问医生。",
                        "Age affects reference ranges and risk. Medication and dosage decisions still belong with a doctor.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), ageBand) },
                enabled = name.trim().isNotEmpty(),
            ) { Text(uiText("保存", "Save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(uiText("取消", "Cancel")) }
        },
    )
}
