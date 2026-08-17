package com.pinapia.vana.checkin

import com.pinapia.vana.health.DayPeriod
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CheckInSchedulerTest {
    @Test
    fun missingHealthSituationDoesNotClaimDataWasSynced() = runBlocking {
        val morning = CheckInScheduler.content(DayPeriod.MORNING, situation = null)
        val evening = CheckInScheduler.content(DayPeriod.EVENING, situation = null)

        assertNull(morning.topicId)
        assertNull(evening.topicId)
        assertFalse(morning.body.contains("睡眠数据"))
        assertFalse(evening.body.contains("活动量"))
    }
}
