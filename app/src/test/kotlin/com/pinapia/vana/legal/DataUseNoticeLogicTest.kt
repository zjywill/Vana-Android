package com.pinapia.vana.legal

import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iOS 2026-08-29 被 5.1.1(i)/5.1.2(i) 判的两处,Android 同步修:按钮必须是明确同意,
 * 「发给谁」必须点得出名字。改文案时这两样掉一样,判词就会原样回来。
 * 两种语言各验一遍——L10n 按 locale 分支,只验默认那份等于没验另一半。
 */
class DataUseNoticeLogicTest {
    private fun <T> withLocale(locale: Locale, block: () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            return block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun noticeIsExplicitConsentInBothLocales() {
        withLocale(Locale.SIMPLIFIED_CHINESE) {
            assertTrue(DataUseNotice.cta.contains("同意"))
            // 同意说明里引用的按钮文字必须和按钮是同一串。
            assertTrue(DataUseNotice.consentFootnote.contains("「${DataUseNotice.cta}」"))
        }
        withLocale(Locale.ENGLISH) {
            assertTrue(DataUseNotice.cta.contains("Agree"))
            assertTrue(DataUseNotice.consentFootnote.contains("“${DataUseNotice.cta}”"))
        }
    }

    @Test
    fun noticeNamesTheThirdPartyInBothLocales() {
        for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
            withLocale(locale) {
                val leaving = DataUseNotice.leaves.points.joinToString()
                assertTrue("$locale：「发给谁」没有名字", leaving.contains("DeepSeek"))
                assertTrue(
                    "$locale：没说清对方是第三方",
                    leaving.contains("第三方") || leaving.contains("third-party"),
                )
            }
        }
    }
}
