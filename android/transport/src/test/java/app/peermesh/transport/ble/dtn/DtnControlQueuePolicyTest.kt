package app.peermesh.transport.ble.dtn

import org.junit.Assert.assertEquals
import org.junit.Test

class DtnControlQueuePolicyTest {
    @Test fun matchingHelloPrecedesDependencyWantButWantPrecedesBulkInventory() {
        val hello = candidate("hello", DtnControlTypes.HELLO, "g", "s")
        val want = candidate("want", DtnControlTypes.INV_WANT, "g", "s")
        val have = candidate("have", DtnControlTypes.INV_HAVE, "g", "other")

        assertEquals(hello, DtnControlQueuePolicy.select(listOf(have, hello, want)))
        assertEquals(want, DtnControlQueuePolicy.select(listOf(have, want)))
    }

    @Test fun unrelatedHelloCannotStarveDependencyWant() {
        val unrelatedHello = candidate("hello-other", DtnControlTypes.HELLO, "other", "s2")
        val want = candidate("want", DtnControlTypes.INV_WANT, "g", "s1")
        val have = candidate("have", DtnControlTypes.INV_HAVE, "g", "s1")

        assertEquals(want, DtnControlQueuePolicy.select(listOf(unrelatedHello, have, want)))
    }

    @Test fun dataKeepsPriorityOverWantAfterSessionHelloIsGone() {
        val want = candidate("want", DtnControlTypes.INV_WANT, "g", "s")
        val data = candidate("data", DtnControlTypes.DATA, "g", "s")

        assertEquals(data, DtnControlQueuePolicy.select(listOf(want, data)))
    }

    private fun candidate(itemId: String, type: String, gid: String?, sessionId: String?) =
        DtnControlQueuePolicy.Candidate(itemId, type, gid, sessionId)
}
