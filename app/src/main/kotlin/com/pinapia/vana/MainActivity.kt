package com.pinapia.vana

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pinapia.vana.ui.theme.VanaTheme

/**
 * 单 Activity。所有界面都在 Compose 里,导航走 navigation-compose。
 *
 * check-in 通知、快捷方式这类外部入口最终都落到这里,由一个 launch router 分发——
 * 对应 iOS 的 `VanaLaunchRouter` / `CheckInLaunch`。多一条路进 app 不该多一套载入逻辑。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            VanaTheme {
                PlaceholderScreen()
            }
        }
    }
}
