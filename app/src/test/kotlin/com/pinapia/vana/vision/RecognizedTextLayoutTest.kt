package com.pinapia.vana.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognizedTextLayoutTest {
    @Test
    fun tableRowStaysTogether() {
        val text = RecognizedTextLayout.reconstruct(
            listOf(
                RecognizedFragment("血红蛋白", 0.05f, 0.30f, 0.20f, 0.03f),
                RecognizedFragment("132", 0.35f, 0.30f, 0.06f, 0.03f),
                RecognizedFragment("g/L", 0.50f, 0.30f, 0.06f, 0.03f),
                RecognizedFragment("130-175", 0.65f, 0.30f, 0.12f, 0.03f),
            ),
        )
        assertEquals("血红蛋白 | 132 | g/L | 130-175", text)
    }

    @Test
    fun readingOrderIsRestored() {
        val text = RecognizedTextLayout.reconstruct(
            listOf(
                RecognizedFragment("132", 0.35f, 0.30f, 0.06f, 0.03f),
                RecognizedFragment("白细胞", 0.05f, 0.35f, 0.16f, 0.03f),
                RecognizedFragment("血红蛋白", 0.05f, 0.30f, 0.20f, 0.03f),
                RecognizedFragment("6.1", 0.35f, 0.35f, 0.06f, 0.03f),
            ),
        )
        assertEquals("血红蛋白 | 132\n白细胞 | 6.1", text)
    }

    @Test
    fun adjacentChineseIsNotPaddedWithSpaces() {
        val text = RecognizedTextLayout.reconstruct(
            listOf(
                RecognizedFragment("血红蛋白", 0.05f, 0.30f, 0.10f, 0.03f),
                RecognizedFragment("浓度", 0.155f, 0.30f, 0.05f, 0.03f),
            ),
        )
        assertEquals("血红蛋白浓度", text)
    }

    @Test
    fun spacedChineseFieldsKeepTheirGap() {
        val text = RecognizedTextLayout.reconstruct(
            listOf(
                RecognizedFragment("性别：男", 0.05f, 0.10f, 0.12f, 0.03f),
                RecognizedFragment("年龄：34", 0.185f, 0.10f, 0.12f, 0.03f),
            ),
        )
        assertEquals("性别：男 年龄：34", text)
    }

    @Test
    fun mixedFontSizesShareARow() {
        val text = RecognizedTextLayout.reconstruct(
            listOf(
                RecognizedFragment("总胆固醇", 0.05f, 0.10f, 0.20f, 0.04f),
                RecognizedFragment("↓", 0.80f, 0.115f, 0.02f, 0.015f),
            ),
        )
        assertEquals("总胆固醇 | ↓", text)
    }

    @Test
    fun separateRowsStaySeparate() {
        val rows = RecognizedTextLayout.rows(
            from = listOf(
                RecognizedFragment("姓名：张三", 0.05f, 0.10f, 0.25f, 0.02f),
                RecognizedFragment("门诊号：0421", 0.05f, 0.13f, 0.30f, 0.02f),
            ),
        )
        assertEquals(2, rows.size)
    }

    @Test
    fun blankFragmentsAreDropped() {
        val text = RecognizedTextLayout.reconstruct(
            listOf(
                RecognizedFragment("  ", 0.05f, 0.10f, 0.05f, 0.02f),
                RecognizedFragment("体检报告", 0.05f, 0.20f, 0.20f, 0.02f),
            ),
        )
        assertEquals("体检报告", text)
    }

    @Test
    fun truncationCutsOnLineBoundaries() {
        val text = (1..10).joinToString("\n") { "第${it}行数据" }
        val (cut, dropped) = RecognizedTextLayout.truncated(text, maxCharacters = 20)
        assertFalse(cut.endsWith("第"))
        assertTrue(cut.split("\n").all { it.endsWith("行数据") })
        assertEquals(10 - cut.split("\n").size, dropped)
        assertTrue(dropped > 0)
    }

    @Test
    fun shortTextIsUntouched() {
        val (cut, dropped) = RecognizedTextLayout.truncated("血红蛋白 | 132", maxCharacters = 4000)
        assertEquals("血红蛋白 | 132", cut)
        assertEquals(0, dropped)
    }

    @Test
    fun oneVeryLongLineStillYieldsText() {
        val (cut, _) = RecognizedTextLayout.truncated("数".repeat(500), maxCharacters = 100)
        assertEquals(100, cut.length)
    }
}
