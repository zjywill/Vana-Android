package com.pinapia.vana.exercises

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作库：挑得对、说得住、图不进上下文。
 *
 * 盯的大半是「不该发生什么」——排除掉的关节不许再出现、没有图的动作不许进库、图不许进模型
 * 上下文、挑不到不许报成错误、末尾那三句不许被删掉。最要紧的是排除那几条：记忆里写着
 * 「膝盖不好」而卡片上出现深蹲，是这个功能能出的最严重的一种故障。
 *
 * 数据和 iOS 那份是同一个文件（`Vana/Exercises/exercises.json`），所以这里断言的口径也要和
 * `VanaTests/ExerciseTests` 一致——两边对同一份数据说出两种结论，改的人只会更糊涂。
 */
class ExerciseLibraryTest {

    private val library = ExerciseLibrary.parse(
        File("src/main/assets/exercises.json").readText(),
    )

    @Test
    fun `库能从 assets 里载进来`() {
        assertTrue(library.moves.size >= 280)
        assertTrue(library.scenes.isNotEmpty())
    }

    /**
     * **一张静图说不出方向。** 库里曾经有 31 个动作只有一张彩色瑜伽图标，用户的原话是
     * 「不是动作，我都看不懂」——那正是这个功能最贵的一次失灵：图在、卡片在、步骤也在，屏幕上
     * 没有任何一处报错，只是那张图没有回答「我该往哪个方向动」。所以门槛不是「有图」，
     * 是**至少两帧**。
     */
    @Test
    fun `每个动作至少两帧`() {
        library.moves.forEach {
            assertTrue("${it.id} 只有一张图，说不出动作方向", it.files.size >= 2)
        }
    }

    @Test
    fun `每个动作都有步骤、要领、禁忌和部位`() {
        library.moves.forEach {
            assertTrue("${it.id} 没有步骤", it.steps.isNotEmpty())
            assertTrue("${it.id} 没有要领", it.cue.isNotBlank())
            // 这是卡片上唯一一句可能拦住伤害的话。
            assertTrue("${it.id} 没有禁忌", it.avoid.isNotBlank())
            // **场景可以是空的，部位不能。** 卧推不属于办公室、睡前、跑前任何一个场合——
            // 硬给它安一个，模型问「在工位上做点什么」时就会挑到它。它靠部位被挑出来。
            assertTrue("${it.id} 没有部位", it.region.isNotBlank())
        }
    }

    @Test
    fun `场景、部位、器械、关节都在声明过的那几组里`() {
        val scenes = library.scenes.toSet()
        library.moves.forEach {
            assertTrue("${it.id} 的场景不在名单里", scenes.containsAll(it.scenes))
            assertTrue("${it.id} 的部位不在名单里：${it.region}", it.region in ExerciseTools.regions)
            assertTrue("${it.id} 的器械不在名单里：${it.equipment}", it.equipment in ExerciseTools.equipmentKinds)
            assertTrue("${it.id} 的关节标签不在名单里", ExerciseTools.joints.containsAll(it.risk))
        }
    }

    /**
     * **不给次数、组数、保持秒数**，同「剂量一律不给建议」那条线。原始英文数据里到处都是
     * 「hold for 20-30 seconds」，照着译就带进来了，所以这条最容易在补动作时被破坏。
     *
     * 判据是**数字紧跟着单位**，不是出现过「秒」这个字：plank 那句「多撑的每一秒都是在练错的
     * 东西」说的正好是反面，按字面查会把这套东西里最该留下的一句话一起毙掉。
     */
    @Test
    fun `步骤里不出现次数、组数或保持秒数`() {
        val dosage = Regex("[0-9０-９]+\\s*(秒|分钟|组|次|下|遍)")
        library.moves.forEach { move ->
            (move.steps + move.cue + move.avoid).forEach { line ->
                assertFalse("${move.id} 里给了具体的量：$line", dosage.containsMatchIn(line))
            }
        }
    }

    /**
     * **`floor` 说的必须和步骤里写的是同一件事。**
     *
     * 这个旗标是硬过滤的判据，而它是一个布尔值——标错了，卡片上一切正常、步骤一个字没错，
     * 只是一个说了「起身困难」的人拿到了一组平板支撑。和「膝盖不好却拿到深蹲」是同一类失灵，
     * 只是更难被发现：关节那条至少还有用户自己说过的话可以对，这条只能靠有人去读步骤。
     *
     * 同一句里点了名的支撑物（凳、椅、球、架、机、垫、台）放过：躺在卧推凳上不是到地上去。
     */
    @Test
    fun `步骤里要趴要跪要躺的 floor 必须是 true`() {
        val onTheGround = Regex("[趴跪躺卧]")
        val onSomething = Regex("[凳椅球架机垫台]")
        library.moves.filter { !it.floor }.forEach { move ->
            (move.steps + move.gear).forEach { line ->
                if (onSomething.containsMatchIn(line)) return@forEach
                assertFalse(
                    "${move.id} 要到地上去，但 floor 标着 false：$line",
                    onTheGround.containsMatchIn(line),
                )
            }
        }
    }

    /**
     * **`equipment` 是硬过滤，所以它必须说的是「没有这个就做不了」。**
     *
     * 原始目录里 `Bodyweight` 的意思是「不加外部负重」，不是「不需要器材」——引体向上、双杠
     * 臂屈伸、山羊挺身在那份数据里全是 Bodyweight。照搬进来的话，一个在家问「练背」的人第一
     * 张卡就是引体向上，而他家里没有单杠。
     */
    @Test
    fun `标成家里现成的动作 器材那行不许写着健身房的东西`() {
        val gym = Regex("罗马椅|器械|龙门架|双杠|单杠|杠铃|哑铃|壶铃|绳索|瑜伽球")
        library.moves
            .filter { it.equipment in ExerciseLibrary.householdEquipment }
            // 「图上那条弹力带没有也一样做」这类说明是在讲它**不需要**什么，放过。
            .filter { !it.gear.contains("没有也") && !it.gear.contains("或背包") }
            .forEach {
                assertFalse(
                    "${it.id} 标着「${it.equipment}」，但器材写的是：${it.gear}",
                    gym.containsMatchIn(it.gear),
                )
            }
    }

    // MARK: - 挑选

    /**
     * **不问他有什么的时候，给的必须是他多半有的东西。** 这个默认没有出处——用户从没说过
     * 「我只有徒手」，是 app 替他假设的，所以假设错了他也无从知道为什么这张卡他做不了。
     */
    @Test
    fun `不指定器械时每个部位都还挑得到东西`() {
        ExerciseTools.regions.forEach { region ->
            val picked = library.suggest(region = region)
            assertTrue("「$region」在家里什么都挑不到", picked.isNotEmpty())
            assertTrue(picked.all { it.equipment in ExerciseLibrary.householdEquipment })
            assertTrue(picked.all { !it.advanced })
        }
    }

    /** 场合和部位是两把尺子，一把都不给不是「随便来三个」，是这次调用没说清要什么。 */
    @Test
    fun `场合和部位都不给就什么都不返回`() {
        assertTrue(library.suggest().isEmpty())
        assertTrue(ExerciseTools.emptyText(scene = "").contains("至少要给一个"))
    }

    /** **排除是硬的。** 排在后面不算——模型看到列表里有它就可能提一句，而用户已经说了做不了。 */
    @Test
    fun `排除掉的关节一个都不出现`() {
        val picked = library.suggest(region = "腿", excludeJoints = listOf("膝"), limit = 4)
        assertTrue(picked.isNotEmpty())
        assertTrue(picked.all { "膝" !in it.risk })
    }

    @Test
    fun `不方便到地上时不给躺跪趴的动作`() {
        val picked = library.suggest(scene = "腰背", avoidsFloor = true, limit = 4)
        assertTrue(picked.isNotEmpty())
        assertTrue(picked.all { !it.floor })
    }

    /** 他手边没有的东西，给了就是一张废卡，而且他还得自己看出来为什么没用。 */
    @Test
    fun `器械是硬过滤`() {
        val picked = library.suggest(region = "胸", equipment = listOf("哑铃"), limit = 4)
        assertTrue(picked.isNotEmpty())
        assertTrue(picked.all { it.equipment == "哑铃" })

        // 传了空数组是「他什么都没有」——那也还剩徒手，不是一个都不给。
        val bare = library.suggest(region = "核心", equipment = emptyList(), limit = 4)
        assertTrue(bare.isNotEmpty())
        assertTrue(bare.all { it.equipment == "徒手" })
    }

    /** 把 dragon flag 摆在「练核心」的第一张卡上，不是给他一个选择，是给他一次受伤的机会。 */
    @Test
    fun `高难度动作默认不出现 说了要才给`() {
        assertTrue(library.suggest(region = "核心", equipment = listOf("徒手"), limit = 4).all { !it.advanced })
        // 但也不整条删掉——真在练的人会发现这个库里没有他要的东西。
        assertTrue(library.moves.any { it.advanced })
    }

    /** 同一个人在同一个条件下问两次拿到两组不同的动作，会让人以为前一组是随口说的。 */
    @Test
    fun `同样的条件挑出来的是同一组`() {
        assertEquals(
            library.suggest(scene = "睡前", limit = 3).map { it.id },
            library.suggest(scene = "睡前", limit = 3).map { it.id },
        )
    }

    @Test
    fun `最多四个`() {
        assertTrue(library.suggest(scene = "腰背", limit = 99).size <= 4)
    }

    // MARK: - 工具输出

    /** **图对模型是零信息，对预算是纯损失**（同「逐小时序列只画在面板里」）。 */
    @Test
    fun `图不进模型上下文`() {
        val text = ExerciseTools.modelText(library.suggest(scene = "颈肩"))
        assertFalse(text.contains(".svg"))
        library.moves.forEach { move ->
            move.files.forEach { file ->
                assertFalse("工具输出里出现了图名 $file", text.contains(file.removeSuffix(".svg")))
            }
        }
    }

    /**
     * 这三句是这个工具真正的产出，不是免责声明。少第一句，正文会把卡片上已经有的步骤再抄一遍；
     * 少第二句，一个膝盖不好的人会拿到一组深蹲；少第三句，它会开始开处方。
     */
    @Test
    fun `末尾那三句都在`() {
        val text = ExerciseTools.modelText(library.suggest(scene = "腰背"))
        assertTrue(text.contains("不要把上面的步骤逐条复述"))
        assertTrue(text.contains("做不了的动作绝对不要提"))
        assertTrue(text.contains("不要给次数、组数"))
    }

    @Test
    fun `工具输出里带着中文名和步骤`() {
        val picked = library.suggest(scene = "颈肩")
        val text = ExerciseTools.modelText(picked)
        picked.forEach { move ->
            assertTrue(text.contains(move.zh))
            assertTrue(move.steps.all { text.contains(it) })
            assertTrue(text.contains(move.avoid))
        }
    }

    /**
     * 挑不到时要说清是被哪一条挡住的——尤其是器械那一档，它有一个**用户从没说过的默认值**，
     * 不念回去的话那次落空在他看来毫无道理，而他其实只要补一句「我有哑铃」就有了。
     */
    @Test
    fun `挑不到时把当时的条件念回去`() {
        val unspecified = ExerciseTools.emptyText(scene = "", region = "胸")
        assertTrue(unspecified.contains("没有指定器械"))
        assertTrue(unspecified.contains("徒手"))
        // 挑不到**不是错误**：报成错误模型会以为工具坏了，换个说法再调一次，白花一轮。
        assertTrue(unspecified.contains("不要自己编"))

        val limited = ExerciseTools.emptyText(scene = "", region = "胸", equipment = listOf("哑铃"))
        assertTrue(limited.contains("哑铃"))
        assertFalse(limited.contains("没有指定器械"))
    }

    /**
     * 署名是授权要求，不是礼貌：CC BY-SA 明写要保留出处，而少了那一行是静默的。
     * **改编者和原始来源两个名字都要在**——workout-guide 本身是 Everkinetic 的改编作品。
     */
    @Test
    fun `两个图源的署名都在`() {
        val joined = ExerciseLibrary.attributions.joinToString()
        assertTrue(joined.contains("everkinetic"))
        assertTrue(joined.contains("workout-guide"))
        assertTrue(joined.contains("Bryl Lim"))
        assertTrue(joined.contains("CC BY-SA"))
    }
}
