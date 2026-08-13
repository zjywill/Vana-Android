package com.pinapia.vana.vision

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.pinapia.vana.medications.MedicationDraft
import com.pinapia.vana.medications.MedicationEditScreen
import com.pinapia.vana.medications.MedicationItem

/**
 * 发出去之前核对这一张。
 *
 * 识别错一个小数点在健康场景里不是脏数据，所以这段文字可改。
 * 模型看不了图时，「让 Vana 直接看这张图」那颗开关整个不出现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentReviewScreen(
    draft: DraftAttachment,
    supportsVision: Boolean,
    visionUnavailableNote: String?,
    onChangeText: (String) -> Unit,
    onChangeSendsImage: ((Boolean) -> Unit)?,
    onRemove: () -> Unit,
    onSaveMedication: (MedicationItem, onSaved: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(draft.id) { mutableStateOf(draft.text) }
    var medicationDraft by remember { mutableStateOf<MedicationDraft?>(null) }
    var savedMedication by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(draft.isRecognizing, draft.text) {
        if (!draft.isRecognizing && text.isEmpty() && draft.text.isNotEmpty()) {
            text = draft.text
        }
    }

    val editing = medicationDraft
    if (editing != null) {
        MedicationEditScreen(
            draft = editing,
            onSave = { item ->
                onSaveMedication(item) { name ->
                    savedMedication = name
                    medicationDraft = null
                }
            },
            onCancel = { medicationDraft = null },
            modifier = modifier,
        )
        return
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("核对识别结果") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "完成")
                    }
                },
                actions = {
                    TextButton(onClick = onDismiss) { Text("完成") }
                },
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (draft.preview != null) {
                Image(
                    bitmap = draft.preview!!.asImageBitmap(),
                    contentDescription = draft.documentName ?: "照片预览",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    draft.documentName ?: "文件",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Text(
                footprint(draft, visionUnavailableNote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (onChangeSendsImage != null && draft.canSendImage) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "让 Vana 直接看这张图",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = draft.sendsImage,
                        onCheckedChange = onChangeSendsImage,
                    )
                }
                Text(
                    if (draft.hasText) {
                        "文字已经识别出来了，上面那段就够回答问题。原图上还有姓名、就诊号、" +
                            "医院和医生签名——真要发的话，它会一起发到你配置的模型服务上。"
                    } else {
                        "本机一个字都没认出来。一顿饭、一处皮疹这类照片的信息本来就不是字，" +
                            "让模型直接看图才答得上——但那意味着这张照片本身会发到你配置的模型服务上。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!supportsVision && !draft.isDocument) {
                Text(
                    "当前模型看不了图，发给它的只有下面这段文字。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    onChangeText(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp),
                label = { Text(if (draft.isDocument) "文件里的文字" else "识别出的文字") },
                supportingText = { Text(reviewFooter(draft, canSendImage = onChangeSendsImage != null)) },
                enabled = !draft.isRecognizing && !draft.isLoading,
            )

            TextButton(
                onClick = { medicationDraft = MedicationDraft.fromRecognizedText(text) },
                enabled = draft.hasText || text.isNotBlank(),
            ) {
                Text("记入用药与补剂")
            }
            savedMedication?.let { name ->
                Text(
                    "已记下「$name」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TextButton(
                onClick = onRemove,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("不发这张")
            }
        }
    }
}

private fun footprint(draft: DraftAttachment, visionUnavailableNote: String?): String {
    if (draft.isDocument) return "文件留在这台手机上，发给模型的只有下面这段文字。"
    if (draft.sendsImage) {
        return "这张照片本身会发到你配置的模型服务上。关掉下面那个开关，就只发识别出来的文字。"
    }
    val base = "图片留在这台手机上，发给模型的只有下面这段文字。"
    return if (visionUnavailableNote.isNullOrBlank()) base else "$base$visionUnavailableNote"
}

private fun reviewFooter(draft: DraftAttachment, canSendImage: Boolean): String {
    draft.failure?.let { return it }
    if (!draft.hasText) {
        return when {
            draft.sendsImage -> "这张图里没认出文字，原图会随这句话一起发出去，让模型直接看。"
            draft.isDocument ->
                "这份文件里没取到正文。里面如果是扫描件（整页都是图），先导出成 PDF 或者直接拍一张。"
            canSendImage ->
                "这张图里没认出文字。Vana 现在只能读照片里的字——一顿饭、一处皮疹这类，" +
                    "可以打开上面那个开关让它直接看图。"
            else ->
                "这张图里没认出文字。当前模型看不了图，发给它的只有识别结果；" +
                    "换一个支持看图的模型才能让它直接看。"
        }
    }
    if (draft.droppedLines > 0) {
        return "太长了，后面 ${draft.droppedLines} 行没有带进来。删掉用不上的几段，再把要问的那几项留下。"
    }
    return if (draft.isDocument) {
        "改成什么样，发出去的就是什么样。用不上的段落可以直接删掉。"
    } else {
        "改成什么样，发出去的就是什么样。数值和单位值得对一眼。"
    }
}
