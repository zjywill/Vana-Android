package com.pinapia.vana.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.pinapia.vana.ui.L10n
import com.pinapia.vana.ui.uiText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderPickerSheet(
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            CloudCatalog.providers
        } else {
            CloudCatalog.providers.filter {
                it.displayName.contains(keyword, ignoreCase = true) ||
                    it.id.contains(keyword, ignoreCase = true)
            }
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                uiText("选择 Provider", "Choose provider"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text(uiText("搜索 provider", "Search providers")) },
                singleLine = true,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .padding(top = 8.dp),
            ) {
                items(matches, key = { it.id }) { provider ->
                    val count = CloudCatalog.models(provider.id).size
                    val subtitle = if (count == 0) {
                        provider.id
                    } else {
                        uiText("${provider.id} · $count 个模型", "${provider.id} · $count models")
                    }
                    PickerRow(
                        title = provider.displayName,
                        subtitle = subtitle,
                        selected = provider.id == selectedId,
                        onClick = {
                            onSelect(provider.id)
                            onDismiss()
                        },
                    )
                }
                item {
                    Text(
                        uiText(
                            "共 ${CloudCatalog.providers.count()} 个 provider，来自 AIKit 内置目录。",
                            "${CloudCatalog.providers.count()} providers from the built-in AIKit catalog.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    providerId: String,
    selectedId: String,
    apiKey: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var customId by remember { mutableStateOf("") }
    var fetched by remember { mutableStateOf<List<CloudCatalog.ModelInfo>>(emptyList()) }
    var isFetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun filtered(models: List<CloudCatalog.ModelInfo>): List<CloudCatalog.ModelInfo> {
        val keyword = query.trim()
        if (keyword.isEmpty()) return models
        return models.filter {
            it.displayName.contains(keyword, ignoreCase = true) ||
                it.id.contains(keyword, ignoreCase = true)
        }
    }

    val catalogMatches = filtered(CloudCatalog.models(providerId))
    val known = CloudCatalog.models(providerId).map { it.id }.toSet()
    val fetchedMatches = filtered(fetched.filter { it.id !in known })

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                CloudCatalog.providerName(providerId),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text(uiText("搜索模型", "Search models")) },
                singleLine = true,
            )

            if (catalogMatches.isNotEmpty()) {
                SectionLabel(uiText("内置目录", "Built-in catalog"))
                catalogMatches.forEach { model ->
                    ModelPickerRow(
                        model = model,
                        selected = model.id == selectedId,
                        onClick = {
                            onSelect(model.id)
                            onDismiss()
                        },
                    )
                }
                Text(
                    uiText(
                        "只列出支持工具调用的模型——不支持的模型无法使用用药、测量和记忆能力。",
                        "Only models with tool calling are shown; other models cannot use medications, measurements or memory.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            if (fetchedMatches.isNotEmpty()) {
                SectionLabel(uiText("服务端返回", "Returned by provider"))
                fetchedMatches.forEach { model ->
                    ModelPickerRow(
                        model = model,
                        selected = model.id == selectedId,
                        onClick = {
                            onSelect(model.id)
                            onDismiss()
                        },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp))
            TextButton(
                onClick = {
                    if (isFetching) return@TextButton
                    isFetching = true
                    fetchError = null
                    scope.launch {
                        try {
                            val list = withContext(Dispatchers.IO) {
                                CloudCatalog.fetchModels(providerId, apiKey)
                            }
                            fetched = list
                            if (list.isEmpty()) {
                                fetchError = L10n.text("服务端没有返回模型", "The provider returned no models")
                            }
                        } catch (error: Throwable) {
                            fetchError = L10n.text(
                                "获取失败：${error.message ?: error.javaClass.simpleName}",
                                "Fetch failed: ${error.message ?: error.javaClass.simpleName}",
                            )
                        } finally {
                            isFetching = false
                        }
                    }
                },
                enabled = !isFetching,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(
                    if (isFetching) uiText("正在获取…", "Fetching…")
                    else uiText("从服务端获取模型列表", "Fetch model list from provider"),
                )
            }
            fetchError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            Text(
                if (catalogMatches.isEmpty() && fetched.isEmpty()) {
                    uiText(
                        "该 provider 没有内置模型列表，请用已保存的 API key 获取，或直接填写模型 ID。",
                        "This provider has no built-in model list. Fetch it with the saved API key or enter a model ID.",
                    )
                } else {
                    uiText(
                        "获取会用已保存的 API key 向该 provider 查询当前可用模型。",
                        "Fetching uses the saved API key to query currently available models.",
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )

            SectionLabel(uiText("自定义模型 ID", "Custom model ID"))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = customId,
                    onValueChange = { customId = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(uiText("例如 llama3.1", "For example, llama3.1")) },
                    singleLine = true,
                )
                TextButton(
                    onClick = {
                        val value = customId.trim()
                        if (value.isNotEmpty()) {
                            onSelect(value)
                            onDismiss()
                        }
                    },
                    enabled = customId.trim().isNotEmpty(),
                ) { Text(uiText("使用", "Use")) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ModelPickerRow(
    model: CloudCatalog.ModelInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val limits = CloudCatalog.limitSummary(model)
    val subtitle = if (limits != null) "${model.id} · $limits" else model.id
    PickerRow(
        title = model.displayName,
        subtitle = subtitle,
        selected = selected,
        onClick = onClick,
        below = { ModelCapabilityTags(model = model) },
    )
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    below: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            below?.let {
                Spacer(modifier = Modifier.padding(top = 4.dp))
                it()
            }
        }
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = uiText("已选中", "Selected"),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
