package com.pinapia.vana.vision

import com.pinapia.vana.medications.MedicationDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoImagePolicyTest {
    @Test
    fun askWhenNoTextOnlyOffersBlankPhotos() {
        val policy = PhotoImagePolicy.ASK_WHEN_NO_TEXT
        assertTrue(policy.offers(hasText = false))
        assertFalse(policy.offers(hasText = true))
        assertFalse(policy.sendsImageByDefault)
    }

    @Test
    fun textOnlyNeverOffers() {
        val policy = PhotoImagePolicy.TEXT_ONLY
        assertFalse(policy.offers(hasText = false))
        assertFalse(policy.offers(hasText = true))
        assertFalse(policy.sendsImageByDefault)
    }

    @Test
    fun alwaysOffersEveryPhoto() {
        val policy = PhotoImagePolicy.ALWAYS
        assertTrue(policy.offers(hasText = false))
        assertTrue(policy.offers(hasText = true))
        assertTrue(policy.sendsImageByDefault)
    }

    @Test
    fun modelTextWithoutImageSaysCannotSee() {
        val attachment = ChatAttachment(text = "", sendsImage = false)
        val text = ChatAttachment.modelText("", listOf(attachment))
        assertTrue(text.contains("看不了图像本身"))
        assertFalse(text.contains("原图直接附"))
    }

    @Test
    fun modelTextWithImageAsksModelToLook() {
        val attachment = ChatAttachment(
            text = "",
            sendsImage = true,
            imagePayload = "abc",
        )
        val text = ChatAttachment.modelText("", listOf(attachment))
        assertTrue(text.contains("原图直接附"))
        assertTrue(text.contains("直接看图回答"))
        assertFalse(text.contains("看不了图像本身"))
    }

    @Test
    fun guessedMedicationNameTakesFirstCell() {
        val name = MedicationDraft.guessedName(
            from = "褪黑素 | 3mg | 睡前\n用法用量 每日一次",
        )
        assertEquals("褪黑素", name)
    }
}
