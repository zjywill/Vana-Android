package com.pinapia.vana.plugins

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pinapia.vana.settings.EngineSettings
import com.pinapia.vana.tenant.TenantScope
import com.pinapia.vana.ui.icons.VanaIcons
import com.pinapia.vana.ui.uiText

/**
 * 插件页:每个能整个开关的插件一行(名字、一句话、开没开),点进去是它的详情页([PluginDetailScreen])。
 *
 * **一件设置归不归插件,只看一个问题:关掉这个插件,它还有没有意义。** 没有意义的(用药表、测量卡片、
 * 家人档案)在插件自己的详情页里,关掉插件就一起收起来;还有意义的(模型、搜索、位置、照片、语音、记忆、
 * check-in、后台任务)留在「设置」。核心(记忆、召回、搜索……)不在这里:用户关不掉它。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(
    engineSettings: EngineSettings,
    onBack: () -> Unit,
    onOpenPlugin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(uiText("插件", "Plugins")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(VanaIcons.ArrowLeft, contentDescription = uiText("返回", "Back"))
                    }
                },
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            PluginRegistry.togglable.forEach { plugin ->
                val enabled = engineSettings.isPluginEnabled(plugin.manifest.id)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPlugin(plugin.manifest.id) }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(plugin.manifest.name.text, style = MaterialTheme.typography.titleMedium)
                        Text(
                            plugin.manifest.summary.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (enabled) uiText("已开启", "On") else uiText("已关闭", "Off"),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        VanaIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
            Text(
                uiText(
                    "插件是可以整个开关的能力。关掉之后，Vana 不再带上它的规则、工具和数据；数据本身留在本机，重新打开就回来。",
                    "Plugins are capabilities you can switch off entirely. When off, Vana no longer uses their rules, tools or data; the data stays on this device and comes back when you turn it on again.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
}

/**
 * 一个插件的详情页:开关、它自己的页面和设置、它自己的免责声明。关着的时候只剩开关和一句说明。
 * 插件只说「是什么」(`surface.id`),去哪儿由外壳定([onOpenSurface])。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginDetailScreen(
    pluginId: String,
    engineSettings: EngineSettings,
    onBack: () -> Unit,
    onOpenSurface: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val plugin = PluginRegistry.togglable.firstOrNull { it.manifest.id == pluginId } ?: return
    // 开关写进 SharedPreferences,不是 Compose 状态:写完拨一下这个数,让下面重新读。
    var version by remember { mutableIntStateOf(0) }
    fun set(id: String, enabled: Boolean) {
        engineSettings.setPluginEnabled(id, enabled)
        version++
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(plugin.manifest.name.text) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(VanaIcons.ArrowLeft, contentDescription = uiText("返回", "Back"))
                    }
                },
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // version 只用来在开关变化后重新执行下面的读取。
            @Suppress("UNUSED_EXPRESSION") version
            val enabled = engineSettings.isPluginEnabled(plugin.manifest.id)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    uiText("启用", "Enabled"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = enabled, onCheckedChange = { set(plugin.manifest.id, it) })
            }
            Text(
                plugin.manifest.summary.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (enabled) {
                plugin.surfaces
                    .filter { it.id != PluginSurface.FAMILY || TenantScope.isolationAvailable }
                    .forEach { surface ->
                        HorizontalDivider()
                        SurfaceRow(
                            surface = surface,
                            toggled = surface.toggleId?.let { engineSettings.isPluginEnabled(it) },
                            onToggle = { on -> surface.toggleId?.let { set(it, on) } },
                            onClick = { onOpenSurface(surface.id) },
                        )
                    }
                plugin.disclaimer?.let {
                    HorizontalDivider()
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SurfaceRow(
    surface: PluginSurface,
    toggled: Boolean?,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(surface.title.text, style = MaterialTheme.typography.bodyLarge)
            Text(
                surface.subtitle.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 整行点开是入口页;带子开关的那几行开关在前、箭头在后——只有开关的话,
        // 用户看不出这一行还能点进去。
        if (toggled != null) {
            Switch(checked = toggled, onCheckedChange = onToggle)
            Spacer(modifier = Modifier.width(4.dp))
        }
        Icon(
            VanaIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
