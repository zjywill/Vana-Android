package com.pinapia.vana.ask

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

@Composable
fun AskUserCard(
    question: AskUserQuestion,
    answer: AskUserAnswer?,
    isLive: Boolean,
    onAnswer: (AskUserAnswer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAnswered = answer != null
    var selected by remember(question.question, question.options) { mutableStateOf(setOf<String>()) }
    var custom by remember(question.question) { mutableStateOf("") }
    val needsConfirmation = question.allowsMultiple || custom.isNotBlank()
    val interactive = isLive && !isAnswered

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (isLive || isAnswered) 1f else 0.55f),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(question.question, style = MaterialTheme.typography.titleSmall)

            question.options.forEachIndexed { index, option ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(start = 28.dp))
                val chosen = if (isAnswered) {
                    answer?.choices?.contains(option.label) == true
                } else {
                    selected.contains(option.label)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = interactive) {
                            if (question.allowsMultiple) {
                                selected = if (selected.contains(option.label)) {
                                    selected - option.label
                                } else {
                                    selected + option.label
                                }
                            } else {
                                selected = setOf(option.label)
                                if (custom.isBlank()) {
                                    onAnswer(AskUserAnswer(choices = listOf(option.label)))
                                }
                            }
                        }
                        .padding(vertical = 10.dp),
                ) {
                    Text(
                        text = buildString {
                            append(if (chosen) "● " else "○ ")
                            append(option.label)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (option.detail.isNotEmpty()) {
                        Text(
                            option.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 18.dp),
                        )
                    }
                }
            }

            if (!isAnswered) {
                HorizontalDivider(modifier = Modifier.padding(start = 28.dp))
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = interactive,
                    placeholder = { Text("都不是，我自己说") },
                    maxLines = 3,
                )
            }

            when {
                answer != null -> {
                    Text(
                        if (answer.declined) "已跳过" else "已回答",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                interactive -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { onAnswer(AskUserAnswer(declined = true)) }) {
                            Text("跳过")
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (needsConfirmation) {
                            TextButton(
                                onClick = {
                                    val payload = AskUserAnswer(
                                        choices = selected.toList(),
                                        custom = custom,
                                    )
                                    if (!payload.isEmpty) onAnswer(payload)
                                },
                                enabled = selected.isNotEmpty() || custom.isNotBlank(),
                            ) {
                                Text(if (custom.isNotBlank()) "发送" else "确认")
                            }
                        }
                    }
                }
            }
        }
    }
}
