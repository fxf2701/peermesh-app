package app.peermesh.core.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeSourceTaggerTest {
    @Test
    fun tagMesh_appendsSource() {
        val json = "{\"v\":1}"
        val tagged = EnvelopeSourceTagger.tagMesh(json)
        assertTrue(tagged.contains("\"src\":\"mesh\""))
    }

    @Test
    fun hasServerTag_detectsServer() {
        val json = "{\"v\":1,\"src\":\"server\"}"
        assertTrue(EnvelopeSourceTagger.hasServerTag(json))
        assertFalse(EnvelopeSourceTagger.hasServerTag("{\"v\":1}"))
    }
}
