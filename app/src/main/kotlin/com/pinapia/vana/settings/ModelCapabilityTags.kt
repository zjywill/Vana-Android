package com.pinapia.vana.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 一个模型能做什么,用几颗小标签说清。
 *
 * 只标会改变 app 行为的能力:看图、思考、不支持工具。
 */
@Composable
fun ModelCapabilityTags(
    model: CloudCatalog.ModelInfo,
    modifier: Modifier = Modifier,
) {
    val tags = buildList {
        if (model.supportsVision) add(Tag("看图", MaterialTheme.colorScheme.primary))
        if (model.supportsReasoning) add(Tag("思考", Color(0xFF7B61FF)))
        if (!model.supportsTools) {
            add(Tag("不支持工具", MaterialTheme.colorScheme.tertiary))
        }
    }
    if (tags.isEmpty()) return
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = tags.joinToString("，") { it.title }
        },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        tags.forEach { tag ->
            Text(
                text = tag.title,
                style = MaterialTheme.typography.labelSmall,
                color = tag.tint,
                modifier = Modifier
                    .background(tag.tint.copy(alpha = 0.12f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
fun ModelCapabilityTagsFor(
    modelId: String,
    providerId: String,
    modifier: Modifier = Modifier,
) {
    val info = CloudCatalog.model(modelId, providerId) ?: return
    ModelCapabilityTags(model = info, modifier = modifier)
}

private data class Tag(val title: String, val tint: Color)
