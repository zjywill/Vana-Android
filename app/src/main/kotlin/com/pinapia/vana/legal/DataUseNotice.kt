package com.pinapia.vana.legal

import com.pinapia.vana.ui.L10n

/**
 * 第一次打开时说清楚:数据会去哪儿。
 */
object DataUseNotice {
    const val ACCEPTED_KEY = "hasAcceptedDataUseNotice"

    data class Group(
        val title: String,
        val points: List<String>,
    )

    val leaves: Group
        get() = Group(
        title = L10n.text("会发给你配置的模型服务", "Sent to the model service you configure"),
        points = listOf(
            L10n.text("你打的字，以及这条对话里的往来", "What you type and the earlier messages in this conversation"),
            L10n.text("化验单、报告、药盒在本机识别出来的文字", "Text recognized on this device from reports and medicine packaging"),
            L10n.text("照片原图——默认不发；本机认不出文字的那些会问你一句，你点了才发", "Original photos are not sent by default; Vana asks before sending a photo when no text was recognized"),
            L10n.text("你所在的城市（授权了位置的话）", "Your city, if you allow approximate location"),
            L10n.text("长期记忆和用药表里的内容（没关掉的话）", "Long-term memory and medication-list content, if enabled"),
        ),
    )

    val stays: Group
        get() = Group(
        title = L10n.text("不会离开这台设备", "Does not leave this device"),
        points = listOf(
            L10n.text("照片和文件原件——识别在本机做，发出去的默认只有文字；原图发不发在设置里定，每一张发送前还能单独改", "Photo and file originals; recognition runs on-device and each photo can be reviewed before sending"),
            L10n.text("经纬度坐标——只发城市名，坐标一个字都不发", "Latitude and longitude; only the city name may be sent"),
            L10n.text("你的 API key——只在本机加密存储里", "Your API key; it stays in encrypted storage on this device"),
            L10n.text("对话记录、记忆、用药表——存在本机，没有云端副本，也不进设备备份", "Conversation history, memory and medication lists; they have no cloud copy and are excluded from backup"),
        ),
    )

    val systemServices: Group
        get() = Group(
        title = L10n.text("由 Android 系统服务处理", "Handled by Android system services"),
        points = listOf(
            L10n.text("按住说话的录音——Vana 请求优先离线识别，也不保存录音；具体是否联网由手机上的语音识别服务决定", "Hold-to-talk audio; Vana requests offline recognition and never saves the recording, but the installed speech service controls network use"),
            L10n.text("通知和相机权限只在你打开对应功能时使用，不经过 Vana 的服务器", "Notification and camera permissions are used only for features you open and never pass through a Vana server"),
        ),
    )

    val noServer: Group
        get() = Group(
        title = L10n.text("Vana 自己没有服务器", "Vana operates no server"),
        points = listOf(
            L10n.text("没有账号，没有后台，没有任何统计埋点", "No accounts, backend or analytics SDK"),
            L10n.text("开发者看不到你的数据——它不经过我们的任何一台机器", "The developer cannot see your data; it never passes through our machines"),
            L10n.text("发给哪家模型服务由你决定，对方如何处理适用它自己的隐私政策", "You choose the model service, whose own privacy policy governs its processing"),
        ),
    )

    val groups: List<Group> get() = listOf(leaves, stays, systemServices, noServer)

    val intro: String
        get() = L10n.text(
            "Vana 要靠一个模型来回答你的问题，而那个模型跑在你自己选的那家服务上。所以有些东西必须发出去，有些不用——这一屏说清是哪些。",
            "Vana uses a model hosted by the service you choose. Some information must be sent to answer you, while other information stays on this device. This screen explains the difference.",
        )

    val title: String get() = L10n.text("在开始之前", "Before you begin")
    val cta: String get() = L10n.text("开始使用", "Start using Vana")
    val privacyLink: String get() = L10n.text("完整的隐私说明", "Full privacy policy")

    val medicalDisclaimer: String
        get() = L10n.text(
            """
            Vana 不是医疗器械，也不是医生。它根据你提供的信息和一个通用语言模型生成回答，仅供参考，不构成医疗诊断、治疗方案或用药建议，也不会给出任何剂量建议，不能替代医生、药师或其他专业医疗人员的判断。

            模型会出错——它可能读错化验单上的一个小数点，也可能把一段过去的数据当成最近的。据此做出的任何健康决定，请先和专业人员确认。

            身体出现急症（例如胸痛、呼吸困难、意识改变、严重出血），或者有伤害自己的念头时，请立即就医或拨打当地急救电话，不要等 Vana 回答。
            """.trimIndent(),
            """
            Vana is not a medical device or a doctor. It generates answers from the information you provide and a general-purpose language model. Its output is for reference only, is not a diagnosis, treatment plan or medication advice, gives no dosage recommendations, and cannot replace a doctor, pharmacist or other health professional.

            Models make mistakes. They may misread a decimal point or treat an older measurement as recent. Confirm any health decision with a qualified professional.

            In an emergency such as chest pain, trouble breathing, altered consciousness or severe bleeding, or if you may harm yourself, seek help immediately or call your local emergency number. Do not wait for Vana.
            """.trimIndent(),
        )
}
