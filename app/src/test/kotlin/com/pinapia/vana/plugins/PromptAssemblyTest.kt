package com.pinapia.vana.plugins

import com.pinapia.vana.agent.CloudEngine
import com.pinapia.vana.agentruntime.PluginContext
import com.pinapia.vana.agentruntime.TokenEstimate
import com.pinapia.vana.exercises.ExerciseLibrary
import com.pinapia.vana.location.LocationSnapshot
import com.pinapia.vana.measurements.MeasurementCard
import com.pinapia.vana.measurements.MeasurementSnapshot
import com.pinapia.vana.measurements.MeasurementStore
import com.pinapia.vana.medications.MedicationItem
import com.pinapia.vana.medications.MedicationSnapshot
import com.pinapia.vana.medications.MedicationStore
import com.pinapia.vana.memory.MemoryItem
import com.pinapia.vana.memory.MemoryStore
import com.pinapia.vana.memory.MemorySnapshot
import com.pinapia.vana.search.WebSearchClient
import com.pinapia.vana.search.WebSearchResults
import com.pinapia.vana.thread.ThreadArchive
import com.pinapia.vana.thread.ThreadStore
import kotlinx.coroutines.runBlocking
import com.pinapia.vana.settings.AssistantPersona
import com.pinapia.vana.tasks.Task
import com.pinapia.vana.tasks.TaskKind
import com.pinapia.vana.tasks.TaskStatus
import com.pinapia.vana.tasks.TaskStore
import com.pinapia.vana.tasks.TasksEnvironment
import com.pinapia.vana.tenant.Tenant
import java.io.File
import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 装配出来的 system 段和工具定义的契约。
 *
 * 插件化第一步是「一字不差地搬家」,由逐字对照旧装配的等价测试盯着;第二步(拆提示词)有意打破它,
 * 那份测试和旧装配一起删了,换成这里的三类断言:
 * 1. 结构:静态在前易变在后、按工具是否挂载门控、隐私/后台过滤。
 * 2. 健康开着时,原来基础规则里那些要紧的话一句都没丢(子串清单,不是逐字相等)。
 * 3. 健康关掉时,整段 system 加全部工具定义里**不含健康词**——核心不认识药,也不认识化验单。
 */
class PromptAssemblyTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var memoryStore: MemoryStore
    private lateinit var medicationStore: MedicationStore
    private lateinit var measurementStore: MeasurementStore
    private lateinit var threadStore: ThreadStore
    private lateinit var archive: ThreadArchive
    private lateinit var taskStore: TaskStore

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
        // 一条已经有历史的线程:档案里有几条用户说过的话。窗口起点由各测试的 hiddenHistory 决定。
        threadStore = ThreadStore(folder.newFolder("thread"))
        threadStore.sync(
            listOf(
                com.pinapia.vana.session.ChatMessage(role = com.pinapia.vana.session.ChatMessage.Role.USER, text = "上个月我说过想学吉他"),
                com.pinapia.vana.session.ChatMessage(role = com.pinapia.vana.session.ChatMessage.Role.ASSISTANT, text = "好的"),
            ),
            emptySet(),
            emptySet(),
        )
        archive = ThreadArchive(threadStore)
        runBlocking { archive.await() }
        taskStore = TaskStore(folder.newFolder("tasks"))
        taskStore.add(Task(kind = TaskKind.GOAL, title = "备半马", status = TaskStatus.RUNNING, why = "想跑一次完整的比赛"))
    }

    private fun env(
        health: Boolean = true,
        medicationsOn: Boolean = true,
        measurementsOn: Boolean = true,
        memoryOn: Boolean = true,
        search: Boolean = true,
        tenant: Tenant = owner,
        location: LocationSnapshot = LocationSnapshot.unknown,
        memorySnapshot: MemorySnapshot = memory,
        /** 有没有原文滑出了窗口。没有的话召回不该挂。 */
        hiddenHistory: Boolean = true,
        /** 召回还够得着的别的线(侧聊)。 */
        otherThreads: List<com.pinapia.vana.recall.HistoryRecallTools.Source> = emptyList(),
        otherThreadsScope: OtherThreadsScope? = null,
    ) = PluginEnvironment(
        tasks = TasksEnvironment(store = taskStore),
        isEnabled = { id ->
            when (id) {
                PluginIds.HEALTH -> health
                PluginIds.HEALTH_MEDICATIONS -> health && medicationsOn
                PluginIds.HEALTH_MEASUREMENTS -> health && measurementsOn
                PluginIds.MEMORY -> memoryOn
                else -> true
            }
        },
        tenant = tenant,
        archive = archive,
        hiddenBeforePos = { if (hiddenHistory) 100.0 else null },
        otherThreads = otherThreads,
        otherThreadsScope = otherThreadsScope,
        memoryStore = memoryStore,
        memorySnapshot = { memorySnapshot },
        location = location,
        webSearch = if (search) webSearch else null,
        exerciseLibrary = exercises,
        medicationStore = medicationStore,
        medicationSnapshot = { medications },
        measurementStore = measurementStore,
        measurementSnapshot = { measurements },
    )

    private fun engine(
        env: PluginEnvironment,
        route: PluginRoute = PluginRoute.FOREGROUND,
        isPrivate: Boolean = false,
        persona: AssistantPersona = AssistantPersona.BALANCED,
        /** 在哪条侧聊里(它的名字)。null 是主对话。 */
        sideChatTitle: String? = null,
    ): CloudEngine {
        val context: PluginContext = when (route) {
            PluginRoute.FOREGROUND -> PluginRegistry.foregroundContext(isPrivate = isPrivate)
            PluginRoute.BACKGROUND -> PluginRegistry.backgroundContext()
        }
        return CloudEngine(
            providerId = "deepseek",
            model = "deepseek-chat",
            apiKey = "sk-test",
            plugins = PluginRegistry.agentPlugins(env, route),
            pluginContext = context,
            persona = persona,
            sideChatTitle = sideChatTitle,
        )
    }

    private fun CloudEngine.toolNames() = toolDefinitions().map { it.name }

    /** 模型实际看到的全部文字:system 段,加每个工具的名字、描述和参数说明。 */
    private fun CloudEngine.everythingTheModelReads(): String =
        systemInstruction(acceptsInterjections = true) + "\n" +
            toolDefinitions().joinToString("\n") { "${it.name}\n${it.description}\n${it.inputSchema}" }

    // ================= 0. 固定开销(窗口预算要先扣它) =================

    @Test
    fun requestOverheadIsTheSystemTextPlusTheToolDefinitionsAsTheyAreSent() {
        val e = engine(env())
        val expected = TokenEstimate.text(e.systemInstruction(acceptsInterjections = true)) +
            e.toolDefinitions().sumOf(TokenEstimate::definition)
        assertEquals(expected, e.requestOverheadTokens())
    }

    @Test
    fun requestOverheadMeasuresTheSentJsonNotKotlinsDebugString() {
        // inputSchema.toString() 是 ObjectValue(value={…StringValue(value=…这种外壳,比真正发出去的 JSON 长得多;
        // 用它算会把窗口无谓地挤窄。
        val e = engine(env())
        val debugStyle = TokenEstimate.text(e.systemInstruction(acceptsInterjections = true)) +
            e.toolDefinitions().sumOf { TokenEstimate.text((it.description ?: "") + it.inputSchema.toString()) }
        assertTrue("按发出去的 JSON 算应当更小：${e.requestOverheadTokens()} vs $debugStyle", e.requestOverheadTokens() < debugStyle)
    }

    @Test
    fun turningHealthOffShrinksTheFixedOverheadSoTheWindowCanHoldMoreConversation() {
        assertTrue(engine(env(health = false)).requestOverheadTokens() < engine(env()).requestOverheadTokens())
    }

    // ================= 1. 结构 =================

    @Test
    fun staticBlocksComeBeforeVolatileSnapshots() {
        val text = engine(env()).systemInstruction()
        val order = listOf(
            "你是 Vana，用户的日常助手", // 核心静态
            "急症优先于一切", // 健康规则
            "log_medication", // 工具用法
            "今天是", // 易变从这里开始
            "关于这位用户（来自过往对话）", // 记忆块（说明里也有「关于这位用户」几个字，所以带上括号）
            "青霉素", // 用药名单
            "68.5", // 测量卡片（「体重」两个字规则里也有，用卡片里独有的数值）
            "他正在推进的目标", // 目标（任务插件贡献）
        ).map { it to text.indexOf(it) }
        order.forEach { (needle, index) -> assertTrue("缺少：$needle", index >= 0) }
        assertEquals("顺序应当是静态在前、易变在后", order.map { it.second }.sorted(), order.map { it.second })
    }

    @Test
    fun healthToolsAreMountedOnlyWhileTheirSwitchIsOn() {
        val healthTools = setOf(
            "suggest_exercises", "list_medications", "log_medication", "update_medication",
            "list_measurements", "log_measurement",
        )
        val on = engine(env()).toolNames().toSet()
        assertTrue(on.containsAll(healthTools))

        val off = engine(env(health = false)).toolNames().toSet()
        assertTrue("健康关掉后不该挂任何健康工具：${off.intersect(healthTools)}", off.intersect(healthTools).isEmpty())
        assertTrue(off.containsAll(setOf("ask_user", "web_search", "remember")))

        val noMeds = engine(env(medicationsOn = false)).toolNames().toSet()
        assertFalse("log_medication" in noMeds)
        assertTrue("log_measurement" in noMeds)
    }

    @Test
    fun guidesAreOnlySentForToolsThatAreActuallyMounted() {
        val full = engine(env()).systemInstruction()
        assertTrue(full.contains("log_medication"))
        assertTrue(full.contains("log_measurement"))
        assertTrue(full.contains("suggest_exercises"))
        assertTrue(full.contains("web_search"))

        val noMeds = engine(env(medicationsOn = false)).systemInstruction()
        assertFalse(noMeds.contains("log_medication"))
        assertFalse(noMeds.contains("list_medications"))

        val noSearch = engine(env(search = false)).systemInstruction()
        assertFalse(noSearch.contains("web_search"))

        val noHealth = engine(env(health = false)).systemInstruction()
        assertFalse(noHealth.contains("suggest_exercises"))
    }

    @Test
    fun aPrivateConversationMountsNoWriteTool() {
        val names = engine(env(), isPrivate = true).toolNames().toSet()
        assertFalse("remember" in names)
        assertFalse("log_medication" in names)
        assertFalse("update_medication" in names)
        assertFalse("log_measurement" in names)
        assertTrue("list_medications" in names)
        assertTrue("ask_user" in names)
        assertFalse(
            "隐私会话里不该再教模型去调写盘工具",
            engine(env(), isPrivate = true).systemInstruction().contains("调用 remember"),
        )
    }

    @Test
    fun rememberForgetAndReviseAreMountedAndDescribedTogether() {
        val engine = engine(env())
        val names = engine.toolNames().toSet()
        assertTrue(names.containsAll(setOf("remember", "forget_memory", "revise_memory")))
        val text = engine.systemInstruction()
        assertTrue(text.contains("forget_memory"))
        assertTrue(text.contains("revise_memory"))
        assertTrue("一次性的要求不是偏好", text.contains("一次性的要求"))
    }

    @Test
    fun aPrivateConversationOrMemoryOffMountsNoneOfTheThreeAndTeachesNoneOfThem() {
        listOf(engine(env(), isPrivate = true), engine(env(memoryOn = false))).forEach { engine ->
            val names = engine.toolNames().toSet()
            assertFalse("remember" in names)
            assertFalse("forget_memory" in names)
            assertFalse("revise_memory" in names)
            assertFalse(engine.systemInstruction().contains("forget_memory"))
        }
    }

    @Test
    fun memoryTheHealthPluginOwnsIsInThePromptOnlyWhileHealthIsOn() {
        val snapshot = MemorySnapshot(
            listOf(
                MemoryItem(text = "他上夜班", kind = MemoryItem.Kind.PROFILE),
                MemoryItem(text = "他的低密度偏高是因为断药", kind = MemoryItem.Kind.INTERPRETATION),
                MemoryItem(text = "喜欢简短", kind = MemoryItem.Kind.PREFERENCE),
            ),
        )
        val on = engine(env(memorySnapshot = snapshot)).systemInstruction()
        assertTrue(on.contains("他的低密度偏高是因为断药"))

        val off = engine(env(health = false, memorySnapshot = snapshot)).systemInstruction()
        assertFalse("健康关掉，它拥有的那类记忆不再带进对话", off.contains("他的低密度偏高是因为断药"))
        assertTrue(off.contains("他上夜班"))
        assertTrue("过滤后编号按剩下的重排，和工具读到的是同一份", off.contains("- M2 [表达偏好] 喜欢简短"))
    }

    @Test
    fun recallIsMountedOnlyWhenHistoryHasSlippedOutOfTheWindow() {
        val withHistory = engine(env(hiddenHistory = true))
        assertTrue("search_sessions" in withHistory.toolNames())
        assertTrue(withHistory.systemInstruction().contains("已经滑出了你能直接看到的范围"))

        val without = engine(env(hiddenHistory = false))
        assertFalse("没有原文滑出去，就没有可回顾的", "search_sessions" in without.toolNames())
        assertFalse(without.systemInstruction().contains("search_sessions"))
    }

    // ---- 提醒、目标、现在几点 ----

    @Test
    fun theTaskToolsAreMountedInTheForegroundAndTheGuideNamesThem() {
        val engine = engine(env())
        val names = engine.toolNames().toSet()
        assertTrue(names.containsAll(setOf("get_current_time", "create_reminder", "list_tasks", "update_task", "create_goal", "update_goal")))
        val text = engine.systemInstruction()
        assertTrue(text.contains("先用 get_current_time 知道现在几点"))
        assertTrue("提醒到点不调模型，这一点要写进去", text.contains("不会再调用你"))
    }

    @Test
    fun aPrivateConversationKeepsTheReadOnlyTaskToolsButNotTheWriteOnes() {
        val names = engine(env(), isPrivate = true).toolNames().toSet()
        assertTrue("get_current_time" in names)
        assertTrue("list_tasks" in names)
        assertFalse("create_reminder" in names)
        assertFalse("create_goal" in names)
        assertFalse("update_task" in names)
        assertFalse(engine(env(), isPrivate = true).systemInstruction().contains("create_reminder"))
    }

    // ---- 笔记与清单 ----

    private fun envWithNotes(on: Boolean = true) = env().let {
        PluginEnvironment(
            isEnabled = { id -> if (id == PluginIds.NOTES) on else it.isEnabled(id) }, tenant = it.tenant, archive = it.archive,
            hiddenBeforePos = it.hiddenBeforePos, memoryStore = it.memoryStore, memorySnapshot = it.memorySnapshot,
            location = it.location, tasks = it.tasks, webSearch = it.webSearch, exerciseLibrary = it.exerciseLibrary,
            medicationStore = it.medicationStore, medicationSnapshot = it.medicationSnapshot,
            measurementStore = it.measurementStore, measurementSnapshot = it.measurementSnapshot,
            noteStore = com.pinapia.vana.notes.NoteStore(folder.newFolder()),
        )
    }

    @Test
    fun theNoteToolsAreMountedAndTheGuideNamesThemAndDrawsTheLineWithMemory() {
        val engine = engine(envWithNotes())
        assertTrue(engine.toolNames().containsAll(listOf("save_note", "list_notes", "read_note", "update_note")))
        val text = engine.systemInstruction()
        assertTrue(text.contains("不在你的上下文里，需要时才读"))
        assertTrue("购物单不是记忆", text.contains("购物单不是记忆"))
        assertFalse("笔记不常驻:system 段里不该有任何笔记内容", text.contains("牛奶"))
    }

    @Test
    fun withTheNotesSwitchOffNothingIsMountedOrMentioned() {
        val engine = engine(engineEnvNotesOff())
        assertTrue(engine.toolNames().none { it.endsWith("_note") || it == "list_notes" })
        assertFalse(engine.systemInstruction().contains("list_notes"))
    }

    private fun engineEnvNotesOff() = envWithNotes(on = false)

    @Test
    fun aPrivateConversationCanReadNotesButNotChangeThem() {
        val names = engine(envWithNotes(), isPrivate = true).toolNames().toSet()
        assertTrue("list_notes" in names && "read_note" in names)
        assertFalse("save_note" in names)
        assertFalse("update_note" in names)
    }

    @Test
    fun backgroundTasksNeverSeeTheUsersNotes() {
        assertTrue(engine(envWithNotes(), route = PluginRoute.BACKGROUND).toolNames().none { it.contains("note") })
    }

    // ---- 后台任务(子 agent)2026-09-30 撤掉了 ----

    /** 派后台任务那一整套撤掉了:哪条路上都不再有 `start_task` / `propose_action`,也不再教模型把活儿分出去。 */
    @Test
    fun noRouteOffersToHandWorkOffToABackgroundHelper() {
        for (route in PluginRoute.entries) {
            for (private in listOf(false, true)) {
                val engine = engine(env(), route = route, isPrivate = private)
                assertFalse("start_task" in engine.toolNames())
                assertFalse("propose_action" in engine.toolNames())
                val text = engine.everythingTheModelReads()
                assertFalse(text.contains("后台助手"))
                assertFalse(text.contains("start_task"))
            }
        }
        val list = engine(env()).toolDefinitions().first { it.name == "list_tasks" }
        assertFalse("list_tasks 不再列 job", list.inputSchema.toString().contains("job"))
    }

    @Test
    fun theBackgroundRouteDoesNotSetRemindersForTheUser() {
        val names = engine(env(), route = PluginRoute.BACKGROUND).toolNames().toSet()
        assertFalse("create_reminder" in names)
        assertFalse("get_current_time" in names)
    }

    @Test
    fun activeGoalsAreListedInTheVolatileTailWithTheirShortHandle() {
        val text = engine(env()).systemInstruction()
        val goal = taskStore.active().first()
        assertTrue(text.contains("- ${goal.handle} 备半马（想跑一次完整的比赛） · 还没有步骤"))
        assertTrue("目标块在易变的那一片，排在今天之后", text.indexOf("他正在推进的目标") > text.indexOf("今天是"))
    }

    @Test
    fun theBackgroundRouteCarriesOnlyReadOnlyRecall() {
        val names = engine(env(search = false), route = PluginRoute.BACKGROUND).toolNames().toSet()
        assertEquals(setOf("search_sessions", "read_session"), names)
    }

    @Test
    fun theBackgroundRouteGetsSearchOnlyWhenTheCallerAsksForIt() {
        assertTrue("web_search" in engine(env(search = true), route = PluginRoute.BACKGROUND).toolNames())
        assertFalse("web_search" in engine(env(search = false), route = PluginRoute.BACKGROUND).toolNames())
    }

    @Test
    fun theBackgroundRouteKeepsTheHealthRulesButNotTheHealthData() {
        val text = engine(env(), route = PluginRoute.BACKGROUND).systemInstruction()
        assertTrue("后台说到健康时该守的底线不能丢", text.contains("急症优先于一切"))
        assertFalse(text.contains("青霉素"))
        assertFalse(text.contains("68.5"))
        assertFalse(text.contains("suggest_exercises"))
    }

    @Test
    fun theFamilyBlockIsForManagedMembersOnlyAndOnlyWithHealthOn() {
        assertFalse(engine(env(tenant = owner)).systemInstruction().contains("关于这次对话的对象"))

        val text = engine(env(tenant = family)).systemInstruction()
        assertTrue(text.contains("关于这次对话的对象"))
        assertTrue(text.contains("妈妈"))

        assertFalse(engine(env(health = false, tenant = family)).systemInstruction().contains("妈妈"))
    }

    @Test
    fun healthNotesOnCoreToolsFollowThoseToolsBeingMounted() {
        val full = engine(env()).systemInstruction()
        assertTrue(full.contains("搜索：常识性的健康知识"))
        assertTrue(full.contains("反问：缺的条件涉及症状时"))
        assertTrue(full.contains("回顾：他问的是自己记下的测量趋势时"))
        assertTrue(full.contains("记忆：用药与补剂走用药表工具；口述的测量数字走 log_measurement"))

        val noSearch = engine(env(search = false)).systemInstruction()
        assertFalse(noSearch.contains("搜索：常识性的健康知识"))

        val noMeasurements = engine(env(measurementsOn = false)).systemInstruction()
        assertFalse(noMeasurements.contains("回顾：他问的是自己记下的测量趋势时"))
        assertFalse(noMeasurements.contains("log_measurement"))
        assertTrue(noMeasurements.contains("用药与补剂走用药表工具"))
    }

    @Test
    fun rememberNamesWhatAlreadyHasItsOwnHomeOnlyWhileThatHomeIsOn() {
        fun description(env: PluginEnvironment) =
            engine(env).toolDefinitions().first { it.name == "remember" }.description.orEmpty()

        val both = description(env())
        assertTrue(both.contains("用药与补剂"))
        assertTrue(both.contains("测量数字"))

        val noMeds = description(env(medicationsOn = false))
        assertFalse("用药表关了，「我不能吃布洛芬」就该进记忆", noMeds.contains("用药与补剂"))
        assertTrue(noMeds.contains("测量数字"))

        val noHealth = description(env(health = false))
        assertEquals("当用户明确要求记住某件关于自己的长期情况、偏好或约定时调用。", noHealth)
    }

    @Test
    fun theExtractorPolicyFollowsTheSameSwitches() {
        val on = PluginRegistry.memoryPolicy(env())
        assertEquals(listOf("用药与补剂", "测量数字"), on.exclusions)
        assertTrue(on.guidance.any { it.contains("interpretation") })

        val off = PluginRegistry.memoryPolicy(env(health = false))
        assertTrue(off.exclusions.isEmpty())
        assertTrue(off.guidance.isEmpty())
    }

    @Test
    fun turningMemoryOffMountsNoRecallAndNoRemember() {
        val names = engine(env(memoryOn = false)).toolNames().toSet()
        assertFalse("remember" in names)
        assertFalse("search_sessions" in names)
        assertNull(engine(env(memoryOn = false)).systemInstruction().takeIf { "关于这位用户（来自过往对话）" in it })
    }

    // ================= 2. 健康开着:旧规则一句没丢 =================

    @Test
    fun healthOnKeepsEveryRuleTheOldBasePromptCarried() {
        val text = engine(env()).systemInstruction()
        listOf(
            "急症优先于一切",
            "胸痛或胸闷持续不缓解",
            "不要先查数据，也不要先分析可能的原因",
            "但不要滥用",
            "不要做医疗诊断，也不要替代医生",
            "不等同于诊断，并建议咨询专业医疗人员",
            "Android 端不连接手机、手表或第三方健康平台",
            "不要声称自动同步、读取或看到了设备健康数据",
            "不做影像诊断",
            "皮疹、伤口、眼底、一顿饭",
            "建议由医生当面看",
            // 动作库那一大段
            "只推荐它返回的动作",
            "excludeJoint",
            "器械不确定就别传 equipment",
            "不要给次数、组数或保持多少秒",
            "术后或孕期时不要给动作",
            // 不分话题、留在核心里的
            "想伤害自己或不想活了",
            "只引用用户提供或工具实际返回的数字",
            "「照片 N」",
            "「文件 N」",
            "默认你看不见图像",
            "用户同意把原图发给你",
        ).forEach { assertTrue("丢了这句：$it", text.contains(it)) }
    }

    // ================= 3. 健康关掉:零健康词 =================

    private val healthWords = listOf(
        "用药", "药品", "药盒", "补剂", "化验", "诊断", "剂量", "血压", "心率", "体重", "体检",
        "症状", "不舒服", "测量", "病史", "健康", "就医", "医疗", "医生", "疾病", "过敏",
        "suggest_exercises", "log_medication", "log_measurement", "list_medications", "list_measurements",
    )

    private fun assertNoHealthWords(text: String, label: String) {
        val leaked = healthWords.filter { text.contains(it) }
        assertTrue("$label 里还有健康词：$leaked", leaked.isEmpty())
    }

    @Test
    fun withHealthOffNothingTheModelReadsMentionsHealth() {
        for (route in PluginRoute.entries) {
            for (persona in AssistantPersona.entries) {
                for (private in listOf(false, true)) {
                    val engine = engine(
                        env(health = false, location = LocationSnapshot(place = "上海")),
                        route = route,
                        isPrivate = private,
                        persona = persona,
                    )
                    assertNoHealthWords(engine.everythingTheModelReads(), "route=$route persona=$persona private=$private")
                }
            }
        }
    }

    @Test
    fun theDetectorIsNotVacuousItSeesPlenty_whenHealthIsOn() {
        // 「关掉时零健康词」这条只有在检测器真能报出词来的时候才有意义。
        val corpus = engine(env()).everythingTheModelReads()
        val seen = healthWords.filter { corpus.contains(it) }
        assertTrue("健康开着时应当命中大量健康词，实际只有：$seen", seen.size >= 15)
    }

    // ================= 侧聊 =================

    /**
     * 侧聊说明只在侧聊里发,排在静态区(插话之后、人格之前、易变快照之前);还没起名时不写话题。
     * 侧聊不属于哪个插件:健康关掉之后,侧聊里模型读到的东西照样一个健康词都没有。
     */
    @Test
    fun theSideChatParagraphIsOnlySentInsideASideChatInTheStaticZone() {
        val marker = "这是一条侧聊"
        assertFalse(engine(env()).systemInstruction(acceptsInterjections = true).contains(marker))

        val persona = AssistantPersona.COACH
        val text = engine(env(), persona = persona, sideChatTitle = "十月去京都").systemInstruction(acceptsInterjections = true)
        assertTrue(text.contains("话题是「十月去京都」"))
        val order = listOf(
            "用户可能在你查资料或回答的中途补一句", // 插话
            marker, // 侧聊
            persona.instruction, // 人格
            "今天是", // 易变从这里开始
        ).map { it to text.indexOf(it) }
        order.forEach { (needle, index) -> assertTrue("缺少：$needle", index >= 0) }
        assertEquals("侧聊说明排在插话之后、人格之前", order.map { it.second }.sorted(), order.map { it.second })

        val untitled = engine(env(), sideChatTitle = "").systemInstruction()
        assertTrue(untitled.contains(marker))
        assertFalse(untitled.contains("话题是"))

        assertNoHealthWords(
            engine(env(health = false), sideChatTitle = "十月去京都").everythingTheModelReads(),
            "侧聊",
        )
    }

    /** 主对话里:够得着一条有内容的侧聊,没有自己滑出去的历史。 */
    private fun envReachingASideChat(health: Boolean = true, memoryOn: Boolean = true) = env(
        health = health,
        memoryOn = memoryOn,
        hiddenHistory = false,
        otherThreads = listOf(com.pinapia.vana.recall.HistoryRecallTools.Source(label = "侧聊「十月去京都」", archive = archive)),
        otherThreadsScope = OtherThreadsScope(
            others = "他开的侧聊",
            sideChats = listOf(RecallReach.Listing(title = "十月去京都", lastActiveAt = Clock.System.now())),
        ),
    )

    /**
     * 跨线程召回:只有别的线有原文时召回照样挂,说明里说清楚还够得着哪儿;主对话的易变区最后挂一份侧聊名单,
     * 末尾那句「他没提起时不要主动说起它们」必须在。
     */
    @Test
    fun recallReachesSideChatsAndTheRosterSitsAtTheVeryEnd() {
        val engine = engine(envReachingASideChat())
        assertTrue(HistoryRecallToolsNames.SEARCH in engine.toolNames())
        val text = engine.systemInstruction()
        assertTrue(text.contains("他开的侧聊里说过的你在这里看不到，但原文都还在。"))
        assertTrue(text.contains("他提到他开的侧聊里的事时也一样。"))
        assertTrue(text.contains("- 「十月去京都」，最近一次是 "))
        assertTrue(text.contains("他没提起时不要主动说起它们"))
        assertTrue("名单在易变区最后", text.indexOf("他另外开着几条侧聊") > text.indexOf("他正在推进的目标"))
        assertTrue(
            engine.toolDefinitions().first { it.name == HistoryRecallToolsNames.SEARCH }.description.orEmpty()
                .contains("以及别的对话线（主对话、侧聊）里说过的"),
        )
        assertNoHealthWords(engine(envReachingASideChat(health = false)).everythingTheModelReads(), "跨线程召回")
    }

    /** 只有这条对话自己时,召回那段话和原来逐字一样,一个「侧聊」都不提。名单跟着召回走,归记忆开关。 */
    @Test
    fun withoutOtherThreadsRecallTalksExactlyAsBeforeAndMemoryOffDropsTheRoster() {
        val text = engine(env()).systemInstruction()
        assertTrue(text.contains("这条对话更早的部分已经滑出了你能直接看到的范围，但原文都还在。默认不要去翻"))
        assertFalse(text.contains("侧聊"))
        val off = engine(envReachingASideChat(memoryOn = false))
        assertFalse(HistoryRecallToolsNames.SEARCH in off.toolNames())
        assertFalse(off.systemInstruction().contains("他另外开着几条侧聊"))
    }

    private object HistoryRecallToolsNames {
        const val SEARCH = com.pinapia.vana.recall.HistoryRecallTools.SEARCH_TOOL_NAME
    }

    @Test
    fun withHealthOffTheSafetyFloorIsStillThere() {
        val text = engine(env(health = false)).systemInstruction()
        assertTrue(text.contains("你是 Vana，用户的日常助手"))
        assertTrue(text.contains("拨打当地急救电话"))
        assertTrue(text.contains("想伤害自己或不想活了"))
        assertFalse(text.contains("suggest_exercises"))
    }
}
