package com.pinapia.vana.exercises

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ExerciseMove(
    val id: String,
    val zh: String,
    val en: String = "",
    val src: String = "",
    /** 什么**场合**用得上。可以一个都不属于：卧推不属于任何一个场合，它按部位挑。 */
    val scenes: List<String> = emptyList(),
    /**
     * 练**哪儿**。筛选用的闭集（`ExerciseTools.regions`），和下面那个 [part] 不是一回事。
     *
     * **两个字段是有意分开的。** [part] 是「上臂后侧 / 胸」这种写给人读的短语，它要出现在
     * 卡片上；而模型没法拿一个自由文本去过滤——「练胸」得对上一个确定的值。
     */
    val region: String = "",
    /**
     * 需要什么东西（`ExerciseTools.equipmentKinds`）。**硬过滤**，同 [risk] 和 [floor]：
     * 他手边没有杠铃，给一张杠铃的卡就是一张废卡，而他还得自己看出来这张卡为什么没用。
     */
    val equipment: String = "",
    /**
     * 需要一定基础的动作（单腿深蹲、倒立俯卧撑…）。**默认不推，他明确说了想练才给。**
     *
     * 用户多数是久坐的人和上了年纪的人，把 dragon flag 摆在「练核心」的第一张卡上，
     * 不是给他一个选择，是给他一次受伤的机会。但也不整条删掉——真在练的人会发现这个库里
     * 没有他要的东西。
     */
    val advanced: Boolean = false,
    val part: String = "",
    val gear: String = "",
    val steps: List<String> = emptyList(),
    val cue: String = "",
    val avoid: String = "",
    /** 这个动作会明显吃力的关节。用户说过哪儿不好，带那个关节的整组**根本不返回**。 */
    val risk: List<String> = emptyList(),
    /** 要不要到地上去（躺/跪/趴）。办公室、年纪大的用户、腰不好的人，这一条比部位还硬。 */
    val floor: Boolean = false,
    /**
     * 图，按动作发生的先后排。多于一张时卡片上交替显示。
     *
     * **一张静图说不出方向。** 最早那一版有 31 个动作只有一张彩色瑜伽图标，用户的原话是
     * 「不是动作，我都看不懂」——所以库里没有单张的动作：`wg` 是三帧，`ek` 是两态。
     */
    val files: List<String> = emptyList(),
)

@Serializable
private data class ExerciseFile(
    val scenes: List<String> = emptyList(),
    val moves: List<ExerciseMove> = emptyList(),
)

class ExerciseLibrary private constructor(
    val moves: List<ExerciseMove>,
    val scenes: List<String>,
) {
    private val byId = moves.associateBy { it.id }

    operator fun get(id: String): ExerciseMove? = byId[id]

    fun moves(ids: List<String>): List<ExerciseMove> = ids.mapNotNull { byId[it] }

    /**
     * 挑几个动作。
     *
     * **排除是硬的，排序是软的。** [excludeJoints]、[avoidsFloor]、[equipment] 直接把整组
     * 滤掉；剩下的按库里的固定顺序给，不做随机——同一个人在同一个条件下问两次拿到两组不同的
     * 动作，会让人以为前一组是随口说的。
     *
     * **场合和部位是两把不同的尺子，可以只给一把。** 「在工位上能做点什么」问的是场合，
     * 「练胸」问的是部位，而「跑完了拉一下腿」两个都问。都不给就什么都不返回：那不是
     * 「随便来三个」，那是这次调用没说清要什么。
     *
     * [equipment] 传 null 表示模型没说，走 [householdEquipment]；传空表表示「他什么都没有」，
     * 那也还剩徒手——一个动作都不给才是错的答案。
     */
    fun suggest(
        scene: String? = null,
        region: String? = null,
        excludeJoints: List<String> = emptyList(),
        avoidsFloor: Boolean = false,
        equipment: List<String>? = null,
        includesAdvanced: Boolean = false,
        limit: Int = 3,
    ): List<ExerciseMove> {
        val wantScene = scene?.takeIf { it.isNotBlank() }
        val wantRegion = region?.takeIf { it.isNotBlank() }
        if (wantScene == null && wantRegion == null) return emptyList()

        val excluded = excludeJoints.toSet()
        // 没说他手边有什么，就只给徒手和家里现成的那几样。**这个默认是有方向的**：
        // 推一个他没有的器械只是浪费一张卡，而这是个健康 app 不是健身房 app。
        val available = (equipment?.ifEmpty { listOf("徒手") } ?: householdEquipment).toSet()
        return moves
            .asSequence()
            .filter { wantScene == null || wantScene in it.scenes }
            .filter { wantRegion == null || wantRegion == it.region }
            .filter { excluded.intersect(it.risk.toSet()).isEmpty() }
            .filter { !(avoidsFloor && it.floor) }
            .filter { it.equipment in available }
            .filter { includesAdvanced || !it.advanced }
            .take(limit.coerceIn(1, 4))
            .toList()
    }

    companion object {
        /**
         * 不问也算他有的那几样：徒手，加上家里和工位上本来就有的东西。
         *
         * 长凳算在里面是因为一把椅子就能顶；箱子、瑜伽球、杠铃片不算——那是特意买过器材的人
         * 才有的。
         */
        val householdEquipment: List<String> = listOf("徒手", "墙", "门框", "毛巾", "椅子", "长凳")

        /**
         * 出处与授权。「关于」页要逐条列出来——CC BY-SA 要求保留出处。
         *
         * **改编者和原始来源都要署。** workout-guide 是 Bryl Lim 在 Everkinetic 上改的，
         * CC BY-SA 的传递性要求两个名字都在。
         */
        val attributions: List<String> = listOf(
            "动作图示 · everkinetic/data(CC BY-SA 4.0)",
            "动作图示 · bryllim/workout-guide，Bryl Lim(CC BY-SA 4.0，改编自 everkinetic/data)",
        )

        @Volatile
        private var cached: ExerciseLibrary? = null

        fun shared(context: Context): ExerciseLibrary {
            cached?.let { return it }
            return load(context.applicationContext).also { cached = it }
        }

        fun load(context: Context): ExerciseLibrary =
            runCatching {
                context.assets.open("exercises.json").bufferedReader().use { it.readText() }
            }.mapCatching { parse(it) }
                .getOrElse { ExerciseLibrary(moves = emptyList(), scenes = emptyList()) }

        /**
         * 从原始 JSON 建库。
         *
         * 单独拆出来是为了让单元测试够得着这份数据——测试里没有 `Context`，而这个库最要紧的
         * 那几条（关节排除、器械过滤、不给剂量）盯的正是数据本身，不是读文件那一步。
         */
        fun parse(raw: String): ExerciseLibrary {
            val file = Json { ignoreUnknownKeys = true }.decodeFromString(ExerciseFile.serializer(), raw)
            // 载不进来不是崩溃的理由：这个功能没了，别的照常。工具那边会照实说没有动作可推荐。
            return ExerciseLibrary(
                moves = file.moves.filter { it.files.isNotEmpty() },
                scenes = file.scenes,
            )
        }
    }
}
