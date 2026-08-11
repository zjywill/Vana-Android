package com.pinapia.vana

import android.app.Application

/**
 * 进程入口。
 *
 * iOS 那边 `HealthChatApp.init` 里同步跑 `TenantScope.bootstrap()`——名单必须在任何视图建起来
 * 之前就位,否则启动路径上会有一个「还不知道当前是谁」的窗口,而那个窗口里读到的是别人的数据。
 * 这里是同一个位置:成员名单、数据目录迁移这类事该在 `onCreate` 里同步做完。
 *
 * 现在是空的——脚手架阶段还没有东西要 bootstrap。
 */
class VanaApplication : Application()
