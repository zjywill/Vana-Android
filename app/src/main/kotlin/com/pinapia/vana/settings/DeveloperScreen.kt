package com.pinapia.vana.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pinapia.vana.Features
import com.pinapia.vana.checkin.CheckInScheduler
import com.pinapia.vana.health.DebugSeeder
import com.pinapia.vana.health.HealthStore
import kotlinx.coroutines.launch

/**
 * 只在 Debug 构建里从设置页进入的开发工具。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperScreen(
    healthStore: HealthStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var status by remember { mutableStateOf<DeveloperStatus?>(null) }
    var isSeeding by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val seeder = remember { DebugSeeder(healthStore) }
    val writePermissionLauncher = rememberLauncherForActivityResult(
        contract = healthStore.writePermissionContract(),
    ) { }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("开发") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("健康数据", style = MaterialTheme.typography.titleMedium)
            if (!Features.HEALTH_CONNECT) {
                Text(
                    "Features.HEALTH_CONNECT = false（大陆机暂缓）。种子写入不可用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
            Text(
                "种子数据会写入最近 30 天的模拟健康记录，可重复执行。自检把每个查询工具都跑一遍，结果输出到 logcat。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    if (isSeeding || isChecking) return@Button
                    writePermissionLauncher.launch(healthStore.writePermissions)
                    isSeeding = true
                    status = DeveloperStatus("正在写入健康数据…", isError = false)
                    scope.launch {
                        try {
                            val result = seeder.seed()
                            status = when {
                                !result.writesAvailable -> DeveloperStatus(
                                    "Health Connect 写权限不可用。已在 debug Manifest 声明 WRITE，请在系统授权面板勾选写入项后再试。",
                                    isError = true,
                                )
                                result.skipped.isEmpty() -> DeveloperStatus(
                                    "已写入最近 30 天的种子数据",
                                    isError = false,
                                )
                                else -> DeveloperStatus(
                                    "已写入种子数据，跳过未授权：${result.skipped.joinToString("、")}",
                                    isError = false,
                                )
                            }
                        } catch (error: Throwable) {
                            status = DeveloperStatus(
                                "写入失败：${error.message ?: error::class.java.simpleName}",
                                isError = true,
                            )
                        } finally {
                            isSeeding = false
                        }
                    }
                },
                enabled = !isSeeding && !isChecking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("写入种子数据")
            }
            Button(
                onClick = {
                    if (isSeeding || isChecking) return@Button
                    isChecking = true
                    status = DeveloperStatus("正在运行自检…", isError = false)
                    scope.launch {
                        try {
                            seeder.selfCheck()
                            status = DeveloperStatus("自检完成，结果已输出到 logcat（tag=VanaDebug）", isError = false)
                        } catch (error: Throwable) {
                            status = DeveloperStatus(
                                "自检失败：${error.message ?: error::class.java.simpleName}",
                                isError = true,
                            )
                        } finally {
                            isChecking = false
                        }
                    }
                },
                enabled = !isSeeding && !isChecking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("自检查询")
            }
            }

            Text("通知", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = {
                    scope.launch {
                        val message = CheckInScheduler.sendTest(context)
                        status = DeveloperStatus(message, isError = false)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("发一条测试 check-in")
            }

            status?.let {
                Text(
                    it.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private data class DeveloperStatus(val message: String, val isError: Boolean)
