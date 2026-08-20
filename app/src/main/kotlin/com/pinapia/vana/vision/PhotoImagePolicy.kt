package com.pinapia.vana.vision

import com.pinapia.vana.ui.L10n
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
            TEXT_ONLY -> L10n.text("只发文字", "Text only")
            ASK_WHEN_NO_TEXT -> L10n.text("认不出字时问一句", "Ask when no text is found")
            ALWAYS -> L10n.text("每张都发原图", "Always send original photos")
        }

    val summary: String
        get() = when (this) {
            TEXT_ONLY ->
                L10n.text(
                    "照片永远不出这台手机。认不出字的那些（一顿饭、一处皮疹）Vana 就答不上来，" +
                        "需要的话在那张图的核对面板里单独打开。",
                    "Photos never leave this device. For photos without text, such as a meal or rash, " +
                        "you can enable image sending for that photo in its review screen.",
                )
            ASK_WHEN_NO_TEXT ->
                L10n.text(
                    "只有本机一个字都没认出来的照片才问你一句，你点了才发。" +
                        "化验单、药盒这些认得出字的连问都不会问——它们的文字已经够回答问题了。",
                    "Vana asks only when no text was recognized, and sends the photo only after you agree. " +
                        "Photos with recognized text are handled using text alone.",
                )
            ALWAYS ->
                L10n.text(
                    "每张照片的原图都会发到你配置的模型服务上，包括化验单——那上面有姓名、就诊号、" +
                        "医院和医生签名，而回答问题通常只需要那几行数值。",
                    "Every original photo is sent to your model provider, including lab reports that may contain " +
                        "names, record numbers, hospitals and signatures.",
                )
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
