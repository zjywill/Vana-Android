package com.pinapia.vana.plugins

/**
 * system 段里每一块排在哪。
 *
 * **按「会不会变」分区,不按「谁的」分区**:prompt 缓存认的是前缀,前缀里任何一个字变了,
 * 它后面的全部都要重新按全价算。所以一次会话里不变的排前面,会变的排后面,
 * 一变只打掉尾巴,不打掉整段:
 *
 * - `0–99`    核心静态:身份与规则、插话、侧聊说明、人格。
 * - `100–199` 核心插件的工具用法。只在对应工具挂出去时才有,同一条会话里挂载集合不变。
 * - `200–299` 健康插件:规则与它自己的工具用法,同上。
 * - `300–399` 易变快照:今天、位置、记忆、用药、测量、目标……随时会变,一律排在最后。
 *
 * 数字之间留了空,新插件往自己的区间里加,不用挪别人的。同一个数字按插件注册顺序稳定排序。
 */
object PromptOrder {
    // ---- 核心静态 ----
    const val BASE = 0
    const val INTERJECTION = 20

    /** 侧聊的说明:这是哪件事、主对话在别处。只有侧聊有;侧聊存在期间逐字不变(改名时变一次)。 */
    const val SIDE_CHAT = 25
    const val PERSONA = 30

    /** 后台助手(子 agent)的角色说明。只有派出去的那一路有。 */
    const val SUBAGENT = 10

    // ---- 核心插件的工具用法 ----
    const val GUIDE_RECALL = 100
    const val GUIDE_REMEMBER = 110
    const val GUIDE_WEB_SEARCH = 120
    const val GUIDE_WEB_FETCH = 125
    const val GUIDE_ASK_USER = 130
    const val GUIDE_TASKS = 140
    const val GUIDE_JOBS = 145
    const val GUIDE_NOTES = 150

    // ---- 健康插件 ----
    const val HEALTH_RULES = 200
    const val GUIDE_EXERCISE = 210
    const val GUIDE_MEDICATION_LOG = 220
    const val GUIDE_MEDICATION_LIST = 230
    const val GUIDE_MEASUREMENT_LOG = 240
    const val GUIDE_MEASUREMENT_LIST = 250

    /** 健康插件对核心工具的补充(搜索、反问、召回、记忆),各自按那个工具是否挂出去门控。 */
    const val HEALTH_TOOL_NOTES = 260

    // ---- 易变快照 ----
    const val TODAY = 300
    const val TENANT = 310
    const val LOCATION = 320
    const val MEMORY = 330
    const val MEDICATIONS = 340
    const val MEASUREMENTS = 350
    const val FOCUS_MEDICATION = 360
    const val GOAL = 370

    /** 主对话里挂的侧聊名单:几个名字加最近一次的日期。侧聊一有人说话就可能变,排在最后。 */
    const val SIDE_CHATS = 380
}
