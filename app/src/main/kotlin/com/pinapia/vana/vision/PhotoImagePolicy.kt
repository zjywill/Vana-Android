package com.pinapia.vana.vision

/**
 * 拍进来的照片，原图默认要不要跟着发给模型。
 * 三档只管默认值：每一张在核对面板里还能单独翻。
 */
enum class PhotoImagePolicy(val raw: String) {
    TEXT_ONLY("textOnly"),
    ASK_WHEN_NO_TEXT("askWhenNoText"),
    ALWAYS("always"),
    ;

    val label: String
        get() = when (this) {
            TEXT_ONLY -> "只发文字"
            ASK_WHEN_NO_TEXT -> "认不出字时问一句"
            ALWAYS -> "每张都发原图"
        }

    val summary: String
        get() = when (this) {
            TEXT_ONLY ->
                "照片永远不出这台手机。认不出字的那些（一顿饭、一处皮疹）Vana 就答不上来，" +
                    "需要的话在那张图的核对面板里单独打开。"
            ASK_WHEN_NO_TEXT ->
                "只有本机一个字都没认出来的照片才问你一句，你点了才发。" +
                    "化验单、药盒这些认得出字的连问都不会问——它们的文字已经够回答问题了。"
            ALWAYS ->
                "每张照片的原图都会发到你配置的模型服务上，包括化验单——那上面有姓名、就诊号、" +
                    "医院和医生签名，而回答问题通常只需要那几行数值。"
        }

    /** `.askWhenNoText` 在这儿是 false：那一档的意思是问一句，不是替他答应。 */
    val sendsImageByDefault: Boolean get() = this == ALWAYS

    fun offers(hasText: Boolean): Boolean = when (this) {
        TEXT_ONLY -> false
        ASK_WHEN_NO_TEXT -> !hasText
        ALWAYS -> true
    }

    companion object {
        fun fromRaw(raw: String?): PhotoImagePolicy =
            entries.firstOrNull { it.raw.equals(raw, ignoreCase = true) } ?: ASK_WHEN_NO_TEXT
    }
}
