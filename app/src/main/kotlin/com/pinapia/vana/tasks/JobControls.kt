package com.pinapia.vana.tasks

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 后台任务的「手」:开始、重试、停止、对提议做决定。界面和 `start_task` 工具都经它,
 * 谁来接线(要碰模型、闹钟、记忆)由外壳定,这一层不认识 Android。
 */
interface JobControls {
    /** 设置里「只读任务自动开始」开着吗。默认关:每个任务先弹确认卡,用户点了才跑。 */
    val autoStart: Boolean

    /** 开始(或重试)。缺配置、缺同意、超限时**不**开始,原因写进任务的 `error` 让卡片显示。 */
    fun start(taskId: String)

    fun stop(taskId: String)

    fun decide(taskId: String, proposalId: String, accept: Boolean)

    companion object {
        val none = object : JobControls {
            override val autoStart = false
            override fun start(taskId: String) = Unit
            override fun stop(taskId: String) = Unit
            override fun decide(taskId: String, proposalId: String, accept: Boolean) = Unit
        }
    }
}

val LocalJobControls = staticCompositionLocalOf<JobControls> { JobControls.none }
val LocalTasksEnvironment = staticCompositionLocalOf<TasksEnvironment?> { null }
