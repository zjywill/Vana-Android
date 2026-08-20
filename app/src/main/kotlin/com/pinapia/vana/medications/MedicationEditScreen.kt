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
import com.pinapia.vana.ui.uiText
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
        /**
         * 拍药瓶 → 识别 → 落进用药表。只预填、不代填：名字猜错了他当场就能改。
         */
        fun fromRecognizedText(text: String): MedicationDraft = MedicationDraft(
            status = MedicationItem.Status.ONGOING,
            name = guessedName(from = text),
            note = text,
        )

        /** 第一行、去掉列分隔之后的第一格。药瓶上字最大的那一行几乎总是商品名。 */
        fun guessedName(from: String): String {
            val firstLine = from.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
            val firstCell = firstLine
                .split(com.pinapia.vana.vision.RecognizedTextLayout.COLUMN_SEPARATOR)
                .firstOrNull()
                ?.trim()
                .orEmpty()
            return firstCell.take(24)
        }

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
                title = { Text(if (draft.id == null) uiText("加一条", "Add item") else uiText("编辑", "Edit")) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = uiText("取消", "Cancel"))
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
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(uiText("名字，比如：褪黑素", "Name, for example melatonin")) },
                singleLine = true,
            )
            Text(
                uiText("关系：${status.label}", "Status: ${status.label}"),
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
                label = { Text(uiText("什么情况下吃，比如：头疼时", "When you take it, for example for a headache")) },
            )
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(uiText("为什么吃 / 谁让你吃的", "Why you take it or who recommended it")) },
            )
            Text(
                uiText(
                    "不用写剂量。Vana 不做用药提醒，也不提供剂量建议。",
                    "Do not enter a dose. Vana does not provide dose advice or medication reminders.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(uiText("你自己的评价", "Your assessment"), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = outcome,
                onValueChange = { outcome = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(uiText("有没有用？有什么感觉？", "Did it help? How did it feel?")) },
            )
            Text(
                uiText(
                    "记下来，Vana 下次就不会再推荐一次你试过的东西。",
                    "Record the result so Vana does not suggest something that already failed for you.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(uiText("过多久回头看", "Follow up after"), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FollowUpDayOptions.forEach { days ->
                    FilterChip(
                        selected = followUpDays == days,
                        onClick = { followUpDays = days },
                        label = {
                            Text(if (days == 0) uiText("不用", "None") else uiText("${days}天后", "$days days"))
                        },
                    )
                }
            }
            Text(
                uiText(
                    "到时候 Vana 会在早上那条消息里问你一句有没有用。",
                    "Vana will include a brief follow-up in the morning check-in.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = brief,
                onValueChange = { brief = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(uiText("一般说明", "General information")) },
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(uiText("备注", "Notes")) },
            )
            Text(
                uiText(
                    "「一般说明」原本由 Vana 自动写，改过之后就不会再被自动覆盖。",
                    "Vana can generate the general information. Once you edit it, it will not be overwritten automatically.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
