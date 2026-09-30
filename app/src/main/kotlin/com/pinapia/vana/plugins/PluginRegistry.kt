package com.pinapia.vana.plugins

import com.pinapia.vana.agentruntime.AgentPlugin
import com.pinapia.vana.agentruntime.MemoryPolicy
import com.pinapia.vana.agentruntime.PluginContext
import com.pinapia.vana.agentruntime.PluginHost
import com.pinapia.vana.ask.AskUserTools
import com.pinapia.vana.exercises.ExerciseTools
import com.pinapia.vana.exercises.exerciseIDs
import com.pinapia.vana.legal.DataUseNotice
import com.pinapia.vana.measurements.MeasurementTools
import com.pinapia.vana.medications.MedicationTools
import com.pinapia.vana.memory.MemoryItem
import com.pinapia.vana.memory.MemorySnapshot
import com.pinapia.vana.memory.MemoryTools
import com.pinapia.vana.recall.HistoryRecallTools
import com.pinapia.vana.search.WebFetchTools
import com.pinapia.vana.search.WebSearchTools
import com.pinapia.vana.session.ToolCallRecord
import com.pinapia.vana.tenant.TenantOpening
import com.pinapia.vana.tasks.TasksTools
import com.pinapia.vana.today.CoreToday
import com.pinapia.vana.today.HealthToday
import com.pinapia.vana.today.TodayCard
import com.pinapia.vana.today.TodayContext
import com.pinapia.vana.ui.L10n

/** 任何 Vana 都带着的那几样:反问、搜索、召回、记忆、位置。 */
object CorePlugin : VanaPlugin {
    override val manifest = PluginManifest(
        id = PluginIds.CORE,
        name = Localized("基础能力", "Core"),
        summary = Localized("记忆、召回、反问、网页搜索、位置", "Memory, recall, follow-up questions, web search, location"),
        defaultEnabled = true,
        togglable = false,
    )

    override val welcomeBlurb = Localized(
        zh = "记事、查资料、整理想法、看懂拍下来的文字",
        en = "keeping notes, looking things up, sorting out ideas and reading text from photos",
    )

    /** 只写现在真有的能力。 */
    override fun suggestions(context: SuggestionContext): SuggestionSet = SuggestionSet(
        buildList {
            add(L10n.text("帮我整理一下今天要做的事", "Help me plan what to do today"))
            add(L10n.text("明天早上八点提醒我带伞", "Remind me to bring an umbrella tomorrow at 8am"))
            if (context.isEnabled(PluginIds.MEMORY)) add(L10n.text("记住：我不吃香菜", "Remember that I don't eat cilantro"))
            add(L10n.text("帮我想想周末去哪儿玩", "Help me think of something to do this weekend"))
        },
    )

    override fun todayCards(context: TodayContext): List<TodayCard> = CoreToday.cards(context)

    override fun toolLabel(call: ToolCallRecord): String? = when (call.name) {
        MemoryTools.REMEMBER -> L10n.text("记住了", "Saved to memory")
        AskUserTools.ASK_TOOL_NAME -> L10n.text("问了你一句", "Asked a question")
        WebSearchTools.SEARCH_TOOL_NAME -> L10n.text("搜索了网页", "Searched the web")
        WebFetchTools.FETCH_TOOL_NAME -> L10n.text("读了一个网页", "Read a web page")
        HistoryRecallTools.SEARCH_TOOL_NAME -> L10n.text("查找了过往对话", "Searched past conversations")
        HistoryRecallTools.READ_TOOL_NAME -> L10n.text("读了一次过往对话", "Read a past conversation")
        TasksTools.GET_TIME -> L10n.text("看了一眼时间", "Checked the time")
        TasksTools.CREATE_REMINDER -> L10n.text("设了一个提醒", "Set a reminder")
        TasksTools.LIST -> L10n.text("查看了任务清单", "Looked at your task list")
        TasksTools.UPDATE_TASK -> L10n.text("更新了一项任务", "Updated a task")
        TasksTools.CREATE_GOAL -> L10n.text("记成了一个目标", "Saved a goal")
        TasksTools.UPDATE_GOAL -> L10n.text("更新了一个目标", "Updated a goal")
        // 以前存下来的 `start_task` 调用(后台任务 2026-09-30 撤掉了):胶囊上照样说得出是什么。
        "start_task" -> L10n.text("派了一个后台任务", "Started a background task")
        else -> null
    }

    override fun agentPlugins(env: PluginEnvironment, route: PluginRoute): List<AgentPlugin> {
        // 召回归在记忆开关下面:关掉记忆的人不指望 Vana 还在引用他上个月说过的话。
        // 而且只有真的有看不见的原文才挂——这条对话自己滑出窗口的那段,加上别的线(主对话、侧聊)整条。
        val memoryOn = env.isEnabled(PluginIds.MEMORY)
        val own = env.archive?.let { archive ->
            env.hiddenBeforePos()?.takeIf { archive.hasRowsBefore(it) }
                ?.let { HistoryRecallTools.Source(label = null, archive = archive, hiddenBeforePos = env.hiddenBeforePos) }
        }
        val sources = listOfNotNull(own) + env.otherThreads
        val recall = sources.takeIf { memoryOn && it.isNotEmpty() }?.let {
            RecallPlugin(
                sources = it,
                reach = env.otherThreadsScope?.takeIf { env.otherThreads.isNotEmpty() }
                    ?.let { scope -> RecallReach(ownHistory = own != null, others = scope.others, sideChats = scope.sideChats) },
            )
        }
        val memory = MemoryPlugin(
            store = env.memoryStore?.takeIf { memoryOn },
            snapshot = if (memoryOn) PluginRegistry.visibleMemory(env.memorySnapshot(), env.isEnabled) else MemorySnapshot.empty,
        )
        return when (route) {
            PluginRoute.FOREGROUND -> buildList {
                add(AskUserPlugin())
                env.webSearch?.let { add(WebSearchPlugin(it)) }
                env.webFetch?.let { add(WebFetchPlugin(it)) }
                recall?.let { add(it) }
                add(memory)
                add(LocationPlugin(env.location))
                env.tasks?.let { add(TasksPlugin(it)) }
            }
            // 后台派生:用户不在场。只带记忆(只读)和召回——结论不取决于别的,多挂一样就多花一份钱。
            PluginRoute.BACKGROUND -> buildList {
                // 搜索和读网页由调用方决定给不给;待跟进回访不给,多挂一样就多花一份钱。
                env.webSearch?.let { add(WebSearchPlugin(it)) }
                env.webFetch?.let { add(WebFetchPlugin(it)) }
                recall?.let { add(it) }
                add(memory)
            }
        }
    }
}

/**
 * 健康:规则、用药表、测量卡片、动作库、家人身份。整个可关;用药表和测量另有子开关。
 * 后台派生只带规则——那一轮没有用药表也没有测量工具,但说到健康时该守的底线一条不少。
 */
object HealthVanaPlugin : VanaPlugin {
    override val manifest = PluginManifest(
        id = PluginIds.HEALTH,
        name = Localized("健康", "Health"),
        summary = Localized(
            "用药与补剂、测量记录、锻炼动作库、化验单解读",
            "Medications, measurements, exercise library, lab report reading",
        ),
        defaultEnabled = true,
    )

    override val surfaces = listOf(
        PluginSurface(
            id = PluginSurface.MEDICATIONS,
            title = Localized("用药与补剂", "Medications and supplements"),
            subtitle = Localized("在吃的、不能吃的、试过没用的，分开记", "What you take, can't take, or tried"),
            toggleId = PluginIds.HEALTH_MEDICATIONS,
        ),
        PluginSurface(
            id = PluginSurface.MEASUREMENTS,
            title = Localized("测量卡片", "Measurement cards"),
            subtitle = Localized("口述的身高、体重、血压等，带观测时间", "Spoken height, weight, blood pressure and more, with observation time"),
            toggleId = PluginIds.HEALTH_MEASUREMENTS,
        ),
        PluginSurface(
            id = PluginSurface.FAMILY,
            title = Localized("家人档案", "Family members"),
            subtitle = Localized("为家人分别记录用药和测量，数据互相隔离", "Keep medications and measurements for family members, separately"),
        ),
    )

    override val disclaimer: String get() = DataUseNotice.medicalDisclaimer

    override val memoryKinds = setOf(MemoryItem.Kind.INTERPRETATION)

    override val welcomeBlurb = Localized(
        zh = "解读化验单、记用药与测量",
        en = "reading lab reports and tracking medications and measurements",
    )

    override fun todayCards(context: TodayContext): List<TodayCard> =
        if (context.isEnabled(PluginIds.HEALTH_MEDICATIONS)) HealthToday.cards(context) else emptyList()

    override fun suggestions(context: SuggestionContext): SuggestionSet {
        context.focusMedication?.openingQuestions?.let { return SuggestionSet(it, exclusive = true) }
        if (!context.tenant.isOwner) {
            return SuggestionSet(TenantOpening.questions(context.tenant, context.medications()), exclusive = true)
        }
        return SuggestionSet(
            listOf(
                L10n.text("帮我看看这张化验单", "Help me understand this lab report"),
                L10n.text("最近总感觉不舒服是怎么回事？", "Why have I been feeling unwell lately?"),
                L10n.text("帮我记下今天的体重", "Record today's weight for me"),
            ),
        )
    }

    override fun toolLabel(call: ToolCallRecord): String? = when (call.name) {
        MedicationTools.LIST -> L10n.text("查看了用药表", "Viewed medications")
        MedicationTools.LOG, MedicationTools.UPDATE -> L10n.text("更新了用药表", "Updated medications")
        MeasurementTools.LIST -> L10n.text("查看了测量卡片", "Viewed measurements")
        MeasurementTools.LOG -> L10n.text("记下了测量", "Recorded a measurement")
        ExerciseTools.SUGGEST_TOOL_NAME ->
            if (call.exerciseIDs.isEmpty()) {
                L10n.text("没找到合适的动作", "No suitable exercise found")
            } else {
                L10n.text("挑了 ${call.exerciseIDs.size} 个动作", "Selected ${call.exerciseIDs.size} exercises")
            }
        else -> null
    }

    override fun agentPlugins(env: PluginEnvironment, route: PluginRoute): List<AgentPlugin> {
        if (!env.isEnabled(PluginIds.HEALTH)) return emptyList()
        val rules = HealthRulesPlugin()
        if (route == PluginRoute.BACKGROUND) return listOf(rules)
        return buildList {
            add(rules)
            env.exerciseLibrary?.let { add(ExercisePlugin(it)) }
            if (env.isEnabled(PluginIds.HEALTH_MEDICATIONS)) {
                env.medicationStore?.let {
                    add(MedicationPlugin(store = it, snapshot = env.medicationSnapshot(), focus = env.focusMedication))
                }
            }
            if (env.isEnabled(PluginIds.HEALTH_MEASUREMENTS)) {
                env.measurementStore?.let {
                    add(MeasurementPlugin(store = it, snapshot = env.measurementSnapshot()))
                }
            }
            add(FamilyPlugin(env.tenant))
        }
    }
}

/**
 * 哪条路挂哪些插件。**注册顺序就是工具定义发出去的顺序**,同 order 的提示词块也按它稳定排序。
 * 开关已经兑现在 [PluginEnvironment] 里(关着就没有 store),所以这里只按整个插件的开关过滤。
 * 隐私会话和后台派生不在这里分,由 [PluginContext] 按工具声明的副作用统一过滤。
 */
object PluginRegistry {
    val all: List<VanaPlugin> = listOf(CorePlugin, NotesVanaPlugin, HealthVanaPlugin)

    fun agentPlugins(env: PluginEnvironment, route: PluginRoute): List<AgentPlugin> =
        all.filter { env.isEnabled(it.manifest.id) }
            .flatMap { it.agentPlugins(env, route) }

    /** 记忆抽取器要遵守的、来自各生效插件的规则(排除项加领域补充)。 */
    fun memoryPolicy(env: PluginEnvironment): MemoryPolicy =
        PluginHost.memoryPolicy(agentPlugins(env, PluginRoute.FOREGROUND))

    /** 哪个插件拥有这种记忆。核心的种类返回 null。 */
    fun memoryOwner(kind: MemoryItem.Kind): VanaPlugin? = all.firstOrNull { kind in it.memoryKinds }

    /** 这种记忆现在该不该带进对话:它的主人关了就不带。 */
    fun isMemoryVisible(kind: MemoryItem.Kind, isEnabled: (String) -> Boolean): Boolean =
        memoryOwner(kind)?.let { isEnabled(it.manifest.id) } ?: true

    /** 只留下现在生效的插件该看到的记忆。聊天和抽取器用同一份,两边对「记着什么」的认识才一致。 */
    fun visibleMemory(snapshot: MemorySnapshot, isEnabled: (String) -> Boolean): MemorySnapshot =
        snapshot.filtered { isMemoryVisible(it.kind, isEnabled) }

    /** 插件页里列出来的:用户能开关的那些。 */
    val togglable: List<VanaPlugin> get() = all.filter { it.manifest.togglable }

    /**
     * 「今天」页上的那几行:各插件贡献的合起来,重要的在前。关掉的插件不出。
     *
     * 不设条数上限:以前它是对话里的一张卡,要给对话腾地方;现在是单独一页,今天到点的提醒少列一条,
     * 就是那一页在对他说谎。各插件自己限着回访那几类的条数。
     */
    fun todayCards(context: TodayContext): List<TodayCard> =
        all.filter { context.isEnabled(it.manifest.id) }
            .flatMap { it.todayCards(context) }
            .sortedByDescending { it.priority }

    /**
     * 首屏的 [limit] 条建议。**通用优先**:核心先占位,其余插件轮流分剩下的;
     * 某个插件正处在具体上下文里(聊某样药、替家人问)时,只给它的。
     */
    fun suggestions(context: SuggestionContext, limit: Int = 3): List<String> {
        val sets = all.filter { context.isEnabled(it.manifest.id) }.map { it.suggestions(context) }
        sets.firstOrNull { it.exclusive && it.items.isNotEmpty() }?.let { return it.items.take(limit) }
        return mix(sets.map { it.items }, limit)
    }

    /**
     * 通用优先:第一份(核心)占大头,其余各份轮流分**至少一个、约三分之一**的位子(`limit = 3` 就是 1 个),
     * 某一份不够再从别处补。这样以后多了几个插件也不会把核心挤到一个位子。
     */
    internal fun mix(lists: List<List<String>>, limit: Int): List<String> {
        val core = lists.firstOrNull().orEmpty()
        val others = lists.drop(1).filter { it.isNotEmpty() }
        val reserved = if (others.isEmpty()) 0 else maxOf(1, limit / 3)
        val coreSlots = limit - reserved
        val result = core.take(coreSlots).toMutableList()
        var round = 0
        while (result.size < limit && others.any { round < it.size }) {
            for (other in others) {
                if (result.size < limit && round < other.size) result += other[round]
            }
            round++
        }
        if (result.size < limit) result += core.drop(coreSlots).take(limit - result.size)
        return result.distinct()
    }

    /** 欢迎语。「我能帮你……」由各生效的插件各贡献一小段,健康关掉就不提健康。 */
    fun welcomeBody(isEnabled: (String) -> Boolean): String {
        val blurbs = all.filter { isEnabled(it.manifest.id) }.mapNotNull { it.welcomeBlurb }
        val first = blurbs.firstOrNull()
        val rest = blurbs.drop(1)
        val zh = buildString {
            append("日常的事都可以交给我")
            if (first != null) append("：${first.zh}")
            append("。")
            if (rest.isNotEmpty()) append("也能${rest.joinToString("、") { it.zh }}。")
            append("文字识别在本机完成；要回答问题时才会把必要内容发给你配置的模型。")
        }
        val en = buildString {
            append("Ask me about everyday things")
            if (first != null) append(": ${first.en}")
            append(". ")
            if (rest.isNotEmpty()) append("I can also help with ${rest.joinToString(" and ") { it.en }}. ")
            append("Text recognition runs on-device; only the content needed to answer is sent to the model you configure.")
        }
        return L10n.text(zh, en)
    }

    /** 工具调用在聊天里的那一行字。历史消息里的调用照样认,不看插件现在开没开。 */
    fun toolLabel(call: ToolCallRecord): String =
        all.firstNotNullOfOrNull { it.toolLabel(call) }
            ?: L10n.text("调用了 ${call.name}", "Used ${call.name}")

    fun foregroundContext(isPrivate: Boolean) = PluginContext(isPrivate = isPrivate)

    fun backgroundContext() = PluginContext(isBackground = true)
}
