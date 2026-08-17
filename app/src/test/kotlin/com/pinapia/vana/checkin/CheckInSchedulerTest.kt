package com.pinapia.vana.checkin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test

class CheckInSchedulerTest {
    @Test
    fun genericCheckInsDoNotClaimDeviceDataWasSynced() = runBlocking {
        val morning = CheckInScheduler.content(DayPeriod.MORNING)
        val evening = CheckInScheduler.content(DayPeriod.EVENING)

        assertFalse(morning.body.contains("睡眠数据"))
        assertFalse(evening.body.contains("活动量"))
    }
}
