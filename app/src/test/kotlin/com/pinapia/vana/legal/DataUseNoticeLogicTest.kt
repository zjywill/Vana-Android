package com.pinapia.vana.legal

import java.util.Locale
import org.junit.Assert.assertFalse
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

    /**
     * 声明和行为对不上是合规这一块唯一的失败模式。后台任务(子 agent)2026-09-30 撤掉了:告知屏和隐私说明
     * 中英两份都不能再许它,也都要说清侧聊各自发什么。隐私说明读的是打进包里的那两份原文件。
     */
    @Test
    fun theNoticeAndBothPrivacyPoliciesDescribeSideChatsAndNoLongerPromiseBackgroundTasks() {
        withLocale(Locale.SIMPLIFIED_CHINESE) {
            val leaving = DataUseNotice.leaves.points.joinToString()
            assertTrue(leaving.contains("侧聊"))
            assertFalse(leaving.contains("后台任务"))
        }
        withLocale(Locale.ENGLISH) {
            val leaving = DataUseNotice.leaves.points.joinToString()
            assertTrue(leaving.contains("side chat"))
            assertFalse(leaving.contains("ackground task"))
        }

        val zh = java.io.File("src/main/assets/PrivacyPolicy.html").readText()
        assertTrue(zh.contains("<strong>侧聊。</strong>"))
        assertTrue(zh.contains("一条持续的主对话，加上你自己开的侧聊"))
        assertTrue(zh.contains("主对话和全部侧聊，含照片"))
        assertFalse(zh.contains("后台任务"))
        assertFalse(zh.contains("只读任务自动开始"))
        assertFalse(zh.contains("每周回顾"))

        val en = java.io.File("src/main/assets/PrivacyPolicy.en.html").readText()
        assertTrue(en.contains("<strong>Side chats.</strong>"))
        assertTrue(en.contains("one ongoing main conversation plus any side chats you open yourself"))
        assertFalse(en.contains("ackground task"))
        assertFalse(en.contains("Weekly review"))
    }
}
