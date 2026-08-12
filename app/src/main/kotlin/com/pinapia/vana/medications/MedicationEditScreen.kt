package com.pinapia.vana.medications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.until

data class MedicationDraft(
    val id: String? = null,
    var name: String = "",
    var status: MedicationItem.Status = MedicationItem.Status.AS_NEEDED,
    var whenText: String = "",
    var reason: String = "",
    var outcome: String = "",
    var brief: String = "",
    var note: String = "",
    var followUpDays: Int = 0,
    var originalBrief: String = "",
    var briefIsUserWritten: Boolean = false,
    var startedAt: kotlinx.datetime.Instant? = null,
    var origin: MedicationItem.Origin = MedicationItem.Origin.MANUAL,
    var createdAt: kotlinx.datetime.Instant? = null,
) {
    fun applied(): MedicationItem {
        val now = Clock.System.now()
        val briefChanged = brief.trim() != originalBrief.trim()
        return MedicationItem(
            id = id ?: java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            status = status,
            whenText = whenText.trim(),
            reason = reason.trim(),
            outcome = outcome.trim(),
            brief = brief.trim(),
            briefIsUserWritten = when {
                briefChanged -> brief.trim().isNotEmpty()
                else -> briefIsUserWritten
            },
            note = note.trim(),
            origin = origin,
            followUpAt = if (followUpDays > 0) {
                now.plus(followUpDays, DateTimeUnit.DAY, TimeZone.currentSystemDefault())
            } else {
                null
            },
            startedAt = startedAt ?: if (
                status == MedicationItem.Status.ONGOING || status == MedicationItem.Status.AS_NEEDED
            ) {
                now
            } else {
                null
            },
            createdAt = createdAt ?: now,
            updatedAt = now,
        )
    }

    companion object {
        fun from(item: MedicationItem): MedicationDraft {
            val days = item.followUpAt?.let { due ->
                val now = Clock.System.now()
                now.until(due, DateTimeUnit.DAY, TimeZone.currentSystemDefault()).toInt().coerceAtLeast(1)
            } ?: 0
            return MedicationDraft(
                id = item.id,
                name = item.name,
                status = item.status,
                whenText = item.whenText,
                reason = item.reason,
                outcome = item.outcome,
                brief = item.brief,
                note = item.note,
                followUpDays = days,
                originalBrief = item.brief,
                briefIsUserWritten = item.briefIsUserWritten,
                startedAt = item.startedAt,
                origin = item.origin,
                createdAt = item.createdAt,
            )
        }
    }
}

private val FollowUpDayOptions = listOf(0, 7, 14, 30, 60, 90)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationEditScreen(
    draft: MedicationDraft,
    onSave: (MedicationItem) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf(draft.name) }
    var status by remember { mutableStateOf(draft.status) }
    var whenText by remember { mutableStateOf(draft.whenText) }
    var reason by remember { mutableStateOf(draft.reason) }
    var outcome by remember { mutableStateOf(draft.outcome) }
    var brief by remember { mutableStateOf(draft.brief) }
    var note by remember { mutableStateOf(draft.note) }
    var followUpDays by remember { mutableIntStateOf(draft.followUpDays) }
    var showStatuses by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (draft.id == null) "加一条" else "编辑") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "取消")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            onSave(
                                draft.copy(
                                    name = name,
                                    status = status,
                                    whenText = whenText,
                                    reason = reason,
                                    outcome = outcome,
                                    brief = brief,
                                    note = note,
                                    followUpDays = followUpDays,
                                ).applied(),
                            )
                        },
                        enabled = name.isNotBlank(),
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
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("名字，比如：褪黑素") },
                singleLine = true,
            )
            Text(
                "关系：${status.label}",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showStatuses = !showStatuses }
                    .padding(vertical = 8.dp),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(status.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (showStatuses) {
                MedicationItem.Status.entries.forEach { option ->
                    Text(
                        option.label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                status = option
                                showStatuses = false
                            }
                            .padding(vertical = 6.dp),
                        color = if (option == status) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
            OutlinedTextField(
                value = whenText,
                onValueChange = { whenText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("什么情况下吃，比如：头疼时") },
            )
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("为什么吃 / 谁让你吃的") },
            )
            Text(
                "不用写剂量。Vana 不做用药提醒，剂量和按时吃在「健康」App 里管更合适。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("你自己的评价", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = outcome,
                onValueChange = { outcome = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("有没有用？有什么感觉？") },
            )
            Text(
                "记下来，Vana 下次就不会再推荐一次你试过的东西。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("过多久回头看", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FollowUpDayOptions.forEach { days ->
                    FilterChip(
                        selected = followUpDays == days,
                        onClick = { followUpDays = days },
                        label = {
                            Text(if (days == 0) "不用" else "${days}天后")
                        },
                    )
                }
            }
            Text(
                "到时候 Vana 会在早上那条消息里问你一句有没有用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = brief,
                onValueChange = { brief = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("一般说明") },
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("备注") },
            )
            Text(
                "「一般说明」原本由 Vana 自动写，改过之后就不会再被自动覆盖。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
