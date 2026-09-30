package com.pinapia.vana

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pinapia.vana.chat.ChatScreen
import com.pinapia.vana.chat.HostedSideChat
import com.pinapia.vana.chat.SideChatHost
import com.pinapia.vana.chat.SideChatListScreen
import com.pinapia.vana.chat.SideChatVisit
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import com.pinapia.vana.thread.SideChat
import com.pinapia.vana.chat.ChatViewModel
import com.pinapia.vana.checkin.CheckInScheduler
import com.pinapia.vana.exercises.ExerciseLibrary
import com.pinapia.vana.intents.VanaLaunchRouter
import com.pinapia.vana.legal.AboutScreen
import com.pinapia.vana.legal.DataUseDetailScreen
import com.pinapia.vana.legal.DataUseNoticeScreen
import com.pinapia.vana.legal.PrivacyPolicyScreen
import com.pinapia.vana.medications.MedicationItem
import com.pinapia.vana.medications.MedicationListScreen
import com.pinapia.vana.measurements.MeasurementListScreen
import com.pinapia.vana.memory.MemoryListScreen
import com.pinapia.vana.recall.BackgroundDigest
import com.pinapia.vana.settings.DeveloperScreen
import com.pinapia.vana.settings.SettingsScreen
import com.pinapia.vana.memory.MemoryHarvester
import com.pinapia.vana.plugins.PluginSurface
import com.pinapia.vana.plugins.PluginDetailScreen
import com.pinapia.vana.plugins.PluginsScreen
import com.pinapia.vana.settings.CloudCatalog
import com.pinapia.vana.notes.NotesScreen
import com.pinapia.vana.tasks.LocalTasksEnvironment
import com.pinapia.vana.tasks.ReminderScheduler
import com.pinapia.vana.tasks.TaskDetailScreen
import com.pinapia.vana.tasks.TasksEnvironment
import com.pinapia.vana.tenant.TenantListScreen
import com.pinapia.vana.tenant.TenantScope
import com.pinapia.vana.today.TodayAction
import com.pinapia.vana.today.TodayScreen
import com.pinapia.vana.ui.L10n
import com.pinapia.vana.ui.uiText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 目标那条侧聊里替他起的头。放进输入框,他看一眼再发。 */
private fun goalReviewPrompt(title: String): String =
    L10n.text("回顾一下「$title」最近的进展，接下来该做什么？", "Let's look back at how \"$title\" has been going. What should I do next?")

/** 插件页和「今天」页上的入口 id 对到路由。插件只说「是什么」,去哪儿由外壳定。 */
private fun surfaceRoute(surfaceId: String): String? = when (surfaceId) {
    PluginSurface.MEDICATIONS -> Routes.MEDICATIONS
    PluginSurface.MEASUREMENTS -> Routes.MEASUREMENTS
    PluginSurface.FAMILY -> Routes.TENANTS
    PluginSurface.NOTES -> Routes.NOTES
    else -> null
}

private object Routes {
    const val NOTICE = "notice"
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val MEMORY = "memory"
    const val PLUGINS = "plugins"
    const val PLUGIN = "plugin/{id}"
    fun plugin(id: String) = "plugin/$id"
    const val NOTES = "notes"
    const val TODAY = "today"
    const val TASK = "task/{id}"
    fun task(id: String) = "task/$id"
    const val EPHEMERAL = "ephemeral"
    const val SIDES = "sides"
    const val SIDE = "side/{id}"
    fun side(id: String) = "side/$id"
    const val MEDICATIONS = "medications"
    const val MEASUREMENTS = "measurements"
    const val TENANTS = "tenants"
    const val ABOUT = "about"
    const val PRIVACY = "privacy"
    const val DATA_USE = "data_use"
    const val DEVELOPER = "developer"
}

@Composable
fun VanaApp(
    checkInQuestion: String? = null,
    onCheckInConsumed: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as VanaApplication
    val navController = rememberNavController()
    var accepted by remember { mutableStateOf(app.engineSettings.hasAcceptedDataUseNotice) }
    var tenantId by remember { mutableStateOf(TenantScope.current.id) }
    var pendingMedication by remember { mutableStateOf<MedicationItem?>(null) }
    val exerciseLibrary = remember { ExerciseLibrary.shared(app) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        CheckInScheduler.reschedule(app)
        ReminderScheduler.rescheduleAll(app)
    }

    /** 当前成员的提醒/目标存储,加上把提醒排到系统闹钟上的那一头。 */
    fun tasksEnvironment() = TasksEnvironment(
        store = TenantScope.currentStores.tasks,
        scheduling = ReminderScheduler.scheduling(app, TenantScope.current.id),
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_STOP) {
                CheckInScheduler.reschedule(app)
                ReminderScheduler.rescheduleAll(app)
                scope.launch {
                    BackgroundDigest.runIfDue(app)
                }
            }
            if (event == Lifecycle.Event.ON_STOP) {
                // 切到后台:把还没抽过的对话收割进记忆(水位线之后的,一次一块)。
                scope.launch { MemoryHarvester.forCurrentTenant(app).runIfDue() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val start = if (accepted) Routes.CHAT else Routes.NOTICE

    // 主对话永远是家:快捷方式、check-in 都只落在主对话里。正开着侧聊(或侧聊列表)时先回到主对话,
    // 不然那句话要等他自己退回来才发得出去。侧聊那一页被弹掉时,它的 view model 在 onCleared 里停下。
    val pendingAsk by VanaLaunchRouter.pending.collectAsStateWithLifecycle()
    LaunchedEffect(checkInQuestion, pendingAsk) {
        if (checkInQuestion == null && pendingAsk == null) return@LaunchedEffect
        val route = navController.currentDestination?.route
        if (route == Routes.SIDE || route == Routes.SIDES) {
            navController.popBackStack(Routes.CHAT, inclusive = false)
        }
    }

    /** 侧聊的宿主:主对话那一项的 view model。侧聊列表和侧聊本身都从那一项上取,拿到的是同一个。 */
    @Composable
    fun sideChatHost(entry: NavBackStackEntry): SideChatHost {
        val chatEntry = remember(entry) { navController.getBackStackEntry(Routes.CHAT) }
        return viewModel(viewModelStoreOwner = chatEntry, key = "side-host-$tenantId")
    }

    fun chatFactory(ephemeral: Boolean = false, sideChat: SideChat? = null) = ChatViewModel.Factory(
        threadWriter = TenantScope.currentStores.threadWriter,
        engineSettings = app.engineSettings,
        secureKeyStore = app.secureKeyStore,
        locationProvider = app.locationProvider,
        exerciseLibrary = exerciseLibrary,
        memorySnapshotProvider = { TenantScope.currentStores.memory.snapshot() },
        medicationSnapshotProvider = { TenantScope.currentStores.medications.snapshot() },
        measurementSnapshotProvider = { TenantScope.currentStores.measurements.snapshot() },
        tasksEnvironment = tasksEnvironment(),
        ephemeral = ephemeral,
        sideChat = sideChat,
        sides = TenantScope.currentStores.sides,
    )

    CompositionLocalProvider(
        LocalTasksEnvironment provides remember(tenantId) { tasksEnvironment() },
    ) {
    NavHost(navController = navController, startDestination = start) {
        composable(Routes.NOTICE) {
            DataUseNoticeScreen(
                onAccept = {
                    app.engineSettings.hasAcceptedDataUseNotice = true
                    accepted = true
                    navController.navigate(Routes.CHAT) {
                        popUpTo(Routes.NOTICE) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.CHAT) {
            key(tenantId) {
                val chatViewModel: ChatViewModel = viewModel(
                    key = "chat-$tenantId",
                    factory = chatFactory(),
                )
                // 侧聊的宿主挂在主对话这一项上:换成员、清空全部对话时和主对话一起换掉。
                val sideChatsUnread by viewModel<SideChatHost>(key = "side-host-$tenantId").unread.collectAsStateWithLifecycle()
                LaunchedEffect(checkInQuestion) {
                    if (checkInQuestion != null) {
                        chatViewModel.applyCheckIn(checkInQuestion)
                        onCheckInConsumed()
                    }
                }
                LaunchedEffect(Unit) {
                    while (true) {
                        VanaLaunchRouter.consumeAsk()?.let { question ->
                            chatViewModel.applyAskAndSend(question)
                        }
                        delay(400)
                    }
                }
                LaunchedEffect(pendingMedication) {
                    val med = pendingMedication ?: return@LaunchedEffect
                    chatViewModel.openMedication(med)
                    pendingMedication = null
                }
                ChatScreen(
                    viewModel = chatViewModel,
                    exerciseLibrary = exerciseLibrary,
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenMemory = { navController.navigate(Routes.MEMORY) },
                    onOpenPlugins = { navController.navigate(Routes.PLUGINS) },
                    onOpenEphemeral = { navController.navigate(Routes.EPHEMERAL) },
                    onOpenToday = { navController.navigate(Routes.TODAY) },
                    onOpenSideChats = { navController.navigate(Routes.SIDES) },
                    onOpenSideChat = { navController.navigate(Routes.side(it.id)) },
                    sideChatsUnread = sideChatsUnread.isNotEmpty(),
                )
            }
        }
        composable(Routes.SIDES) { entry ->
            val host = sideChatHost(entry)
            SideChatListScreen(
                store = TenantScope.currentStores.sides,
                host = host,
                tenant = TenantScope.current,
                onOpen = { navController.navigate(Routes.side(it.id)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SIDE) { entry ->
            // 一条侧聊:另一个聊天 view model 接另一条线程。返回回到「⋯ › 侧聊」那一页。
            val id = entry.arguments?.getString("id").orEmpty()
            val sides = TenantScope.currentStores.sides
            val host = sideChatHost(entry)
            // 这一项被弹出返回栈(返回、被快捷方式顶回主对话)时告诉宿主「那一页离开了」:还在写就留着写完。
            viewModel<SideChatVisit>(key = "visit-$id", factory = viewModelFactory { initializer { SideChatVisit { host.close(id) } } })
            var lookedUp by remember(id) { mutableStateOf(false) }
            var found by remember(id) { mutableStateOf<SideChat?>(null) }
            LaunchedEffect(id) {
                found = sides.get(id)
                lookedUp = true
            }
            if (!lookedUp) return@composable
            val sideChat = found
            if (sideChat == null) {
                // 名单上已经没有它了(刚被删):别开一条空的出来。
                LaunchedEffect(Unit) { navController.popBackStack() }
                return@composable
            }
            // 宿主里还在写的那一个就接着用——同一个对象,不是重新读盘。
            val sideViewModel = remember(id) {
                host.open(id) { HostedSideChat.make(chatFactory(sideChat = sideChat)) }.viewModel
            }
            // 从目标详情进来的:输入框里替他起个头。他已经打了字就不动。
            LaunchedEffect(id) {
                host.takeDraft(id)?.let { draft -> if (sideViewModel.input.value.isEmpty()) sideViewModel.setInput(draft) }
            }
            ChatScreen(
                viewModel = sideViewModel,
                exerciseLibrary = exerciseLibrary,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenMemory = { navController.navigate(Routes.MEMORY) },
                onOpenPlugins = { navController.navigate(Routes.PLUGINS) },
                onDeleteSideChat = {
                    // 先停下、落盘,再按「名单 → 线程和照片 → 目录」的顺序删;界面当场关掉,不等。
                    val leaving = host.discard(id)
                    sides.launch {
                        leaving?.join()
                        sides.delete(id)
                    }
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.EPHEMERAL) {
            // 「不留痕」浮层:一个只活在内存里的聊天,离开这一页它的 ViewModel 就被回收,内容跟着没。
            val ephemeralViewModel: ChatViewModel = viewModel(
                key = "ephemeral",
                factory = chatFactory(ephemeral = true),
            )
            ChatScreen(
                viewModel = ephemeralViewModel,
                exerciseLibrary = exerciseLibrary,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenMemory = { navController.navigate(Routes.MEMORY) },
                onOpenPlugins = { navController.navigate(Routes.PLUGINS) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                engineSettings = app.engineSettings,
                secureKeyStore = app.secureKeyStore,
                locationProvider = app.locationProvider,
                history = TenantScope.currentStores.history,
                onBack = { navController.popBackStack() },
                onOpenMemory = { navController.navigate(Routes.MEMORY) },
                onOpenPlugins = { navController.navigate(Routes.PLUGINS) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
                onOpenDeveloper = {
                    if (BuildConfig.DEBUG) {
                        navController.navigate(Routes.DEVELOPER)
                    }
                },
                onChatsCleared = {
                    tenantId = TenantScope.current.id
                    navController.navigate(Routes.CHAT) {
                        popUpTo(Routes.CHAT) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        if (BuildConfig.DEBUG) {
            composable(Routes.DEVELOPER) {
                DeveloperScreen(onBack = { navController.popBackStack() })
            }
        }
        composable(Routes.PLUGINS) {
            PluginsScreen(
                engineSettings = app.engineSettings,
                onBack = { navController.popBackStack() },
                onOpenPlugin = { navController.navigate(Routes.plugin(it)) },
            )
        }
        composable(Routes.PLUGIN) { entry ->
            PluginDetailScreen(
                pluginId = entry.arguments?.getString("id").orEmpty(),
                engineSettings = app.engineSettings,
                onBack = { navController.popBackStack() },
                // 插件只说「是什么」,去哪儿由外壳定。
                onOpenSurface = { surfaceId -> surfaceRoute(surfaceId)?.let { navController.navigate(it) } },
            )
        }
        composable(Routes.NOTES) {
            NotesScreen(
                store = TenantScope.currentStores.notes,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.TODAY) { entry ->
            // 「今天」读的是主对话手里那一份:顶栏那颗角标数的是同一份,替他问的那一句也要交给它去发。
            val chatEntry = remember(entry) { navController.getBackStackEntry(Routes.CHAT) }
            val chatViewModel: ChatViewModel = viewModel(
                viewModelStoreOwner = chatEntry,
                key = "chat-$tenantId",
                factory = chatFactory(),
            )
            val cards by chatViewModel.todayCards.collectAsStateWithLifecycle()
            TodayScreen(
                env = tasksEnvironment(),
                cards = cards,
                onRefresh = chatViewModel::refreshToday,
                onAction = { action ->
                    when (action) {
                        is TodayAction.OpenTask -> navController.navigate(Routes.task(action.id))
                        TodayAction.OpenMemory -> navController.navigate(Routes.MEMORY)
                        is TodayAction.OpenSurface -> surfaceRoute(action.surfaceId)?.let { navController.navigate(it) }
                        // 替他问的那一句当场发,再退回对话:发送不用等这一页走,回到那一屏时回答已经在写了。
                        is TodayAction.Ask -> {
                            chatViewModel.send(action.prompt)
                            navController.popBackStack(Routes.CHAT, inclusive = false)
                        }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.TASK) { entry ->
            val host = sideChatHost(entry)
            TaskDetailScreen(
                env = tasksEnvironment(),
                taskId = entry.arguments?.getString("id").orEmpty(),
                onBack = { navController.popBackStack() },
                // 「在侧聊里聊这个目标」:同名的侧聊有了就接着用,输入框里替他起个头;「今天」页退到底下,返回回到主对话。
                onDiscussGoal = { goal ->
                    scope.launch {
                        val chat = TenantScope.currentStores.sides.named(goal.title)
                        host.stageDraft(chat.id, goalReviewPrompt(goal.title))
                        navController.navigate(Routes.side(chat.id)) { popUpTo(Routes.CHAT) }
                    }
                },
            )
        }
        composable(Routes.MEMORY) {
            MemoryListScreen(
                store = TenantScope.currentStores.memory,
                engineSettings = app.engineSettings,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.MEDICATIONS) {
            MedicationListScreen(
                store = TenantScope.currentStores.medications,
                engineSettings = app.engineSettings,
                secureKeyStore = app.secureKeyStore,
                onBack = { navController.popBackStack() },
                onAskMedication = { medication ->
                    pendingMedication = medication
                    navController.popBackStack(Routes.CHAT, inclusive = false)
                },
            )
        }
        composable(Routes.MEASUREMENTS) {
            MeasurementListScreen(
                store = TenantScope.currentStores.measurements,
                engineSettings = app.engineSettings,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(
                onBack = { navController.popBackStack() },
                onOpenPrivacy = { navController.navigate(Routes.PRIVACY) },
                onOpenDataUse = { navController.navigate(Routes.DATA_USE) },
            )
        }
        composable(Routes.PRIVACY) {
            PrivacyPolicyScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.DATA_USE) {
            DataUseDetailScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.TENANTS) {
            TenantListScreen(
                store = app.tenantStore,
                onBack = { navController.popBackStack() },
                onSwitched = {
                    tenantId = TenantScope.current.id
                    navController.navigate(Routes.CHAT) {
                        popUpTo(Routes.CHAT) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
    }
    }
}
