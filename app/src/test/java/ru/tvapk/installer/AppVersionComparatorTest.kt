package ru.tvapk.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppVersionComparatorTest {
    @Test
    fun comparesOrdinaryVersionsNumerically() {
        assertEquals(1, AppVersionComparator.compare("0.9.130", "0.9.129"))
        assertEquals(-1, AppVersionComparator.compare("1.0.0", "1.0.1"))
        assertEquals(0, AppVersionComparator.compare("v1.2", "1.2.0"))
    }

    @Test
    fun comparesComponentsLargerThanIntWithoutOverflow() {
        assertEquals(
            1,
            AppVersionComparator.compare("999999999999999999999999.0", "2147483647.999")
        )
        assertEquals(
            -1,
            AppVersionComparator.compare("1.999999999999999999999", "2.0")
        )
    }

    @Test
    fun treatsLeadingZeroesAsTheSameNumericVersion() {
        assertEquals(0, AppVersionComparator.compare("v01.002.0003", "1.2.3"))
    }

    @Test
    fun rejectsMalformedVersionsInsteadOfTreatingThemAsZero() {
        assertThrows(IllegalArgumentException::class.java) {
            AppVersionComparator.compare("1.x.3", "1.2.3")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppVersionComparator.compare("1.2.3", "1.2.3.4.5")
        }
    }
}
