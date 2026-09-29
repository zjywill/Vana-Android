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
 * 插件页:每个能整个开关的插件一段——开关、一句话、它自己的入口页、它自己的免责声明。
 *
 * 以前用药和测量的开关散在设置里、入口占着聊天顶栏的两个图标。现在它们都属于「健康」这一个插件,
 * 关掉健康,下面的入口和子开关一起收起来。核心(记忆、召回、搜索……)不在这里:用户关不掉它。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(
    engineSettings: EngineSettings,
    onBack: () -> Unit,
    onOpenSurface: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                uiText(
                    "插件是可以整个开关的能力。关掉之后，Vana 不再带上它的规则、工具和数据。",
                    "Plugins are capabilities you can switch off entirely. When off, Vana no longer uses their rules, tools or data.",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            // version 只用来在开关变化后重新执行下面的读取。
            @Suppress("UNUSED_EXPRESSION") version
            PluginRegistry.togglable.forEach { plugin ->
                val id = plugin.manifest.id
                val enabled = engineSettings.isPluginEnabled(id)
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        plugin.manifest.name.text,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = enabled, onCheckedChange = { set(id, it) })
                }
                Text(
                    plugin.manifest.summary.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (enabled) {
                    plugin.surfaces
                        .filter { it.id != PluginSurface.FAMILY || TenantScope.isolationAvailable }
                        .forEach { surface ->
                            SurfaceRow(
                                surface = surface,
                                toggled = surface.toggleId?.let { engineSettings.isPluginEnabled(it) },
                                onToggle = { on -> surface.toggleId?.let { set(it, on) } },
                                onClick = { onOpenSurface(surface.id) },
                            )
                        }
                    plugin.disclaimer?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                        )
                    }
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
