package com.pinapia.vana.agent

import com.pinapia.vana.agentruntime.MemoryPolicy
import com.pinapia.vana.memory.MemoryExtractor
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreInstructionsTest {
    @Test
    fun answerLanguageAndEmergencyNumberFollowUiLanguage() {
        withLocale(Locale.ENGLISH) {
            val prompt = CoreInstructions.text()
            assertTrue(prompt.contains("用English回答"))
            assertFalse(prompt.contains("中国大陆是 120"))
        }
        withLocale(Locale.SIMPLIFIED_CHINESE) {
            val prompt = CoreInstructions.text()
            assertTrue(prompt.contains("用简体中文回答"))
            assertTrue(prompt.contains("中国大陆是 120"))
        }
    }

    @Test
    fun theDateIsItsOwnBlockSoItsDailyChangeDoesNotInvalidateTheRules() {
        assertFalse("日期不能混在静态规则里", CoreInstructions.text().contains("今天是"))
        assertTrue(CoreInstructions.today(LocalDate.of(2026, 9, 29)).startsWith("今天是 2026-09-29"))
    }

    @Test
    fun theCorePromptDoesNotKnowAboutAnyDomain() {
        val prompt = CoreInstructions.text()
        listOf("健康", "用药", "化验", "诊断", "剂量", "症状", "测量").forEach {
            assertFalse("核心提示词里不该有「$it」", prompt.contains(it))
        }
    }

    // ---- 记忆抽取器的提示词也是「通用规则 + 插件贡献」 ----

    @Test
    fun theExtractorPromptIsGenericUntilAPluginContributes() {
        val bare = MemoryExtractor.instructions(MemoryPolicy())
        listOf("健康", "用药", "测量卡片", "诊断").forEach {
            assertFalse("没有插件贡献时不该出现「$it」", bare.contains(it))
        }
        assertTrue(bare.contains("profile"))
        assertTrue(bare.contains("followUp"))
        assertTrue("近况是核心的一类", bare.contains("episode"))
        assertFalse("interpretation 归健康插件，核心不再提示", bare.contains("interpretation"))
        assertTrue("一次性的要求不是偏好", bare.contains("一次性的要求不是偏好"))
    }

    @Test
    fun exclusionsAndGuidanceFromPluginsAreSpliced() {
        val text = MemoryExtractor.instructions(
            MemoryPolicy(
                exclusions = listOf("用药与补剂", "测量数字"),
                guidance = listOf("健康方面另有一类 interpretation 已有解释：某个指标的正常范围。"),
            ),
        )
        assertTrue(text.contains("用药与补剂、测量数字：这些有专门的地方存"))
        assertTrue(text.contains("另外要注意：\n- 健康方面另有一类 interpretation 已有解释：某个指标的正常范围。"))
    }

    private inline fun withLocale(locale: Locale, block: () -> Unit) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }
}
