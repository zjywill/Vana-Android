package com.pinapia.vana.update

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.pinapia.vana.BuildConfig
import com.pinapia.vana.ui.L10n
import com.pinapia.vana.ui.uiText
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data class UpToDate(val latest: String) : UpdateUi
    data class Available(val release: AppRelease) : UpdateUi
    data class Failed(val message: String) : UpdateUi
}

@Composable
fun CheckForUpdatesRow(
    currentVersion: String = BuildConfig.VERSION_NAME,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val client = remember { GitHubReleaseClient.github() }
    val installer = remember(context.applicationContext) {
        AppUpdateInstaller(context.applicationContext)
    }
    var ui by remember { mutableStateOf<UpdateUi>(UpdateUi.Idle) }
    var showDialog by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var pendingApk by remember { mutableStateOf<File?>(null) }

    fun launchInstall(file: File) {
        val intent = installer.installIntent(file)
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        val file = pendingApk ?: return@rememberLauncherForActivityResult
        if (installer.canInstallPackages()) {
            launchInstall(file)
        }
        pendingApk = null
    }

    suspend fun refresh(): UpdateUi {
        return try {
            when (val status = AppUpdate.evaluate(currentVersion, client.latest())) {
                is AppUpdateStatus.UpToDate -> UpdateUi.UpToDate(status.latestVersion)
                is AppUpdateStatus.Available -> UpdateUi.Available(status.release)
            }
        } catch (error: AppUpdateError) {
            UpdateUi.Failed(error.message ?: L10n.text("连不上 GitHub，过一会儿再试。", "Could not reach GitHub. Try again later."))
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            UpdateUi.Failed(L10n.text("连不上 GitHub，过一会儿再试。", "Could not reach GitHub. Try again later."))
        }
    }

    LaunchedEffect(currentVersion) {
        ui = refresh()
    }

    SettingsPickerRow(
        label = uiText("检查更新", "Check for updates"),
        value = when (val state = ui) {
            UpdateUi.Idle -> currentVersion
            UpdateUi.Checking -> uiText("正在检查…", "Checking…")
            is UpdateUi.UpToDate -> uiText("已是最新 ${state.latest}", "Up to date: ${state.latest}")
            is UpdateUi.Available -> uiText("有新版本 ${state.release.versionName}", "New version: ${state.release.versionName}")
            is UpdateUi.Failed -> uiText("检查失败", "Check failed")
        },
        onClick = {
            when (val state = ui) {
                is UpdateUi.Available -> showDialog = true
                else -> {
                    ui = UpdateUi.Checking
                    scope.launch {
                        val next = refresh()
                        ui = next
                        showDialog = next is UpdateUi.Available
                    }
                }
            }
        },
    )
    when (val state = ui) {
        is UpdateUi.Failed -> Text(
            state.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        is UpdateUi.Available -> Text(
            uiText(
                "点这一行下载 GitHub Release 上的签名包。",
                "Tap this row to download the signed package from GitHub Releases.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Text(
            uiText(
                "对照 GitHub Release 检查是否有新的签名包。当前 $currentVersion。",
                "Checks GitHub Releases for a newer signed package. Current version: $currentVersion.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val available = (ui as? UpdateUi.Available)?.release
    if (showDialog && available != null) {
        UpdateAvailableDialog(
            release = available,
            downloading = downloading,
            progress = progress,
            onDismiss = {
                downloadJob?.cancel()
                downloadJob = null
                downloading = false
                showDialog = false
            },
            onOpenGithub = {
                uriHandler.openUri(available.htmlUrl)
                showDialog = false
            },
            onDownload = {
                val apk = available.apk
                if (apk == null) {
                    uriHandler.openUri(available.htmlUrl)
                    showDialog = false
                    return@UpdateAvailableDialog
                }
                downloading = true
                progress = null
                downloadJob = scope.launch {
                    try {
                        val file = installer.download(apk) { progress = it }
                        if (!installer.canInstallPackages()) {
                            pendingApk = file
                            permissionLauncher.launch(installer.installPermissionSettingsIntent())
                        } else {
                            launchInstall(file)
                        }
                        showDialog = false
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        // 用户关掉对话框
                    } catch (error: Exception) {
                        ui = UpdateUi.Failed(error.message ?: L10n.text("下载失败。", "Download failed."))
                        showDialog = false
                    } finally {
                        downloading = false
                        downloadJob = null
                    }
                }
            },
        )
    }
}

@Composable
private fun UpdateAvailableDialog(
    release: AppRelease,
    downloading: Boolean,
    progress: Float?,
    onDismiss: () -> Unit,
    onOpenGithub: () -> Unit,
    onDownload: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text(uiText("发现新版本 ${release.versionName}", "Version ${release.versionName} is available")) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    release.notes.ifBlank {
                        uiText("到 GitHub 下载签名安装包。", "Download the signed package from GitHub.")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (downloading) {
                    if (progress == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        if (progress == null) {
                            uiText("正在下载…", "Downloading…")
                        } else {
                            uiText("正在下载 ${(progress * 100).toInt()}%", "Downloading ${(progress * 100).toInt()}%")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload, enabled = !downloading) {
                Text(
                    if (release.apk == null) uiText("打开 GitHub", "Open GitHub")
                    else uiText("下载安装", "Download and install"),
                )
            }
        },
        dismissButton = {
            Column {
                if (release.apk != null) {
                    TextButton(onClick = onOpenGithub, enabled = !downloading) {
                        Text(uiText("打开 GitHub", "Open GitHub"))
                    }
                }
                TextButton(onClick = onDismiss, enabled = !downloading) {
                    Text(uiText("稍后", "Later"))
                }
            }
        },
    )
}

@Composable
private fun SettingsPickerRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
