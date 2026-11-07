package app.peermesh.beta

import org.junit.Assert.assertEquals
import org.junit.Test

class AppVersionComparatorTest {
    @Test
    fun `compares semantic versions`() {
        assertEquals(0, AppVersionComparator.compare("0.9.3", "0.9.3"))
        assertEquals(-1, AppVersionComparator.compare("0.9.2", "0.9.3"))
        assertEquals(1, AppVersionComparator.compare("0.10.0", "0.9.9"))
    }

    @Test
    fun `ignores flavor suffixes`() {
        assertEquals(0, AppVersionComparator.compare("0.9.3-emergency", "0.9.3"))
        assertEquals(-1, AppVersionComparator.compare("0.9.2-official", "0.9.3"))
    }
}
