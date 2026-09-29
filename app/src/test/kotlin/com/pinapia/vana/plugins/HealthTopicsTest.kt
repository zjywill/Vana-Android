package com.pinapia.vana.plugins

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTopicsTest {
    private inline fun <T> zh(block: () -> T): T {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            return block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun aHealthToolCallMakesTheAnswerHealthRelatedWhateverItSays() {
        assertTrue(HealthTopics.applies(listOf("log_measurement"), "记一下", "好的"))
        assertTrue(HealthTopics.applies(listOf("web_search", "suggest_exercises"), null, null))
    }

    @Test
    fun healthWordsInEitherSideOfTheExchangeCountEvenWithoutATool() {
        assertTrue(HealthTopics.applies(emptyList(), "我最近总头晕", "先别急"))
        assertTrue(HealthTopics.applies(emptyList(), "帮我整理一下", "如果症状持续请就医"))
        assertTrue(HealthTopics.applies(emptyList(), "What's the usual dosage?", null))
    }

    @Test
    fun anOrdinaryExchangeIsNotHealthRelated() {
        assertFalse(HealthTopics.applies(listOf("web_search", "remember"), "帮我整理一下今天要做的事", "好，先列三件"))
        assertFalse(HealthTopics.applies(emptyList(), null, ""))
    }

    @Test
    fun theGeneralDisclaimerIsAlwaysThereAndTheMedicalHalfOnlyForHealthTopics() = zh {
        val plain = HealthTopics.disclaimer(healthRelated = false)
        val medical = HealthTopics.disclaimer(healthRelated = true)
        assertTrue(plain.contains("AI 生成"))
        assertFalse(plain.contains("诊断"))
        assertTrue(medical.startsWith(plain))
        assertTrue(medical.contains("不构成诊断或用药建议"))
    }

    @Test
    fun theDisclaimerDoesNotDependOnTheHealthPluginSwitch() {
        // 判据只看内容,不看开关:关掉健康插件的人,收到一条谈症状的回答也该看到那半句。
        assertTrue(HealthTopics.applies(emptyList(), "我发烧了", null))
    }
}
