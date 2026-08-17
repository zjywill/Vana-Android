package com.pinapia.vana

import androidx.compose.runtime.Composable
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pinapia.vana.chat.ChatScreen
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
import com.pinapia.vana.tenant.TenantListScreen
import com.pinapia.vana.tenant.TenantScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private object Routes {
    const val NOTICE = "notice"
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val MEMORY = "memory"
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
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_STOP) {
                CheckInScheduler.reschedule(app)
                scope.launch {
                    BackgroundDigest.runIfDue(app)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val start = if (accepted) Routes.CHAT else Routes.NOTICE

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
                    factory = ChatViewModel.Factory(
                        sessionStore = TenantScope.currentStores.sessions,
                        engineSettings = app.engineSettings,
                        secureKeyStore = app.secureKeyStore,
                        locationProvider = app.locationProvider,
                        exerciseLibrary = exerciseLibrary,
                        memorySnapshotProvider = { TenantScope.currentStores.memory.snapshot() },
                        medicationSnapshotProvider = { TenantScope.currentStores.medications.snapshot() },
                        measurementSnapshotProvider = { TenantScope.currentStores.measurements.snapshot() },
                    ),
                )
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
                    onOpenMedications = { navController.navigate(Routes.MEDICATIONS) },
                    onOpenMeasurements = { navController.navigate(Routes.MEASUREMENTS) },
                    onOpenTenants = { navController.navigate(Routes.TENANTS) },
                )
            }
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                engineSettings = app.engineSettings,
                secureKeyStore = app.secureKeyStore,
                locationProvider = app.locationProvider,
                sessionStore = TenantScope.currentStores.sessions,
                onBack = { navController.popBackStack() },
                onOpenMemory = { navController.navigate(Routes.MEMORY) },
                onOpenMeasurements = { navController.navigate(Routes.MEASUREMENTS) },
                onOpenTenants = { navController.navigate(Routes.TENANTS) },
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
