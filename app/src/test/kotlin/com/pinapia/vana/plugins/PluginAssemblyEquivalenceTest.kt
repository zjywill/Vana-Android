package com.pinapia.vana.plugins

import com.pinapia.vana.agent.CloudEngine
import com.pinapia.vana.agentruntime.AgentPlugin
import com.pinapia.vana.agentruntime.PluginContext
import com.pinapia.vana.exercises.ExerciseLibrary
import com.pinapia.vana.location.LocationSnapshot
import com.pinapia.vana.measurements.MeasurementCard
import com.pinapia.vana.measurements.MeasurementSnapshot
import com.pinapia.vana.measurements.MeasurementStore
import com.pinapia.vana.medications.MedicationItem
import com.pinapia.vana.medications.MedicationSnapshot
import com.pinapia.vana.medications.MedicationStore
import com.pinapia.vana.memory.MemoryItem
import com.pinapia.vana.memory.MemorySnapshot
import com.pinapia.vana.memory.MemoryStore
import com.pinapia.vana.search.WebSearchClient
import com.pinapia.vana.search.WebSearchResults
import com.pinapia.vana.session.SessionStore
import com.pinapia.vana.settings.AssistantPersona
import com.pinapia.vana.tenant.Tenant
import java.io.File
import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 插件化第一步是纯搬家:模型看到的 system 段和工具定义必须和插件化之前逐字相同。
 *
 * 参照物是搬进测试里的旧装配(`LegacyHealthChatAssembly.kt`)。覆盖前台的全部开关组合
 * 和后台派生那条路;任何一个组合对不上,说明插件化悄悄改了模型读到的东西。
 */
class PluginAssemblyEquivalenceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var memoryStore: MemoryStore
    private lateinit var medicationStore: MedicationStore
    private lateinit var measurementStore: MeasurementStore
    private lateinit var sessionStore: SessionStore

    private val exercises = ExerciseLibrary.parse(File("src/main/assets/exercises.json").readText())
    private val webSearch = WebSearchClient { WebSearchResults(query = it) }

    private val memory = MemorySnapshot(
        items = listOf(MemoryItem(text = "他膝盖不好，不能跑步", kind = MemoryItem.Kind.PROFILE)),
    )
    private val medications = MedicationSnapshot(
        items = listOf(
            MedicationItem(name = "青霉素", status = MedicationItem.Status.CANNOT_TAKE, reason = "过敏"),
            MedicationItem(name = "褪黑素", status = MedicationItem.Status.TRIED, outcome = "没感觉"),
        ),
    )
    private val measurements = MeasurementSnapshot(
        cards = listOf(MeasurementCard(name = "体重", value = "68.5", unit = "kg", observedAt = Clock.System.now())),
    )
    private val owner = Tenant(name = "我", kind = Tenant.Kind.OWNER)
    private val family = Tenant(name = "妈妈", kind = Tenant.Kind.MANAGED, ageBand = Tenant.AgeBand.ADULT)

    @Before
    fun setUp() {
        memoryStore = MemoryStore(folder.newFolder("memory"))
        medicationStore = MedicationStore(folder.newFolder("medications"))
        measurementStore = MeasurementStore(folder.newFolder("measurements"))
        sessionStore = SessionStore(parent = folder.newFolder("sessions"))
    }

    private fun engine(
        tenant: Tenant,
        plugins: List<AgentPlugin>,
        context: PluginContext,
        persona: AssistantPersona,
        goal: String?,
    ) = CloudEngine(
        providerId = "deepseek",
        model = "deepseek-chat",
        apiKey = "sk-test",
        tenant = tenant,
        plugins = plugins,
        pluginContext = context,
        persona = persona,
        goal = goal,
    )

    @Test
    fun foregroundMatchesLegacyInEveryCombination() {
        var checked = 0
        for (isPrivate in listOf(false, true))
        for (recall in listOf(false, true))
        for (memoryEnabled in listOf(false, true))
        for (medicationsEnabled in listOf(false, true))
        for (measurementsEnabled in listOf(false, true))
        for (search in listOf(null, webSearch))
        for (location in listOf(LocationSnapshot.unknown, LocationSnapshot(place = "中国浙江省杭州市")))
        for (tenant in listOf(owner, family))
        for (focus in listOf(null, medications.items.first()))
        for ((persona, goal) in listOf(AssistantPersona.BALANCED to null, AssistantPersona.COACH to "减脂")) {
            val memorySnapshot = if (memoryEnabled) memory else MemorySnapshot.empty
            val medicationSnapshot = if (medicationsEnabled) medications else MedicationSnapshot.empty
            val measurementSnapshot = if (measurementsEnabled) measurements else MeasurementSnapshot.empty

            val legacyRegistry = legacyHealthChat(
                allowsMemoryWrites = !isPrivate,
                allowsMedicationWrites = !isPrivate,
                allowsMeasurementWrites = !isPrivate,
                allowsRecall = recall,
                asksUser = true,
                memoryStore = memoryStore,
                medicationStore = medicationStore,
                measurementStore = measurementStore,
                sessionStore = sessionStore,
                currentSessionId = "current",
                webSearch = search,
                exerciseLibrary = exercises,
                memoryEnabled = memoryEnabled,
                medicationsEnabled = medicationsEnabled,
                measurementsEnabled = measurementsEnabled,
            )
            val plugins = VanaPlugins.foreground(
                exerciseLibrary = exercises,
                webSearch = search,
                medicationStore = if (medicationsEnabled) medicationStore else null,
                medications = medicationSnapshot,
                focusMedication = focus,
                measurementStore = if (measurementsEnabled) measurementStore else null,
                measurements = measurementSnapshot,
                sessionStore = if (memoryEnabled) sessionStore else null,
                currentSessionId = "current",
                memoryStore = if (memoryEnabled) memoryStore else null,
                memory = memorySnapshot,
                location = location,
            )
            val engine = engine(
                tenant = tenant,
                plugins = plugins,
                context = VanaPlugins.foregroundContext(isPrivate = isPrivate, recallUnlocked = recall),
                persona = persona,
                goal = goal,
            )
            val label = "private=$isPrivate recall=$recall memory=$memoryEnabled meds=$medicationsEnabled " +
                "measurements=$measurementsEnabled search=${search != null} location=${location.isKnown} " +
                "owner=${tenant.isOwner} focus=${focus != null} goal=$goal"

            assertEquals(label, legacyRegistry.definitions, engine.toolDefinitions())
            for (interjections in listOf(false, true)) {
                assertEquals(
                    "$label interjections=$interjections",
                    legacySystemInstruction(
                        tenant = tenant,
                        memory = memorySnapshot,
                        medications = medicationSnapshot,
                        measurements = measurementSnapshot,
                        location = location,
                        capabilityRegistry = legacyRegistry,
                        persona = persona,
                        goal = goal,
                        focusMedication = focus,
                        acceptsInterjections = interjections,
                    ),
                    engine.systemInstruction(acceptsInterjections = interjections),
                )
            }
            checked++
        }
        assertEquals(2 * 2 * 2 * 2 * 2 * 2 * 2 * 2 * 2 * 2, checked)
    }

    @Test
    fun backgroundDerivedTurnMatchesLegacy() {
        for (memoryEnabled in listOf(false, true))
        for (recall in listOf(false, true))
        for (goal in listOf(null, "备半马")) {
            val memorySnapshot = if (memoryEnabled) memory else MemorySnapshot.empty
            val legacyRegistry = legacyHealthChat(
                allowsMemoryWrites = false,
                allowsMedicationWrites = false,
                allowsRecall = recall,
                asksUser = false,
                memoryStore = memoryStore,
                medicationStore = null,
                sessionStore = sessionStore,
                currentSessionId = "derived",
                webSearch = null,
                exerciseLibrary = null,
                memoryEnabled = memoryEnabled,
                medicationsEnabled = false,
            )
            val engine = engine(
                tenant = owner,
                plugins = VanaPlugins.background(
                    sessionStore = if (memoryEnabled) sessionStore else null,
                    currentSessionId = "derived",
                    memoryStore = if (memoryEnabled) memoryStore else null,
                    memory = memorySnapshot,
                ),
                context = VanaPlugins.backgroundContext(recallUnlocked = recall),
                persona = AssistantPersona.BALANCED,
                goal = goal,
            )
            val label = "memory=$memoryEnabled recall=$recall goal=$goal"

            assertEquals(label, legacyRegistry.definitions, engine.toolDefinitions())
            assertEquals(
                label,
                legacySystemInstruction(
                    tenant = owner,
                    memory = memorySnapshot,
                    medications = MedicationSnapshot.empty,
                    measurements = MeasurementSnapshot.empty,
                    location = LocationSnapshot.unknown,
                    capabilityRegistry = legacyRegistry,
                    persona = AssistantPersona.BALANCED,
                    goal = goal,
                    focusMedication = null,
                    acceptsInterjections = false,
                ),
                engine.systemInstruction(acceptsInterjections = false),
            )
        }
    }
}
