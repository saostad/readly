package app.readly.feeds

import org.junit.Assert.assertThrows
import org.junit.Test

class RefreshScheduleTest {
    @Test fun acceptsAndroidPeriodicMinimumAndUserMaximum() {
        RefreshSchedule(minutes = 15).validate()
        RefreshSchedule(minutes = 1440).validate()
        RefreshSchedule().validate()
        for (minutes in listOf(0, 14, 1441)) assertThrows(IllegalArgumentException::class.java) { RefreshSchedule(minutes = minutes).validate() }
    }
}
