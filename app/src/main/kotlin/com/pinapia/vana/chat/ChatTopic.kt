package com.pinapia.vana.chat

data class ChatTopic(
    val id: String,
    val name: String,
    val focus: String,
    val questions: List<String>,
)

object ChatTopics {
    val workouts: List<ChatTopic> = listOf(
        ChatTopic(
            id = "running",
            name = "跑步",
            focus = "用户想聊跑步。优先看 workouts 里类型为「跑步」的记录（时长、消耗、频次），" +
                "再结合静息心率、HRV 和睡眠判断恢复情况。其他类型的锻炼只在做对比时提。",
            questions = listOf("最近跑量合适吗？", "跑完恢复得怎么样？", "这周还能再跑吗？"),
        ),
        ChatTopic(
            id = "cycling",
            name = "骑行",
            focus = "用户想聊骑行。优先看 workouts 里类型为「骑行」的记录，结合消耗和恢复指标回答。",
            questions = listOf("最近骑行强度如何？", "骑完恢复得怎么样？", "这周骑够了吗？"),
        ),
        ChatTopic(
            id = "strength",
            name = "力量训练",
            focus = "用户想聊力量训练。优先看 workouts 里类型为「力量训练」的记录，" +
                "关注频次和间隔，结合静息心率与 HRV 判断是否需要休息。",
            questions = listOf("这周练了几次？", "间隔够恢复吗？", "今天适合再练吗？"),
        ),
        ChatTopic(
            id = "swimming",
            name = "游泳",
            focus = "用户想聊游泳。优先看 workouts 里类型为「游泳」的记录，结合消耗和恢复指标回答。",
            questions = listOf("最近游得够多吗？", "游完消耗多少？", "频次合适吗？"),
        ),
        ChatTopic(
            id = "walking",
            name = "步行徒步",
            focus = "用户想聊步行和徒步。优先看 daily_steps，再看 workouts 里「步行」「徒步」的记录。",
            questions = listOf("最近走得够吗？", "今天走了多少？", "这周比上周多吗？"),
        ),
        ChatTopic(
            id = "yoga",
            name = "瑜伽拉伸",
            focus = "用户想聊瑜伽和拉伸。优先看 workouts 里「瑜伽」的记录，" +
                "结合 HRV 和睡眠聊放松与恢复,不要把它当成高强度训练来评估。",
            questions = listOf("最近练得规律吗？", "对睡眠有帮助吗？", "HRV 有变化吗？"),
        ),
        ChatTopic(
            id = "hiit",
            name = "高强度间歇",
            focus = "用户想聊高强度间歇训练。优先看 workouts 里「高强度间歇训练」的记录，" +
                "重点是频次和恢复——连续多次高强度要提醒风险。",
            questions = listOf("这周几次高强度？", "恢复跟得上吗？", "今天还能练吗？"),
        ),
    )

    val metrics: List<ChatTopic> = listOf(
        ChatTopic(
            id = "sleep",
            name = "睡眠",
            focus = "用户想聊睡眠。优先用 sleep_summary（时长、入睡与起床时间、波动），" +
                "需要时用 heart_rate_summary 看静息心率和 HRV 与睡眠的关系。",
            questions = listOf("昨晚睡得怎么样？", "最近睡眠稳定吗？", "入睡时间在变晚吗？"),
        ),
        ChatTopic(
            id = "heart",
            name = "心率与 HRV",
            focus = "用户想聊静息心率和 HRV。优先用 heart_rate_summary，" +
                "把变化和睡眠、锻炼负荷联系起来解释，但不要做诊断。",
            questions = listOf("静息心率正常吗？", "HRV 最近怎么样？", "和睡眠有关系吗？"),
        ),
        ChatTopic(
            id = "activity",
            name = "日常活动量",
            focus = "用户想聊日常活动量。优先用 daily_steps，必要时用 workouts 补充有记录的锻炼。",
            questions = listOf("今天活动量够吗？", "这周比上周多吗？", "哪天动得最少？"),
        ),
        ChatTopic(
            id = "body",
            name = "体重与体脂",
            focus = "用户想聊体重和体脂。优先用 body_metrics，结合活动量与锻炼解释趋势，不要下诊断。",
            questions = listOf("体重趋势怎么样？", "体脂有变化吗？", "和运动量有关吗？"),
        ),
        ChatTopic(
            id = "overall",
            name = "整体状态",
            focus = "用户想聊整体状态。综合睡眠、活动量、静息心率/HRV 和锻炼，给出一两句总览，再指出最值得看的一项。",
            questions = listOf("我最近状态如何？", "有哪项需要注意？", "这周比上周好吗？"),
        ),
    )

    val all: List<ChatTopic> get() = workouts + metrics

    fun find(id: String?): ChatTopic? = id?.let { key -> all.firstOrNull { it.id == key } }
}
