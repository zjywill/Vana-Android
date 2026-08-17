package com.pinapia.vana.legal

/**
 * 第一次打开时说清楚:数据会去哪儿。
 */
object DataUseNotice {
    const val ACCEPTED_KEY = "hasAcceptedDataUseNotice"

    data class Group(
        val title: String,
        val points: List<String>,
    )

    val leaves = Group(
        title = "会发给你配置的模型服务",
        points = listOf(
            "你打的字，以及这条对话里的往来",
            "化验单、报告、药盒在本机识别出来的文字",
            "照片原图——默认不发；本机认不出文字的那些会问你一句，你点了才发",
            "你所在的城市（授权了位置的话）",
            "长期记忆和用药表里的内容（没关掉的话）",
        ),
    )

    val stays = Group(
        title = "不会离开这台设备",
        points = listOf(
            "照片和文件原件——识别在本机做，发出去的默认只有文字；原图发不发在设置里定，每一张发送前还能单独改",
            "经纬度坐标——只发城市名，坐标一个字都不发",
            "你的 API key——只在本机加密存储里",
            "对话记录、记忆、用药表——存在本机，没有云端副本，也不进设备备份",
        ),
    )

    val systemServices = Group(
        title = "由 Android 系统服务处理",
        points = listOf(
            "按住说话的录音——Vana 请求优先离线识别，也不保存录音；具体是否联网由手机上的语音识别服务决定",
            "通知和相机权限只在你打开对应功能时使用，不经过 Vana 的服务器",
        ),
    )

    val noServer = Group(
        title = "Vana 自己没有服务器",
        points = listOf(
            "没有账号，没有后台，没有任何统计埋点",
            "开发者看不到你的数据——它不经过我们的任何一台机器",
            "发给哪家模型服务由你决定，对方如何处理适用它自己的隐私政策",
        ),
    )

    val groups = listOf(leaves, stays, systemServices, noServer)

    const val INTRO =
        "Vana 要靠一个模型来回答你的问题，而那个模型跑在你自己选的那家服务上。所以有些东西必须发出去，有些不用——这一屏说清是哪些。"

    const val TITLE = "在开始之前"
    const val CTA = "开始使用"
    const val PRIVACY_LINK = "完整的隐私说明"

    val medicalDisclaimer = """
        Vana 不是医疗器械，也不是医生。它给出的分析基于你的健康数据和一个通用语言模型，仅供参考，不构成医疗诊断、治疗方案或用药建议，也不会给出任何剂量建议，不能替代医生、药师或其他专业医疗人员的判断。

        模型会出错——它可能读错化验单上的一个小数点，也可能把一段过去的数据当成最近的。据此做出的任何健康决定，请先和专业人员确认。

        身体出现急症（例如胸痛、呼吸困难、意识改变、严重出血），或者有伤害自己的念头时，请立即就医或拨打当地急救电话，不要等 Vana 回答。
    """.trimIndent()
}
