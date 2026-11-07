package app.peermesh.core.cache

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayCacheTest {
    @Test
    fun seen_tracksWithinTtl() {
        val cache = ReplayCache(ttlSeconds = 10)
        assertFalse(cache.seen("gid:event", nowSeconds = 100))
        assertTrue(cache.seen("gid:event", nowSeconds = 105))
    }

    @Test
    fun seen_expiresAfterTtl() {
        val cache = ReplayCache(ttlSeconds = 10)
        assertFalse(cache.seen("gid:event", nowSeconds = 100))
        assertFalse(cache.seen("gid:event", nowSeconds = 111))
    }
}
