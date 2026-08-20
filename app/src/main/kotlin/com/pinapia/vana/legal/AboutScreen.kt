package com.pinapia.vana.legal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.pinapia.vana.BuildConfig
import com.pinapia.vana.exercises.ExerciseLibrary
import com.pinapia.vana.update.CheckForUpdatesRow
import com.pinapia.vana.ui.uiText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenDataUse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(uiText("关于", "About")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = uiText("返回", "Back"))
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(uiText("免责声明", "Disclaimer"), style = MaterialTheme.typography.titleMedium)
            Text(
                DataUseNotice.medicalDisclaimer,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Text(uiText("数据会发送到哪里", "Where your data goes"), style = MaterialTheme.typography.titleMedium)
            Text(
                uiText("和你第一次打开 Vana 时看到的是同一份。", "This is the same notice shown on first launch."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                uiText("数据会发送到哪里", "Where your data goes"),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenDataUse)
                    .padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                uiText("隐私说明", "Privacy policy"),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenPrivacy)
                    .padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary,
            )
            HorizontalDivider()
            Text(uiText("图片出处", "Image credits"), style = MaterialTheme.typography.titleMedium)
            ExerciseLibrary.attributions.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            Text(uiText("版本", "Version") + " ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            if (BuildConfig.ALLOW_SELF_UPDATE) {
                CheckForUpdatesRow()
            }
            Text(
                uiText("项目地址", "Project repository"),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        uriHandler.openUri("https://github.com/zjywill/Vana-Android")
                    }
                    .padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
