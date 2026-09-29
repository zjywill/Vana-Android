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
            // 「发给谁」必须点得出名字(iOS 2026-08-29 被 5.1.1(i)/5.1.2(i) 判的一半):
            // 默认预选的是 DeepSeek,写出来;第一次真发给某家之前还会点名再问一次。
            L10n.text(
                "对方是一家由你选定的第三方模型服务——默认预选的是 DeepSeek（深度求索），可以在设置里换成目录里的其他家。第一次真的要发给某一家之前，Vana 还会点名问你一次",
                "The recipient is a third-party model service you choose — DeepSeek is pre-selected by default, and you can switch to any other provider in Settings. The first time Vana is about to send to a given service, it names that service and asks you once more",
            ),
            L10n.text(
                "你打的字，以及最近一段对话里的往来（更早的原文只在需要回顾时才按需取出相关片段）",
                "What you type and the recent part of the conversation (older text is retrieved only when a recall is needed)",
            ),
            L10n.text("票据、报告、说明书、药盒、化验单等在本机识别出来的文字", "Text recognized on this device from receipts, reports, manuals, medicine packaging and lab reports"),
            L10n.text("照片原图——默认不发；本机认不出文字的那些会问你一句，你点了才发", "Original photos are not sent by default; Vana asks before sending a photo when no text was recognized"),
            L10n.text("你所在的城市（授权了位置的话）", "Your city, if you allow approximate location"),
            L10n.text("长期记忆，以及用药表、测量卡片里的内容（没关掉的话）", "Long-term memory, and medication-list and measurement-card content, if enabled"),
            L10n.text(
                "目标的名称和进展；你让 Vana 去读的笔记的内容（笔记不常驻，读的那一次才发出）",
                "The names and progress of your goals, and the content of a note only when you ask Vana to read it",
            ),
            L10n.text(
                "为了记住长期成立的事，离开 app 或对话空闲一段时间后，还没处理过的对话片段（开着记忆才做）",
                "To remember things that stay true, not-yet-processed parts of the conversation after you leave the app or it goes idle (only when Memory is on)",
            ),
            L10n.text(
                "你点了「开始」的后台任务：任务说明、它用到的记忆和过往对话片段、它搜索或读取到的网页内容。后台任务只读，不会改动你的数据",
                "Background tasks you start: the brief, the memory and past-conversation excerpts it uses, and the web content it finds. Background tasks are read-only and never change your data",
            ),
        ),
    )

    val direct: Group
        get() = Group(
        title = L10n.text("由这台设备直接访问", "Fetched directly by this device"),
        points = listOf(
            L10n.text(
                "你发来的链接、或搜索结果里值得细看的网页——由这台设备直接访问该网站，不经过中转；对方网站能看到你的 IP 地址和请求的网址。本机和内网地址一律不读",
                "Links you send or search results worth reading — this device visits the site directly with no relay, so the site can see your IP address and the URL. Localhost and private-network addresses are refused",
            ),
            L10n.text("网页搜索（填了搜索 key 才有）——搜索词发给 serper.dev，不含你的个人情况", "Web search, only if you add a search key — search terms go to serper.dev and never include your personal situation"),
        ),
    )

    val stays: Group
        get() = Group(
        title = L10n.text("不会离开这台设备", "Does not leave this device"),
        points = listOf(
            L10n.text("照片和文件原件——识别在本机做，发出去的默认只有文字；原图发不发在设置里定，每一张发送前还能单独改", "Photo and file originals; recognition runs on-device and each photo can be reviewed before sending"),
            L10n.text("经纬度坐标——只发城市名，坐标一个字都不发", "Latitude and longitude; only the city name may be sent"),
            L10n.text("你的 API key——只在本机加密存储里", "Your API key; it stays in encrypted storage on this device"),
            L10n.text("对话记录、记忆、笔记、提醒、目标、用药表和测量卡片——存在本机，没有云端副本，也不进设备备份", "Conversation history, memory, notes, reminders, goals, medication lists and measurement cards; they have no cloud copy and are excluded from backup"),
        ),
    )

    val systemServices: Group
        get() = Group(
        title = L10n.text("由 Android 系统服务处理", "Handled by Android system services"),
        points = listOf(
            L10n.text("按住说话的录音——Vana 请求优先离线识别，也不保存录音；具体是否联网由手机上的语音识别服务决定", "Hold-to-talk audio; Vana requests offline recognition and never saves the recording, but the installed speech service controls network use"),
            L10n.text("通知（含你设的提醒，到点只发通知、不联网也不调用模型）和相机权限只在你打开对应功能时使用，不经过 Vana 的服务器", "Notifications (including reminders you set, which only post a notification and never use the network or a model) and camera permission are used only for features you open and never pass through a Vana server"),
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

    val groups: List<Group> get() = listOf(leaves, direct, stays, systemServices, noServer)

    val intro: String
        get() = L10n.text(
            "Vana 要靠一个模型来回答你的问题，而那个模型跑在你自己选的那家服务上。所以有些东西必须发出去，有些不用——这一屏说清是哪些。",
            "Vana uses a model hosted by the service you choose. Some information must be sent to answer you, while other information stays on this device. This screen explains the difference.",
        )

    val title: String get() = L10n.text("在开始之前", "Before you begin")

    /**
     * 这一屏是一次**明确同意**,不只是告知。第一版按钮写「开始使用」(告知不做成同意书),
     * iOS 2026-08-29 被 5.1.1(i)/5.1.2(i) 判回来:发给第三方 AI 服务之前必须
     * obtain the user's permission。按钮上方那句 [consentFootnote] 说清点下去同意了什么。
     */
    val cta: String get() = L10n.text("同意并继续", "Agree and continue")

    /** 按钮上方那句:点下去到底同意了什么。引号里的按钮文字必须和 [cta] 是同一串。 */
    val consentFootnote: String
        get() = L10n.text(
            "点「同意并继续」，表示你已读过上面的说明，并同意 Vana 在你提问时，把「会发出去」那一组里列出的内容发给你选定的模型服务来生成回答。",
            "By tapping “Agree and continue”, you confirm you have read the notice above and agree that, when you ask a question, Vana sends the items listed under “Sent to the model service you configure” to the model service you choose in order to generate the answer.",
        )

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
