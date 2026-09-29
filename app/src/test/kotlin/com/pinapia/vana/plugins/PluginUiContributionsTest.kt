package com.pinapia.vana.plugins

import com.pinapia.vana.agentruntime.AgentPlugin
import com.pinapia.vana.exercises.ExerciseLibrary
import com.pinapia.vana.measurements.MeasurementStore
import com.pinapia.vana.medications.MedicationItem
import com.pinapia.vana.medications.MedicationSnapshot
import com.pinapia.vana.medications.MedicationStore
import com.pinapia.vana.memory.MemoryStore
import com.pinapia.vana.session.ToolCallRecord
import com.pinapia.vana.tenant.Tenant
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 插件对界面的贡献:首屏建议、欢迎语、工具标签、入口页。 */
class PluginUiContributionsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val owner = Tenant(name = "我", kind = Tenant.Kind.OWNER)
    private val family = Tenant(name = "妈妈", kind = Tenant.Kind.MANAGED, ageBand = Tenant.AgeBand.SENIOR)

    private inline fun <T> zh(block: () -> T): T {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            return block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    private fun context(
        health: Boolean = true,
        memory: Boolean = true,
        tenant: Tenant = owner,
        focus: MedicationItem? = null,
    ) = SuggestionContext(
        isEnabled = { id ->
            when (id) {
                PluginIds.HEALTH -> health
                PluginIds.MEMORY -> memory
                else -> true
            }
        },
        tenant = tenant,
        focusMedication = focus,
        medications = { MedicationSnapshot.empty },
    )

    // ---- 首屏建议 ----

    @Test
    fun theOpeningSuggestionsAreGeneralFirstWithHealthAsOneOfThree() = zh {
        val chips = PluginRegistry.suggestions(context())
        assertEquals(3, chips.size)
        assertEquals("帮我整理一下今天要做的事", chips[0])
        assertEquals("提醒是核心的能力，第二个就亮出来", "明天早上八点提醒我带伞", chips[1])
        assertEquals("健康只占一个位子", "帮我看看这张化验单", chips[2])
    }

    @Test
    fun withHealthOffEveryOpeningSuggestionIsGeneral() = zh {
        val chips = PluginRegistry.suggestions(context(health = false))
        assertEquals(3, chips.size)
        assertTrue(chips.none { HealthTopics.mentions(it) })
    }

    @Test
    fun withMemoryOffNoSuggestionAsksToRemember() = zh {
        val chips = PluginRegistry.suggestions(context(health = false, memory = false))
        assertTrue(chips.none { it.contains("记住") })
        assertEquals(3, chips.size)
    }

    @Test
    fun aFocusedMedicationReplacesTheGeneralSuggestions() = zh {
        val med = MedicationItem(name = "维生素 D", status = MedicationItem.Status.ONGOING)
        val chips = PluginRegistry.suggestions(context(focus = med))
        assertEquals(med.openingQuestions.take(3), chips)
        assertTrue(chips.none { it == "帮我整理一下今天要做的事" })
    }

    @Test
    fun askingOnBehalfOfAFamilyMemberReplacesTheGeneralSuggestions() = zh {
        val chips = PluginRegistry.suggestions(context(tenant = family))
        assertTrue(chips.isNotEmpty())
        assertTrue(chips.none { it == "帮我整理一下今天要做的事" })
        assertTrue(chips.any { it.contains("妈妈") })
    }

    @Test
    fun theMixIsGeneralFirstAndOtherPluginsShareTheReservedSlots() {
        val core = listOf("c1", "c2", "c3", "c4")
        val a = listOf("a1", "a2")
        val b = listOf("b1")
        // 三个位子:核心 2 个,其余共用 1 个。
        assertEquals(listOf("c1", "c2", "a1"), PluginRegistry.mix(listOf(core, a), 3))
        assertEquals(listOf("c1", "c2", "a1"), PluginRegistry.mix(listOf(core, a, b), 3))
        // 位子多了,预留的也跟着多,各插件轮流占。
        assertEquals(listOf("c1", "c2", "c3", "c4", "a1", "b1"), PluginRegistry.mix(listOf(core, a, b), 6))
        // 没有别的插件,全给核心。
        assertEquals(listOf("c1", "c2", "c3"), PluginRegistry.mix(listOf(core), 3))
        // 别的插件不够占满预留位,从核心补。
        assertEquals(listOf("c1", "c2", "b1"), PluginRegistry.mix(listOf(core, emptyList(), b), 3))
        // 核心自己也不够时,谁有给谁。
        assertEquals(listOf("x", "y"), PluginRegistry.mix(listOf(listOf("x"), listOf("y")), 3))
    }

    // ---- 欢迎语 ----

    @Test
    fun theWelcomeBodyMentionsHealthOnlyWhileItIsOn() = zh {
        val on = PluginRegistry.welcomeBody { true }
        assertTrue(on.contains("日常的事都可以交给我"))
        assertTrue(on.contains("化验单"))
        assertTrue(on.contains("文字识别在本机完成"))

        val off = PluginRegistry.welcomeBody { id -> id != PluginIds.HEALTH }
        assertTrue(off.contains("日常的事都可以交给我"))
        assertFalse(off.contains("化验单"))
        assertFalse(off.contains("用药"))
        assertTrue("数据去向那句一直都在", off.contains("发给你配置的模型"))
    }

    // ---- 工具标签 ----

    @Test
    fun everyKnownToolHasAHumanLabelAndUnknownOnesFallBack() = zh {
        val labeled = listOf(
            "remember", "ask_user", "web_search", "search_sessions", "read_session",
            "list_medications", "log_medication", "update_medication", "list_measurements", "log_measurement",
        )
        labeled.forEach { name ->
            val label = PluginRegistry.toolLabel(ToolCallRecord(id = "1", name = name, input = "{}"))
            assertFalse("$name 不该落到兜底文案", label.startsWith("调用了"))
        }
        assertEquals("调用了 mystery", PluginRegistry.toolLabel(ToolCallRecord(id = "1", name = "mystery", input = "{}")))
        assertEquals("没找到合适的动作", PluginRegistry.toolLabel(ToolCallRecord(id = "1", name = "suggest_exercises", input = "{}")))
    }

    // ---- 插件页 ----

    @Test
    fun onlyTheOptionalPluginsShowUpOnThePluginsPage() {
        assertEquals(listOf(PluginIds.NOTES, PluginIds.HEALTH), PluginRegistry.togglable.map { it.manifest.id })
        assertFalse("核心用户关不掉", CorePlugin.manifest.togglable)
    }

    @Test
    fun everySurfaceToggleIsAKnownPluginIdAndSurfacesAreUnique() {
        val known = setOf(PluginIds.HEALTH_MEDICATIONS, PluginIds.HEALTH_MEASUREMENTS)
        val surfaces = HealthVanaPlugin.surfaces
        assertTrue(surfaces.mapNotNull { it.toggleId }.all { it in known })
        assertEquals(surfaces.size, surfaces.map { it.id }.toSet().size)
        assertEquals(
            setOf(PluginSurface.MEDICATIONS, PluginSurface.MEASUREMENTS, PluginSurface.FAMILY),
            surfaces.map { it.id }.toSet(),
        )
    }

    @Test
    fun healthOwnsTheInterpretationKindAndNoPluginOwnsTheCoreKinds() {
        assertEquals(HealthVanaPlugin, PluginRegistry.memoryOwner(com.pinapia.vana.memory.MemoryItem.Kind.INTERPRETATION))
        listOf(
            com.pinapia.vana.memory.MemoryItem.Kind.PROFILE,
            com.pinapia.vana.memory.MemoryItem.Kind.PREFERENCE,
            com.pinapia.vana.memory.MemoryItem.Kind.EPISODE,
            com.pinapia.vana.memory.MemoryItem.Kind.FOLLOW_UP,
        ).forEach { assertEquals(null, PluginRegistry.memoryOwner(it)) }
    }

    @Test
    fun aKindIsVisibleUnlessItsOwnerIsSwitchedOff() {
        val interpretation = com.pinapia.vana.memory.MemoryItem.Kind.INTERPRETATION
        assertTrue(PluginRegistry.isMemoryVisible(interpretation) { true })
        assertFalse(PluginRegistry.isMemoryVisible(interpretation) { id -> id != PluginIds.HEALTH })
        assertTrue(PluginRegistry.isMemoryVisible(com.pinapia.vana.memory.MemoryItem.Kind.PROFILE) { false })
    }

    @Test
    fun theHealthPluginCarriesItsOwnDisclaimer() = zh {
        val text = HealthVanaPlugin.disclaimer
        assertTrue(text != null && text.contains("不是医疗器械"))
        assertEquals(null, CorePlugin.disclaimer)
    }

    // ---- 免责声明的判据不能和真实的健康工具集合脱节 ----

    @Test
    fun healthTopicsKnowsEveryToolTheHealthPluginMounts() {
        val env = PluginEnvironment(
            isEnabled = { true },
            tenant = owner,
            archive = null,
            memoryStore = MemoryStore(folder.newFolder("m")),
            exerciseLibrary = ExerciseLibrary.parse(File("src/main/assets/exercises.json").readText()),
            medicationStore = MedicationStore(folder.newFolder("med")),
            measurementStore = MeasurementStore(folder.newFolder("mea")),
        )
        val healthPlugins: List<AgentPlugin> =
            PluginRegistry.agentPlugins(env, PluginRoute.FOREGROUND).filter { it.id.startsWith("${PluginIds.HEALTH}.") }
        val mounted = healthPlugins.flatMap { plugin ->
            plugin.tools(com.pinapia.vana.agentruntime.PluginContext()).map { it.name }
        }.toSet()

        assertTrue(mounted.isNotEmpty())
        assertEquals(
            "新增健康工具时要同步登记进 HealthTopics.TOOL_NAMES，否则它的回答下面不会出现医疗免责",
            mounted,
            HealthTopics.TOOL_NAMES,
        )
    }

    @Test
    fun localizedTextFollowsTheLocaleAtReadTimeNotAtCreationTime() {
        val text = Localized(zh = "中文", en = "English")
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            val english = text.text
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            assertEquals("English", english)
            assertEquals("中文", text.text)
            assertNotEquals(english, text.text)
        } finally {
            Locale.setDefault(previous)
        }
    }
}
